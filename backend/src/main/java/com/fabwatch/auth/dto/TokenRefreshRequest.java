package com.fabwatch.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /api/v1/auth/refresh 요청 (docs/06 §1) */
public record TokenRefreshRequest(@NotBlank @Size(max = 512) String refreshToken) {
}
