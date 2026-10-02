package com.fabwatch.inspection.service;

import com.fabwatch.common.event.PmOverdueEvent;
import com.fabwatch.inspection.entity.PmSchedule;
import com.fabwatch.inspection.repository.PmScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * PM 지연 알람 발행 (docs/03 F-3.3 — OVERDUE 3일 초과 시 MAJOR "PM 지연", docs/12 PM-5/PM-6).
 *
 * 스케줄러(PmOverdueScheduler)는 시각만 넘기고, 판정·발행은 여기서 한다(고정 시각으로 단위 테스트 가능).
 * 알람 생성은 alarm 도메인이 PmOverdueEvent를 구독해 수행한다 — 직접 호출하지 않는다.
 * 이벤트 발행과 overdue_alarm_sent=true 는 한 트랜잭션: 알람 생성이 실패하면 플래그도 롤백되어 다음 주기에 재시도된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmOverdueService {

    private final PmScheduleRepository pmScheduleRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** @return 이번 호출에서 발행한 이벤트 수 */
    @Transactional
    public int publishOverdueEvents(Instant now) {
        List<PmSchedule> targets = pmScheduleRepository
                .findByNextDueAtBeforeAndOverdueAlarmSentFalse(PmScheduleCalculator.alarmDueThreshold(now));
        for (PmSchedule schedule : targets) {
            eventPublisher.publishEvent(new PmOverdueEvent(
                    schedule.getId(), schedule.getEquipmentId(), schedule.getNextDueAt(),
                    PmScheduleCalculator.overdueDays(schedule.getNextDueAt(), now), now));
            schedule.markOverdueAlarmSent();
            log.info("PM 지연 이벤트 발행: scheduleId={}, equipmentId={}, nextDueAt={}",
                    schedule.getId(), schedule.getEquipmentId(), schedule.getNextDueAt());
        }
        return targets.size();
    }
}
