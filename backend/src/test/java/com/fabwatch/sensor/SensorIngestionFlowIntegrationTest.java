package com.fabwatch.sensor;

import com.fabwatch.alarm.entity.Alarm;
import com.fabwatch.alarm.repository.AlarmRepository;
import com.fabwatch.alarm.service.AlarmService;
import com.fabwatch.common.event.AlarmRaisedEvent;
import com.fabwatch.common.event.EquipmentDownRequestedEvent;
import com.fabwatch.equipment.dto.EquipmentStatusChangeRequest;
import com.fabwatch.equipment.entity.Equipment;
import com.fabwatch.equipment.entity.EquipmentStatus;
import com.fabwatch.equipment.repository.EquipmentRepository;
import com.fabwatch.equipment.service.EquipmentService;
import com.fabwatch.sensor.entity.Sensor;
import com.fabwatch.sensor.repository.SensorDataRepository;
import com.fabwatch.sensor.repository.SensorRepository;
import com.fabwatch.sensor.service.SensorIngestionService;
import com.fabwatch.sensor.service.SensorStreamService;
import com.fabwatch.simulator.dto.ScenarioCreateRequest;
import com.fabwatch.simulator.dto.ScenarioResponse;
import com.fabwatch.simulator.entity.SimulationScenario;
import com.fabwatch.simulator.service.ScenarioService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 수집 틱의 트랜잭션 경계·오류 격리·SSE 커밋 후 발행·시나리오 만료 통합 테스트
 * (안정성 감사 H-3, M-13; 실제 서비스·JPA·이벤트 연동, H2).
 *
 * 전용 컨텍스트(marker 속성)라 다른 통합 테스트의 시드 상태와 섞이지 않는다. 테스트마다 다른 설비를 쓴다.
 * SSE는 test 프로파일에서 동기 전송(fabwatch.sse.async=false)이라 기록이 결정적이다.
 */
@SpringBootTest(properties = {"fabwatch.seed.enabled=true", "fabwatch.test.marker=ingestion-flow"})
@ActiveProfiles({"local", "test"})
class SensorIngestionFlowIntegrationTest {

    @Autowired
    private SensorIngestionService ingestionService;
    @Autowired
    private ScenarioService scenarioService;
    @Autowired
    private EquipmentService equipmentService;
    @Autowired
    private AlarmService alarmService;
    @Autowired
    private SensorStreamService streamService;
    @Autowired
    private EquipmentRepository equipmentRepository;
    @Autowired
    private SensorRepository sensorRepository;
    @Autowired
    private SensorDataRepository sensorDataRepository;
    @Autowired
    private AlarmRepository alarmRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ApplicationContext applicationContext;

    private final List<RecordingEmitter> emitters = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> injectedScenarios = new ArrayList<>();
    private final List<ApplicationListener<?>> registered = new ArrayList<>();

    /** 보낸 SSE 이벤트 텍스트를 기록하는 emitter (서블릿 핸들러 없이 동작) */
    static class RecordingEmitter extends SseEmitter {
        final List<String> sent = Collections.synchronizedList(new ArrayList<>());
        Runnable completion;

        RecordingEmitter(long timeout) {
            super(timeout);
        }

        @Override
        public void send(SseEventBuilder builder) {
            sent.add(builder.build().stream().map(d -> String.valueOf(d.getData())).collect(Collectors.joining()));
        }

        @Override
        public synchronized void onCompletion(Runnable callback) {
            this.completion = callback;
        }

        @Override
        public synchronized void onTimeout(Runnable callback) {
        }

        @Override
        public synchronized void onError(java.util.function.Consumer<Throwable> callback) {
        }

