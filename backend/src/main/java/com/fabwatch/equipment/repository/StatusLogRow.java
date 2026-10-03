package com.fabwatch.equipment.repository;

import com.fabwatch.equipment.entity.EquipmentStatus;

import java.time.Instant;

/** KPI 계산용 상태 로그 경량 투영 — 엔티티(설비 연관) 로딩 없이 필요한 3필드만 읽는다. */
public record StatusLogRow(Long equipmentId, EquipmentStatus toStatus, Instant changedAt) {
}
