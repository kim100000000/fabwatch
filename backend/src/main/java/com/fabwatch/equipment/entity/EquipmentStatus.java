package com.fabwatch.equipment.entity;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 설비 상태 머신 (docs/03 F-2 — 현장 규칙 그대로).
 *
 * <pre>
 * RUN ↔ IDLE            (수동 전환)
 * RUN/IDLE → DOWN       (CRITICAL 알람 자동 or 수동 고장 보고)
 * DOWN → IDLE           (수리 완료 → 시운전 대기. 사유 필수, 전 역할 가능)
 * DOWN → RUN            (시운전 생략 복귀. 사유 필수, ENGINEER 이상만)
 * DOWN → PM             (정비 병행 정기 PM 착수 시 수동)
 * PM → IDLE             (정비 완료 → 시운전 대기. 바로 RUN 불가가 현장 원칙)
 * RUN/IDLE → PM         (정기 PM 착수)
 * </pre>
 *
 * DOWN = BM(계획 외 정지). 이 enum은 "전이 가능 여부"만 판정하고, 사유 필수·역할 제한은
 * EquipmentService.changeStatus()의 한 곳에서 판정한다.
 *
 * 이 표에 없는 전환은 전부 거부한다(400 INVALID_STATUS_TRANSITION). 동일 상태로의 전환도 거부 —
 * 상태 로그가 KPI(MTBF/MTTR) 원천이라 의미 없는 전이가 섞이면 계산이 오염된다.
 *
 * ※ 범위 한정: 상태 전환은 DB equipment.status 필드값 변경일 뿐, 실제 설비를 정지시키는
 *   물리적 제어(PLC/Modbus 출력)가 아니다 (docs/03 F-2, docs/11 §10).
 */
public enum EquipmentStatus {

    RUN,
    IDLE,
    DOWN,
    PM;

    private static final Map<EquipmentStatus, Set<EquipmentStatus>> ALLOWED_TRANSITIONS;

    static {
        Map<EquipmentStatus, Set<EquipmentStatus>> map = new EnumMap<>(EquipmentStatus.class);
        map.put(RUN, EnumSet.of(IDLE, DOWN, PM));
        map.put(IDLE, EnumSet.of(RUN, DOWN, PM));
        map.put(DOWN, EnumSet.of(IDLE, RUN, PM));
        map.put(PM, EnumSet.of(IDLE));
        ALLOWED_TRANSITIONS = Collections.unmodifiableMap(map);
    }

    /** 이 상태에서 to로 전환 가능한가. 상태 전이 판정은 이 메서드 한 곳에만 존재한다. */
    public boolean canTransitionTo(EquipmentStatus to) {
        return to != null && ALLOWED_TRANSITIONS.get(this).contains(to);
    }

    public Set<EquipmentStatus> allowedTargets() {
        return ALLOWED_TRANSITIONS.get(this);
    }
}
