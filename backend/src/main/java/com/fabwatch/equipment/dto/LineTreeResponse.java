package com.fabwatch.equipment.dto;

import java.util.List;

/** GET /lines — 라인+공정+설비 트리 (docs/06 §2) */
public record LineTreeResponse(Long id, String name, List<ProcessTreeResponse> processes) {

    /** 트리 하위 노드 */
    public record ProcessTreeResponse(Long id, String name, Integer seq, List<EquipmentSummaryResponse> equipments) {
    }
}
