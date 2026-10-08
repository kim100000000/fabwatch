package com.fabwatch.sensor.service;

import com.fabwatch.common.event.EquipmentStatusChangedEvent;
import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import com.fabwatch.sensor.config.SseProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SSE 전역/사용자별 연결 상한과 구독 해제 시 카운터 정확성 (보안 감사 M-3) */
class SensorStreamServiceLimitTest {

    /** 콜백을 붙잡아 두고 전송 실패를 흉내 낼 수 있는 가짜 emitter (서블릿 핸들러 없이도 콜백 호출 가능) */
    static class FakeEmitter extends SseEmitter {
        Runnable completion;
        Runnable timeout;
        Consumer<Throwable> error;
        boolean failSend;
        boolean completed;

        FakeEmitter(long timeout) {
            super(timeout);
        }

        @Override
        public synchronized void onCompletion(Runnable callback) {
            this.completion = callback;
        }

        @Override
        public synchronized void onTimeout(Runnable callback) {
            this.timeout = callback;
        }

        @Override
        public synchronized void onError(Consumer<Throwable> callback) {
            this.error = callback;
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (failSend) {
                throw new IOException("끊긴 연결");
            }
        }

        @Override
        public synchronized void complete() {
            completed = true;
        }

        @Override
        public synchronized void completeWithError(Throwable ex) {
            completed = true;
        }
    }

    private final List<FakeEmitter> created = new ArrayList<>();

    private SensorStreamService service(int maxConnections, int maxPerUser) {
        return new SensorStreamService(new SseProperties(maxConnections, maxPerUser, false, null, null, null), timeout -> {
            FakeEmitter emitter = new FakeEmitter(timeout);
            created.add(emitter);
            return emitter;
        });
    }

    @Test
    @DisplayName("기본값: 전체 200 / 사용자당 5 / 비동기 / 큐 100 / 전송 타임아웃 5초 / 전송 스레드 8")
    void 기본값() {
        SseProperties defaults = new SseProperties(null, null, null, null, null, null);
        assertThat(defaults.maxConnections()).isEqualTo(200);
        assertThat(defaults.maxPerUser()).isEqualTo(5);
        assertThat(defaults.async()).isTrue();
        assertThat(defaults.queueCapacity()).isEqualTo(100);
        assertThat(defaults.sendTimeoutMs()).isEqualTo(5000L);
        assertThat(defaults.senderThreads()).isEqualTo(8);
    }

    @Test
    @DisplayName("사용자당 상한 초과 시 그 사용자의 가장 오래된 연결을 닫고 새 연결을 허용한다")
    void 사용자별_상한_퇴출() {
        SensorStreamService service = service(100, 3);
        for (int i = 0; i < 3; i++) {
            service.subscribe(null, 1L);
        }
        assertThat(service.subscriberCount()).isEqualTo(3);

        service.subscribe(null, 1L); // 4번째 — 가장 오래된(첫 번째) 연결 종료

        assertThat(service.subscriberCount()).isEqualTo(3);
        assertThat(created.get(0).completed).isTrue();
        assertThat(created.get(1).completed).isFalse();
        assertThat(created.get(2).completed).isFalse();
        assertThat(created.get(3).completed).isFalse();
    }

    @Test
    @DisplayName("사용자별 상한은 다른 사용자의 연결을 건드리지 않는다")
    void 사용자별_상한은_타인에게_영향_없음() {
        SensorStreamService service = service(100, 2);
        service.subscribe(null, 2L);
        service.subscribe(null, 1L);
        service.subscribe(null, 1L);
        service.subscribe(null, 1L); // user 1의 첫 연결만 퇴출

        assertThat(service.subscriberCount()).isEqualTo(3);
        assertThat(created.get(0).completed).isFalse(); // user 2
        assertThat(created.get(1).completed).isTrue();  // user 1의 가장 오래된 것
    }

    @Test
    @DisplayName("전체 상한 초과 시 새 연결은 429 RATE_LIMITED로 거절되고 emitter를 만들지 않는다")
    void 전체_상한_거절() {
        SensorStreamService service = service(2, 5);
        service.subscribe(null, 1L);
        service.subscribe(null, 2L);

        assertThatThrownBy(() -> service.subscribe(null, 3L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.RATE_LIMITED);
        assertThat(service.subscriberCount()).isEqualTo(2);
        assertThat(created).hasSize(2);
    }

    @Test
    @DisplayName("구독 해제(완료·타임아웃·오류) 시 연결 수가 정확히 감소하고, 이후 새 연결을 다시 받는다")
    void 해제_시_카운터_감소() {
        SensorStreamService service = service(3, 5);
        service.subscribe(null, 1L);
        service.subscribe(null, 2L);
        service.subscribe(null, 3L);
        assertThat(service.subscriberCount()).isEqualTo(3);

        created.get(0).completion.run();
        assertThat(service.subscriberCount()).isEqualTo(2);
        created.get(1).timeout.run();
        assertThat(service.subscriberCount()).isEqualTo(1);
        assertThat(created.get(1).completed).isTrue(); // 타임아웃은 emitter도 닫는다
        created.get(2).error.accept(new IOException("reset"));
        assertThat(service.subscriberCount()).isZero();

        // 같은 콜백이 중복 호출돼도 음수/이중 감소 없음(멱등)
        created.get(0).completion.run();
        assertThat(service.subscriberCount()).isZero();

        // 비어 있으므로 상한(3)까지 다시 받을 수 있다
        service.subscribe(null, 4L);
        service.subscribe(null, 5L);
        service.subscribe(null, 6L);
        assertThat(service.subscriberCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("전송 실패(끊긴 클라이언트)는 브로드캐스트·heartbeat에서 정리되어 카운터가 줄어든다")
    void 전송_실패_정리() {
        SensorStreamService service = service(10, 5);
        service.subscribe(null, 1L);
        service.subscribe(null, 2L);
        created.get(0).failSend = true;

        service.onStatusChanged(new EquipmentStatusChangedEvent(
                1L, "EQ-1", "RUN", "IDLE", "테스트", 1L, Instant.now()));
        assertThat(service.subscriberCount()).isEqualTo(1);

        created.get(1).failSend = true;
        service.heartbeat();
        assertThat(service.subscriberCount()).isZero();
    }

    @Test
    @DisplayName("연결·해제를 반복해도 카운터가 새지 않는다 (누수 없음)")
    void 반복_연결_해제_누수_없음() {
        SensorStreamService service = service(2, 2);
        for (int round = 0; round < 500; round++) {
            service.subscribe(null, 1L);
            service.subscribe(null, 2L);
            created.get(created.size() - 2).completion.run();
            created.get(created.size() - 1).completion.run();
        }
        assertThat(service.subscriberCount()).isZero();
        // 마지막에도 상한 안에서 정상 수락
        service.subscribe(null, 1L);
        service.subscribe(null, 2L);
        assertThat(service.subscriberCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("사용자 퇴출로 닫힌 연결의 늦은 완료 콜백이 새 연결의 카운트를 깎지 않는다")
    void 퇴출된_연결의_늦은_콜백은_무해() {
        SensorStreamService service = service(100, 1);
        service.subscribe(null, 1L);
        service.subscribe(null, 1L); // 첫 연결 퇴출
        assertThat(service.subscriberCount()).isEqualTo(1);

        created.get(0).completion.run(); // 퇴출된 emitter의 완료 콜백이 뒤늦게 도착

        assertThat(service.subscriberCount()).isEqualTo(1);
    }
}
