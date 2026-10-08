package com.fabwatch.common.security;

import com.fabwatch.common.config.SecurityProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 레이트 리밋 필터(429)와 본문 크기 필터(413)의 서블릿 단계 동작 */
class RequestFiltersTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ErrorResponseWriter writer = new ErrorResponseWriter(objectMapper);

    private static SecurityProperties props(Integer perMinute, Long maxBytes) {
        return new SecurityProperties(new SecurityProperties.RateLimit(perMinute), maxBytes);
    }

    private static MockHttpServletRequest post(String uri, String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    // ------------------------------------------------------------ 레이트 리밋

    @Test
    @DisplayName("한도 초과 시 429 RATE_LIMITED + Retry-After + 공통 에러 포맷, 컨트롤러까지 가지 않는다")
    void 레이트_리밋_429() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(props(2, null), writer);

        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse ok = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(post("/api/v1/auth/login", "10.0.0.1"), ok, chain);
            assertThat(ok.getStatus()).isEqualTo(200);
            assertThat(chain.getRequest()).isNotNull(); // 통과
        }

        MockHttpServletResponse denied = new MockHttpServletResponse();
        MockFilterChain blockedChain = new MockFilterChain();
        filter.doFilter(post("/api/v1/auth/login", "10.0.0.1"), denied, blockedChain);

        assertThat(denied.getStatus()).isEqualTo(429);
        assertThat(blockedChain.getRequest()).isNull(); // 체인 미호출
        assertThat(Integer.parseInt(denied.getHeader("Retry-After"))).isBetween(1, 60);
        assertThat(denied.getContentType()).startsWith("application/json");
        JsonNode body = objectMapper.readTree(denied.getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.get("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(body.get("message").asText()).isNotBlank();
        assertThat(body.get("timestamp").asText()).isNotBlank();
    }

    @Test
    @DisplayName("IP별·엔드포인트별로 따로 센다 — 다른 IP, 같은 IP의 /refresh는 영향 없음")
    void 레이트_리밋_분리() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(props(1, null), writer);

        assertThat(status(filter, post("/api/v1/auth/login", "10.0.0.1"))).isEqualTo(200);
        assertThat(status(filter, post("/api/v1/auth/login", "10.0.0.1"))).isEqualTo(429);
        assertThat(status(filter, post("/api/v1/auth/login", "10.0.0.2"))).isEqualTo(200);
        assertThat(status(filter, post("/api/v1/auth/refresh", "10.0.0.1"))).isEqualTo(200);
    }

    @Test
    @DisplayName("대상이 아닌 경로·메서드는 제한하지 않는다")
    void 레이트_리밋_대상_외() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(props(1, null), writer);
        for (int i = 0; i < 5; i++) {
            assertThat(status(filter, new MockHttpServletRequest("GET", "/api/v1/auth/login"))).isEqualTo(200);
            assertThat(status(filter, post("/api/v1/equipments", "10.0.0.1"))).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("X-Forwarded-For 헤더는 믿지 않는다 — 헤더를 바꿔도 같은 원격 주소면 같은 버킷")
    void 포워드_헤더_무시() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(props(1, null), writer);
        MockHttpServletRequest first = post("/api/v1/auth/login", "10.0.0.1");
        first.addHeader("X-Forwarded-For", "1.1.1.1");
        MockHttpServletRequest second = post("/api/v1/auth/login", "10.0.0.1");
        second.addHeader("X-Forwarded-For", "2.2.2.2");

        assertThat(status(filter, first)).isEqualTo(200);
        assertThat(status(filter, second)).isEqualTo(429);
    }

    @Test
    @DisplayName("속성 기본값: 분당 30회 / 본문 1MB")
    void 기본값() {
        SecurityProperties defaults = new SecurityProperties(null, null);
        assertThat(defaults.rateLimit().authPerMinute()).isEqualTo(30);
        assertThat(defaults.maxRequestBytes()).isEqualTo(1024 * 1024);
    }

    // ------------------------------------------------------------ 본문 크기

    @Test
    @DisplayName("Content-Length가 상한을 넘으면 본문을 읽지 않고 413 PAYLOAD_TOO_LARGE")
    void 본문_상한_413() throws Exception {
        RequestSizeLimitFilter filter = new RequestSizeLimitFilter(props(null, 100L), writer);
        MockHttpServletRequest request = post("/api/v1/auth/login", "10.0.0.1");
        request.setContent(new byte[101]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).isNull();
        JsonNode body = objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.get("code").asText()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(body.has("timestamp")).isTrue();
    }

    @Test
    @DisplayName("상한 이내(경계 포함)는 통과")
    void 본문_상한_이내() throws Exception {
        RequestSizeLimitFilter filter = new RequestSizeLimitFilter(props(null, 100L), writer);
        MockHttpServletRequest request = post("/api/v1/auth/login", "10.0.0.1");
        request.setContent(new byte[100]);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("Content-Length 없는 청크 전송 — 읽는 도중 누적량이 상한을 넘으면 PayloadTooLargeException")
    void 청크_전송_상한() throws Exception {
        RequestSizeLimitFilter filter = new RequestSizeLimitFilter(props(null, 100L), writer);
        MockHttpServletRequest raw = post("/api/v1/auth/login", "10.0.0.1");
        raw.setContent(new byte[250]);
        HttpServletRequest chunked = new HttpServletRequestWrapper(raw) {
            @Override
            public long getContentLengthLong() {
                return -1; // 길이 미선언
            }
        };

        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response)
                    throws IOException, jakarta.servlet.ServletException {
                try (InputStream in = request.getInputStream()) {
                    assertThatThrownBy(in::readAllBytes).isInstanceOf(RequestSizeLimitFilter.PayloadTooLargeException.class);
                }
                super.doFilter(request, response);
            }
        };
        filter.doFilter(chunked, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("청크 전송이라도 상한 이내면 끝까지 읽힌다")
    void 청크_전송_상한_이내() throws Exception {
        RequestSizeLimitFilter filter = new RequestSizeLimitFilter(props(null, 100L), writer);
        MockHttpServletRequest raw = post("/api/v1/auth/login", "10.0.0.1");
        raw.setContent(new byte[100]);
        HttpServletRequest chunked = new HttpServletRequestWrapper(raw) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        int[] read = new int[1];
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response)
                    throws IOException, jakarta.servlet.ServletException {
                read[0] = request.getInputStream().readAllBytes().length;
                super.doFilter(request, response);
            }
        };
        filter.doFilter(chunked, new MockHttpServletResponse(), chain);
        assertThat(read[0]).isEqualTo(100);
    }

    private int status(AuthRateLimitFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }
}
