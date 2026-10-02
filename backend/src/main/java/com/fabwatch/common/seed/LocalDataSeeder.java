package com.fabwatch.common.seed;

import com.fabwatch.alarm.entity.Alarm;
import com.fabwatch.auth.entity.Role;
import com.fabwatch.auth.entity.User;
import com.fabwatch.auth.repository.UserRepository;
import com.fabwatch.aireport.entity.AiReport;
import com.fabwatch.inspection.entity.ChecklistItem;
import com.fabwatch.inspection.entity.Inspection;
import com.fabwatch.inspection.entity.InspectionCheckResult;
import com.fabwatch.inspection.entity.PmSchedule;
import com.fabwatch.common.util.PasswordPolicy;
import com.fabwatch.common.util.ShiftUtil;
import com.fabwatch.equipment.entity.Equipment;
import com.fabwatch.equipment.entity.EquipmentStatus;
import com.fabwatch.equipment.entity.EquipmentStatusLog;
import com.fabwatch.equipment.entity.Line;
import com.fabwatch.equipment.entity.Process;
import com.fabwatch.equipment.repository.EquipmentRepository;
import com.fabwatch.equipment.repository.EquipmentStatusLogRepository;
import com.fabwatch.equipment.repository.LineRepository;
import com.fabwatch.sensor.entity.Sensor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 시연/개발용 시드 데이터 (docs/05 §4). local 프로파일 + fabwatch.seed.enabled=true 에서만 동작하고,
 * 이미 데이터가 있으면 아무 것도 하지 않는다 (중복 시드 방지 — docs/13 §4·§7).
 *
 * ★ 모든 값은 가상값이다. 실제 LG디스플레이 공정 수치·알람 코드·레시피를 쓰지 않는다 (docs/08 B-3).
 *
 * 시더는 스키마 전체를 한 번에 채워야 해서 도메인 서비스를 거치지 않고 EntityManager로 직접 저장한다.
 * common/seed는 이 목적에 한해 도메인 엔티티 직접 참조가 허용되는 유일한 예외 지점이다
 * (런타임 코드의 도메인 간 통신은 전부 service 인터페이스 또는 스프링 이벤트를 사용한다).
 * 아직 서비스가 없는 도메인: inspection / aireport.
 */
@Slf4j
@Component
@Profile("local")
@ConditionalOnProperty(name = "fabwatch.seed.enabled", havingValue = "true")
@RequiredArgsConstructor
public class LocalDataSeeder implements CommandLineRunner {

    private static final String SEED_PASSWORD = "fabwatch123";

    private final UserRepository userRepository;
    private final LineRepository lineRepository;
    private final EquipmentRepository equipmentRepository;
    private final EquipmentStatusLogRepository statusLogRepository;
    private final PasswordEncoder passwordEncoder;

    @PersistenceContext
    private EntityManager em;

