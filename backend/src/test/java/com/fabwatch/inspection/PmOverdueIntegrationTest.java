package com.fabwatch.inspection;

import com.fabwatch.common.event.AlarmRaisedEvent;
import com.fabwatch.common.event.PmOverdueEvent;
import com.fabwatch.inspection.entity.PmSchedule;
import com.fabwatch.inspection.repository.PmScheduleRepository;
import com.fabwatch.inspection.service.PmOverdueService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PM 지연 알람 트랜잭션 경계 통합 테스트 (QA MED-2, docs/12 PM-5/PM-6).
 *
 * 증명하려는 불변식: PmOverdueEvent 리스너(AlarmService.onPmOverdue)는 발행 트랜잭션에 "동기"로 참여하므로
 * 알람 생성 단계가 실패하면 overdue_alarm_sent=true 도 함께 롤백되어 다음 주기에 재시도된다.
 * 스케줄 단위 격리(M-11): 롤백은 실패한 스케줄의 트랜잭션에만 미치고 같은 배치의 다른 스케줄은 정상 처리된다.
 * 누군가 리스너를 @TransactionalEventListener(AFTER_COMMIT)·@Async 등으로 바꾸면 (3) 케이스가 깨진다.
 *
 * 격리 전략 — 클래스 @Transactional을 쓰지 않는다(쓰면 서비스 트랜잭션이 테스트 트랜잭션에 합류해 경계 검증이 무의미).
 * 대신
 *  - 시드 데이터와 겹치지 않도록 서비스 기준 시각(now)을 2000년으로 잡아, 이 테스트가 만든 스케줄만 대상이 되게 한다
 *    (시드 스케줄의 next_due는 현재 시각 기준이라 임계값(now-3일)에 걸리지 않는다).
 *  - 테스트마다 고유 equipmentId를 쓰고 @AfterEach 에서 JdbcTemplate으로 직접 정리한다
 *    (물리 삭제 금지는 운영 코드 규칙 — 테스트 정리는 허용. soft delete 행이 남으면 unique(equipment_id)를 막기 때문).
 *  - 컨텍스트는 InspectionApiIntegrationTest 와 같은 설정을 써서 캐시를 공유한다(새 컨텍스트가 공유 H2의
 *    create-drop 으로 다른 테스트 데이터를 날리는 일을 피함). 실패 주입은 mock bean 대신 실행 중 컨텍스트의
 *    이벤트 멀티캐스터에 테스트 리스너를 임시 등록/해제하는 방식이다.
 */
@SpringBootTest(properties = "fabwatch.seed.enabled=true")
@ActiveProfiles({"local", "test"})
class PmOverdueIntegrationTest {

    private static final AtomicLong EQUIPMENT_SEQ = new AtomicLong(990_000L);
    /** 서비스에 넘기는 기준 시각 — 시드 데이터(현재 시각 기준)와 절대 겹치지 않는 과거 시점 */
    private static final Instant NOW = Instant.parse("2000-01-10T00:00:00Z");

    @Autowired
    private PmOverdueService pmOverdueService;
    @Autowired
    private PmScheduleRepository pmScheduleRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ApplicationContext applicationContext;

    private long equipmentId;

    @BeforeEach
    void setUp() {
        equipmentId = EQUIPMENT_SEQ.incrementAndGet();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM alarms WHERE equipment_id = ?", equipmentId);
        jdbc.update("DELETE FROM pm_schedules WHERE equipment_id = ?", equipmentId);
    }

    // ------------------------------------------------------------ (1)(2) 정상 경로 + 재발행 없음

