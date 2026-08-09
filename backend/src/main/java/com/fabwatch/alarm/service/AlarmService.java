package com.fabwatch.alarm.service;

import com.fabwatch.alarm.dto.AlarmResolveRequest;
import com.fabwatch.alarm.dto.AlarmResponse;
import com.fabwatch.alarm.dto.ManualAlarmRequest;
import com.fabwatch.alarm.entity.Alarm;
import com.fabwatch.alarm.repository.AlarmRepository;
import com.fabwatch.alarm.repository.AlarmSpecifications;
import com.fabwatch.auth.service.UserQueryService;
import com.fabwatch.common.dto.PageResponse;
import com.fabwatch.common.event.AlarmRaisedEvent;
import com.fabwatch.common.event.EquipmentDownRequestedEvent;
import com.fabwatch.common.event.SensorLevel;
import com.fabwatch.common.event.ThresholdExceededEvent;
import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import com.fabwatch.common.util.Lookup;
import com.fabwatch.equipment.service.EquipmentQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 알람 서비스 (docs/03 F-4.3·F-5.3, docs/06 §6).
 *
 * 도메인 경계:
 * - sensor → alarm : ThresholdExceededEvent 구독 (sensor가 alarm을 직접 호출하지 않는다)
 * - alarm → equipment : EquipmentDownRequestedEvent 발행 (Equipment 엔티티를 직접 만지지 않는다)
 * - alarm → SSE : AlarmRaisedEvent 발행 (sensor의 SSE 브로드캐스터가 구독)
 * - 설비 코드/사용자 이름은 상대 도메인의 QueryService 인터페이스로만 조회
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlarmService {

    private final AlarmRepository alarmRepository;
    private final EquipmentQueryService equipmentQueryService;
    private final UserQueryService userQueryService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 임계치 초과 → 알람 생성 (docs/03 F-4.3).
     * 2초마다 계속 들어오지만 중복 억제로 첫 1건만 만들어진다.
     */
    @EventListener
    @Transactional
    public void onThresholdExceeded(ThresholdExceededEvent event) {
        Alarm.Severity severity = toSeverity(event.level());
        if (severity == null) {
            return;
        }
        String message = "%s %s %s (%s %s)".formatted(
                equipmentQueryService.findCodeById(event.equipmentId()).orElse("설비#" + event.equipmentId()),
                typeLabel(event.sensorType()),
                severity == Alarm.Severity.CRITICAL ? "임계 초과" : "경고",
                event.value(),
                event.unit() == null ? "" : event.unit()).trim();

        raise(Alarm.builder()
                .equipmentId(event.equipmentId())
                .sensorId(event.sensorId())
                .alarmType(Alarm.Type.SENSOR_THRESHOLD)
                .severity(severity)
                .status(Alarm.Status.OPEN)
                .triggerValue(event.value())
                .thresholdValue(event.thresholdValue())
                .message(message)
                .occurredAt(event.occurredAt())
                .build(), event.sensorType());
    }

    /** POST /alarms/manual — 수동 고장 보고 (docs/06 §6). */
    @Transactional
    public AlarmResponse createManual(ManualAlarmRequest request, Long actorUserId) {
        if (!equipmentQueryService.existsById(request.equipmentId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "설비를 찾을 수 없습니다: id=" + request.equipmentId());
        }
        Alarm created = raise(Alarm.builder()
                .equipmentId(request.equipmentId())
                .sensorId(null)
                .alarmType(Alarm.Type.MANUAL)
                .severity(request.severity())
                .status(Alarm.Status.OPEN)
                .message(request.message())
                .occurredAt(Instant.now())
                .build(), null);

        if (created == null) {
            // 중복 억제에 걸린 경우에도 사용자에게는 400이 아니라 기존 알람을 돌려주는 게 자연스럽지만,
            // 조회 키가 애매해 여기서는 예외로 알린다 (수동 보고는 사람이 누르는 것이라 빈도가 낮다).
            throw new BusinessException(ErrorCode.INVALID_ALARM_STATUS,
                    "동일 설비·심각도의 미해결 알람이 이미 있습니다. 기존 알람을 확인하세요.");
        }
        log.info("수동 고장 보고: equipmentId={}, severity={}, by={}",
                request.equipmentId(), request.severity(), actorUserId);
        return toResponse(created);
    }

    /**
     * 알람 생성 공통 경로 — 중복 억제 + CRITICAL 자동 DOWN + SSE 발행이 전부 여기를 지난다.
     *
     * @return 생성된 알람. 중복 억제로 생성하지 않았으면 null.
     */
    private Alarm raise(Alarm candidate, String sensorType) {
        if (isSuppressed(candidate.getEquipmentId(), candidate.getSensorId(), candidate.getSeverity())) {
            log.debug("알람 중복 억제: equipmentId={}, sensorId={}, severity={}",
                    candidate.getEquipmentId(), candidate.getSensorId(), candidate.getSeverity());
            return null;
        }
        Alarm saved = alarmRepository.save(candidate);
        log.info("알람 생성: id={}, equipmentId={}, severity={}, message={}",
                saved.getId(), saved.getEquipmentId(), saved.getSeverity(), saved.getMessage());

        String equipmentCode = equipmentQueryService.findCodeById(saved.getEquipmentId()).orElse(null);
        eventPublisher.publishEvent(new AlarmRaisedEvent(
                saved.getId(), saved.getEquipmentId(), equipmentCode, saved.getSensorId(), sensorType,
                saved.getAlarmType().name(), saved.getSeverity().name(), saved.getStatus().name(),
                saved.getMessage(), saved.getTriggerValue(), saved.getThresholdValue(), saved.getOccurredAt()));

        if (saved.getSeverity() == Alarm.Severity.CRITICAL) {
            // ★ 안전 게이트(docs/11 §10): equipment 도메인이 DB status 필드만 바꾼다. 물리 제어 아님.
            eventPublisher.publishEvent(new EquipmentDownRequestedEvent(
                    saved.getEquipmentId(), saved.getId(),
                    "CRITICAL 알람 자동 DOWN — " + saved.getMessage(), saved.getOccurredAt()));
        }
        return saved;
    }

    /**
     * 중복 억제 (docs/03 F-4.3, docs/05 "중복 억제 조회").
     * {@code WHERE equipment_id=? AND sensor_id=? AND severity=? AND status != 'RESOLVED'}
     */
    private boolean isSuppressed(Long equipmentId, Long sensorId, Alarm.Severity severity) {
        return sensorId == null
                ? alarmRepository.existsByEquipmentIdAndSensorIdIsNullAndSeverityAndStatusNot(
                        equipmentId, severity, Alarm.Status.RESOLVED)
                : alarmRepository.existsByEquipmentIdAndSensorIdAndSeverityAndStatusNot(
                        equipmentId, sensorId, severity, Alarm.Status.RESOLVED);
    }

    /** GET /alarms — 기본 OPEN 우선 정렬 (docs/06 §6) */
    @Transactional(readOnly = true)
    public PageResponse<AlarmResponse> getAlarms(Long equipmentId, Alarm.Status status, Alarm.Severity severity,
                                                 Instant from, Instant to, Pageable pageable) {
        Page<Alarm> page = alarmRepository.findAll(
                AlarmSpecifications.filter(equipmentId, status, severity, from, to), pageable);

        Map<Long, String> equipmentCodes = equipmentQueryService.findCodesByIds(
                page.getContent().stream().map(Alarm::getEquipmentId).toList());
        Map<Long, String> userNames = userQueryService.findNamesByIds(
                page.getContent().stream()
                        .flatMap(alarm -> Stream.of(alarm.getAckBy(), alarm.getResolvedBy()))
                        .filter(Objects::nonNull)
                        .toList());

        // ack_by/resolved_by는 미확인 알람에서 null이다 — 불변 맵의 get(null) NPE를 피하려면 Lookup 필수
        return PageResponse.of(page, alarm -> AlarmResponse.from(alarm,
                Lookup.get(equipmentCodes, alarm.getEquipmentId()),
                Lookup.get(userNames, alarm.getAckBy()),
                Lookup.get(userNames, alarm.getResolvedBy())));
    }

    /** PATCH /alarms/{id}/ack */
    @Transactional
    public AlarmResponse acknowledge(Long alarmId, Long actorUserId) {
        Alarm alarm = findAlarm(alarmId);
        alarm.acknowledge(actorUserId, Instant.now());
        log.info("알람 확인(ACK): id={}, by={}", alarmId, actorUserId);
        return toResponse(alarm);
    }

    /** PATCH /alarms/{id}/resolve — ACK 상태에서만 가능(400 ACK_REQUIRED_FIRST), 사유 필수 */
    @Transactional
    public AlarmResponse resolve(Long alarmId, AlarmResolveRequest request, Long actorUserId) {
        Alarm alarm = findAlarm(alarmId);
        alarm.resolve(actorUserId, request.resolveNote(), Instant.now());
        log.info("알람 해제(RESOLVED): id={}, by={}", alarmId, actorUserId);
        return toResponse(alarm);
    }

    private Alarm findAlarm(Long alarmId) {
        return alarmRepository.findById(alarmId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "알람을 찾을 수 없습니다: id=" + alarmId));
    }

    private AlarmResponse toResponse(Alarm alarm) {
        return AlarmResponse.from(alarm,
                equipmentQueryService.findCodeById(alarm.getEquipmentId()).orElse(null),
                nameOf(alarm.getAckBy()),
                nameOf(alarm.getResolvedBy()));
    }

    private String nameOf(Long userId) {
        return userId == null ? null : userQueryService.findNameById(userId).orElse(null);
    }

    /** 임계치 판정 결과 → 알람 심각도 (docs/03 F-4.3. MAJOR는 PM 지연 등 시스템 규칙 알람 전용) */
    private static Alarm.Severity toSeverity(SensorLevel level) {
        if (level == SensorLevel.WARNING) {
            return Alarm.Severity.WARNING;
        }
        if (level == SensorLevel.CRITICAL) {
            return Alarm.Severity.CRITICAL;
        }
        return null;
    }

    private static String typeLabel(String sensorType) {
        return Optional.ofNullable(sensorType).map(type -> switch (type) {
            case "TEMP" -> "온도";
            case "VIBRATION" -> "진동";
            case "PRESSURE" -> "압력";
            case "CURRENT" -> "전류";
            default -> type;
        }).orElse("센서");
    }

    /** 미해결(OPEN/ACK) 알람 수 — 설비 상세/카드 배지용 */
    @Transactional(readOnly = true)
    public long countUnresolved(Long equipmentId) {
        return alarmRepository.countByEquipmentIdAndStatusNot(equipmentId, Alarm.Status.RESOLVED);
    }
}
