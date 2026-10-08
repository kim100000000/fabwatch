package com.fabwatch.simulator.service;

import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
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
 * 주입 시점 입력 검증 (보안 감사 M-1): 유형별 범위를 벗어나거나 NaN/Infinity/숫자가 아닌 값(문자열 포함)이면
 * 400 VALIDATION_ERROR. 값 하나가 센서 수집 전체(한 틱 트랜잭션)를 죽이는 poison pill이 되지 않게 입구에서 막는다.
 * 범위: DRIFT durationMin 1~240, |slopePerSec| ≤ 1000 / SPIKE probability 0~1, multiplier 1~5 /
 *       STEP |offsetRatio| ≤ 10, |offset| ≤ 1,000,000. 계산된 목표값·오프셋도 decimal(10,2) 범위 안이어야 한다.
 *
 * 주입 시점에 절대값(slope/offset)까지 계산해 저장하는 이유: 나중에 임계치를 바꿔도 진행 중인
 * 시나리오의 거동이 흔들리지 않게 하기 위함(시연 재현성).
 */
public final class ScenarioParams {

    public static final int DEFAULT_DURATION_MIN = 10;
    public static final double DEFAULT_PROBABILITY = 0.1;
    public static final double DEFAULT_MULTIPLIER = 1.8;
    public static final double DEFAULT_OFFSET_RATIO = 0.15;

    // 입력 허용 범위 (주입 검증)
    public static final int MIN_DURATION_MIN = 1;
    public static final int MAX_DURATION_MIN = 240;
    public static final double MAX_ABS_SLOPE_PER_SEC = 1_000;
    public static final double MIN_MULTIPLIER = 1.0;
    public static final double MAX_MULTIPLIER = 5.0;
    public static final double MAX_ABS_OFFSET_RATIO = 10;
    public static final double MAX_ABS_OFFSET = 1_000_000;
    /** sensor_data.value decimal(10,2)가 담을 수 있는 최대 절대값 */
    public static final double MAX_STORABLE_ABS = 99_999_999.99;

    private ScenarioParams() {
    }

    /**
     * DRIFT 파라미터 정규화 → {durationMin, slopePerSec, maxElapsedSec, targetValue}
     * slopePerSec = (목표값 − base) / (durationMin × 60). maxElapsedSec = durationMin × 60 (이후 값 고정).
     * 목표값은 crit_high + 노이즈 1σ(없으면 warn_high, 둘 다 없으면 base의 ±20%). 하한 임계치만 있는 센서면 아래 방향으로 흐른다.
     */
    public static Map<String, Object> normalizeDrift(SensorSpec spec, Map<String, Object> raw) {
        Map<String, Object> param = new LinkedHashMap<>();
        int durationMin = (int) Math.round(strictNumber(raw, "durationMin", DEFAULT_DURATION_MIN,
                MIN_DURATION_MIN, MAX_DURATION_MIN));
        param.put("durationMin", durationMin);

        double base = toDouble(spec.baseValue(), 0);
        long maxElapsedSec = Math.max(1L, durationMin * 60L);

        Double explicitSlope = strictNumberOrNull(raw, "slopePerSec", -MAX_ABS_SLOPE_PER_SEC, MAX_ABS_SLOPE_PER_SEC);
        if (explicitSlope != null) {
            // 기울기를 직접 준 경우: 목표값 = base + 기울기 × 상한초 (crit과 무관)
            double target = base + explicitSlope * maxElapsedSec;
            requireStorable(target, "slopePerSec");
            param.put("slopePerSec", explicitSlope);
            param.put("maxElapsedSec", maxElapsedSec);
            param.put("targetValue", target);
            return param;
        }
        double target = resolveDriftTarget(spec, base);
        param.put("slopePerSec", (target - base) / maxElapsedSec);
        param.put("maxElapsedSec", maxElapsedSec);
        param.put("targetValue", target);
        return param;
    }

