package com.fabwatch.equipment.repository;

import com.fabwatch.equipment.entity.EquipmentStatusLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface EquipmentStatusLogRepository extends JpaRepository<EquipmentStatusLog, Long> {

    Page<EquipmentStatusLog> findByEquipmentIdOrderByChangedAtDesc(Long equipmentId, Pageable pageable);

    /**
     * KPI 기간 내 전이 로그: start < changedAt < end. 설비 여러 대를 한 번에 조회한다(N+1 방지).
     * 인덱스 (equipment_id, changed_at) 범위 스캔 — docs/15 §5.
     */
    @Query("""
            select new com.fabwatch.equipment.repository.StatusLogRow(l.equipment.id, l.toStatus, l.changedAt)
            from EquipmentStatusLog l
            where l.equipment.id in :equipmentIds and l.changedAt > :start and l.changedAt < :end
            order by l.changedAt asc, l.id asc
            """)
    List<StatusLogRow> findTransitionsInPeriod(@Param("equipmentIds") Collection<Long> equipmentIds,
                                               @Param("start") Instant start,
                                               @Param("end") Instant end);

    /**
     * KPI 기간 시작 시점의 상태 = start 이하 마지막 로그(설비별 1건, 동시각 동률이면 모두 반환).
     */
    @Query("""
            select new com.fabwatch.equipment.repository.StatusLogRow(l.equipment.id, l.toStatus, l.changedAt)
            from EquipmentStatusLog l
            where l.equipment.id in :equipmentIds
              and l.changedAt = (select max(l2.changedAt) from EquipmentStatusLog l2
                                 where l2.equipment.id = l.equipment.id and l2.changedAt <= :start)
            order by l.changedAt asc, l.id asc
            """)
    List<StatusLogRow> findStateAtPeriodStart(@Param("equipmentIds") Collection<Long> equipmentIds,
                                              @Param("start") Instant start);
}
