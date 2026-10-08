package com.fabwatch.sensor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 센서 이력 조회 상한 (fabwatch.sensor.query.*, docs/06 §3).
 *
 * @param maxRangeDays      한 번에 조회할 수 있는 최대 기간(일). 기본 31. 초과하면 400 VALIDATION_ERROR.
 * @param maxPointsPerSensor 센서당 응답 최대 포인트 수. 기본 20,000. 1분 집계로 초과하면 5분→1시간 집계로 서버가 확대해 낮춘다.
 */
@ConfigurationProperties(prefix = "fabwatch.sensor.query")
public record SensorQueryProperties(Integer maxRangeDays, Integer maxPointsPerSensor) {

    public static final int DEFAULT_MAX_RANGE_DAYS = 31;
    public static final int DEFAULT_MAX_POINTS_PER_SENSOR = 20_000;

    public SensorQueryProperties {
        if (maxRangeDays == null || maxRangeDays <= 0) {
            maxRangeDays = DEFAULT_MAX_RANGE_DAYS;
        }
        if (maxPointsPerSensor == null || maxPointsPerSensor <= 0) {
            maxPointsPerSensor = DEFAULT_MAX_POINTS_PER_SENSOR;
        }
    }
}
