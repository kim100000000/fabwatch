package com.fabwatch.equipment.service;

/**
 * 기간 내 상태 시간 누적 합계 (밀리초). 설비별 결과를 더해 라인/전체 요약을 만들 수 있게
 * 비율이 아니라 "분자·분모 원재료"를 들고 있다 — 요약은 합산 후 재계산한다(비율의 평균 금지).
 *
 * @param runMillis           RUN 상태 누적 시간
 * @param pmMillis            PM 상태 누적 시간 (가동률 분모에서 제외)
 * @param totalMillis         집계 대상 전체 시간 (상태를 알 수 있는 구간 전부)
 * @param downCount           기간 내 DOWN 진입 횟수 (자동/수동 모두)
 * @param completedDownMillis 기간 내 이탈까지 완료된 DOWN 구간의 길이 합 (기간으로 절삭)
 * @param completedDownCount  기간 내 이탈이 확인된 DOWN 건수 (진행 중 건 제외)
 */
public record KpiTotals(long runMillis, long pmMillis, long totalMillis,
                        int downCount, long completedDownMillis, int completedDownCount) {

    private static final double MILLIS_PER_HOUR = 3_600_000.0;
    private static final double MILLIS_PER_MIN = 60_000.0;

    public static final KpiTotals EMPTY = new KpiTotals(0, 0, 0, 0, 0, 0);

    public KpiTotals plus(KpiTotals other) {
        return new KpiTotals(
                runMillis + other.runMillis,
                pmMillis + other.pmMillis,
                totalMillis + other.totalMillis,
                downCount + other.downCount,
                completedDownMillis + other.completedDownMillis,
                completedDownCount + other.completedDownCount);
    }

    /** 가동률(0~1) = RUN / (전체 − PM). 분모가 0이면 null. */
    public Double availability() {
        long denominator = totalMillis - pmMillis;
        return denominator <= 0 ? null : (double) runMillis / denominator;
    }

    /** MTBF(시간) = Σ RUN / DOWN 진입 횟수. DOWN 진입이 없으면 null("고장 없음"). */
    public Double mtbfHours() {
        return downCount == 0 ? null : runMillis / MILLIS_PER_HOUR / downCount;
    }

    /** MTTR(분) = Σ 완료된 DOWN 시간 / 완료 건수. 완료 건이 없으면 null. */
    public Double mttrMin() {
        return completedDownCount == 0 ? null : completedDownMillis / MILLIS_PER_MIN / completedDownCount;
    }
}
