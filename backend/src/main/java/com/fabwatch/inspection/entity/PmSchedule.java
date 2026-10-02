package com.fabwatch.inspection.entity;

import com.fabwatch.common.entity.SoftDeletableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;

/**
 * PM 스케줄 (docs/05 pm_schedules, docs/03 F-3.3) — 설비당 1개(MVP).
 */
@Entity
@Table(name = "pm_schedules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE pm_schedules SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
public class PmSchedule extends SoftDeletableEntity {

    public enum CycleType {
        DAILY, WEEKLY, MONTHLY
    }

    @Column(name = "equipment_id", nullable = false, unique = true)
    private Long equipmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "cycle_type", length = 10, nullable = false)
    private CycleType cycleType;

    /** WEEKLY=요일(1~7), MONTHLY=일자(1~28), DAILY=NULL */
    @Column(name = "cycle_value")
    private Integer cycleValue;

    @Column(name = "last_done_at")
    private Instant lastDoneAt;

    @Column(name = "next_due_at", nullable = false)
    private Instant nextDueAt;

    /** 지연 알람 중복 방지 플래그 */
    @Column(name = "overdue_alarm_sent", nullable = false)
    private boolean overdueAlarmSent = false;

    @Builder
    private PmSchedule(Long equipmentId, CycleType cycleType, Integer cycleValue,
                       Instant lastDoneAt, Instant nextDueAt, boolean overdueAlarmSent) {
        this.equipmentId = equipmentId;
        this.cycleType = cycleType;
        this.cycleValue = cycleValue;
        this.lastDoneAt = lastDoneAt;
        this.nextDueAt = nextDueAt;
        this.overdueAlarmSent = overdueAlarmSent;
    }

    /** 주기 설정/변경 — 다음 예정일을 호출 측(PmScheduleCalculator)이 계산해서 넘긴다. 지연 알람 플래그는 리셋. */
    public void reschedule(CycleType cycleType, Integer cycleValue, Instant nextDueAt) {
        this.cycleType = cycleType;
        this.cycleValue = cycleValue;
        this.nextDueAt = nextDueAt;
        this.overdueAlarmSent = false;
    }

    /** PM 수행 완료 — last_done 갱신 + next_due 재계산 + 지연 알람 플래그 초기화 (docs/12 PM-7) */
    public void markDone(Instant doneAt, Instant nextDueAt) {
        this.lastDoneAt = doneAt;
        this.nextDueAt = nextDueAt;
        this.overdueAlarmSent = false;
    }

    /** 지연 알람 이벤트 발행 완료 표시 — 중복 발행 방지 */
    public void markOverdueAlarmSent() {
        this.overdueAlarmSent = true;
    }
}
