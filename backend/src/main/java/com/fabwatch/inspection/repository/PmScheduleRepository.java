package com.fabwatch.inspection.repository;

import com.fabwatch.inspection.entity.PmSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PmScheduleRepository extends JpaRepository<PmSchedule, Long>, JpaSpecificationExecutor<PmSchedule> {

    Optional<PmSchedule> findByEquipmentId(Long equipmentId);

    /** 지연 알람 대상 — next_due_at이 기준 시각(now-3일)보다 이전이고 아직 발행하지 않은 스케줄 */
    List<PmSchedule> findByNextDueAtBeforeAndOverdueAlarmSentFalse(Instant threshold);
}
