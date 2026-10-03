package com.fabwatch.simulator.service;

import com.fabwatch.sensor.service.SensorSpec;
import com.fabwatch.sensor.service.ThresholdEvaluator;
import com.fabwatch.common.event.SensorLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 시나리오 값 계산 공식 (docs/03 F-4.2) 단위 테스트.
 * 시드 LAMI-01 온도 센서(base 45℃, σ 0.8, warn 38~50, crit 35~55)를 기준으로 검증한다.
 */
class ScenarioValueCalculatorTest {

    private static final SensorSpec TEMP = new SensorSpec(1L, 10L, "TEMP", "℃",
            new BigDecimal("45"), new BigDecimal("0.8"),
            new BigDecimal("38"), new BigDecimal("50"), new BigDecimal("35"), new BigDecimal("55"));

    // ---------- DRIFT ----------

    @Test
    @DisplayName("DRIFT 기본 파라미터 — 10분에 걸쳐 crit_high에 도달하는 기울기를 계산한다")
    void 드리프트_기본_기울기() {
        Map<String, Object> param = ScenarioParams.normalizeDrift(TEMP, null);

        assertThat(param.get("durationMin")).isEqualTo(ScenarioParams.DEFAULT_DURATION_MIN);
        assertThat(param.get("maxElapsedSec")).isEqualTo(600L);
        assertThat((double) param.get("targetValue")).isCloseTo(55.8, within(1e-9)); // crit_high 55 + 노이즈 1σ(0.8)
        double slope = (double) param.get("slopePerSec");
        // (55.8 - 45) / (10분 × 60초) = 0.018/초
        assertThat(slope).isCloseTo(10.8 / 600.0, within(1e-9));

        // 10분 뒤 목표값(crit_high 55 + 1σ = 55.8)에 도달 → 노이즈가 -1σ까지 내려와도 crit(55)을 넘는다
        double after10min = ScenarioValueCalculator.applyDrift(45.0, slope, 600, 600);
        assertThat(after10min).isCloseTo(55.8, within(1e-6));
        assertThat(ThresholdEvaluator.evaluate(TEMP, BigDecimal.valueOf(after10min - 0.8 + 0.05)).level())
                .isEqualTo(SensorLevel.CRITICAL);
    }

    @Test
    @DisplayName("DRIFT는 중간 지점에서 WARNING을 먼저 통과한다 (전조 → 고장 서사)")
    void 드리프트_경고_먼저() {
        double slope = (double) ScenarioParams.normalizeDrift(TEMP, null).get("slopePerSec");

        double after6min = ScenarioValueCalculator.applyDrift(45.0, slope, 360, 600); // 45 + 6 = 51
        assertThat(ThresholdEvaluator.evaluate(TEMP, BigDecimal.valueOf(after6min)).level())
                .isEqualTo(SensorLevel.WARNING);
    }

    @Test
    @DisplayName("DRIFT: value += slope × 경과초 (공식 그대로)")
    void 드리프트_공식() {
        assertThat(ScenarioValueCalculator.applyDrift(45.0, 0.02, 100, 600)).isCloseTo(47.0, within(1e-9));
        assertThat(ScenarioValueCalculator.applyDrift(45.0, -0.02, 100, 600)).isCloseTo(43.0, within(1e-9));
    }

    @Test
    @DisplayName("DRIFT: 경과초가 0 이하면 변형 없음")
    void 드리프트_시작전() {
        assertThat(ScenarioValueCalculator.applyDrift(45.0, 0.02, 0, 600)).isEqualTo(45.0);
        assertThat(ScenarioValueCalculator.applyDrift(45.0, 0.02, -10, 600)).isEqualTo(45.0);
    }

    @Test
    @DisplayName("DRIFT: durationMin을 줄이면 기울기가 그만큼 가팔라진다")
    void 드리프트_기간_지정() {
        double slope = (double) ScenarioParams.normalizeDrift(TEMP, Map.of("durationMin", 5)).get("slopePerSec");
        assertThat(slope).isCloseTo(10.8 / 300.0, within(1e-9));
    }

    @Test
    @DisplayName("DRIFT: 상한 임계치가 없으면 하한(crit_low) 방향으로 흐른다")
    void 드리프트_하한_방향() {
        SensorSpec lowOnly = new SensorSpec(2L, 10L, "PRESSURE", "kPa",
                new BigDecimal("-95"), new BigDecimal("1.5"), null, null, new BigDecimal("-110"), null);

        double slope = (double) ScenarioParams.normalizeDrift(lowOnly, null).get("slopePerSec");
        assertThat(slope).isNegative();
        assertThat(ScenarioValueCalculator.applyDrift(-95.0, slope, 600, 600)).isCloseTo(-111.5, within(1e-6)); // crit_low -110 − 1σ(1.5)
    }

    @Test
    @DisplayName("DRIFT 목표값은 crit_high보다 노이즈 1σ만큼 높아서, plateau에서도 노이즈가 있는 값이 crit을 넘을 수 있다")
    void 드리프트_목표값은_crit보다_높다() {
        double target = (double) ScenarioParams.normalizeDrift(TEMP, null).get("targetValue");
        assertThat(target).isGreaterThan(55.0); // crit_high
        // plateau 값에서 -1σ 노이즈가 와도 crit(55) 이상
        assertThat(target - 0.8).isGreaterThanOrEqualTo(55.0 - 1e-9);
    }

