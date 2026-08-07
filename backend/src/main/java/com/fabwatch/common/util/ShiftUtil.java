package com.fabwatch.common.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 교대 판정 유틸 — D = 08:00~20:00 KST, N = 20:00~08:00 KST (docs/03 F-7).
 * DB/API는 UTC지만 교대 판정만은 KST 기준으로 계산한다.
 */
public final class ShiftUtil {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private ShiftUtil() {
    }

    /** 주간(D) 여부. 08:00 이상 20:00 미만이면 D. */
    public static boolean isDayShift(Instant at) {
        ZonedDateTime kst = at.atZone(KST);
        int hour = kst.getHour();
        return hour >= 8 && hour < 20;
    }

    /** 교대 코드 문자열("D"/"N"). enum Shift는 inspection 도메인 소속이라 여기서는 문자열로만 다룬다. */
    public static String shiftCode(Instant at) {
        return isDayShift(at) ? "D" : "N";
    }
}
