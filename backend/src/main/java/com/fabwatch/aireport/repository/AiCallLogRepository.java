package com.fabwatch.aireport.repository;

import com.fabwatch.aireport.entity.AiCallLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AiCallLogRepository extends JpaRepository<AiCallLog, Long> {

    /** 일일 쿼터 집계 — 구간 [from, to) 의 호출 시도 수 (mock 포함, 성공/실패/진행 중 전부) */
    long countByRequestedAtGreaterThanEqualAndRequestedAtLessThan(Instant from, Instant to);

    /** 복구 로직이 진행 중 로그를 닫을 때 사용 */
    List<AiCallLog> findByReportIdAndSuccessIsNull(Long reportId);

    Optional<AiCallLog> findFirstByReportIdOrderByIdDesc(Long reportId);
}
