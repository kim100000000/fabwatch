package com.fabwatch.aireport.client;

import com.fabwatch.aireport.config.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withRawStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Claude 클라이언트 응답/에러 매핑 — 외부 네트워크 없이 MockRestServiceServer로만 검증 (docs/12 T-3, AI-1·AI-2).
 */
class AnthropicHttpClaudeClientTest {

    private static final String KEY = "sk-ant-api03-TEST_SECRET_KEY_0123456789";
    private static final String URL = "https://api.anthropic.com/v1/messages";

    private MockRestServiceServer server;
    private AnthropicHttpClaudeClient client;

    private static AiProperties props(String apiKey) {
        return new AiProperties("claude", apiKey, "claude-sonnet-5-5", 4096, 20, 60,
                "https://api.anthropic.com", "2023-06-01", 0, 2, 10, 5, "low");
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AnthropicHttpClaudeClient(builder.baseUrl("https://api.anthropic.com").build(), props(KEY));
    }

    private static String okBody(String stopReason) {
        return """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5-5",
                 "content":[{"type":"thinking","thinking":""},{"type":"text","text":"# 리포트\\n본문 "},{"type":"text","text":"끝"}],
                 "stop_reason":"%s","usage":{"input_tokens":1234,"output_tokens":567}}
                """.formatted(stopReason);
    }

    // ------------------------------------------------------------ 성공

    @Test
    @DisplayName("200: 요청 형식(헤더·바디) 검증 + text 블록 이어붙임 + usage 토큰 추출")
    void success_parsesTextAndUsage_andSendsExpectedRequest() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-api-key", KEY))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andExpect(header("content-type", containsString("application/json")))
                .andExpect(jsonPath("$.model", equalTo("claude-sonnet-5-5")))
                .andExpect(jsonPath("$.max_tokens", equalTo(4096)))
                // H-1: effort=low, thinking/샘플링 파라미터는 보내지 않는다(비기본값은 400)
                .andExpect(jsonPath("$.output_config.effort", equalTo("low")))
                .andExpect(jsonPath("$.thinking").doesNotExist())
                .andExpect(jsonPath("$.temperature").doesNotExist())
                .andExpect(jsonPath("$.top_p").doesNotExist())
                .andExpect(jsonPath("$.top_k").doesNotExist())
                .andExpect(jsonPath("$.system", equalTo("시스템 프롬프트")))
                .andExpect(jsonPath("$.messages[0].role", equalTo("user")))
                .andExpect(jsonPath("$.messages[0].content", equalTo("사용자 데이터")))
                .andRespond(withSuccess(okBody("end_turn"), MediaType.APPLICATION_JSON));

        ClaudeResponse response = client.generate("시스템 프롬프트", "사용자 데이터");

