package com.fabwatch.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** 교대 판정 (D=08~20시 KST, N=20~08시 KST) — docs/03 F-7 */
class ShiftUtilTest {

    @Test
    @DisplayName("KST 08:00은 주간(D), 07:59는 야간(N)")
    void 주간_시작_경계() {
        assertThat(ShiftUtil.shiftCode(kst(8, 0))).isEqualTo("D");
        assertThat(ShiftUtil.shiftCode(kst(7, 59))).isEqualTo("N");
    }

    @Test
    @DisplayName("KST 20:00은 야간(N), 19:59는 주간(D)")
    void 야간_시작_경계() {
        assertThat(ShiftUtil.shiftCode(kst(20, 0))).isEqualTo("N");
        assertThat(ShiftUtil.shiftCode(kst(19, 59))).isEqualTo("D");
    }

    @Test
    @DisplayName("UTC 자정(=KST 09:00)은 주간(D) — UTC 기준으로 판정하지 않는다")
    void UTC가_아닌_KST_기준() {
        Instant utcMidnight = LocalDateTime.of(2026, 8, 7, 0, 0).atZone(ZoneId.of("UTC")).toInstant();
        assertThat(ShiftUtil.isDayShift(utcMidnight)).isTrue();
    }

    private Instant kst(int hour, int minute) {
        return LocalDateTime.of(2026, 8, 7, hour, minute).atZone(ShiftUtil.KST).toInstant();
    }
}
