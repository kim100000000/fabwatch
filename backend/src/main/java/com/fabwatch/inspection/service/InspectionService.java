package com.fabwatch.inspection.service;

import com.fabwatch.alarm.service.AlarmCommandService;
import com.fabwatch.auth.service.UserQueryService;
import com.fabwatch.common.dto.PageResponse;
import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import com.fabwatch.common.util.Lookup;
import com.fabwatch.common.util.ShiftUtil;
import com.fabwatch.equipment.service.EquipmentQueryService;
import com.fabwatch.inspection.dto.CheckResultRequest;
import com.fabwatch.inspection.dto.CheckResultResponse;
import com.fabwatch.inspection.dto.InspectionCreateRequest;
import com.fabwatch.inspection.dto.InspectionDetailResponse;
import com.fabwatch.inspection.dto.InspectionResponse;
import com.fabwatch.inspection.dto.InspectionUpdateRequest;
import com.fabwatch.inspection.entity.ChecklistItem;
import com.fabwatch.inspection.entity.Inspection;
import com.fabwatch.inspection.entity.InspectionCheckResult;
import com.fabwatch.inspection.repository.ChecklistItemRepository;
import com.fabwatch.inspection.repository.InspectionCheckResultRepository;
import com.fabwatch.inspection.repository.InspectionRepository;
import com.fabwatch.inspection.repository.InspectionSpecifications;
import com.fabwatch.inspection.repository.PmScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 점검 이력 서비스 (docs/03 F-3.1~3.4, docs/06 §4).
 *
 * 도메인 경계:
 * - 설비 존재/코드/이름 → equipment의 EquipmentQueryService (Equipment 엔티티 직접 참조 없음)
 * - 작업자 이름 → auth의 UserQueryService
 * - BM 연계 알람 해제 → alarm의 AlarmCommandService (OPEN이면 ACK 후 RESOLVE)
 * - PM 수행 시 미해결 PM_OVERDUE 알람 자동 해소 → AlarmCommandService.resolvePmOverdueByEquipment
 * - 설비 상태는 절대 바꾸지 않는다 — BM 등록 후 DOWN→IDLE 전환은 사용자가 확인 후 PATCH status로 직접 수행(docs/03 F-3.1)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InspectionService {

    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_ENGINEER = "ENGINEER";
    /** 클라이언트-서버 시계 오차 허용치 — 이 범위까지는 "현재"로 본다 */
    private static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(1);

    private final InspectionRepository inspectionRepository;
    private final InspectionCheckResultRepository checkResultRepository;
    private final ChecklistItemRepository checklistItemRepository;
    private final PmScheduleRepository pmScheduleRepository;
    private final EquipmentQueryService equipmentQueryService;
    private final UserQueryService userQueryService;
    private final AlarmCommandService alarmCommandService;

    // ------------------------------------------------------------------ 조회

    /** GET /inspections — 최신순(startedAt desc) 고정. 클라이언트 sort 파라미터는 무시한다. */
    @Transactional(readOnly = true)
    public PageResponse<InspectionResponse> getInspections(Long equipmentId, Inspection.Type type,
                                                           Inspection.Shift shift, Long workerId, Boolean hasNg,
                                                           Instant from, Instant to, Pageable pageable) {
        Pageable fixed = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id")));
        Page<Inspection> page = inspectionRepository.findAll(
                InspectionSpecifications.filter(equipmentId, type, shift, workerId, hasNg, from, to), fixed);

        Map<Long, InspectionResponse> responses = toResponses(page.getContent());
        return PageResponse.of(page, inspection -> responses.get(inspection.getId()));
    }

    @Transactional(readOnly = true)
    public InspectionDetailResponse getInspection(Long id) {
        return toDetail(findInspection(id));
    }

    // ------------------------------------------------------------------ 등록

    /** POST /inspections */
    @Transactional
    public InspectionDetailResponse create(InspectionCreateRequest request, Long workerId) {
        Instant now = Instant.now();
        validateTimes(request.startedAt(), request.endedAt(), now);

        if (!equipmentQueryService.existsById(request.equipmentId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "설비를 찾을 수 없습니다: id=" + request.equipmentId());
        }
        boolean bm = request.type() == Inspection.Type.BM;
        validateCause(request.type(), request.cause4m());

        Long alarmId = request.alarmId();
        if (alarmId != null) {
            if (!bm) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "알람 연계(alarmId)는 BM 점검에서만 가능합니다.");
            }
            alarmCommandService.assertBelongsToEquipment(alarmId, request.equipmentId());
        }
        boolean hasNg = validateCheckResults(request.equipmentId(), request.type(), request.checkResults());

        Inspection inspection = inspectionRepository.save(Inspection.builder()
                .equipmentId(request.equipmentId())
                .type(request.type())
                .shift(request.shift() != null ? request.shift() : shiftOf(request.startedAt()))
                .workerId(workerId)
                .startedAt(request.startedAt())
                .endedAt(request.endedAt())
                .durationMin(durationMin(request.startedAt(), request.endedAt()))
                .content(request.content())
                .actionTaken(request.actionTaken())
                .cause4m(bm ? request.cause4m() : null)
                .causeDetail(bm ? request.causeDetail() : null)
                .alarmId(alarmId)
                .hasNg(hasNg)
                .build());
        saveCheckResults(inspection.getId(), request.checkResults());

        if (inspection.getType() == Inspection.Type.PM && refreshPmSchedule(inspection)) {
            // PM을 실제로 수행해 스케줄이 갱신된 경우에만 — 미해결 PM 지연 알람을 같은 트랜잭션에서 자동 해소 (docs/03 F-3.3)
            alarmCommandService.resolvePmOverdueByEquipment(inspection.getEquipmentId(), workerId,
                    "PM 점검 이력 #%d로 수행 완료".formatted(inspection.getId()));
        }
        if (alarmId != null) {
            // 알람 흐름 OPEN→ACK→RESOLVED 유지: 작성자 명의로 자동 ACK 후 RESOLVE (이미 RESOLVED면 그대로)
            alarmCommandService.ackAndResolve(alarmId, workerId, "BM 점검 이력 #%d로 조치 완료".formatted(inspection.getId()));
        }
        log.info("점검 이력 등록: id={}, equipmentId={}, type={}, worker={}, hasNg={}",
                inspection.getId(), inspection.getEquipmentId(), inspection.getType(), workerId, hasNg);
        return toDetail(inspection);
    }

    // ------------------------------------------------------------------ 수정

    /** PUT /inspections/{id} — 작성자 본인 또는 ENGINEER+ (404 → 403 → 400 순서로 판정) */
    @Transactional
    public InspectionDetailResponse update(Long id, InspectionUpdateRequest request, Long actorId, String actorRole) {
        Inspection inspection = findInspection(id);
        if (!canEdit(inspection, actorId, actorRole)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "작성자 본인 또는 엔지니어 이상만 수정할 수 있습니다.");
        }
        validateTimes(request.startedAt(), request.endedAt(), Instant.now());
        validateCause(inspection.getType(), request.cause4m());
        boolean bm = inspection.getType() == Inspection.Type.BM;

        Inspection.Shift shift = request.shift();
        if (shift == null) {
            shift = request.startedAt().equals(inspection.getStartedAt())
                    ? inspection.getShift() : shiftOf(request.startedAt());
        }

        Boolean newHasNg = null;
        if (request.checkResults() != null) {
            newHasNg = validateCheckResults(inspection.getEquipmentId(), inspection.getType(), request.checkResults());
        }

        inspection.update(shift, request.startedAt(), request.endedAt(),
                durationMin(request.startedAt(), request.endedAt()), request.content(), request.actionTaken(),
                bm ? request.cause4m() : null, bm ? request.causeDetail() : null);

        if (request.checkResults() != null) {
            // 이력 보존 원칙 — 기존 결과는 soft delete 후 새로 저장
            checkResultRepository.findByInspectionIdOrderByIdAsc(id).forEach(InspectionCheckResult::markDeleted);
            checkResultRepository.flush();
            saveCheckResults(id, request.checkResults());
            inspection.changeHasNg(newHasNg);
        }
        log.info("점검 이력 수정: id={}, by={}", id, actorId);
        return toDetail(inspection);
    }

    // ------------------------------------------------------------------ 승인

    /** PATCH /inspections/{id}/review — 이미 승인된 건은 그대로 반환(멱등). 권한(ENGINEER+)은 컨트롤러에서 판정. */
    @Transactional
    public InspectionDetailResponse review(Long id, Long reviewerId) {
        Inspection inspection = findInspection(id);
        if (!inspection.isReviewed()) {
            inspection.review(reviewerId, Instant.now());
            log.info("점검 이력 승인: id={}, by={}", id, reviewerId);
        }
        return toDetail(inspection);
    }

    // ------------------------------------------------------------------ 검증/보조

    private static void validateTimes(Instant startedAt, Instant endedAt, Instant now) {
        if (!endedAt.isAfter(startedAt)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "종료 일시는 시작 일시보다 늦어야 합니다.");
        }
        if (endedAt.isAfter(now.plus(FUTURE_TOLERANCE))) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "미래 시각은 입력할 수 없습니다.");
        }
    }

    /** BM은 4M 원인 분류 필수 (docs/03 F-3.1) */
    private static void validateCause(Inspection.Type type, Inspection.Cause4M cause4m) {
        if (type == Inspection.Type.BM && cause4m == null) {
            throw new BusinessException(ErrorCode.CAUSE_4M_REQUIRED);
        }
    }

    /**
     * 체크리스트 결과 검증 → NG 존재 여부 반환.
     * PM에서만 허용, 항목 중복 불가, 항목은 해당 설비의 템플릿이어야 한다.
     */
    private boolean validateCheckResults(Long equipmentId, Inspection.Type type, List<CheckResultRequest> results) {
        if (results == null || results.isEmpty()) {
            return false;
        }
        if (type != Inspection.Type.PM) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "체크리스트 결과는 PM 점검에서만 입력할 수 있습니다.");
        }
        Set<Long> ids = new HashSet<>();
        for (CheckResultRequest row : results) {
            if (!ids.add(row.checklistItemId())) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "체크리스트 항목이 중복되었습니다: checklistItemId=" + row.checklistItemId());
            }
        }
        long found = checklistItemRepository.findByEquipmentIdAndIdIn(equipmentId, ids).size();
        if (found != ids.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "해당 설비의 체크리스트 항목이 아닌 값이 포함되어 있습니다.");
        }
        return results.stream().anyMatch(row -> row.result() == InspectionCheckResult.Result.NG);
    }

    private void saveCheckResults(Long inspectionId, List<CheckResultRequest> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        checkResultRepository.saveAll(results.stream()
                .map(row -> InspectionCheckResult.builder()
                        .inspectionId(inspectionId)
                        .checklistItemId(row.checklistItemId())
                        .result(row.result())
                        .note(row.note())
                        .build())
                .toList());
    }

    /**
     * PM 수행 완료 → 스케줄 갱신 (docs/12 PM-7): last_done_at=종료 시각, next_due_at 재계산, 지연 알람 플래그 초기화.
     * 과거 날짜 소급 등록(기존 last_done 이전)은 스케줄을 되돌리지 않는다. 스케줄이 없는 설비는 건너뛴다.
     *
     * @return 스케줄을 실제로 갱신했으면 true (소급 등록·스케줄 없음이면 false)
     */
    private boolean refreshPmSchedule(Inspection inspection) {
        return pmScheduleRepository.findByEquipmentId(inspection.getEquipmentId()).map(schedule -> {
            Instant doneAt = inspection.getEndedAt();
            if (schedule.getLastDoneAt() != null && !doneAt.isAfter(schedule.getLastDoneAt())) {
                return false;
            }
            schedule.markDone(doneAt,
                    PmScheduleCalculator.nextDueAt(schedule.getCycleType(), schedule.getCycleValue(), doneAt));
            return true;
        }).orElse(false);
    }

    private static boolean canEdit(Inspection inspection, Long actorId, String actorRole) {
        return Objects.equals(inspection.getWorkerId(), actorId)
                || ROLE_ADMIN.equals(actorRole) || ROLE_ENGINEER.equals(actorRole);
    }

    private static Inspection.Shift shiftOf(Instant at) {
        return Inspection.Shift.valueOf(ShiftUtil.shiftCode(at));
    }

    private static int durationMin(Instant startedAt, Instant endedAt) {
        return (int) Duration.between(startedAt, endedAt).toMinutes();
    }

    private Inspection findInspection(Long id) {
        return inspectionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "점검 이력을 찾을 수 없습니다: id=" + id));
    }

    // ------------------------------------------------------------------ 응답 조립

    /** 목록 N+1 방지 — 설비·사용자 이름을 한 번에 조회해 채운다. key = inspectionId */
    private Map<Long, InspectionResponse> toResponses(List<Inspection> inspections) {
        List<Long> equipmentIds = inspections.stream().map(Inspection::getEquipmentId).toList();
        Map<Long, String> codes = equipmentQueryService.findCodesByIds(equipmentIds);
        Map<Long, String> names = equipmentQueryService.findNamesByIds(equipmentIds);
        Map<Long, String> userNames = userQueryService.findNamesByIds(inspections.stream()
                .flatMap(i -> Stream.of(i.getWorkerId(), i.getReviewedBy()))
                .filter(Objects::nonNull)
                .toList());
        return inspections.stream().collect(Collectors.toMap(Inspection::getId, i -> InspectionResponse.from(i,
                Lookup.get(codes, i.getEquipmentId()), Lookup.get(names, i.getEquipmentId()),
                Lookup.get(userNames, i.getWorkerId()), Lookup.get(userNames, i.getReviewedBy()))));
    }

    private InspectionDetailResponse toDetail(Inspection inspection) {
        InspectionResponse base = toResponses(List.of(inspection)).get(inspection.getId());
        List<InspectionCheckResult> rows = checkResultRepository.findByInspectionIdOrderByIdAsc(inspection.getId());
        Map<Long, ChecklistItem> items = checklistItemRepository
                .findAllById(rows.stream().map(InspectionCheckResult::getChecklistItemId).toList()).stream()
                .collect(Collectors.toMap(ChecklistItem::getId, Function.identity()));
        List<CheckResultResponse> results = rows.stream().map(row -> {
            ChecklistItem item = items.get(row.getChecklistItemId());
            return CheckResultResponse.of(row, item == null ? null : item.getItemName(),
                    item == null ? null : item.getCriteria());
        }).toList();
        return InspectionDetailResponse.from(base, results);
    }
}
