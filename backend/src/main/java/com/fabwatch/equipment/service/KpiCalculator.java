package com.fabwatch.equipment.service;

import com.fabwatch.equipment.entity.EquipmentStatus;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * 설비 KPI(가동률/MTBF/MTTR) 계산 — 상태 전이 로그 기반 순수 함수 (docs/03 F-5.4, docs/12 §4.4 KPI-1~6).
 * DB·시계 의존이 없어 경계값(자정 KST, 월요일, 월초) 단위 테스트가 가능하다.
 *
 * 규칙
 * <ul>
 *   <li>기간 [start, end)에 걸친 구간은 기간 내로 절삭한다(KPI-5).</li>
 *   <li>기간 시작 시점의 상태 = start 이하 마지막 로그의 to_status. 그런 로그가 없으면
 *       기간 내 첫 로그 이후부터만 집계한다(그 앞은 상태 불명 → 제외). 로그가 전혀 없으면 빈 결과.</li>
 *   <li>마지막 로그 이후 진행 중 구간은 end(=now)까지 계산한다(KPI-6).</li>
 *   <li>downCount = 기간 내 DOWN 진입 횟수. 기간 시작 전에 진입한 DOWN은 세지 않는다.</li>
 *   <li>MTTR 대상 = 기간 내 이탈이 확인된 DOWN. 이탈 후 상태(IDLE/RUN/PM)와 무관, 진행 중 건은 제외.
 *       시작 전에 진입해 기간 내 이탈한 건은 기간 시작부터의 길이로 센다.</li>
 *   <li>동일 상태로의 전이 로그(데이터 오염)는 무시한다.</li>
 * </ul>
 */
public final class KpiCalculator {

    /** 상태 전이 한 건 (to_status로 changedAt 시점에 진입) */
    public record StatusTransition(EquipmentStatus toStatus, Instant changedAt) {
    }

    private KpiCalculator() {
    }

    /**
     * @param logs  해당 설비의 상태 로그(순서 무관, 기간 앞 로그 포함 가능)
     * @param start 기간 시작(포함)
     * @param end   기간 끝(제외) — 보통 now
     */
    public static KpiTotals calculate(List<StatusTransition> logs, Instant start, Instant end) {
        if (logs == null || logs.isEmpty() || !start.isBefore(end)) {
            return KpiTotals.EMPTY;
        }
        List<StatusTransition> sorted = logs.stream()
                .sorted(Comparator.comparing(StatusTransition::changedAt))
                .toList();

        // 기간 시작 직전(포함) 마지막 로그 = 시작 시점 상태
        StatusTransition prior = null;
        for (StatusTransition log : sorted) {
            if (!log.changedAt().isAfter(start)) {
                prior = log;
            }
        }

        Accumulator acc = new Accumulator();
        EquipmentStatus state;
        Instant cursor;

        List<StatusTransition> inPeriod = sorted.stream()
                .filter(log -> log.changedAt().isAfter(start) && log.changedAt().isBefore(end))
                .toList();
        int from = 0;

        if (prior != null) {
            state = prior.toStatus();
            cursor = start;
            // 시작 시점에 이미 DOWN이면 진입은 기간 밖이라 downCount에 넣지 않는다(이탈 시 MTTR에는 절삭 길이로 포함)
        } else {
            if (inPeriod.isEmpty()) {
                return KpiTotals.EMPTY; // 기간 안에 알 수 있는 상태가 없다
            }
            StatusTransition first = inPeriod.get(0);
            state = first.toStatus();
            cursor = first.changedAt();
            from = 1;
            if (state == EquipmentStatus.DOWN) {
                acc.downCount++;
            }
        }

        // 현재 DOWN 구간의 누적 길이(기간 내로 절삭된 길이)
        long currentDownMillis = 0;

        for (int i = from; i < inPeriod.size(); i++) {
            StatusTransition log = inPeriod.get(i);
            if (log.toStatus() == state) {
                continue; // 동일 상태 전이는 무시
            }
            long span = millisBetween(cursor, log.changedAt());
            acc.addSpan(state, span);
            if (state == EquipmentStatus.DOWN) {
                currentDownMillis += span;
                // DOWN 이탈 — 완료 건으로 집계
                acc.completedDownMillis += currentDownMillis;
                acc.completedDownCount++;
                currentDownMillis = 0;
            }
            if (log.toStatus() == EquipmentStatus.DOWN) {
                acc.downCount++;
            }
            state = log.toStatus();
            cursor = log.changedAt();
        }

        // 진행 중 구간은 end까지. 진행 중 DOWN은 MTTR 대상에서 제외(완료 집계에 넣지 않는다).
        acc.addSpan(state, millisBetween(cursor, end));

        return new KpiTotals(acc.runMillis, acc.pmMillis, acc.totalMillis,
                acc.downCount, acc.completedDownMillis, acc.completedDownCount);
    }

    private static long millisBetween(Instant from, Instant to) {
        return Math.max(0, to.toEpochMilli() - from.toEpochMilli());
    }

    private static final class Accumulator {
        long runMillis;
        long pmMillis;
        long totalMillis;
        int downCount;
        long completedDownMillis;
        int completedDownCount;

        void addSpan(EquipmentStatus state, long millis) {
            totalMillis += millis;
            if (state == EquipmentStatus.RUN) {
                runMillis += millis;
            } else if (state == EquipmentStatus.PM) {
                pmMillis += millis;
            }
        }
    }
}
