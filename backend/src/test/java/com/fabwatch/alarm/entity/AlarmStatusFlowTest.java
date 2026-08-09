package com.fabwatch.alarm.entity;

import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 알람 상태 전이 (docs/03 F-5.3): OPEN → ACK → RESOLVED. ACK 없이 RESOLVED 불가, 해제 사유 필수.
 */
class AlarmStatusFlowTest {

    private static final Instant NOW = Instant.parse("2026-08-10T02:00:00Z");

    private Alarm openAlarm() {
        return Alarm.builder()
                .equipmentId(1L)
                .sensorId(2L)
                .alarmType(Alarm.Type.SENSOR_THRESHOLD)
                .severity(Alarm.Severity.WARNING)
                .status(Alarm.Status.OPEN)
                .message("테스트 알람")
                .occurredAt(NOW)
                .build();
    }

    @Test
    @DisplayName("OPEN → ACK → RESOLVED 정상 흐름")
    void 정상_흐름() {
        Alarm alarm = openAlarm();

        alarm.acknowledge(7L, NOW);
        assertThat(alarm.getStatus()).isEqualTo(Alarm.Status.ACK);
        assertThat(alarm.getAckBy()).isEqualTo(7L);

        alarm.resolve(8L, "베어링 교체 완료", NOW);
        assertThat(alarm.getStatus()).isEqualTo(Alarm.Status.RESOLVED);
        assertThat(alarm.getResolvedBy()).isEqualTo(8L);
        assertThat(alarm.getResolveNote()).isEqualTo("베어링 교체 완료");
    }

    @Test
    @DisplayName("ACK 없이 RESOLVED 불가 → 400 ACK_REQUIRED_FIRST")
    void ACK_없이_해제_불가() {
        Alarm alarm = openAlarm();

        assertThatThrownBy(() -> alarm.resolve(8L, "그냥 닫음", NOW))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACK_REQUIRED_FIRST);
        assertThat(alarm.getStatus()).isEqualTo(Alarm.Status.OPEN);
    }

    @Test
    @DisplayName("해제 사유가 비어 있으면 거부")
    void 해제사유_필수() {
        Alarm alarm = openAlarm();
        alarm.acknowledge(7L, NOW);

        assertThatThrownBy(() -> alarm.resolve(8L, "  ", NOW))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(alarm.getStatus()).isEqualTo(Alarm.Status.ACK);
    }

    @Test
    @DisplayName("이미 ACK인 알람을 다시 ACK하면 400 INVALID_ALARM_STATUS")
    void 중복_ACK_거부() {
        Alarm alarm = openAlarm();
        alarm.acknowledge(7L, NOW);

        assertThatThrownBy(() -> alarm.acknowledge(9L, NOW))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_ALARM_STATUS);
    }

    @Test
    @DisplayName("RESOLVED 알람은 다시 해제할 수 없다")
    void 중복_해제_거부() {
        Alarm alarm = openAlarm();
        alarm.acknowledge(7L, NOW);
        alarm.resolve(8L, "조치 완료", NOW);

        assertThatThrownBy(() -> alarm.resolve(8L, "또 조치", NOW))
                .isInstanceOf(BusinessException.class);
    }
}
