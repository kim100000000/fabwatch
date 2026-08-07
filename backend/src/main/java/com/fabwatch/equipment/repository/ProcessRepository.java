package com.fabwatch.equipment.repository;

import com.fabwatch.equipment.entity.Process;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProcessRepository extends JpaRepository<Process, Long> {

    List<Process> findByLineIdOrderBySeqAsc(Long lineId);
}
