package com.fabwatch.aireport.entity;

import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI 리포트 상태 전이 매트릭스 (docs/03 F-6.3, 계약 4~6): PUT / confirm / retry 허용·거부 + draft 불변.
 */
class AiReportStateMachineTest {

    private static final Instant NOW = Instant.parse("2026-10-02T03:00:00Z");

    private static AiReport generating() {
        return AiReport.generating(1L, 10L, null, "고장 리포트 — LAMI-01 2026-10-02 12:00", 5L);
    }

    private static AiReport draft() {
        AiReport r = generating();
        r.completeDraft("AI 원본", "claude-sonnet-5-5", 1200, 650);
        return r;
    }

    private static AiReport failed() {
        AiReport r = generating();
        r.fail("AI 서버 오류", "claude-sonnet-5-5");
        return r;
    }

    private static AiReport confirmed() {
        AiReport r = draft();
        r.confirm(9L, NOW);
        return r;
    }

    private static void assertInvalidState(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, e ->
                assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REPORT_STATE));
    }

    // ------------------------------------------------------------ 생성/완료/실패

    @Test
    @DisplayName("생성 직후 GENERATING, 본문 없음")
    void created_isGenerating() {
        AiReport r = generating();
        assertThat(r.getStatus()).isEqualTo(AiReport.Status.GENERATING);
        assertThat(r.getDraftContent()).isNull();
        assertThat(r.getFinalContent()).isNull();
    }

    @Test
    @DisplayName("GENERATING → DRAFT: 원본·모델·토큰 기록")
    void completeDraft() {
        AiReport r = draft();
        assertThat(r.getStatus()).isEqualTo(AiReport.Status.DRAFT);
        assertThat(r.getDraftContent()).isEqualTo("AI 원본");
        assertThat(r.getModel()).isEqualTo("claude-sonnet-5-5");
        assertThat(r.getPromptTokens()).isEqualTo(1200);
        assertThat(r.getCompletionTokens()).isEqualTo(650);
    }

    @Test
    @DisplayName("GENERATING → FAILED: 사유 300자 제한")
    void fail_truncatesReason() {
        AiReport r = generating();
        r.fail("가".repeat(500), "m");
        assertThat(r.getStatus()).isEqualTo(AiReport.Status.FAILED);
        assertThat(r.getFailReason()).hasSize(300);
    }

    @Test
    @DisplayName("completeDraft/fail 은 GENERATING에서만 (DRAFT/FAILED/CONFIRMED는 409)")
    void completeAndFail_onlyFromGenerating() {
        assertInvalidState(() -> draft().completeDraft("x", "m", 0, 0));
        assertInvalidState(() -> failed().completeDraft("x", "m", 0, 0));
        assertInvalidState(() -> confirmed().completeDraft("x", "m", 0, 0));
        assertInvalidState(() -> draft().fail("x", null));
        assertInvalidState(() -> failed().fail("x", null));
        assertInvalidState(() -> confirmed().fail("x", null));
    }

    // ------------------------------------------------------------ PUT (updateFinal)

    @Test
    @DisplayName("PUT 허용: DRAFT, FAILED(수동 작성)")
    void updateFinal_allowedInDraftAndFailed() {
        AiReport d = draft();
        d.updateFinal("편집본");
        assertThat(d.getFinalContent()).isEqualTo("편집본");
        assertThat(d.getStatus()).isEqualTo(AiReport.Status.DRAFT);

        AiReport f = failed();
        f.updateFinal("수동 작성");
        assertThat(f.getFinalContent()).isEqualTo("수동 작성");
        assertThat(f.getStatus()).isEqualTo(AiReport.Status.FAILED);
    }

    @Test
    @DisplayName("PUT 거부: GENERATING, CONFIRMED → 409 INVALID_REPORT_STATE")
    void updateFinal_rejectedInGeneratingAndConfirmed() {
        assertInvalidState(() -> generating().updateFinal("x"));
        assertInvalidState(() -> confirmed().updateFinal("x"));
    }

    @Test
    @DisplayName("draft_content는 PUT·확정·재시도로 절대 바뀌지 않는다 (AI 원본 보존)")
    void draftContent_isImmutable() {
        AiReport r = draft();
        r.updateFinal("편집 1");
        r.updateFinal("편집 2");
        assertThat(r.getDraftContent()).isEqualTo("AI 원본");
        r.confirm(9L, NOW);
        assertThat(r.getDraftContent()).isEqualTo("AI 원본");
        assertThat(r.getFinalContent()).isEqualTo("편집 2");
    }

    // ------------------------------------------------------------ confirm

    @Test
    @DisplayName("DRAFT 확정: final이 비어 있으면 draft를 final로 복사, 원본은 그대로")
    void confirm_draftCopiesDraftWhenFinalBlank() {
        AiReport r = draft();
        r.confirm(9L, NOW);

        assertThat(r.getStatus()).isEqualTo(AiReport.Status.CONFIRMED);
        assertThat(r.getFinalContent()).isEqualTo("AI 원본");
        assertThat(r.getDraftContent()).isEqualTo("AI 원본");
        assertThat(r.getConfirmedBy()).isEqualTo(9L);
        assertThat(r.getConfirmedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("DRAFT 확정: 공백뿐인 final도 비어 있는 것으로 보고 draft 복사")
    void confirm_draftWithBlankFinal() {
        AiReport r = draft();
        r.updateFinal("   ");
        r.confirm(9L, NOW);
        assertThat(r.getFinalContent()).isEqualTo("AI 원본");
    }

    @Test
    @DisplayName("DRAFT 확정: 편집한 final이 있으면 그대로 확정")
    void confirm_draftKeepsEditedFinal() {
        AiReport r = draft();
        r.updateFinal("편집본");
        r.confirm(9L, NOW);
        assertThat(r.getFinalContent()).isEqualTo("편집본");
    }

    @Test
    @DisplayName("FAILED 확정: 수동 작성본이 있으면 가능, 없으면 400 VALIDATION_ERROR")
    void confirm_failedNeedsManualFinal() {
        AiReport manual = failed();
        manual.updateFinal("수동 작성");
        manual.confirm(9L, NOW);
        assertThat(manual.getStatus()).isEqualTo(AiReport.Status.CONFIRMED);
        assertThat(manual.getDraftContent()).isNull();

        assertThatThrownBy(() -> failed().confirm(9L, NOW)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        AiReport blank = failed();
        blank.updateFinal(" ");
        assertThatThrownBy(() -> blank.confirm(9L, NOW)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    @DisplayName("확정 거부: GENERATING, 이미 CONFIRMED → 409. CONFIRMED 이후 PUT도 불가")
    void confirm_rejectedInGeneratingAndConfirmed() {
        assertInvalidState(() -> generating().confirm(9L, NOW));
        assertInvalidState(() -> confirmed().confirm(9L, NOW));
        assertInvalidState(() -> confirmed().updateFinal("확정 후 수정"));
    }

    // ------------------------------------------------------------ retry

    @Test
    @DisplayName("retry(restartGeneration): FAILED만 허용 → GENERATING, 사유 초기화")
    void restartGeneration_onlyFromFailed() {
        AiReport r = failed();
        r.restartGeneration();
        assertThat(r.getStatus()).isEqualTo(AiReport.Status.GENERATING);
        assertThat(r.getFailReason()).isNull();

        assertInvalidState(() -> generating().restartGeneration());
        assertInvalidState(() -> draft().restartGeneration());
        assertInvalidState(() -> confirmed().restartGeneration());
    }

    @Test
    @DisplayName("retry는 수동 작성해 둔 final_content를 지우지 않는다")
    void restartGeneration_keepsManualFinal() {
        AiReport r = failed();
        r.updateFinal("수동 작성 중");
        r.restartGeneration();
        assertThat(r.getFinalContent()).isEqualTo("수동 작성 중");
    }
}
