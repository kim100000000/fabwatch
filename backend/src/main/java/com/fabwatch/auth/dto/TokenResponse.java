package com.fabwatch.auth.dto;

/**
 * 로그인/리프레시 공통 응답 — { accessToken, refreshToken, user{id,name,role} } (docs/06 §1).
 * accessTokenExpiresIn(초)은 프론트 자동 리프레시 편의를 위해 추가한 필드.
 */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        long accessTokenExpiresIn,
        UserSummaryResponse user) {
}
