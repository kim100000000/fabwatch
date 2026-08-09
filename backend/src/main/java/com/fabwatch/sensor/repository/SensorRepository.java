package com.fabwatch.sensor.repository;

import com.fabwatch.sensor.entity.Sensor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SensorRepository extends JpaRepository<Sensor, Long> {

    List<Sensor> findByEquipmentIdOrderByTypeAsc(Long equipmentId);

    List<Sensor> findByEquipmentIdInOrderByTypeAsc(List<Long> equipmentIds);

    Optional<Sensor> findByIdAndEquipmentId(Long id, Long equipmentId);

    Optional<Sensor> findByEquipmentIdAndType(Long equipmentId, Sensor.Type type);
}
