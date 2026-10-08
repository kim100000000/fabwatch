package com.fabwatch.sensor.service;

import com.fabwatch.auth.service.UserQueryService;
import com.fabwatch.common.dto.PageResponse;
import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import com.fabwatch.common.util.Lookup;
import com.fabwatch.common.util.PageableUtil;
import com.fabwatch.equipment.service.EquipmentQueryService;
import com.fabwatch.sensor.dto.SensorResponse;
import com.fabwatch.sensor.dto.ThresholdLogResponse;
import com.fabwatch.sensor.dto.ThresholdUpdateRequest;
import com.fabwatch.sensor.entity.Sensor;
import com.fabwatch.sensor.entity.SensorThresholdLog;
import com.fabwatch.sensor.repository.SensorRepository;
import com.fabwatch.sensor.repository.SensorThresholdLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 센서 임계치 설정 (docs/03 F-2 "임계치 설정", FR-2.3 / docs/06 §2 PUT thresholds).
 *
 * 규칙 두 가지가 전부다:
 * 1) crit_low ≤ warn_low < warn_high ≤ crit_high 검증 (ThresholdRangeValidator)
 * 2) 변경 이력 기록 (누가·언제·왜) — 현장에서 임계치 임의 변경은 사고의 씨앗
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensorThresholdService {

    private final SensorRepository sensorRepository;
    private final SensorThresholdLogRepository thresholdLogRepository;
    private final EquipmentQueryService equipmentQueryService;
    private final UserQueryService userQueryService;

    /** GET /equipments/{id}/sensors — 설비의 센서 정의 목록 (임계치 화면 진입용) */
    @Transactional(readOnly = true)
    public PageResponse<SensorResponse> getSensors(Long equipmentId) {
        requireEquipment(equipmentId);
        return PageResponse.ofAll(sensorRepository.findByEquipmentIdOrderByTypeAsc(equipmentId).stream()
                .map(SensorResponse::from)
                .toList());
    }

    /** PUT /equipments/{id}/sensors/{sensorId}/thresholds (ADMIN) */
    @Transactional
    public SensorResponse updateThresholds(Long equipmentId, Long sensorId,
                                           ThresholdUpdateRequest request, Long actorUserId) {
        requireEquipment(equipmentId);
        Sensor sensor = sensorRepository.findByIdAndEquipmentId(sensorId, equipmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "해당 설비의 센서를 찾을 수 없습니다: equipmentId=%d, sensorId=%d".formatted(equipmentId, sensorId)));

        ThresholdRangeValidator.validate(request.warnLow(), request.warnHigh(), request.critLow(), request.critHigh());

        SensorThresholdLog history = SensorThresholdLog.builder()
                .sensorId(sensor.getId())
                .equipmentId(sensor.getEquipmentId())
                .oldWarnLow(sensor.getWarnLow())
                .oldWarnHigh(sensor.getWarnHigh())
                .oldCritLow(sensor.getCritLow())
                .oldCritHigh(sensor.getCritHigh())
                .newWarnLow(request.warnLow())
                .newWarnHigh(request.warnHigh())
                .newCritLow(request.critLow())
                .newCritHigh(request.critHigh())
                .reason(request.reason().trim())
                .changedBy(actorUserId)
                .changedAt(Instant.now())
                .build();

        sensor.updateThresholds(request.warnLow(), request.warnHigh(), request.critLow(), request.critHigh());
        thresholdLogRepository.save(history);

        // 사유는 사용자 자유 입력이라 로그에 남기지 않는다(변경 이력 테이블에 보존됨, 로그 인젝션 방지)
        log.info("임계치 변경: sensorId={}, warn[{}~{}], crit[{}~{}], by={}",
                sensor.getId(), request.warnLow(), request.warnHigh(),
                request.critLow(), request.critHigh(), actorUserId);
        return SensorResponse.from(sensor);
    }

    /**
     * GET /equipments/{id}/sensors/{sensorId}/thresholds/logs — 임계치 변경 이력.
     * docs/06에 없는 추가 엔드포인트다 (이력을 남기라는 docs/03 F-2 요구를 확인 가능하게 만들기 위함).
     */
    @Transactional(readOnly = true)
    public PageResponse<ThresholdLogResponse> getThresholdLogs(Long equipmentId, Long sensorId, Pageable pageable) {
        requireEquipment(equipmentId);
        sensorRepository.findByIdAndEquipmentId(sensorId, equipmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "해당 설비의 센서를 찾을 수 없습니다: equipmentId=%d, sensorId=%d".formatted(equipmentId, sensorId)));

        Page<SensorThresholdLog> page = thresholdLogRepository.findBySensorIdOrderByChangedAtDesc(sensorId, PageableUtil.ignoreSort(pageable));
        Map<Long, String> names = userQueryService.findNamesByIds(
                page.getContent().stream().map(SensorThresholdLog::getChangedBy).filter(Objects::nonNull).toList());
        return PageResponse.of(page, log -> ThresholdLogResponse.from(log, Lookup.get(names, log.getChangedBy())));
    }

    private void requireEquipment(Long equipmentId) {
        if (!equipmentQueryService.existsById(equipmentId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "설비를 찾을 수 없습니다: id=" + equipmentId);
        }
    }
}