        @Override
        public synchronized void complete() {
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void swapEmitterFactory() {
        LongFunction<SseEmitter> recording = timeout -> {
            RecordingEmitter emitter = new RecordingEmitter(timeout);
            emitters.add(emitter);
            return emitter;
        };
        ReflectionTestUtils.setField(streamService, "emitterFactory", recording);
    }

    @AfterEach
    void cleanUp() {
        registered.forEach(listener -> multicaster().removeApplicationListener(listener));
        registered.clear();
        injectedScenarios.forEach(id -> {
            try {
                scenarioService.release(id);
            } catch (RuntimeException ignored) {
                // 이미 만료·해제됨
            }
        });
        injectedScenarios.clear();
        emitters.forEach(e -> {
            if (e.completion != null) {
                e.completion.run(); // 구독 맵에서 제거
            }
        });
        emitters.clear();
        ReflectionTestUtils.setField(streamService, "emitterFactory", (LongFunction<SseEmitter>) SseEmitter::new);
    }

    // ------------------------------------------------------------ ① 센서 단위 오류 격리

    @Test
    @DisplayName("★ 한 센서의 알람 처리가 예외로 롤백돼도 나머지 센서의 알람·자동 DOWN과 18개 원본 저장은 유지된다")
    void 한센서_알람실패가_다른센서를_막지_않는다() {
        Equipment oven = equipment("OVEN-01");   // 실패 주입 대상
        Equipment lami = equipment("LAMI-02");   // 정상
        Sensor ovenTemp = sensor("OVEN-01", Sensor.Type.TEMP);
        Sensor lamiTemp = sensor("LAMI-02", Sensor.Type.TEMP);
        inject(ovenTemp);
        inject(lamiTemp);
        // oven의 AlarmRaisedEvent 처리에서 예외 → AlarmService 트랜잭션(알람 INSERT 포함)이 롤백된다
        register(new ThrowingListener(AlarmRaisedEvent.class,
                payload -> ((AlarmRaisedEvent) payload).equipmentId().equals(oven.getId())));
        subscribeRecording();

        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        int saved = ingestionService.ingest(at);

        assertThat(saved).isEqualTo(18);
        // 원본은 알람 처리 실패와 무관하게 18건 모두 커밋됨
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sensor_data WHERE measured_at = ?", Integer.class,
                java.sql.Timestamp.from(at))).isEqualTo(18);
        // 정상 센서: 알람 + 자동 DOWN
        assertThat(criticalAlarms(lamiTemp)).hasSize(1);
        assertThat(equipment("LAMI-02").getStatus()).isEqualTo(EquipmentStatus.DOWN);
        // 실패 센서: 알람·DOWN 모두 롤백 (알람만 남고 DOWN은 영영 안 되는 반쪽 상태 없음)
        assertThat(criticalAlarms(ovenTemp)).isEmpty();
        assertThat(equipment("OVEN-01").getStatus()).isEqualTo(EquipmentStatus.RUN);
        // SSE: 센서 값 18건은 커밋 후 나갔고, 알람 이벤트는 커밋된 LAMI-02 것만 나갔다
        List<String> sent = allSent();
        assertThat(sent.stream().filter(s -> s.contains("event:sensor"))).hasSize(18);
        assertThat(sent.stream().filter(s -> s.contains("event:alarm")).toList())
                .hasSize(1).allMatch(s -> s.contains("LAMI-02"));
        assertThat(lami.getId()).isNotEqualTo(oven.getId());

        // 장애가 사라지면 다음 틱에서 같은 판정이 재시도되어 알람·DOWN이 만들어진다
        unregisterAll();
        ingestionService.ingest(Instant.now());
        assertThat(criticalAlarms(ovenTemp)).hasSize(1);
        assertThat(equipment("OVEN-01").getStatus()).isEqualTo(EquipmentStatus.DOWN);
    }

    // ------------------------------------------------------------ ② 롤백 시 SSE 미발행

