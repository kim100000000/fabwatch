package com.fabwatch.equipment.service;

import com.fabwatch.equipment.entity.EquipmentStatus;
import com.fabwatch.equipment.service.KpiCalculator.StatusTransition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static com.fabwatch.equipment.entity.EquipmentStatus.DOWN;
import static com.fabwatch.equipment.entity.EquipmentStatus.IDLE;
import static com.fabwatch.equipment.entity.EquipmentStatus.PM;
import static com.fabwatch.equipment.entity.EquipmentStatus.RUN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * KPI 계산 단위 테스트 — docs/12 §4.4 KPI-1~6 + 경계값(T-4: UTC 저장, KST 경계).
 * 기준일: 2026-10-05(월) KST. 시각 헬퍼 kst()는 KST 벽시계를 UTC Instant로 바꾼다.
 */
class KpiCalculatorTest {

    private static final double EPS = 1e-9;

    private static Instant kst(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(com.fabwatch.common.util.ShiftUtil.KST).toInstant();
    }

    private static StatusTransition t(EquipmentStatus to, String kstTime) {
        return new StatusTransition(to, kst(kstTime));
    }

    // 기간: 2026-10-05 00:00 ~ 12:00 KST (12시간)
    private static final Instant START = kst("2026-10-05T00:00:00");
    private static final Instant END = kst("2026-10-05T12:00:00");

