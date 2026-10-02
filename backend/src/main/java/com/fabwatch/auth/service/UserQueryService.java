package com.fabwatch.auth.service;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * auth 도메인이 다른 도메인에 노출하는 유일한 조회 통로.
 * 다른 도메인은 UserRepository/User 엔티티를 직접 참조하지 않고 이 인터페이스만 사용한다
 * (도메인 간 직접 참조 금지 — CLAUDE.md).
 */
public interface UserQueryService {

    /** 사용자 이름 조회. 없거나 삭제된 사용자면 empty. */
    Optional<String> findNameById(Long userId);

    /** 여러 사용자 이름을 한 번에 조회 (목록 API의 N+1 방지). key = userId */
    Map<Long, String> findNamesByIds(Collection<Long> userIds);

    /**
     * 여러 사용자의 역할명(ADMIN/ENGINEER/TECHNICIAN)을 한 번에 조회. key = userId.
     * AI 프롬프트처럼 이름·이메일을 내보내면 안 되는 곳에서 "작업자 역할"만 필요할 때 쓴다.
     */
    Map<Long, String> findRolesByIds(Collection<Long> userIds);

    /** 존재하는(삭제되지 않은) 사용자 여부 */
    boolean existsById(Long userId);
}