        assertThat(response.text()).isEqualTo("# 리포트\n본문 끝");
        assertThat(response.inputTokens()).isEqualTo(1234);
        assertThat(response.outputTokens()).isEqualTo(567);
        assertThat(response.model()).isEqualTo("claude-sonnet-5-5");
        assertThat(response.truncated()).isFalse();
        server.verify();
    }

    @Test
    @DisplayName("stop_reason=max_tokens 이면 truncated 표시")
    void success_marksTruncated() {
        server.expect(requestTo(URL)).andRespond(withSuccess(okBody("max_tokens"), MediaType.APPLICATION_JSON));

        assertThat(client.generate("s", "u").truncated()).isTrue();
    }

    // ------------------------------------------------------------ 5xx / 타임아웃 재시도

    @Test
    @DisplayName("5xx → 1회 재시도 → 두 번째 성공이면 성공 (총 2회 호출)")
    void serverError_retriesOnceThenSucceeds() {
        server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andRespond(withSuccess(okBody("end_turn"), MediaType.APPLICATION_JSON));

        assertThat(client.generate("s", "u").text()).contains("리포트");
        server.verify();
    }

    @Test
    @DisplayName("5xx 두 번 연속 → 재시도 1회 후 FAILED(SERVER). 정확히 2회만 호출")
    void serverError_failsAfterOneRetry() {
        server.expect(ExpectedCount.times(2), requestTo(URL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"type\":\"api_error\",\"message\":\"내부 응답 원문 " + KEY + "\"}}"));

        assertThatThrownBy(() -> client.generate("s", "u"))
                .isInstanceOfSatisfying(ClaudeClientException.class, e -> {
                    assertThat(e.getKind()).isEqualTo(ClaudeClientException.Kind.SERVER);
                    assertThat(e.getHttpStatus()).isEqualTo(500);
                    assertThat(e.getMessage()).contains("HTTP 500");
                    // 응답 원문·키 미포함
                    assertThat(e.getMessage()).doesNotContain(KEY).doesNotContain("내부 응답 원문");
                });
        server.verify();
    }

    @Test
    @DisplayName("529(overloaded) 같은 5xx도 재시도 대상")
    void overloaded529_isRetried() {
        server.expect(ExpectedCount.times(2), requestTo(URL)).andRespond(withRawStatus(529));

        assertThatThrownBy(() -> client.generate("s", "u"))
                .isInstanceOfSatisfying(ClaudeClientException.class,
                        e -> assertThat(e.getKind()).isEqualTo(ClaudeClientException.Kind.SERVER));
        server.verify();
    }

    @Test
    @DisplayName("타임아웃 → 1회 재시도 → 계속 타임아웃이면 FAILED(TIMEOUT). 2회 호출")
    void timeout_retriesOnceThenFails() {
        server.expect(ExpectedCount.times(2), requestTo(URL))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> client.generate("s", "u"))
                .isInstanceOfSatisfying(ClaudeClientException.class, e -> {
                    assertThat(e.getKind()).isEqualTo(ClaudeClientException.Kind.TIMEOUT);
                    assertThat(e.getMessage()).contains("시간이 초과");
                });
        server.verify();
    }

    @Test
    @DisplayName("타임아웃 후 재시도에서 성공하면 성공")
    void timeout_thenSuccess() {
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andRespond(withSuccess(okBody("end_turn"), MediaType.APPLICATION_JSON));

        assertThat(client.generate("s", "u").outputTokens()).isEqualTo(567);
        server.verify();
    }

    // ------------------------------------------------------------ 4xx 무재시도

    @Test
    @DisplayName("429 → 재시도 없이 즉시 FAILED, 사유 '잠시 후 다시 시도해 주세요' (1회만 호출)")
    void rateLimit_noRetry() {
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}"));

        assertThatThrownBy(() -> client.generate("s", "u"))
                .isInstanceOfSatisfying(ClaudeClientException.class, e -> {
                    assertThat(e.getKind()).isEqualTo(ClaudeClientException.Kind.RATE_LIMITED);
                    assertThat(e.getMessage()).isEqualTo("잠시 후 다시 시도해 주세요");
                });
        server.verify(); // once 이므로 2번 호출됐다면 여기서 실패
    }

    @Test
    @DisplayName("401 → 재시도 없이 FAILED(AUTH), 사유에 키 미포함")
    void unauthorized_noRetry_noKeyLeak() {
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key " + KEY + "\"}}"));

        assertThatThrownBy(() -> client.generate("s", "u"))
                .isInstanceOfSatisfying(ClaudeClientException.class, e -> {
                    assertThat(e.getKind()).isEqualTo(ClaudeClientException.Kind.AUTH);
                    assertThat(e.getMessage()).contains("HTTP 401").doesNotContain(KEY).doesNotContain("sk-ant");
                });
        server.verify();
    }

    @Test
    @DisplayName("400 → 재시도 없이 FAILED(BAD_REQUEST). error.type만 화이트리스트로 노출, 본문 원문은 버림")
    void badRequest_noRetry_onlyErrorType() {
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"max_tokens 비밀 상세 " + KEY + "\"}}"));

        assertThatThrownBy(() -> client.generate("s", "u"))
                .isInstanceOfSatisfying(ClaudeClientException.class, e -> {
                    assertThat(e.getKind()).isEqualTo(ClaudeClientException.Kind.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("HTTP 400", "invalid_request_error")
                            .doesNotContain("비밀 상세").doesNotContain(KEY);
                });
        server.verify();
    }

    @Test
    @DisplayName("400 본문이 JSON이 아니어도 안전하게 처리")
    void badRequest_nonJsonBody() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.TEXT_PLAIN).body("<html>oops " + KEY + "</html>"));

        assertThatThrownBy(() -> client.generate("s", "u"))
                .isInstanceOfSatisfying(ClaudeClientException.class,
                        e -> assertThat(e.getMessage()).doesNotContain(KEY).doesNotContain("html"));
    }

    // ------------------------------------------------------------ 키 미설정

    @Test
    @DisplayName("키 미설정: 호출 없이 NOT_CONFIGURED — 'ANTHROPIC_API_KEY가 설정되지 않았습니다'")
    void missingKey_noCall() {
        for (String blank : new String[]{null, "", "   "}) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer noCallServer = MockRestServiceServer.bindTo(builder).build();
            AnthropicHttpClaudeClient noKey = new AnthropicHttpClaudeClient(
                    builder.baseUrl("https://api.anthropic.com").build(), props(blank));

            assertThatThrownBy(() -> noKey.generate("s", "u"))
                    .isInstanceOfSatisfying(ClaudeClientException.class, e -> {
                        assertThat(e.getKind()).isEqualTo(ClaudeClientException.Kind.NOT_CONFIGURED);
                        assertThat(e.getMessage()).isEqualTo("ANTHROPIC_API_KEY가 설정되지 않았습니다");
                    });
            noCallServer.verify(); // 기대한 요청이 없음 — 호출이 발생했다면 AssertionError
        }
    }

    // ------------------------------------------------------------ 응답 이상

    @Test
    @DisplayName("H-1: text 블록이 비고 stop_reason=max_tokens면 MAX_TOKENS_TRUNCATED 코드로 구분 (재시도 없음)")
    void noTextBlock_maxTokens_isDistinguished() {
        server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withSuccess(
                "{\"content\":[{\"type\":\"thinking\",\"thinking\":\"\"}],\"stop_reason\":\"max_tokens\",\"usage\":{\"input_tokens\":1,\"output_tokens\":4096}}",
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.generate("s", "u"))
                .isInstanceOfSatisfying(ClaudeClientException.class, e -> {
                    assertThat(e.getKind()).isEqualTo(ClaudeClientException.Kind.MAX_TOKENS_TRUNCATED);
                    assertThat(e.getMessage()).startsWith("[MAX_TOKENS_TRUNCATED]");
                });
        server.verify();
    }

    @Test
    @DisplayName("H-1: text 블록이 비었지만 max_tokens가 아니면 EMPTY_CONTENT 코드")
    void noTextBlock_otherReason_isEmptyContent() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"content\":[],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}",
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.generate("s", "u"))
                .isInstanceOfSatisfying(ClaudeClientException.class, e -> {
                    assertThat(e.getKind()).isEqualTo(ClaudeClientException.Kind.EMPTY_CONTENT);
                    assertThat(e.getMessage()).startsWith("[EMPTY_CONTENT]");
                });
    }

    @Test
    @DisplayName("stop_reason=refusal 이면 REFUSED (재시도 없음)")
    void refusal() {
        server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withSuccess(
                "{\"content\":[],\"stop_reason\":\"refusal\",\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}",
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.generate("s", "u"))
                .isInstanceOfSatisfying(ClaudeClientException.class,
                        e -> assertThat(e.getKind()).isEqualTo(ClaudeClientException.Kind.REFUSED));
        server.verify();
    }

    @Test
    @DisplayName("본문이 JSON이 아닌 200 응답은 INVALID_RESPONSE — 원문 미포함")
    void malformedBody() {
        server.expect(requestTo(URL)).andRespond(withSuccess("not json " + KEY, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.generate("s", "u"))
                .isInstanceOfSatisfying(ClaudeClientException.class, e -> {
                    assertThat(e.getKind()).isEqualTo(ClaudeClientException.Kind.INVALID_RESPONSE);
                    assertThat(e.getMessage()).doesNotContain(KEY);
                });
    }

    // ------------------------------------------------------------ M-1 키 노출 경로

    @Test
    @DisplayName("M-1: 요청 구성 중 RuntimeException(키 원문이 든 IllegalArgumentException)은 키 없는 고정 문구로 변환된다")
    void runtimeException_isConvertedToFixedMessage_withoutKey() {
        RestClient failing = RestClient.builder().baseUrl("https://api.anthropic.com")
                .requestInterceptor((request, body, execution) -> {
                    throw new IllegalArgumentException("Illegal character(s) in message header value: " + KEY + "\n");
                }).build();
        AnthropicHttpClaudeClient c = new AnthropicHttpClaudeClient(failing, props(KEY));

        assertThatThrownBy(() -> c.generate("s", "u"))
                .isInstanceOfSatisfying(ClaudeClientException.class, e -> {
                    assertThat(e.getKind()).isEqualTo(ClaudeClientException.Kind.BAD_REQUEST);
                    assertThat(e.getMessage()).doesNotContain(KEY).doesNotContain("sk-ant").doesNotContain("Illegal");
                    assertThat(e.getCause()).isNull(); // 원인 예외(키 포함)를 체인에 남기지 않는다
                });
    }

    @Test
    @DisplayName("M-1: 키 내부에 개행이 섞이면 호출 없이 NOT_CONFIGURED (유효하지 않은 키)")
    void keyWithEmbeddedNewline_isNotConfigured_noCall() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer noCallServer = MockRestServiceServer.bindTo(builder).build();
        AnthropicHttpClaudeClient bad = new AnthropicHttpClaudeClient(
                builder.baseUrl("https://api.anthropic.com").build(), props("sk-ant-abc\ndef"));

        assertThatThrownBy(() -> bad.generate("s", "u"))
                .isInstanceOfSatisfying(ClaudeClientException.class, e -> {
                    assertThat(e.getKind()).isEqualTo(ClaudeClientException.Kind.NOT_CONFIGURED);
                    assertThat(e.getMessage()).doesNotContain("sk-ant");
                });
        noCallServer.verify();
    }

    @Test
    @DisplayName("M-1: 키 앞뒤 공백/개행은 제거되어 정상 키로 호출된다")
    void keyWithSurroundingWhitespace_isTrimmed() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer s2 = MockRestServiceServer.bindTo(builder).build();
        AnthropicHttpClaudeClient trimmed = new AnthropicHttpClaudeClient(
                builder.baseUrl("https://api.anthropic.com").build(), props("  " + KEY + "\n"));
        s2.expect(requestTo(URL)).andExpect(header("x-api-key", KEY))
                .andRespond(withSuccess(okBody("end_turn"), MediaType.APPLICATION_JSON));

        assertThat(trimmed.generate("s", "u").text()).contains("리포트");
        s2.verify();
    }
}
