package com.fabwatch.aireport.prompt;

import com.fabwatch.common.util.ShiftUtil;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

/** 리포트 본문·제목용 KST 시각 포맷 (DB/API는 UTC, 사람이 읽는 문서는 KST — docs/03 F-7). */
public final class ReportTimeFormat {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private ReportTimeFormat() {
    }

    /** 예: 2026-10-02 23:05 (KST) */
    public static String kst(Instant at) {
        return at == null ? "-" : DATE_TIME.format(at.atZone(ShiftUtil.KST));
    }

    /** 예: 23:05 (KST) */
    public static String kstTime(Instant at) {
        return at == null ? "-" : TIME.format(at.atZone(ShiftUtil.KST));
    }

    /** 리포트 제목 — '고장 리포트 — {설비코드} {발생일시 KST}' (계약 1) */
    public static String title(String equipmentCode, Instant occurredAt) {
        return "고장 리포트 — " + equipmentCode + " " + kst(occurredAt);
    }
}
