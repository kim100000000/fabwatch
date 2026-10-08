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
import com.fabwatch.common.event.PmOverdueEvent;
import com.fabwatch.common.event.SensorLevel;
import com.fabwatch.common.event.ThresholdExceededEvent;
import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import com.fabwatch.common.util.LogSanitizer;
import com.fabwatch.common.util.PageableUtil;
import com.fabwatch.common.util.Lookup;
import com.fabwatch.equipment.service.EquipmentQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
public class AlarmService implements AlarmCommandService {

    private final AlarmRepository alarmRepository;
    private final EquipmentQueryService equipmentQueryService;
    private final UserQueryService userQueryService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 임계치 초과 → 알람 생성 (docs/03 F-4.3).
     * 2초마다 계속 들어오지만 중복 억제로 첫 1건만 만들어진다.
     *
     * 이벤트마다 **자체 새 트랜잭션**(REQUIRES_NEW)이다 — 수집 틱의 원본 저장은 이미 커밋돼 있고, 한 센서의 알람 처리 실패가
     * 다른 센서의 알람을 롤백시키지 않는다(안정성 감사 H-3). 알람 저장과 CRITICAL 자동 DOWN(EquipmentDownRequestedEvent의
     * 동기 리스너)은 이 트랜잭션 하나에서 함께 커밋/롤백된다 — 따로 커밋하면 "알람은 있는데 DOWN은 실패"한 뒤
     * 중복 억제 때문에 DOWN이 영영 재시도되지 않는 상태가 생기기 때문이다. SSE(alarm/status)는 커밋 후에 나간다.
     */
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onThresholdExceeded(ThresholdExceededEvent event) {
        Alarm.Severity severity = toSeverity(event.level());
        if (severity == null) {
            return;
        }
        // 억제 판정을 먼저 한다 — 2초마다 들어오는 대부분의 이벤트는 억제되므로 설비 코드 조회·메시지 조립을 아낀다 (L-1)
        if (isSuppressed(event.equipmentId(), event.sensorId(), severity)) {
            log.debug("알람 중복 억제: equipmentId={}, sensorId={}, severity={}",
                    event.equipmentId(), event.sensorId(), severity);
            return;
        }
        String message = "%s %s %s (%s %s)".formatted(
                equipmentQueryService.findCodeById(event.equipmentId()).orElse("설비#" + event.equipmentId()),
                typeLabel(event.sensorType()),
                severity == Alarm.Severity.CRITICAL ? "임계 초과" : "경고",
                event.value(),
                event.unit() == null ? "" : event.unit()).trim();

        persist(Alarm.builder()
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

    /**
     * PM 지연 → MAJOR 알람 생성 (docs/03 F-3.3). inspection 스케줄러가 발행한 PmOverdueEvent를 구독한다.
     * 메시지 형식은 시드 알람과 동일: "LAMI-02 PM 예정일 경과 (3일)".
     * 같은 설비의 미해결 PM_OVERDUE 알람이 이미 있으면 중복 생성하지 않는다(raise의 유형별 중복 억제).
     */
    @EventListener
    @Transactional
    public void onPmOverdue(PmOverdueEvent event) {
        String message = "%s PM 예정일 경과 (%d일)".formatted(
                equipmentQueryService.findCodeById(event.equipmentId()).orElse("설비#" + event.equipmentId()),
                event.overdueDays());
        raise(Alarm.builder()
                .equipmentId(event.equipmentId())
                .sensorId(null)
                .alarmType(Alarm.Type.PM_OVERDUE)
                .severity(Alarm.Severity.MAJOR)
                .status(Alarm.Status.OPEN)
                .message(message)
                .occurredAt(event.detectedAt())
                .build(), null);
    }

    @Override
    @Transactional(readOnly = true)
    public void assertBelongsToEquipment(Long alarmId, Long equipmentId) {
        Alarm alarm = findAlarm(alarmId);
        if (!alarm.getEquipmentId().equals(equipmentId)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "연계할 알람이 해당 설비의 알람이 아닙니다: alarmId=" + alarmId);
        }
    }

    /** 호출 측(inspection) 트랜잭션에 참여한다 — 점검 이력 저장과 알람 해제가 함께 커밋/롤백된다. */
    @Override
    @Transactional
    public boolean ackAndResolve(Long alarmId, Long actorUserId, String resolveNote) {
        boolean resolved = ackAndResolve(findAlarm(alarmId), actorUserId, resolveNote);
        if (resolved) {
            log.info("알람 해제(BM 점검 연계): id={}, by={}", alarmId, actorUserId);
        }
        return resolved;
    }

    /**
     * PM 점검 이력 연계 해소 — 설비의 미해결 PM_OVERDUE 알람을 전부 ackAndResolve 규칙으로 닫는다.
     * 호출 측(inspection) 트랜잭션에 참여하므로 해소 중 예외가 나면 점검 이력·스케줄 갱신까지 함께 롤백된다.
     */
    @Override
    @Transactional
    public int resolvePmOverdueByEquipment(Long equipmentId, Long actorUserId, String resolveNote) {
        int resolved = 0;
        for (Alarm alarm : alarmRepository.findByEquipmentIdAndAlarmTypeAndStatusNot(
                equipmentId, Alarm.Type.PM_OVERDUE, Alarm.Status.RESOLVED)) {
            if (ackAndResolve(alarm, actorUserId, resolveNote)) {
                resolved++;
                log.info("알람 해제(PM 점검 연계): id={}, equipmentId={}, by={}", alarm.getId(), equipmentId, actorUserId);
            }
        }
        return resolved;
    }

    /** OPEN→ACK→RESOLVED 흐름을 지키는 공통 전이. 이미 RESOLVED면 건드리지 않고 false. */
    private boolean ackAndResolve(Alarm alarm, Long actorUserId, String resolveNote) {
        if (alarm.getStatus() == Alarm.Status.RESOLVED) {
            return false;
        }
        Instant now = Instant.now();
        if (alarm.getStatus() == Alarm.Status.OPEN) {
            alarm.acknowledge(actorUserId, now);
        }
        alarm.resolve(actorUserId, resolveNote, now);
        return true;
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
     * 알람 생성 공통 경로 — 중복 억제 + CRITICAL 자동 DOWN + SSE 발행이 전부 여기를 지난다
     * (센서 임계치 경로는 억제 판정을 앞당겨 한 뒤 {@link #persist}로 직행).
     *
     * @return 생성된 알람. 중복 억제로 생성하지 않았으면 null.
     */
    private Alarm raise(Alarm candidate, String sensorType) {
        if (isSuppressed(candidate)) {
            log.debug("알람 중복 억제: equipmentId={}, sensorId={}, severity={}",
                    candidate.getEquipmentId(), candidate.getSensorId(), candidate.getSeverity());
            return null;
        }
        return persist(candidate, sensorType);
    }

    /**
     * 억제 판정을 이미 통과한 알람을 저장하고 후속 이벤트를 발행한다 (저장 → AlarmRaisedEvent → CRITICAL이면 DOWN 요청).
     * 센서 임계치 경로는 설비 코드 조회·메시지 조립을 아끼려고 억제 판정을 먼저 하고 곧바로 이 메서드를 부른다.
     */
    private Alarm persist(Alarm candidate, String sensorType) {
        Alarm saved = alarmRepository.save(candidate);
        log.info("알람 생성: id={}, equipmentId={}, severity={}, message={}",
                saved.getId(), saved.getEquipmentId(), saved.getSeverity(), LogSanitizer.clean(saved.getMessage()));

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
     * PM 지연 알람은 설비당 미해결 1건이면 충분하다 — 시스템 알람(sensor_id NULL) 공통 억제는
     * 수동 MAJOR 보고와 서로를 가리므로, 유형(PM_OVERDUE)까지 포함해 판정한다.
     */
    private boolean isSuppressed(Alarm candidate) {
        if (candidate.getAlarmType() == Alarm.Type.PM_OVERDUE) {
            return alarmRepository.existsByEquipmentIdAndAlarmTypeAndStatusNot(
                    candidate.getEquipmentId(), Alarm.Type.PM_OVERDUE, Alarm.Status.RESOLVED);
        }
        return isSuppressed(candidate.getEquipmentId(), candidate.getSensorId(), candidate.getSeverity());
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
        // 정렬은 Specification이 고정(OPEN 우선 → 최신순)한다. 클라이언트 sort는 무시 — ?sort=foo가 500이 되지 않게
        Page<Alarm> page = alarmRepository.findAll(
                AlarmSpecifications.filter(equipmentId, status, severity, from, to), PageableUtil.ignoreSort(pageable));

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
