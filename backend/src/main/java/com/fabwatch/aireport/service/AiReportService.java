package com.fabwatch.aireport.service;

import com.fabwatch.aireport.dto.AiReportAcceptedResponse;
import com.fabwatch.aireport.dto.AiReportCreateRequest;
import com.fabwatch.aireport.dto.AiReportResponse;
import com.fabwatch.aireport.dto.AiReportSummary;
import com.fabwatch.aireport.dto.AiReportUpdateRequest;
import com.fabwatch.aireport.entity.AiCallLog;
import com.fabwatch.aireport.entity.AiReport;
import com.fabwatch.aireport.prompt.ReportTimeFormat;
import com.fabwatch.aireport.repository.AiReportRepository;
import com.fabwatch.aireport.repository.AiReportSpecifications;
import com.fabwatch.alarm.dto.AlarmSnapshot;
import com.fabwatch.alarm.service.AlarmQueryService;
import com.fabwatch.auth.service.UserQueryService;
import com.fabwatch.common.dto.PageResponse;
import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import com.fabwatch.common.util.Lookup;
import com.fabwatch.equipment.service.EquipmentQueryService;
import com.fabwatch.inspection.service.InspectionQueryService;
import com.fabwatch.inspection.service.InspectionSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * AI 리포트 서비스 (docs/03 F-6, docs/06 §7).
 *
 * 도메인 경계: 알람/점검/설비/사용자 정보는 각 도메인의 서비스 인터페이스로만 조회한다.
 *
 * 비동기 생성: 요청 수락(리포트 GENERATING + 호출 로그 reserve)을 먼저 커밋하고, 커밋 이후에 전용 워커로 넘긴다
 * (워커가 커밋된 GENERATING 행을 볼 수 있어야 하므로 afterCommit 성격). 쿼터 검사~기록은 락+트랜잭션으로 묶는다
 * // SCALE: 단일 인스턴스 전제의 JVM 락. 스케일아웃 시 DB 락 또는 분산락 필요.
 */
@Slf4j
@Service
public class AiReportService {

    private final AiReportRepository reportRepository;
    private final AiQuotaService quotaService;
    private final AiReportDispatcher dispatcher;
    private final AiReportGenerator generator;
    private final AiReportResultWriter resultWriter;
    private final AlarmQueryService alarmQueryService;
    private final InspectionQueryService inspectionQueryService;
    private final EquipmentQueryService equipmentQueryService;
    private final UserQueryService userQueryService;
    private final Clock aiClock;
    private final TransactionTemplate tx;
    private final Object reserveLock = new Object();

    public AiReportService(AiReportRepository reportRepository, AiQuotaService quotaService,
                           AiReportDispatcher dispatcher, AiReportGenerator generator,
                           AiReportResultWriter resultWriter, AlarmQueryService alarmQueryService,
                           InspectionQueryService inspectionQueryService,
                           EquipmentQueryService equipmentQueryService, UserQueryService userQueryService,
                           Clock aiClock, PlatformTransactionManager txManager) {
        this.reportRepository = reportRepository;
        this.quotaService = quotaService;
        this.dispatcher = dispatcher;
        this.generator = generator;
        this.resultWriter = resultWriter;
        this.alarmQueryService = alarmQueryService;
        this.inspectionQueryService = inspectionQueryService;
        this.equipmentQueryService = equipmentQueryService;
        this.userQueryService = userQueryService;
        this.aiClock = aiClock;
        this.tx = new TransactionTemplate(txManager);
    }

    private record Reservation(Long reportId, Long logId) {
    }

    // ------------------------------------------------------------------ 생성 / 재시도 (비동기)

