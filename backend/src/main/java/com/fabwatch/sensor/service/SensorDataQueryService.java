package com.fabwatch.sensor.service;

import com.fabwatch.common.dto.PageResponse;
import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import com.fabwatch.equipment.service.EquipmentQueryService;
import com.fabwatch.sensor.dto.SensorLatestResponse;
import com.fabwatch.sensor.dto.SensorSeriesResponse;
import com.fabwatch.sensor.entity.Sensor;
import com.fabwatch.sensor.entity.SensorData;
import com.fabwatch.sensor.entity.SensorData1m;
import com.fabwatch.sensor.repository.SensorData1mRepository;
import com.fabwatch.sensor.repository.SensorDataRepository;
import com.fabwatch.sensor.repository.SensorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 센서 데이터 조회 (docs/06 §3).
 * 설비 존재 확인은 equipment 도메인의 EquipmentQueryService 인터페이스로만 한다 (엔티티 직접 참조 금지).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SensorDataQueryService {

    /** 이 구간 이내는 원본, 초과하면 1분 집계 (docs/06 §3) */
    public static final Duration RAW_WINDOW = Duration.ofHours(1);

    private final SensorRepository sensorRepository;
    private final SensorDataRepository sensorDataRepository;
    private final SensorData1mRepository sensorData1mRepository;
    private final EquipmentQueryService equipmentQueryService;

    /** GET /equipments/{id}/sensor-data/latest — 센서별 최신값 1건씩 */
    public PageResponse<SensorLatestResponse> getLatest(Long equipmentId) {
        requireEquipment(equipmentId);
        List<SensorLatestResponse> items = sensorRepository.findByEquipmentIdOrderByTypeAsc(equipmentId).stream()
                .map(sensor -> {
                    SensorData latest = sensorDataRepository
                            .findFirstBySensorIdOrderByMeasuredAtDesc(sensor.getId()).orElse(null);
                    var judged = ThresholdEvaluator.evaluate(SensorSpec.from(sensor),
                            latest == null ? null : latest.getValue());
                    return new SensorLatestResponse(
                            sensor.getId(),
                            sensor.getEquipmentId(),
                            sensor.getType().name(),
                            sensor.getUnit(),
                            latest == null ? null : latest.getValue(),
                            latest == null ? null : latest.getMeasuredAt(),
                            judged.level(),
                            sensor.getWarnLow(), sensor.getWarnHigh(), sensor.getCritLow(), sensor.getCritHigh());
                })
                .toList();
        return PageResponse.ofAll(items);
    }

    /**
     * GET /equipments/{id}/sensor-data?sensorType=&from=&to=
     * from/to 생략 시 최근 1시간. 구간이 1시간 이내면 RAW, 초과면 1M을 자동 선택한다.
     */
    public SensorSeriesResponse getSeries(Long equipmentId, Sensor.Type sensorType, Instant from, Instant to) {
        requireEquipment(equipmentId);
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(RAW_WINDOW);
        if (!start.isBefore(end)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "from은 to보다 이전이어야 합니다.");
        }
        boolean raw = Duration.between(start, end).compareTo(RAW_WINDOW) <= 0;

        List<Sensor> sensors = sensorRepository.findByEquipmentIdOrderByTypeAsc(equipmentId).stream()
                .filter(sensor -> sensorType == null || sensor.getType() == sensorType)
                .toList();

        List<SensorSeriesResponse.Series> series = sensors.stream()
                .map(sensor -> new SensorSeriesResponse.Series(
                        sensor.getId(),
                        sensor.getType().name(),
                        sensor.getUnit(),
                        sensor.getWarnLow(), sensor.getWarnHigh(), sensor.getCritLow(), sensor.getCritHigh(),
                        raw ? rawPoints(sensor.getId(), start, end) : aggregatedPoints(sensor.getId(), start, end)))
                .toList();

        return new SensorSeriesResponse(
                equipmentId,
                raw ? SensorSeriesResponse.RAW : SensorSeriesResponse.ONE_MINUTE,
                start, end, series);
    }

    private List<SensorSeriesResponse.Point> rawPoints(Long sensorId, Instant from, Instant to) {
        return sensorDataRepository
                .findBySensorIdAndMeasuredAtGreaterThanEqualAndMeasuredAtLessThanOrderByMeasuredAtAsc(sensorId, from, to)
                .stream()
                .map(d -> new SensorSeriesResponse.Point(d.getMeasuredAt(), d.getValue(), null, null, null))
                .toList();
    }

    private List<SensorSeriesResponse.Point> aggregatedPoints(Long sensorId, Instant from, Instant to) {
        return sensorData1mRepository
                .findBySensorIdAndBucketAtGreaterThanEqualAndBucketAtLessThanOrderByBucketAtAsc(sensorId, from, to)
                .stream()
                .map((SensorData1m d) -> new SensorSeriesResponse.Point(
                        d.getBucketAt(), d.getAvgV(), d.getMinV(), d.getMaxV(), d.getSampleCount()))
                .toList();
    }

    private void requireEquipment(Long equipmentId) {
        if (!equipmentQueryService.existsById(equipmentId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "설비를 찾을 수 없습니다: id=" + equipmentId);
        }
    }
}
