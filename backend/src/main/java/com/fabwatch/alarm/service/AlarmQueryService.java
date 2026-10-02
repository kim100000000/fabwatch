package com.fabwatch.alarm.service;

import com.fabwatch.alarm.dto.AlarmSnapshot;

import java.util.Optional;

/**
 * alarm 도메인이 다른 도메인에 노출하는 조회 통로 (쓰기는 AlarmCommandService).
 * aireport는 AlarmRepository/Alarm 엔티티를 직접 참조하지 않고 이 인터페이스만 쓴다.
 */
public interface AlarmQueryService {

    /** 알람 단건 스냅샷. 없거나 삭제된 알람이면 empty. */
    Optional<AlarmSnapshot> findSnapshot(Long alarmId);
}
