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
     * 원본 7일 보존 배치용 청크 삭제 (docs/05 §3, docs/15 §7).
     * sensor_data는 soft delete 대상이 아니다 — 대량 시계열이라 물리 삭제가 보존 정책 그 자체다.
     * (soft delete 원칙은 이력성 테이블 대상이며, 1분 집계본이 영구 보관되므로 이력은 남는다.)
     *
     * 한 번에 최대 {@code limit}행만 지워 락 시간·undo 로그·복제 지연을 제한한다. 호출 측이 청크마다 별도 트랜잭션으로
     * 반복 호출한다. {@code measured_at} 단독 인덱스가 있어야 풀스캔을 피한다.
     * MySQL·H2(MySQL 모드) 모두 {@code DELETE ... LIMIT}를 지원한다.
     *
     * @return 이번 호출로 삭제한 행 수 (limit보다 작으면 더 지울 것이 없다는 뜻)
     */
    @Modifying
    @Query(value = "DELETE FROM sensor_data WHERE measured_at < :threshold LIMIT :limit", nativeQuery = true)
    int deleteChunkByMeasuredAtBefore(@Param("threshold") Instant threshold, @Param("limit") int limit);
}
