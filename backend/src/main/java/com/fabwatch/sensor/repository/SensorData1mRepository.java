package com.fabwatch.sensor.repository;

import com.fabwatch.sensor.entity.SensorData1m;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SensorData1mRepository extends JpaRepository<SensorData1m, Long> {

    Optional<SensorData1m> findBySensorIdAndBucketAt(Long sensorId, Instant bucketAt);

    List<SensorData1m> findBySensorIdAndBucketAtGreaterThanEqualAndBucketAtLessThanOrderByBucketAtAsc(
            Long sensorId, Instant from, Instant to);
}
