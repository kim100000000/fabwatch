package com.fabwatch.inspection.service;

import com.fabwatch.inspection.entity.ChecklistItem;
import com.fabwatch.inspection.entity.Inspection;
import com.fabwatch.inspection.entity.InspectionCheckResult;
import com.fabwatch.inspection.repository.ChecklistItemRepository;
import com.fabwatch.inspection.repository.InspectionCheckResultRepository;
import com.fabwatch.inspection.repository.InspectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InspectionQueryServiceImpl implements InspectionQueryService {

    private final InspectionRepository inspectionRepository;
    private final InspectionCheckResultRepository checkResultRepository;
    private final ChecklistItemRepository checklistItemRepository;

    @Override
    public Optional<InspectionSummary> findSummary(Long inspectionId) {
        if (inspectionId == null) {
            return Optional.empty();
        }
        return inspectionRepository.findById(inspectionId).map(this::toSummary);
    }

    @Override
    public List<InspectionSummary> findRecentByEquipment(Long equipmentId, int limit) {
        if (equipmentId == null || limit <= 0) {
            return List.of();
        }
        return inspectionRepository
                .findByEquipmentIdOrderByStartedAtDescIdDesc(equipmentId, PageRequest.of(0, limit))
                .stream().map(this::toSummary).toList();
    }

    @Override
    public List<InspectionSummary> findRecentBmByEquipment(Long equipmentId, int limit) {
        if (equipmentId == null || limit <= 0) {
            return List.of();
        }
        return inspectionRepository
                .findByEquipmentIdAndTypeOrderByStartedAtDescIdDesc(equipmentId, Inspection.Type.BM,
                        PageRequest.of(0, limit))
                .stream().map(this::toSummary).toList();
    }

    private InspectionSummary toSummary(Inspection inspection) {
        return new InspectionSummary(
                inspection.getId(),
                inspection.getEquipmentId(),
                inspection.getType().name(),
                inspection.getShift().name(),
                inspection.getWorkerId(),
                inspection.getStartedAt(),
                inspection.getDurationMin(),
                inspection.getContent(),
                inspection.getActionTaken(),
                inspection.getCause4m() == null ? null : inspection.getCause4m().name(),
                inspection.getCauseDetail(),
                inspection.isHasNg(),
                inspection.getAlarmId(),
                inspection.isHasNg() ? ngItems(inspection) : List.of());
    }

    /** NG 판정 체크리스트 항목을 "항목명 — 메모"로 풀어낸다 (PM NG 항목 포함 요구, docs/03 F-6.1 ③). */
    private List<String> ngItems(Inspection inspection) {
        List<InspectionCheckResult> ngResults = checkResultRepository
                .findByInspectionIdOrderByIdAsc(inspection.getId()).stream()
                .filter(r -> r.getResult() == InspectionCheckResult.Result.NG)
                .toList();
        if (ngResults.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = checklistItemRepository
                .findAllById(ngResults.stream().map(InspectionCheckResult::getChecklistItemId).distinct().toList())
                .stream().collect(Collectors.toMap(ChecklistItem::getId, ChecklistItem::getItemName,
                        (a, b) -> a));
        return ngResults.stream()
                .map(r -> {
                    String name = names.getOrDefault(r.getChecklistItemId(), "항목#" + r.getChecklistItemId());
                    return r.getNote() == null || r.getNote().isBlank() ? name : name + " — " + r.getNote();
                })
                .toList();
    }
}
