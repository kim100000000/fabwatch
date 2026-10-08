package com.fabwatch.inspection.dto;

/**
 * 점검 이력 입력 길이 제한.
 *
 * content/actionTaken 컬럼은 MySQL text(최대 65,535 바이트)다. Bean Validation @Size는 UTF-16 단위 수를 세고,
 * UTF-16 단위 하나는 UTF-8로 최대 3바이트이므로(4바이트 문자는 2단위) 20,000단위는 최대 60,000바이트 — 항상 안전하다.
 * (이전 65,535는 문자 수 기준이라 한글처럼 3바이트 문자가 21,846자를 넘으면 INSERT가 실패해 500이 났다.)
 * 프론트는 5,000자로 제한 중이라 API 계약과 충돌하지 않는다.
 */
public final class InspectionLimits {

    public static final int CONTENT_MAX = 20_000;
    /** 한 점검 이력에 붙는 체크리스트 판정 개수 상한 */
    public static final int CHECK_RESULTS_MAX = 200;

    private InspectionLimits() {
    }
}
