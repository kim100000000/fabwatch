package com.fabwatch.sensor.service;

import com.fabwatch.common.event.ThresholdExceededEvent;
import com.fabwatch.sensor.dto.SensorStreamPayload;
import com.fabwatch.sensor.entity.SensorData;
import com.fabwatch.sensor.repository.SensorDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 센서 값 수집 파이프라인 (docs/03 F-4.2): SensorDataSource에서 읽기 → DB 저장 → SSE 발행 → 임계치 판정.
 *
 * 데이터 출처를 전혀 모른다 — 시뮬레이터든 실제 PLC 어댑터든 SensorDataSource 구현체만 갈아끼우면 된다
 * (docs/10 ADR-8).
 *
 * 임계치 초과 시 alarm 도메인을 직접 호출하지 않고 ThresholdExceededEvent만 발행한다.
 * 2초마다 계속 발행되며, 알람 폭주 방지(중복 억제)는 alarm 도메인 책임이다 (docs/03 F-4.3).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensorIngestionService {

    private final SensorDataSource sensorDataSource;
    private final SensorQueryService sensorQueryService;
    private final SensorDataRepository sensorDataRepository;
    private final SensorStreamService sensorStreamService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 한 틱 수집. 스케줄러가 2초마다 호출하지만, 테스트는 고정 시각을 넘겨 직접 호출한다
     * (실제 2초를 기다리는 테스트를 만들지 않기 위해 시각을 인자로 받는다).
     *
     * @return 저장된 건수
     */
    @Transactional
    public int ingest(Instant at) {
        List<SensorReading> readings = sensorDataSource.read(at);
        if (readings.isEmpty()) {
            return 0;
        }
        Map<Long, SensorSpec> specs = sensorQueryService.findAllSpecs().stream()
                .collect(java.util.stream.Collectors.toMap(SensorSpec::sensorId, Function.identity()));

        List<SensorData> rows = new ArrayList<>(readings.size());
        List<Runnable> afterSave = new ArrayList<>(readings.size());

        for (SensorReading reading : readings) {
            SensorSpec spec = specs.get(reading.sensorId());
            if (spec == null) {
                continue; // 삭제된 센서 — 조용히 건너뛴다
            }
            rows.add(new SensorData(reading.sensorId(), reading.value(), reading.measuredAt()));

            ThresholdEvaluator.Result judged = ThresholdEvaluator.evaluate(spec, reading.value());
            afterSave.add(() -> {
                sensorStreamService.publishSensor(new SensorStreamPayload(
                        spec.sensorId(), spec.equipmentId(), spec.type(), spec.unit(),
                        reading.value(), reading.measuredAt(), judged.level()));
                if (judged.isAbnormal()) {
                    eventPublisher.publishEvent(new ThresholdExceededEvent(
                            spec.equipmentId(), spec.sensorId(), spec.type(), spec.unit(),
                            reading.value(), judged.thresholdValue(), judged.level(), reading.measuredAt()));
                }
            });
        }

        sensorDataRepository.saveAll(rows);
        afterSave.forEach(Runnable::run);
        return rows.size();
    }
}
