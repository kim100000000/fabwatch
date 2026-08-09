package com.fabwatch.sensor.service;

import com.fabwatch.common.event.SensorLevel;

import java.math.BigDecimal;

/**
 * 임계치 판정 (docs/03 F-4.3) — "warn 초과 → WARNING, crit 초과 → CRITICAL".
 *
 * 판정 규칙은 이 클래스 한 곳에만 존재한다. 상태 머신과 마찬가지로 산재시키지 않는다(QA 추적 지점).
 * - 상/하한 각각 독립: crit_low 미만 또는 crit_high 초과 → CRITICAL
 * - CRITICAL을 WARNING보다 먼저 본다 (crit 구간은 warn 구간을 포함하므로)
 * - 임계치가 NULL이면 해당 방향은 미사용 (docs/05 sensors "NULL=미사용")
 * - 경계값(정확히 임계치와 같은 값)은 "초과"가 아니므로 정상으로 본다
 */
public final class ThresholdEvaluator {

    private ThresholdEvaluator() {
    }

    /** 판정 결과 — 어떤 기준값에 걸렸는지도 함께 반환한다 (알람 threshold_value 기록용). */
    public record Result(SensorLevel level, BigDecimal thresholdValue) {

        public boolean isAbnormal() {
            return level.isAbnormal();
        }
    }

    private static final Result NORMAL = new Result(SensorLevel.NORMAL, null);

    public static Result evaluate(SensorSpec spec, BigDecimal value) {
        if (spec == null || value == null) {
            return NORMAL;
        }
        return evaluate(value, spec.warnLow(), spec.warnHigh(), spec.critLow(), spec.critHigh());
    }

    public static Result evaluate(BigDecimal value, BigDecimal warnLow, BigDecimal warnHigh,
                                  BigDecimal critLow, BigDecimal critHigh) {
        if (value == null) {
            return NORMAL;
        }
        if (critLow != null && value.compareTo(critLow) < 0) {
            return new Result(SensorLevel.CRITICAL, critLow);
        }
        if (critHigh != null && value.compareTo(critHigh) > 0) {
            return new Result(SensorLevel.CRITICAL, critHigh);
        }
        if (warnLow != null && value.compareTo(warnLow) < 0) {
            return new Result(SensorLevel.WARNING, warnLow);
        }
        if (warnHigh != null && value.compareTo(warnHigh) > 0) {
            return new Result(SensorLevel.WARNING, warnHigh);
        }
        return NORMAL;
    }
}
