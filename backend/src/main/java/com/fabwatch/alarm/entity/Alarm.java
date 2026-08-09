package com.fabwatch.alarm.entity;

import com.fabwatch.common.entity.SoftDeletableEntity;

import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 알람 (docs/05 alarms, docs/03 F-5.3).
 */
@Entity
@Table(name = "alarms", indexes = {
        @Index(name = "idx_alarm_equipment_status", columnList = "equipment_id, status"),
        @Index(name = "idx_alarm_status_occurred", columnList = "status, occurred_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE alarms SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
public class Alarm extends SoftDeletableEntity {

    public enum Type {
        SENSOR_THRESHOLD, PM_OVERDUE, MANUAL
    }

    /** MAJOR는 PM 지연 등 시스템 규칙 알람용 (docs/03 F-4.3) */
    public enum Severity {
        WARNING, MAJOR, CRITICAL
    }

    /** OPEN → ACK → RESOLVED. ACK 없이 RESOLVED 불가 (docs/03 F-5.3) */
    public enum Status {
        OPEN, ACK, RESOLVED
    }

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    /** NULL = 시스템 알람(PM 지연 등) */
    @Column(name = "sensor_id")
    private Long sensorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "alarm_type", length = 30, nullable = false)
    private Type alarmType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 10, nullable = false)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 10, nullable = false)
    private Status status = Status.OPEN;

    @Column(name = "trigger_value", precision = 10, scale = 2)
    private BigDecimal triggerValue;

    @Column(name = "threshold_value", precision = 10, scale = 2)
    private BigDecimal thresholdValue;

    @Column(name = "message", length = 300, nullable = false)
    private String message;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "ack_by")
    private Long ackBy;

    @Column(name = "ack_at")
    private Instant ackAt;

    @Column(name = "resolved_by")
    private Long resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolve_note", length = 300)
    private String resolveNote;

    /**
     * 알람 상태 전이 (docs/03 F-5.3): OPEN → ACK → RESOLVED.
     * ACK 없이 RESOLVED 불가, RESOLVED 시 해제 사유 필수 — 전이 판정은 이 두 메서드에만 존재한다.
     */
    public void acknowledge(Long userId, Instant at) {
        if (status != Status.OPEN) {
            throw new BusinessException(ErrorCode.INVALID_ALARM_STATUS,
                    "OPEN 상태의 알람만 확인 처리할 수 있습니다. 현재 상태: " + status);
        }
        this.status = Status.ACK;
        this.ackBy = userId;
        this.ackAt = at;
    }

    public void resolve(Long userId, String note, Instant at) {
        if (status != Status.ACK) {
            throw new BusinessException(ErrorCode.ACK_REQUIRED_FIRST,
                    "확인(ACK) 처리 후에만 해제할 수 있습니다. 현재 상태: " + status);
        }
        if (note == null || note.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "해제 사유(resolveNote)는 필수입니다.");
        }
        this.status = Status.RESOLVED;
        this.resolvedBy = userId;
        this.resolveNote = note;
        this.resolvedAt = at;
    }

    @Builder
    private Alarm(Long equipmentId, Long sensorId, Type alarmType, Severity severity, Status status,
                  BigDecimal triggerValue, BigDecimal thresholdValue, String message, Instant occurredAt,
                  Long ackBy, Instant ackAt, Long resolvedBy, Instant resolvedAt, String resolveNote) {
        this.equipmentId = equipmentId;
        this.sensorId = sensorId;
        this.alarmType = alarmType;
        this.severity = severity;
        this.status = status == null ? Status.OPEN : status;
        this.triggerValue = triggerValue;
        this.thresholdValue = thresholdValue;
        this.message = message;
        this.occurredAt = occurredAt;
        this.ackBy = ackBy;
        this.ackAt = ackAt;
        this.resolvedBy = resolvedBy;
        this.resolvedAt = resolvedAt;
        this.resolveNote = resolveNote;
    }
}
