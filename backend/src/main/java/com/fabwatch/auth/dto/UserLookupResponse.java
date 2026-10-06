package com.fabwatch.auth.dto;

import com.fabwatch.auth.entity.User;

/**
 * 사용자 경량 조회 응답 — { id, name, role } 3개 필드만 (docs/06 §1 GET /users/lookup).
 * 개인정보 최소화: 이메일·비밀번호·토큰·잠금 상태 등은 필드 자체를 두지 않는다.
 * 필드를 추가하려면 docs/11 개인정보 원칙 검토가 먼저다 (JSON 키 집합 테스트가 막아 준다).
 */
public record UserLookupResponse(Long id, String name, String role) {

    public static UserLookupResponse from(User user) {
        return new UserLookupResponse(user.getId(), user.getName(), user.getRole().name());
    }
}
