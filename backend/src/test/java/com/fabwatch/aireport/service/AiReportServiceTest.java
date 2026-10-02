package com.fabwatch.aireport.service;

import com.fabwatch.aireport.dto.AiReportAcceptedResponse;
import com.fabwatch.aireport.dto.AiReportCreateRequest;
import com.fabwatch.aireport.entity.AiCallLog;
import com.fabwatch.aireport.entity.AiReport;
import com.fabwatch.aireport.repository.AiReportRepository;
import com.fabwatch.alarm.dto.AlarmSnapshot;
import com.fabwatch.alarm.service.AlarmQueryService;
import com.fabwatch.auth.service.UserQueryService;
import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import com.fabwatch.equipment.service.EquipmentQueryService;
import com.fabwatch.aireport.dto.AiReportUpdateRequest;
import com.fabwatch.inspection.service.InspectionQueryService;
import com.fabwatch.inspection.service.InspectionSummary;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 서비스 오케스트레이션 단위 테스트 — ALREADY_GENERATING, 쿼터 우선순위, 큐 포화, 타 도메인 인터페이스 사용.
 * (DB·스레드 없이 목으로 검증. 실DB 경로는 AiReportApiIntegrationTest)
 */
class AiReportServiceTest {

    private static final Instant OCCURRED = Instant.parse("2026-10-02T14:03:00Z");

    private AiReportRepository reportRepository;
    private AiQuotaService quotaService;
    private AiReportGenerator generator;
    private AiReportResultWriter resultWriter;
    private AlarmQueryService alarmQueryService;
    private InspectionQueryService inspectionQueryService;
    private EquipmentQueryService equipmentQueryService;
    private AiReportService service;
    private final AtomicReference<Runnable> submitted = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        reportRepository = mock(AiReportRepository.class);
        quotaService = mock(AiQuotaService.class);
        generator = mock(AiReportGenerator.class);
        resultWriter = mock(AiReportResultWriter.class);
        alarmQueryService = mock(AlarmQueryService.class);
        inspectionQueryService = mock(InspectionQueryService.class);
        equipmentQueryService = mock(EquipmentQueryService.class);

