package com.fabwatch.inspection.repository;

import com.fabwatch.inspection.entity.Inspection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface InspectionRepository extends JpaRepository<Inspection, Long>, JpaSpecificationExecutor<Inspection> {
}
