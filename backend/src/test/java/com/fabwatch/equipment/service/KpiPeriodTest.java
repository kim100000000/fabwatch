package com.fabwatch.equipment.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** KPI 기간 경계 — 모든 경계는 KST 달력 기준, 결과는 UTC Instant (T-4). 2026-10-05는 월요일. */
class KpiPeriodTest {

    private static Instant kst(String local) {
        return LocalDateTime.parse(local).atZone(com.fabwatch.common.util.ShiftUtil.KST).toInstant();
    }

    @Test
    @DisplayName("DAY: 오늘 KST 00:00 — UTC 날짜가 전날인 시각(KST 새벽)에도 KST 오늘 자정")
    void 오늘_KST_자정() {
        Instant now = Instant.parse("2026-10-04T16:30:00Z"); // KST 10/05 01:30 (UTC는 10/04)
        assertThat(KpiPeriod.DAY.startAt(now)).isEqualTo(Instant.parse("2026-10-04T15:00:00Z"));
    }

    @Test
    @DisplayName("DAY: KST 23:59는 아직 같은 날, 00:00 정각이면 새 날(빈 기간)")
    void 자정_정각_경계() {
        assertThat(KpiPeriod.DAY.startAt(kst("2026-10-05T23:59:59"))).isEqualTo(kst("2026-10-05T00:00:00"));
        assertThat(KpiPeriod.DAY.startAt(kst("2026-10-06T00:00:00"))).isEqualTo(kst("2026-10-06T00:00:00"));
    }

    @Test
    @DisplayName("WEEK: 월요일 KST 00:00 — 일요일 밤은 직전 월요일, UTC로 일요일인 월요일 새벽은 이번 월요일")
    void 주_시작_월요일() {
        assertThat(KpiPeriod.WEEK.startAt(kst("2026-10-04T23:30:00"))).isEqualTo(kst("2026-09-28T00:00:00"));
        assertThat(KpiPeriod.WEEK.startAt(kst("2026-10-05T00:00:00"))).isEqualTo(kst("2026-10-05T00:00:00"));
        Instant mondayDawnKst = Instant.parse("2026-10-04T16:00:00Z"); // UTC 일요일, KST 월요일 01:00
        assertThat(KpiPeriod.WEEK.startAt(mondayDawnKst)).isEqualTo(kst("2026-10-05T00:00:00"));
        assertThat(KpiPeriod.WEEK.startAt(kst("2026-10-08T12:00:00"))).isEqualTo(kst("2026-10-05T00:00:00"));
    }

    @Test
    @DisplayName("MONTH: 1일 KST 00:00 — UTC로는 전월 말(10/31 15:30Z)인 KST 11/01 00:30은 11월")
    void 월_시작_1일() {
        Instant now = Instant.parse("2026-10-31T15:30:00Z");
        assertThat(KpiPeriod.MONTH.startAt(now)).isEqualTo(Instant.parse("2026-10-31T15:00:00Z"));
        assertThat(KpiPeriod.MONTH.startAt(kst("2026-10-31T23:59:59"))).isEqualTo(kst("2026-10-01T00:00:00"));
        assertThat(KpiPeriod.MONTH.startAt(kst("2027-01-15T09:00:00"))).isEqualTo(kst("2027-01-01T00:00:00"));
    }
}
