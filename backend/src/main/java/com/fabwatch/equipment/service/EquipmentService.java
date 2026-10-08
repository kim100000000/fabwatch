package com.fabwatch.equipment.service;

import com.fabwatch.auth.service.UserQueryService;
import com.fabwatch.common.dto.PageResponse;
import com.fabwatch.common.event.EquipmentStatusChangedEvent;
import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import com.fabwatch.common.util.LogSanitizer;
import com.fabwatch.common.util.Lookup;
import com.fabwatch.common.util.PageableUtil;
import com.fabwatch.common.util.TextUtil;
import com.fabwatch.equipment.dto.EquipmentCreateRequest;
import com.fabwatch.equipment.dto.EquipmentDetailResponse;
import com.fabwatch.equipment.dto.EquipmentStatusChangeRequest;
import com.fabwatch.equipment.dto.EquipmentStatusLogResponse;
import com.fabwatch.equipment.dto.EquipmentSummaryResponse;
import com.fabwatch.equipment.dto.EquipmentUpdateRequest;
import com.fabwatch.equipment.dto.LineTreeResponse;
import com.fabwatch.equipment.entity.Equipment;
import com.fabwatch.equipment.entity.EquipmentStatus;
import com.fabwatch.equipment.entity.EquipmentStatusLog;
import com.fabwatch.equipment.entity.Line;
import com.fabwatch.equipment.entity.Process;
import com.fabwatch.equipment.repository.EquipmentRepository;
import com.fabwatch.equipment.repository.EquipmentSpecifications;
import com.fabwatch.equipment.repository.EquipmentStatusLogRepository;
import com.fabwatch.equipment.repository.LineRepository;
import com.fabwatch.equipment.repository.ProcessRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 설비 마스터 서비스 (docs/03 F-2, docs/06 §2).
 * 상태 전환은 changeStatus() 한 곳에서만 수행하고 반드시 status log를 남긴다 — KPI 계산 원천이기 때문.
 * 사용자(담당 엔지니어) 정보는 auth 도메인의 UserQueryService 인터페이스로만 조회한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EquipmentService {

    /** equipment_status_logs.reason 컬럼 길이 (varchar 200) */
    private static final int STATUS_REASON_MAX_LENGTH = 200;
    /** GET /equipments 정렬 허용 속성 (그 밖의 sort는 400) */
    private static final Set<String> EQUIPMENT_SORTABLE = Set.of("code", "name", "status", "modelName", "maker", "installedAt");
    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_ENGINEER = "ENGINEER";

    private final EquipmentRepository equipmentRepository;
    private final ProcessRepository processRepository;
    private final LineRepository lineRepository;
    private final EquipmentStatusLogRepository statusLogRepository;
    private final UserQueryService userQueryService;
    private final ApplicationEventPublisher eventPublisher;

    /** GET /lines — 라인 > 공정 > 설비 트리 전체 */
    @Transactional(readOnly = true)
    public PageResponse<LineTreeResponse> getLineTree() {
        List<Line> lines = lineRepository.findAllByOrderByIdAsc();

        List<Long> managerIds = lines.stream()
                .flatMap(line -> line.getProcesses().stream())
                .flatMap(process -> process.getEquipments().stream())
                .map(Equipment::getManagerId)
                .filter(Objects::nonNull)
                .toList();
        Map<Long, String> managerNames = userQueryService.findNamesByIds(managerIds);

        List<LineTreeResponse> tree = lines.stream()
                .map(line -> new LineTreeResponse(
                        line.getId(),
                        line.getName(),
                        line.getProcesses().stream()
                                .sorted(Comparator.comparing(Process::getSeq, Comparator.nullsLast(Integer::compareTo)))
                                .map(process -> new LineTreeResponse.ProcessTreeResponse(
                                        process.getId(),
                                        process.getName(),
                                        process.getSeq(),
                                        process.getEquipments().stream()
                                                .map(e -> EquipmentSummaryResponse.from(e, Lookup.get(managerNames, e.getManagerId())))
                                                .toList()))
                                .toList()))
                .toList();
        return PageResponse.ofAll(tree);
    }

    /** GET /equipments — 목록 (filter: processId, status) */
    @Transactional(readOnly = true)
    public PageResponse<EquipmentSummaryResponse> getEquipments(Long processId, EquipmentStatus status, Pageable pageable) {
        Page<Equipment> page = equipmentRepository.findAll(EquipmentSpecifications.filter(processId, status),
                PageableUtil.allowSort(pageable, EQUIPMENT_SORTABLE));
        Map<Long, String> managerNames = userQueryService.findNamesByIds(
                page.getContent().stream().map(Equipment::getManagerId).filter(Objects::nonNull).toList());
        return PageResponse.of(page, e -> EquipmentSummaryResponse.from(e, Lookup.get(managerNames, e.getManagerId())));
    }

    /** GET /equipments/{id} — 상세 (센서·PM스케줄·미해결알람수는 다음 라운드) */
    @Transactional(readOnly = true)
    public EquipmentDetailResponse getEquipment(Long id) {
        Equipment equipment = findEquipment(id);
        return EquipmentDetailResponse.from(equipment, managerName(equipment.getManagerId()));
    }

    /** POST /equipments — 설비 등록 (ADMIN) */
    @Transactional
    public EquipmentDetailResponse create(EquipmentCreateRequest request) {
        if (equipmentRepository.existsByCode(request.code())) {
            throw new BusinessException(ErrorCode.DUPLICATE_EQUIPMENT_CODE);
        }
        Process process = findProcess(request.processId());
        validateManager(request.managerId());

        Equipment equipment = Equipment.builder()
                .code(request.code())
                .name(request.name())
                .modelName(request.modelName())
                .maker(request.maker())
                .installedAt(request.installedAt())
                .status(request.status())
                .managerId(request.managerId())
                .note(request.note())
                .build();
        equipment.moveToProcess(process);
        Equipment saved = equipmentRepository.save(equipment);

        // 최초 상태도 이력에 남긴다 (from=null) — KPI 계산의 시작점
        statusLogRepository.save(new EquipmentStatusLog(
                saved, null, saved.getStatus(), "설비 등록", currentActorOrNull(), Instant.now()));

        log.info("설비 등록: code={}, status={}", saved.getCode(), saved.getStatus());
        return EquipmentDetailResponse.from(saved, managerName(saved.getManagerId()));
    }

    /** PUT /equipments/{id} — 수정 (ADMIN). code/status는 변경 불가. */
    @Transactional
    public EquipmentDetailResponse update(Long id, EquipmentUpdateRequest request) {
        Equipment equipment = findEquipment(id);
        validateManager(request.managerId());
        equipment.updateBasicInfo(request.name(), request.modelName(), request.maker(),
                request.installedAt(), request.managerId(), request.note());
        if (!equipment.getProcess().getId().equals(request.processId())) {
            equipment.moveToProcess(findProcess(request.processId()));
        }
        return EquipmentDetailResponse.from(equipment, managerName(equipment.getManagerId()));
    }

    /**
     * PATCH /equipments/{id}/status — 상태 전환 (docs/03 F-2, docs/06 §2).
     * 판정 순서: 404(설비 없음) → 400 INVALID_STATUS_TRANSITION(허용표 밖) → 403 FORBIDDEN(전이별 역할)
     * → 400 VALIDATION_ERROR(사유 필수). 전이별 권한·사유 판정은 validateManualChange() 한 곳에서만 한다.
     * ※ DB 필드 변경일 뿐 실제 설비를 정지시키는 물리 제어가 아니다 (docs/11 §10).
     *
     * @param actorRole 호출자 역할명(ADMIN/ENGINEER/TECHNICIAN) — JWT 클레임 기준
     */
    @Transactional
    public EquipmentDetailResponse changeStatus(Long id, EquipmentStatusChangeRequest request,
                                                Long actorUserId, String actorRole) {
        // 행 락을 잡은 뒤의 최신 상태로 전이를 판정한다 — 동시 자동 DOWN과 엇갈려 로그가 어긋나는 것을 막는다 (M-1)
        Equipment equipment = findEquipmentForUpdate(id);
        EquipmentStatus to = request.toStatus();
        validateManualChange(equipment, to, request.reason(), actorRole);

        EquipmentStatus from = equipment.changeStatus(to);
        Instant changedAt = Instant.now();
        statusLogRepository.save(new EquipmentStatusLog(
                equipment, from, to, request.reason(), actorUserId, changedAt));
        log.info("설비 상태 전환: code={}, {} → {}, 사유={}", equipment.getCode(), from, to, LogSanitizer.clean(request.reason()));
        publishStatusChanged(equipment, from, request.reason(), actorUserId, changedAt);
        return EquipmentDetailResponse.from(equipment, managerName(equipment.getManagerId()));
    }

    /**
     * 수동 전환의 전이별 규칙 판정 (한 곳).
     * - 허용표 밖 → 400 INVALID_STATUS_TRANSITION (Equipment.validateTransition)
     * - 기본 ENGINEER 이상(ADMIN 포함). 예외: DOWN → IDLE 은 TECHNICIAN 포함 전 역할 → 그 외 403 FORBIDDEN
     * - DOWN → IDLE / DOWN → RUN 은 reason 공백·null 불가 → 400 VALIDATION_ERROR
     * 자동 DOWN(autoDown)은 이 검사를 거치지 않는다(시스템 전환, 역할 없음).
     */
    private void validateManualChange(Equipment equipment, EquipmentStatus to, String reason, String actorRole) {
        equipment.validateTransition(to);
        EquipmentStatus from = equipment.getStatus();

        boolean allRolesAllowed = from == EquipmentStatus.DOWN && to == EquipmentStatus.IDLE;
        boolean engineerOrAbove = ROLE_ADMIN.equals(actorRole) || ROLE_ENGINEER.equals(actorRole);
        if (!allRolesAllowed && !engineerOrAbove) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "해당 상태 전환 권한이 없습니다: " + from + " → " + to + " (ENGINEER 이상 필요)");
        }

        boolean reasonRequired = from == EquipmentStatus.DOWN
                && (to == EquipmentStatus.IDLE || to == EquipmentStatus.RUN);
        if (reasonRequired && (reason == null || reason.isBlank())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "DOWN에서 복귀(" + from + " → " + to + ")할 때는 사유(reason)가 필수입니다.");
        }
    }

    /**
     * CRITICAL 알람에 의한 설비 자동 DOWN (docs/03 F-4.3).
     * alarm 도메인이 EquipmentDownRequestedEvent를 발행하면 EquipmentAutoDownListener가 이 메서드를 호출한다.
     * alarm은 Equipment 엔티티/레포지토리를 절대 직접 만지지 않는다.
     *
     * ★ 안전 게이트(docs/11 §10, docs/03 F-2 범위 한정): 여기서 하는 일은 DB `equipments.status` 필드값
     *   변경 + 상태 로그 기록뿐이다. PLC/Modbus 출력 등 실제 설비를 정지시키는 물리적 제어가 아니며,
     *   그런 인터락으로 확장하려면 기능안전 인증(IEC 61508 등) 검토가 선행되어야 한다.
     *
     * DOWN으로 전이할 수 없는 상태(이미 DOWN, PM 중)면 조용히 건너뛴다 — 상태 머신 규칙(docs/03 F-2)을
     * 자동 처리라고 해서 우회하지 않는다.
     */
    @Transactional
    public boolean autoDown(Long equipmentId, String reason, Instant occurredAt) {
        // 락 이후의 최신 상태로 판정 — 틱이 오래된 스냅샷으로 RUN→DOWN을 판단하는 사이 사용자가 바꿔도 안전하다 (M-1)
        Equipment equipment = equipmentRepository.findByIdForUpdate(equipmentId).orElse(null);
        if (equipment == null) {
            log.warn("자동 DOWN 대상 설비 없음: id={}", equipmentId);
            return false;
        }
        EquipmentStatus from = equipment.getStatus();
        if (!from.canTransitionTo(EquipmentStatus.DOWN)) {
            log.info("자동 DOWN 생략: code={}, 현재 상태={} (허용 전이 아님)", equipment.getCode(), from);
            return false;
        }
        // 알람 메시지(최대 300자)가 붙은 사유는 status_log.reason(varchar 200)을 넘을 수 있다.
        // 그대로 저장하면 INSERT 실패 → 알람 생성 트랜잭션 전체가 롤백되므로 저장 직전에 자른다.
        // 알람 발생 시각은 사유 끝에 붙인다(잘려도 시각은 남도록 본문을 먼저 자른다). changedAt은 전환이 일어난 지금이다 —
        // 알람 발생 시각을 쓰면 락 대기 중 먼저 커밋된 수동 전환 로그보다 시각이 앞서 KPI 순서가 뒤집힌다.
        String occurredSuffix = occurredAt == null ? "" : " [알람 발생 " + occurredAt + "]";
        String baseReason = reason == null ? "" : reason;
        reason = TextUtil.truncate(baseReason, Math.max(0, STATUS_REASON_MAX_LENGTH - occurredSuffix.length())) + occurredSuffix;
        equipment.changeStatus(EquipmentStatus.DOWN);
        Instant changedAt = Instant.now();
        // changedBy = null → 시스템 자동 전환 (사람이 누른 것이 아님을 이력에 남긴다)
        statusLogRepository.save(new EquipmentStatusLog(equipment, from, EquipmentStatus.DOWN, reason, null, changedAt));
        log.warn("CRITICAL 알람 자동 DOWN: code={}, {} → DOWN, 사유={}", equipment.getCode(), from, LogSanitizer.clean(reason));
        publishStatusChanged(equipment, from, reason, null, changedAt);
        return true;
    }

    private void publishStatusChanged(Equipment equipment, EquipmentStatus from, String reason,
                                      Long actorUserId, Instant changedAt) {
        eventPublisher.publishEvent(new EquipmentStatusChangedEvent(
                equipment.getId(), equipment.getCode(),
                from == null ? null : from.name(), equipment.getStatus().name(),
                reason, actorUserId, changedAt));
    }

    /** GET /equipments/{id}/status-logs — 상태 변경 이력 (최신순) */
    @Transactional(readOnly = true)
    public PageResponse<EquipmentStatusLogResponse> getStatusLogs(Long id, Pageable pageable) {
        findEquipment(id); // 존재하지 않는 설비면 404
        // 정렬은 최신순 고정 — 클라이언트 sort는 무시(?sort=foo가 500이 되지 않게)
        Page<EquipmentStatusLog> page = statusLogRepository.findByEquipmentIdOrderByChangedAtDesc(id, PageableUtil.ignoreSort(pageable));
        Map<Long, String> names = userQueryService.findNamesByIds(
                page.getContent().stream().map(EquipmentStatusLog::getChangedBy).filter(Objects::nonNull).toList());
        // 자동 DOWN(시스템 전환)은 changed_by가 null이다 — 불변 맵의 get(null) NPE 방지
        return PageResponse.of(page, statusLog ->
                EquipmentStatusLogResponse.from(statusLog, Lookup.get(names, statusLog.getChangedBy())));
    }

    private Equipment findEquipment(Long id) {
        return equipmentRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "설비를 찾을 수 없습니다: id=" + id));
    }

    /** 상태 전환 전용 — 행 락 조회. 반드시 @Transactional 안에서 호출한다. */
    private Equipment findEquipmentForUpdate(Long id) {
        return equipmentRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "설비를 찾을 수 없습니다: id=" + id));
    }

    private Process findProcess(Long processId) {
        return processRepository.findById(processId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "공정을 찾을 수 없습니다: id=" + processId));
    }

    /** 담당 엔지니어 지정 시 실제 존재하는 사용자여야 한다 */
    private void validateManager(Long managerId) {
        if (managerId != null && !userQueryService.existsById(managerId)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "담당자를 찾을 수 없습니다: managerId=" + managerId);
        }
    }

    private String managerName(Long managerId) {
        return managerId == null ? null : userQueryService.findNameById(managerId).orElse(null);
    }

    private Long currentActorOrNull() {
        return com.fabwatch.common.security.SecurityUtils.currentPrincipal()
                .map(com.fabwatch.common.security.AuthPrincipal::userId)
                .orElse(null);
    }
}
