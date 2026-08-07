package com.fabwatch.equipment.repository;

import com.fabwatch.equipment.entity.Line;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LineRepository extends JpaRepository<Line, Long> {

    // TENANT: 멀티테넌트 전환 시 tenant_id 필터 추가 지점
    List<Line> findAllByOrderByIdAsc();
}
