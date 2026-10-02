package com.fabwatch.inspection.dto;

import com.fabwatch.inspection.entity.ChecklistItem;

public record ChecklistItemResponse(Long id, String itemName, String criteria, Integer seq, boolean active) {

    public static ChecklistItemResponse from(ChecklistItem item) {
        return new ChecklistItemResponse(item.getId(), item.getItemName(), item.getCriteria(), item.getSeq(), item.isActive());
    }
}
