package com.fabwatch.inspection.repository;

import com.fabwatch.inspection.entity.InspectionCheckResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InspectionCheckResultRepository extends JpaRepository<InspectionCheckResult, Long> {

    List<InspectionCheckResult> findByInspectionIdOrderByIdAsc(Long inspectionId);
}
