package com.fabwatch.equipment.service;

import com.fabwatch.common.util.ShiftUtil;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

/**
 * KPI 집계 기간 (docs/03 F-5.4). 경계는 KST 기준, DB/API는 UTC이므로 Instant로 변환해 돌려준다.
 *
 * <pre>
 * DAY  : 오늘 KST 00:00 ~ now
 * WEEK : 이번 주 월요일 KST 00:00 ~ now
 * MONTH: 이번 달 1일 KST 00:00 ~ now
 * </pre>
 *
 * 시계(now)를 인자로 받는 순수 함수라 경계값(자정·월요일·월초) 단위 테스트가 가능하다.
 */
public enum KpiPeriod {

    DAY,
    WEEK,
    MONTH;

    /** 기간 시작(포함) — KST 달력 경계를 UTC 시각으로 변환 */
    public Instant startAt(Instant now) {
        ZonedDateTime kstNow = now.atZone(ShiftUtil.KST);
        ZonedDateTime startOfDay = kstNow.toLocalDate().atStartOfDay(ShiftUtil.KST);
        return switch (this) {
            case DAY -> startOfDay.toInstant();
            case WEEK -> startOfDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toInstant();
            case MONTH -> startOfDay.withDayOfMonth(1).toInstant();
        };
    }
}
