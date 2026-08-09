package com.fabwatch.sensor.service;

import java.util.List;
import java.util.Optional;

/**
 * sensor 도메인이 다른 도메인에 노출하는 유일한 조회 통로 (auth의 UserQueryService와 같은 역할).
 * simulator/alarm은 SensorRepository·Sensor 엔티티를 직접 참조하지 않고 이 인터페이스만 쓴다.
 */
public interface SensorQueryService {

    /** 시뮬레이션/수집 대상 전체 센서 정의 */
    List<SensorSpec> findAllSpecs();

    Optional<SensorSpec> findSpec(Long sensorId);

    List<SensorSpec> findSpecsByEquipmentId(Long equipmentId);
}
