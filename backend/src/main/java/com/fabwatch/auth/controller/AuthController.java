package com.fabwatch.auth.controller;

import com.fabwatch.auth.dto.LoginRequest;
import com.fabwatch.auth.dto.LogoutRequest;
import com.fabwatch.auth.dto.TokenRefreshRequest;
import com.fabwatch.auth.dto.TokenResponse;
import com.fabwatch.auth.service.AuthService;
import com.fabwatch.common.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 인증 API (docs/06 §1) */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 공개 — 이메일+비밀번호 로그인 */
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    /** 공개 — Refresh 회전 후 새 토큰 쌍 발급 */
    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody TokenRefreshRequest request) {
        return authService.refresh(request);
    }

    /** 로그인 필요 — 제시된 Refresh의 세션만 무효화(본문 생략 시 본인의 모든 세션) */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody(required = false) LogoutRequest request) {
        authService.logout(SecurityUtils.currentUserId(), request == null ? null : request.refreshToken());
        return ResponseEntity.noContent().build();
    }
}
