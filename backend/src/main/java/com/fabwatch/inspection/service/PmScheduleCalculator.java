package com.fabwatch.inspection.service;

import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import com.fabwatch.common.util.ShiftUtil;
import com.fabwatch.inspection.entity.PmSchedule.CycleType;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

/**
 * PM 일정 계산 순수 함수 모음 (docs/03 F-3.3, docs/12 §4.3).
 *
 * 상태 없는 정적 유틸 — DB·시계 의존이 없어 고정 시각으로 그대로 단위 테스트한다.
 *
 * 계산 규칙 (날짜는 KST 달력 기준, 시각은 last_done의 KST 시각을 유지):
 * - DAILY   : last_done + 1일
 * - WEEKLY  : cycle_value = 요일(1=월 ~ 7=일, ISO-8601). last_done 날짜 "이후"의 첫 해당 요일
 *             (같은 요일에 수행했으면 다음 주 같은 요일)
 * - MONTHLY : cycle_value = 일자(1~28). last_done 날짜 "이후"의 첫 해당 일자
 *             (일자 상한 28 — 2월·말일 경계에서 존재하지 않는 날짜가 나오지 않게 하기 위함)
 *
 * 지연 판정: now > next_due → OVERDUE. now > next_due + 3일 → 지연 알람 대상(MAJOR).
 */
public final class PmScheduleCalculator {

    /** OVERDUE 후 이 기간을 초과하면 MAJOR "PM 지연" 알람 (docs/03 F-3.3) */
    public static final Duration ALARM_GRACE = Duration.ofDays(3);

    private PmScheduleCalculator() {
    }

    /** 주기 값 검증 — WEEKLY 1~7, MONTHLY 1~28, DAILY는 null. 위반 시 400 VALIDATION_ERROR. */
    public static void validate(CycleType cycleType, Integer cycleValue) {
        if (cycleType == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "cycleType은 필수입니다.");
        }
        switch (cycleType) {
            case DAILY -> {
                if (cycleValue != null) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR, "DAILY 주기는 cycleValue를 지정할 수 없습니다(null).");
                }
            }
            case WEEKLY -> {
                if (cycleValue == null || cycleValue < 1 || cycleValue > 7) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR, "WEEKLY 주기의 cycleValue는 요일 1(월)~7(일)이어야 합니다.");
                }
            }
            case MONTHLY -> {
                if (cycleValue == null || cycleValue < 1 || cycleValue > 28) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR, "MONTHLY 주기의 cycleValue는 일자 1~28이어야 합니다.");
                }
            }
        }
    }

    /** 마지막 수행 시각 기준 다음 예정 시각. 입력은 사전에 validate 되었다고 가정한다. */
    public static Instant nextDueAt(CycleType cycleType, Integer cycleValue, Instant lastDoneAt) {
        ZonedDateTime base = lastDoneAt.atZone(ShiftUtil.KST);
        ZonedDateTime next = switch (cycleType) {
            case DAILY -> base.plusDays(1);
            case WEEKLY -> {
                LocalDate date = base.toLocalDate()
                        .with(TemporalAdjusters.next(DayOfWeek.of(cycleValue)));
                yield base.with(date);
            }
            case MONTHLY -> {
                LocalDate date = base.toLocalDate();
                // 이번 달 해당 일자가 아직 남았으면 이번 달, 아니면(같은 날 포함) 다음 달
                LocalDate candidate = date.withDayOfMonth(cycleValue);
                if (!candidate.isAfter(date)) {
                    candidate = date.plusMonths(1).withDayOfMonth(cycleValue);
                }
                yield base.with(candidate);
            }
        };
        return next.toInstant();
    }

    /** 예정일 경과 여부 (OVERDUE) */
    public static boolean isOverdue(Instant nextDueAt, Instant now) {
        return now.isAfter(nextDueAt);
    }

    /** 경과 일수 — 24시간 단위 내림. 경과 전이면 0. */
    public static int overdueDays(Instant nextDueAt, Instant now) {
        if (!isOverdue(nextDueAt, now)) {
            return 0;
        }
        return (int) ChronoUnit.DAYS.between(nextDueAt, now);
    }

    /** 지연 알람 대상 여부 — 예정일 + 3일을 초과했는가 */
    public static boolean isAlarmDue(Instant nextDueAt, Instant now) {
        return now.isAfter(nextDueAt.plus(ALARM_GRACE));
    }

    /** 지연 알람 판정 기준 시각 — 이 시각보다 next_due가 이전이면 알람 대상 (스케줄러 쿼리용) */
    public static Instant alarmDueThreshold(Instant now) {
        return now.minus(ALARM_GRACE);
    }
}