    @Test
    @DisplayName("★ 자동 DOWN 단계 실패로 알람 트랜잭션이 롤백되면 alarm/status SSE는 나가지 않고, 커밋된 경우에만 나간다")
    void 롤백되면_SSE_알람이_나가지_않는다() {
        Sensor temp = sensor("SCRB-01", Sensor.Type.TEMP);
        Equipment scrb = equipment("SCRB-01");
        EquipmentStatus before = scrb.getStatus();
        int logsBefore = statusLogCount(scrb.getId());
        inject(temp);
        // EquipmentAutoDownListener가 상태를 DOWN으로 바꾼 "뒤"(알람·AlarmRaisedEvent·StatusChangedEvent 발행 이후) 실패 → 전부 롤백
        ThrowingListener failing = new ThrowingListener(EquipmentDownRequestedEvent.class, payload -> true);
        register(failing);
        subscribeRecording();

        ingestionService.ingest(Instant.now());

        assertThat(failing.invocations()).isGreaterThanOrEqualTo(1);
        assertThat(criticalAlarms(temp)).as("알람 롤백").isEmpty();
        assertThat(equipment("SCRB-01").getStatus()).as("자동 DOWN 롤백").isEqualTo(before);
        assertThat(statusLogCount(scrb.getId())).as("상태 로그 롤백").isEqualTo(logsBefore);
        assertThat(allSent().stream().filter(s -> s.contains("event:alarm") || s.contains("event:status")))
                .as("롤백된 알람/상태 변경이 클라이언트에 나가면 유령 토스트가 된다").isEmpty();

        // 실패 제거 후: 커밋되고 나서야 alarm/status 이벤트가 나간다
        unregisterAll();
        emitters.forEach(e -> e.sent.clear());
        ingestionService.ingest(Instant.now());

        assertThat(criticalAlarms(temp)).hasSize(1);
        List<String> sent = allSent();
        assertThat(sent.stream().filter(s -> s.contains("event:alarm"))).hasSize(1);
        assertThat(sent.stream().filter(s -> s.contains("event:status")).toList())
                .hasSize(1).allMatch(s -> s.contains("DOWN"));
    }

    // ------------------------------------------------------------ 시나리오 만료 (M-13)

