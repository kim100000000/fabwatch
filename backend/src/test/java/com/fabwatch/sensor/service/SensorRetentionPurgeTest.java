package com.fabwatch.sensor.service;

import com.fabwatch.sensor.config.SensorRetentionProperties;
import com.fabwatch.sensor.repository.SensorData1mRepository;
import com.fabwatch.sensor.repository.SensorDataRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 원본 7일 삭제 배치의 청크 반복·종료 조건 단위 테스트 (안정성 감사 H-2, docs/15 §7). 저장소는 목으로 대체한다. */
class SensorRetentionPurgeTest {

    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant THRESHOLD = NOW.minus(Duration.ofDays(7));

    private final SensorDataRepository dataRepository = mock(SensorDataRepository.class);
    private final SensorData1mRepository oneMinuteRepository = mock(SensorData1mRepository.class);

    private SensorAggregationService service(int chunkSize) {
        return new SensorAggregationService(dataRepository, oneMinuteRepository,
                new SensorRetentionProperties(chunkSize, 0L), mock(PlatformTransactionManager.class));
    }

    @Test
    @DisplayName("설정 기본값: 청크 10,000행 / 청크 간 100ms, 비정상 값은 기본값으로, 상한 100,000")
    void 설정_기본값() {
        SensorRetentionProperties defaults = new SensorRetentionProperties(null, null);
        assertThat(defaults.chunkSize()).isEqualTo(10_000);
        assertThat(defaults.chunkPauseMs()).isEqualTo(100L);
        assertThat(new SensorRetentionProperties(0, -1L).chunkSize()).isEqualTo(10_000);
        assertThat(new SensorRetentionProperties(0, -1L).chunkPauseMs()).isEqualTo(100L);
        assertThat(new SensorRetentionProperties(5_000_000, 0L).chunkSize()).isEqualTo(100_000);
    }

    @Test
    @DisplayName("청크 미만으로 끝나면(마지막 청크가 부분) 거기서 종료 — 총합이 반환된다")
    void 부분_청크에서_종료() {
        when(dataRepository.deleteChunkByMeasuredAtBefore(any(), anyInt())).thenReturn(5, 5, 2);

        int total = service(5).purgeRawBefore(NOW);

        assertThat(total).isEqualTo(12);
        verify(dataRepository, times(3)).deleteChunkByMeasuredAtBefore(THRESHOLD, 5);
    }

    @Test
    @DisplayName("정확히 청크 배수면 빈 청크 1회를 더 돌고 종료한다 (무한 루프 없음)")
    void 정확한_배수에서_빈_청크로_종료() {
        when(dataRepository.deleteChunkByMeasuredAtBefore(any(), anyInt())).thenReturn(5, 5, 0);

        int total = service(5).purgeRawBefore(NOW);

        assertThat(total).isEqualTo(10);
        verify(dataRepository, times(3)).deleteChunkByMeasuredAtBefore(THRESHOLD, 5);
    }

    @Test
    @DisplayName("지울 것이 없으면 쿼리 1회로 끝난다")
    void 대상_없음() {
        when(dataRepository.deleteChunkByMeasuredAtBefore(any(), anyInt())).thenReturn(0);

        assertThat(service(5).purgeRawBefore(NOW)).isZero();
        verify(dataRepository, times(1)).deleteChunkByMeasuredAtBefore(THRESHOLD, 5);
    }

    @Test
    @DisplayName("기준 시각은 now - 7일이다")
    void 기준시각_7일() {
        when(dataRepository.deleteChunkByMeasuredAtBefore(any(), anyInt())).thenReturn(0);

        service(5).purgeRawBefore(NOW);

        verify(dataRepository).deleteChunkByMeasuredAtBefore(Instant.parse("2026-09-24T00:00:00Z"), 5);
    }

    @Test
    @DisplayName("스레드가 인터럽트되면 다음 청크로 가지 않고 즉시 중단한다 (인터럽트 상태 보존)")
    void 인터럽트_즉시_중단() {
        SensorAggregationService withPause = new SensorAggregationService(dataRepository, oneMinuteRepository,
                new SensorRetentionProperties(5, 10_000L), mock(PlatformTransactionManager.class));
        when(dataRepository.deleteChunkByMeasuredAtBefore(any(), anyInt())).thenReturn(5);
        Thread.currentThread().interrupt();

        int total = withPause.purgeRawBefore(NOW); // 첫 청크 삭제 후 대기 진입 시 InterruptedException

        assertThat(total).isEqualTo(5);
        assertThat(Thread.interrupted()).as("인터럽트 플래그가 보존되어야 한다").isTrue();
        verify(dataRepository, times(1)).deleteChunkByMeasuredAtBefore(THRESHOLD, 5);
        verify(dataRepository, never()).deleteChunkByMeasuredAtBefore(THRESHOLD, 0);
    }

    @Test
    @DisplayName("청크 삭제 중 예외는 전파된다 (이미 지운 청크는 유지, 스케줄러가 기록하고 다음 날 이어서 지움)")
    void 예외_전파() {
        when(dataRepository.deleteChunkByMeasuredAtBefore(any(), anyInt()))
                .thenReturn(5).thenThrow(new IllegalStateException("락 타임아웃"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service(5).purgeRawBefore(NOW))
                .isInstanceOf(IllegalStateException.class);
    }
}
