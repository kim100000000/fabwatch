package com.fabwatch.sensor.service;

import com.fabwatch.sensor.repository.SensorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SensorQueryServiceImpl implements SensorQueryService {

    private final SensorRepository sensorRepository;

    @Override
    public List<SensorSpec> findAllSpecs() {
        return sensorRepository.findAll().stream().map(SensorSpec::from).toList();
    }

    @Override
    public Optional<SensorSpec> findSpec(Long sensorId) {
        return sensorRepository.findById(sensorId).map(SensorSpec::from);
    }

    @Override
    public List<SensorSpec> findSpecsByEquipmentId(Long equipmentId) {
        return sensorRepository.findByEquipmentIdOrderByTypeAsc(equipmentId).stream().map(SensorSpec::from).toList();
    }
}
