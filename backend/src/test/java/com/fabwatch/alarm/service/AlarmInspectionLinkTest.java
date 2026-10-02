package com.fabwatch.alarm.service;

import com.fabwatch.alarm.entity.Alarm;
import com.fabwatch.alarm.repository.AlarmRepository;
import com.fabwatch.auth.service.UserQueryService;
import com.fabwatch.common.event.AlarmRaisedEvent;
import com.fabwatch.common.event.EquipmentDownRequestedEvent;
import com.fabwatch.common.event.PmOverdueEvent;
import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import com.fabwatch.equipment.service.EquipmentQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * inspection ↔ alarm 연계 단위 테스트 — BM 점검 이력의 알람 해제(AlarmCommandService)와 PM 지연 알람(PmOverdueEvent 구독).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AlarmInspectionLinkTest {

    private static final Long EQUIPMENT_ID = 10L;
    private static final Long ACTOR_ID = 7L;

    @Mock
    private AlarmRepository alarmRepository;
    @Mock
    private EquipmentQueryService equipmentQueryService;
    @Mock
    private UserQueryService userQueryService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private AlarmService alarmService;

    @BeforeEach
    void setUp() {
        given(equipmentQueryService.findCodeById(EQUIPMENT_ID)).willReturn(Optional.of("LAMI-02"));
        given(alarmRepository.save(any(Alarm.class))).willAnswer(invocation -> {
            Alarm alarm = invocation.getArgument(0);
            ReflectionTestUtils.setField(alarm, "id", 1L);
            return alarm;
        });
    }

    private Alarm alarm(Alarm.Status status, Long equipmentId) {
        Alarm alarm = Alarm.builder().equipmentId(equipmentId).alarmType(Alarm.Type.SENSOR_THRESHOLD)
                .severity(Alarm.Severity.CRITICAL).status(status).message("진동 임계 초과")
                .occurredAt(Instant.parse("2026-07-06T00:00:00Z")).build();
        ReflectionTestUtils.setField(alarm, "id", 45L);
        given(alarmRepository.findById(45L)).willReturn(Optional.of(alarm));
        return alarm;
    }

    @Test
    @DisplayName("OPEN 알람 → 작성자 명의로 ACK 후 RESOLVE (ACK_REQUIRED_FIRST 규칙 유지)")
    void openAlarmAckThenResolve() {
        Alarm alarm = alarm(Alarm.Status.OPEN, EQUIPMENT_ID);

        boolean resolved = alarmService.ackAndResolve(45L, ACTOR_ID, "BM 점검 이력 #3로 조치 완료");

        assertThat(resolved).isTrue();
        assertThat(alarm.getStatus()).isEqualTo(Alarm.Status.RESOLVED);
        assertThat(alarm.getAckBy()).isEqualTo(ACTOR_ID);
        assertThat(alarm.getAckAt()).isNotNull();
        assertThat(alarm.getResolvedBy()).isEqualTo(ACTOR_ID);
        assertThat(alarm.getResolveNote()).isEqualTo("BM 점검 이력 #3로 조치 완료");
    }

    @Test
    @DisplayName("ACK 상태 알람 → 기존 ACK 기록은 유지한 채 RESOLVE")
    void ackAlarmResolve() {
        Alarm alarm = alarm(Alarm.Status.ACK, EQUIPMENT_ID);
        ReflectionTestUtils.setField(alarm, "ackBy", 99L);

        assertThat(alarmService.ackAndResolve(45L, ACTOR_ID, "조치")).isTrue();

        assertThat(alarm.getStatus()).isEqualTo(Alarm.Status.RESOLVED);
        assertThat(alarm.getAckBy()).isEqualTo(99L);
        assertThat(alarm.getResolvedBy()).isEqualTo(ACTOR_ID);
    }

    @Test
    @DisplayName("이미 RESOLVED면 에러 없이 그대로 둔다 (기존 해제자·사유 보존)")
    void alreadyResolvedIsNoop() {
        Alarm alarm = alarm(Alarm.Status.RESOLVED, EQUIPMENT_ID);
        ReflectionTestUtils.setField(alarm, "resolvedBy", 99L);
        ReflectionTestUtils.setField(alarm, "resolveNote", "먼저 처리됨");

        assertThat(alarmService.ackAndResolve(45L, ACTOR_ID, "조치")).isFalse();

        assertThat(alarm.getResolvedBy()).isEqualTo(99L);
        assertThat(alarm.getResolveNote()).isEqualTo("먼저 처리됨");
    }

    @Test
    @DisplayName("설비 소속 검증 — 같은 설비면 통과, 다른 설비면 400, 없는 알람은 404")
    void belongsToEquipment() {
        alarm(Alarm.Status.OPEN, EQUIPMENT_ID);
        alarmService.assertBelongsToEquipment(45L, EQUIPMENT_ID);

        assertThatThrownBy(() -> alarmService.assertBelongsToEquipment(45L, 99L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);

        given(alarmRepository.findById(404L)).willReturn(Optional.empty());
        assertThatThrownBy(() -> alarmService.assertBelongsToEquipment(404L, EQUIPMENT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ------------------------------------------------------------ PM 지연 알람

    private PmOverdueEvent overdueEvent() {
        return new PmOverdueEvent(5L, EQUIPMENT_ID, Instant.parse("2026-07-10T00:00:00Z"), 3,
                Instant.parse("2026-07-13T01:00:00Z"));
    }

    @Test
    @DisplayName("PmOverdueEvent → MAJOR/PM_OVERDUE 알람 생성, 메시지는 시드와 같은 형식, CRITICAL이 아니므로 자동 DOWN 없음")
    void pmOverdueCreatesMajorAlarm() {
        alarmService.onPmOverdue(overdueEvent());

        ArgumentCaptor<Alarm> captor = ArgumentCaptor.forClass(Alarm.class);
        verify(alarmRepository).save(captor.capture());
        Alarm created = captor.getValue();
        assertThat(created.getAlarmType()).isEqualTo(Alarm.Type.PM_OVERDUE);
        assertThat(created.getSeverity()).isEqualTo(Alarm.Severity.MAJOR);
        assertThat(created.getStatus()).isEqualTo(Alarm.Status.OPEN);
        assertThat(created.getSensorId()).isNull();
        assertThat(created.getMessage()).isEqualTo("LAMI-02 PM 예정일 경과 (3일)");
        verify(eventPublisher).publishEvent(any(AlarmRaisedEvent.class));
        verify(eventPublisher, never()).publishEvent(any(EquipmentDownRequestedEvent.class));
    }

    @Test
    @DisplayName("같은 설비에 미해결 PM_OVERDUE 알람이 있으면 새로 만들지 않는다 (수동 MAJOR 알람과는 서로 가리지 않음)")
    void pmOverdueSuppressedByType() {
        given(alarmRepository.existsByEquipmentIdAndAlarmTypeAndStatusNot(
                EQUIPMENT_ID, Alarm.Type.PM_OVERDUE, Alarm.Status.RESOLVED)).willReturn(true);

        alarmService.onPmOverdue(overdueEvent());

        verify(alarmRepository, never()).save(any());

        // 유형이 다른 미해결 MAJOR(수동 보고)가 있어도 PM 지연 알람은 생성되어야 한다
        given(alarmRepository.existsByEquipmentIdAndAlarmTypeAndStatusNot(
                EQUIPMENT_ID, Alarm.Type.PM_OVERDUE, Alarm.Status.RESOLVED)).willReturn(false);
        given(alarmRepository.existsByEquipmentIdAndSensorIdIsNullAndSeverityAndStatusNot(
                EQUIPMENT_ID, Alarm.Severity.MAJOR, Alarm.Status.RESOLVED)).willReturn(true);
        alarmService.onPmOverdue(overdueEvent());
        verify(alarmRepository).save(any(Alarm.class));
    }

    // ------------------------------------------------------------ PM 수행 → PM_OVERDUE 알람 자동 해소

    private Alarm pmOverdue(Alarm.Status status, Long equipmentId) {
        return Alarm.builder().equipmentId(equipmentId).alarmType(Alarm.Type.PM_OVERDUE)
                .severity(Alarm.Severity.MAJOR).status(status).message("LAMI-02 PM 예정일 경과 (3일)")
                .occurredAt(Instant.parse("2026-07-13T01:00:00Z")).build();
    }

    private void givenUnresolved(Long equipmentId, Alarm... alarms) {
        given(alarmRepository.findByEquipmentIdAndAlarmTypeAndStatusNot(
                equipmentId, Alarm.Type.PM_OVERDUE, Alarm.Status.RESOLVED)).willReturn(java.util.List.of(alarms));
    }

    @Test
    @DisplayName("OPEN PM_OVERDUE → 작성자 명의 ACK 후 RESOLVE, resolveNote/resolvedBy 기록")
    void resolvePmOverdue_open() {
        Alarm open = pmOverdue(Alarm.Status.OPEN, EQUIPMENT_ID);
        givenUnresolved(EQUIPMENT_ID, open);

        int resolved = alarmService.resolvePmOverdueByEquipment(EQUIPMENT_ID, ACTOR_ID, "PM 점검 이력 #9로 수행 완료");

        assertThat(resolved).isEqualTo(1);
        assertThat(open.getStatus()).isEqualTo(Alarm.Status.RESOLVED);
        assertThat(open.getAckBy()).isEqualTo(ACTOR_ID);
        assertThat(open.getResolvedBy()).isEqualTo(ACTOR_ID);
        assertThat(open.getResolveNote()).isEqualTo("PM 점검 이력 #9로 수행 완료");
        assertThat(open.getResolvedAt()).isNotNull();
    }

    @Test
    @DisplayName("ACK 상태 PM_OVERDUE도 해소 — 기존 ACK 기록은 유지")
    void resolvePmOverdue_ack() {
        Alarm ack = pmOverdue(Alarm.Status.ACK, EQUIPMENT_ID);
        ReflectionTestUtils.setField(ack, "ackBy", 99L);
        givenUnresolved(EQUIPMENT_ID, ack);

        assertThat(alarmService.resolvePmOverdueByEquipment(EQUIPMENT_ID, ACTOR_ID, "수행 완료")).isEqualTo(1);

        assertThat(ack.getStatus()).isEqualTo(Alarm.Status.RESOLVED);
        assertThat(ack.getAckBy()).isEqualTo(99L);
        assertThat(ack.getResolvedBy()).isEqualTo(ACTOR_ID);
    }

    @Test
    @DisplayName("알람이 없거나 이미 RESOLVED뿐이면 no-op — 에러 없음, 기존 해제 기록 보존")
    void resolvePmOverdue_noopWhenNoneOrResolved() {
        givenUnresolved(EQUIPMENT_ID); // 미해결 없음
        assertThat(alarmService.resolvePmOverdueByEquipment(EQUIPMENT_ID, ACTOR_ID, "수행 완료")).isZero();

        // 방어: 조회 결과에 RESOLVED가 섞여 있어도 건드리지 않는다
        Alarm resolved = pmOverdue(Alarm.Status.RESOLVED, EQUIPMENT_ID);
        ReflectionTestUtils.setField(resolved, "resolvedBy", 99L);
        ReflectionTestUtils.setField(resolved, "resolveNote", "먼저 처리됨");
        givenUnresolved(EQUIPMENT_ID, resolved);
        assertThat(alarmService.resolvePmOverdueByEquipment(EQUIPMENT_ID, ACTOR_ID, "수행 완료")).isZero();
        assertThat(resolved.getResolvedBy()).isEqualTo(99L);
        assertThat(resolved.getResolveNote()).isEqualTo("먼저 처리됨");
    }

    @Test
    @DisplayName("다른 설비의 PM_OVERDUE 알람은 건드리지 않는다 — 조회가 대상 설비 id로만 이뤄진다")
    void resolvePmOverdue_otherEquipmentUntouched() {
        Alarm mine = pmOverdue(Alarm.Status.OPEN, EQUIPMENT_ID);
        Alarm others = pmOverdue(Alarm.Status.OPEN, 99L);
        givenUnresolved(EQUIPMENT_ID, mine);
        givenUnresolved(99L, others);

        alarmService.resolvePmOverdueByEquipment(EQUIPMENT_ID, ACTOR_ID, "수행 완료");

        assertThat(mine.getStatus()).isEqualTo(Alarm.Status.RESOLVED);
        assertThat(others.getStatus()).isEqualTo(Alarm.Status.OPEN);
        verify(alarmRepository, never()).findByEquipmentIdAndAlarmTypeAndStatusNot(
                eq(99L), any(), any());
    }

    @Test
    @DisplayName("해소 후에는 중복 억제에 걸리지 않아 새 PM_OVERDUE 알람이 생성될 수 있다")
    void pmOverdueCanBeRaisedAgainAfterResolve() {
        Alarm open = pmOverdue(Alarm.Status.OPEN, EQUIPMENT_ID);
        givenUnresolved(EQUIPMENT_ID, open);
        // 억제 판정은 "미해결 PM_OVERDUE 존재 여부" — 해소 전에는 true, 해소 후에는 false를 흉내낸다
        given(alarmRepository.existsByEquipmentIdAndAlarmTypeAndStatusNot(
                EQUIPMENT_ID, Alarm.Type.PM_OVERDUE, Alarm.Status.RESOLVED))
                .willAnswer(invocation -> open.getStatus() != Alarm.Status.RESOLVED);

        alarmService.onPmOverdue(overdueEvent());
        verify(alarmRepository, never()).save(any());

        alarmService.resolvePmOverdueByEquipment(EQUIPMENT_ID, ACTOR_ID, "수행 완료");
        alarmService.onPmOverdue(overdueEvent());

        verify(alarmRepository).save(any(Alarm.class));
    }
}
