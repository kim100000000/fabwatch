package com.fabwatch.aireport.service;

import com.fabwatch.common.util.ShiftUtil;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 일일 쿼터 집계 구간 — KST 기준 일자의 [시작, 다음날 시작) (docs/03 F-6.4, docs/11 §7).
 * 서버·DB는 UTC지만 "하루"는 사용자가 체감하는 KST 자정 기준으로 끊는다.
 */
public record KstDayRange(Instant from, Instant to) {

    public static KstDayRange of(Instant now) {
        LocalDate day = now.atZone(ShiftUtil.KST).toLocalDate();
        return new KstDayRange(
                day.atStartOfDay(ShiftUtil.KST).toInstant(),
                day.plusDays(1).atStartOfDay(ShiftUtil.KST).toInstant());
    }
}
