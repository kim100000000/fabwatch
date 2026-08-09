package com.fabwatch.alarm.repository;

import com.fabwatch.alarm.entity.Alarm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AlarmRepository extends JpaRepository<Alarm, Long>, JpaSpecificationExecutor<Alarm> {

    /**
     * 중복 억제 조회 (docs/05 alarms):
     * {@code WHERE equipment_id=? AND sensor_id=? AND severity=? AND status != 'RESOLVED'}
     *
     * 알람 폭주는 현장에서 최악이다(실제 알람 무시 문화의 원인) — 동일 설비+센서+심각도의
     * 미해결(OPEN/ACK) 알람이 이미 있으면 신규 생성하지 않는다 (docs/03 F-4.3).
     */
    boolean existsByEquipmentIdAndSensorIdAndSeverityAndStatusNot(
            Long equipmentId, Long sensorId, Alarm.Severity severity, Alarm.Status status);

    /** sensor_id가 NULL인 시스템 알람(PM 지연·수동 보고)용 — JPQL의 `= NULL`은 매칭되지 않으므로 분리한다. */
    boolean existsByEquipmentIdAndSensorIdIsNullAndSeverityAndStatusNot(
            Long equipmentId, Alarm.Severity severity, Alarm.Status status);

    long countByEquipmentIdAndStatusNot(Long equipmentId, Alarm.Status status);
}
