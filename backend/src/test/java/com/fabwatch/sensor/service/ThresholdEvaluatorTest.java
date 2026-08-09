package com.fabwatch.sensor.service;

import com.fabwatch.common.event.SensorLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 임계치 판정 단위 테스트 (docs/03 F-4.3 — 백엔드 필수 테스트 4영역 중 하나).
 * 시드 LAMI-01 온도 센서(warn 38~50, crit 35~55)를 기준값으로 쓴다.
 */
class ThresholdEvaluatorTest {

    private static final BigDecimal WARN_LOW = new BigDecimal("38");
    private static final BigDecimal WARN_HIGH = new BigDecimal("50");
    private static final BigDecimal CRIT_LOW = new BigDecimal("35");
    private static final BigDecimal CRIT_HIGH = new BigDecimal("55");

    private ThresholdEvaluator.Result evaluate(String value) {
        return ThresholdEvaluator.evaluate(new BigDecimal(value), WARN_LOW, WARN_HIGH, CRIT_LOW, CRIT_HIGH);
    }

    @Nested
    @DisplayName("상하한 4값이 모두 설정된 센서")
    class FullThresholds {

        @Test
        @DisplayName("warn 범위 안이면 NORMAL")
        void 정상() {
            assertThat(evaluate("45.0").level()).isEqualTo(SensorLevel.NORMAL);
        }

        @Test
        @DisplayName("warn_high 초과 → WARNING, 걸린 기준값은 warn_high")
        void 상한_경고() {
            ThresholdEvaluator.Result result = evaluate("50.8");
            assertThat(result.level()).isEqualTo(SensorLevel.WARNING);
            assertThat(result.thresholdValue()).isEqualByComparingTo(WARN_HIGH);
        }

        @Test
        @DisplayName("crit_high 초과 → CRITICAL (WARNING보다 우선)")
        void 상한_임계() {
            ThresholdEvaluator.Result result = evaluate("55.1");
            assertThat(result.level()).isEqualTo(SensorLevel.CRITICAL);
            assertThat(result.thresholdValue()).isEqualByComparingTo(CRIT_HIGH);
        }

        @Test
        @DisplayName("warn_low 미만 → WARNING (하한도 판정한다)")
        void 하한_경고() {
            ThresholdEvaluator.Result result = evaluate("37.9");
            assertThat(result.level()).isEqualTo(SensorLevel.WARNING);
            assertThat(result.thresholdValue()).isEqualByComparingTo(WARN_LOW);
        }

        @Test
        @DisplayName("crit_low 미만 → CRITICAL")
        void 하한_임계() {
            ThresholdEvaluator.Result result = evaluate("34.9");
            assertThat(result.level()).isEqualTo(SensorLevel.CRITICAL);
            assertThat(result.thresholdValue()).isEqualByComparingTo(CRIT_LOW);
        }

        @Test
        @DisplayName("경계값(임계치와 정확히 같은 값)은 '초과'가 아니다 — 각 단계에서 한 칸씩 낮게 판정된다")
        void 경계값() {
            // warn 경계 = 아직 정상
            assertThat(evaluate("50").level()).isEqualTo(SensorLevel.NORMAL);
            assertThat(evaluate("38").level()).isEqualTo(SensorLevel.NORMAL);
            // crit 경계 = crit 초과는 아니지만 warn은 이미 넘었으므로 WARNING
            assertThat(evaluate("55").level()).isEqualTo(SensorLevel.WARNING);
            assertThat(evaluate("35").level()).isEqualTo(SensorLevel.WARNING);
            // 한 단계 더 = CRITICAL
            assertThat(evaluate("55.01").level()).isEqualTo(SensorLevel.CRITICAL);
            assertThat(evaluate("34.99").level()).isEqualTo(SensorLevel.CRITICAL);
        }
    }

    @Nested
    @DisplayName("임계치가 NULL이면 해당 방향은 미사용 (docs/05 sensors)")
    class NullThresholds {

        @Test
        @DisplayName("하한이 NULL인 진동 센서 — 아무리 낮아도 정상")
        void 하한_없음() {
            // 시드 LAMI-01 진동: warn_high 3.5 / crit_high 5.0, 하한 없음
            assertThat(ThresholdEvaluator.evaluate(new BigDecimal("0.0"), null, new BigDecimal("3.5"),
                    null, new BigDecimal("5.0")).level()).isEqualTo(SensorLevel.NORMAL);
            assertThat(ThresholdEvaluator.evaluate(new BigDecimal("5.4"), null, new BigDecimal("3.5"),
                    null, new BigDecimal("5.0")).level()).isEqualTo(SensorLevel.CRITICAL);
        }

        @Test
        @DisplayName("임계치가 하나도 없으면 항상 NORMAL")
        void 전부_없음() {
            assertThat(ThresholdEvaluator.evaluate(new BigDecimal("9999"), null, null, null, null).level())
                    .isEqualTo(SensorLevel.NORMAL);
        }

        @Test
        @DisplayName("값이 null이면 NORMAL (아직 수집 전 센서)")
        void 값_없음() {
            assertThat(ThresholdEvaluator.evaluate(null, WARN_LOW, WARN_HIGH, CRIT_LOW, CRIT_HIGH).level())
                    .isEqualTo(SensorLevel.NORMAL);
        }
    }

    @Test
    @DisplayName("음수 기준 센서(진공 압력)도 동일 규칙으로 판정된다")
    void 음수_압력센서() {
        // 시드 LAMI-01 압력: warn -105~-85, crit -110~-80 (진공이라 전부 음수)
        BigDecimal warnLow = new BigDecimal("-105");
        BigDecimal warnHigh = new BigDecimal("-85");
        BigDecimal critLow = new BigDecimal("-110");
        BigDecimal critHigh = new BigDecimal("-80");

        assertThat(ThresholdEvaluator.evaluate(new BigDecimal("-95"), warnLow, warnHigh, critLow, critHigh).level())
                .isEqualTo(SensorLevel.NORMAL);
        assertThat(ThresholdEvaluator.evaluate(new BigDecimal("-112"), warnLow, warnHigh, critLow, critHigh).level())
                .isEqualTo(SensorLevel.CRITICAL);
        assertThat(ThresholdEvaluator.evaluate(new BigDecimal("-84"), warnLow, warnHigh, critLow, critHigh).level())
                .isEqualTo(SensorLevel.WARNING);
    }

    @Test
    @DisplayName("SensorSpec 오버로드도 동일하게 동작한다")
    void 스펙_오버로드() {
        SensorSpec spec = new SensorSpec(1L, 10L, "TEMP", "℃",
                new BigDecimal("45"), new BigDecimal("0.8"), WARN_LOW, WARN_HIGH, CRIT_LOW, CRIT_HIGH);
        assertThat(ThresholdEvaluator.evaluate(spec, new BigDecimal("56")).level()).isEqualTo(SensorLevel.CRITICAL);
        assertThat(ThresholdEvaluator.evaluate(spec, new BigDecimal("45")).isAbnormal()).isFalse();
    }
}
