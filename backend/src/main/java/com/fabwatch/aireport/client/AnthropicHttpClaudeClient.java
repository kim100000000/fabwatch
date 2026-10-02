package com.fabwatch.aireport.client;

import com.fabwatch.aireport.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Anthropic Messages API 직접 호출 구현 (docs/06 §10). 공식 Java SDK 대신 RestClient를 쓴다 —
 * 새 의존성 없이 단일 엔드포인트만 호출하고, MockRestServiceServer로 외부 네트워크 없이 검증하기 위함.
 *
 * <pre>
 *   POST {baseUrl}/v1/messages
 *   headers: x-api-key, anthropic-version, content-type
 *   body   : { model, max_tokens, output_config:{effort}, system, messages:[{role:"user", content}] }
 * </pre>
 *
 * 요청 설정 (claude-sonnet-5-5): thinking 필드는 보내지 않는다(생략 = adaptive thinking). 대신 output_config.effort=low로
 * 사고 토큰을 최소화한다. temperature/top_p/top_k는 비기본값이면 400이므로 절대 보내지 않는다. 사고 토큰도 max_tokens에 포함된다.
 *
 * 재시도 정책 (docs/03 F-6.4): 타임아웃·5xx는 1회 재시도, 429·그 외 4xx는 재시도 없이 즉시 실패.
 * 실패 메시지는 전부 고정 문구 — API 키·응답 원문을 담지 않는다.
 */
@Slf4j
@Component
public class AnthropicHttpClaudeClient implements ClaudeClient {

    static final int MAX_ATTEMPTS = 2;
    private static final com.fasterxml.jackson.databind.ObjectMapper ERROR_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private static final Pattern ERROR_TYPE = Pattern.compile("^[a-z_]{1,40}$");

    public static final String MSG_NOT_CONFIGURED = "ANTHROPIC_API_KEY가 설정되지 않았습니다";
    public static final String MSG_RATE_LIMITED = "잠시 후 다시 시도해 주세요";

    private final RestClient restClient;
    private final AiProperties props;

    @Autowired
    public AnthropicHttpClaudeClient(RestClient.Builder builder, AiProperties props) {
        this(buildRestClient(builder, props), props);
    }

    /** 테스트용 — MockRestServiceServer가 바인딩된 RestClient를 그대로 받는다. */
    AnthropicHttpClaudeClient(RestClient restClient, AiProperties props) {
        this.restClient = restClient;
        this.props = props;
    }

    private static RestClient buildRestClient(RestClient.Builder builder, AiProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        // 응답 읽기 타임아웃: fabwatch.ai.timeout-seconds (기본 60초, docs/03 F-6.4) / connect는 10초 고정
        factory.setReadTimeout(Duration.ofSeconds(props.timeoutSeconds()));
        return builder.baseUrl(props.baseUrl()).requestFactory(factory).build();
    }

