package com.fabwatch.common.event;

import java.time.Instant;

/**
 * CRITICAL 알람 발생에 따른 설비 자동 DOWN 요청 (docs/03 F-4.3).
 *
 * alarm 도메인이 발행하고 equipment 도메인이 구독한다. alarm은 Equipment 엔티티/레포지토리를
 * 절대 직접 만지지 않는다 (CLAUDE.md "도메인 간 직접 참조 금지").
 *
 * ★ 안전 게이트(docs/11 §10): 이 이벤트의 처리 결과는 DB `equipments.status` 필드값 변경 + 상태 로그
 *   기록뿐이다. PLC/Modbus 출력 등 실제 설비를 정지시키는 물리적 제어가 아니며, 그런 확장은
 *   기능안전 인증(IEC 61508 등) 검토 없이 구현하지 않는다.
 */
public record EquipmentDownRequestedEvent(
        Long equipmentId,
        Long alarmId,
        String reason,
        Instant requestedAt) {
}
