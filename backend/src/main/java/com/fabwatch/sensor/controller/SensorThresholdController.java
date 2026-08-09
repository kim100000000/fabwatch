package com.fabwatch.sensor.controller;

import com.fabwatch.common.dto.PageResponse;
import com.fabwatch.common.security.SecurityUtils;
import com.fabwatch.sensor.dto.SensorResponse;
import com.fabwatch.sensor.dto.ThresholdLogResponse;
import com.fabwatch.sensor.dto.ThresholdUpdateRequest;
import com.fabwatch.sensor.service.SensorThresholdService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 센서 임계치 API (docs/06 §2 PUT /equipments/{id}/sensors/{sensorId}/thresholds).
 *
 * 경로는 /equipments 아래지만 자원은 센서이므로 sensor 도메인이 소유한다
 * (equipment 도메인이 Sensor 엔티티를 참조하지 않게 하기 위한 배치 — 도메인 간 직접 참조 금지).
 * 권한: 수정 ADMIN / 조회 전체 (docs/06 §2, docs/11 §3).
 */
@RestController
@RequestMapping("/api/v1/equipments/{equipmentId}/sensors")
@RequiredArgsConstructor
public class SensorThresholdController {

    private final SensorThresholdService sensorThresholdService;

    /** 설비의 센서 정의 목록 (docs/06에 없는 추가 엔드포인트 — 임계치 편집 화면 진입용) */
    @GetMapping
    public PageResponse<SensorResponse> getSensors(@PathVariable Long equipmentId) {
        return sensorThresholdService.getSensors(equipmentId);
    }

    /** 임계치 수정 — crit_low ≤ warn_low < warn_high ≤ crit_high 위반 시 400 INVALID_THRESHOLD_RANGE */
    @PutMapping("/{sensorId}/thresholds")
    @PreAuthorize("hasRole('ADMIN')")
    public SensorResponse updateThresholds(@PathVariable Long equipmentId,
                                           @PathVariable Long sensorId,
                                           @Valid @RequestBody ThresholdUpdateRequest request) {
        return sensorThresholdService.updateThresholds(equipmentId, sensorId, request, SecurityUtils.currentUserId());
    }

    /** 임계치 변경 이력 (docs/06에 없는 추가 엔드포인트 — docs/03 F-2 "누가 언제 왜" 확인용) */
    @GetMapping("/{sensorId}/thresholds/logs")
    public PageResponse<ThresholdLogResponse> getThresholdLogs(@PathVariable Long equipmentId,
                                                               @PathVariable Long sensorId,
                                                               @PageableDefault(size = 20) Pageable pageable) {
        return sensorThresholdService.getThresholdLogs(equipmentId, sensorId, pageable);
    }
}
