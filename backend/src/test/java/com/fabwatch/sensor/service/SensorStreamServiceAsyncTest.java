package com.fabwatch.sensor.service;

import com.fabwatch.common.event.EquipmentStatusChangedEvent;
import com.fabwatch.sensor.config.SseProperties;
import com.fabwatch.sensor.dto.SensorStreamPayload;
import com.fabwatch.common.event.SensorLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * SSE 비동기 전송 (안정성 감사 H-3): 느린/막힌 구독자가 있어도 발행(수집) 스레드는 즉시 반환하고,
 * 큐 포화·전송 지연 구독자는 연결이 끊겨 제거된다.
 */
class SensorStreamServiceAsyncTest {

    /** send가 호출되면 기록하고, 막기로 지정되면 해제될 때까지 블로킹하는 가짜 emitter */
    static class ControlledEmitter extends SseEmitter {
        final List<String> sent = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch firstSendStarted = new CountDownLatch(1);
        final AtomicBoolean completed = new AtomicBoolean();
        volatile boolean block;

        ControlledEmitter(long timeout) {
            super(timeout);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            firstSendStarted.countDown();
            if (block) {
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
            }
            sent.add(builder.build().stream().map(d -> String.valueOf(d.getData())).collect(Collectors.joining()));
        }

        @Override
        public synchronized void onCompletion(Runnable callback) {
        }

        @Override
        public synchronized void onTimeout(Runnable callback) {
        }

        @Override
        public synchronized void onError(java.util.function.Consumer<Throwable> callback) {
        }

        @Override
        public synchronized void complete() {
            completed.set(true);
        }

        @Override
        public synchronized void completeWithError(Throwable ex) {
            completed.set(true);
        }
    }

    private final List<ControlledEmitter> emitters = new ArrayList<>();
    private SensorStreamService service;

    @AfterEach
    void tearDown() {
        emitters.forEach(e -> e.release.countDown());
        if (service != null) {
            service.shutdown();
        }
    }

    private SensorStreamService service(int queueCapacity, long sendTimeoutMs) {
        service = new SensorStreamService(
                new SseProperties(100, 10, true, queueCapacity, sendTimeoutMs, 4), timeout -> {
            ControlledEmitter emitter = new ControlledEmitter(timeout);
            emitters.add(emitter);
            return emitter;
        });
        return service;
    }

    private static SensorStreamPayload payload(long n) {
        return new SensorStreamPayload(1L, 10L, "TEMP", "C", BigDecimal.valueOf(n), Instant.now(), SensorLevel.NORMAL);
    }

    private ControlledEmitter subscribe(boolean block) {
        service.subscribe(null, (long) emitters.size() + 1);
        ControlledEmitter emitter = emitters.get(emitters.size() - 1);
        emitter.sent.clear(); // 연결 확립용 첫 comment 제거
        emitter.block = block;
        return emitter;
    }

