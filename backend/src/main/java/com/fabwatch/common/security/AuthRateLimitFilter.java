package com.fabwatch.common.security;

import com.fabwatch.common.config.SecurityProperties;
import com.fabwatch.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;

/**
 * 로그인·리프레시 IP별 레이트 리밋 (docs/11 §4, 보안 감사 M-2·M-3).
 * - 대상: POST /api/v1/auth/login, POST /api/v1/auth/refresh — IP × 엔드포인트별로 분당 N회(기본 30)
 * - 초과 시 429 RATE_LIMITED + Retry-After 헤더 (공통 에러 포맷)
 * - 클라이언트 IP는 request.getRemoteAddr(). 프록시 뒤에서 X-Forwarded-For를 쓰려면
 *   server.forward-headers-strategy=framework 설정이 필요하다(설정 없이 헤더를 직접 믿으면 IP 위조가 가능해 읽지 않는다).
 *
 * // SCALE: 인메모리 단일 인스턴스 전제 (SlidingWindowRateLimiter 참고)
 */
@Slf4j
@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> LIMITED_PATHS = Set.of("/api/v1/auth/login", "/api/v1/auth/refresh");

    private final SlidingWindowRateLimiter limiter;
    private final ErrorResponseWriter errorWriter;

    @Autowired
    public AuthRateLimitFilter(SecurityProperties properties, ErrorResponseWriter errorWriter) {
        this(properties, errorWriter, Clock.systemUTC());
    }

    /** 테스트에서 시계를 주입하기 위한 생성자 */
    AuthRateLimitFilter(SecurityProperties properties, ErrorResponseWriter errorWriter, Clock clock) {
        this.limiter = new SlidingWindowRateLimiter(properties.rateLimit().authPerMinute(), Duration.ofMinutes(1), clock);
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !LIMITED_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getRemoteAddr() + "|" + request.getRequestURI();
        SlidingWindowRateLimiter.Decision decision = limiter.tryAcquire(key);
        if (!decision.allowed()) {
            // IP는 로그에 남기지 않는다(개인정보 최소화) — 경로만 기록
            log.warn("레이트 리밋 초과: path={}", request.getRequestURI());
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            errorWriter.write(response, ErrorCode.RATE_LIMITED);
            return;
        }
        chain.doFilter(request, response);
    }
}
