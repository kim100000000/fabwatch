package com.fabwatch.common.seed;

import com.fabwatch.equipment.entity.Equipment;
import com.fabwatch.equipment.entity.EquipmentStatus;
import com.fabwatch.equipment.entity.Line;
import com.fabwatch.equipment.entity.Process;
import com.fabwatch.equipment.repository.LineRepository;
import com.fabwatch.inspection.entity.ChecklistItem;
import com.fabwatch.inspection.entity.PmSchedule;
import com.fabwatch.sensor.entity.Sensor;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 데모 마스터 데이터(라인·공정·설비·센서+임계치·PM 체크리스트·PM 스케줄) 생성기 — 사용자/알람/점검 이력은 포함하지 않는다.
 * local 시더(LocalDataSeeder)와 운영 옵트인 데모 로더(DemoDataService)가 같은 데이터를 쓰도록 공유한다.
 *
 * ★ 모든 값은 가상값이다. 실제 LG디스플레이 공정 수치·알람 코드·레시피를 쓰지 않는다 (docs/08 B-3).
 * common/seed는 도메인 엔티티를 직접 다룰 수 있는 유일한 예외 지점이다(런타임 도메인 간 통신 규칙의 예외, 시더 전용).
 */
public class DemoMasterData {

    private final EntityManager em;
    private final LineRepository lineRepository;

    public DemoMasterData(EntityManager em, LineRepository lineRepository) {
        this.em = em;
        this.lineRepository = lineRepository;
    }

    /** CELL-1라인 > 공정 3개 > 설비 5대 (docs/03 F-4.1) */
    public Map<String, Equipment> seedLineTree(Long managerId) {
        Line line = new Line("CELL-1라인");
        Process bonding = new Process("합착", 1);
        Process cutting = new Process("절단", 2);
        Process inspecting = new Process("검사", 3);
        line.addProcess(bonding);
        line.addProcess(cutting);
        line.addProcess(inspecting);

        Map<String, Equipment> equipments = new LinkedHashMap<>();
        equipments.put("LAMI-01", newEquipment(bonding, "LAMI-01", "합착기 1호기", "FW-LAM-2000", "가상정밀",
                LocalDate.of(2022, 3, 14), EquipmentStatus.RUN, managerId));
        equipments.put("LAMI-02", newEquipment(bonding, "LAMI-02", "합착기 2호기", "FW-LAM-2000", "가상정밀",
                LocalDate.of(2022, 6, 2), EquipmentStatus.RUN, managerId));
        equipments.put("OVEN-01", newEquipment(bonding, "OVEN-01", "경화로 1호기", "FW-OVN-500", "가상열기",
                LocalDate.of(2021, 11, 20), EquipmentStatus.RUN, managerId));
        equipments.put("SCRB-01", newEquipment(cutting, "SCRB-01", "스크라이버 1호기", "FW-SCR-880", "가상머신",
                LocalDate.of(2023, 1, 9), EquipmentStatus.IDLE, managerId));
        equipments.put("AOI-01", newEquipment(inspecting, "AOI-01", "AOI 검사기 1호기", "FW-AOI-120", "가상비전",
                LocalDate.of(2023, 5, 30), EquipmentStatus.RUN, managerId));

        lineRepository.saveAndFlush(line);
        return equipments;
    }

    private Equipment newEquipment(Process process, String code, String name, String model, String maker,
                                   LocalDate installedAt, EquipmentStatus status, Long managerId) {
        Equipment equipment = Equipment.builder()
                .code(code).name(name).modelName(model).maker(maker)
                .installedAt(installedAt).status(status).managerId(managerId)
                .note(null)
                .build();
        process.addEquipment(equipment);
        return equipment;
    }

    /** 센서 18개 + 임계치 (docs/03 F-4.1 — 전부 가상값) */
    public Map<String, List<Sensor>> seedSensors(Map<String, Equipment> equipments) {
        Map<String, List<Sensor>> result = new LinkedHashMap<>();
        result.put("LAMI-01", List.of(
                sensor(equipments.get("LAMI-01"), Sensor.Type.TEMP, "℃", 45.0, 0.8, 38, 50, 35, 55),
                sensor(equipments.get("LAMI-01"), Sensor.Type.VIBRATION, "mm/s", 2.0, 0.3, null, 3.5, null, 5.0),
                sensor(equipments.get("LAMI-01"), Sensor.Type.PRESSURE, "kPa", -95.0, 1.5, -105, -85, -110, -80),
                sensor(equipments.get("LAMI-01"), Sensor.Type.CURRENT, "A", 12.0, 0.5, 8, 15, 6, 18)));
        result.put("LAMI-02", List.of(
                sensor(equipments.get("LAMI-02"), Sensor.Type.TEMP, "℃", 46.0, 0.9, 38, 51, 35, 56),
                sensor(equipments.get("LAMI-02"), Sensor.Type.VIBRATION, "mm/s", 2.2, 0.3, null, 3.5, null, 5.0),
                sensor(equipments.get("LAMI-02"), Sensor.Type.PRESSURE, "kPa", -94.0, 1.6, -105, -85, -110, -80),
                sensor(equipments.get("LAMI-02"), Sensor.Type.CURRENT, "A", 12.5, 0.5, 8, 15, 6, 18)));
        result.put("SCRB-01", List.of(
                sensor(equipments.get("SCRB-01"), Sensor.Type.TEMP, "℃", 38.0, 0.6, 30, 44, 28, 48),
                sensor(equipments.get("SCRB-01"), Sensor.Type.VIBRATION, "mm/s", 3.5, 0.4, null, 5.0, null, 6.5),
                sensor(equipments.get("SCRB-01"), Sensor.Type.PRESSURE, "kPa", 500.0, 8.0, 460, 540, 440, 560),
                sensor(equipments.get("SCRB-01"), Sensor.Type.CURRENT, "A", 8.0, 0.4, 5, 11, 4, 13)));
        result.put("AOI-01", List.of(
                sensor(equipments.get("AOI-01"), Sensor.Type.TEMP, "℃", 30.0, 0.4, 24, 34, 22, 37),
                sensor(equipments.get("AOI-01"), Sensor.Type.VIBRATION, "mm/s", 0.8, 0.15, null, 1.5, null, 2.2),
                sensor(equipments.get("AOI-01"), Sensor.Type.CURRENT, "A", 5.0, 0.3, 3, 7, 2, 8)));
        result.put("OVEN-01", List.of(
                sensor(equipments.get("OVEN-01"), Sensor.Type.TEMP, "℃", 120.0, 1.5, 112, 128, 108, 133),
                sensor(equipments.get("OVEN-01"), Sensor.Type.VIBRATION, "mm/s", 1.0, 0.2, null, 2.0, null, 3.0),
                sensor(equipments.get("OVEN-01"), Sensor.Type.CURRENT, "A", 20.0, 1.0, 15, 25, 13, 28)));
        result.values().forEach(list -> list.forEach(em::persist));
        em.flush();
        return result;
    }

