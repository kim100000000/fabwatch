package com.fabwatch.inspection.service;

import com.fabwatch.alarm.service.AlarmCommandService;
import com.fabwatch.auth.service.UserQueryService;
import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import com.fabwatch.equipment.service.EquipmentQueryService;
import com.fabwatch.inspection.dto.CheckResultRequest;
import com.fabwatch.inspection.dto.InspectionCreateRequest;
import com.fabwatch.inspection.dto.InspectionDetailResponse;
import com.fabwatch.inspection.dto.InspectionUpdateRequest;
import com.fabwatch.inspection.entity.ChecklistItem;
import com.fabwatch.inspection.entity.Inspection;
import com.fabwatch.inspection.entity.InspectionCheckResult;
import com.fabwatch.inspection.entity.PmSchedule;
import com.fabwatch.inspection.repository.ChecklistItemRepository;
import com.fabwatch.inspection.repository.InspectionCheckResultRepository;
import com.fabwatch.inspection.repository.InspectionRepository;
import com.fabwatch.inspection.repository.PmScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 점검 이력 등록/수정/승인 서비스 단위 테스트 (docs/03 F-3.1~3.2).
 * 알람·설비·사용자는 도메인 인터페이스를 목으로 대체한다 — 설비 상태를 바꾸는 호출이 아예 없음도 함께 확인.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InspectionServiceTest {

    private static final Long EQUIPMENT_ID = 10L;
    private static final Long WORKER_ID = 7L;
    private static final Long OTHER_USER_ID = 8L;
    private static final Long ALARM_ID = 45L;

    @Mock
    private InspectionRepository inspectionRepository;
    @Mock
    private InspectionCheckResultRepository checkResultRepository;
    @Mock
    private ChecklistItemRepository checklistItemRepository;
    @Mock
    private PmScheduleRepository pmScheduleRepository;
    @Mock
    private EquipmentQueryService equipmentQueryService;
    @Mock
    private UserQueryService userQueryService;
    @Mock
    private AlarmCommandService alarmCommandService;

    @InjectMocks
    private InspectionService service;

    private final Instant now = Instant.now();
    private final Instant started = now.minus(3, ChronoUnit.HOURS);
    private final Instant ended = now.minus(1, ChronoUnit.HOURS);

    @BeforeEach
    void setUp() {
        given(equipmentQueryService.existsById(EQUIPMENT_ID)).willReturn(true);
        given(equipmentQueryService.findCodesByIds(anyCollection())).willReturn(Map.of(EQUIPMENT_ID, "LAMI-01"));
        given(equipmentQueryService.findNamesByIds(anyCollection())).willReturn(Map.of(EQUIPMENT_ID, "합착기 1호"));
        given(userQueryService.findNamesByIds(anyCollection())).willReturn(Map.of(WORKER_ID, "김테크"));
        given(inspectionRepository.save(any(Inspection.class))).willAnswer(invocation -> {
            Inspection saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 100L);
            return saved;
        });
        given(checkResultRepository.findByInspectionIdOrderByIdAsc(anyLong())).willReturn(List.of());
        given(checklistItemRepository.findAllById(anyCollection())).willReturn(List.of());
    }

    private InspectionCreateRequest request(Inspection.Type type, Instant s, Instant e, Inspection.Cause4M cause,
                                            Long alarmId, List<CheckResultRequest> results) {
        return new InspectionCreateRequest(EQUIPMENT_ID, type, null, s, e, "내용", "조치", cause, "상세", alarmId, results);
    }

    private InspectionCreateRequest bm(Inspection.Cause4M cause, Long alarmId) {
        return request(Inspection.Type.BM, started, ended, cause, alarmId, null);
    }

    private InspectionCreateRequest pm(List<CheckResultRequest> results) {
        return request(Inspection.Type.PM, started, ended, null, null, results);
    }

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }

    // ------------------------------------------------------------ 시각/4M 검증

    @Test
    @DisplayName("종료 일시가 시작 일시와 같거나 빠르면 400 VALIDATION_ERROR")
    void endedMustBeAfterStarted() {
        assertCode(() -> service.create(request(Inspection.Type.PM, ended, started, null, null, null), WORKER_ID),
                ErrorCode.VALIDATION_ERROR);
        assertCode(() -> service.create(request(Inspection.Type.PM, started, started, null, null, null), WORKER_ID),
                ErrorCode.VALIDATION_ERROR);
        verify(inspectionRepository, never()).save(any());
    }

    @Test
    @DisplayName("미래 시각은 400 VALIDATION_ERROR")
    void futureNotAllowed() {
        Instant futureStart = now.plus(1, ChronoUnit.HOURS);
        Instant futureEnd = now.plus(2, ChronoUnit.HOURS);
        assertCode(() -> service.create(request(Inspection.Type.PM, futureStart, futureEnd, null, null, null), WORKER_ID),
                ErrorCode.VALIDATION_ERROR);
        // 시작은 과거여도 종료가 미래면 거절
        assertCode(() -> service.create(request(Inspection.Type.PM, started, futureEnd, null, null, null), WORKER_ID),
                ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("BM은 cause4m 필수 — 없으면 400 CAUSE_4M_REQUIRED, PM은 불필요")
    void bmRequiresCause4m() {
        assertCode(() -> service.create(bm(null, null), WORKER_ID), ErrorCode.CAUSE_4M_REQUIRED);

        InspectionDetailResponse pmResult = service.create(pm(null), WORKER_ID);
        assertThat(pmResult.cause4m()).isNull();
    }

    @Test
    @DisplayName("소요시간(분) 자동 계산, shift 생략 시 시작 시각(KST)으로 자동 판정, 작성자는 로그인 사용자")
    void durationShiftWorker() {
        // KST 2026-07-06 22:00(N) ~ 23:30 = UTC 13:00 ~ 14:30
        Instant s = Instant.parse("2026-07-06T13:00:00Z");
        Instant e = Instant.parse("2026-07-06T14:30:00Z");

        InspectionDetailResponse result = service.create(
                request(Inspection.Type.BM, s, e, Inspection.Cause4M.MACHINE, null, null), WORKER_ID);

        assertThat(result.durationMin()).isEqualTo(90);
        assertThat(result.shift()).isEqualTo("N");
        assertThat(result.workerId()).isEqualTo(WORKER_ID);
        assertThat(result.workerName()).isEqualTo("김테크");
        assertThat(result.equipmentCode()).isEqualTo("LAMI-01");
        assertThat(result.equipmentName()).isEqualTo("합착기 1호");
    }

    @Test
    @DisplayName("명시한 shift는 자동 판정보다 우선하고, 24시간 초과도 허용(서버 에러 아님)")
    void explicitShiftAndLongDuration() {
        Instant s = now.minus(40, ChronoUnit.HOURS);
        Instant e = now.minus(1, ChronoUnit.HOURS);
        InspectionCreateRequest req = new InspectionCreateRequest(EQUIPMENT_ID, Inspection.Type.BM,
                Inspection.Shift.D, s, e, "대수리", null, Inspection.Cause4M.MACHINE, null, null, null);

        InspectionDetailResponse result = service.create(req, WORKER_ID);

        assertThat(result.shift()).isEqualTo("D");
        assertThat(result.durationMin()).isEqualTo(39 * 60);
    }

    @Test
    @DisplayName("존재하지 않는 설비는 404")
    void unknownEquipment() {
        given(equipmentQueryService.existsById(999L)).willReturn(false);
        InspectionCreateRequest req = new InspectionCreateRequest(999L, Inspection.Type.PM, null, started, ended,
                "내용", null, null, null, null, null);
        assertCode(() -> service.create(req, WORKER_ID), ErrorCode.NOT_FOUND);
    }

    // ------------------------------------------------------------ 체크리스트 / NG

    private static ChecklistItem item(long id) {
        ChecklistItem item = ChecklistItem.builder().equipmentId(EQUIPMENT_ID).itemName("항목" + id)
                .criteria("기준").seq((int) id).active(true).build();
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }

    @Test
    @DisplayName("체크리스트에 NG가 1건이라도 있으면 hasNg=true, 전부 OK/NA면 false")
    void ngFlag() {
        given(checklistItemRepository.findByEquipmentIdAndIdIn(eq(EQUIPMENT_ID), anyCollection()))
                .willReturn(List.of(item(1), item(2)));

        InspectionDetailResponse withNg = service.create(pm(List.of(
                new CheckResultRequest(1L, InspectionCheckResult.Result.OK, null),
                new CheckResultRequest(2L, InspectionCheckResult.Result.NG, "기준 미달"))), WORKER_ID);
        assertThat(withNg.hasNg()).isTrue();

        InspectionDetailResponse noNg = service.create(pm(List.of(
                new CheckResultRequest(1L, InspectionCheckResult.Result.OK, null),
                new CheckResultRequest(2L, InspectionCheckResult.Result.NA, null))), WORKER_ID);
        assertThat(noNg.hasNg()).isFalse();
    }

    @Test
    @DisplayName("다른 설비의 체크리스트 항목·중복 항목·BM 체크 결과는 400")
    void invalidCheckResults() {
        given(checklistItemRepository.findByEquipmentIdAndIdIn(eq(EQUIPMENT_ID), anyCollection()))
                .willReturn(List.of(item(1)));

        // 2번 항목은 이 설비 템플릿이 아님
        assertCode(() -> service.create(pm(List.of(
                new CheckResultRequest(1L, InspectionCheckResult.Result.OK, null),
                new CheckResultRequest(2L, InspectionCheckResult.Result.OK, null))), WORKER_ID), ErrorCode.VALIDATION_ERROR);
        // 같은 항목 두 번
        assertCode(() -> service.create(pm(List.of(
                new CheckResultRequest(1L, InspectionCheckResult.Result.OK, null),
                new CheckResultRequest(1L, InspectionCheckResult.Result.NG, null))), WORKER_ID), ErrorCode.VALIDATION_ERROR);
        // BM에는 체크 결과 불가
        assertCode(() -> service.create(request(Inspection.Type.BM, started, ended, Inspection.Cause4M.MAN, null,
                List.of(new CheckResultRequest(1L, InspectionCheckResult.Result.OK, null))), WORKER_ID), ErrorCode.VALIDATION_ERROR);
    }

    // ------------------------------------------------------------ PM 스케줄 갱신

    private PmSchedule schedule(Instant lastDone, Instant nextDue, boolean alarmSent) {
        return PmSchedule.builder().equipmentId(EQUIPMENT_ID).cycleType(PmSchedule.CycleType.DAILY)
                .lastDoneAt(lastDone).nextDueAt(nextDue).overdueAlarmSent(alarmSent).build();
    }

    @Test
    @DisplayName("PM 등록 → 스케줄 last_done=종료 시각, next_due 재계산, overdue_alarm_sent=false 초기화 (PM-7)")
    void pmUpdatesSchedule() {
        PmSchedule schedule = schedule(now.minus(10, ChronoUnit.DAYS), now.minus(9, ChronoUnit.DAYS), true);
        given(pmScheduleRepository.findByEquipmentId(EQUIPMENT_ID)).willReturn(Optional.of(schedule));

        service.create(pm(null), WORKER_ID);

        assertThat(schedule.getLastDoneAt()).isEqualTo(ended);
        assertThat(schedule.getNextDueAt()).isEqualTo(ended.plus(1, ChronoUnit.DAYS)); // DAILY +1일
        assertThat(schedule.isOverdueAlarmSent()).isFalse();
    }

    @Test
    @DisplayName("BM 등록은 PM 스케줄을 건드리지 않고, 스케줄 없는 설비의 PM 등록도 에러 없이 통과")
    void bmLeavesScheduleAlone() {
        PmSchedule schedule = schedule(now.minus(10, ChronoUnit.DAYS), now.minus(9, ChronoUnit.DAYS), true);
        given(pmScheduleRepository.findByEquipmentId(EQUIPMENT_ID)).willReturn(Optional.of(schedule));
        service.create(bm(Inspection.Cause4M.MACHINE, null), WORKER_ID);
        assertThat(schedule.isOverdueAlarmSent()).isTrue();
        assertThat(schedule.getNextDueAt()).isEqualTo(now.minus(9, ChronoUnit.DAYS));

        given(pmScheduleRepository.findByEquipmentId(EQUIPMENT_ID)).willReturn(Optional.empty());
        assertThat(service.create(pm(null), WORKER_ID).id()).isEqualTo(100L);
    }

    @Test
    @DisplayName("기존 last_done보다 이전인 PM을 소급 등록해도 스케줄을 되돌리지 않는다")
    void backfilledPmDoesNotRewindSchedule() {
        Instant lastDone = now.minus(30, ChronoUnit.MINUTES);
        Instant nextDue = lastDone.plus(1, ChronoUnit.DAYS);
        PmSchedule schedule = schedule(lastDone, nextDue, false);
        given(pmScheduleRepository.findByEquipmentId(EQUIPMENT_ID)).willReturn(Optional.of(schedule));

        service.create(pm(null), WORKER_ID); // ended = now-1h < lastDone

        assertThat(schedule.getLastDoneAt()).isEqualTo(lastDone);
        assertThat(schedule.getNextDueAt()).isEqualTo(nextDue);
    }

    // ------------------------------------------------------------ 알람 연계

    @Test
    @DisplayName("BM + alarmId → 작성자 명의로 ACK→RESOLVE 위임, 해제 사유는 'BM 점검 이력 #id로 조치 완료'")
    void bmResolvesLinkedAlarm() {
        service.create(bm(Inspection.Cause4M.MACHINE, ALARM_ID), WORKER_ID);

        verify(alarmCommandService).assertBelongsToEquipment(ALARM_ID, EQUIPMENT_ID);
        verify(alarmCommandService).ackAndResolve(ALARM_ID, WORKER_ID, "BM 점검 이력 #100로 조치 완료");
    }

    @Test
    @DisplayName("다른 설비 알람이면 400이고 점검 이력은 저장되지 않으며 알람도 건드리지 않는다")
    void otherEquipmentAlarmRejected() {
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "다른 설비 알람"))
                .when(alarmCommandService).assertBelongsToEquipment(ALARM_ID, EQUIPMENT_ID);

        assertCode(() -> service.create(bm(Inspection.Cause4M.MACHINE, ALARM_ID), WORKER_ID), ErrorCode.VALIDATION_ERROR);

        verify(inspectionRepository, never()).save(any());
        verify(alarmCommandService, never()).ackAndResolve(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("PM에 alarmId를 붙이면 400 (알람 연계는 BM 전용)")
    void pmCannotLinkAlarm() {
        assertCode(() -> service.create(request(Inspection.Type.PM, started, ended, null, ALARM_ID, null), WORKER_ID),
                ErrorCode.VALIDATION_ERROR);
        verifyNoInteractions(alarmCommandService);
    }

    @Test
    @DisplayName("alarmId 없는 BM은 알람 서비스를 호출하지 않는다")
    void bmWithoutAlarm() {
        service.create(bm(Inspection.Cause4M.MAN, null), WORKER_ID);
        verifyNoInteractions(alarmCommandService);
    }

    // ------------------------------------------------------------ PM 수행 → PM_OVERDUE 알람 자동 해소

    @Test
    @DisplayName("PM 등록으로 스케줄이 갱신되면 작성자 명의로 해당 설비의 PM_OVERDUE 알람 해소를 위임한다")
    void pmResolvesPmOverdueAlarms() {
        PmSchedule schedule = schedule(now.minus(10, ChronoUnit.DAYS), now.minus(9, ChronoUnit.DAYS), true);
        given(pmScheduleRepository.findByEquipmentId(EQUIPMENT_ID)).willReturn(Optional.of(schedule));

        service.create(pm(null), WORKER_ID);

        verify(alarmCommandService).resolvePmOverdueByEquipment(EQUIPMENT_ID, WORKER_ID, "PM 점검 이력 #100로 수행 완료");
        verify(alarmCommandService, never()).ackAndResolve(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("소급 PM 등록(스케줄 안 되돌림)·스케줄 없는 설비는 PM_OVERDUE 알람을 해소하지 않는다")
    void backfilledOrNoSchedulePmDoesNotResolve() {
        Instant lastDone = now.minus(30, ChronoUnit.MINUTES);
        given(pmScheduleRepository.findByEquipmentId(EQUIPMENT_ID))
                .willReturn(Optional.of(schedule(lastDone, lastDone.plus(1, ChronoUnit.DAYS), false)));
        service.create(pm(null), WORKER_ID); // ended = now-1h < lastDone → 소급

        given(pmScheduleRepository.findByEquipmentId(EQUIPMENT_ID)).willReturn(Optional.empty());
        service.create(pm(null), WORKER_ID);

        verify(alarmCommandService, never()).resolvePmOverdueByEquipment(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("BM 등록은 PM_OVERDUE 알람 해소를 호출하지 않는다 (스케줄이 있어도)")
    void bmDoesNotResolvePmOverdue() {
        given(pmScheduleRepository.findByEquipmentId(EQUIPMENT_ID)).willReturn(Optional.of(
                schedule(now.minus(10, ChronoUnit.DAYS), now.minus(9, ChronoUnit.DAYS), true)));

        service.create(bm(Inspection.Cause4M.MACHINE, ALARM_ID), WORKER_ID);

        verify(alarmCommandService, never()).resolvePmOverdueByEquipment(anyLong(), anyLong(), anyString());
        verify(alarmCommandService).ackAndResolve(ALARM_ID, WORKER_ID, "BM 점검 이력 #100로 조치 완료");
    }

    @Test
    @DisplayName("알람 해소가 실패하면 예외가 그대로 전파된다 (같은 트랜잭션 → 호출 측에서 전부 롤백)")
    void pmAlarmResolveFailurePropagates() {
        given(pmScheduleRepository.findByEquipmentId(EQUIPMENT_ID)).willReturn(Optional.of(
                schedule(now.minus(10, ChronoUnit.DAYS), now.minus(9, ChronoUnit.DAYS), true)));
        org.mockito.Mockito.doThrow(new IllegalStateException("해소 실패"))
                .when(alarmCommandService).resolvePmOverdueByEquipment(anyLong(), anyLong(), anyString());

        assertThatThrownBy(() -> service.create(pm(null), WORKER_ID))
                .isInstanceOf(IllegalStateException.class).hasMessage("해소 실패");
    }

    // ------------------------------------------------------------ 수정 권한

    private Inspection existingBm() {
        Inspection inspection = Inspection.builder().equipmentId(EQUIPMENT_ID).type(Inspection.Type.BM)
                .shift(Inspection.Shift.D).workerId(WORKER_ID).startedAt(started).endedAt(ended).durationMin(120)
                .content("원본").cause4m(Inspection.Cause4M.MACHINE).alarmId(ALARM_ID).build();
        ReflectionTestUtils.setField(inspection, "id", 100L);
        given(inspectionRepository.findById(100L)).willReturn(Optional.of(inspection));
        return inspection;
    }

    private InspectionUpdateRequest update(Inspection.Cause4M cause) {
        return new InspectionUpdateRequest(null, started, ended, "수정본", "조치", cause, "상세", null);
    }

    @Test
    @DisplayName("작성자 본인은 수정 가능, 설비·유형·알람 연계는 불변")
    void authorCanUpdate() {
        Inspection inspection = existingBm();

        InspectionDetailResponse result = service.update(100L, update(Inspection.Cause4M.METHOD), WORKER_ID, "TECHNICIAN");

        assertThat(result.content()).isEqualTo("수정본");
        assertThat(result.cause4m()).isEqualTo("METHOD");
        assertThat(inspection.getEquipmentId()).isEqualTo(EQUIPMENT_ID);
        assertThat(inspection.getType()).isEqualTo(Inspection.Type.BM);
        assertThat(inspection.getAlarmId()).isEqualTo(ALARM_ID);
        assertThat(inspection.getWorkerId()).isEqualTo(WORKER_ID);
    }

    @Test
    @DisplayName("ENGINEER·ADMIN은 타인 이력도 수정 가능")
    void engineerAndAdminCanUpdate() {
        existingBm();
        assertThat(service.update(100L, update(Inspection.Cause4M.MAN), OTHER_USER_ID, "ENGINEER").content()).isEqualTo("수정본");
        assertThat(service.update(100L, update(Inspection.Cause4M.MAN), OTHER_USER_ID, "ADMIN").content()).isEqualTo("수정본");
    }

    @Test
    @DisplayName("타인(TECHNICIAN)의 수정은 403 FORBIDDEN")
    void otherTechnicianForbidden() {
        Inspection inspection = existingBm();
        assertCode(() -> service.update(100L, update(Inspection.Cause4M.MAN), OTHER_USER_ID, "TECHNICIAN"), ErrorCode.FORBIDDEN);
        assertThat(inspection.getContent()).isEqualTo("원본");
    }

    @Test
    @DisplayName("수정에도 POST와 같은 검증 — BM 4M 누락 400, 시각 역전 400")
    void updateValidation() {
        existingBm();
        assertCode(() -> service.update(100L, update(null), WORKER_ID, "TECHNICIAN"), ErrorCode.CAUSE_4M_REQUIRED);
        InspectionUpdateRequest reversed = new InspectionUpdateRequest(null, ended, started, "x", null,
                Inspection.Cause4M.MAN, null, null);
        assertCode(() -> service.update(100L, reversed, WORKER_ID, "TECHNICIAN"), ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("수정 시 checkResults 생략이면 기존 결과 유지, 값이 있으면 기존 soft delete 후 교체 + hasNg 재계산")
    void updateCheckResults() {
        Inspection pmInspection = Inspection.builder().equipmentId(EQUIPMENT_ID).type(Inspection.Type.PM)
                .shift(Inspection.Shift.D).workerId(WORKER_ID).startedAt(started).endedAt(ended).durationMin(120)
                .content("원본").hasNg(true).build();
        ReflectionTestUtils.setField(pmInspection, "id", 100L);
        given(inspectionRepository.findById(100L)).willReturn(Optional.of(pmInspection));
        InspectionCheckResult old = InspectionCheckResult.builder().inspectionId(100L).checklistItemId(1L)
                .result(InspectionCheckResult.Result.NG).build();
        given(checkResultRepository.findByInspectionIdOrderByIdAsc(100L)).willReturn(new ArrayList<>(List.of(old)));
        given(checklistItemRepository.findByEquipmentIdAndIdIn(eq(EQUIPMENT_ID), anyCollection())).willReturn(List.of(item(1)));

        // 생략 → 유지
        service.update(100L, new InspectionUpdateRequest(null, started, ended, "수정", null, null, null, null),
                WORKER_ID, "TECHNICIAN");
        assertThat(old.isDeleted()).isFalse();
        assertThat(pmInspection.isHasNg()).isTrue();

        // 교체
        service.update(100L, new InspectionUpdateRequest(null, started, ended, "수정", null, null, null,
                List.of(new CheckResultRequest(1L, InspectionCheckResult.Result.OK, null))), WORKER_ID, "TECHNICIAN");
        assertThat(old.isDeleted()).isTrue();
        assertThat(pmInspection.isHasNg()).isFalse();
        verify(checkResultRepository).saveAll(any());
    }

    // ------------------------------------------------------------ 승인

    @Test
    @DisplayName("승인 시 reviewedBy/reviewedAt 기록, 이미 승인된 건은 멱등(기존 승인자 유지)")
    void reviewIsIdempotent() {
        Inspection inspection = existingBm();

        service.review(100L, OTHER_USER_ID);
        assertThat(inspection.getReviewedBy()).isEqualTo(OTHER_USER_ID);
        Instant firstReviewedAt = inspection.getReviewedAt();
        assertThat(firstReviewedAt).isNotNull();

        service.review(100L, 99L);
        assertThat(inspection.getReviewedBy()).isEqualTo(OTHER_USER_ID);
        assertThat(inspection.getReviewedAt()).isEqualTo(firstReviewedAt);
    }

    @Test
    @DisplayName("없는 이력 조회·수정·승인은 404")
    void notFound() {
        given(inspectionRepository.findById(404L)).willReturn(Optional.empty());
        assertCode(() -> service.getInspection(404L), ErrorCode.NOT_FOUND);
        assertCode(() -> service.update(404L, update(null), WORKER_ID, "ADMIN"), ErrorCode.NOT_FOUND);
        assertCode(() -> service.review(404L, WORKER_ID), ErrorCode.NOT_FOUND);
    }

    // ------------------------------------------------------------ PM 이력 수정 → 스케줄 재계산 (안정성 감사 M-11)

    private Inspection existingPm(Instant endedAt) {
        Inspection inspection = Inspection.builder().equipmentId(EQUIPMENT_ID).type(Inspection.Type.PM)
                .shift(Inspection.Shift.D).workerId(WORKER_ID).startedAt(endedAt.minus(2, ChronoUnit.HOURS))
                .endedAt(endedAt).durationMin(120).content("PM").build();
        ReflectionTestUtils.setField(inspection, "id", 100L);
        given(inspectionRepository.findById(100L)).willReturn(Optional.of(inspection));
        return inspection;
    }

    private InspectionUpdateRequest updateEnded(Instant newEndedAt) {
        return new InspectionUpdateRequest(null, newEndedAt.minus(2, ChronoUnit.HOURS), newEndedAt, "수정", null, null, null, null);
    }

    @Test
    @DisplayName("★ 마지막 수행 PM의 종료 시각을 늦추면 last_done·next_due가 재계산되고 지연 플래그가 초기화되며 PM 지연 알람이 해소된다")
    void editLastPmLater() {
        Instant oldEnded = now.minus(5, ChronoUnit.HOURS);
        Instant newEnded = now.minus(2, ChronoUnit.HOURS);
        existingPm(oldEnded);
        PmSchedule schedule = schedule(oldEnded, oldEnded.plus(1, ChronoUnit.DAYS), true);
        given(pmScheduleRepository.findByEquipmentId(EQUIPMENT_ID)).willReturn(Optional.of(schedule));
        given(inspectionRepository.findLatestEndedAt(EQUIPMENT_ID, Inspection.Type.PM)).willReturn(Optional.of(newEnded));

        service.update(100L, updateEnded(newEnded), WORKER_ID, "TECHNICIAN");

        assertThat(schedule.getLastDoneAt()).isEqualTo(newEnded);
        assertThat(schedule.getNextDueAt()).isEqualTo(newEnded.plus(1, ChronoUnit.DAYS)); // DAILY
        assertThat(schedule.isOverdueAlarmSent()).isFalse();
        verify(alarmCommandService).resolvePmOverdueByEquipment(eq(EQUIPMENT_ID), eq(WORKER_ID), anyString());
    }

    @Test
    @DisplayName("★ 마지막 수행 PM의 종료 시각을 앞당기면 직전 PM 수행 시각까지만 되돌려진다 (그보다 과거로는 못 감), 알람은 새로 해소하지 않는다")
    void editLastPmEarlierRewindsOnlyToPreviousPm() {
        Instant oldEnded = now.minus(5, ChronoUnit.HOURS);
        Instant newEnded = now.minus(30, ChronoUnit.DAYS);               // 크게 앞당김
        Instant previousPmEnded = now.minus(10, ChronoUnit.DAYS);        // 그 설비의 직전 PM
        existingPm(oldEnded);
        PmSchedule schedule = schedule(oldEnded, oldEnded.plus(1, ChronoUnit.DAYS), false);
        given(pmScheduleRepository.findByEquipmentId(EQUIPMENT_ID)).willReturn(Optional.of(schedule));
        // 수정 반영 후 이 설비 PM 이력 중 가장 늦은 종료 = 직전 PM
        given(inspectionRepository.findLatestEndedAt(EQUIPMENT_ID, Inspection.Type.PM)).willReturn(Optional.of(previousPmEnded));

        service.update(100L, updateEnded(newEnded), WORKER_ID, "TECHNICIAN");

        assertThat(schedule.getLastDoneAt()).isEqualTo(previousPmEnded);
        assertThat(schedule.getNextDueAt()).isEqualTo(previousPmEnded.plus(1, ChronoUnit.DAYS));
        verify(alarmCommandService, never()).resolvePmOverdueByEquipment(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("마지막 수행이 아닌 과거 PM의 종료 시각 수정은 스케줄에 영향이 없다")
    void editOlderPmLeavesScheduleAlone() {
        Instant lastDone = now.minus(1, ChronoUnit.DAYS);
        Instant oldEnded = now.minus(8, ChronoUnit.DAYS);
        Instant newEnded = now.minus(7, ChronoUnit.DAYS); // 여전히 last_done보다 이전
        existingPm(oldEnded);
        PmSchedule schedule = schedule(lastDone, lastDone.plus(1, ChronoUnit.DAYS), true);
        given(pmScheduleRepository.findByEquipmentId(EQUIPMENT_ID)).willReturn(Optional.of(schedule));

        service.update(100L, updateEnded(newEnded), WORKER_ID, "TECHNICIAN");

        assertThat(schedule.getLastDoneAt()).isEqualTo(lastDone);
        assertThat(schedule.isOverdueAlarmSent()).isTrue();
        verify(inspectionRepository, never()).findLatestEndedAt(anyLong(), any());
        verify(alarmCommandService, never()).resolvePmOverdueByEquipment(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("과거 PM을 last_done보다 늦게 고쳐 새 마지막 수행이 되면 스케줄이 그 시각으로 갱신된다")
    void editOlderPmBecomesLast() {
        Instant lastDone = now.minus(5, ChronoUnit.DAYS);
        Instant oldEnded = now.minus(8, ChronoUnit.DAYS);
        Instant newEnded = now.minus(3, ChronoUnit.HOURS); // last_done 이후로 이동
        existingPm(oldEnded);
        PmSchedule schedule = schedule(lastDone, lastDone.plus(1, ChronoUnit.DAYS), true);
        given(pmScheduleRepository.findByEquipmentId(EQUIPMENT_ID)).willReturn(Optional.of(schedule));
        given(inspectionRepository.findLatestEndedAt(EQUIPMENT_ID, Inspection.Type.PM)).willReturn(Optional.of(newEnded));

        service.update(100L, updateEnded(newEnded), WORKER_ID, "TECHNICIAN");

        assertThat(schedule.getLastDoneAt()).isEqualTo(newEnded);
        assertThat(schedule.isOverdueAlarmSent()).isFalse();
    }

    @Test
    @DisplayName("종료 시각이 바뀌지 않는 PM 수정(내용만)은 스케줄을 조회하지도 않는다")
    void editContentOnlyDoesNotTouchSchedule() {
        Instant oldEnded = now.minus(5, ChronoUnit.HOURS);
        existingPm(oldEnded);

        service.update(100L, updateEnded(oldEnded), WORKER_ID, "TECHNICIAN");

        verify(pmScheduleRepository, never()).findByEquipmentId(anyLong());
    }

    @Test
    @DisplayName("BM 수정·스케줄 없는 설비의 PM 수정은 스케줄을 건드리지 않고 에러도 없다")
    void bmAndNoScheduleAreSafe() {
        existingBm();
        service.update(100L, new InspectionUpdateRequest(null, started, ended.minus(10, ChronoUnit.MINUTES),
                "수정", "조치", Inspection.Cause4M.MACHINE, null, null), WORKER_ID, "TECHNICIAN");
        verify(pmScheduleRepository, never()).findByEquipmentId(anyLong());

        Instant oldEnded = now.minus(5, ChronoUnit.HOURS);
        existingPm(oldEnded);
        given(pmScheduleRepository.findByEquipmentId(EQUIPMENT_ID)).willReturn(Optional.empty());
        assertThat(service.update(100L, updateEnded(now.minus(4, ChronoUnit.HOURS)), WORKER_ID, "TECHNICIAN").id())
                .isEqualTo(100L);
    }
}
