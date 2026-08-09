package com.fabwatch.sensor.service;

import com.fabwatch.common.event.AlarmRaisedEvent;
import com.fabwatch.common.event.EquipmentStatusChangedEvent;
import com.fabwatch.sensor.dto.SensorStreamPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
 */
@Slf4j
@Service
public class SensorStreamService {

    /** 30분 — Access 토큰 만료(30분)와 맞춰 연결도 함께 끊고 재연결 시 재인증되게 한다 (docs/11 §4). */
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<Long, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    private record Subscription(SseEmitter emitter, Long equipmentId) {

        boolean interestedIn(Long targetEquipmentId) {
            return equipmentId == null || equipmentId.equals(targetEquipmentId);
        }
    }

    /** equipmentId가 null이면 전체 라인 구독 */
    public SseEmitter subscribe(Long equipmentId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        long id = sequence.incrementAndGet();
        subscriptions.put(id, new Subscription(emitter, equipmentId));

        emitter.onCompletion(() -> subscriptions.remove(id));
        emitter.onTimeout(() -> {
            subscriptions.remove(id);
            emitter.complete();
        });
        emitter.onError(e -> subscriptions.remove(id));

        // 첫 바이트를 즉시 흘려보내야 프록시/브라우저가 연결을 확립한다
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            subscriptions.remove(id);
            emitter.completeWithError(e);
        }
        log.debug("SSE 구독 시작: id={}, equipmentId={}, 총 {}건", id, equipmentId, subscriptions.size());
        return emitter;
    }

    public void publishSensor(SensorStreamPayload payload) {
        broadcast("sensor", payload, payload.equipmentId());
    }

    /** alarm 도메인 → SSE. 도메인 간 직접 호출 대신 스프링 이벤트로 받는다. */
    @EventListener
    public void onAlarmRaised(AlarmRaisedEvent event) {
        broadcast("alarm", event, event.equipmentId());
    }

    /** equipment 도메인 → SSE. */
    @EventListener
    public void onStatusChanged(EquipmentStatusChangedEvent event) {
        broadcast("status", event, event.equipmentId());
    }

    /** 30초 heartbeat (docs/06 §3) */
    @Scheduled(fixedRate = 30_000)
    public void heartbeat() {
        subscriptions.forEach((id, subscription) -> {
            try {
                subscription.emitter().send(SseEmitter.event().comment("heartbeat"));
            } catch (Exception e) {
                remove(id, subscription);
            }
        });
    }

    public int subscriberCount() {
        return subscriptions.size();
    }

    private void broadcast(String eventName, Object payload, Long equipmentId) {
        subscriptions.forEach((id, subscription) -> {
            if (!subscription.interestedIn(equipmentId)) {
                return;
            }
            try {
                subscription.emitter().send(SseEmitter.event().name(eventName).data(payload));
            } catch (Exception e) {
                // 끊긴 클라이언트는 조용히 정리한다 (정상 상황 — 탭 닫힘 등)
                remove(id, subscription);
            }
        });
    }

    private void remove(Long id, Subscription subscription) {
        subscriptions.remove(id);
        try {
            subscription.emitter().complete();
        } catch (Exception ignored) {
            // 이미 종료된 emitter — 무시
        }
    }
}
