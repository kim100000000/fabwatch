package com.fabwatch.sensor.controller;

import com.fabwatch.common.dto.PageResponse;
import com.fabwatch.sensor.dto.SensorLatestResponse;
import com.fabwatch.sensor.dto.SensorSeriesResponse;
import com.fabwatch.sensor.entity.Sensor;
import com.fabwatch.sensor.service.SensorDataQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 센서 데이터 조회 API (docs/06 §3).
 *
 * 경로는 /equipments/{id}/... 아래지만 다루는 자원은 센서라서 sensor 도메인이 소유한다
 * (equipment 도메인이 Sensor 엔티티를 참조하지 않도록).
 */
@RestController
@RequestMapping("/api/v1/equipments/{equipmentId}/sensor-data")
@RequiredArgsConstructor
public class SensorDataController {

    private final SensorDataQueryService sensorDataQueryService;

    /** 센서별 최신값 1건씩 (카드용) */
    @GetMapping("/latest")
    public PageResponse<SensorLatestResponse> getLatest(@PathVariable Long equipmentId) {
        return sensorDataQueryService.getLatest(equipmentId);
    }

    /**
     * 기간 조회. 구간이 1시간 이내면 원본(RAW), 초과하면 1분 집계(1M)를 자동 선택하고
     * 응답의 granularity 필드에 명시한다.
     */
    @GetMapping
    public SensorSeriesResponse getSeries(
            @PathVariable Long equipmentId,
            @RequestParam(required = false) Sensor.Type sensorType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return sensorDataQueryService.getSeries(equipmentId, sensorType, from, to);
    }
}
