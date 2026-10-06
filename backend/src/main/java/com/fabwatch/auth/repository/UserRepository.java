package com.fabwatch.auth.repository;

import com.fabwatch.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    // TENANT: 멀티테넌트 전환 시 email + tenant_id 복합 조회로 변경
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** 활성 사용자 이름순 조회 (이름 동률은 id순으로 결정적 정렬). soft delete는 @SQLRestriction이 제외. */
    // TENANT: 멀티테넌트 전환 시 tenant_id 조건 추가
    List<User> findByEnabledTrueOrderByNameAscIdAsc();
}
