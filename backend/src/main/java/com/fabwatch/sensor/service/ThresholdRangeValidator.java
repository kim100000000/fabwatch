package com.fabwatch.sensor.service;

import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;

import java.math.BigDecimal;

/**
 * 임계치 순서 검증 (docs/03 F-2 "임계치 설정"): {@code crit_low ≤ warn_low < warn_high ≤ crit_high}.
 *
 * NULL은 "해당 방향 미사용"이므로 검증에서 건너뛰되, 정의된 값들 사이의 상대 순서는 반드시 지켜야 한다.
 * (예: warn_low만 NULL이면 crit_low ≤ ... < warn_high ≤ crit_high 로 이어 검증한다.)
 * 위반 시 400 INVALID_THRESHOLD_RANGE.
 */
public final class ThresholdRangeValidator {

    private ThresholdRangeValidator() {
    }

    public static void validate(BigDecimal warnLow, BigDecimal warnHigh, BigDecimal critLow, BigDecimal critHigh) {
        // 4값을 전부 비우면 해당 센서의 알람 판정이 조용히 꺼진다(요청 본문에서 키를 빠뜨려도 동일) → 최소 1개는 필요
        if (warnLow == null && warnHigh == null && critLow == null && critHigh == null) {
            throw new BusinessException(ErrorCode.INVALID_THRESHOLD_RANGE,
                    "임계치를 최소 1개 이상 설정해야 합니다. 전부 비우면 센서 감시가 꺼집니다.");
        }
        // 하한 쌍: crit_low ≤ warn_low
        requireLessOrEqual(critLow, warnLow, "crit_low ≤ warn_low");
        // 상한 쌍: warn_high ≤ crit_high
        requireLessOrEqual(warnHigh, critHigh, "warn_high ≤ crit_high");
        // 중앙: warn_low < warn_high (둘 다 있을 때만 엄격 부등호)
        requireLess(warnLow, warnHigh, "warn_low < warn_high");
        // warn이 한쪽만 정의된 경우에도 crit 상/하한이 교차하면 안 된다
        requireLess(critLow, warnHigh, "crit_low < warn_high");
        requireLess(warnLow, critHigh, "warn_low < crit_high");
        requireLess(critLow, critHigh, "crit_low < crit_high");
    }

    private static void requireLessOrEqual(BigDecimal lower, BigDecimal upper, String rule) {
        if (lower != null && upper != null && lower.compareTo(upper) > 0) {
            throw new BusinessException(ErrorCode.INVALID_THRESHOLD_RANGE,
                    "임계치 순서 위반(" + rule + "): " + lower + " > " + upper);
        }
    }

    private static void requireLess(BigDecimal lower, BigDecimal upper, String rule) {
        if (lower != null && upper != null && lower.compareTo(upper) >= 0) {
            throw new BusinessException(ErrorCode.INVALID_THRESHOLD_RANGE,
                    "임계치 순서 위반(" + rule + "): " + lower + " >= " + upper);
        }
    }
}
