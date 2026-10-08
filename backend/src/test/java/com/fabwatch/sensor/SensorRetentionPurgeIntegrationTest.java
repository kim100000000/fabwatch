package com.fabwatch.sensor;

import com.fabwatch.sensor.entity.SensorData;
import com.fabwatch.sensor.repository.SensorDataRepository;
import com.fabwatch.sensor.service.SensorAggregationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 원본 7일 삭제 배치 통합 테스트 — 실제 DELETE ... LIMIT 쿼리와 설정 바인딩(chunk-size=5)을 H2로 검증한다
 * (안정성 감사 H-2). 전용 컨텍스트라 다른 테스트 데이터를 건드리지 않는다.
 */
@SpringBootTest(properties = "fabwatch.sensor.retention.chunk-size=5")
@ActiveProfiles({"local", "test"})
class SensorRetentionPurgeIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant CUTOFF = NOW.minus(Duration.ofDays(7));
    private static final long SENSOR_ID = 9_900_001L;

    @Autowired
    private SensorAggregationService service;
    @Autowired
    private SensorDataRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM sensor_data WHERE sensor_id = ?", SENSOR_ID);
    }

    private void insert(int count, Instant firstAt) {
        List<SensorData> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new SensorData(SENSOR_ID, new BigDecimal("1.00"), firstAt.plusSeconds(i)));
        }
        repository.saveAll(rows);
    }

    private int remaining() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sensor_data WHERE sensor_id = ?", Integer.class, SENSOR_ID);
    }

    @Test
    @DisplayName("청크(5) 경계: 12행 삭제 = 5+5+2, cutoff 이후 행은 보존")
    void 청크_경계_부분() {
        insert(12, CUTOFF.minus(Duration.ofDays(2)));
        insert(4, NOW.minus(Duration.ofHours(1)));

        assertThat(service.purgeRawBefore(NOW)).isEqualTo(12);
        assertThat(remaining()).isEqualTo(4);
    }

    @Test
    @DisplayName("청크 배수(10행)·청크 미만(3행)·0행 모두 정확히 삭제하고 끝난다")
    void 청크_배수_미만_없음() {
        insert(10, CUTOFF.minus(Duration.ofDays(1)));
        assertThat(service.purgeRawBefore(NOW)).isEqualTo(10);
        assertThat(remaining()).isZero();

        insert(3, CUTOFF.minus(Duration.ofDays(1)));
        assertThat(service.purgeRawBefore(NOW)).isEqualTo(3);

        assertThat(service.purgeRawBefore(NOW)).isZero();
    }

    @Test
    @DisplayName("cutoff 경계: 정확히 cutoff 시각의 행은 보존(엄격한 <), 1밀리초 이전 행은 삭제")
    void cutoff_경계() {
        repository.saveAll(List.of(
                new SensorData(SENSOR_ID, new BigDecimal("1.00"), CUTOFF.minusMillis(1)),
                new SensorData(SENSOR_ID, new BigDecimal("2.00"), CUTOFF),
                new SensorData(SENSOR_ID, new BigDecimal("3.00"), CUTOFF.plusMillis(1))));

        assertThat(service.purgeRawBefore(NOW)).isEqualTo(1);
        assertThat(remaining()).isEqualTo(2);
    }

    @Test
    @DisplayName("스키마에 신규 인덱스가 생성된다 (sensor_data.measured_at, 점검 결과/체크리스트/시나리오 FK 조회용) — ddl-auto가 같은 정의로 MySQL에도 만든다")
    void 신규_인덱스_정의() {
        List<String> names = jdbc.queryForList(
                "SELECT DISTINCT UPPER(INDEX_NAME) FROM INFORMATION_SCHEMA.INDEXES", String.class);
        assertThat(names).contains("IDX_SENSOR_DATA_MEASURED", "IDX_SENSOR_DATA_SENSOR_MEASURED",
                "IDX_CHECK_RESULT_INSPECTION", "IDX_CHECKLIST_ITEM_EQUIPMENT", "IDX_SCENARIO_SENSOR");
    }
}
