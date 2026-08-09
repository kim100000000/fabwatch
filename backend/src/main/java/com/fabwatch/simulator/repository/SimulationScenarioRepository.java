package com.fabwatch.simulator.repository;

import com.fabwatch.simulator.entity.SimulationScenario;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SimulationScenarioRepository extends JpaRepository<SimulationScenario, Long> {

    List<SimulationScenario> findByActiveTrueOrderByIdAsc();

    boolean existsBySensorIdAndTypeAndActiveTrue(Long sensorId, SimulationScenario.Type type);
}
