package com.fabwatch.aireport.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 쿼터 집계 구간 — KST 자정 경계 (KST = UTC+9, 15:00 UTC가 KST 자정) */
class KstDayRangeTest {

    @Test
    @DisplayName("KST 23:59:59(= 14:59:59Z)는 그날, 00:00:00(= 15:00:00Z)은 다음날 구간")
    void boundaryAtKstMidnight() {
        KstDayRange justBefore = KstDayRange.of(Instant.parse("2026-10-02T14:59:59Z"));
        KstDayRange justAfter = KstDayRange.of(Instant.parse("2026-10-02T15:00:00Z"));

        assertThat(justBefore.from()).isEqualTo(Instant.parse("2026-10-01T15:00:00Z"));
        assertThat(justBefore.to()).isEqualTo(Instant.parse("2026-10-02T15:00:00Z"));
        assertThat(justAfter.from()).isEqualTo(Instant.parse("2026-10-02T15:00:00Z"));
        assertThat(justAfter.to()).isEqualTo(Instant.parse("2026-10-03T15:00:00Z"));
    }

    @Test
    @DisplayName("UTC 날짜가 바뀌기 전이라도 KST로는 이미 다음날일 수 있다 (UTC 00:30 = KST 09:30 같은날)")
    void utcDateIsNotKstDate() {
        KstDayRange range = KstDayRange.of(Instant.parse("2026-10-02T00:30:00Z"));

        assertThat(range.from()).isEqualTo(Instant.parse("2026-10-01T15:00:00Z"));
        assertThat(range.to()).isEqualTo(Instant.parse("2026-10-02T15:00:00Z"));
    }
}
