package com.fabwatch.inspection.dto;

import com.fabwatch.inspection.entity.InspectionCheckResult;

/** 점검 상세의 체크리스트 결과 행 — itemName/criteria는 템플릿에서 채운 표시용 필드 */
public record CheckResultResponse(
        Long checklistItemId,
        String itemName,
        String criteria,
        String result,
        String note) {

    public static CheckResultResponse of(InspectionCheckResult row, String itemName, String criteria) {
        return new CheckResultResponse(row.getChecklistItemId(), itemName, criteria, row.getResult().name(), row.getNote());
    }
}
