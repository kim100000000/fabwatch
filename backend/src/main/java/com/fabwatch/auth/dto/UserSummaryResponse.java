package com.fabwatch.auth.dto;

import com.fabwatch.auth.entity.User;

/** 로그인 응답에 포함되는 사용자 요약 — { id, name, role } (docs/06 §1) */
public record UserSummaryResponse(Long id, String name, String role) {

    public static UserSummaryResponse from(User user) {
        return new UserSummaryResponse(user.getId(), user.getName(), user.getRole().name());
    }
}
