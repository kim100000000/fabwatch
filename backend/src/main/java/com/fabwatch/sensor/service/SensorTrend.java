package com.fabwatch.sensor.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 센서 1분 집계 추이의 도메인 외부 노출용 값 객체 (aireport 프롬프트 컨텍스트용).
 * 원본(2초 주기)을 다 내보내지 않는다 — 토큰 절약 (docs/03 F-6.1).
 */
public record SensorTrend(
        Long sensorId,
        /** TEMP / VIBRATION / PRESSURE / CURRENT */
        String type,
        String unit,
        BigDecimal warnLow,
        BigDecimal warnHigh,
        BigDecimal critLow,
        BigDecimal critHigh,
        /** bucketAt 오름차순. 구간에 집계가 없으면 빈 리스트 */
        List<Point> points) {

    public record Point(Instant bucketAt, BigDecimal min, BigDecimal max, BigDecimal avg) {
    }
}
