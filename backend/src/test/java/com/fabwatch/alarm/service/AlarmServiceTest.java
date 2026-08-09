package com.fabwatch.alarm.service;

import com.fabwatch.alarm.entity.Alarm;
import com.fabwatch.alarm.repository.AlarmRepository;
import com.fabwatch.auth.service.UserQueryService;
import com.fabwatch.common.event.AlarmRaisedEvent;
import com.fabwatch.common.event.EquipmentDownRequestedEvent;
import com.fabwatch.common.event.SensorLevel;
import com.fabwatch.common.event.ThresholdExceededEvent;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 알람 중복 억제 + CRITICAL 자동 DOWN 요청 단위 테스트 (docs/03 F-4.3).
 *
 * 중복 억제는 이 프로젝트의 현장 경험 반영 포인트다 — 알람 폭주는 실제 알람 무시 문화의 원인이라
 * "동일 설비+센서+심각도의 미해결 알람이 있으면 신규 생성 안 함"이 반드시 지켜져야 한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AlarmServiceTest {

    private static final Long EQUIPMENT_ID = 10L;
    private static final Long SENSOR_ID = 100L;

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
        given(equipmentQueryService.findCodeById(EQUIPMENT_ID)).willReturn(Optional.of("LAMI-01"));
        given(alarmRepository.save(any(Alarm.class))).willAnswer(invocation -> {
            Alarm alarm = invocation.getArgument(0);
            ReflectionTestUtils.setField(alarm, "id", 1L);
            return alarm;
        });
    }

    private ThresholdExceededEvent event(SensorLevel level) {
        return new ThresholdExceededEvent(EQUIPMENT_ID, SENSOR_ID, "VIBRATION", "mm/s",
                new BigDecimal("5.4"), new BigDecimal("5.0"), level, Instant.parse("2026-08-10T02:00:00Z"));
    }

    private void suppress(boolean suppressed, Alarm.Severity severity) {
        given(alarmRepository.existsByEquipmentIdAndSensorIdAndSeverityAndStatusNot(
                EQUIPMENT_ID, SENSOR_ID, severity, Alarm.Status.RESOLVED)).willReturn(suppressed);
    }

    @Test
    @DisplayName("미해결 알람이 없으면 신규 알람을 생성한다")
    void 신규_생성() {
        suppress(false, Alarm.Severity.WARNING);

        alarmService.onThresholdExceeded(event(SensorLevel.WARNING));

        ArgumentCaptor<Alarm> captor = ArgumentCaptor.forClass(Alarm.class);
        verify(alarmRepository).save(captor.capture());
        Alarm saved = captor.getValue();
        assertThat(saved.getEquipmentId()).isEqualTo(EQUIPMENT_ID);
        assertThat(saved.getSensorId()).isEqualTo(SENSOR_ID);
        assertThat(saved.getSeverity()).isEqualTo(Alarm.Severity.WARNING);
        assertThat(saved.getAlarmType()).isEqualTo(Alarm.Type.SENSOR_THRESHOLD);
        assertThat(saved.getStatus()).isEqualTo(Alarm.Status.OPEN);
        assertThat(saved.getTriggerValue()).isEqualByComparingTo("5.4");
        assertThat(saved.getThresholdValue()).isEqualByComparingTo("5.0");
        assertThat(saved.getMessage()).contains("LAMI-01").contains("진동");
    }

    @Test
    @DisplayName("★ 중복 억제 — 동일 설비+센서+심각도의 미해결 알람이 있으면 신규 생성하지 않는다")
    void 중복_억제() {
        suppress(true, Alarm.Severity.WARNING);

        alarmService.onThresholdExceeded(event(SensorLevel.WARNING));

        verify(alarmRepository, never()).save(any(Alarm.class));
        verify(eventPublisher, never()).publishEvent(any(AlarmRaisedEvent.class));
    }

    @Test
    @DisplayName("★ 중복 억제 — 2초마다 같은 이벤트가 들어와도 알람은 1건만 생성된다")
    void 반복_이벤트_1건만() {
        // 첫 호출은 미해결 없음 → 생성, 이후에는 미해결 있음 → 억제
        given(alarmRepository.existsByEquipmentIdAndSensorIdAndSeverityAndStatusNot(
                EQUIPMENT_ID, SENSOR_ID, Alarm.Severity.WARNING, Alarm.Status.RESOLVED))
                .willReturn(false, true, true, true, true);

        for (int i = 0; i < 5; i++) {
            alarmService.onThresholdExceeded(event(SensorLevel.WARNING));
        }

        verify(alarmRepository, org.mockito.Mockito.times(1)).save(any(Alarm.class));
    }

    @Test
    @DisplayName("★ 중복 억제 — 심각도가 다르면(WARNING 미해결 + CRITICAL 신규) 별개로 생성한다")
    void 심각도_다르면_생성() {
        given(alarmRepository.existsByEquipmentIdAndSensorIdAndSeverityAndStatusNot(
                EQUIPMENT_ID, SENSOR_ID, Alarm.Severity.WARNING, Alarm.Status.RESOLVED)).willReturn(true);
        given(alarmRepository.existsByEquipmentIdAndSensorIdAndSeverityAndStatusNot(
                EQUIPMENT_ID, SENSOR_ID, Alarm.Severity.CRITICAL, Alarm.Status.RESOLVED)).willReturn(false);

        alarmService.onThresholdExceeded(event(SensorLevel.CRITICAL));

        ArgumentCaptor<Alarm> captor = ArgumentCaptor.forClass(Alarm.class);
        verify(alarmRepository).save(captor.capture());
        assertThat(captor.getValue().getSeverity()).isEqualTo(Alarm.Severity.CRITICAL);
    }

    @Test
    @DisplayName("중복 억제 조회는 status != RESOLVED 로 한다 (docs/05 — RESOLVED만 재발생 허용)")
    void 억제_조회_조건() {
        suppress(false, Alarm.Severity.WARNING);

        alarmService.onThresholdExceeded(event(SensorLevel.WARNING));

        verify(alarmRepository).existsByEquipmentIdAndSensorIdAndSeverityAndStatusNot(
                eq(EQUIPMENT_ID), eq(SENSOR_ID), eq(Alarm.Severity.WARNING), eq(Alarm.Status.RESOLVED));
    }

    @Test
    @DisplayName("NORMAL 판정은 알람을 만들지 않는다")
    void 정상은_무시() {
        alarmService.onThresholdExceeded(event(SensorLevel.NORMAL));
        verify(alarmRepository, never()).save(any(Alarm.class));
    }

    @Test
    @DisplayName("CRITICAL 생성 시 설비 자동 DOWN 요청 이벤트를 발행한다 (equipment 직접 호출 아님)")
    void CRITICAL_자동DOWN_이벤트() {
        suppress(false, Alarm.Severity.CRITICAL);

        alarmService.onThresholdExceeded(event(SensorLevel.CRITICAL));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(captor.capture());
        EquipmentDownRequestedEvent downEvent = captor.getAllValues().stream()
                .filter(EquipmentDownRequestedEvent.class::isInstance)
                .map(EquipmentDownRequestedEvent.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("EquipmentDownRequestedEvent가 발행되지 않았습니다."));
        assertThat(downEvent.equipmentId()).isEqualTo(EQUIPMENT_ID);
        assertThat(downEvent.alarmId()).isEqualTo(1L);
        assertThat(downEvent.reason()).contains("CRITICAL");
    }

    @Test
    @DisplayName("WARNING은 자동 DOWN 요청을 하지 않는다")
    void WARNING은_DOWN_없음() {
        suppress(false, Alarm.Severity.WARNING);

        alarmService.onThresholdExceeded(event(SensorLevel.WARNING));

        verify(eventPublisher, never()).publishEvent(any(EquipmentDownRequestedEvent.class));
    }

    @Test
    @DisplayName("알람 생성 시 SSE용 AlarmRaisedEvent를 발행한다")
    void SSE_이벤트_발행() {
        suppress(false, Alarm.Severity.WARNING);

        alarmService.onThresholdExceeded(event(SensorLevel.WARNING));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(captor.capture());
        AlarmRaisedEvent raised = captor.getAllValues().stream()
                .filter(AlarmRaisedEvent.class::isInstance)
                .map(AlarmRaisedEvent.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("AlarmRaisedEvent가 발행되지 않았습니다."));
        assertThat(raised.alarmId()).isEqualTo(1L);
        assertThat(raised.equipmentCode()).isEqualTo("LAMI-01");
        assertThat(raised.severity()).isEqualTo("WARNING");
        assertThat(raised.status()).isEqualTo("OPEN");
        assertThat(raised.sensorType()).isEqualTo("VIBRATION");
    }
}
