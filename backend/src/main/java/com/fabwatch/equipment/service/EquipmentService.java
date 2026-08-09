package com.fabwatch.equipment.service;

import com.fabwatch.auth.service.UserQueryService;
import com.fabwatch.common.dto.PageResponse;
import com.fabwatch.common.event.EquipmentStatusChangedEvent;
import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import com.fabwatch.common.util.Lookup;
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

/**
 * 설비 마스터 서비스 (docs/03 F-2, docs/06 §2).
 * 상태 전환은 changeStatus() 한 곳에서만 수행하고 반드시 status log를 남긴다 — KPI 계산 원천이기 때문.
 * 사용자(담당 엔지니어) 정보는 auth 도메인의 UserQueryService 인터페이스로만 조회한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EquipmentService {

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
        Page<Equipment> page = equipmentRepository.findAll(EquipmentSpecifications.filter(processId, status), pageable);
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
     * PATCH /equipments/{id}/status — 상태 전환 (ADMIN/ENGINEER).
     * 허용되지 않는 전이는 400 INVALID_STATUS_TRANSITION (docs/03 F-2).
     * ※ DB 필드 변경일 뿐 실제 설비를 정지시키는 물리 제어가 아니다 (docs/11 §10).
     */
    @Transactional
    public EquipmentDetailResponse changeStatus(Long id, EquipmentStatusChangeRequest request, Long actorUserId) {
        Equipment equipment = findEquipment(id);
        EquipmentStatus from = equipment.changeStatus(request.toStatus());
        Instant changedAt = Instant.now();
        statusLogRepository.save(new EquipmentStatusLog(
                equipment, from, request.toStatus(), request.reason(), actorUserId, changedAt));
        log.info("설비 상태 전환: code={}, {} → {}, 사유={}", equipment.getCode(), from, request.toStatus(), request.reason());
        publishStatusChanged(equipment, from, request.reason(), actorUserId, changedAt);
        return EquipmentDetailResponse.from(equipment, managerName(equipment.getManagerId()));
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
        Equipment equipment = equipmentRepository.findById(equipmentId).orElse(null);
        if (equipment == null) {
            log.warn("자동 DOWN 대상 설비 없음: id={}", equipmentId);
            return false;
        }
        EquipmentStatus from = equipment.getStatus();
        if (!from.canTransitionTo(EquipmentStatus.DOWN)) {
            log.info("자동 DOWN 생략: code={}, 현재 상태={} (허용 전이 아님)", equipment.getCode(), from);
            return false;
        }
        equipment.changeStatus(EquipmentStatus.DOWN);
        Instant changedAt = occurredAt != null ? occurredAt : Instant.now();
        // changedBy = null → 시스템 자동 전환 (사람이 누른 것이 아님을 이력에 남긴다)
        statusLogRepository.save(new EquipmentStatusLog(equipment, from, EquipmentStatus.DOWN, reason, null, changedAt));
        log.warn("CRITICAL 알람 자동 DOWN: code={}, {} → DOWN, 사유={}", equipment.getCode(), from, reason);
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
        Page<EquipmentStatusLog> page = statusLogRepository.findByEquipmentIdOrderByChangedAtDesc(id, pageable);
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