    @Test
    @DisplayName("(1) 3일 초과 스케줄 → PM_OVERDUE MAJOR 알람 정확히 1건 + overdue_alarm_sent=true")
    void overdue_createsSingleAlarmAndMarksFlag() {
        createSchedule(NOW.minus(5, ChronoUnit.DAYS));

        int published = pmOverdueService.publishOverdueEvents(NOW);

        assertThat(published).isEqualTo(1);
        assertThat(overdueAlarmCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM alarms WHERE equipment_id = ? AND alarm_type = 'PM_OVERDUE' AND severity = 'MAJOR' AND status = 'OPEN'",
                Integer.class, equipmentId)).isEqualTo(1);
        assertThat(overdueAlarmSent()).isTrue();
    }

    @Test
    @DisplayName("(2) 같은 서비스를 다시 호출해도 알람이 추가되지 않는다 (재발행 없음)")
    void overdue_secondCallDoesNotCreateMoreAlarms() {
        createSchedule(NOW.minus(5, ChronoUnit.DAYS));
        pmOverdueService.publishOverdueEvents(NOW);

        int secondRun = pmOverdueService.publishOverdueEvents(NOW.plus(1, ChronoUnit.HOURS));
        int thirdRun = pmOverdueService.publishOverdueEvents(NOW.plus(2, ChronoUnit.DAYS));

        assertThat(secondRun).isZero();
        assertThat(thirdRun).isZero();
        assertThat(overdueAlarmCount()).isEqualTo(1);
        assertThat(overdueAlarmSent()).isTrue();
    }

    // ------------------------------------------------------------ (3) 롤백 증명

    @Test
    @DisplayName("(3a) PmOverdueEvent 처리 중 알람 생성 이후 단계가 예외를 던지면 → 해당 스케줄만 롤백(알람 0건, overdue_alarm_sent=false), 배치는 예외 없이 끝난다")
    void alarmStageFailure_rollsBackFlagAndAlarm() {
        createSchedule(NOW.minus(5, ChronoUnit.DAYS));

        // AlarmService.onPmOverdue 가 알람을 저장한 "뒤"에 실행되도록 나중에 등록한 리스너가 예외를 던진다.
        // 동기·같은 트랜잭션이면 이미 INSERT 된 알람과 플래그 갱신이 함께 롤백되어야 한다.
        ThrowingListener failing = new ThrowingListener(PmOverdueEvent.class);
        registerListener(failing);
        try {
            // 스케줄 단위 격리(M-11): 실패는 WARN으로만 남고 예외는 전파되지 않는다. 성공 건수는 0.
            assertThat(pmOverdueService.publishOverdueEvents(NOW)).isZero();
        } finally {
            unregisterListener(failing);
        }

        // 리스너가 실제로 호출되었고(= 알람 생성 후 단계까지 도달), 그럼에도 아무것도 남지 않아야 한다
        assertThat(failing.invocations()).isEqualTo(1);
        assertThat(overdueAlarmCount()).as("알람 생성이 롤백되어야 한다").isZero();
        assertThat(overdueAlarmSent()).as("overdue_alarm_sent 가 롤백되어야 한다").isFalse();

        // 다음 주기(장애 해소 후)에는 재시도되어 정상 생성된다
        int retried = pmOverdueService.publishOverdueEvents(NOW.plus(1, ChronoUnit.HOURS));
        assertThat(retried).isEqualTo(1);
        assertThat(overdueAlarmCount()).isEqualTo(1);
        assertThat(overdueAlarmSent()).isTrue();
    }

    @Test
    @DisplayName("(3b) 알람 서비스 내부(AlarmRaisedEvent 후속 처리)에서 예외가 나도 → 해당 스케줄만 롤백(알람 0건, overdue_alarm_sent=false)")
    void failureInsideAlarmServiceTransaction_rollsBackFlagAndAlarm() {
        createSchedule(NOW.minus(5, ChronoUnit.DAYS));

        // AlarmService.raise 가 알람을 저장하고 AlarmRaisedEvent 를 발행하는 지점 — AlarmService 자신의 트랜잭션 메서드 안쪽이다
        ThrowingListener failing = new ThrowingListener(AlarmRaisedEvent.class);
        registerListener(failing);
        try {
            assertThat(pmOverdueService.publishOverdueEvents(NOW)).isZero();
        } finally {
            unregisterListener(failing);
        }

        assertThat(failing.invocations()).isEqualTo(1);
        assertThat(overdueAlarmCount()).isZero();
        assertThat(overdueAlarmSent()).isFalse();
    }

    // ------------------------------------------------------------ (3c) 스케줄 단위 격리

    @Test
    @DisplayName("(3c) ★ 한 스케줄의 알람 생성이 실패해도 같은 배치의 다른 스케줄은 처리된다 — 실패 건만 롤백되어 다음 주기에 재시도")
    void oneFailingScheduleDoesNotBlockTheOthers() {
        long poisonEquipment = equipmentId;
        long healthyEquipment = EQUIPMENT_SEQ.incrementAndGet();
        try {
            createSchedule(poisonEquipment, NOW.minus(5, ChronoUnit.DAYS));
            createSchedule(healthyEquipment, NOW.minus(6, ChronoUnit.DAYS));

            // poison 설비의 이벤트에서만 예외를 던진다
            ThrowingListener failing = new ThrowingListener(PmOverdueEvent.class,
                    payload -> ((PmOverdueEvent) payload).equipmentId().equals(poisonEquipment));
            registerListener(failing);
            try {
                assertThat(pmOverdueService.publishOverdueEvents(NOW)).as("성공한 스케줄 수").isEqualTo(1);
            } finally {
                unregisterListener(failing);
            }

            assertThat(failing.invocations()).isEqualTo(1);
            assertThat(overdueAlarmCount(poisonEquipment)).as("실패 건은 알람·플래그 롤백").isZero();
            assertThat(overdueAlarmSent(poisonEquipment)).isFalse();
            assertThat(overdueAlarmCount(healthyEquipment)).as("정상 건은 처리됨").isEqualTo(1);
            assertThat(overdueAlarmSent(healthyEquipment)).isTrue();

            // 장애 해소 후 다음 주기에는 실패했던 건도 처리되고, 정상 건은 재발행되지 않는다
            assertThat(pmOverdueService.publishOverdueEvents(NOW.plus(1, ChronoUnit.HOURS))).isEqualTo(1);
            assertThat(overdueAlarmCount(poisonEquipment)).isEqualTo(1);
            assertThat(overdueAlarmCount(healthyEquipment)).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM alarms WHERE equipment_id = ?", healthyEquipment);
            jdbc.update("DELETE FROM pm_schedules WHERE equipment_id = ?", healthyEquipment);
        }
    }

    @Test
    @DisplayName("(3d) 알람이 해소(RESOLVED)된 뒤 스케줄이 다시 3일 초과되면 새 PM_OVERDUE 알람이 생성된다 (중복 억제에 안 걸림)")
    void newOverdueAlarmAfterResolve() {
        createSchedule(NOW.minus(5, ChronoUnit.DAYS));
        pmOverdueService.publishOverdueEvents(NOW);
        assertThat(overdueAlarmCount()).isEqualTo(1);

        // PM 수행으로 알람 해소 + 스케줄 갱신(markDone과 같은 효과: 플래그 초기화)을 DB에 직접 반영
        jdbc.update("UPDATE alarms SET status = 'RESOLVED' WHERE equipment_id = ? AND alarm_type = 'PM_OVERDUE'", equipmentId);
        Instant doneAt = NOW.plus(1, ChronoUnit.DAYS);
        jdbc.update("UPDATE pm_schedules SET overdue_alarm_sent = FALSE, last_done_at = ?, next_due_at = ? WHERE equipment_id = ?",
                java.sql.Timestamp.from(doneAt), java.sql.Timestamp.from(doneAt.plus(1, ChronoUnit.DAYS)), equipmentId);

        // 주기(DAILY급)가 지나고 3일 더 경과한 시점 — 다시 이벤트 발행
        assertThat(pmOverdueService.publishOverdueEvents(doneAt.plus(10, ChronoUnit.DAYS))).isEqualTo(1);
        assertThat(overdueAlarmCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM alarms WHERE equipment_id = ? AND alarm_type = 'PM_OVERDUE' AND status = 'OPEN'",
                Integer.class, equipmentId)).isEqualTo(1);
    }

    // ------------------------------------------------------------ (4) 3일 이내는 발행 없음

    @Test
    @DisplayName("(4) 예정일 3일 이내 지연(2일·정확히 3일)·정상 스케줄은 이벤트/알람 없음, 플래그 유지")
    void withinGrace_noEventNoAlarm() {
        createSchedule(NOW.minus(2, ChronoUnit.DAYS));
        EventCounter counter = new EventCounter();
        registerListener(counter);
        try {
            assertThat(pmOverdueService.publishOverdueEvents(NOW)).isZero();

            // 정확히 3일 경과(= 임계값과 동일)는 "초과"가 아니다
            jdbc.update("UPDATE pm_schedules SET next_due_at = ? WHERE equipment_id = ?",
                    java.sql.Timestamp.from(NOW.minus(3, ChronoUnit.DAYS)), equipmentId);
            assertThat(pmOverdueService.publishOverdueEvents(NOW)).isZero();

            // 아직 도래하지 않은 정상 스케줄
            jdbc.update("UPDATE pm_schedules SET next_due_at = ? WHERE equipment_id = ?",
                    java.sql.Timestamp.from(NOW.plus(1, ChronoUnit.DAYS)), equipmentId);
            assertThat(pmOverdueService.publishOverdueEvents(NOW)).isZero();
        } finally {
            unregisterListener(counter);
        }

        assertThat(counter.count).as("PmOverdueEvent 발행 없음").isZero();
        assertThat(overdueAlarmCount()).isZero();
        assertThat(overdueAlarmSent()).isFalse();
    }

    // ------------------------------------------------------------ 헬퍼

    private void createSchedule(Instant nextDueAt) {
        createSchedule(equipmentId, nextDueAt);
    }

    private void createSchedule(long targetEquipmentId, Instant nextDueAt) {
        pmScheduleRepository.saveAndFlush(PmSchedule.builder()
                .equipmentId(targetEquipmentId)
                .cycleType(PmSchedule.CycleType.WEEKLY)
                .cycleValue(4)
                .lastDoneAt(nextDueAt.minus(7, ChronoUnit.DAYS))
                .nextDueAt(nextDueAt)
                .overdueAlarmSent(false)
                .build());
    }

    /** 영속성 컨텍스트를 거치지 않고 DB 실제 값을 읽는다 (트랜잭션 커밋/롤백 결과 확인용) */
    private boolean overdueAlarmSent() {
        return overdueAlarmSent(equipmentId);
    }

    private boolean overdueAlarmSent(long targetEquipmentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT overdue_alarm_sent FROM pm_schedules WHERE equipment_id = ?", Boolean.class, targetEquipmentId));
    }

    private int overdueAlarmCount() {
        return overdueAlarmCount(equipmentId);
    }

    private int overdueAlarmCount(long targetEquipmentId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM alarms WHERE equipment_id = ? AND alarm_type = 'PM_OVERDUE'",
                Integer.class, targetEquipmentId);
        return count == null ? 0 : count;
    }

    private void registerListener(ApplicationListener<?> listener) {
        multicaster().addApplicationListener(listener);
    }

    private void unregisterListener(ApplicationListener<?> listener) {
        multicaster().removeApplicationListener(listener);
    }

    private ApplicationEventMulticaster multicaster() {
        return applicationContext.getBean(
                AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME, ApplicationEventMulticaster.class);
    }

    /** 특정 이벤트를 받으면 예외를 던지는 테스트 리스너. 알람 서비스 리스너 뒤에 실행되도록 가장 낮은 우선순위로 둔다. */
    private static final class ThrowingListener implements ApplicationListener<ApplicationEvent>, Ordered {
        private final Class<?> target;
        private final java.util.function.Predicate<Object> condition;
        private int invocations;

        ThrowingListener(Class<?> target) {
            this(target, payload -> true);
        }

        /** condition이 true인 payload에서만 예외를 던진다 (특정 설비만 실패시키기 위함) */
        ThrowingListener(Class<?> target, java.util.function.Predicate<Object> condition) {
            this.target = target;
            this.condition = condition;
        }

        @Override
        public void onApplicationEvent(ApplicationEvent event) {
            // 도메인 이벤트는 record(= ApplicationEvent 아님)라 PayloadApplicationEvent로 감싸져 들어온다
            if (event instanceof org.springframework.context.PayloadApplicationEvent<?> payload
                    && target.isInstance(payload.getPayload()) && condition.test(payload.getPayload())) {
                invocations++;
                throw new IllegalStateException("테스트 주입 실패: " + target.getSimpleName());
            }
        }

        int invocations() {
            return invocations;
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }

    /** PmOverdueEvent 발행 횟수만 센다 */
    private static final class EventCounter implements ApplicationListener<ApplicationEvent> {
        private int count;

        @Override
        public void onApplicationEvent(ApplicationEvent event) {
            if (event instanceof org.springframework.context.PayloadApplicationEvent<?> payload
                    && payload.getPayload() instanceof PmOverdueEvent) {
                count++;
            }
        }
    }
}