    /** POST /ai-reports — 202. 대상 검증 → 쿼터·중복 확인 → GENERATING 저장 → 커밋 후 비동기 생성. */
    public AiReportAcceptedResponse create(AiReportCreateRequest request, Long userId) {
        if (request.alarmId() == null && request.inspectionId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "alarmId 또는 inspectionId 중 하나는 필수입니다.");
        }
        AlarmSnapshot alarm = request.alarmId() == null ? null
                : alarmQueryService.findSnapshot(request.alarmId()).orElseThrow(() ->
                        new BusinessException(ErrorCode.NOT_FOUND, "알람을 찾을 수 없습니다: id=" + request.alarmId()));
        InspectionSummary inspection = request.inspectionId() == null ? null
                : inspectionQueryService.findSummary(request.inspectionId()).orElseThrow(() ->
                        new BusinessException(ErrorCode.NOT_FOUND,
                                "점검 이력을 찾을 수 없습니다: id=" + request.inspectionId()));
        if (alarm != null && inspection != null && !Objects.equals(alarm.equipmentId(), inspection.equipmentId())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "알람과 점검 이력이 서로 다른 설비의 것입니다.");
        }

        Long equipmentId = alarm != null ? alarm.equipmentId() : inspection.equipmentId();
        String equipmentCode = equipmentQueryService.findCodeById(equipmentId).orElseThrow(() ->
                new BusinessException(ErrorCode.NOT_FOUND, "설비를 찾을 수 없습니다: id=" + equipmentId));

        // 제목의 발생일시 기준: 알람 → (점검만 지정 시) 연계 알람 → 점검 시작 시각
        AlarmSnapshot anchorAlarm = alarm;
        if (anchorAlarm == null && inspection.alarmId() != null) {
            anchorAlarm = alarmQueryService.findSnapshot(inspection.alarmId()).orElse(null);
        }
        Instant occurredAt = anchorAlarm != null ? anchorAlarm.occurredAt() : inspection.startedAt();
        String title = ReportTimeFormat.title(equipmentCode, occurredAt);

        // 저장할 alarm_id: 요청값 우선, 점검만 지정했는데 그 점검에 (실재하는) 연계 알람이 있으면 그 알람 id도 함께 저장한다.
        // 알람 경로·점검 경로로 같은 사건의 리포트가 중복 생성/동시 생성되는 것을 ALREADY_GENERATING·?alarmId= 필터로 잡기 위함 (QA M-4).
        final Long storedAlarmId = request.alarmId() != null ? request.alarmId()
                : (anchorAlarm != null ? anchorAlarm.id() : null);

        Reservation reservation;
        synchronized (reserveLock) {
            reservation = tx.execute(status -> {
                assertNotGenerating(storedAlarmId, request.inspectionId(), -1L);
                AiReport report = reportRepository.save(AiReport.generating(
                        equipmentId, storedAlarmId, request.inspectionId(), title, userId));
                AiCallLog callLog = quotaService.reserve(report.getId(), userId);
                return new Reservation(report.getId(), callLog.getId());
            });
        }
        dispatch(reservation, userId);
        return AiReportAcceptedResponse.generating(reservation.reportId());
    }

    /** POST /ai-reports/{id}/retry — FAILED만. 같은 reportId를 재사용해 다시 GENERATING으로. */
    public AiReportAcceptedResponse retry(Long reportId, Long userId) {
        Reservation reservation;
        synchronized (reserveLock) {
            reservation = tx.execute(status -> {
                AiReport report = findForUpdate(reportId);
                if (report.getStatus() != AiReport.Status.FAILED) {
                    throw new BusinessException(ErrorCode.INVALID_REPORT_STATE,
                            "생성에 실패(FAILED)한 리포트만 재시도할 수 있습니다. 현재 상태: " + report.getStatus());
                }
                assertNotGenerating(report.getAlarmId(), report.getInspectionId(), report.getId());
                AiCallLog callLog = quotaService.reserve(report.getId(), userId);
                report.restartGeneration();
                return new Reservation(report.getId(), callLog.getId());
            });
        }
        dispatch(reservation, userId);
        return AiReportAcceptedResponse.generating(reservation.reportId());
    }

    /** 같은 알람/점검에 GENERATING 리포트가 이미 있으면 409 ALREADY_GENERATING */
    private void assertNotGenerating(Long alarmId, Long inspectionId, Long excludeId) {
        boolean alarmBusy = alarmId != null && reportRepository.existsByAlarmIdAndStatusAndIdNot(
                alarmId, AiReport.Status.GENERATING, excludeId);
        boolean inspectionBusy = inspectionId != null && reportRepository.existsByInspectionIdAndStatusAndIdNot(
                inspectionId, AiReport.Status.GENERATING, excludeId);
        if (alarmBusy || inspectionBusy) {
            throw new BusinessException(ErrorCode.ALREADY_GENERATING);
        }
    }

    /** 커밋 이후 호출한다. 대기열이 가득 찼으면 곧바로 FAILED로 마무리한다. */
    private void dispatch(Reservation reservation, Long userId) {
        try {
            dispatcher.submit(() -> generator.generate(reservation.reportId(), reservation.logId(), userId));
        } catch (TaskRejectedException e) {
            log.warn("AI 생성 대기열 포화: reportId={}", reservation.reportId());
            resultWriter.saveFailure(reservation.reportId(), reservation.logId(),
                    "AI 생성 대기열이 가득 찼습니다. 잠시 후 다시 시도해 주세요", null);
        }
    }

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public AiReportResponse get(Long id) {
        return toResponse(find(id));
    }

    /** GET /ai-reports — 최신순(createdAt desc) 고정. 클라이언트 sort 파라미터는 무시한다. */
    @Transactional(readOnly = true)
    public PageResponse<AiReportSummary> list(Long equipmentId, AiReport.Status status, Long alarmId,
                                              Long inspectionId, Pageable pageable) {
        Pageable fixed = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<AiReport> page = reportRepository.findAll(
                AiReportSpecifications.filter(equipmentId, status, alarmId, inspectionId), fixed);

        Map<Long, String> codes = equipmentQueryService.findCodesByIds(
                page.getContent().stream().map(AiReport::getEquipmentId).toList());
        Map<Long, String> names = equipmentQueryService.findNamesByIds(
                page.getContent().stream().map(AiReport::getEquipmentId).toList());
        Map<Long, String> users = userQueryService.findNamesByIds(
                page.getContent().stream().map(AiReport::getCreatedBy).filter(Objects::nonNull).toList());
        return PageResponse.of(page, r -> AiReportSummary.from(r,
                Lookup.get(codes, r.getEquipmentId()), Lookup.get(names, r.getEquipmentId()),
                Lookup.get(users, r.getCreatedBy())));
    }

    // ------------------------------------------------------------------ 편집 / 확정

    /**
     * PUT /ai-reports/{id} — DRAFT/FAILED에서만. draft_content(AI 원본)는 절대 수정하지 않는다.
     * 행 잠금으로 confirm/retry와 직렬화한다 — CONFIRMED 이후에는 상태 검증에서 409가 되어 final_content가 바뀌지 않는다 (M-3).
     */
    @Transactional
    public AiReportResponse updateFinal(Long id, AiReportUpdateRequest request) {
        AiReport report = findForUpdate(id);
        report.updateFinal(request.finalContent());
        return toResponse(report);
    }

    /** PATCH /ai-reports/{id}/confirm */
    @Transactional
    public AiReportResponse confirm(Long id, Long userId) {
        AiReport report = findForUpdate(id);
        report.confirm(userId, aiClock.instant());
        log.info("AI 리포트 확정: reportId={}, by={}", id, userId);
        return toResponse(report);
    }

    // ------------------------------------------------------------------ 내부

    private AiReport find(Long id) {
        return reportRepository.findById(id).orElseThrow(() ->
                new BusinessException(ErrorCode.NOT_FOUND, "AI 리포트를 찾을 수 없습니다: id=" + id));
    }

    /** 상태 검증~저장을 한 트랜잭션에서 행 잠금으로 직렬화해야 하는 경로(PUT/confirm/retry)용 조회 */
    private AiReport findForUpdate(Long id) {
        return reportRepository.findByIdForUpdate(id).orElseThrow(() ->
                new BusinessException(ErrorCode.NOT_FOUND, "AI 리포트를 찾을 수 없습니다: id=" + id));
    }

    private AiReportResponse toResponse(AiReport report) {
        return AiReportResponse.from(report,
                equipmentQueryService.findCodeById(report.getEquipmentId()).orElse(null),
                equipmentQueryService.findNameById(report.getEquipmentId()).orElse(null),
                nameOf(report.getCreatedBy()), nameOf(report.getConfirmedBy()));
    }

    private String nameOf(Long userId) {
        return userId == null ? null : userQueryService.findNameById(userId).orElse(null);
    }
}
