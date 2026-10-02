package com.fabwatch.inspection.service;

import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import com.fabwatch.common.util.ShiftUtil;
import com.fabwatch.inspection.entity.PmSchedule.CycleType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PM 일정 계산 단위 테스트 (docs/12 §4.3 PM-1~PM-4). 모든 날짜는 KST 기준으로 적었다.
 * 2026-07-06 = 월요일.
 */
class PmScheduleCalculatorTest {

    private static Instant kst(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(ShiftUtil.KST).toInstant();
    }

    // ------------------------------------------------------------ 다음 예정일

    @Test
    @DisplayName("PM-1 DAILY: 수행 시각 +1일 (시각 유지)")
    void daily() {
        assertThat(PmScheduleCalculator.nextDueAt(CycleType.DAILY, null, kst("2026-07-06T08:00:00")))
                .isEqualTo(kst("2026-07-07T08:00:00"));
    }

    @Test
    @DisplayName("DAILY: 월말·연말 경계를 넘긴다")
    void dailyBoundary() {
        assertThat(PmScheduleCalculator.nextDueAt(CycleType.DAILY, null, kst("2026-12-31T22:30:00")))
                .isEqualTo(kst("2027-01-01T22:30:00"));
        assertThat(PmScheduleCalculator.nextDueAt(CycleType.DAILY, null, kst("2028-02-28T10:00:00")))
                .isEqualTo(kst("2028-02-29T10:00:00")); // 윤년
    }

    @Test
    @DisplayName("PM-2 WEEKLY: 수행일 이후 첫 해당 요일 (월요일 수행, 수요일 지정 → 같은 주 수요일)")
    void weeklyLaterThisWeek() {
        assertThat(PmScheduleCalculator.nextDueAt(CycleType.WEEKLY, 3, kst("2026-07-06T09:00:00")))
                .isEqualTo(kst("2026-07-08T09:00:00"));
    }

    @Test
    @DisplayName("WEEKLY: 같은 요일에 수행했으면 다음 주 같은 요일 (당일은 포함하지 않는다)")
    void weeklySameWeekdayGoesNextWeek() {
        assertThat(PmScheduleCalculator.nextDueAt(CycleType.WEEKLY, 1, kst("2026-07-06T09:00:00")))
                .isEqualTo(kst("2026-07-13T09:00:00"));
    }

    @Test
    @DisplayName("WEEKLY: 일요일(7) 경계 — 일요일 수행 후 월요일 지정은 다음날, 일요일 지정은 다음 주")
    void weeklySundayBoundary() {
        assertThat(PmScheduleCalculator.nextDueAt(CycleType.WEEKLY, 1, kst("2026-07-12T09:00:00")))
                .isEqualTo(kst("2026-07-13T09:00:00"));
        assertThat(PmScheduleCalculator.nextDueAt(CycleType.WEEKLY, 7, kst("2026-07-12T09:00:00")))
                .isEqualTo(kst("2026-07-19T09:00:00"));
        assertThat(PmScheduleCalculator.nextDueAt(CycleType.WEEKLY, 7, kst("2026-07-06T09:00:00")))
                .isEqualTo(kst("2026-07-12T09:00:00"));
    }

    @Test
    @DisplayName("WEEKLY: 요일 판정은 UTC가 아니라 KST 날짜 기준 (UTC 월요일 16시 = KST 화요일 01시)")
    void weeklyUsesKstCalendar() {
        Instant lastDone = Instant.parse("2026-07-06T16:00:00Z"); // KST 2026-07-07(화) 01:00
        assertThat(PmScheduleCalculator.nextDueAt(CycleType.WEEKLY, 2, lastDone))
                .isEqualTo(kst("2026-07-14T01:00:00")); // 화요일 지정 → 같은 날이 아니라 다음 주 화요일
    }

    @Test
    @DisplayName("PM-3 MONTHLY(28): 28일에 수행하면 다음 달 28일")
    void monthlyNextMonth() {
        assertThat(PmScheduleCalculator.nextDueAt(CycleType.MONTHLY, 28, kst("2026-07-28T10:00:00")))
                .isEqualTo(kst("2026-08-28T10:00:00"));
    }

    @Test
    @DisplayName("MONTHLY: 지정 일자가 이번 달에 아직 남았으면 이번 달")
    void monthlyLaterThisMonth() {
        assertThat(PmScheduleCalculator.nextDueAt(CycleType.MONTHLY, 28, kst("2026-07-06T10:00:00")))
                .isEqualTo(kst("2026-07-28T10:00:00"));
    }

