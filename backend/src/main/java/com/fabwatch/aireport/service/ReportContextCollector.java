package com.fabwatch.aireport.service;

import com.fabwatch.aireport.entity.AiReport;
import com.fabwatch.aireport.prompt.ReportContext;
import com.fabwatch.alarm.dto.AlarmSnapshot;
import com.fabwatch.alarm.service.AlarmQueryService;
import com.fabwatch.auth.service.UserQueryService;
import com.fabwatch.equipment.service.EquipmentQueryService;
import com.fabwatch.inspection.service.InspectionQueryService;
import com.fabwatch.inspection.service.InspectionSummary;
import com.fabwatch.sensor.service.SensorQueryService;
import com.fabwatch.sensor.service.SensorSpec;
import com.fabwatch.sensor.service.SensorTrend;
import com.fabwatch.sensor.service.SensorTrendQueryService;
import com.fabwatch.common.util.Lookup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 리포트 컨텍스트 수집 (docs/03 F-6.1). 다른 도메인은 전부 서비스 인터페이스로만 조회한다
 * (alarm / sensor / inspection / equipment / auth — repository·entity import 없음).
 *
 * 개인정보: 작업자 이름은 조회하지 않는다. 역할(findRolesByIds)과 교대만 컨텍스트에 담는다.
 */
@Component
@RequiredArgsConstructor
public class ReportContextCollector {

    /** 기준 시각 전 30분 ~ 후 10분 (docs/03 F-6.1 ②) */
    static final Duration BEFORE = Duration.ofMinutes(30);
    static final Duration AFTER = Duration.ofMinutes(10);
    static final int RECENT_INSPECTIONS = 5;
    static final int RECENT_BM = 3;

    private final EquipmentQueryService equipmentQueryService;
    private final AlarmQueryService alarmQueryService;
    private final SensorQueryService sensorQueryService;
    private final SensorTrendQueryService sensorTrendQueryService;
    private final InspectionQueryService inspectionQueryService;
    private final UserQueryService userQueryService;

    public ReportContext collect(AiReport report) {
        Long equipmentId = report.getEquipmentId();
        String code = equipmentQueryService.findCodeById(equipmentId).orElse("설비#" + equipmentId);
        String name = equipmentQueryService.findNameById(equipmentId).orElse(null);

        InspectionSummary inspection = report.getInspectionId() == null ? null
                : inspectionQueryService.findSummary(report.getInspectionId()).orElse(null);
        // 알람 없이 점검만 지정했으면 그 점검에 연계된 알람(있으면)을 포함한다
        Long alarmId = report.getAlarmId() != null ? report.getAlarmId()
                : (inspection == null ? null : inspection.alarmId());
        AlarmSnapshot alarm = alarmId == null ? null : alarmQueryService.findSnapshot(alarmId).orElse(null);

        Instant anchor = alarm != null ? alarm.occurredAt()
                : inspection != null ? inspection.startedAt() : report.getCreatedAt();
        Instant from = anchor.minus(BEFORE);
        Instant to = anchor.plus(AFTER);

        List<ReportContext.SensorInfo> sensors = sensorTrendQueryService.findTrends(equipmentId, from, to).stream()
                .filter(trend -> !trend.points().isEmpty())
                .map(ReportContextCollector::toSensorInfo)
                .toList();

        List<InspectionSummary> recent = inspectionQueryService.findRecentByEquipment(equipmentId, RECENT_INSPECTIONS);
        List<InspectionSummary> recentBm = inspectionQueryService.findRecentBmByEquipment(equipmentId, RECENT_BM);
        Map<Long, String> roles = userQueryService.findRolesByIds(
                Stream.concat(recent.stream(), recentBm.stream())
                        .map(InspectionSummary::workerId).filter(Objects::nonNull).distinct().toList());

        return new ReportContext(
                report.getTitle(), code, name,
                alarm == null ? null : toAlarmInfo(alarm),
                from, to, sensors,
                recent.stream().map(i -> toInspectionInfo(i, roles)).toList(),
                recentBm.stream().map(i -> toInspectionInfo(i, roles)).toList());
    }

    private ReportContext.AlarmInfo toAlarmInfo(AlarmSnapshot alarm) {
        SensorSpec spec = alarm.sensorId() == null ? null : sensorQueryService.findSpec(alarm.sensorId()).orElse(null);
        return new ReportContext.AlarmInfo(
                alarm.alarmType(), alarm.severity(), alarm.status(),
                spec == null ? null : spec.type(), spec == null ? null : spec.unit(),
                alarm.triggerValue(), alarm.thresholdValue(), alarm.occurredAt(),
                alarm.message(), alarm.resolveNote());
    }

    private static ReportContext.SensorInfo toSensorInfo(SensorTrend trend) {
        return new ReportContext.SensorInfo(
                trend.type(), trend.unit(),
                trend.warnLow(), trend.warnHigh(), trend.critLow(), trend.critHigh(),
                trend.points().stream()
                        .map(p -> new ReportContext.Point(p.bucketAt(), p.min(), p.max(), p.avg()))
                        .toList());
    }

    private static ReportContext.InspectionInfo toInspectionInfo(InspectionSummary in, Map<Long, String> roles) {
        return new ReportContext.InspectionInfo(
                in.type(), in.shift(), Lookup.get(roles, in.workerId()),
                in.startedAt(), in.durationMin(), in.content(), in.actionTaken(),
                in.cause4m(), in.causeDetail(), in.hasNg(), in.ngItems());
    }
}
