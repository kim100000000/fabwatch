package com.fabwatch.simulator.service;

import com.fabwatch.sensor.service.SensorSpec;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 시나리오 파라미터 기본값·정규화 (docs/06 §8 "param 기본값", docs/03 F-4.2).
 *
 * - DRIFT: {@code {durationMin: 10}} — durationMin에 걸쳐 crit에 도달하는 기울기를 자동 계산해 slope로 굳혀 저장하고,
 *          상한(maxElapsedSec = durationMin×60)·목표값(targetValue)도 함께 저장한다. 상한 이후엔 목표값에서 고정(plateau)
 * - SPIKE: {@code {probability: 0.1, multiplier: 1.8}}
 * - STEP : {@code {offsetRatio: 0.15}} — base_value 기준 비율을 offset(절대값)으로 굳혀 저장
 *
 * 주입 시점에 절대값(slope/offset)까지 계산해 저장하는 이유: 나중에 임계치를 바꿔도 진행 중인
 * 시나리오의 거동이 흔들리지 않게 하기 위함(시연 재현성).
 */
public final class ScenarioParams {

    public static final int DEFAULT_DURATION_MIN = 10;
    public static final double DEFAULT_PROBABILITY = 0.1;
    public static final double DEFAULT_MULTIPLIER = 1.8;
    public static final double DEFAULT_OFFSET_RATIO = 0.15;

    private ScenarioParams() {
    }

    /**
     * DRIFT 파라미터 정규화 → {durationMin, slopePerSec, maxElapsedSec, targetValue}
     * slopePerSec = (목표값 − base) / (durationMin × 60). maxElapsedSec = durationMin × 60 (이후 값 고정).
     * 목표값은 crit_high + 노이즈 1σ(없으면 warn_high, 둘 다 없으면 base의 ±20%). 하한 임계치만 있는 센서면 아래 방향으로 흐른다.
     */
    public static Map<String, Object> normalizeDrift(SensorSpec spec, Map<String, Object> raw) {
        Map<String, Object> param = new LinkedHashMap<>();
        int durationMin = intValue(raw, "durationMin", DEFAULT_DURATION_MIN);
        param.put("durationMin", durationMin);

        double base = toDouble(spec.baseValue(), 0);
        long maxElapsedSec = Math.max(1L, durationMin * 60L);

        Double explicitSlope = doubleOrNull(raw, "slopePerSec");
        if (explicitSlope != null) {
            // 기울기를 직접 준 경우: 목표값 = base + 기울기 × 상한초 (crit과 무관)
            param.put("slopePerSec", explicitSlope);
            param.put("maxElapsedSec", maxElapsedSec);
            param.put("targetValue", base + explicitSlope * maxElapsedSec);
            return param;
        }
        double target = resolveDriftTarget(spec, base);
        param.put("slopePerSec", (target - base) / maxElapsedSec);
        param.put("maxElapsedSec", maxElapsedSec);
        param.put("targetValue", target);
        return param;
    }

    /** SPIKE 파라미터 정규화 → {probability, multiplier} */
    public static Map<String, Object> normalizeSpike(Map<String, Object> raw) {
        Map<String, Object> param = new LinkedHashMap<>();
        double probability = clamp(doubleValue(raw, "probability", DEFAULT_PROBABILITY), 0.0, 1.0);
        double multiplier = doubleValue(raw, "multiplier", DEFAULT_MULTIPLIER);
        param.put("probability", probability);
        param.put("multiplier", multiplier);
        return param;
    }

    /** STEP 파라미터 정규화 → {offsetRatio, offset} */
    public static Map<String, Object> normalizeStep(SensorSpec spec, Map<String, Object> raw) {
        Map<String, Object> param = new LinkedHashMap<>();
        Double explicitOffset = doubleOrNull(raw, "offset");
        double ratio = doubleValue(raw, "offsetRatio", DEFAULT_OFFSET_RATIO);
        param.put("offsetRatio", ratio);
        param.put("offset", explicitOffset != null ? explicitOffset : toDouble(spec.baseValue(), 0) * ratio);
        return param;
    }

    /**
     * DRIFT 목표값: crit_high > warn_high > crit_low > warn_low 순으로 찾는다.
     * 임계치가 하나도 없으면 base의 ±20% (base가 음수면 더 음수 방향).
     */
    private static double resolveDriftTarget(SensorSpec spec, double base) {
        // 목표값을 crit에 정확히 맞추면 노이즈 때문에 plateau에서 '초과' 판정이 샘플당 약 50%뿐이라
        // 데모에서 CRITICAL이 안 나올 수 있다(σ=0이면 영원히 안 나옴). crit에서 노이즈 1σ만큼 더 넘긴다.
        double sigma = spec.noiseSigma() == null ? 0 : Math.abs(spec.noiseSigma().doubleValue());
        if (spec.critHigh() != null) {
            return spec.critHigh().doubleValue() + sigma;
        }
        if (spec.warnHigh() != null) {
            return spec.warnHigh().doubleValue();
        }
        if (spec.critLow() != null) {
            return spec.critLow().doubleValue() - sigma;
        }
        if (spec.warnLow() != null) {
            return spec.warnLow().doubleValue();
        }
        return base >= 0 ? base * 1.2 : base * 1.2;
    }

    public static double doubleValue(Map<String, Object> map, String key, double fallback) {
        Double value = doubleOrNull(map, key);
        return value == null ? fallback : value;
    }

    public static int intValue(Map<String, Object> map, String key, int fallback) {
        Double value = doubleOrNull(map, key);
        return value == null ? fallback : (int) Math.round(value);
    }

    private static Double doubleOrNull(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static double toDouble(BigDecimal value, double fallback) {
        return value == null ? fallback : value.doubleValue();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
