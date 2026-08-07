package com.fabwatch.common.entity;

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
 * TODO: 다음 라운드에 com.fabwatch.inspection.entity 패키지로 이동 예정.
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
}
