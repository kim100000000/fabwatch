package com.fabwatch.equipment.repository;

import com.fabwatch.equipment.entity.EquipmentStatusLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentStatusLogRepository extends JpaRepository<EquipmentStatusLog, Long> {

    Page<EquipmentStatusLog> findByEquipmentIdOrderByChangedAtDesc(Long equipmentId, Pageable pageable);
}
