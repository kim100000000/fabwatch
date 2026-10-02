package com.fabwatch.inspection.service;

import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import com.fabwatch.equipment.service.EquipmentQueryService;
import com.fabwatch.inspection.dto.ChecklistItemCreateRequest;
import com.fabwatch.inspection.dto.ChecklistItemResponse;
import com.fabwatch.inspection.dto.ChecklistItemUpdateRequest;
import com.fabwatch.inspection.entity.ChecklistItem;
import com.fabwatch.inspection.repository.ChecklistItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * PM 체크리스트 템플릿 관리 (docs/03 F-3.2, docs/06 §4).
 * 삭제는 active=false 로만 한다 — 과거 점검 결과가 항목을 참조하므로 물리 삭제는 금지(soft delete 원칙).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChecklistService {

    private final ChecklistItemRepository checklistItemRepository;
    private final EquipmentQueryService equipmentQueryService;

    /** 활성 항목만, seq 순 */
    @Transactional(readOnly = true)
    public List<ChecklistItemResponse> getChecklist(Long equipmentId) {
        requireEquipment(equipmentId);
        return checklistItemRepository.findByEquipmentIdAndActiveTrueOrderBySeqAscIdAsc(equipmentId).stream()
                .map(ChecklistItemResponse::from)
                .toList();
    }

    @Transactional
    public ChecklistItemResponse create(Long equipmentId, ChecklistItemCreateRequest request) {
        requireEquipment(equipmentId);
        int seq = request.seq() != null ? request.seq()
                : checklistItemRepository.findFirstByEquipmentIdAndSeqIsNotNullOrderBySeqDesc(equipmentId)
                        .map(last -> last.getSeq() + 1).orElse(1);
        ChecklistItem saved = checklistItemRepository.save(ChecklistItem.builder()
                .equipmentId(equipmentId)
                .itemName(request.itemName().trim())
                .criteria(request.criteria())
                .seq(seq)
                .active(true)
                .build());
        log.info("체크리스트 항목 추가: id={}, equipmentId={}", saved.getId(), equipmentId);
        return ChecklistItemResponse.from(saved);
    }

    @Transactional
    public ChecklistItemResponse update(Long equipmentId, Long itemId, ChecklistItemUpdateRequest request) {
        requireEquipment(equipmentId);
        ChecklistItem item = checklistItemRepository.findByIdAndEquipmentId(itemId, equipmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "체크리스트 항목을 찾을 수 없습니다: id=" + itemId));
        item.update(request.itemName().trim(), request.criteria(), request.seq(), request.active());
        log.info("체크리스트 항목 수정: id={}, equipmentId={}, active={}", itemId, equipmentId, request.active());
        return ChecklistItemResponse.from(item);
    }

    private void requireEquipment(Long equipmentId) {
        if (!equipmentQueryService.existsById(equipmentId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "설비를 찾을 수 없습니다: id=" + equipmentId);
        }
    }
}
