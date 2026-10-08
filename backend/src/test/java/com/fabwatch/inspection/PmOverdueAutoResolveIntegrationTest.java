package com.fabwatch.inspection;

import com.fabwatch.alarm.entity.Alarm;
import com.fabwatch.alarm.repository.AlarmRepository;
import com.fabwatch.inspection.dto.InspectionCreateRequest;
import com.fabwatch.inspection.entity.Inspection;
import com.fabwatch.inspection.entity.PmSchedule;
import com.fabwatch.inspection.repository.PmScheduleRepository;
import com.fabwatch.inspection.service.InspectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PM 수행 → 미해결 PM_OVERDUE 알람 자동 해소 통합 테스트 (실제 서비스·JPA 연동, docs/03 F-3.3).
 * 클래스 단위 @Transactional — 각 테스트의 쓰기는 끝나면 롤백된다. 시드 설비(LAMI-01/LAMI-02)를 사용한다.
 */
@SpringBootTest(properties = "fabwatch.seed.enabled=true")
@ActiveProfiles({"local", "test"})
@Transactional
class PmOverdueAutoResolveIntegrationTest {

    @Autowired
    private InspectionService inspectionService;
    @Autowired
    private PmScheduleRepository pmScheduleRepository;
    @Autowired
    private AlarmRepository alarmRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private long lami01;
    private long lami02;
    private long workerId;

    @BeforeEach
    void setUp() {
        lami01 = equipmentId("LAMI-01");
        lami02 = equipmentId("LAMI-02");
        workerId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'tech@fabwatch.dev'", Long.class);
        // 시드 상태와 무관하게 대상 설비 두 대의 PM_OVERDUE 알람은 이 테스트가 만든 것만 남긴다
        // (영속성 컨텍스트에 올리지 않도록 JDBC로 직접 갱신 — 테스트 트랜잭션 안이라 롤백된다)
        jdbc.update("UPDATE alarms SET status = 'RESOLVED', resolve_note = '사전 정리' "
                + "WHERE alarm_type = 'PM_OVERDUE' AND status <> 'RESOLVED'");
    }

    @Test
    @DisplayName("PM 등록 → 해당 설비 OPEN/ACK PM_OVERDUE 해소, 다른 설비 알람은 그대로")
    void pmResolvesOnlyThatEquipmentsPmOverdue() {
        overdueSchedule(lami01);
        Alarm open = pmOverdueAlarm(lami01, Alarm.Status.OPEN);
        Alarm other = pmOverdueAlarm(lami02, Alarm.Status.OPEN);
        Alarm manual = alarmRepository.saveAndFlush(Alarm.builder().equipmentId(lami01).alarmType(Alarm.Type.MANUAL)
                .severity(Alarm.Severity.MAJOR).status(Alarm.Status.OPEN).message("수동").occurredAt(Instant.now()).build());

        var detail = inspectionService.create(pm(lami01), workerId);

        Alarm resolved = alarmRepository.findById(open.getId()).orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo(Alarm.Status.RESOLVED);
        assertThat(resolved.getAckBy()).isEqualTo(workerId);
        assertThat(resolved.getResolvedBy()).isEqualTo(workerId);
        assertThat(resolved.getResolveNote()).isEqualTo("PM 점검 이력 #%d로 수행 완료".formatted(detail.id()));
        assertThat(alarmRepository.findById(other.getId()).orElseThrow().getStatus()).isEqualTo(Alarm.Status.OPEN);
        assertThat(alarmRepository.findById(manual.getId()).orElseThrow().getStatus()).isEqualTo(Alarm.Status.OPEN);
    }

    @Test
    @DisplayName("ACK 상태 알람도 해소, 알람이 없거나 이미 RESOLVED여도 에러 없이 PM 등록 성공")
    void ackResolvedAndNoAlarm() {
        overdueSchedule(lami01);
        Alarm ack = pmOverdueAlarm(lami01, Alarm.Status.ACK);
        inspectionService.create(pm(lami01), workerId);
        assertThat(alarmRepository.findById(ack.getId()).orElseThrow().getStatus()).isEqualTo(Alarm.Status.RESOLVED);

        // 이미 RESOLVED + 알람 없음(LAMI-02는 사전 정리로 미해결 없음) → no-op
        overdueSchedule(lami02);
        assertThat(inspectionService.create(pm(lami02), workerId).id()).isNotNull();
        assertThat(alarmRepository.findByEquipmentIdAndAlarmTypeAndStatusNot(
                lami02, Alarm.Type.PM_OVERDUE, Alarm.Status.RESOLVED)).isEmpty();
    }

    @Test
    @DisplayName("소급 PM 등록(스케줄 안 되돌림)·BM 등록은 PM_OVERDUE 알람을 해소하지 않는다")
    void backfilledPmAndBmDoNotResolve() {
        PmSchedule schedule = overdueSchedule(lami01);
        Instant lastDone = Instant.now().minus(10, ChronoUnit.MINUTES);
        schedule.markDone(lastDone, lastDone.plus(1, ChronoUnit.DAYS)); // 이미 최근 수행 — 이후 PM(종료 1h 전)은 소급
        pmScheduleRepository.saveAndFlush(schedule);
        Alarm open = pmOverdueAlarm(lami01, Alarm.Status.OPEN);

        inspectionService.create(pm(lami01), workerId);
        inspectionService.create(new InspectionCreateRequest(lami01, Inspection.Type.BM, null,
                Instant.now().minus(3, ChronoUnit.HOURS), Instant.now().minus(1, ChronoUnit.HOURS),
                "고장", "조치", Inspection.Cause4M.MACHINE, null, null, null), workerId);

        assertThat(alarmRepository.findById(open.getId()).orElseThrow().getStatus()).isEqualTo(Alarm.Status.OPEN);
    }