    @Test
    @DisplayName("MONTHLY: 월말(31일) 수행 → 다음 달 28일, 2월 평년/윤년·연말 경계")
    void monthlyMonthEndBoundary() {
        assertThat(PmScheduleCalculator.nextDueAt(CycleType.MONTHLY, 28, kst("2026-07-31T10:00:00")))
                .isEqualTo(kst("2026-08-28T10:00:00"));
        assertThat(PmScheduleCalculator.nextDueAt(CycleType.MONTHLY, 28, kst("2027-01-31T10:00:00")))
                .isEqualTo(kst("2027-02-28T10:00:00")); // 평년 2월 말일
        assertThat(PmScheduleCalculator.nextDueAt(CycleType.MONTHLY, 28, kst("2028-01-31T10:00:00")))
                .isEqualTo(kst("2028-02-28T10:00:00")); // 윤년에도 28일이라 29일 문제 없음
        assertThat(PmScheduleCalculator.nextDueAt(CycleType.MONTHLY, 28, kst("2026-12-30T10:00:00")))
                .isEqualTo(kst("2027-01-28T10:00:00")); // 연말 → 다음 해
        assertThat(PmScheduleCalculator.nextDueAt(CycleType.MONTHLY, 1, kst("2027-01-31T10:00:00")))
                .isEqualTo(kst("2027-02-01T10:00:00"));
    }

    // ------------------------------------------------------------ 경과 판정

    @Test
    @DisplayName("PM-4 경과 판정: 예정 시각 당일 정각까지는 정상, 1초라도 지나면 OVERDUE")
    void overdueBoundary() {
        Instant due = kst("2026-07-10T08:00:00");
        assertThat(PmScheduleCalculator.isOverdue(due, due.minusSeconds(1))).isFalse();
        assertThat(PmScheduleCalculator.isOverdue(due, due)).isFalse();
        assertThat(PmScheduleCalculator.isOverdue(due, due.plusSeconds(1))).isTrue();
    }

    @Test
    @DisplayName("경과 일수: 24시간 단위 내림, 경과 전은 0")
    void overdueDays() {
        Instant due = kst("2026-07-10T08:00:00");
        assertThat(PmScheduleCalculator.overdueDays(due, due.minus(5, ChronoUnit.DAYS))).isZero();
        assertThat(PmScheduleCalculator.overdueDays(due, due)).isZero();
        assertThat(PmScheduleCalculator.overdueDays(due, due.plus(5, ChronoUnit.HOURS))).isZero();
        assertThat(PmScheduleCalculator.overdueDays(due, due.plus(2, ChronoUnit.DAYS).plus(23, ChronoUnit.HOURS))).isEqualTo(2);
        assertThat(PmScheduleCalculator.overdueDays(due, due.plus(3, ChronoUnit.DAYS))).isEqualTo(3);
    }

    @Test
    @DisplayName("3일 초과 판정: 정확히 3일은 아직 아니고, 그 이후부터 알람 대상")
    void alarmDueBoundary() {
        Instant due = kst("2026-07-10T08:00:00");
        assertThat(PmScheduleCalculator.isAlarmDue(due, due.plus(2, ChronoUnit.DAYS))).isFalse();
        assertThat(PmScheduleCalculator.isAlarmDue(due, due.plus(3, ChronoUnit.DAYS))).isFalse();
        assertThat(PmScheduleCalculator.isAlarmDue(due, due.plus(3, ChronoUnit.DAYS).plusSeconds(1))).isTrue();
        assertThat(PmScheduleCalculator.alarmDueThreshold(due.plus(10, ChronoUnit.DAYS)))
                .isEqualTo(due.plus(7, ChronoUnit.DAYS));
    }

    // ------------------------------------------------------------ 주기 값 검증

    @Test
    @DisplayName("주기 값 검증: WEEKLY 1~7, MONTHLY 1~28, DAILY는 null — 위반은 400 VALIDATION_ERROR")
    void validate() {
        PmScheduleCalculator.validate(CycleType.DAILY, null);
        PmScheduleCalculator.validate(CycleType.WEEKLY, 1);
        PmScheduleCalculator.validate(CycleType.WEEKLY, 7);
        PmScheduleCalculator.validate(CycleType.MONTHLY, 1);
        PmScheduleCalculator.validate(CycleType.MONTHLY, 28);

        assertInvalid(CycleType.DAILY, 1);
        assertInvalid(CycleType.WEEKLY, null);
        assertInvalid(CycleType.WEEKLY, 0);
        assertInvalid(CycleType.WEEKLY, 8);
        assertInvalid(CycleType.MONTHLY, null);
        assertInvalid(CycleType.MONTHLY, 0);
        assertInvalid(CycleType.MONTHLY, 29);
        assertInvalid(null, null);
    }

    private static void assertInvalid(CycleType type, Integer value) {
        assertThatThrownBy(() -> PmScheduleCalculator.validate(type, value))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }
}
