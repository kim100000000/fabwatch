package com.fabwatch.auth.dto;

import jakarta.validation.constraints.Size;

/**
 * POST /api/v1/auth/logout 요청 (docs/06 §1). 본문은 선택이다.
 * refreshToken을 보내면 그 토큰의 세션만 폐기하고, 생략하면 해당 사용자의 모든 세션을 폐기한다(구형 클라이언트 호환).
 */
public record LogoutRequest(@Size(max = 512) String refreshToken) {
}
