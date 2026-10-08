package com.fabwatch.sensor.service;

import com.fabwatch.common.event.AlarmRaisedEvent;
import com.fabwatch.common.event.EquipmentStatusChangedEvent;
import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import com.fabwatch.sensor.config.SseProperties;
import com.fabwatch.sensor.dto.SensorStreamPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongFunction;

/**
 * SSE 브로드캐스터 (docs/06 §3 GET /stream/sensors).
 *
 * 이벤트 3종만 내보낸다:
 * - `sensor` : 2초 주기 센서 값 (payload = SensorStreamPayload)
 * - `alarm`  : 신규 알람 (payload = AlarmRaisedEvent, alarm 도메인이 발행한 스프링 이벤트를 그대로 전달)
 * - `status` : 설비 상태 변경 (payload = EquipmentStatusChangedEvent)
 *
 * 그 외에 30초마다 SSE 주석(`:heartbeat`)을 보낸다 — 프록시 idle timeout 방지용이며
 * EventSource는 주석을 무시하므로 프론트에서 별도 처리가 필요 없다 (이벤트 종류는 3종 유지).
 *
 * equipmentId를 지정하면 해당 설비 이벤트만, 생략하면 전체를 받는다(메인 대시보드용).
 *
 * 연결 상한 (보안 감사 M-3): 전체 {@code fabwatch.sse.max-connections}(기본 200) 초과 시 새 연결 거절(429 RATE_LIMITED),
 * 사용자당 {@code fabwatch.sse.max-per-user}(기본 5) 초과 시 그 사용자의 가장 오래된 연결을 닫고 새 연결 허용.
 * 연결 수는 별도 카운터 없이 구독 맵의 크기로 센다 — 맵에서 빠지는 모든 경로(완료·타임아웃·오류·전송 실패·상한 퇴출)가
 * 곧 감소이므로 카운터가 어긋나(누수) 신규 연결이 영영 거절되는 일이 없다.
 *
 * 전송 구조 (안정성 감사 H-3): 이벤트는 수집 스레드에서 직접 {@code emitter.send}하지 않고 구독자별 유한 큐
 * ({@code queue-capacity})에 넣은 뒤 전용 스레드풀({@code sender-threads})이 구독자별로 직렬 전송한다.
 * 느린 클라이언트가 소켓 쓰기에서 막혀도 수집 스레드·DB 커넥션은 영향을 받지 않는다.
 * 큐가 가득 차거나 한 번의 전송이 {@code send-timeout-ms}를 넘게 막혀 있으면 그 구독자만 연결을 끊고 맵에서 제거한다.
 * {@code fabwatch.sse.async=false}면 호출 스레드에서 동기 전송한다(테스트 전용).
 * 한계: 막힌 전송은 인터럽트할 수 없어 소켓 쓰기 타임아웃(Tomcat connection-timeout)까지 전송 스레드 1개를 점유한다.
 *
 * alarm/status 이벤트는 {@code @TransactionalEventListener(AFTER_COMMIT)}로 받는다 — 발행한 트랜잭션이 롤백되면
 * 클라이언트에 나가지 않고(유령 알람/토스트 방지), 나갈 때는 이미 커밋돼 있어 즉시 재조회해도 데이터가 보인다.
 *
 * // SCALE: 연결 수·사용자별 상한은 인스턴스 로컬 값이다(단일 인스턴스 전제).
 */
@Slf4j
@Service
public class SensorStreamService {

    /** 30분 — Access 토큰 만료(30분)와 맞춰 연결도 함께 끊고 재연결 시 재인증되게 한다 (docs/11 §4). */
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    /** 구독 id는 단조 증가 — 숫자가 작을수록 오래된 연결 */
    private final Map<Long, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final SseProperties properties;
    private final LongFunction<SseEmitter> emitterFactory;
    /** 비동기 전송 풀 — 동기 모드(async=false)면 null */
    private final ExecutorService sender;
    private final long sendTimeoutNanos;
    /** subscribe의 "상한 판정 → 퇴출 → 등록"을 한 덩어리로 만들기 위한 락 (구독 요청은 드물어 경합이 없다) */
    private final Object subscribeLock = new Object();

    @Autowired
    public SensorStreamService(SseProperties properties) {
        this(properties, SseEmitter::new);
    }

    /** 테스트에서 emitter를 가짜로 바꿔 끼우기 위한 생성자 */
    SensorStreamService(SseProperties properties, LongFunction<SseEmitter> emitterFactory) {
        this.properties = properties;
        this.emitterFactory = emitterFactory;
        this.sendTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(properties.sendTimeoutMs());
        this.sender = properties.async() ? newSenderPool(properties.senderThreads()) : null;
    }

