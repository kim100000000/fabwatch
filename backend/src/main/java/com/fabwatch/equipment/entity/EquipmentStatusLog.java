package com.fabwatch.equipment.entity;

import com.fabwatch.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 설비 상태 변경 이력 (docs/05 equipment_status_logs) — KPI(MTBF/MTTR/가동률) 계산 원천.
 * 추가 전용 로그이므로 soft delete 대상이 아니다.
 */
@Entity
@Table(name = "equipment_status_logs",
        indexes = @Index(name = "idx_status_log_equipment_changed", columnList = "equipment_id, changed_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EquipmentStatusLog extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "equipment_id", nullable = false)
    private Equipment equipment;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 10)
    private EquipmentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 10, nullable = false)
    private EquipmentStatus toStatus;

    @Column(name = "reason", length = 200)
    private String reason;

    /** NULL = 시스템 자동 전환 (예: CRITICAL 알람) */
    @Column(name = "changed_by")
    private Long changedBy;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    public EquipmentStatusLog(Equipment equipment, EquipmentStatus fromStatus, EquipmentStatus toStatus,
                              String reason, Long changedBy, Instant changedAt) {
        this.equipment = equipment;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
        this.changedBy = changedBy;
        this.changedAt = changedAt;
    }
}
