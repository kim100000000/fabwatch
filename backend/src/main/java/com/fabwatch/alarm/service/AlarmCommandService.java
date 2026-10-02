package com.fabwatch.alarm.service;

/**
 * alarm 도메인이 다른 도메인(inspection)에 노출하는 쓰기 통로.
 * 다른 도메인은 AlarmRepository/Alarm 엔티티를 직접 참조하지 않고 이 인터페이스만 쓴다.
 */
public interface AlarmCommandService {

    /**
     * 알람이 존재하고 지정한 설비의 알람인지 검증한다.
     * 알람이 없으면 404 NOT_FOUND, 다른 설비 알람이면 400 VALIDATION_ERROR.
     */
    void assertBelongsToEquipment(Long alarmId, Long equipmentId);

    /**
     * BM 점검 이력 연계 해제 — 알람 흐름(OPEN→ACK→RESOLVED)을 지키기 위해
     * OPEN이면 actor 명의로 먼저 ACK 후 RESOLVE, ACK면 RESOLVE, 이미 RESOLVED면 아무것도 하지 않는다.
     *
     * @return 이번 호출로 새로 RESOLVED 처리했으면 true, 이미 RESOLVED여서 건너뛰었으면 false
     */
    boolean ackAndResolve(Long alarmId, Long actorUserId, String resolveNote);

    /**
     * PM 수행 완료 → 해당 설비의 미해결(OPEN/ACK) PM_OVERDUE 알람을 모두 해소한다 (docs/03 F-3.3).
     * 각 알람은 {@link #ackAndResolve}와 같은 규칙(OPEN이면 actor 명의 ACK 후 RESOLVE, ACK면 RESOLVE)을 따른다.
     * 이미 RESOLVED이거나 알람이 없으면 아무 일도 하지 않는다(에러 없음).
     *
     * @return 이번 호출로 새로 RESOLVED 처리한 알람 수
     */
    int resolvePmOverdueByEquipment(Long equipmentId, Long actorUserId, String resolveNote);
}
