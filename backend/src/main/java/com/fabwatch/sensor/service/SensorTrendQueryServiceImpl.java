package com.fabwatch.sensor.service;

import com.fabwatch.sensor.repository.SensorData1mRepository;
import com.fabwatch.sensor.repository.SensorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SensorTrendQueryServiceImpl implements SensorTrendQueryService {

    private final SensorRepository sensorRepository;
    private final SensorData1mRepository sensorData1mRepository;

    @Override
    public List<SensorTrend> findTrends(Long equipmentId, Instant from, Instant to) {
        return sensorRepository.findByEquipmentIdOrderByTypeAsc(equipmentId).stream()
                .map(sensor -> new SensorTrend(
                        sensor.getId(),
                        sensor.getType().name(),
                        sensor.getUnit(),
                        sensor.getWarnLow(), sensor.getWarnHigh(), sensor.getCritLow(), sensor.getCritHigh(),
                        sensorData1mRepository
                                .findBySensorIdAndBucketAtGreaterThanEqualAndBucketAtLessThanOrderByBucketAtAsc(
                                        sensor.getId(), from, to)
                                .stream()
                                .map(d -> new SensorTrend.Point(d.getBucketAt(), d.getMinV(), d.getMaxV(), d.getAvgV()))
                                .toList()))
                .toList();
    }
}
