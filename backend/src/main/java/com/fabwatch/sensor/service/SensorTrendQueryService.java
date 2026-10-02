package com.fabwatch.sensor.service;

import java.time.Instant;
import java.util.List;

/**
 * sensor 도메인이 aireport에 노출하는 1분 집계 요약 조회 통로.
 * SensorData1mRepository/엔티티를 다른 도메인이 직접 참조하지 않게 한다.
 */
public interface SensorTrendQueryService {

    /**
     * 설비의 센서별 1분 집계(min/max/avg) 추이. 구간은 [from, to).
     * 센서가 없는 설비면 빈 리스트, 센서는 있으나 집계가 없으면 points가 빈 센서가 포함된다.
     */
    List<SensorTrend> findTrends(Long equipmentId, Instant from, Instant to);
}
