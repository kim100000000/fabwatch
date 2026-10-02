package com.fabwatch.inspection.service;

import com.fabwatch.common.event.PmOverdueEvent;
import com.fabwatch.inspection.entity.PmSchedule;
import com.fabwatch.inspection.repository.PmScheduleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** PM 지연 이벤트 발행 단위 테스트 (docs/12 PM-5, PM-6) — 같은 스케줄에 대해 정확히 1회만 발행되어야 한다. */
@ExtendWith(MockitoExtension.class)
class PmOverdueServiceTest {

    @Mock
    private PmScheduleRepository pmScheduleRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private PmOverdueService service;

    private final Instant now = Instant.parse("2026-07-20T00:00:00Z");

    private PmSchedule schedule(Instant nextDue) {
        return PmSchedule.builder().equipmentId(11L).cycleType(PmSchedule.CycleType.WEEKLY).cycleValue(4)
                .lastDoneAt(nextDue.minus(7, ChronoUnit.DAYS)).nextDueAt(nextDue).overdueAlarmSent(false).build();
    }

    /** 실제 쿼리 조건(next_due < now-3일 AND 미발송)을 흉내내는 가짜 저장소 */
    private void stubRepository(List<PmSchedule> all) {
        given(pmScheduleRepository.findByNextDueAtBeforeAndOverdueAlarmSentFalse(any())).willAnswer(invocation -> {
            Instant threshold = invocation.getArgument(0);
            return all.stream()
                    .filter(s -> s.getNextDueAt().isBefore(threshold) && !s.isOverdueAlarmSent())
                    .toList();
        });
    }

    @Test
    @DisplayName("PM-5 예정일+3일 초과 & 미발송 → PmOverdueEvent 발행, overdue_alarm_sent=true")
    void publishesOnce() {
        PmSchedule overdue = schedule(now.minus(5, ChronoUnit.DAYS));
        stubRepository(new ArrayList<>(List.of(overdue)));

        int published = service.publishOverdueEvents(now);

        assertThat(published).isEqualTo(1);
        assertThat(overdue.isOverdueAlarmSent()).isTrue();
        ArgumentCaptor<PmOverdueEvent> captor = ArgumentCaptor.forClass(PmOverdueEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().equipmentId()).isEqualTo(11L);
        assertThat(captor.getValue().overdueDays()).isEqualTo(5);
        assertThat(captor.getValue().nextDueAt()).isEqualTo(overdue.getNextDueAt());
    }

    @Test
    @DisplayName("PM-6 이미 발송된 스케줄은 몇 번을 돌려도 재발행하지 않는다 (중복 방지)")
    void doesNotRepublish() {
        PmSchedule overdue = schedule(now.minus(5, ChronoUnit.DAYS));
        stubRepository(new ArrayList<>(List.of(overdue)));

        service.publishOverdueEvents(now);
        service.publishOverdueEvents(now.plus(1, ChronoUnit.HOURS));
        service.publishOverdueEvents(now.plus(2, ChronoUnit.DAYS));

        verify(eventPublisher, times(1)).publishEvent(any(PmOverdueEvent.class));
    }

    @Test
    @DisplayName("3일 이내 지연(OVERDUE지만 알람 전)·정상 스케줄은 발행하지 않는다")
    void ignoresWithinGrace() {
        stubRepository(new ArrayList<>(List.of(
                schedule(now.minus(2, ChronoUnit.DAYS)),   // OVERDUE 2일 — 아직 알람 아님
                schedule(now.minus(3, ChronoUnit.DAYS)),   // 정확히 3일 — 초과 아님
                schedule(now.plus(1, ChronoUnit.DAYS)))));  // 정상

        assertThat(service.publishOverdueEvents(now)).isZero();
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("PM 수행(markDone) 후 플래그가 초기화되면 다음 지연 때 다시 1회 발행된다")
    void republishesAfterPmDone() {
        PmSchedule schedule = schedule(now.minus(5, ChronoUnit.DAYS));
        stubRepository(new ArrayList<>(List.of(schedule)));
        service.publishOverdueEvents(now);

        // PM 수행 → 다음 예정일이 다시 지나 3일 초과
        Instant doneAt = now.plus(1, ChronoUnit.DAYS);
        schedule.markDone(doneAt, doneAt.plus(7, ChronoUnit.DAYS));
        assertThat(schedule.isOverdueAlarmSent()).isFalse();
        service.publishOverdueEvents(doneAt.plus(7 + 4, ChronoUnit.DAYS));

        verify(eventPublisher, times(2)).publishEvent(any(PmOverdueEvent.class));
    }
}
