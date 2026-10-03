package com.fabwatch.equipment.dto;

/** KPI 응답 수치 반올림 — 부동소수 꼬리(0.30000000000000004)가 화면/로그에 새지 않게 한다. null은 그대로. */
final class KpiRound {

    private KpiRound() {
    }

    /** MTBF 시간: 소수 2자리 */
    static Double hours(Double value) {
        return round(value, 100.0);
    }

    /** MTTR 분: 소수 2자리 */
    static Double minutes(Double value) {
        return round(value, 100.0);
    }

    /** 가동률 비율(0~1): 소수 4자리 */
    static Double ratio(Double value) {
        return round(value, 10_000.0);
    }

    private static Double round(Double value, double scale) {
        return value == null ? null : Math.round(value * scale) / scale;
    }
}
