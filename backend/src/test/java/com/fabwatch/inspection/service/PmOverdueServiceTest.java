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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
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
    /** 스케줄 단위 REQUIRES_NEW 트랜잭션용 — 목 매니저라 실제 트랜잭션은 없고 예외 전파/격리 흐름만 검증한다 */
    @Mock
    private PlatformTransactionManager transactionManager;

    @InjectMocks
    private PmOverdueService service;

    private final Instant now = Instant.parse("2026-07-20T00:00:00Z");

    private final AtomicLong ids = new AtomicLong(100);

    private PmSchedule schedule(Instant nextDue) {
        return schedule(11L, nextDue);
    }

    private PmSchedule schedule(long equipmentId, Instant nextDue) {
        PmSchedule schedule = PmSchedule.builder().equipmentId(equipmentId).cycleType(PmSchedule.CycleType.WEEKLY).cycleValue(4)
                .lastDoneAt(nextDue.minus(7, ChronoUnit.DAYS)).nextDueAt(nextDue).overdueAlarmSent(false).build();
        ReflectionTestUtils.setField(schedule, "id", ids.incrementAndGet());
        return schedule;
    }

    /** 실제 쿼리 조건(next_due < now-3일 AND 미발송)을 흉내내는 가짜 저장소 */
    private void stubRepository(List<PmSchedule> all) {
        // 건별 트랜잭션에서 id로 최신 상태를 다시 읽는다
        org.mockito.Mockito.lenient().when(pmScheduleRepository.findById(any())).thenAnswer(invocation -> all.stream()
                .filter(s -> s.getId().equals(invocation.getArgument(0))).findFirst());
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

    @Test
    @DisplayName("★ 스케줄 단위 격리 — 한 스케줄의 이벤트 처리가 예외를 던져도 예외는 전파되지 않고 나머지 스케줄은 처리된다 (M-11)")
    void oneFailureDoesNotBlockOthers() {
        PmSchedule poison = schedule(11L, now.minus(5, ChronoUnit.DAYS));
        PmSchedule healthy = schedule(12L, now.minus(6, ChronoUnit.DAYS));
        stubRepository(new ArrayList<>(List.of(poison, healthy)));
        doThrow(new IllegalStateException("알람 생성 실패")).when(eventPublisher)
                .publishEvent(org.mockito.ArgumentMatchers.argThat(
                        (Object event) -> event instanceof PmOverdueEvent e && e.equipmentId().equals(11L)));

        int[] published = new int[1];
        assertThatCode(() -> published[0] = service.publishOverdueEvents(now)).doesNotThrowAnyException();

        assertThat(published[0]).isEqualTo(1); // 성공한 건수만
        verify(eventPublisher, times(2)).publishEvent(any(PmOverdueEvent.class)); // 둘 다 시도했다
        assertThat(healthy.isOverdueAlarmSent()).isTrue();
        // 실패한 건은 (목 환경이라 실제 롤백은 없지만) 플래그 갱신 코드에 도달하지 못했다 → 다음 주기 재시도 대상으로 남는다
        assertThat(poison.isOverdueAlarmSent()).isFalse();
    }

    @Test
    @DisplayName("목록 조회 이후 그 사이 PM 수행/삭제로 대상에서 빠진 스케줄은 재조회 결과를 보고 건너뛴다")
    void skipsScheduleThatChangedAfterListing() {
        PmSchedule done = schedule(now.minus(5, ChronoUnit.DAYS));
        PmSchedule gone = schedule(12L, now.minus(5, ChronoUnit.DAYS));
        given(pmScheduleRepository.findByNextDueAtBeforeAndOverdueAlarmSentFalse(any())).willReturn(List.of(done, gone));
        done.markDone(now.minus(1, ChronoUnit.HOURS), now.plus(7, ChronoUnit.DAYS)); // 목록 조회 직후 PM 수행됨
        given(pmScheduleRepository.findById(done.getId())).willReturn(Optional.of(done));
        given(pmScheduleRepository.findById(gone.getId())).willReturn(Optional.empty()); // 삭제됨

        assertThat(service.publishOverdueEvents(now)).isZero();
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }
}