    @Test
    @DisplayName("DRIFT plateau: 상한(durationMin) 도달 시점에 목표값(crit + 1σ)이 되고, 그 이후엔 값이 더 오르지 않는다")
    void 드리프트_상한_도달_후_고정() {
        Map<String, Object> param = ScenarioParams.normalizeDrift(TEMP, Map.of("durationMin", 2));
        double slope = (double) param.get("slopePerSec");
        long max = (long) param.get("maxElapsedSec");
        assertThat(max).isEqualTo(120L);
        assertThat((double) param.get("targetValue")).isCloseTo(55.8, within(1e-9));

        // 상한 정확히 도달 = 목표값
        assertThat(ScenarioValueCalculator.applyDrift(45.0, slope, max, max)).isCloseTo(55.8, within(1e-9));
        // 상한 이후(1분 뒤, 수 시간 뒤)에도 목표값 그대로 — 무한 상승 방지
        assertThat(ScenarioValueCalculator.applyDrift(45.0, slope, max + 60, max)).isCloseTo(55.8, within(1e-9));
        assertThat(ScenarioValueCalculator.applyDrift(45.0, slope, 6 * 3600, max)).isCloseTo(55.8, within(1e-9));
        // 상한 직전은 아직 상승 중
        assertThat(ScenarioValueCalculator.applyDrift(45.0, slope, max - 12, max)).isCloseTo(54.72, within(1e-9));
    }

    @Test
    @DisplayName("DRIFT: 상한이 0 이하면 상한 없음(상한 정보 없는 과거 시나리오 호환) — 기존 선형 공식")
    void 드리프트_상한_없음_호환() {
        assertThat(ScenarioValueCalculator.applyDrift(45.0, 0.02, 1000, 0)).isCloseTo(65.0, within(1e-9));
    }

    @Test
    @DisplayName("DRIFT: 경과 0초는 상한과 무관하게 변형 없음")
    void 드리프트_경과_0_상한_있음() {
        assertThat(ScenarioValueCalculator.applyDrift(45.0, 0.02, 0, 120)).isEqualTo(45.0);
    }

    @Test
    @DisplayName("DRIFT: 기울기를 직접 지정하면 목표값 = base + 기울기 × 상한초")
    void 드리프트_기울기_직접_지정_목표값() {
        Map<String, Object> param = ScenarioParams.normalizeDrift(TEMP, Map.of("durationMin", 1, "slopePerSec", 0.05));
        assertThat(param.get("maxElapsedSec")).isEqualTo(60L);
        assertThat((double) param.get("targetValue")).isCloseTo(45.0 + 0.05 * 60, within(1e-9));
    }

    // ---------- SPIKE ----------

    @Test
    @DisplayName("SPIKE: 난수가 확률 미만이면 배수를 곱하고, 이상이면 그대로 둔다")
    void 스파이크_확률() {
        assertThat(ScenarioValueCalculator.applySpike(45.0, 0.1, 1.8, 0.05)).isCloseTo(81.0, within(1e-9));
        assertThat(ScenarioValueCalculator.applySpike(45.0, 0.1, 1.8, 0.5)).isEqualTo(45.0);
        // 경계: roll == probability 는 발동하지 않는다
        assertThat(ScenarioValueCalculator.applySpike(45.0, 0.1, 1.8, 0.1)).isEqualTo(45.0);
    }

    @Test
    @DisplayName("SPIKE 기본 파라미터는 확률 10% / 배수 1.8 (docs/06 §8)")
    void 스파이크_기본값() {
        Map<String, Object> param = ScenarioParams.normalizeSpike(null);
        assertThat(param.get("probability")).isEqualTo(0.1);
        assertThat(param.get("multiplier")).isEqualTo(1.8);
    }

    @Test
    @DisplayName("SPIKE 확률은 0~1로 클램프된다")
    void 스파이크_확률_클램프() {
        assertThat(ScenarioParams.normalizeSpike(Map.of("probability", 5)).get("probability")).isEqualTo(1.0);
        assertThat(ScenarioParams.normalizeSpike(Map.of("probability", -1)).get("probability")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("SPIKE 기본 배수(1.8)면 온도 45℃가 한 번에 crit을 넘는다")
    void 스파이크_임계_돌파() {
        double spiked = ScenarioValueCalculator.applySpike(45.0, 0.1, 1.8, 0.0);
        assertThat(ThresholdEvaluator.evaluate(TEMP, BigDecimal.valueOf(spiked)).level())
                .isEqualTo(SensorLevel.CRITICAL);
    }

    // ---------- STEP ----------

    @Test
    @DisplayName("STEP: value += offset (기준선 이동)")
    void 스텝_공식() {
        assertThat(ScenarioValueCalculator.applyStep(45.0, 6.75)).isCloseTo(51.75, within(1e-9));
        assertThat(ScenarioValueCalculator.applyStep(45.0, -6.75)).isCloseTo(38.25, within(1e-9));
    }

    @Test
    @DisplayName("STEP 기본 offsetRatio는 0.15이고 base_value 기준 절대 offset으로 굳는다")
    void 스텝_기본값() {
        Map<String, Object> param = ScenarioParams.normalizeStep(TEMP, null);
        assertThat(param.get("offsetRatio")).isEqualTo(0.15);
        assertThat((double) param.get("offset")).isCloseTo(45.0 * 0.15, within(1e-9)); // 6.75

        // 45 + 6.75 = 51.75 → warn_high(50) 초과 = WARNING (부품 교체 후 기준선 틀어짐 재현)
        double shifted = ScenarioValueCalculator.applyStep(45.0, (double) param.get("offset"));
        assertThat(ThresholdEvaluator.evaluate(TEMP, BigDecimal.valueOf(shifted)).level())
                .isEqualTo(SensorLevel.WARNING);
    }

    @Test
    @DisplayName("STEP: offset을 직접 주면 ratio 계산을 건너뛴다")
    void 스텝_offset_직접지정() {
        Map<String, Object> param = ScenarioParams.normalizeStep(TEMP, Map.of("offset", 20));
        assertThat((double) param.get("offset")).isCloseTo(20.0, within(1e-9));
    }
}
