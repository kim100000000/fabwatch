package com.fabwatch.sensor.repository;

import com.fabwatch.sensor.entity.SensorData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SensorDataRepository extends JpaRepository<SensorData, Long> {

    Optional<SensorData> findFirstBySensorIdOrderByMeasuredAtDesc(Long sensorId);

    List<SensorData> findBySensorIdAndMeasuredAtGreaterThanEqualAndMeasuredAtLessThanOrderByMeasuredAtAsc(
            Long sensorId, Instant from, Instant to);

    /** 1분 집계 스케줄러용 — 지정 구간을 센서별로 집계한다 (docs/05 §3). */
    @Query("""
            SELECT d.sensorId, MIN(d.value), MAX(d.value), AVG(d.value), COUNT(d)
            FROM SensorData d
            WHERE d.measuredAt >= :from AND d.measuredAt < :to
            GROUP BY d.sensorId
            """)
    List<Object[]> aggregateBySensor(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * 원본 7일 보존 배치 (docs/05 §3).
     * sensor_data는 soft delete 대상이 아니다 — 대량 시계열이라 물리 삭제가 보존 정책 그 자체다.
     * (soft delete 원칙은 이력성 테이블 대상이며, 1분 집계본이 영구 보관되므로 이력은 남는다.)
     */
    @Modifying
    @Query("DELETE FROM SensorData d WHERE d.measuredAt < :threshold")
    int deleteByMeasuredAtBefore(@Param("threshold") Instant threshold);
}