    @Test
    @DisplayName("KPI-1: DOWN 0회 → MTBF null(고장 없음, 0 나눗셈 없음), MTTR null, 가동률 계산됨")
    void DOWN_0회() {
        KpiTotals totals = KpiCalculator.calculate(List.of(t(RUN, "2026-10-04T20:00:00")), START, END);

        assertThat(totals.downCount()).isZero();
        assertThat(totals.mtbfHours()).isNull();
        assertThat(totals.mttrMin()).isNull();
        assertThat(totals.availability()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("KPI-2/3: MTBF = ΣRUN/DOWN 진입 횟수, MTTR = Σ(DOWN 진입~이탈)/완료 건수 — 정확한 수치")
    void MTBF_MTTR_정확값() {
        // RUN 00-04 / DOWN 04-05 / RUN 05-09 / DOWN 09-09:30 / RUN 09:30-12
        List<StatusTransition> logs = List.of(
                t(RUN, "2026-10-04T22:00:00"),
                t(DOWN, "2026-10-05T04:00:00"),
                t(RUN, "2026-10-05T05:00:00"),
                t(DOWN, "2026-10-05T09:00:00"),
                t(RUN, "2026-10-05T09:30:00"));

        KpiTotals totals = KpiCalculator.calculate(logs, START, END);

        assertThat(totals.downCount()).isEqualTo(2);
        assertThat(totals.mtbfHours()).isCloseTo((4 + 4 + 2.5) / 2, within(EPS)); // 5.25h
        assertThat(totals.mttrMin()).isCloseTo((60 + 30) / 2.0, within(EPS));     // 45분
        assertThat(totals.availability()).isCloseTo(10.5 / 12, within(EPS));
    }

    @Test
    @DisplayName("KPI-4: 가동률 분모에서 PM 시간을 제외한다 (RUN 4h, PM 2h, IDLE 6h → 4/10)")
    void 가동률_PM_분모_제외() {
        List<StatusTransition> logs = List.of(
                t(RUN, "2026-10-04T22:00:00"),
                t(PM, "2026-10-05T04:00:00"),
                t(IDLE, "2026-10-05T06:00:00"));

        KpiTotals totals = KpiCalculator.calculate(logs, START, END);

        assertThat(totals.availability()).isCloseTo(4.0 / (12 - 2), within(EPS)); // PM 포함 시 4/12와 구분
        assertThat(totals.pmMillis()).isEqualTo(2 * 3_600_000L);
    }

    @Test
    @DisplayName("KPI-5: 자정(KST)을 걸친 DOWN은 기간 시작으로 절삭 — 기간 전 진입이라 downCount 0, MTTR은 절삭 길이")
    void 자정_걸친_DOWN_절삭() {
        // DOWN 전날 23:00 KST 진입 → 당일 01:00 이탈(IDLE). 기간 내 길이 = 00:00~01:00 = 60분
        List<StatusTransition> logs = List.of(
                t(RUN, "2026-10-04T08:00:00"),
                t(DOWN, "2026-10-04T23:00:00"),
                t(IDLE, "2026-10-05T01:00:00"));

        KpiTotals totals = KpiCalculator.calculate(logs, START, END);

        assertThat(totals.downCount()).isZero();
        assertThat(totals.mtbfHours()).isNull();
        assertThat(totals.completedDownCount()).isEqualTo(1);
        assertThat(totals.mttrMin()).isCloseTo(60.0, within(EPS));
        assertThat(totals.runMillis()).isZero();
    }

    @Test
    @DisplayName("KPI-5 + UTC↔KST 9시간 차: UTC로는 전날(10/04 15:30Z)인 00:30 KST 진입은 KST 오늘 기간에 포함")
    void UTC_날짜_다른_케이스_기간_포함() {
        Instant utcPrevDay = Instant.parse("2026-10-04T15:30:00Z"); // = 2026-10-05 00:30 KST
        List<StatusTransition> logs = List.of(
                t(RUN, "2026-10-04T08:00:00"),
                new StatusTransition(DOWN, utcPrevDay),
                t(RUN, "2026-10-05T01:30:00"));

        KpiTotals totals = KpiCalculator.calculate(logs, START, END);

        assertThat(totals.downCount()).isEqualTo(1);
        assertThat(totals.mttrMin()).isCloseTo(60.0, within(EPS));
    }

    @Test
    @DisplayName("UTC로는 오늘(10/04 14:59Z)이지만 KST로는 전날 23:59인 로그는 기간 밖 → 시작 시점 상태로만 쓰인다")
    void UTC_날짜_다른_케이스_기간_제외() {
        Instant beforeKstMidnight = Instant.parse("2026-10-04T14:59:00Z"); // = 2026-10-04 23:59 KST
        List<StatusTransition> logs = List.of(new StatusTransition(DOWN, beforeKstMidnight),
                t(RUN, "2026-10-05T02:00:00"));

        KpiTotals totals = KpiCalculator.calculate(logs, START, END);

        assertThat(totals.downCount()).isZero(); // 진입이 기간 밖
        assertThat(totals.mttrMin()).isCloseTo(120.0, within(EPS)); // 00:00~02:00 절삭
    }

    @Test
    @DisplayName("월요일 경계: 주 기간(월 00:00 KST)에 걸친 DOWN은 월요일 00:00으로 절삭")
    void 월요일_경계_절삭() {
        Instant now = kst("2026-10-07T12:00:00");
        Instant weekStart = KpiPeriod.WEEK.startAt(now);
        assertThat(weekStart).isEqualTo(kst("2026-10-05T00:00:00"));

        // 일요일 22:00 진입 → 월요일 02:00 이탈 → 주 기간 내 길이 120분
        List<StatusTransition> logs = List.of(
                t(RUN, "2026-10-01T00:00:00"),
                t(DOWN, "2026-10-04T22:00:00"),
                t(RUN, "2026-10-05T02:00:00"));

        KpiTotals totals = KpiCalculator.calculate(logs, weekStart, now);

        assertThat(totals.mttrMin()).isCloseTo(120.0, within(EPS));
        assertThat(totals.downCount()).isZero();
    }

    @Test
    @DisplayName("월 경계: 월 기간(1일 00:00 KST)에 걸친 DOWN은 월초로 절삭, 진입은 전월이라 downCount 0")
    void 월_경계_절삭() {
        Instant now = kst("2026-11-10T00:00:00");
        Instant monthStart = KpiPeriod.MONTH.startAt(now);
        assertThat(monthStart).isEqualTo(kst("2026-11-01T00:00:00"));

        List<StatusTransition> logs = List.of(
                t(RUN, "2026-10-01T00:00:00"),
                t(DOWN, "2026-10-31T23:00:00"),
                t(IDLE, "2026-11-01T03:00:00"));

        KpiTotals totals = KpiCalculator.calculate(logs, monthStart, now);

        assertThat(totals.downCount()).isZero();
        assertThat(totals.mttrMin()).isCloseTo(180.0, within(EPS));
    }

    @Test
    @DisplayName("KPI-6: 진행 중 DOWN은 MTTR 제외(완료 건 0 → null)하되 downCount·MTBF에는 반영, 진행 중 RUN은 now까지")
    void 진행중_DOWN_제외() {
        List<StatusTransition> logs = List.of(
                t(RUN, "2026-10-04T20:00:00"),
                t(DOWN, "2026-10-05T10:00:00")); // 이탈 없음, now=12:00

        KpiTotals totals = KpiCalculator.calculate(logs, START, END);

        assertThat(totals.downCount()).isEqualTo(1);
        assertThat(totals.mttrMin()).isNull();
        assertThat(totals.mtbfHours()).isCloseTo(10.0, within(EPS));
        assertThat(totals.availability()).isCloseTo(10.0 / 12, within(EPS));
    }

    @Test
    @DisplayName("진행 중 DOWN이 있어도 완료된 다른 DOWN 건만으로 MTTR 계산")
    void 진행중_DOWN_완료건_혼재() {
        List<StatusTransition> logs = List.of(
                t(RUN, "2026-10-04T20:00:00"),
                t(DOWN, "2026-10-05T01:00:00"),
                t(IDLE, "2026-10-05T03:00:00"), // 완료 120분
                t(RUN, "2026-10-05T04:00:00"),
                t(DOWN, "2026-10-05T11:00:00")); // 진행 중

        KpiTotals totals = KpiCalculator.calculate(logs, START, END);

        assertThat(totals.downCount()).isEqualTo(2);
        assertThat(totals.completedDownCount()).isEqualTo(1);
        assertThat(totals.mttrMin()).isCloseTo(120.0, within(EPS));
    }

    @Test
    @DisplayName("로그가 전혀 없으면 빈 결과 — 전부 null, downCount 0")
    void 로그_없음() {
        KpiTotals totals = KpiCalculator.calculate(List.of(), START, END);

        assertThat(totals).isEqualTo(KpiTotals.EMPTY);
        assertThat(totals.availability()).isNull();
        assertThat(totals.mtbfHours()).isNull();
        assertThat(totals.mttrMin()).isNull();
    }

    @Test
    @DisplayName("시작 전 상태 불명: 기간 내 첫 로그 이후부터만 집계한다 (그 앞 구간은 제외)")
    void 시작_전_상태_불명() {
        // 첫 로그(설비 등록 RUN)가 06:00 — 00~06시는 제외, 06~12시 6시간만 집계
        KpiTotals totals = KpiCalculator.calculate(List.of(t(RUN, "2026-10-05T06:00:00")), START, END);

        assertThat(totals.totalMillis()).isEqualTo(6 * 3_600_000L);
        assertThat(totals.availability()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("시작 전 상태 불명 + 첫 로그가 DOWN이면 그 진입은 기간 내라 downCount에 포함")
    void 시작_전_불명_첫로그_DOWN() {
        List<StatusTransition> logs = List.of(t(DOWN, "2026-10-05T06:00:00"), t(IDLE, "2026-10-05T07:00:00"));

        KpiTotals totals = KpiCalculator.calculate(logs, START, END);

        assertThat(totals.downCount()).isEqualTo(1);
        assertThat(totals.mttrMin()).isCloseTo(60.0, within(EPS));
        assertThat(totals.totalMillis()).isEqualTo(6 * 3_600_000L);
    }

    @Test
    @DisplayName("기간 끝(now) 이후에 생긴 로그만 있으면 빈 결과")
    void 모든_로그가_기간_이후() {
        KpiTotals totals = KpiCalculator.calculate(List.of(t(RUN, "2026-10-05T13:00:00")), START, END);
        assertThat(totals).isEqualTo(KpiTotals.EMPTY);
    }

    @ParameterizedTest(name = "DOWN → {0} 이탈도 MTTR 동일(90분)")
    @EnumSource(value = EquipmentStatus.class, names = {"IDLE", "RUN", "PM"})
    @DisplayName("KPI-3: DOWN 이탈 후 상태(IDLE/RUN/PM)와 무관하게 MTTR이 같다")
    void 이탈_후_상태_무관_MTTR(EquipmentStatus exitTo) {
        List<StatusTransition> logs = List.of(
                t(RUN, "2026-10-04T20:00:00"),
                t(DOWN, "2026-10-05T02:00:00"),
                new StatusTransition(exitTo, kst("2026-10-05T03:30:00")));

        KpiTotals totals = KpiCalculator.calculate(logs, START, END);

        assertThat(totals.completedDownCount()).isEqualTo(1);
        assertThat(totals.mttrMin()).isCloseTo(90.0, within(EPS));
        assertThat(totals.downCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("DOWN → PM 이탈: PM 시간은 가동률 분모에서 빠지고 DOWN 시간은 분모에 남는다")
    void DOWN에서_PM_이탈_분모() {
        // RUN 00-02 / DOWN 02-04 / PM 04-06 / IDLE 06-12
        List<StatusTransition> logs = List.of(
                t(RUN, "2026-10-04T20:00:00"),
                t(DOWN, "2026-10-05T02:00:00"),
                t(PM, "2026-10-05T04:00:00"),
                t(IDLE, "2026-10-05T06:00:00"));

        KpiTotals totals = KpiCalculator.calculate(logs, START, END);

        assertThat(totals.mttrMin()).isCloseTo(120.0, within(EPS));
        assertThat(totals.availability()).isCloseTo(2.0 / (12 - 2), within(EPS));
    }

    @Test
    @DisplayName("분모 0: 기간 내내 PM이면 가동률 null, MTBF·MTTR도 null")
    void 분모_0() {
        KpiTotals totals = KpiCalculator.calculate(List.of(t(PM, "2026-10-04T20:00:00")), START, END);

        assertThat(totals.totalMillis()).isEqualTo(totals.pmMillis());
        assertThat(totals.availability()).isNull();
        assertThat(totals.mtbfHours()).isNull();
        assertThat(totals.mttrMin()).isNull();
    }

    @Test
    @DisplayName("기간 시작 시각과 정확히 같은 로그는 '시작 시점 상태'로 쓰이고 중복 계산되지 않는다")
    void 시작시각_정각_로그() {
        KpiTotals totals = KpiCalculator.calculate(List.of(t(RUN, "2026-10-05T00:00:00")), START, END);

        assertThat(totals.totalMillis()).isEqualTo(12 * 3_600_000L);
        assertThat(totals.availability()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("입력 로그가 시간 역순이어도 정렬해서 계산하고, 동일 상태 전이 로그는 무시한다")
    void 정렬_및_중복_상태_무시() {
        List<StatusTransition> logs = List.of(
                t(RUN, "2026-10-05T07:00:00"),   // DOWN → RUN
                t(DOWN, "2026-10-05T06:00:00"),
                t(DOWN, "2026-10-05T05:00:00"),  // 중복 DOWN 로그 — 무시, 진입은 05:00 한 번
                t(RUN, "2026-10-04T20:00:00"));

        KpiTotals totals = KpiCalculator.calculate(logs, START, END);

        assertThat(totals.downCount()).isEqualTo(1);
        assertThat(totals.mttrMin()).isCloseTo(120.0, within(EPS)); // 05:00~07:00
    }

    @Test
    @DisplayName("요약 합산 재계산: 설비 A(가동률 1.0, 10h)+B(0.5, 2h) = 11/12 — 비율의 평균(0.75)이 아니다")
    void 합산_후_재계산() {
        KpiTotals a = new KpiTotals(10 * 3_600_000L, 0, 10 * 3_600_000L, 0, 0, 0);
        KpiTotals b = new KpiTotals(1 * 3_600_000L, 0, 2 * 3_600_000L, 1, 30 * 60_000L, 1);

        KpiTotals sum = a.plus(b);

        assertThat(sum.availability()).isCloseTo(11.0 / 12, within(EPS));
        assertThat(sum.availability()).isNotCloseTo((1.0 + 0.5) / 2, within(0.01));
        assertThat(sum.mtbfHours()).isCloseTo(11.0 / 1, within(EPS));
        assertThat(sum.mttrMin()).isCloseTo(30.0, within(EPS));
        assertThat(sum.downCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("동시각 로그가 여러 개면 입력 순서상 마지막이 시작 시점 상태")
    void 동시각_로그_마지막이_상태() {
        List<StatusTransition> logs = List.of(
                t(RUN, "2026-10-05T00:00:00"),
                t(DOWN, "2026-10-05T00:00:00"),
                t(IDLE, "2026-10-05T01:00:00"));

        KpiTotals totals = KpiCalculator.calculate(logs, START, END);

        assertThat(totals.mttrMin()).isCloseTo(60.0, within(EPS));
        assertThat(totals.downCount()).isZero();
    }
}
