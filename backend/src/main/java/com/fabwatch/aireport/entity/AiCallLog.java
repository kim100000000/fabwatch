package com.fabwatch.aireport.entity;

import com.fabwatch.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * AI 호출 이력 (docs/05 ai_call_logs, docs/11 §7 호출 로그).
 * 일일 쿼터 집계의 단일 출처이기도 하다 — 요청이 수락될 때 1행을 먼저 만들고(success=null, 진행 중),
 * 생성이 끝나면 결과를 채운다. mock 호출도 1건으로 센다.
 * 추가 전용 로그라 soft delete 대상이 아니다(삭제 경로 없음).
 */
@Entity
@Table(name = "ai_call_logs", indexes = {
        @Index(name = "idx_ai_call_requested", columnList = "requested_at"),
        @Index(name = "idx_ai_call_report", columnList = "report_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiCallLog extends BaseEntity {

    public static final int ERROR_SUMMARY_MAX = 300;

    @Column(name = "report_id")
    private Long reportId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    /** claude / mock */
    @Column(name = "provider", length = 10, nullable = false)
    private String provider;

    @Column(name = "model", length = 50)
    private String model;

    /** null = 진행 중, true/false = 완료 */
    @Column(name = "success")
    private Boolean success;

    /** 정제된 실패 사유 — API 키·응답 원문 금지 */
    @Column(name = "error_summary", length = ERROR_SUMMARY_MAX)
    private String errorSummary;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    public AiCallLog(Long reportId, Long userId, Instant requestedAt, String provider, String model) {
        this.reportId = reportId;
        this.userId = userId;
        this.requestedAt = requestedAt;
        this.provider = provider;
        this.model = model;
    }

    public void succeed(String model, int promptTokens, int completionTokens) {
        this.success = true;
        this.model = model;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.errorSummary = null;
    }

    /** 성공이지만 max_tokens로 본문이 잘린 채 DRAFT 저장된 경우 — error_summary에 고정 코드를 남겨 로그에서 구분한다 */
    public void succeedTruncated(String model, int promptTokens, int completionTokens, String note) {
        succeed(model, promptTokens, completionTokens);
        this.errorSummary = note == null ? null
                : (note.length() <= ERROR_SUMMARY_MAX ? note : note.substring(0, ERROR_SUMMARY_MAX));
    }

    public void fail(String summary) {
        this.success = false;
        this.errorSummary = summary == null ? null
                : (summary.length() <= ERROR_SUMMARY_MAX ? summary : summary.substring(0, ERROR_SUMMARY_MAX));
    }

    public boolean isPending() {
        return success == null;
    }
}
