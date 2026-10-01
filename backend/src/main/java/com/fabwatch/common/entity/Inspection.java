package com.fabwatch.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;

/**
 * 점검 이력 (docs/05 inspections, docs/03 F-3).
 * TODO: 다음 라운드에 com.fabwatch.inspection.entity 패키지로 이동 예정.
 */
@Entity
@Table(name = "inspections", indexes = {
        @Index(name = "idx_inspection_equipment_started", columnList = "equipment_id, started_at"),
        @Index(name = "idx_inspection_type_shift", columnList = "type, shift")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE inspections SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
public class Inspection extends SoftDeletableEntity {

    /** MySQL TEXT 상한(64KB). length를 안 주면 Hibernate가 255로 잡아 tinytext가 만들어진다. */
    private static final int TEXT_LENGTH = 65_535;

    /** PM=예방보전, BM=사후보전 */
    public enum Type {
        PM, BM
    }

    /** 교대조 — D = 08~20시 KST, N = 20~08시 KST (docs/03 F-7) */
    public enum Shift {
        D, N
    }

    /** 4M 원인 분류 — BM 필수 (docs/03 F-3.1) */
    public enum Cause4M {
        MAN, MACHINE, MATERIAL, METHOD
    }

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 10, nullable = false)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(name = "shift", length = 1, nullable = false)
    private Shift shift;

    @Column(name = "worker_id", nullable = false)
    private Long workerId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at", nullable = false)
    private Instant endedAt;

    /** 소요시간(분) — 자동 계산 */
    @Column(name = "duration_min")
    private Integer durationMin;

    @Lob
    @Column(name = "content", nullable = false, length = TEXT_LENGTH)
    private String content;

    @Lob
    @Column(name = "action_taken", length = TEXT_LENGTH)
    private String actionTaken;

    @Enumerated(EnumType.STRING)
    @Column(name = "cause_4m", length = 20)
    private Cause4M cause4m;

    @Column(name = "cause_detail", length = 300)
    private String causeDetail;

    /** BM-알람 연계 (nullable) */
    @Column(name = "alarm_id")
    private Long alarmId;

    /** 체크리스트 NG 존재 여부 */
    @Column(name = "has_ng", nullable = false)
    private boolean hasNg = false;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Builder
    private Inspection(Long equipmentId, Type type, Shift shift, Long workerId, Instant startedAt, Instant endedAt,
                       Integer durationMin, String content, String actionTaken, Cause4M cause4m, String causeDetail,
                       Long alarmId, boolean hasNg, Long reviewedBy, Instant reviewedAt) {
        this.equipmentId = equipmentId;
        this.type = type;
        this.shift = shift;
        this.workerId = workerId;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.durationMin = durationMin;
        this.content = content;
        this.actionTaken = actionTaken;
        this.cause4m = cause4m;
        this.causeDetail = causeDetail;
        this.alarmId = alarmId;
        this.hasNg = hasNg;
        this.reviewedBy = reviewedBy;
        this.reviewedAt = reviewedAt;
    }
}
