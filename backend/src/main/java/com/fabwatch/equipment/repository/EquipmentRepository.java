package com.fabwatch.equipment.repository;

import com.fabwatch.equipment.entity.Equipment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

// TENANT: 목록/상세 조회에 tenant_id 필터 추가 지점
public interface EquipmentRepository extends JpaRepository<Equipment, Long>, JpaSpecificationExecutor<Equipment> {

    Optional<Equipment> findByCode(String code);

    boolean existsByCode(String code);
}
