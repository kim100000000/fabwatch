package com.fabwatch.equipment.entity;

import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 설비 상태 머신 단위 테스트 (docs/03 F-2, docs/12 §4 핵심 4대 도메인).
 * 허용 전이 10개 / 비허용 전이 6개(자기 자신으로의 전이 4개 포함)를 모두 검증한다.
 */
class EquipmentStatusMachineTest {

    /** docs/03 F-2에 정의된 허용 전이 전부 */
    private static final Set<String> ALLOWED = Set.of(
            "RUN>IDLE", "RUN>DOWN", "RUN>PM",
            "IDLE>RUN", "IDLE>DOWN", "IDLE>PM",
            "DOWN>IDLE", "DOWN>RUN", "DOWN>PM",
            "PM>IDLE");

    @Test
    @DisplayName("전이 매트릭스 4x4 전체 — 문서에 정의된 10개만 허용되고 나머지는 전부 거부된다")
    void 전이_매트릭스_전체_검증() {
        for (EquipmentStatus from : EquipmentStatus.values()) {
            for (EquipmentStatus to : EquipmentStatus.values()) {
                boolean expected = ALLOWED.contains(from + ">" + to);
                assertThat(from.canTransitionTo(to))
                        .as("%s → %s 전이 허용 여부", from, to)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    @DisplayName("동일 상태로의 전이는 허용하지 않는다 (상태 로그 오염 방지)")
    void 동일_상태_전이_거부() {
        for (EquipmentStatus status : EquipmentStatus.values()) {
            assertThat(status.canTransitionTo(status)).isFalse();
        }
    }

    @Test
    @DisplayName("null 대상 전이는 거부한다")
    void null_전이_거부() {
        assertThat(EquipmentStatus.RUN.canTransitionTo(null)).isFalse();
    }

    @Test
    @DisplayName("DOWN에서는 IDLE/RUN/PM으로, PM에서는 IDLE로만 갈 수 있다 (PM→RUN은 현장 원칙상 불가)")
    void 고장_정비_흐름() {
        assertThat(EquipmentStatus.DOWN.allowedTargets())
                .containsExactlyInAnyOrder(EquipmentStatus.IDLE, EquipmentStatus.RUN, EquipmentStatus.PM);
        assertThat(EquipmentStatus.PM.allowedTargets()).containsExactly(EquipmentStatus.IDLE);
    }

    @ParameterizedTest(name = "{0} → {1} 전이 성공")
    @CsvSource({"RUN,IDLE", "RUN,DOWN", "RUN,PM", "IDLE,RUN", "IDLE,DOWN", "IDLE,PM", "DOWN,IDLE", "DOWN,RUN", "DOWN,PM", "PM,IDLE"})
    @DisplayName("허용 전이 — 설비 상태가 실제로 바뀌고 이전 상태를 반환한다")
    void 허용_전이시_상태변경(EquipmentStatus from, EquipmentStatus to) {
        Equipment equipment = equipmentWithStatus(from);

        EquipmentStatus previous = equipment.changeStatus(to);

        assertThat(previous).isEqualTo(from);
        assertThat(equipment.getStatus()).isEqualTo(to);
    }

    @ParameterizedTest(name = "{0} → {1} 전이 거부")
    @CsvSource({"PM,RUN", "PM,DOWN", "RUN,RUN", "IDLE,IDLE", "DOWN,DOWN", "PM,PM"})
    @DisplayName("비허용 전이 — INVALID_STATUS_TRANSITION 예외를 던지고 상태는 그대로다")
    void 비허용_전이시_예외(EquipmentStatus from, EquipmentStatus to) {
        Equipment equipment = equipmentWithStatus(from);

        assertThatThrownBy(() -> equipment.changeStatus(to))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);
        assertThat(equipment.getStatus()).isEqualTo(from);
    }

    @Test
    @DisplayName("PM 완료 후 바로 RUN으로 갈 수 없다 — IDLE(시운전 대기)를 거쳐야 한다")
    void PM_이후_시운전_대기_강제() {
        Equipment equipment = equipmentWithStatus(EquipmentStatus.PM);

        assertThatThrownBy(() -> equipment.changeStatus(EquipmentStatus.RUN))
                .isInstanceOf(BusinessException.class);

        equipment.changeStatus(EquipmentStatus.IDLE);
        equipment.changeStatus(EquipmentStatus.RUN);
        assertThat(equipment.getStatus()).isEqualTo(EquipmentStatus.RUN);
    }

    @Test
    @DisplayName("DOWN(BM) 수리 후 PM을 거치지 않고 IDLE → RUN으로 복귀한다")
    void 고장_수리_직접_복귀_시나리오() {
        Equipment equipment = equipmentWithStatus(EquipmentStatus.RUN);

        List.of(EquipmentStatus.DOWN, EquipmentStatus.IDLE, EquipmentStatus.RUN).forEach(equipment::changeStatus);

        assertThat(equipment.getStatus()).isEqualTo(EquipmentStatus.RUN);
    }

    @Test
    @DisplayName("고장 → 정비 → 복귀 시나리오 전체가 상태 머신을 통과한다")
    void 고장복구_시나리오() {
        Equipment equipment = equipmentWithStatus(EquipmentStatus.RUN);

        List.of(EquipmentStatus.DOWN, EquipmentStatus.PM, EquipmentStatus.IDLE, EquipmentStatus.RUN)
                .forEach(equipment::changeStatus);

        assertThat(equipment.getStatus()).isEqualTo(EquipmentStatus.RUN);
    }

    private Equipment equipmentWithStatus(EquipmentStatus status) {
        return Equipment.builder()
                .code("LAMI-01")
                .name("합착기 1호기")
                .status(status)
                .build();
    }
}
