package com.fabwatch.sensor.service;

import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import com.fabwatch.equipment.service.EquipmentQueryService;
import com.fabwatch.sensor.config.SensorQueryProperties;
import com.fabwatch.sensor.dto.SensorSeriesResponse;
import com.fabwatch.sensor.entity.Sensor;
import com.fabwatch.sensor.entity.SensorData1m;
import com.fabwatch.sensor.repository.SensorData1mRepository;
import com.fabwatch.sensor.repository.SensorDataRepository;
import com.fabwatch.sensor.repository.SensorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 센서 이력 조회 기간·포인트 상한 (안정성 감사 M-7, docs/06 §3) */
class SensorDataQueryRangeLimitTest {

    private static final Long EQUIPMENT_ID = 10L;

    private final SensorRepository sensorRepository = mock(SensorRepository.class);
    private final SensorDataRepository dataRepository = mock(SensorDataRepository.class);
    private final SensorData1mRepository oneMinuteRepository = mock(SensorData1mRepository.class);
    private final EquipmentQueryService equipmentQueryService = mock(EquipmentQueryService.class);

    @BeforeEach
    void setUp() {
        when(equipmentQueryService.existsById(EQUIPMENT_ID)).thenReturn(true);
        Sensor sensor = Sensor.builder().equipmentId(EQUIPMENT_ID).type(Sensor.Type.TEMP).unit("C")
                .baseValue(BigDecimal.TEN).noiseSigma(BigDecimal.ONE).build();
        ReflectionTestUtils.setField(sensor, "id", 1L);
        when(sensorRepository.findByEquipmentIdOrderByTypeAsc(EQUIPMENT_ID)).thenReturn(List.of(sensor));
        when(dataRepository.findBySensorIdAndMeasuredAtGreaterThanEqualAndMeasuredAtLessThanOrderByMeasuredAtAsc(
                anyLong(), any(), any())).thenReturn(List.of());
        when(oneMinuteRepository.findBySensorIdAndBucketAtGreaterThanEqualAndBucketAtLessThanOrderByBucketAtAsc(
                anyLong(), any(), any())).thenReturn(List.of());
    }

    private SensorDataQueryService service(int maxDays, int maxPoints) {
        return new SensorDataQueryService(sensorRepository, dataRepository, oneMinuteRepository,
                equipmentQueryService, new SensorQueryProperties(maxDays, maxPoints));
    }

