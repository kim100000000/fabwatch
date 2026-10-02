package com.fabwatch.aireport.service;

import com.fabwatch.aireport.client.ClaudeClient;
import com.fabwatch.aireport.client.ClaudeClientException;
import com.fabwatch.aireport.client.ClaudeResponse;
import com.fabwatch.aireport.config.AiProperties;
import com.fabwatch.aireport.entity.AiReport;
import com.fabwatch.aireport.prompt.MockReportTemplate;
import com.fabwatch.aireport.prompt.Prompt;
import com.fabwatch.aireport.prompt.PromptBuilder;
import com.fabwatch.aireport.prompt.ReportContext;
import com.fabwatch.aireport.repository.AiReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 리포트 생성 워커 — 비동기 스레드에서 실행된다 (SecurityContext 없음: 필요한 값은 전부 인자로 받는다).
 * 컨텍스트 수집 → (mock 템플릿 | 프롬프트 조립 + Claude 호출) → 결과 저장. 어떤 예외도 밖으로 던지지 않고
 * FAILED로 마무리해 GENERATING에 남지 않게 한다.
 *
 * provider는 명시 설정만 따른다 — 키가 없다고 자동으로 mock이 되지 않는다(키 없는 claude는 FAILED).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiReportGenerator {

    static final String TRUNCATED_NOTE =
            "\n\n> 출력 길이 제한(max_tokens)으로 내용이 잘렸을 수 있습니다. 필요하면 편집해서 보완하세요.";
    static final String MSG_INTERNAL = "리포트 생성 중 내부 오류가 발생했습니다";
    static final String MSG_BAD_PROVIDER = "AI_PROVIDER 설정이 올바르지 않습니다(claude 또는 mock)";

    private final AiProperties props;
    private final AiReportRepository reportRepository;
    private final ReportContextCollector collector;
    private final ClaudeClient claudeClient;
    private final AiReportResultWriter writer;

    /**
     * @param logId  요청 수락 시 만든 ai_call_logs 행
     * @param userId 요청자(로그용) — 비동기 스레드에는 SecurityContext가 없어 인자로 받는다
     */
    public void generate(Long reportId, Long logId, Long userId) {
        String recordedModel = props.isMock() ? "mock" : props.model();
        try {
            AiReport report = reportRepository.findById(reportId).orElse(null);
            if (report == null || report.getStatus() != AiReport.Status.GENERATING) {
                log.warn("AI 리포트 생성 건너뜀(GENERATING 아님): reportId={}", reportId);
                return;
            }
            log.info("AI 리포트 생성 시작: reportId={}, provider={}, by={}", reportId, props.normalizedProvider(), userId);

            ReportContext context = collector.collect(report);
            if (props.isMock()) {
                writer.saveDraft(reportId, logId, MockReportTemplate.render(context), "mock", 0, 0);
            } else if (props.isClaude()) {
                Prompt prompt = PromptBuilder.build(context);
                ClaudeResponse response = claudeClient.generate(prompt.system(), prompt.user());
                if (response.truncated()) {
                    // 본문이 잘린 채 DRAFT로 저장 — 호출 로그에 MAX_TOKENS_TRUNCATED를 남겨 구분한다 (H-1)
                    writer.saveTruncatedDraft(reportId, logId, response.text() + TRUNCATED_NOTE, response.model(),
                            response.inputTokens(), response.outputTokens());
                } else {
                    writer.saveDraft(reportId, logId, response.text(), response.model(),
                            response.inputTokens(), response.outputTokens());
                }
            } else {
                writer.saveFailure(reportId, logId, MSG_BAD_PROVIDER, null);
            }
        } catch (ClaudeClientException e) {
            writer.saveFailure(reportId, logId, e.getMessage(), recordedModel);
        } catch (Exception e) {
            // 예외 객체·메시지는 로그에 넘기지 않는다 — 스택트레이스/메시지에 요청 헤더 값(API 키)이 섞일 수 있다 (M-1).
            // 클래스명과 reportId만 기록한다.
            log.error("AI 리포트 생성 중 내부 오류: reportId={}, exception={}", reportId, e.getClass().getSimpleName());
            safeFail(reportId, logId, recordedModel);
        }
    }

    private void safeFail(Long reportId, Long logId, String model) {
        try {
            writer.saveFailure(reportId, logId, MSG_INTERNAL, model);
        } catch (Exception e) {
            // 마지막 수단 — 복구 스케줄러가 GENERATING 정체 건을 정리한다
            log.error("AI 리포트 실패 상태 저장 실패: reportId={}, exception={}", reportId, e.getClass().getSimpleName());
        }
    }
}
