package com.fabwatch.equipment.service;

import com.fabwatch.auth.service.UserQueryService;
import com.fabwatch.common.dto.PageResponse;
import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
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
                                                .map(e -> EquipmentSummaryResponse.from(e, managerNames.get(e.getManagerId())))
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
        return PageResponse.of(page, e -> EquipmentSummaryResponse.from(e, managerNames.get(e.getManagerId())));
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
        statusLogRepository.save(new EquipmentStatusLog(
                equipment, from, request.toStatus(), request.reason(), actorUserId, Instant.now()));
        log.info("설비 상태 전환: code={}, {} → {}, 사유={}", equipment.getCode(), from, request.toStatus(), request.reason());
        return EquipmentDetailResponse.from(equipment, managerName(equipment.getManagerId()));
    }

    /** GET /equipments/{id}/status-logs — 상태 변경 이력 (최신순) */
    @Transactional(readOnly = true)
    public PageResponse<EquipmentStatusLogResponse> getStatusLogs(Long id, Pageable pageable) {
        findEquipment(id); // 존재하지 않는 설비면 404
        Page<EquipmentStatusLog> page = statusLogRepository.findByEquipmentIdOrderByChangedAtDesc(id, pageable);
        Map<Long, String> names = userQueryService.findNamesByIds(
                page.getContent().stream().map(EquipmentStatusLog::getChangedBy).filter(Objects::nonNull).toList());
        return PageResponse.of(page, statusLog ->
                EquipmentStatusLogResponse.from(statusLog, names.get(statusLog.getChangedBy())));
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
