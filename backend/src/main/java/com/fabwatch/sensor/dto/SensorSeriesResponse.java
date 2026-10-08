package com.fabwatch.sensor.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * GET /equipments/{id}/sensor-data 응답 (docs/06 §3).
 *
 * granularity: 조회 구간이 1시간 이내면 "RAW"(sensor_data), 초과하면 "1M"(sensor_data_1m) — 서버가 자동 선택.
 * 1M으로 센서당 포인트가 상한(fabwatch.sensor.query.max-points-per-sensor, 기본 20,000)을 넘는 긴 구간(기본값 기준 약 13.8일 초과)은
 * 서버가 버킷을 키워 "5M"(5분) → "1H"(1시간)로 내려보낸다. 포인트 shape은 동일하며 value=가중 평균, min/max/sampleCount는 합성값.
 *
 * 포인트 shape은 RAW/1M 공통이다 (프론트가 분기하지 않도록):
 * - RAW  → at=measured_at, value=원본값, minValue/maxValue/sampleCount = null
 * - 1M   → at=bucket_at,   value=avg_v,  minValue=min_v, maxValue=max_v, sampleCount=표본수
 */
public record SensorSeriesResponse(
        Long equipmentId,
        String granularity,
        Instant from,
        Instant to,
        List<Series> series) {

    public record Series(
            Long sensorId,
            String sensorType,
            String unit,
            BigDecimal warnLow,
            BigDecimal warnHigh,
            BigDecimal critLow,
            BigDecimal critHigh,
            List<Point> points) {
    }

    public record Point(
            Instant at,
            BigDecimal value,
            BigDecimal minValue,
            BigDecimal maxValue,
            Integer sampleCount) {
    }

    /** granularity 값 상수 — 프론트와 문자열을 맞추기 위한 단일 출처 */
    public static final String RAW = "RAW";
    public static final String ONE_MINUTE = "1M";
    public static final String FIVE_MINUTES = "5M";
    public static final String ONE_HOUR = "1H";
}
