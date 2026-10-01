package com.fabwatch.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * AI 고장 리포트 (docs/05 ai_reports, docs/03 F-6).
 * draft_content(AI 원본)와 final_content(확정본)를 분리 보관한다 — AI를 그대로 신뢰하지 않는다는 설계.
 * TODO: 다음 라운드에 com.fabwatch.aireport.entity 패키지로 이동 예정.
 */
@Entity
@Table(name = "ai_reports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE ai_reports SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
public class AiReport extends SoftDeletableEntity {

    /** MySQL MEDIUMTEXT 상한(16MB). @Column(length)로 넘겨야 Hibernate가 MEDIUMTEXT로 DDL을 만든다. */
    private static final int MEDIUMTEXT_LENGTH = 16_777_215;

    public enum Status {
        GENERATING, DRAFT, CONFIRMED, FAILED
    }

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    /** alarm_id / inspection_id 둘 중 하나 이상 (docs/05) */
    @Column(name = "alarm_id")
    private Long alarmId;

    @Column(name = "inspection_id")
    private Long inspectionId;

    @Column(name = "title", length = 200, nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 15, nullable = false)
    private Status status;

    /**
     * AI 원본 — 수정 금지.
     * length를 명시하지 않으면 Hibernate가 기본 255로 잡아 MySQL에서 tinytext가 생성된다(실기동 시 시드 삽입 실패).
     * docs/05 스펙대로 MEDIUMTEXT가 되도록 16MB 길이를 지정한다.
     */
    @Lob
    @Column(name = "draft_content", length = MEDIUMTEXT_LENGTH)
    private String draftContent;

    @Lob
    @Column(name = "final_content", length = MEDIUMTEXT_LENGTH)
    private String finalContent;

    @Column(name = "fail_reason", length = 300)
    private String failReason;

    /** 비용 추적 (NFR-4, docs/11 §7) */
    @Column(name = "model", length = 50)
    private String model;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Builder
    private AiReport(Long equipmentId, Long alarmId, Long inspectionId, String title, Status status,
                     String draftContent, String finalContent, String failReason, String model,
                     Integer promptTokens, Integer completionTokens, Long createdBy, Long confirmedBy) {
        this.equipmentId = equipmentId;
        this.alarmId = alarmId;
        this.inspectionId = inspectionId;
        this.title = title;
        this.status = status;
        this.draftContent = draftContent;
        this.finalContent = finalContent;
        this.failReason = failReason;
        this.model = model;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.createdBy = createdBy;
        this.confirmedBy = confirmedBy;
    }
}