    @Test
    @DisplayName("★ 블로킹 구독자가 있어도 발행 호출은 제한 시간 안에 반환하고, 정상 구독자는 모든 이벤트를 순서대로 받는다")
    void 블로킹_구독자가_발행을_막지_않는다() {
        service(1_000, 60_000);
        ControlledEmitter stuck = subscribe(true);
        ControlledEmitter healthy = subscribe(false);

        long start = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            service.publishSensor(payload(i));
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).as("50건 발행에 걸린 시간(ms)").isLessThan(500);
        await().atMost(Duration.ofSeconds(5)).until(() -> healthy.sent.size() == 50);
        assertThat(healthy.sent.get(0)).contains("event:sensor");
        assertThat(stuck.sent).isEmpty(); // 막힌 구독자는 아직 아무것도 못 받았다
        assertThat(service.subscriberCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("★ 느린 구독자가 큐 용량을 넘기면 그 구독자만 연결이 끊겨 제거되고, 다른 구독자는 영향이 없다")
    void 큐_포화_구독자_제거() {
        service(3, 60_000);
        ControlledEmitter stuck = subscribe(true);
        ControlledEmitter healthy = subscribe(false);

        // 정상 구독자의 큐(3)가 버스트로 넘치지 않도록 한 건씩 도착을 확인하며 발행한다
        for (int i = 0; i < 10; i++) {
            service.publishSensor(payload(i));
            int expected = i + 1;
            await().atMost(Duration.ofSeconds(5)).until(() -> healthy.sent.size() == expected);
        }

        // 막힌 구독자: 전송 중 1건 + 큐 3건이 차고 다음 발행에서 퇴출
        await().atMost(Duration.ofSeconds(5)).until(() -> service.subscriberCount() == 1);
        await().atMost(Duration.ofSeconds(5)).until(() -> stuck.completed.get());
        assertThat(healthy.completed.get()).isFalse();
    }

    @Test
    @DisplayName("★ 한 번의 전송이 send-timeout을 넘게 막혀 있으면 다음 발행 시 그 구독자를 제거한다")
    void 전송_지연_타임아웃_제거() throws Exception {
        service(1_000, 50);
        ControlledEmitter stuck = subscribe(true);
        ControlledEmitter healthy = subscribe(false);

        service.publishSensor(payload(1)); // stuck 구독자의 전송 스레드가 여기서 막힌다
        assertThat(stuck.firstSendStarted.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(120); // 타임아웃(50ms) 경과

        service.publishSensor(payload(2));

        await().atMost(Duration.ofSeconds(5)).until(() -> service.subscriberCount() == 1);
        await().atMost(Duration.ofSeconds(5)).until(() -> stuck.completed.get());
        await().atMost(Duration.ofSeconds(5)).until(() -> healthy.sent.size() == 2);
    }

    @Test
    @DisplayName("전송이 실패(끊긴 클라이언트)하면 구독자가 정리되고 이후 이벤트는 폐기된다")
    void 전송_실패_정리() {
        service = new SensorStreamService(new SseProperties(100, 10, true, 100, 60_000L, 2), timeout -> {
            ControlledEmitter emitter = new ControlledEmitter(timeout) {
                @Override
                public void send(SseEventBuilder builder) throws IOException {
                    if (block) {
                        throw new IOException("끊긴 연결");
                    }
                }
            };
            emitters.add(emitter);
            return emitter;
        });
        service.subscribe(null, 1L);
        emitters.get(0).block = true;

        service.publishSensor(payload(1));

        await().atMost(Duration.ofSeconds(5)).until(() -> service.subscriberCount() == 0);
        assertThat(emitters.get(0).completed.get()).isTrue();
    }

    @Test
    @DisplayName("equipmentId 구독은 해당 설비 이벤트만 받는다 (비동기 경로에서도 필터 유지)")
    void 설비_필터() {
        service(100, 60_000);
        service.subscribe(10L, 1L);
        service.subscribe(99L, 2L);
        emitters.forEach(e -> e.sent.clear());

        service.onStatusChanged(new EquipmentStatusChangedEvent(10L, "EQ-10", "RUN", "DOWN", "t", null, Instant.now()));

        await().atMost(Duration.ofSeconds(5)).until(() -> emitters.get(0).sent.size() == 1);
        assertThat(emitters.get(0).sent.get(0)).contains("event:status");
        assertThat(emitters.get(1).sent).isEmpty();
    }

    @Test
    @DisplayName("heartbeat도 큐를 거치므로 막힌 구독자가 있어도 호출이 즉시 반환된다")
    void heartbeat_비블로킹() {
        service(100, 60_000);
        ControlledEmitter stuck = subscribe(true);
        ControlledEmitter healthy = subscribe(false);

        long start = System.nanoTime();
        service.heartbeat();
        service.heartbeat();
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(500);

        await().atMost(Duration.ofSeconds(5)).until(() -> healthy.sent.size() == 2);
        assertThat(healthy.sent.get(0)).contains("heartbeat");
        assertThat(stuck.sent).isEmpty();
    }
}
