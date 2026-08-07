package com.fabwatch.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * 점검별 체크리스트 결과 (docs/05 inspection_check_results).
 * TODO: 다음 라운드에 com.fabwatch.inspection.entity 패키지로 이동 예정.
 */
@Entity
@Table(name = "inspection_check_results")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE inspection_check_results SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
public class InspectionCheckResult extends SoftDeletableEntity {

    /** NG가 1건이라도 있으면 점검 이력에 NG 플래그 (docs/03 F-3.2) */
    public enum Result {
        OK, NG, NA
    }

    @Column(name = "inspection_id", nullable = false)
    private Long inspectionId;

    @Column(name = "checklist_item_id", nullable = false)
    private Long checklistItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", length = 5, nullable = false)
    private Result result;

    @Column(name = "note", length = 300)
    private String note;

    @Builder
    private InspectionCheckResult(Long inspectionId, Long checklistItemId, Result result, String note) {
        this.inspectionId = inspectionId;
        this.checklistItemId = checklistItemId;
        this.result = result;
        this.note = note;
    }
}
