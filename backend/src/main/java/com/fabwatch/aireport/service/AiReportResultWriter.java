package com.fabwatch.aireport.service;

import com.fabwatch.aireport.client.ClaudeClientException;
import com.fabwatch.aireport.config.AiProperties;
import com.fabwatch.aireport.entity.AiReport;
import com.fabwatch.aireport.repository.AiCallLogRepository;
import com.fabwatch.aireport.repository.AiReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비동기 생성 결과를 DB에 반영한다 (리포트 상태 전이 + 호출 로그 마감을 한 트랜잭션으로).
 * 생성 중 서버 재시작 복구 등으로 이미 GENERATING이 아니게 된 리포트는 건드리지 않는다.
 * 상태 확인~저장은 행 잠금(findByIdForUpdate)으로 복구 스위퍼·사용자 편집과 직렬화한다 (M-3).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiReportResultWriter {

    private final AiReportRepository reportRepository;
    private final AiCallLogRepository callLogRepository;
    private final AiProperties props;

    /** 호출 로그(ai_call_logs.error_summary)에 남기는 고정 문구 — 성공이지만 본문이 잘림 */
    static final String TRUNCATED_LOG_NOTE = "[" + ClaudeClientException.CODE_MAX_TOKENS_TRUNCATED
            + "] 출력 토큰 한도로 본문이 잘린 채 DRAFT로 저장됨";

    /** @return 저장했으면 true, 리포트가 이미 GENERATING이 아니어서 건너뛰었으면 false */
    @Transactional
    public boolean saveDraft(Long reportId, Long logId, String content, String model,
                             int promptTokens, int completionTokens) {
        return saveDraftInternal(reportId, logId, content, model, promptTokens, completionTokens, false);
    }

    /** max_tokens로 본문이 잘린 응답 — DRAFT로 저장하되 호출 로그 error_summary에 MAX_TOKENS_TRUNCATED를 남긴다. */
    @Transactional
    public boolean saveTruncatedDraft(Long reportId, Long logId, String content, String model,
                                      int promptTokens, int completionTokens) {
        return saveDraftInternal(reportId, logId, content, model, promptTokens, completionTokens, true);
    }

    private boolean saveDraftInternal(Long reportId, Long logId, String content, String model,
                                      int promptTokens, int completionTokens, boolean truncated) {
        AiReport report = reportRepository.findByIdForUpdate(reportId).orElse(null);
        if (report == null || report.getStatus() != AiReport.Status.GENERATING) {
            log.warn("AI 리포트 결과 저장 건너뜀(이미 상태 변경됨): reportId={}", reportId);
            return false;
        }
        report.completeDraft(content, model, promptTokens, completionTokens);
        callLogRepository.findById(logId).ifPresent(l -> {
            if (truncated) {
                l.succeedTruncated(model, promptTokens, completionTokens, TRUNCATED_LOG_NOTE);
            } else {
                l.succeed(model, promptTokens, completionTokens);
            }
        });
        if (truncated) {
            log.warn("AI 리포트 초안 저장(max_tokens로 잘림): reportId={}, model={}, tokens={}/{}", reportId, model,
                    promptTokens, completionTokens);
        } else {
            log.info("AI 리포트 초안 저장: reportId={}, model={}, tokens={}/{}", reportId, model,
                    promptTokens, completionTokens);
        }
        return true;
    }

    @Transactional
    public boolean saveFailure(Long reportId, Long logId, String reason, String model) {
        String safe = FailReasonSanitizer.sanitize(reason, props.apiKey());
        AiReport report = reportRepository.findByIdForUpdate(reportId).orElse(null);
        if (report == null || report.getStatus() != AiReport.Status.GENERATING) {
            log.warn("AI 리포트 실패 저장 건너뜀(이미 상태 변경됨): reportId={}", reportId);
            return false;
        }
        report.fail(safe, model);
        callLogRepository.findById(logId).ifPresent(l -> l.fail(safe));
        log.warn("AI 리포트 생성 실패: reportId={}, reason={}", reportId, safe);
        return true;
    }
}
