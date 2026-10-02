package com.fabwatch.equipment.service;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * equipment 도메인이 다른 도메인에 노출하는 유일한 조회 통로 (auth의 UserQueryService와 같은 역할).
 * sensor/alarm/simulator는 EquipmentRepository·Equipment 엔티티를 직접 참조하지 않고 이 인터페이스만 쓴다.
 *
 * 쓰기(상태 전환)는 여기에 두지 않는다 — CRITICAL 자동 DOWN은 EquipmentDownRequestedEvent로만 요청한다.
 */
public interface EquipmentQueryService {

    boolean existsById(Long equipmentId);

    /** 설비 코드 (예: "LAMI-01"). 없거나 삭제된 설비면 empty. */
    Optional<String> findCodeById(Long equipmentId);

    /** 여러 설비 코드를 한 번에 조회 (목록 API의 N+1 방지). key = equipmentId */
    Map<Long, String> findCodesByIds(Collection<Long> equipmentIds);

    /** 설비 이름 (예: "합착기 1호"). 없거나 삭제된 설비면 empty. */
    Optional<String> findNameById(Long equipmentId);

    /** 여러 설비 이름을 한 번에 조회 (목록 API의 N+1 방지). key = equipmentId */
    Map<Long, String> findNamesByIds(Collection<Long> equipmentIds);
}
