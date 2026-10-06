package com.fabwatch.auth.service;

import com.fabwatch.auth.dto.UserLookupResponse;
import com.fabwatch.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 로그인한 모든 역할이 쓰는 "이름만 노출" 사용자 조회 (점검 이력 작업자 필터 등).
 * 다른 도메인에 노출하는 {@link UserQueryService}와 달리 auth 컨트롤러 전용이다.
 */
@Service
@RequiredArgsConstructor
public class UserLookupService {

    private final UserRepository userRepository;

    /**
     * 활성(enabled) 사용자만 이름순으로 반환한다. soft delete 사용자는 엔티티의 @SQLRestriction으로 이미 제외된다.
     * SCALE: 현재 규모(소수 계정)에서는 전체 반환 — 사용자가 수백 명을 넘으면 검색어(q)+페이징으로 전환한다.
     */
    @Transactional(readOnly = true)
    public List<UserLookupResponse> lookupActiveUsers() {
        // TENANT: 멀티테넌트 전환 시 현재 tenant_id 범위로 한정
        return userRepository.findByEnabledTrueOrderByNameAscIdAsc().stream()
                .map(UserLookupResponse::from)
                .toList();
    }
}