    @Override
    public ClaudeResponse generate(String systemPrompt, String userMessage) {
        if (!props.hasApiKey()) {
            // 미설정이거나 개행/제어문자가 섞여 유효하지 않은 키 — 호출하지 않는다 — 키 없다고 자동 mock으로 넘어가지도 않는다
            throw new ClaudeClientException(ClaudeClientException.Kind.NOT_CONFIGURED, MSG_NOT_CONFIGURED);
        }
        ClaudeClientException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return call(systemPrompt, userMessage);
            } catch (ClaudeClientException e) {
                last = e;
                boolean retryable = e.getKind() == ClaudeClientException.Kind.SERVER
                        || e.getKind() == ClaudeClientException.Kind.TIMEOUT
                        || e.getKind() == ClaudeClientException.Kind.NETWORK;
                log.warn("Claude 호출 실패: attempt={}/{}, kind={}, status={}",
                        attempt, MAX_ATTEMPTS, e.getKind(), e.getHttpStatus());
                if (!retryable || attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                backoff();
            }
        }
        throw last; // 도달 불가 — 컴파일러 만족용
    }

    private ClaudeResponse call(String systemPrompt, String userMessage) {
        Map<String, Object> body = Map.of(
                "model", props.model(),
                "max_tokens", props.maxTokens(),
                "output_config", Map.of("effort", props.effort()),
                "system", systemPrompt,
                "messages", List.of(Map.of("role", "user", "content", userMessage)));
        try {
            JsonNode json = restClient.post()
                    .uri("/v1/messages")
                    .header("x-api-key", props.apiKey())
                    .header("anthropic-version", props.anthropicVersion())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return parse(json);
        } catch (RestClientResponseException e) {
            throw mapStatus(e);
        } catch (ResourceAccessException e) {
            if (isTimeout(e)) {
                throw new ClaudeClientException(ClaudeClientException.Kind.TIMEOUT,
                        "AI 서버 응답 시간이 초과되었습니다(재시도 후에도 실패)");
            }
            throw new ClaudeClientException(ClaudeClientException.Kind.NETWORK,
                    "AI 서버에 연결하지 못했습니다(재시도 후에도 실패)");
        } catch (ClaudeClientException e) {
            throw e;
        } catch (RestClientException e) {
            // 본문 파싱 실패 등 — 원문은 담지 않는다
            throw new ClaudeClientException(ClaudeClientException.Kind.INVALID_RESPONSE,
                    "AI 응답을 해석하지 못했습니다");
        } catch (RuntimeException e) {
            // 예: 헤더 값에 개행이 섞인 키 → IllegalArgumentException(메시지에 키 원문 포함). 원인 예외는 버리고
            // 키·원문이 없는 고정 문구만 남긴다(예외 클래스명도 호출 로그에 남기지 않는다 — M-1)
            // 재시도해도 같은 결과이므로 재시도 대상이 아닌 BAD_REQUEST로 분류한다
            throw new ClaudeClientException(ClaudeClientException.Kind.BAD_REQUEST,
                    "AI 요청을 보내지 못했습니다(요청 구성 오류)");
        }
    }

    private ClaudeResponse parse(JsonNode json) {
        if (json == null || !json.has("content") || !json.get("content").isArray()) {
            throw new ClaudeClientException(ClaudeClientException.Kind.INVALID_RESPONSE,
                    "AI 응답 형식이 올바르지 않습니다");
        }
        String stopReason = json.path("stop_reason").asText("");
        if ("refusal".equals(stopReason)) {
            throw new ClaudeClientException(ClaudeClientException.Kind.REFUSED,
                    "AI가 안전 정책으로 응답을 거부했습니다. 수동으로 작성해 주세요");
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode block : json.get("content")) {
            if ("text".equals(block.path("type").asText()) && block.hasNonNull("text")) {
                text.append(block.get("text").asText());
            }
        }
        JsonNode usage = json.path("usage");
        if (text.toString().isBlank()) {
            // thinking이 max_tokens를 다 쓴 경우(MAX_TOKENS_TRUNCATED)와 그 외 빈 본문(EMPTY_CONTENT)을 고정 코드로 구분한다
            log.warn("Claude 응답 본문 없음: stop_reason={}, output_tokens={}", sanitizeToken(stopReason),
                    usage.path("output_tokens").asInt(0));
            if ("max_tokens".equals(stopReason)) {
                throw new ClaudeClientException(ClaudeClientException.Kind.MAX_TOKENS_TRUNCATED,
                        "[" + ClaudeClientException.CODE_MAX_TOKENS_TRUNCATED
                                + "] 출력 토큰 한도에 도달해 본문이 생성되지 않았습니다. 다시 시도하거나 수동으로 작성하세요");
            }
            throw new ClaudeClientException(ClaudeClientException.Kind.EMPTY_CONTENT,
                    "[" + ClaudeClientException.CODE_EMPTY_CONTENT
                            + "] AI 응답에 본문이 없습니다. 다시 시도하거나 수동으로 작성하세요");
        }
        return new ClaudeResponse(
                text.toString().strip(),
                json.path("model").asText(props.model()),
                usage.path("input_tokens").asInt(0),
                usage.path("output_tokens").asInt(0),
                "max_tokens".equals(stopReason));
    }

    /** 로그에 찍는 stop_reason을 소문자/밑줄 20자로 제한한다 */
    private static String sanitizeToken(String value) {
        return value != null && value.matches("^[a-z_]{0,20}$") ? value : "?";
    }

    private ClaudeClientException mapStatus(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == 429) {
            return new ClaudeClientException(ClaudeClientException.Kind.RATE_LIMITED, MSG_RATE_LIMITED, status);
        }
        if (status >= 500) {
            return new ClaudeClientException(ClaudeClientException.Kind.SERVER,
                    "AI 서버 오류(HTTP " + status + ")로 생성에 실패했습니다(재시도 후에도 실패)", status);
        }
        if (status == 401 || status == 403) {
            return new ClaudeClientException(ClaudeClientException.Kind.AUTH,
                    "AI 서비스 인증에 실패했습니다(HTTP " + status + "). 관리자에게 문의하세요", status);
        }
        String type = errorType(e);
        return new ClaudeClientException(ClaudeClientException.Kind.BAD_REQUEST,
                "AI 요청이 거부되었습니다(HTTP " + status + (type == null ? "" : ", " + type) + ")", status);
    }

    /** 응답 본문에서 error.type(예: invalid_request_error)만 화이트리스트 형식으로 추출한다. 그 외 원문은 버린다. */
    private static String errorType(RestClientResponseException e) {
        try {
            String raw = e.getResponseBodyAsString();
            if (raw == null || raw.isBlank()) {
                return null;
            }
            JsonNode node = ERROR_MAPPER.readTree(raw);
            String type = node.path("error").path("type").asText(null);
            return type != null && ERROR_TYPE.matcher(type).matches() ? type : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException || t instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private void backoff() {
        long millis = props.retryBackoffMillis();
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