    @Test
    @DisplayName("★ 시나리오 만료: 수리(알람 해제·DOWN→IDLE) 후 시나리오가 살아 있으면 재발하지만, 만료되면 정상값으로 돌아와 재발하지 않는다")
    void 시나리오_만료_후_재발_없음() {
        Sensor current = sensor("AOI-01", Sensor.Type.CURRENT);
        Equipment aoi = equipment("AOI-01");
        ScenarioResponse scenario = inject(current);   // STEP: 최대 수명 30분(기본)
        Instant started = scenario.startedAt();

        // 수명 안(29분): 값이 높아 CRITICAL → 알람 + 자동 DOWN
        ingestionService.ingest(started.plus(29, ChronoUnit.MINUTES));
        List<Alarm> alarms = criticalAlarms(current);
        assertThat(alarms).hasSize(1);
        assertThat(equipment("AOI-01").getStatus()).isEqualTo(EquipmentStatus.DOWN);

        // 사용자 수리: 알람 확인→해제, DOWN→IDLE
        long adminId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'admin@fabwatch.dev'", Long.class);
        alarmService.acknowledge(alarms.get(0).getId(), adminId);
        alarmService.resolve(alarms.get(0).getId(),
                new com.fabwatch.alarm.dto.AlarmResolveRequest("센서 점검 완료"), adminId);
        equipmentService.changeStatus(aoi.getId(), new EquipmentStatusChangeRequest(EquipmentStatus.IDLE, "수리 완료"),
                adminId, "ADMIN");

        // 시나리오가 아직 살아 있는 29분 30초 시점이면 즉시 재발한다 (이전 동작의 증명)
        ingestionService.ingest(started.plus(29, ChronoUnit.MINUTES).plusSeconds(30));
        assertThat(criticalAlarms(current)).as("만료 전에는 재발").hasSize(2);
        assertThat(equipment("AOI-01").getStatus()).isEqualTo(EquipmentStatus.DOWN);

        // 다시 수리
        List<Alarm> reopened = criticalAlarms(current).stream().filter(a -> a.getStatus() != Alarm.Status.RESOLVED).toList();
        alarmService.acknowledge(reopened.get(0).getId(), adminId);
        alarmService.resolve(reopened.get(0).getId(), new com.fabwatch.alarm.dto.AlarmResolveRequest("재수리"), adminId);
        equipmentService.changeStatus(aoi.getId(), new EquipmentStatusChangeRequest(EquipmentStatus.IDLE, "재수리 완료"),
                adminId, "ADMIN");

        // 만료 후(31분): 자동 비활성화 → 정상값 → 알람·DOWN 재발 없음
        ingestionService.ingest(started.plus(31, ChronoUnit.MINUTES));

        assertThat(criticalAlarms(current)).as("만료 후에는 새 알람 없음").hasSize(2);
        assertThat(equipment("AOI-01").getStatus()).isEqualTo(EquipmentStatus.IDLE);
        assertThat(scenarioService.getActiveScenarios().content())
                .extracting(ScenarioResponse::id).doesNotContain(scenario.id());
        assertThat(jdbc.queryForObject("SELECT active FROM simulation_scenarios WHERE id = ?", Boolean.class, scenario.id()))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT ended_at IS NOT NULL FROM simulation_scenarios WHERE id = ?",
                Boolean.class, scenario.id())).isTrue();
    }

    // ------------------------------------------------------------ 헬퍼

    private int statusLogCount(long equipmentId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM equipment_status_logs WHERE equipment_id = ?",
                Integer.class, equipmentId);
    }

    private ScenarioResponse inject(Sensor sensor) {
        ScenarioResponse response = scenarioService.inject(new ScenarioCreateRequest(
                sensor.getId(), SimulationScenario.Type.STEP, Map.of("offset", 100)));
        injectedScenarios.add(response.id());
        return response;
    }

    private void subscribeRecording() {
        streamService.subscribe(null, 1L);
        emitters.forEach(e -> e.sent.clear()); // 연결 확립 comment 제거
    }

    private List<String> allSent() {
        List<String> sent = new ArrayList<>();
        emitters.forEach(e -> sent.addAll(e.sent));
        return sent;
    }

    private List<Alarm> criticalAlarms(Sensor sensor) {
        return alarmRepository.findAll().stream()
                .filter(alarm -> sensor.getId().equals(alarm.getSensorId()))
                .filter(alarm -> alarm.getSeverity() == Alarm.Severity.CRITICAL)
                .toList();
    }

    private Equipment equipment(String code) {
        return equipmentRepository.findByCode(code).orElseThrow();
    }

    private Sensor sensor(String equipmentCode, Sensor.Type type) {
        return sensorRepository.findByEquipmentIdAndType(equipment(equipmentCode).getId(), type).orElseThrow();
    }

    private void register(ApplicationListener<?> listener) {
        multicaster().addApplicationListener(listener);
        registered.add(listener);
    }

    private void unregisterAll() {
        registered.forEach(listener -> multicaster().removeApplicationListener(listener));
        registered.clear();
    }

    private ApplicationEventMulticaster multicaster() {
        return applicationContext.getBean(
                AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME, ApplicationEventMulticaster.class);
    }

    /** 조건에 맞는 도메인 이벤트를 받으면 예외를 던지는 테스트 리스너 — 알람 서비스 리스너 뒤에 실행되도록 가장 낮은 우선순위 */
    private static final class ThrowingListener implements ApplicationListener<ApplicationEvent>, Ordered {
        private final Class<?> target;
        private final Predicate<Object> condition;
        private int invocations;

        ThrowingListener(Class<?> target, Predicate<Object> condition) {
            this.target = target;
            this.condition = condition;
        }

        @Override
        public void onApplicationEvent(ApplicationEvent event) {
            if (event instanceof PayloadApplicationEvent<?> payload
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
}
