package com.fabwatch.simulator.service;

import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import com.fabwatch.sensor.service.SensorSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 시나리오 주입 파라미터 검증 (보안 감사 M-1) — 범위 밖/NaN/Infinity/문자열은 400 VALIDATION_ERROR */
class ScenarioParamsValidationTest {

    private static final SensorSpec SPEC = new SensorSpec(1L, 1L, "VIBRATION", "mm/s",
            new BigDecimal("2.0"), new BigDecimal("0.1"),
            new BigDecimal("3.0"), new BigDecimal("4.0"), null, new BigDecimal("5.0"));

    private static void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    private static Map<String, Object> map(String key, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return map;
    }

    @Test
    @DisplayName("DRIFT durationMin: 1~240 경계는 통과, 0·241·음수는 거부")
    void drift_duration_범위() {
        assertThat(ScenarioParams.normalizeDrift(SPEC, map("durationMin", 1)).get("durationMin")).isEqualTo(1);
        assertThat(ScenarioParams.normalizeDrift(SPEC, map("durationMin", 240)).get("durationMin")).isEqualTo(240);
        assertInvalid(() -> ScenarioParams.normalizeDrift(SPEC, map("durationMin", 0)));
        assertInvalid(() -> ScenarioParams.normalizeDrift(SPEC, map("durationMin", 241)));
        assertInvalid(() -> ScenarioParams.normalizeDrift(SPEC, map("durationMin", -5)));
    }

    @Test
    @DisplayName("DRIFT slopePerSec: 유한수·절대값 상한, NaN/Infinity/문자열 거부")
    void drift_slope_검증() {
        assertThat(ScenarioParams.normalizeDrift(SPEC, map("slopePerSec", 0.01)).get("slopePerSec")).isEqualTo(0.01);
        assertInvalid(() -> ScenarioParams.normalizeDrift(SPEC, map("slopePerSec", Double.NaN)));
        assertInvalid(() -> ScenarioParams.normalizeDrift(SPEC, map("slopePerSec", Double.POSITIVE_INFINITY)));
        assertInvalid(() -> ScenarioParams.normalizeDrift(SPEC, map("slopePerSec", Double.NEGATIVE_INFINITY)));
        assertInvalid(() -> ScenarioParams.normalizeDrift(SPEC, map("slopePerSec", 1e12)));
        assertInvalid(() -> ScenarioParams.normalizeDrift(SPEC, map("slopePerSec", -1001)));
        assertInvalid(() -> ScenarioParams.normalizeDrift(SPEC, map("slopePerSec", "0.5")));
        assertInvalid(() -> ScenarioParams.normalizeDrift(SPEC, map("slopePerSec", "NaN")));
        assertInvalid(() -> ScenarioParams.normalizeDrift(SPEC, map("slopePerSec", List.of(1))));
        assertInvalid(() -> ScenarioParams.normalizeDrift(SPEC, map("durationMin", "10")));
    }

    @Test
    @DisplayName("DRIFT: 기울기×시간으로 계산한 목표값이 decimal(10,2)를 넘으면 거부")
    void drift_목표값_저장범위() {
        // 1000/초 × 240분(14,400초) = 1.44e7 → 허용. 기준값이 큰 센서에서 합이 1억을 넘는 경우를 막는다
        SensorSpec huge = new SensorSpec(2L, 1L, "TEMP", "C", new BigDecimal("99999999"), BigDecimal.ZERO,
                null, null, null, null);
        Map<String, Object> raw = new HashMap<>();
        raw.put("durationMin", 240);
        raw.put("slopePerSec", 1000);
        assertInvalid(() -> ScenarioParams.normalizeDrift(huge, raw));
    }

    @Test
    @DisplayName("DRIFT 파라미터 생략 — 기본값(10분)과 crit 기반 목표값으로 정규화")
    void drift_기본값() {
        Map<String, Object> normalized = ScenarioParams.normalizeDrift(SPEC, Map.of());
        assertThat(normalized.get("durationMin")).isEqualTo(10);
        assertThat(normalized.get("maxElapsedSec")).isEqualTo(600L);
        // 목표 = crit_high 5.0 + σ 0.1 → (5.1 - 2.0) / 600
        assertThat((double) normalized.get("slopePerSec")).isEqualTo((5.1 - 2.0) / 600, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("SPIKE probability 0~1, multiplier 1~5 — 경계 통과, 밖은 보정 없이 거부")
    void spike_범위() {
        Map<String, Object> ok = new HashMap<>();
        ok.put("probability", 1);
        ok.put("multiplier", 5);
        assertThat(ScenarioParams.normalizeSpike(ok)).containsEntry("probability", 1.0).containsEntry("multiplier", 5.0);
        ok.put("probability", 0);
        ok.put("multiplier", 1);
        assertThat(ScenarioParams.normalizeSpike(ok)).containsEntry("probability", 0.0).containsEntry("multiplier", 1.0);

        assertInvalid(() -> ScenarioParams.normalizeSpike(map("probability", 1.01)));
        assertInvalid(() -> ScenarioParams.normalizeSpike(map("probability", -0.1)));
        assertInvalid(() -> ScenarioParams.normalizeSpike(map("multiplier", 5.01)));
        assertInvalid(() -> ScenarioParams.normalizeSpike(map("multiplier", 0.5)));
        assertInvalid(() -> ScenarioParams.normalizeSpike(map("multiplier", Double.NaN)));
        assertInvalid(() -> ScenarioParams.normalizeSpike(map("multiplier", Double.POSITIVE_INFINITY)));
        assertInvalid(() -> ScenarioParams.normalizeSpike(map("multiplier", "1e999")));
        assertInvalid(() -> ScenarioParams.normalizeSpike(map("probability", "0.5")));
    }

    @Test
    @DisplayName("STEP offset·offsetRatio — 유한수·절대값 상한, 비율×base도 상한 검사")
    void step_범위() {
        assertThat(ScenarioParams.normalizeStep(SPEC, Map.of()).get("offsetRatio")).isEqualTo(0.15);
        assertThat(ScenarioParams.normalizeStep(SPEC, map("offset", -3.5)).get("offset")).isEqualTo(-3.5);
        assertInvalid(() -> ScenarioParams.normalizeStep(SPEC, map("offset", 1e9)));
        assertInvalid(() -> ScenarioParams.normalizeStep(SPEC, map("offset", Double.NaN)));
        assertInvalid(() -> ScenarioParams.normalizeStep(SPEC, map("offset", Double.NEGATIVE_INFINITY)));
        assertInvalid(() -> ScenarioParams.normalizeStep(SPEC, map("offsetRatio", 11)));
        assertInvalid(() -> ScenarioParams.normalizeStep(SPEC, map("offsetRatio", "0.1")));

        SensorSpec bigBase = new SensorSpec(3L, 1L, "TEMP", "C", new BigDecimal("500000"), BigDecimal.ZERO,
                null, null, null, null);
        assertInvalid(() -> ScenarioParams.normalizeStep(bigBase, map("offsetRatio", 5))); // 2,500,000 > 1,000,000
    }

    @Test
    @DisplayName("키가 null이면 생략과 같이 기본값 사용, 모르는 키는 정규화 결과에 남지 않는다")
    void null값과_미지_키() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("durationMin", null);
        raw.put("evil", "<script>");
        Map<String, Object> normalized = ScenarioParams.normalizeDrift(SPEC, raw);
        assertThat(normalized.get("durationMin")).isEqualTo(10);
        assertThat(normalized).doesNotContainKey("evil");
    }
}
