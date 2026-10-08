package com.fabwatch.auth.repository;

import com.fabwatch.auth.entity.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /**
     * 갱신용 조회 — 현재 해시 또는 직전(previous) 해시와 일치하는 행을 비관적 락으로 잡는다.
     * 같은 토큰으로 병렬 갱신이 들어오면 직렬화되어 두 번째 요청은 previous 경로(유예)로 판정된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :hash or t.previousTokenHash = :hash")
    Optional<RefreshToken> findForRefresh(@Param("hash") String hash);

    /** 사용자별 세션 목록 — 오래 쓰지 않은 순(용량 초과 시 앞에서부터 삭제) */
    List<RefreshToken> findByUserIdOrderByLastUsedAtAscIdAsc(Long userId);

    /** 로그아웃 — 제시된 토큰의 행만 삭제(본인 소유일 때만). 삭제된 행 수를 돌려준다. */
    @Modifying(flushAutomatically = true)
    @Query("delete from RefreshToken t where t.userId = :userId and (t.tokenHash = :hash or t.previousTokenHash = :hash)")
    int deleteByUserIdAndHash(@Param("userId") Long userId, @Param("hash") String hash);

    @Modifying(flushAutomatically = true)
    @Query("delete from RefreshToken t where t.userId = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);

    /** 만료 행 기회적 정리(로그인 시) */
    @Modifying(flushAutomatically = true)
    @Query("delete from RefreshToken t where t.expiresAt <= :now")
    int deleteExpired(@Param("now") Instant now);

    long countByUserId(Long userId);
}
