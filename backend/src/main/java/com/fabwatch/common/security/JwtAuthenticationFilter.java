package com.fabwatch.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authorization: Bearer 토큰을 검증해 SecurityContext에 인증을 채운다 (docs/11 §2, P-4).
 * 토큰 만료는 요청 속성에 표시해 EntryPoint가 TOKEN_EXPIRED로 응답하게 한다 (docs/03 F-1 예외 표).
 * 로그에 토큰 원문을 남기지 않는다 (docs/11 P-5).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String EXPIRED_ATTRIBUTE = "fabwatch.token.expired";
    private static final String BEARER_PREFIX = "Bearer ";
    /** 쿼리 파라미터 토큰을 허용하는 유일한 경로 (docs/11 §4 — SSE 엔드포인트 한정) */
    private static final String SSE_PATH_PREFIX = "/api/v1/stream/";

    private final JwtTokenProvider tokenProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null) {
            try {
                Claims claims = tokenProvider.parseAccessToken(token);
                AuthPrincipal principal = tokenProvider.toPrincipal(claims);
                var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + principal.role()));
                var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (ExpiredJwtException e) {
                request.setAttribute(EXPIRED_ATTRIBUTE, Boolean.TRUE);
                log.debug("만료된 액세스 토큰 요청: {}", request.getRequestURI());
            } catch (JwtException | IllegalArgumentException e) {
                log.debug("유효하지 않은 토큰 요청: {}", request.getRequestURI());
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * 기본은 Authorization 헤더. 단 SSE 엔드포인트(/api/v1/stream/**)에 한해 쿼리 파라미터 token을 허용한다
     * — EventSource가 커스텀 헤더를 붙일 수 없기 때문 (docs/11 §4).
     *
     * 완화책: ① 이 경로에서만 허용 ② 연결 시 1회 검증 ③ Access 토큰 30분 만료로 노출 창 제한
     * ④ 로그에는 request.getRequestURI()(쿼리스트링 미포함)만 남겨 token이 로그로 새지 않게 한다.
     */
    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String value = header.substring(BEARER_PREFIX.length()).trim();
            return value.isEmpty() ? null : value;
        }
        if (request.getRequestURI().startsWith(SSE_PATH_PREFIX)) {
            String value = request.getParameter("token");
            return value == null || value.isBlank() ? null : value.trim();
        }
        return null;
    }
}
