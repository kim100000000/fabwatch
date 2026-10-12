package com.fabwatch.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * prod와 같은 forward-headers-strategy=native 에서 레이트 리밋 키(클라이언트 IP)가 올바르게 잡히는지 — 실제 Tomcat으로 검증.
 * 테스트 클라이언트(127.0.0.1)는 내부 프록시 대역이라 XFF를 신뢰하고, 오른쪽의 마지막 주소(= 프록시가 붙인 실제 클라이언트)를 쓴다.
 * 왼쪽에 클라이언트가 끼워 넣은 위조값은 무시돼야 한다 (Caddy가 덮어쓰든 덧붙이든 안전).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.forward-headers-strategy=native",
        "fabwatch.security.rate-limit.auth-per-minute=3",
        "fabwatch.test.context-isolation=forwarded-native"
})
@ActiveProfiles("test")
class ForwardedHeadersRateLimitTest {

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    private int login(String xForwardedFor) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"nobody@x.example\",\"password\":\"whatever123\"}"));
        if (xForwardedFor != null) {
            builder.header("X-Forwarded-For", xForwardedFor);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    @DisplayName("왼쪽 위조 XFF를 바꿔도 같은 실제 클라이언트(오른쪽 값)는 같은 버킷 — 429, 다른 클라이언트는 독립")
    void 위조_XFF_무시() throws Exception {
        // 실제 클라이언트 203.0.113.7 (프록시가 덧붙인 오른쪽 값), 왼쪽은 공격자가 매번 바꾸는 위조값
        assertThat(login("10.1.1.1, 203.0.113.7")).isEqualTo(401);
        assertThat(login("10.2.2.2, 203.0.113.7")).isEqualTo(401);
        assertThat(login("10.3.3.3, 203.0.113.7")).isEqualTo(401);
        assertThat(login("10.4.4.4, 203.0.113.7")).as("위조값을 바꿔도 우회되지 않는다").isEqualTo(429);
        // 덮어쓰기형 프록시(XFF에 클라이언트 IP 하나만) — 같은 클라이언트면 같은 버킷
        assertThat(login("203.0.113.7")).isEqualTo(429);
        // 다른 실제 클라이언트는 영향 없음
        assertThat(login("203.0.113.99")).isEqualTo(401);
    }
}
