package com.fabwatch.auth.repository;

import com.fabwatch.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    // TENANT: 멀티테넌트 전환 시 email + tenant_id 복합 조회로 변경
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
