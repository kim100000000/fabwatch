package com.fabwatch.alarm.controller;

import com.fabwatch.alarm.dto.AlarmResolveRequest;
import com.fabwatch.alarm.dto.AlarmResponse;
import com.fabwatch.alarm.dto.ManualAlarmRequest;
import com.fabwatch.alarm.entity.Alarm;
import com.fabwatch.alarm.service.AlarmService;
import com.fabwatch.common.dto.PageResponse;
import com.fabwatch.common.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 알람 API (docs/06 §6). 권한은 전부 "전체"(인증된 사용자) — docs/11 §3 매트릭스 기준.
 *
 * 정렬은 서버가 고정한다(OPEN → ACK → RESOLVED, 그 안에서 최신순). Pageable의 sort는 받지 않는다.
 */
@RestController
@RequestMapping("/api/v1/alarms")
@RequiredArgsConstructor
public class AlarmController {

    private final AlarmService alarmService;

    @GetMapping
    public PageResponse<AlarmResponse> getAlarms(
            @RequestParam(required = false) Long equipmentId,
            @RequestParam(required = false) Alarm.Status status,
            @RequestParam(required = false) Alarm.Severity severity,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 20) Pageable pageable) {
        return alarmService.getAlarms(equipmentId, status, severity, from, to, pageable);
    }

    /** 확인 처리 — 본인 기록. OPEN이 아니면 400 INVALID_ALARM_STATUS */
    @PatchMapping("/{id}/ack")
    public AlarmResponse acknowledge(@PathVariable Long id) {
        return alarmService.acknowledge(id, SecurityUtils.currentUserId());
    }

    /** 해제 — ACK 상태에서만 가능(400 ACK_REQUIRED_FIRST), resolveNote 필수 */
    @PatchMapping("/{id}/resolve")
    public AlarmResponse resolve(@PathVariable Long id, @Valid @RequestBody AlarmResolveRequest request) {
        return alarmService.resolve(id, request, SecurityUtils.currentUserId());
    }

    /** 수동 고장 보고. CRITICAL이면 설비 자동 DOWN (DB 필드 변경만 — docs/11 §10) */
    @PostMapping("/manual")
    @ResponseStatus(HttpStatus.CREATED)
    public AlarmResponse createManual(@Valid @RequestBody ManualAlarmRequest request) {
        return alarmService.createManual(request, SecurityUtils.currentUserId());
    }
}