    private static ExecutorService newSenderPool(int threads) {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "sse-sender-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        // 작업(drain)은 구독자당 최대 1개만 대기하므로 무제한 큐여도 길이가 구독자 수(상한 200)를 넘지 않는다
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), factory);
    }

    @PreDestroy
    void shutdown() {
        if (sender != null) {
            sender.shutdownNow();
        }
    }

    /** 전송할 이벤트 한 건. name이 null이면 SSE 주석(heartbeat). payload는 불변 record라 구독자 간 공유해도 안전하다. */
    private record Outbound(String name, Object payload, String comment) {

        static Outbound event(String name, Object payload) {
            return new Outbound(name, payload, null);
        }

        static Outbound comment(String comment) {
            return new Outbound(null, null, comment);
        }

        /** SseEventBuilder는 build() 1회용 상태를 가지므로 전송 시점마다 새로 만든다 */
        SseEmitter.SseEventBuilder toBuilder() {
            return name == null ? SseEmitter.event().comment(comment) : SseEmitter.event().name(name).data(payload);
        }
    }

    private static final class Subscription {

        final long id;
        final SseEmitter emitter;
        final Long equipmentId;
        final Long userId;
        /** 전송 대기 이벤트 (최대 queue-capacity) */
        final Queue<Outbound> queue = new ConcurrentLinkedQueue<>();
        final AtomicInteger queued = new AtomicInteger();
        /** 전송 스레드가 이 구독자의 큐를 비우는 중인지 — 구독자당 동시에 1개 스레드만 전송한다(순서 보장) */
        final AtomicBoolean draining = new AtomicBoolean();
        /** 현재 전송 시작 시각(nanoTime), 전송 중이 아니면 0 — 오래 막힌 전송 감지용 */
        volatile long sendStartedNanos;

        Subscription(long id, SseEmitter emitter, Long equipmentId, Long userId) {
            this.id = id;
            this.emitter = emitter;
            this.equipmentId = equipmentId;
            this.userId = userId;
        }

        boolean interestedIn(Long targetEquipmentId) {
            return equipmentId == null || equipmentId.equals(targetEquipmentId);
        }
    }

    /**
     * equipmentId가 null이면 전체 라인 구독.
     *
     * @param userId 연결 소유자 — 사용자별 상한 판정용
     * @throws BusinessException RATE_LIMITED — 전체 연결 상한 초과
     */
    public SseEmitter subscribe(Long equipmentId, Long userId) {
        long id;
        SseEmitter emitter;
        synchronized (subscribeLock) {
            evictOldestOfUserIfFull(userId);
            if (subscriptions.size() >= properties.maxConnections()) {
                log.warn("SSE 전체 연결 상한 초과로 거절: 현재 {}건", subscriptions.size());
                throw new BusinessException(ErrorCode.RATE_LIMITED,
                        "동시 실시간 연결 한도를 초과했습니다. 잠시 후 다시 시도하세요.");
            }
            emitter = emitterFactory.apply(TIMEOUT_MS);
            id = sequence.incrementAndGet();
            subscriptions.put(id, new Subscription(id, emitter, equipmentId, userId));
        }

        long subscriptionId = id;
        emitter.onCompletion(() -> subscriptions.remove(subscriptionId));
        emitter.onTimeout(() -> {
            subscriptions.remove(subscriptionId);
            emitter.complete();
        });
        emitter.onError(e -> subscriptions.remove(subscriptionId));

        // 첫 바이트를 즉시 흘려보내야 프록시/브라우저가 연결을 확립한다
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            subscriptions.remove(subscriptionId);
            emitter.completeWithError(e);
        }
        log.debug("SSE 구독 시작: id={}, equipmentId={}, 총 {}건", subscriptionId, equipmentId, subscriptions.size());
        return emitter;
    }

    /** 해당 사용자의 연결이 상한 이상이면 가장 오래된 것부터 닫아 새 연결 1개 자리를 만든다. */
    private void evictOldestOfUserIfFull(Long userId) {
        if (userId == null) {
            return;
        }
        while (countOf(userId) >= properties.maxPerUser()) {
            Long oldest = subscriptions.entrySet().stream()
                    .filter(entry -> userId.equals(entry.getValue().userId))
                    .map(Map.Entry::getKey)
                    .min(Long::compare)
                    .orElse(null);
            if (oldest == null) {
                return;
            }
            Subscription evicted = subscriptions.get(oldest);
            if (evicted != null) {
                remove(oldest, evicted);
                log.debug("SSE 사용자 연결 상한 — 가장 오래된 연결 종료: userId={}", userId);
            }
        }
    }

    private long countOf(Long userId) {
        return subscriptions.values().stream().filter(s -> userId.equals(s.userId)).count();
    }

    public void publishSensor(SensorStreamPayload payload) {
        broadcast("sensor", payload, payload.equipmentId());
    }

    /**
     * alarm 도메인 → SSE. 도메인 간 직접 호출 대신 스프링 이벤트로 받는다.
     * 알람이 DB에 커밋된 뒤에만 내보낸다(롤백되면 발행되지 않음). 트랜잭션 밖에서 발행되면 즉시 실행(fallbackExecution).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAlarmRaised(AlarmRaisedEvent event) {
        broadcast("alarm", event, event.equipmentId());
    }

    /** equipment 도메인 → SSE. 상태 변경이 커밋된 뒤에만 내보낸다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onStatusChanged(EquipmentStatusChangedEvent event) {
        broadcast("status", event, event.equipmentId());
    }

    /** 30초 heartbeat (docs/06 §3) — 일반 이벤트와 같은 큐로 보내 스케줄러 스레드가 느린 클라이언트에 막히지 않게 한다 */
    @Scheduled(fixedRate = 30_000)
    public void heartbeat() {
        Outbound heartbeat = Outbound.comment("heartbeat");
        subscriptions.values().forEach(subscription -> dispatch(subscription, heartbeat));
    }

    public int subscriberCount() {
        return subscriptions.size();
    }

    private void broadcast(String eventName, Object payload, Long equipmentId) {
        Outbound outbound = Outbound.event(eventName, payload);
        for (Subscription subscription : subscriptions.values()) {
            if (subscription.interestedIn(equipmentId)) {
                dispatch(subscription, outbound);
            }
        }
    }

    /** 구독자 1명에게 이벤트 1건을 넘긴다. 비동기 모드에서는 큐에 넣기만 하고 즉시 반환한다. */
    private void dispatch(Subscription subscription, Outbound outbound) {
        if (sender == null) {
            sendNow(subscription, outbound);
            return;
        }
        long started = subscription.sendStartedNanos;
        if (started != 0 && System.nanoTime() - started > sendTimeoutNanos) {
            evictSlow(subscription, "전송 지연(" + properties.sendTimeoutMs() + "ms 초과)");
            return;
        }
        if (subscription.queued.get() >= properties.queueCapacity()) {
            evictSlow(subscription, "대기 큐 포화(" + properties.queueCapacity() + "건)");
            return;
        }
        subscription.queue.add(outbound);
        subscription.queued.incrementAndGet();
        scheduleDrain(subscription);
    }

    /** 동기 전송 — 끊긴 클라이언트는 조용히 정리한다 (정상 상황 — 탭 닫힘 등) */
    private void sendNow(Subscription subscription, Outbound outbound) {
        try {
            subscription.emitter.send(outbound.toBuilder());
        } catch (Exception e) {
            remove(subscription.id, subscription);
        }
    }

    private void scheduleDrain(Subscription subscription) {
        if (!subscription.draining.compareAndSet(false, true)) {
            return; // 이미 전송 스레드가 큐를 비우는 중 — 그 루프가 새 이벤트도 가져간다
        }
        try {
            sender.execute(() -> drain(subscription));
        } catch (RejectedExecutionException e) {
            subscription.draining.set(false); // 종료 중
        }
    }

    private void drain(Subscription subscription) {
        try {
            Outbound next;
            while ((next = subscription.queue.poll()) != null) {
                subscription.queued.decrementAndGet();
                if (!subscriptions.containsKey(subscription.id)) {
                    subscription.queue.clear(); // 이미 제거된 연결 — 남은 이벤트 폐기
                    subscription.queued.set(0);
                    return;
                }
                subscription.sendStartedNanos = System.nanoTime();
                try {
                    subscription.emitter.send(next.toBuilder());
                } catch (Exception e) {
                    remove(subscription.id, subscription); // 끊긴 클라이언트
                    return;
                } finally {
                    subscription.sendStartedNanos = 0;
                }
            }
        } finally {
            subscription.draining.set(false);
            // 루프 종료 직후 들어온 이벤트가 있으면 다시 예약한다(경쟁 보정)
            if (!subscription.queue.isEmpty() && subscriptions.containsKey(subscription.id)) {
                scheduleDrain(subscription);
            }
        }
    }

    /** 느린 구독자 퇴출 — 맵에서 먼저 빼서 더 이상 이벤트가 쌓이지 않게 하고, 연결 종료는 전송 풀에서 한다(막힐 수 있음). */
    private void evictSlow(Subscription subscription, String reason) {
        if (!subscriptions.remove(subscription.id, subscription)) {
            return; // 이미 다른 경로에서 제거됨
        }
        subscription.queue.clear();
        subscription.queued.set(0);
        log.warn("SSE 느린 구독자 연결 종료: subscriptionId={}, 사유={}, 남은 연결 {}건",
                subscription.id, reason, subscriptions.size());
        Runnable close = () -> {
            try {
                subscription.emitter.complete();
            } catch (Exception ignored) {
                // 이미 종료된 emitter — 무시
            }
        };
        try {
            sender.execute(close);
        } catch (RejectedExecutionException ignored) {
            // 종료 중 — 무시
        }
    }

    private void remove(Long id, Subscription subscription) {
        subscriptions.remove(id);
        subscription.queue.clear();
        subscription.queued.set(0);
        try {
            subscription.emitter.complete();
        } catch (Exception ignored) {
            // 이미 종료된 emitter — 무시
        }
    }
}