    private static void assertValidation(Runnable action, String messagePart) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(e.getMessage()).contains(messagePart);
                });
    }

    @Test
    @DisplayName("설정 기본값: 31일 / 센서당 20,000 포인트")
    void 기본값() {
        assertThat(new SensorQueryProperties(null, null).maxRangeDays()).isEqualTo(31);
        assertThat(new SensorQueryProperties(null, null).maxPointsPerSensor()).isEqualTo(20_000);
        assertThat(new SensorQueryProperties(0, -5).maxRangeDays()).isEqualTo(31);
    }

    @Test
    @DisplayName("from >= to 는 400")
    void from이_to보다_이후() {
        Instant now = Instant.now();
        assertValidation(() -> service(31, 20_000).getSeries(EQUIPMENT_ID, null, now, now.minusSeconds(1)),
                "from은 to보다 이전");
        assertValidation(() -> service(31, 20_000).getSeries(EQUIPMENT_ID, null, now, now), "from은 to보다 이전");
    }

    @Test
    @DisplayName("미래 to는 400 (시계 오차 허용 5분 이내는 통과)")
    void 미래_시각() {
        Instant now = Instant.now();
        assertValidation(() -> service(31, 20_000).getSeries(EQUIPMENT_ID, null,
                now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(2))), "미래");
        assertValidation(() -> service(31, 20_000).getSeries(EQUIPMENT_ID, null,
                now.minus(Duration.ofHours(1)), now.plus(Duration.ofMinutes(10))), "미래");

        // 클라이언트 시계가 몇 분 빠른 정상 요청은 허용
        SensorSeriesResponse ok = service(31, 20_000).getSeries(EQUIPMENT_ID, null,
                now.minus(Duration.ofMinutes(30)), now.plus(Duration.ofMinutes(2)));
        assertThat(ok.granularity()).isEqualTo("RAW");
    }

    @Test
    @DisplayName("기간 상한: 31일 초과는 400 + 명확한 메시지, 정확히 31일은 허용")
    void 기간_상한() {
        Instant now = Instant.now();
        assertValidation(() -> service(31, 20_000).getSeries(EQUIPMENT_ID, null,
                        Instant.parse("2020-01-01T00:00:00Z"), now),
                "최대 31일");
        assertValidation(() -> service(31, 20_000).getSeries(EQUIPMENT_ID, null,
                now.minus(Duration.ofDays(31)).minusSeconds(1), now), "최대 31일");

        assertThat(service(31, 20_000).getSeries(EQUIPMENT_ID, null, now.minus(Duration.ofDays(31)), now)
                .granularity()).isEqualTo("5M");
    }

    @Test
    @DisplayName("from 생략(최근 1시간)·1시간 이내는 RAW, 1시간 초과 7일은 1M 그대로 (기존 동작 유지)")
    void 기존_동작_유지() {
        Instant now = Instant.now();
        assertThat(service(31, 20_000).getSeries(EQUIPMENT_ID, null, null, null).granularity()).isEqualTo("RAW");
        assertThat(service(31, 20_000).getSeries(EQUIPMENT_ID, null, now.minus(Duration.ofDays(1)), now)
                .granularity()).isEqualTo("1M");
        assertThat(service(31, 20_000).getSeries(EQUIPMENT_ID, null, now.minus(Duration.ofDays(7)), now)
                .granularity()).isEqualTo("1M");
    }

    @Test
    @DisplayName("포인트 상한 초과 구간은 서버가 5분 버킷으로 다운샘플링하고 granularity=5M으로 알린다")
    void 포인트_상한_다운샘플링() {
        Instant now = Instant.now();
        Instant from = now.minus(Duration.ofDays(20)).truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        // 20일치 1분 집계를 흉내: 처음 10분만 (5분 버킷 2개로 합성되는지 확인)
        List<SensorData1m> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rows.add(new SensorData1m(1L, from.plusSeconds(i * 60L), new BigDecimal("1.00"), new BigDecimal("2.00"),
                    new BigDecimal("1.50"), 30));
        }
        when(oneMinuteRepository.findBySensorIdAndBucketAtGreaterThanEqualAndBucketAtLessThanOrderByBucketAtAsc(
                anyLong(), any(), any())).thenReturn(rows);

        SensorSeriesResponse response = service(31, 20_000).getSeries(EQUIPMENT_ID, null, from, now);

        assertThat(response.granularity()).isEqualTo("5M");
        List<SensorSeriesResponse.Point> points = response.series().get(0).points();
        assertThat(points).hasSize(2);
        assertThat(points.get(0).sampleCount()).isEqualTo(150);
        assertThat(points.get(0).value()).isEqualByComparingTo("1.50");
    }

    @Test
    @DisplayName("1시간 버킷으로도 상한을 넘으면 400 (설정을 낮춘 경우)")
    void 어떤_버킷으로도_초과() {
        Instant now = Instant.now();
        assertValidation(() -> service(31, 100).getSeries(EQUIPMENT_ID, null, now.minus(Duration.ofDays(31)), now),
                "100 포인트");
    }

    @Test
    @DisplayName("1시간 버킷 사용: 상한 2,000 포인트로 낮추면 31일은 1H")
    void 한시간_버킷() {
        Instant now = Instant.now();
        assertThat(service(31, 2_000).getSeries(EQUIPMENT_ID, null, now.minus(Duration.ofDays(31)), now)
                .granularity()).isEqualTo("1H");
    }
}
