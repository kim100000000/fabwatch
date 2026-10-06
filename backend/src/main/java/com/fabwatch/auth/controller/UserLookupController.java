package com.fabwatch.auth.controller;

import com.fabwatch.auth.dto.UserLookupResponse;
import com.fabwatch.auth.service.UserLookupService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 사용자 경량 조회 API (docs/06 §1).
 * 관리자 전용 사용자 관리(/admin/users)와 분리된, 이름만 노출하는 조회다 — 로그인한 전체 역할 허용
 * (SecurityConfig의 anyRequest().authenticated()가 토큰 없음 401을 처리한다).
 * 경로 /users/lookup은 정적 경로이며 현재 /users/{id} 류 매핑은 존재하지 않는다.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserLookupController {

    private final UserLookupService userLookupService;

    /** 로그인 필요(전체 역할) — 단순 배열(PageResponse 아님), 이름순 */
    @GetMapping("/lookup")
    public List<UserLookupResponse> lookup() {
        return userLookupService.lookupActiveUsers();
    }
}
