package com.fabwatch.inspection.entity;

import com.fabwatch.common.entity.SoftDeletableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * PM 체크리스트 템플릿 항목 (docs/05 checklist_items, docs/03 F-3.2).
 */
@Entity
@Table(name = "checklist_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE checklist_items SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
public class ChecklistItem extends SoftDeletableEntity {

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    @Column(name = "item_name", length = 200, nullable = false)
    private String itemName;

    /** 판정 기준 */
    @Column(name = "criteria", length = 200)
    private String criteria;

    @Column(name = "seq")
    private Integer seq;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Builder
    private ChecklistItem(Long equipmentId, String itemName, String criteria, Integer seq, boolean active) {
        this.equipmentId = equipmentId;
        this.itemName = itemName;
        this.criteria = criteria;
        this.seq = seq;
        this.active = active;
    }

    /** 템플릿 항목 수정. 삭제는 active=false (물리 삭제 금지). seq가 null이면 기존 순서 유지. */
    public void update(String itemName, String criteria, Integer seq, boolean active) {
        this.itemName = itemName;
        this.criteria = criteria;
        if (seq != null) {
            this.seq = seq;
        }
        this.active = active;
    }
}