    @Test
    @DisplayName("PM 이력 종료 시각 수정(실제 JPQL 자동 flush) — 마지막 수행을 늦추면 스케줄 재계산, 앞당기면 직전 PM 시각까지만 되돌림")
    void editPmEndedAtRecalculatesSchedule() {
        overdueSchedule(lami01);
        // 직전 PM(10일 전) → 최신 PM(1시간 전, 마지막 수행) 순으로 등록
        Instant previousEnded = Instant.now().minus(10, ChronoUnit.DAYS);
        var previous = inspectionService.create(new InspectionCreateRequest(lami01, Inspection.Type.PM, null,
                previousEnded.minus(2, ChronoUnit.HOURS), previousEnded, "직전 PM", null, null, null, null, null), workerId);
        assertThat(previous.id()).isNotNull();
        var latest = inspectionService.create(pm(lami01), workerId);
        Instant latestEnded = pmScheduleRepository.findByEquipmentId(lami01).orElseThrow().getLastDoneAt();
        assertThat(latestEnded).isEqualTo(latest.endedAt());

        // 1) 최신 PM의 종료를 30분 늦춤 → 스케줄 last_done도 그 시각으로
        Instant later = latestEnded.plus(30, ChronoUnit.MINUTES).isAfter(Instant.now()) ? Instant.now().minusSeconds(5)
                : latestEnded.plus(30, ChronoUnit.MINUTES);
        inspectionService.update(latest.id(), new com.fabwatch.inspection.dto.InspectionUpdateRequest(
                null, later.minus(2, ChronoUnit.HOURS), later, "수정", null, null, null, null), workerId, "TECHNICIAN");
        PmSchedule afterLater = pmScheduleRepository.findByEquipmentId(lami01).orElseThrow();
        assertThat(afterLater.getLastDoneAt()).isEqualTo(later);
        assertThat(afterLater.getNextDueAt()).isAfter(later);

        // 2) 최신 PM의 종료를 30일 전으로 크게 앞당김 → 직전 PM(10일 전) 시각까지만 되돌아간다
        Instant farBack = Instant.now().minus(30, ChronoUnit.DAYS);
        inspectionService.update(latest.id(), new com.fabwatch.inspection.dto.InspectionUpdateRequest(
                null, farBack.minus(2, ChronoUnit.HOURS), farBack, "수정", null, null, null, null), workerId, "TECHNICIAN");
        PmSchedule afterEarlier = pmScheduleRepository.findByEquipmentId(lami01).orElseThrow();
        assertThat(afterEarlier.getLastDoneAt()).isEqualTo(previousEnded);
    }

    // "해소 후 다시 3일 초과되면 새 알람 생성" 케이스는 PmOverdueIntegrationTest (3d)로 옮겼다 —
    // PmOverdueService가 스케줄마다 REQUIRES_NEW 트랜잭션을 쓰므로 클래스 @Transactional(미커밋 데이터)에서는 검증할 수 없다.

    // ------------------------------------------------------------ 헬퍼

    private List<Alarm> unresolved(long equipmentId) {
        return alarmRepository.findByEquipmentIdAndAlarmTypeAndStatusNot(
                equipmentId, Alarm.Type.PM_OVERDUE, Alarm.Status.RESOLVED);
    }

    private long equipmentId(String code) {
        return jdbc.queryForObject("SELECT id FROM equipments WHERE code = ?", Long.class, code);
    }

    /** 49일 전에 만료된 DAILY 스케줄 + 지연 알람 이미 발송된 상태로 맞춘다 */
    private PmSchedule overdueSchedule(long equipmentId) {
        Instant due = Instant.now().minus(49, ChronoUnit.DAYS);
        PmSchedule schedule = pmScheduleRepository.findByEquipmentId(equipmentId).orElseGet(() ->
                PmSchedule.builder().equipmentId(equipmentId).cycleType(PmSchedule.CycleType.DAILY)
                        .lastDoneAt(due.minus(1, ChronoUnit.DAYS)).nextDueAt(due).overdueAlarmSent(true).build());
        schedule.markDone(due.minus(1, ChronoUnit.DAYS), due);
        schedule.markOverdueAlarmSent();
        return pmScheduleRepository.saveAndFlush(schedule);
    }

    private Alarm pmOverdueAlarm(long equipmentId, Alarm.Status status) {
        return alarmRepository.saveAndFlush(Alarm.builder().equipmentId(equipmentId).alarmType(Alarm.Type.PM_OVERDUE)
                .severity(Alarm.Severity.MAJOR).status(status).message("PM 예정일 경과 (49일)")
                .occurredAt(Instant.now().minus(1, ChronoUnit.DAYS)).build());
    }

    private InspectionCreateRequest pm(long equipmentId) {
        return new InspectionCreateRequest(equipmentId, Inspection.Type.PM, null,
                Instant.now().minus(3, ChronoUnit.HOURS), Instant.now().minus(1, ChronoUnit.HOURS),
                "정기 점검", null, null, null, null, null);
    }
}