    private Sensor sensor(Equipment equipment, Sensor.Type type, String unit, double base, double sigma,
                          Integer warnLow, Number warnHigh, Integer critLow, Number critHigh) {
        return Sensor.builder()
                .equipmentId(equipment.getId())
                .type(type)
                .unit(unit)
                .baseValue(BigDecimal.valueOf(base))
                .noiseSigma(BigDecimal.valueOf(sigma))
                .warnLow(warnLow == null ? null : BigDecimal.valueOf(warnLow))
                .warnHigh(warnHigh == null ? null : BigDecimal.valueOf(warnHigh.doubleValue()))
                .critLow(critLow == null ? null : BigDecimal.valueOf(critLow))
                .critHigh(critHigh == null ? null : BigDecimal.valueOf(critHigh.doubleValue()))
                .build();
    }

    /** LAMI-01 PM 체크리스트 5항목 (docs/05 §4) */
    public List<ChecklistItem> seedChecklist(Equipment lami01) {
        List<ChecklistItem> items = List.of(
                checklistItem(lami01, "합착 롤러 마모 확인", "마모선 이내", 1),
                checklistItem(lami01, "진공 압력 점검", "기준 진공도 유지", 2),
                checklistItem(lami01, "클린룸 파티클 체크", "관리 기준 이내", 3),
                checklistItem(lami01, "구동부 윤활 상태", "오일 게이지 중간선 이상", 4),
                checklistItem(lami01, "안전 인터락 동작 확인", "정상 동작", 5));
        items.forEach(em::persist);
        em.flush();
        return items;
    }

    private ChecklistItem checklistItem(Equipment equipment, String name, String criteria, int seq) {
        return ChecklistItem.builder()
                .equipmentId(equipment.getId())
                .itemName(name)
                .criteria(criteria)
                .seq(seq)
                .active(true)
                .build();
    }

    /**
     * 설비별 PM 스케줄 (docs/03 F-3.3).
     *
     * @param overdueDemo true면 LAMI-02를 "2일 초과(OVERDUE)"로 만든다(local 시연용).
     *                    false(공개 데모 배포)면 전 설비를 기동 시점 기준 미래로 잡아 처음부터 초과 알람이 쏟아지지 않게 한다.
     */
    public void seedPmSchedules(Map<String, Equipment> equipments, boolean overdueDemo) {
        Instant now = Instant.now();
        persistPm(equipments.get("LAMI-01"), PmSchedule.CycleType.WEEKLY, 1, now.minus(5, ChronoUnit.DAYS), now.plus(2, ChronoUnit.DAYS));
        if (overdueDemo) {
            persistPm(equipments.get("LAMI-02"), PmSchedule.CycleType.WEEKLY, 4, now.minus(9, ChronoUnit.DAYS), now.minus(2, ChronoUnit.DAYS)); // OVERDUE 시연용
        } else {
            persistPm(equipments.get("LAMI-02"), PmSchedule.CycleType.WEEKLY, 4, now.minus(4, ChronoUnit.DAYS), now.plus(3, ChronoUnit.DAYS));
        }
        persistPm(equipments.get("OVEN-01"), PmSchedule.CycleType.MONTHLY, 1, now.minus(20, ChronoUnit.DAYS), now.plus(10, ChronoUnit.DAYS));
        persistPm(equipments.get("SCRB-01"), PmSchedule.CycleType.MONTHLY, 15, now.minus(12, ChronoUnit.DAYS), now.plus(18, ChronoUnit.DAYS));
        persistPm(equipments.get("AOI-01"), PmSchedule.CycleType.DAILY, null, now.minus(1, ChronoUnit.DAYS), now.plus(4, ChronoUnit.HOURS));
        em.flush();
    }

    private void persistPm(Equipment equipment, PmSchedule.CycleType type, Integer value, Instant lastDone, Instant nextDue) {
        em.persist(PmSchedule.builder()
                .equipmentId(equipment.getId())
                .cycleType(type)
                .cycleValue(value)
                .lastDoneAt(lastDone)
                .nextDueAt(nextDue)
                .overdueAlarmSent(false)
                .build());
    }
}
