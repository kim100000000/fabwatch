package com.fabwatch.sensor.repository;

import com.fabwatch.sensor.entity.SensorThresholdLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SensorThresholdLogRepository extends JpaRepository<SensorThresholdLog, Long> {

    Page<SensorThresholdLog> findBySensorIdOrderByChangedAtDesc(Long sensorId, Pageable pageable);
}