        when(alarmQueryService.findSnapshot(10L)).thenReturn(Optional.of(new AlarmSnapshot(10L, 1L, 100L,
                "SENSOR_THRESHOLD", "CRITICAL", "OPEN", new BigDecimal("5.3"), new BigDecimal("5.0"), "진동 초과",
                OCCURRED, null)));
        when(equipmentQueryService.findCodeById(1L)).thenReturn(Optional.of("LAMI-01"));
        when(reportRepository.save(any(AiReport.class))).thenAnswer(inv -> {
            AiReport r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "id", 99L);
            return r;
        });
        AiCallLog callLog = new AiCallLog(99L, 5L, OCCURRED, "mock", "mock");
        ReflectionTestUtils.setField(callLog, "id", 990L);
        when(quotaService.reserve(anyLong(), anyLong())).thenReturn(callLog);

        service = newService(new AiReportDispatcher(submitted::set));
    }

    private AiReportService newService(AiReportDispatcher dispatcher) {
        return new AiReportService(reportRepository, quotaService, dispatcher, generator, resultWriter,
                alarmQueryService, inspectionQueryService, equipmentQueryService, mock(UserQueryService.class),
                Clock.fixed(OCCURRED, ZoneOffset.UTC), mock(PlatformTransactionManager.class));
    }

    @Test
    @DisplayName("정상 수락: 202 shape, 제목은 '고장 리포트 — 설비코드 발생일시(KST)', 워커로 넘기는 것은 커밋 이후")
    void create_acceptsAndDispatches() {
        AiReportAcceptedResponse response = service.create(new AiReportCreateRequest(10L, null), 5L);

        assertThat(response.reportId()).isEqualTo(99L);
        assertThat(response.status()).isEqualTo("GENERATING");
        org.mockito.ArgumentCaptor<AiReport> saved = org.mockito.ArgumentCaptor.forClass(AiReport.class);
        verify(reportRepository).save(saved.capture());
        assertThat(saved.getValue().getTitle()).isEqualTo("고장 리포트 — LAMI-01 2026-10-02 23:03");
        assertThat(saved.getValue().getStatus()).isEqualTo(AiReport.Status.GENERATING);
        assertThat(saved.getValue().getCreatedBy()).isEqualTo(5L);
        verify(quotaService).reserve(99L, 5L);
        // 워커에는 reportId/logId/userId가 인자로 전달된다 (SecurityContext 의존 없음)
        submitted.get().run();
        verify(generator).generate(99L, 990L, 5L);
    }

    @Test
    @DisplayName("GENERATING 중복이면 409 ALREADY_GENERATING — 저장·쿼터·워커 모두 없음")
    void create_alreadyGenerating() {
        when(reportRepository.existsByAlarmIdAndStatusAndIdNot(eq(10L), eq(AiReport.Status.GENERATING), anyLong()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.create(new AiReportCreateRequest(10L, null), 5L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ALREADY_GENERATING));

        verify(reportRepository, never()).save(any());
        verifyNoInteractions(quotaService, generator);
        assertThat(submitted.get()).isNull();
    }

    @Test
    @DisplayName("쿼터 초과면 워커를 띄우지 않는다 (호출 자체가 발생하지 않음, docs/12 AI-3)")
    void create_quotaExceeded_noDispatch() {
        when(quotaService.reserve(anyLong(), anyLong()))
                .thenThrow(new BusinessException(ErrorCode.AI_QUOTA_EXCEEDED));

        assertThatThrownBy(() -> service.create(new AiReportCreateRequest(10L, null), 5L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_QUOTA_EXCEEDED));

        assertThat(submitted.get()).isNull();
        verifyNoInteractions(generator);
    }

    @Test
    @DisplayName("alarmId·inspectionId 둘 다 없으면 400 — 다른 도메인 조회도 하지 않는다")
    void create_requiresOneTarget() {
        assertThatThrownBy(() -> service.create(new AiReportCreateRequest(null, null), 5L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verifyNoInteractions(alarmQueryService, inspectionQueryService, quotaService);
    }

    @Test
    @DisplayName("대기열이 가득 차 거절되면 즉시 FAILED로 마무리한다 (GENERATING에 남지 않음)")
    void create_queueFull_marksFailed() {
        AiReportService rejecting = newService(new AiReportDispatcher(task -> {
            throw new RejectedExecutionException("queue full");
        }));

        AiReportAcceptedResponse response = rejecting.create(new AiReportCreateRequest(10L, null), 5L);

        assertThat(response.reportId()).isEqualTo(99L);
        verify(resultWriter).saveFailure(eq(99L), eq(990L), any(), any());
        verifyNoInteractions(generator);
    }

    @Test
    @DisplayName("retry: FAILED가 아니면 409 INVALID_REPORT_STATE — 쿼터를 소모하지 않는다")
    void retry_requiresFailed() {
        AiReport draft = AiReport.generating(1L, 10L, null, "t", 5L);
        draft.completeDraft("원본", "m", 1, 1);
        ReflectionTestUtils.setField(draft, "id", 50L);
        when(reportRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.retry(50L, 5L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REPORT_STATE));
        verifyNoInteractions(quotaService);
    }

    @Test
    @DisplayName("retry: FAILED → GENERATING, 같은 reportId, 쿼터 확인 후 워커 투입")
    void retry_reusesReportId() {
        AiReport failed = AiReport.generating(1L, 10L, null, "t", 5L);
        failed.fail("실패", "m");
        ReflectionTestUtils.setField(failed, "id", 50L);
        when(reportRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(failed));

        AiReportAcceptedResponse response = service.retry(50L, 5L);

        assertThat(response.reportId()).isEqualTo(50L);
        assertThat(failed.getStatus()).isEqualTo(AiReport.Status.GENERATING);
        verify(quotaService).reserve(50L, 5L);
        submitted.get().run();
        verify(generator).generate(50L, 990L, 5L);
    }

    // ------------------------------------------------------------ M-4 알람 경로/점검 경로 중복 판정

    private void stubInspection(long id, Long linkedAlarmId) {
        when(inspectionQueryService.findSummary(id)).thenReturn(Optional.of(new InspectionSummary(id, 1L, "BM", "D",
                7L, OCCURRED, 30, "내용", "조치", "MACHINE", "마모", false, linkedAlarmId, List.of())));
    }

    @Test
    @DisplayName("M-4: 점검만 지정했는데 연계 알람이 있으면 report.alarmId에도 그 알람 id를 저장한다")
    void create_inspectionOnly_storesLinkedAlarmId() {
        stubInspection(20L, 10L);

        service.create(new AiReportCreateRequest(null, 20L), 5L);

        org.mockito.ArgumentCaptor<AiReport> saved = org.mockito.ArgumentCaptor.forClass(AiReport.class);
        verify(reportRepository).save(saved.capture());
        assertThat(saved.getValue().getAlarmId()).isEqualTo(10L);
        assertThat(saved.getValue().getInspectionId()).isEqualTo(20L);
        // 제목의 기준 시각도 연계 알람 발생 시각
        assertThat(saved.getValue().getTitle()).isEqualTo("고장 리포트 — LAMI-01 2026-10-02 23:03");
    }

    @Test
    @DisplayName("M-4: 연계 알람이 없는 점검만 지정하면 alarmId는 null (기존 동작 유지)")
    void create_inspectionOnly_withoutLinkedAlarm_keepsNull() {
        stubInspection(21L, null);

        service.create(new AiReportCreateRequest(null, 21L), 5L);

        org.mockito.ArgumentCaptor<AiReport> saved = org.mockito.ArgumentCaptor.forClass(AiReport.class);
        verify(reportRepository).save(saved.capture());
        assertThat(saved.getValue().getAlarmId()).isNull();
    }

    @Test
    @DisplayName("M-4: 연계 알람이 삭제돼 조회되지 않으면 존재하지 않는 alarm_id를 저장하지 않는다")
    void create_inspectionOnly_linkedAlarmMissing_notStored() {
        stubInspection(22L, 404L);
        when(alarmQueryService.findSnapshot(404L)).thenReturn(Optional.empty());

        service.create(new AiReportCreateRequest(null, 22L), 5L);

        org.mockito.ArgumentCaptor<AiReport> saved = org.mockito.ArgumentCaptor.forClass(AiReport.class);
        verify(reportRepository).save(saved.capture());
        assertThat(saved.getValue().getAlarmId()).isNull();
    }

    @Test
    @DisplayName("M-4: 요청에 alarmId가 있으면 요청값 우선 (점검의 연계 알람과 달라도 요청값 저장)")
    void create_requestAlarmWinsOverLinkedAlarm() {
        stubInspection(23L, 11L);

        service.create(new AiReportCreateRequest(10L, 23L), 5L);

        org.mockito.ArgumentCaptor<AiReport> saved = org.mockito.ArgumentCaptor.forClass(AiReport.class);
        verify(reportRepository).save(saved.capture());
        assertThat(saved.getValue().getAlarmId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("M-4: 알람 경로로 GENERATING 중인 알람에 연계된 점검으로 생성하면 같은 알람 기준 409 ALREADY_GENERATING")
    void create_linkedInspection_conflictsWithAlarmPathReport() {
        stubInspection(20L, 10L);
        // 알람 10에 대해 GENERATING 리포트가 있다 (알람으로 먼저 생성)
        when(reportRepository.existsByAlarmIdAndStatusAndIdNot(eq(10L), eq(AiReport.Status.GENERATING), anyLong()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.create(new AiReportCreateRequest(null, 20L), 5L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ALREADY_GENERATING));
        verify(reportRepository, never()).save(any());
        verifyNoInteractions(quotaService);
    }

    @Test
    @DisplayName("M-4 반대 방향: 점검 경로로 만든 리포트(alarmId 저장됨)가 GENERATING이면 그 알람으로 생성 시 409")
    void create_alarmPath_conflictsWithInspectionPathReport() {
        // 점검 경로 리포트는 이제 alarm_id=10으로 저장되므로 알람 10 조회에 걸린다
        when(reportRepository.existsByAlarmIdAndStatusAndIdNot(eq(10L), eq(AiReport.Status.GENERATING), anyLong()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.create(new AiReportCreateRequest(10L, null), 5L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ALREADY_GENERATING));
    }

    // ------------------------------------------------------------ M-3 확정본 불변 — 행 잠금 조회 사용

    @Test
    @DisplayName("M-3: PUT·confirm·retry는 잠금 조회(findByIdForUpdate)로 상태를 읽고 일반 findById는 쓰지 않는다")
    void editConfirmRetry_useRowLockQuery() {
        AiReport draft = AiReport.generating(1L, 10L, null, "t", 5L);
        draft.completeDraft("원본", "m", 1, 1);
        ReflectionTestUtils.setField(draft, "id", 60L);
        when(reportRepository.findByIdForUpdate(60L)).thenReturn(Optional.of(draft));

        service.updateFinal(60L, new AiReportUpdateRequest("편집본"));
        service.confirm(60L, 5L);
        assertThatThrownBy(() -> service.retry(60L, 5L)).isInstanceOf(BusinessException.class); // CONFIRMED는 retry 불가

        verify(reportRepository, org.mockito.Mockito.times(3)).findByIdForUpdate(60L);
        verify(reportRepository, never()).findById(any());
        assertThat(draft.getStatus()).isEqualTo(AiReport.Status.CONFIRMED);
        assertThat(draft.getFinalContent()).isEqualTo("편집본");
    }

    @Test
    @DisplayName("M-3: 잠금 획득 뒤 이미 CONFIRMED면 PUT은 409 — final_content는 바뀌지 않는다 (상태 검증이 잠금 이후)")
    void updateFinal_afterConfirm_rejectedAndContentUnchanged() {
        AiReport confirmed = AiReport.generating(1L, 10L, null, "t", 5L);
        confirmed.completeDraft("원본", "m", 1, 1);
        confirmed.confirm(5L, OCCURRED);
        ReflectionTestUtils.setField(confirmed, "id", 61L);
        when(reportRepository.findByIdForUpdate(61L)).thenReturn(Optional.of(confirmed));

        assertThatThrownBy(() -> service.updateFinal(61L, new AiReportUpdateRequest("덮어쓰기")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REPORT_STATE));
        assertThat(confirmed.getFinalContent()).isEqualTo("원본");
    }
}
