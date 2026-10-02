package com.fabwatch.aireport.repository;

import com.fabwatch.aireport.entity.AiReport;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AiReportRepository extends JpaRepository<AiReport, Long>, JpaSpecificationExecutor<AiReport> {

    /** 같은 알람에 이미 생성 중인 리포트가 있는지 (409 ALREADY_GENERATING). excludeId = 재시도 대상 자신 제외 */
    boolean existsByAlarmIdAndStatusAndIdNot(Long alarmId, AiReport.Status status, Long excludeId);

    boolean existsByInspectionIdAndStatusAndIdNot(Long inspectionId, AiReport.Status status, Long excludeId);

    /**
     * 행 잠금(SELECT ... FOR UPDATE) 조회 — PUT/confirm/retry/결과 저장/복구가 같은 리포트의 '상태 검증 ~ 저장'을
     * 직렬화한다. 반드시 트랜잭션 안에서, 상태를 읽기 전에 첫 접근으로 호출할 것 (M-3: CONFIRMED 이후 final_content 덮어쓰기 방지).
     * 스키마 변경(@Version) 없이 해결하기 위한 선택 — 기존 행의 version이 NULL이 되는 문제를 피한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AiReport r where r.id = :id")
    Optional<AiReport> findByIdForUpdate(@Param("id") Long id);

    /** 서버 재시작/시간 초과로 GENERATING에 갇힌 건 복구용 */
    List<AiReport> findByStatusAndUpdatedAtBefore(AiReport.Status status, Instant cutoff);
}
