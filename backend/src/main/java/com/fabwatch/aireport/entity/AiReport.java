package com.fabwatch.aireport.entity;

import com.fabwatch.common.entity.SoftDeletableEntity;
import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;

/**
 * AI 고장 리포트 (docs/05 ai_reports, docs/03 F-6).
 * draft_content(AI 원본)와 final_content(확정본)를 분리 보관한다 — AI를 그대로 신뢰하지 않는다는 설계.
 *
 * 상태 머신 (전이 규칙은 이 클래스의 메서드에만 존재한다 — QA가 전이 완전성을 한 곳에서 추적):
 * <pre>
 *   (생성)  → GENERATING
 *   GENERATING → DRAFT      completeDraft (AI 응답 저장)
 *   GENERATING → FAILED     fail          (호출 실패·서버 재시작 복구)
 *   FAILED     → GENERATING restartGeneration (재시도, 같은 reportId 재사용)
 *   DRAFT/FAILED → (편집)   updateFinal   (FAILED는 '수동 작성' 용도)
 *   DRAFT/FAILED → CONFIRMED confirm      (FAILED는 수동 작성본이 있어야 함)
 * </pre>
 * draftContent는 completeDraft 외에는 절대 쓰지 않는다 (AI 원본 보존).
 */
@Entity
@Table(name = "ai_reports", indexes = {
        @Index(name = "idx_ai_report_equipment_created", columnList = "equipment_id, created_at"),
        @Index(name = "idx_ai_report_status_updated", columnList = "status, updated_at"),
        @Index(name = "idx_ai_report_alarm", columnList = "alarm_id"),
        @Index(name = "idx_ai_report_inspection", columnList = "inspection_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE ai_reports SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
public class AiReport extends SoftDeletableEntity {

    /** MySQL MEDIUMTEXT 상한(16MB). @Column(length)로 넘겨야 Hibernate가 MEDIUMTEXT로 DDL을 만든다. */
    private static final int MEDIUMTEXT_LENGTH = 16_777_215;

    /** fail_reason 컬럼 길이 (docs/05) */
    public static final int FAIL_REASON_MAX = 300;

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

    @Column(name = "fail_reason", length = FAIL_REASON_MAX)
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

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Builder
    private AiReport(Long equipmentId, Long alarmId, Long inspectionId, String title, Status status,
                     String draftContent, String finalContent, String failReason, String model,
                     Integer promptTokens, Integer completionTokens, Long createdBy, Long confirmedBy,
                     Instant confirmedAt) {
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
        this.confirmedAt = confirmedAt;
    }

    /** 신규 생성 요청 — 항상 GENERATING으로 시작한다. */
    public static AiReport generating(Long equipmentId, Long alarmId, Long inspectionId, String title,
                                      Long createdBy) {
        return AiReport.builder()
                .equipmentId(equipmentId)
                .alarmId(alarmId)
                .inspectionId(inspectionId)
                .title(title)
                .status(Status.GENERATING)
                .createdBy(createdBy)
                .build();
    }

    // ------------------------------------------------------------------ 전이

    /** GENERATING → DRAFT. AI 원본(draftContent)은 여기서만 기록된다. */
    public void completeDraft(String content, String model, int promptTokens, int completionTokens) {
        requireStatus(Status.GENERATING);
        this.draftContent = content;
        this.model = model;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.failReason = null;
        this.status = Status.DRAFT;
    }

    /** GENERATING → FAILED. 사유는 호출 측이 정제한 문자열이며 300자로 자른다. */
    public void fail(String reason, String model) {
        requireStatus(Status.GENERATING);
        this.failReason = truncate(reason, FAIL_REASON_MAX);
        if (model != null) {
            this.model = model;
        }
        this.status = Status.FAILED;
    }

    /** FAILED → GENERATING (재시도). 수동 작성해 둔 finalContent는 건드리지 않는다. */
    public void restartGeneration() {
        requireStatus(Status.FAILED);
        this.failReason = null;
        this.status = Status.GENERATING;
    }

    /**
     * 확정본 편집 — DRAFT 또는 FAILED(수동 작성)에서만 가능. draftContent는 절대 건드리지 않는다.
     * GENERATING·CONFIRMED는 409 INVALID_REPORT_STATE.
     */
    public void updateFinal(String content) {
        if (status != Status.DRAFT && status != Status.FAILED) {
            throw invalidState("DRAFT 또는 FAILED 상태의 리포트만 수정할 수 있습니다. 현재 상태: " + status);
        }
        this.finalContent = content;
    }

    /**
     * 확정 → CONFIRMED.
     * DRAFT: finalContent가 비어 있으면 AI 원본을 확정본으로 복사(원본 draftContent는 그대로 보존).
     * FAILED: 수동 작성한 finalContent가 있어야 한다(없으면 400 VALIDATION_ERROR).
     * GENERATING·이미 CONFIRMED는 409 INVALID_REPORT_STATE.
     */
    public void confirm(Long userId, Instant at) {
        switch (status) {
            case DRAFT -> {
                if (isBlank(finalContent)) {
                    this.finalContent = draftContent;
                }
            }
            case FAILED -> {
                if (isBlank(finalContent)) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                            "생성에 실패한 리포트는 수동 작성한 내용(finalContent)이 있어야 확정할 수 있습니다.");
                }
            }
            default -> throw invalidState("DRAFT 또는 FAILED 상태의 리포트만 확정할 수 있습니다. 현재 상태: " + status);
        }
        this.status = Status.CONFIRMED;
        this.confirmedBy = userId;
        this.confirmedAt = at;
    }

    private void requireStatus(Status expected) {
        if (status != expected) {
            throw invalidState(expected + " 상태에서만 수행할 수 있습니다. 현재 상태: " + status);
        }
    }

    private static BusinessException invalidState(String message) {
        return new BusinessException(ErrorCode.INVALID_REPORT_STATE, message);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
