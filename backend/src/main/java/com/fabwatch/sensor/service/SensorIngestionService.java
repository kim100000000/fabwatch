package com.fabwatch.sensor.service;

import com.fabwatch.common.event.ThresholdExceededEvent;
import com.fabwatch.sensor.dto.SensorStreamPayload;
import com.fabwatch.sensor.entity.SensorData;
import com.fabwatch.sensor.repository.SensorDataRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 센서 값 수집 파이프라인 (docs/03 F-4.2): SensorDataSource에서 읽기 → DB 저장(커밋) → SSE 발행 → 임계치 판정.
 *
 * 데이터 출처를 전혀 모른다 — 시뮬레이터든 실제 PLC 어댑터든 SensorDataSource 구현체만 갈아끼우면 된다
 * (docs/10 ADR-8).
 *
 * 트랜잭션 경계 (안정성 감사 H-3, docs/15 §5):
 * 1. 원본 저장은 **한 트랜잭션(saveAll)으로 먼저 커밋**한다. 이 메서드 자체는 트랜잭션이 아니다.
 * 2. 커밋 **이후** 센서별로 SSE 발행과 임계치 초과 이벤트 발행을 한다. SSE는 큐에 넣고 반환(비블로킹)한다.
 * 3. 알람 생성·자동 DOWN은 alarm 리스너가 이벤트마다 **자체 새 트랜잭션**으로 처리한다(알람+자동 DOWN은 한 쌍이라 함께 커밋/롤백).
 * 4. 센서별 try/catch로 오류를 격리한다 — 한 센서의 알람 처리 실패가 다른 센서·이미 저장된 원본에 영향을 주지 않는다.
 *    실패한 센서는 다음 틱(2초 뒤)에 같은 판정으로 자연 재시도된다(중복 억제 덕에 알람이 이미 있으면 건너뜀).
 * 이렇게 하면 수집 틱이 DB 커넥션을 쥔 채 SSE 전송이나 알람 로직을 기다리지 않고, SSE로 나간 값은 항상 커밋된 값이다.
 *
 * 임계치 초과 시 alarm 도메인을 직접 호출하지 않고 ThresholdExceededEvent만 발행한다.
 * 2초마다 계속 발행되며, 알람 폭주 방지(중복 억제)는 alarm 도메인 책임이다 (docs/03 F-4.3).
 */
@Slf4j
@Service
public class SensorIngestionService {

    private final SensorDataSource sensorDataSource;
    private final SensorQueryService sensorQueryService;
    private final SensorDataRepository sensorDataRepository;
    private final SensorStreamService sensorStreamService;
    private final ApplicationEventPublisher eventPublisher;
    /** 원본 저장 전용 짧은 트랜잭션 */
    private final TransactionTemplate saveTransaction;

    public SensorIngestionService(SensorDataSource sensorDataSource,
                                  SensorQueryService sensorQueryService,
                                  SensorDataRepository sensorDataRepository,
                                  SensorStreamService sensorStreamService,
                                  ApplicationEventPublisher eventPublisher,
                                  PlatformTransactionManager transactionManager) {
        this.sensorDataSource = sensorDataSource;
        this.sensorQueryService = sensorQueryService;
        this.sensorDataRepository = sensorDataRepository;
        this.sensorStreamService = sensorStreamService;
        this.eventPublisher = eventPublisher;
        this.saveTransaction = new TransactionTemplate(transactionManager);
    }

    /** 커밋 후 처리할 센서 1건 */
    private record Judged(SensorSpec spec, SensorReading reading, ThresholdEvaluator.Result result) {
    }

    /**
     * 한 틱 수집. 스케줄러가 2초마다 호출하지만, 테스트는 고정 시각을 넘겨 직접 호출한다
     * (실제 2초를 기다리는 테스트를 만들지 않기 위해 시각을 인자로 받는다).
     *
     * @return 저장된 건수
     */
    public int ingest(Instant at) {
        List<SensorReading> readings = sensorDataSource.read(at);
        if (readings.isEmpty()) {
            return 0;
        }
        Map<Long, SensorSpec> specs = sensorQueryService.findAllSpecs().stream()
                .collect(Collectors.toMap(SensorSpec::sensorId, Function.identity()));

        List<SensorData> rows = new ArrayList<>(readings.size());
        List<Judged> judged = new ArrayList<>(readings.size());
        for (SensorReading reading : readings) {
            SensorSpec spec = specs.get(reading.sensorId());
            if (spec == null) {
                continue; // 삭제된 센서 — 조용히 건너뛴다
            }
            rows.add(new SensorData(reading.sensorId(), reading.value(), reading.measuredAt()));
            judged.add(new Judged(spec, reading, ThresholdEvaluator.evaluate(spec, reading.value())));
        }

        // 1) 원본은 한 트랜잭션으로 커밋한다. 실패하면 예외가 그대로 올라가 스케줄러가 기록하고 다음 틱에 재시도한다.
        saveTransaction.executeWithoutResult(status -> sensorDataRepository.saveAll(rows));

        // 2) 커밋 이후: 센서별로 오류를 격리해 SSE 발행 + 임계치 이벤트 발행
        for (Judged item : judged) {
            publishAfterCommit(item);
        }
        return rows.size();
    }

    private void publishAfterCommit(Judged item) {
        SensorSpec spec = item.spec();
        SensorReading reading = item.reading();
        ThresholdEvaluator.Result result = item.result();
        try {
            sensorStreamService.publishSensor(new SensorStreamPayload(
                    spec.sensorId(), spec.equipmentId(), spec.type(), spec.unit(),
                    reading.value(), reading.measuredAt(), result.level()));
        } catch (RuntimeException e) {
            log.warn("센서 SSE 발행 실패: sensorId={}, 예외={}", spec.sensorId(), e.getClass().getSimpleName());
        }
        if (!result.isAbnormal()) {
            return;
        }
        try {
            eventPublisher.publishEvent(new ThresholdExceededEvent(
                    spec.equipmentId(), spec.sensorId(), spec.type(), spec.unit(),
                    reading.value(), result.thresholdValue(), result.level(), reading.measuredAt()));
        } catch (RuntimeException e) {
            // 알람 리스너의 트랜잭션은 이미 롤백됐다. 원본·다른 센서에는 영향 없음 — 다음 틱에 재판정된다.
            log.warn("임계치 이벤트 처리 실패(다음 틱 재시도): sensorId={}, 예외={}",
                    spec.sensorId(), e.getClass().getSimpleName());
        }
    }
}