    private final Random random = new Random(20260807L); // 재현 가능한 시드

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepository.count() > 0) {
            log.info("시드 건너뜀 — 이미 데이터가 존재합니다.");
            return;
        }
        log.info("시드 데이터 생성 시작 (local)");

        Map<String, User> users = seedUsers();
        Map<String, Equipment> equipments = seedLineTree(users);
        Map<String, List<Sensor>> sensors = seedSensors(equipments);
        List<ChecklistItem> checklist = seedChecklist(equipments.get("LAMI-01"));
        seedPmSchedules(equipments);
        List<Alarm> alarms = seedAlarms(equipments, sensors, users);
        seedInspections(equipments, users, checklist, alarms);
        seedStatusLogs(equipments, users);
        seedAiReport(equipments.get("LAMI-01"), alarms.get(0), users.get("engineer"));

        log.info("시드 데이터 생성 완료 — 사용자 {}명, 설비 {}대, 센서 {}개",
                users.size(), equipments.size(), sensors.values().stream().mapToInt(List::size).sum());
    }

    /** 사용자 3명 (docs/05 §4) */
    private Map<String, User> seedUsers() {
        if (!PasswordPolicy.isValid(SEED_PASSWORD)) {
            throw new IllegalStateException("시드 비밀번호가 정책(8자 이상 영문+숫자)에 맞지 않습니다.");
        }
        Map<String, User> users = new LinkedHashMap<>();
        users.put("admin", newUser("admin@fabwatch.dev", "김관리", Role.ADMIN));
        users.put("engineer", newUser("engineer@fabwatch.dev", "박엔지니어", Role.ENGINEER));
        users.put("tech", newUser("tech@fabwatch.dev", "이테크니션", Role.TECHNICIAN));
        userRepository.saveAll(users.values());
        userRepository.flush();
        return users;
    }

    private User newUser(String email, String name, Role role) {
        return User.builder()
                .email(email)
                .password(passwordEncoder.encode(SEED_PASSWORD))
                .name(name)
                .role(role)
                .enabled(true)
                .build();
    }

    /** CELL-1라인 > 공정 3개 > 설비 5대 (docs/03 F-4.1) */
    private Map<String, Equipment> seedLineTree(Map<String, User> users) {
        Line line = new Line("CELL-1라인");
        Process bonding = new Process("합착", 1);
        Process cutting = new Process("절단", 2);
        Process inspecting = new Process("검사", 3);
        line.addProcess(bonding);
        line.addProcess(cutting);
        line.addProcess(inspecting);

        Long managerId = users.get("engineer").getId();
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
    private Map<String, List<Sensor>> seedSensors(Map<String, Equipment> equipments) {
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
    private List<ChecklistItem> seedChecklist(Equipment lami01) {
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

    /** 설비별 PM 스케줄 (docs/03 F-3.3) */
    private void seedPmSchedules(Map<String, Equipment> equipments) {
        Instant now = Instant.now();
        persistPm(equipments.get("LAMI-01"), PmSchedule.CycleType.WEEKLY, 1, now.minus(5, ChronoUnit.DAYS), now.plus(2, ChronoUnit.DAYS));
        persistPm(equipments.get("LAMI-02"), PmSchedule.CycleType.WEEKLY, 4, now.minus(9, ChronoUnit.DAYS), now.minus(2, ChronoUnit.DAYS)); // OVERDUE 시연용
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

    /** 데모용 알람 10건 (docs/05 §4) */
    private List<Alarm> seedAlarms(Map<String, Equipment> equipments, Map<String, List<Sensor>> sensors,
                                   Map<String, User> users) {
        Instant now = Instant.now();
        Long engineerId = users.get("engineer").getId();
        Long techId = users.get("tech").getId();
        List<Alarm> alarms = new ArrayList<>();

        alarms.add(alarm(equipments.get("LAMI-01"), sensors.get("LAMI-01").get(1), Alarm.Type.SENSOR_THRESHOLD,
                Alarm.Severity.CRITICAL, Alarm.Status.RESOLVED, 5.4, 5.0,
                "LAMI-01 진동 임계 초과 (5.4 mm/s)", now.minus(13, ChronoUnit.DAYS), techId, engineerId, "베어링 교체 후 정상화"));
        alarms.add(alarm(equipments.get("LAMI-01"), sensors.get("LAMI-01").get(0), Alarm.Type.SENSOR_THRESHOLD,
                Alarm.Severity.WARNING, Alarm.Status.RESOLVED, 50.8, 50.0,
                "LAMI-01 온도 경고 (50.8 ℃)", now.minus(11, ChronoUnit.DAYS), techId, techId, "냉각수 유량 조정"));
        alarms.add(alarm(equipments.get("LAMI-02"), sensors.get("LAMI-02").get(3), Alarm.Type.SENSOR_THRESHOLD,
                Alarm.Severity.WARNING, Alarm.Status.RESOLVED, 15.4, 15.0,
                "LAMI-02 전류 경고 (15.4 A)", now.minus(9, ChronoUnit.DAYS), engineerId, engineerId, "구동부 부하 점검 완료"));
        alarms.add(alarm(equipments.get("SCRB-01"), sensors.get("SCRB-01").get(1), Alarm.Type.SENSOR_THRESHOLD,
                Alarm.Severity.CRITICAL, Alarm.Status.RESOLVED, 6.8, 6.5,
                "SCRB-01 진동 임계 초과 (6.8 mm/s)", now.minus(7, ChronoUnit.DAYS), techId, engineerId, "스핀들 재조립"));
        alarms.add(alarm(equipments.get("OVEN-01"), sensors.get("OVEN-01").get(0), Alarm.Type.SENSOR_THRESHOLD,
                Alarm.Severity.WARNING, Alarm.Status.RESOLVED, 128.9, 128.0,
                "OVEN-01 온도 경고 (128.9 ℃)", now.minus(6, ChronoUnit.DAYS), techId, techId, "히터 출력 재설정"));
        alarms.add(alarm(equipments.get("LAMI-02"), null, Alarm.Type.PM_OVERDUE,
                Alarm.Severity.MAJOR, Alarm.Status.ACK, null, null,
                "LAMI-02 PM 예정일 경과 (2일)", now.minus(2, ChronoUnit.DAYS), engineerId, null, null));
        alarms.add(alarm(equipments.get("AOI-01"), sensors.get("AOI-01").get(2), Alarm.Type.SENSOR_THRESHOLD,
                Alarm.Severity.WARNING, Alarm.Status.ACK, 7.2, 7.0,
                "AOI-01 전류 경고 (7.2 A)", now.minus(30, ChronoUnit.HOURS), techId, null, null));
        alarms.add(alarm(equipments.get("SCRB-01"), null, Alarm.Type.MANUAL,
                Alarm.Severity.MAJOR, Alarm.Status.ACK, null, null,
                "SCRB-01 이송부 이음 발생 — 수동 보고", now.minus(20, ChronoUnit.HOURS), techId, null, null));
        alarms.add(alarm(equipments.get("LAMI-01"), sensors.get("LAMI-01").get(1), Alarm.Type.SENSOR_THRESHOLD,
                Alarm.Severity.WARNING, Alarm.Status.OPEN, 3.7, 3.5,
                "LAMI-01 진동 경고 (3.7 mm/s)", now.minus(4, ChronoUnit.HOURS), null, null, null));
        alarms.add(alarm(equipments.get("OVEN-01"), sensors.get("OVEN-01").get(2), Alarm.Type.SENSOR_THRESHOLD,
                Alarm.Severity.WARNING, Alarm.Status.OPEN, 25.6, 25.0,
                "OVEN-01 전류 경고 (25.6 A)", now.minus(90, ChronoUnit.MINUTES), null, null, null));

        alarms.forEach(em::persist);
        em.flush();
        return alarms;
    }

    private Alarm alarm(Equipment equipment, Sensor sensor, Alarm.Type type, Alarm.Severity severity,
                        Alarm.Status status, Double triggerValue, Double thresholdValue, String message,
                        Instant occurredAt, Long ackBy, Long resolvedBy, String resolveNote) {
        return Alarm.builder()
                .equipmentId(equipment.getId())
                .sensorId(sensor == null ? null : sensor.getId())
                .alarmType(type)
                .severity(severity)
                .status(status)
                .triggerValue(triggerValue == null ? null : BigDecimal.valueOf(triggerValue))
                .thresholdValue(thresholdValue == null ? null : BigDecimal.valueOf(thresholdValue))
                .message(message)
                .occurredAt(occurredAt)
                .ackBy(ackBy)
                .ackAt(ackBy == null ? null : occurredAt.plus(10, ChronoUnit.MINUTES))
                .resolvedBy(resolvedBy)
                .resolvedAt(resolvedBy == null ? null : occurredAt.plus(2, ChronoUnit.HOURS))
                .resolveNote(resolveNote)
                .build();
    }

    /** 점검 이력 20건 (PM 15 / BM 5) + LAMI-01 PM 체크리스트 결과 (docs/05 §4) */
    private void seedInspections(Map<String, Equipment> equipments, Map<String, User> users,
                                 List<ChecklistItem> checklist, List<Alarm> alarms) {
        List<Equipment> targets = List.copyOf(equipments.values());
        Long techId = users.get("tech").getId();
        Long engineerId = users.get("engineer").getId();
        Instant now = Instant.now();

        // BM 5건 — 4M 분류 필수, 앞쪽 알람과 연계
        String[][] bmCauses = {
                {"MACHINE", "합착 롤러 베어링 마모"},
                {"MACHINE", "스핀들 축 정렬 불량"},
                {"METHOD", "레시피 전환 절차 누락"},
                {"MAN", "지그 장착 미스"},
                {"MATERIAL", "이송 벨트 노후"}
        };
        for (int i = 0; i < 5; i++) {
            Equipment equipment = targets.get(i % targets.size());
            Instant started = now.minus(13L - i * 2, ChronoUnit.DAYS);
            Instant ended = started.plus(90 + random.nextInt(120), ChronoUnit.MINUTES);
            Inspection inspection = Inspection.builder()
                    .equipmentId(equipment.getId())
                    .type(Inspection.Type.BM)
                    .shift(Inspection.Shift.valueOf(ShiftUtil.shiftCode(started)))
                    .workerId(techId)
                    .startedAt(started)
                    .endedAt(ended)
                    .durationMin((int) ChronoUnit.MINUTES.between(started, ended))
                    .content(equipment.getCode() + " 이상 정지 — 현상 확인 및 부품 교체")
                    .actionTaken("부품 교체 후 시운전 정상 확인")
                    .cause4m(Inspection.Cause4M.valueOf(bmCauses[i][0]))
                    .causeDetail(bmCauses[i][1])
                    .alarmId(i < alarms.size() ? alarms.get(i).getId() : null)
                    .hasNg(false)
                    .reviewedBy(engineerId)
                    .reviewedAt(ended.plus(3, ChronoUnit.HOURS))
                    .build();
            em.persist(inspection);
        }

        // PM 15건 — LAMI-01 건에는 체크리스트 결과를 붙인다
        for (int i = 0; i < 15; i++) {
            Equipment equipment = targets.get(i % targets.size());
            Instant started = now.minus(28L - i, ChronoUnit.DAYS).plus(i % 3 == 0 ? 21 : 9, ChronoUnit.HOURS);
            Instant ended = started.plus(40 + random.nextInt(50), ChronoUnit.MINUTES);
            boolean hasNg = i % 5 == 0; // 15건 중 3건 NG
            Inspection inspection = Inspection.builder()
                    .equipmentId(equipment.getId())
                    .type(Inspection.Type.PM)
                    .shift(Inspection.Shift.valueOf(ShiftUtil.shiftCode(started)))
                    .workerId(i % 2 == 0 ? techId : engineerId)
                    .startedAt(started)
                    .endedAt(ended)
                    .durationMin((int) ChronoUnit.MINUTES.between(started, ended))
                    .content(equipment.getCode() + " 정기 PM 수행")
                    .actionTaken(hasNg ? "NG 항목 조치 및 재점검" : "이상 없음")
                    .hasNg(hasNg)
                    .reviewedBy(hasNg ? engineerId : null)
                    .reviewedAt(hasNg ? ended.plus(5, ChronoUnit.HOURS) : null)
                    .build();
            em.persist(inspection);
            em.flush();

            if (equipment.getCode().equals("LAMI-01")) {
                for (int j = 0; j < checklist.size(); j++) {
                    InspectionCheckResult.Result result =
                            (hasNg && j == 1) ? InspectionCheckResult.Result.NG : InspectionCheckResult.Result.OK;
                    em.persist(InspectionCheckResult.builder()
                            .inspectionId(inspection.getId())
                            .checklistItemId(checklist.get(j).getId())
                            .result(result)
                            .note(result == InspectionCheckResult.Result.NG ? "기준 미달 — 재조정 필요" : null)
                            .build());
                }
            }
        }
        em.flush();
    }

    /**
     * 상태 로그 (KPI 계산 원천). 상태 머신 규칙을 그대로 따르는 시퀀스만 생성한다 (docs/03 F-2).
     * Equipment.changeStatus()를 거치므로 허용되지 않는 전이는 시드 단계에서 바로 실패한다.
     */
    private void seedStatusLogs(Map<String, Equipment> equipments, Map<String, User> users) {
        Long engineerId = users.get("engineer").getId();
        Instant now = Instant.now();

        // 설비별 시나리오: {목표 상태, 몇 시간 전, 사유}. IDLE에서 시작해 30일치 이력을 재생한다.
        Map<String, List<Object[]>> scripts = new LinkedHashMap<>();
        scripts.put("LAMI-01", List.of(
                new Object[]{EquipmentStatus.RUN, 720, "생산 시작"},
                new Object[]{EquipmentStatus.DOWN, 312, "CRITICAL 알람 자동 DOWN (진동 임계 초과)"},
                new Object[]{EquipmentStatus.IDLE, 290, "BM 수리 완료 — 시운전 대기"},
                new Object[]{EquipmentStatus.RUN, 288, "시운전 정상 — 생산 재개"}));
        scripts.put("LAMI-02", List.of(
                new Object[]{EquipmentStatus.RUN, 720, "생산 시작"},
                new Object[]{EquipmentStatus.PM, 216, "정기 PM 착수"},
                new Object[]{EquipmentStatus.IDLE, 210, "PM 완료 — 시운전 대기"},
                new Object[]{EquipmentStatus.RUN, 208, "생산 재개"}));
        scripts.put("OVEN-01", List.of(
                new Object[]{EquipmentStatus.RUN, 720, "생산 시작"},
                new Object[]{EquipmentStatus.PM, 480, "정기 PM 착수"},
                new Object[]{EquipmentStatus.IDLE, 470, "PM 완료 — 시운전 대기"},
                new Object[]{EquipmentStatus.RUN, 468, "생산 재개"}));
        scripts.put("SCRB-01", List.of(
                new Object[]{EquipmentStatus.RUN, 720, "생산 시작"},
                new Object[]{EquipmentStatus.DOWN, 168, "CRITICAL 알람 자동 DOWN (진동 임계 초과)"},
                new Object[]{EquipmentStatus.IDLE, 150, "BM 수리 완료 — 시운전 대기"}));
        scripts.put("AOI-01", List.of(
                new Object[]{EquipmentStatus.RUN, 720, "생산 시작"},
                new Object[]{EquipmentStatus.IDLE, 360, "자재 대기"},
                new Object[]{EquipmentStatus.RUN, 356, "생산 재개"}));

        equipments.forEach((code, equipment) -> {
            // 시나리오 시작점(IDLE)으로 맞춘다. RUN → IDLE은 허용된 전이라 상태 머신을 우회하지 않는다.
            if (equipment.getStatus() != EquipmentStatus.IDLE) {
                equipment.changeStatus(EquipmentStatus.IDLE);
            }
            statusLogRepository.save(new EquipmentStatusLog(equipment, null, EquipmentStatus.IDLE, "설비 등록",
                    engineerId, now.minus(721, ChronoUnit.HOURS)));

            for (Object[] step : scripts.getOrDefault(code, List.of())) {
                EquipmentStatus to = (EquipmentStatus) step[0];
                long hoursAgo = ((Number) step[1]).longValue();
                String reason = (String) step[2];
                EquipmentStatus from = equipment.changeStatus(to);
                statusLogRepository.save(new EquipmentStatusLog(equipment, from, to, reason,
                        engineerId, now.minus(hoursAgo, ChronoUnit.HOURS)));
            }
        });
        equipmentRepository.flush();
        statusLogRepository.flush();
    }

    /** 데모용 AI 리포트 1건 (확정본) */
    private void seedAiReport(Equipment equipment, Alarm alarm, User engineer) {
        em.persist(AiReport.builder()
                .equipmentId(equipment.getId())
                .alarmId(alarm.getId())
                .title("고장 리포트 — LAMI-01 진동 임계 초과")
                .status(AiReport.Status.CONFIRMED)
                .draftContent("""
                        # 고장 리포트 — LAMI-01
                        ## 1. 현상 요약
                        합착기 1호기 진동값이 임계(5.0 mm/s)를 초과했습니다. (가상 데모 데이터)
                        ## 2. 센서 데이터 분석
                        발생 30분 전부터 완만한 상승 추세가 관찰되어 드리프트형 열화로 추정됩니다.
                        ## 3. 추정 원인 (4M)
                        1) MACHINE — 구동부 베어링 마모(추정) 2) METHOD — 윤활 주기 지연(추정)
                        ## 4. 권장 조치
                        즉시: 설비 정지 후 베어링 상태 확인 / 후속: 윤활 주기 재설정
                        ## 5. 재발 방지 제안
                        PM 체크리스트에 베어링 진동 측정 항목 추가를 제안합니다.
                        """)
                .finalContent("""
                        # 고장 리포트 — LAMI-01
                        ## 1. 현상 요약
                        합착기 1호기 진동값이 임계(5.0 mm/s)를 초과해 자동 DOWN 처리되었습니다.
                        ## 2. 센서 데이터 분석
                        발생 30분 전부터 완만한 상승 추세 — 드리프트형 열화.
                        ## 3. 추정 원인 (4M)
                        MACHINE — 구동부 베어링 마모(정비 결과 확인됨)
                        ## 4. 권장 조치
                        베어링 교체 완료. 시운전 후 정상 복귀.
                        ## 5. 재발 방지 제안
                        PM 체크리스트에 베어링 진동 측정 항목 추가.
                        """)
                .model("claude-sonnet-demo")
                .promptTokens(1200)
                .completionTokens(650)
                .createdBy(engineer.getId())
                .confirmedBy(engineer.getId())
                .confirmedAt(java.time.Instant.now())
                .build());
        em.flush();
    }
}
