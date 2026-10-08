package com.fabwatch.sensor.service;

import com.fabwatch.common.event.ThresholdExceededEvent;
import com.fabwatch.sensor.config.SseProperties;
import com.fabwatch.sensor.dto.SensorStreamPayload;
import com.fabwatch.sensor.repository.SensorDataRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 수집 틱의 트랜잭션 경계 (안정성 감사 H-3): 저장 커밋 → (그 뒤) SSE 발행·임계치 이벤트, 센서별 오류 격리,
 * 블로킹 구독자가 있어도 수집이 제한 시간 안에 반환.
 */
class SensorIngestionServiceTest {

    private static final Instant AT = Instant.parse("2026-10-01T00:00:00Z");

    private final SensorDataSource dataSource = mock(SensorDataSource.class);
    private final SensorQueryService queryService = mock(SensorQueryService.class);
    private final SensorDataRepository repository = mock(SensorDataRepository.class);
    private final SensorStreamService streamService = mock(SensorStreamService.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

    private SensorIngestionService service(SensorStreamService stream) {
        return new SensorIngestionService(dataSource, queryService, repository, stream, publisher,
                mock(PlatformTransactionManager.class));
    }

    /** 센서 n개: 홀수 id는 critHigh 초과 값(CRITICAL), 짝수 id는 정상 */
    private void givenSensors(int count) {
        List<SensorSpec> specs = new ArrayList<>();
        List<SensorReading> readings = new ArrayList<>();
        for (long id = 1; id <= count; id++) {
            specs.add(new SensorSpec(id, 100 + id, "TEMP", "C", new BigDecimal("50"), BigDecimal.ONE,
                    null, new BigDecimal("70"), null, new BigDecimal("80")));
            readings.add(new SensorReading(id, 100 + id, id % 2 == 1 ? new BigDecimal("90") : new BigDecimal("50"), AT));
        }
        when(queryService.findAllSpecs()).thenReturn(specs);
        when(dataSource.read(AT)).thenReturn(readings);
    }

    @Test
    @DisplayName("★ 원본 저장(saveAll)이 SSE 발행·임계치 이벤트보다 먼저 일어난다 (커밋 이후 발행)")
    void 저장_후_발행_순서() {
        givenSensors(1); // 센서 1개(CRITICAL)

        service(streamService).ingest(AT);

        InOrder order = inOrder(repository, streamService, publisher);
        order.verify(repository).saveAll(any());
        order.verify(streamService).publishSensor(any(SensorStreamPayload.class));
        order.verify(publisher).publishEvent(any(ThresholdExceededEvent.class));
    }

    @Test
    @DisplayName("★ 저장이 실패하면(트랜잭션 롤백) SSE·임계치 이벤트는 아무것도 나가지 않고 예외가 전파된다")
    void 저장_실패시_발행_없음() {
        givenSensors(3);
        doThrow(new IllegalStateException("DB 오류")).when(repository).saveAll(any());

        assertThatThrownBy(() -> service(streamService).ingest(AT)).isInstanceOf(IllegalStateException.class);

        verify(streamService, never()).publishSensor(any());
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("★ 한 센서의 임계치 이벤트 처리 예외는 나머지 센서의 발행·이벤트에 영향을 주지 않고, 저장 건수는 그대로 반환된다")
    void 센서별_오류_격리() {
        givenSensors(5); // CRITICAL: 1, 3, 5
        doThrow(new IllegalStateException("알람 처리 실패")).when(publisher)
                .publishEvent(argThat((Object e) -> e instanceof ThresholdExceededEvent t && t.sensorId() == 1L));

        int saved = service(streamService).ingest(AT);

        assertThat(saved).isEqualTo(5);
        verify(streamService, times(5)).publishSensor(any(SensorStreamPayload.class));
        verify(publisher, times(3)).publishEvent(any(ThresholdExceededEvent.class)); // 1(실패), 3, 5 모두 시도
    }

    @Test
    @DisplayName("한 센서의 SSE 발행 예외도 다른 센서를 막지 않는다")
    void SSE_발행_예외_격리() {
        givenSensors(3);
        doThrow(new IllegalStateException("SSE 오류")).when(streamService)
                .publishSensor(argThat(p -> p.sensorId() == 1L));

        assertThat(service(streamService).ingest(AT)).isEqualTo(3);

        verify(streamService, times(3)).publishSensor(any(SensorStreamPayload.class));
        verify(publisher, times(2)).publishEvent(any(ThresholdExceededEvent.class)); // 센서 1, 3 (SSE 실패와 무관하게 판정)
    }

    @Test
    @DisplayName("삭제된 센서(spec 없음)의 읽기 값은 저장·발행하지 않는다")
    void 삭제된_센서_건너뜀() {
        givenSensors(2);
        when(queryService.findAllSpecs()).thenReturn(List.of(new SensorSpec(1L, 101L, "TEMP", "C",
                new BigDecimal("50"), BigDecimal.ONE, null, new BigDecimal("70"), null, new BigDecimal("80"))));

        assertThat(service(streamService).ingest(AT)).isEqualTo(1);
        verify(streamService, times(1)).publishSensor(any(SensorStreamPayload.class));
    }

    @Test
    @DisplayName("읽을 값이 없으면 저장도 발행도 하지 않는다")
    void 빈_틱() {
        when(dataSource.read(AT)).thenReturn(List.of());

        assertThat(service(streamService).ingest(AT)).isZero();
        verify(repository, never()).saveAll(any());
    }

    @Test
    @DisplayName("★ 블로킹 구독자가 있어도 수집(ingest)은 제한 시간 안에 반환한다 (실제 비동기 SensorStreamService 사용)")
    void 블로킹_구독자가_있어도_수집은_반환() throws Exception {
        givenSensors(18);
        CountDownLatch release = new CountDownLatch(1);
        SensorStreamService realStream = new SensorStreamService(
                new SseProperties(10, 5, true, 1_000, 60_000L, 2), timeout ->
                new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(timeout) {
                    private boolean connected;

                    @Override
                    public void send(SseEventBuilder builder) throws java.io.IOException {
                        if (!connected) { // subscribe()의 첫 comment는 통과시키고 이후 전송부터 막는다
                            connected = true;
                            return;
                        }
                        try {
                            release.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
        try {
            realStream.subscribe(null, 1L);

            long start = System.nanoTime();
            for (int tick = 0; tick < 10; tick++) {
                assertThat(service(realStream).ingest(AT)).isEqualTo(18);
            }
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(elapsed).as("10틱(센서 180건) 수집 소요").isLessThan(Duration.ofSeconds(2));
        } finally {
            release.countDown();
            realStream.shutdown();
        }
    }
}