    /** SPIKE 파라미터 정규화 → {probability, multiplier}. 범위를 벗어나면 보정하지 않고 400. */
    public static Map<String, Object> normalizeSpike(Map<String, Object> raw) {
        Map<String, Object> param = new LinkedHashMap<>();
        param.put("probability", strictNumber(raw, "probability", DEFAULT_PROBABILITY, 0.0, 1.0));
        param.put("multiplier", strictNumber(raw, "multiplier", DEFAULT_MULTIPLIER, MIN_MULTIPLIER, MAX_MULTIPLIER));
        return param;
    }

    /** STEP 파라미터 정규화 → {offsetRatio, offset} */
    public static Map<String, Object> normalizeStep(SensorSpec spec, Map<String, Object> raw) {
        Map<String, Object> param = new LinkedHashMap<>();
        Double explicitOffset = strictNumberOrNull(raw, "offset", -MAX_ABS_OFFSET, MAX_ABS_OFFSET);
        double ratio = strictNumber(raw, "offsetRatio", DEFAULT_OFFSET_RATIO, -MAX_ABS_OFFSET_RATIO, MAX_ABS_OFFSET_RATIO);
        double offset = explicitOffset != null ? explicitOffset : toDouble(spec.baseValue(), 0) * ratio;
        // 비율 × base가 절대값 상한을 넘는 경우(base가 큰 센서)도 막는다
        if (!Double.isFinite(offset) || Math.abs(offset) > MAX_ABS_OFFSET) {
            throw invalid("offsetRatio", "오프셋이 허용 범위(±" + (long) MAX_ABS_OFFSET + ")를 벗어납니다.");
        }
        param.put("offsetRatio", ratio);
        param.put("offset", offset);
        return param;
    }

    // ---- 입력 검증 ----

    /** 키가 없거나 null이면 fallback, 있으면 유한한 숫자이고 [min, max] 안이어야 한다. */
    private static double strictNumber(Map<String, Object> map, String key, double fallback, double min, double max) {
        Double value = strictNumberOrNull(map, key, min, max);
        return value == null ? fallback : value;
    }

    /** 키가 없거나 null이면 null. 있으면 JSON 숫자(문자열 불가)이고 유한하며 [min, max] 안이어야 한다. */
    private static Double strictNumberOrNull(Map<String, Object> map, String key, double min, double max) {
        if (map == null || map.get(key) == null) {
            return null;
        }
        Object raw = map.get(key);
        if (!(raw instanceof Number number)) {
            throw invalid(key, "숫자여야 합니다.");
        }
        double value = number.doubleValue();
        if (!Double.isFinite(value)) {
            throw invalid(key, "유한한 숫자여야 합니다.");
        }
        if (value < min || value > max) {
            throw invalid(key, "허용 범위는 " + format(min) + " ~ " + format(max) + " 입니다.");
        }
        return value;
    }

    private static void requireStorable(double value, String key) {
        if (!Double.isFinite(value) || Math.abs(value) > MAX_STORABLE_ABS) {
            throw invalid(key, "계산된 목표값이 저장 가능한 범위를 벗어납니다.");
        }
    }

    private static BusinessException invalid(String key, String detail) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, "시나리오 파라미터 '" + key + "': " + detail);
    }

    private static String format(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
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

    /**
     * DRIFT 상한 시간(초). 정규화 시 저장한 maxElapsedSec를 우선 쓰고, 없는 과거 시나리오는 durationMin×60으로 대체한다.
     * 둘 다 없으면 0(상한 정보 없음).
     */
    public static long driftMaxElapsedSeconds(Map<String, Object> param) {
        double maxElapsed = doubleValue(param, "maxElapsedSec", 0);
        if (maxElapsed > 0) {
            return Math.round(maxElapsed);
        }
        double durationMin = doubleValue(param, "durationMin", 0);
        return durationMin > 0 ? Math.round(durationMin * 60) : 0;
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

}
