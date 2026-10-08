package com.fabwatch.inspection.service;

import com.fabwatch.common.event.PmOverdueEvent;
import com.fabwatch.inspection.entity.PmSchedule;
import com.fabwatch.inspection.repository.PmScheduleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * PM 지연 알람 발행 (docs/03 F-3.3 — OVERDUE 3일 초과 시 MAJOR "PM 지연", docs/12 PM-5/PM-6).
 *
 * 스케줄러(PmOverdueScheduler)는 시각만 넘기고, 판정·발행은 여기서 한다(고정 시각으로 단위 테스트 가능).
 * 알람 생성은 alarm 도메인이 PmOverdueEvent를 구독해 수행한다 — 직접 호출하지 않는다.
 *
 * 스케줄 단위 격리 (안정성 감사 M-11): 스케줄마다 **별도 새 트랜잭션**에서 "최신 상태 재조회 → 이벤트 발행 → overdue_alarm_sent=true"를
 * 처리한다. 한 건의 알람 생성이 실패하면 그 스케줄의 트랜잭션(알람+플래그)만 롤백되어 다음 주기에 재시도되고,
 * 같은 배치의 다른 스케줄은 영향 없이 처리된다. (예전엔 한 트랜잭션이라 한 건의 실패가 전체를 롤백해
 * 같은 건이 매시 실패하며 뒤의 정상 스케줄이 영원히 처리되지 않았다.) 실패는 스케줄 ID와 예외 클래스만 WARN으로 남긴다.
 * 이벤트 리스너(AlarmService.onPmOverdue)는 발행 트랜잭션에 동기로 참여하므로 "알람 실패 ⇒ 플래그 롤백" 불변식은 그대로다.
 */
@Slf4j
@Service
public class PmOverdueService {

    private final PmScheduleRepository pmScheduleRepository;
    private final ApplicationEventPublisher eventPublisher;
    /** 스케줄 1건 처리용 — 호출 측 트랜잭션이 있더라도 건별로 독립 커밋/롤백 */
    private final TransactionTemplate perScheduleTransaction;

    public PmOverdueService(PmScheduleRepository pmScheduleRepository,
                            ApplicationEventPublisher eventPublisher,
                            PlatformTransactionManager transactionManager) {
        this.pmScheduleRepository = pmScheduleRepository;
        this.eventPublisher = eventPublisher;
        this.perScheduleTransaction = new TransactionTemplate(transactionManager);
        this.perScheduleTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** @return 이번 호출에서 이벤트를 발행·커밋한 스케줄 수 (실패한 건은 제외) */
    public int publishOverdueEvents(Instant now) {
        Instant threshold = PmScheduleCalculator.alarmDueThreshold(now);
        // 대상 목록은 ID만 쓴다 — 실제 처리는 건별 트랜잭션에서 최신 상태를 다시 읽는다(그 사이 PM 수행·주기 변경 반영)
        List<Long> targetIds = pmScheduleRepository.findByNextDueAtBeforeAndOverdueAlarmSentFalse(threshold)
                .stream().map(PmSchedule::getId).toList();

        int published = 0;
        for (Long scheduleId : targetIds) {
            try {
                if (Boolean.TRUE.equals(perScheduleTransaction.execute(status -> publishOne(scheduleId, now, threshold)))) {
                    published++;
                }
            } catch (RuntimeException e) {
                // 이 스케줄의 알람+플래그는 롤백됨 — 다음 주기에 재시도. 다른 스케줄은 계속 처리한다.
                log.warn("PM 지연 이벤트 처리 실패(다음 주기 재시도): scheduleId={}, 예외={}",
                        scheduleId, e.getClass().getSimpleName());
            }
        }
        return published;
    }

    /** 스케줄 1건 처리. 재조회 결과 더 이상 대상이 아니면(그 사이 PM 수행·삭제) 건너뛴다. */
    private boolean publishOne(Long scheduleId, Instant now, Instant threshold) {
        PmSchedule schedule = pmScheduleRepository.findById(scheduleId).orElse(null);
        if (schedule == null || schedule.isOverdueAlarmSent() || !schedule.getNextDueAt().isBefore(threshold)) {
            return false;
        }
        eventPublisher.publishEvent(new PmOverdueEvent(
                schedule.getId(), schedule.getEquipmentId(), schedule.getNextDueAt(),
                PmScheduleCalculator.overdueDays(schedule.getNextDueAt(), now), now));
        schedule.markOverdueAlarmSent();
        log.info("PM 지연 이벤트 발행: scheduleId={}, equipmentId={}, nextDueAt={}",
                schedule.getId(), schedule.getEquipmentId(), schedule.getNextDueAt());
        return true;
    }
}
