package com.fabwatch.auth.repository;

import com.fabwatch.auth.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    // TENANT: 멀티테넌트 전환 시 email + tenant_id 복합 조회로 변경
    Optional<User> findByEmail(String email);

    /**
     * 로그인 전용 — 행 잠금(SELECT ... FOR UPDATE)으로 조회한다.
     * 동시 로그인 실패 요청이 같은 실패 횟수를 읽고 덮어써 증가분이 사라지는 것을 막아, 정확히 5회째에 잠기게 한다.
     * 같은 계정의 로그인 시도는 직렬화되므로 BCrypt 병렬 폭주도 계정 단위로 제한된다.
     */
    // TENANT: 멀티테넌트 전환 시 email + tenant_id 복합 조회로 변경
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<User> findWithLockByEmail(String email);

    boolean existsByEmail(String email);

    /** 활성 사용자 이름순 조회 (이름 동률은 id순으로 결정적 정렬). soft delete는 @SQLRestriction이 제외. */
    // TENANT: 멀티테넌트 전환 시 tenant_id 조건 추가
    List<User> findByEnabledTrueOrderByNameAscIdAsc();
}
