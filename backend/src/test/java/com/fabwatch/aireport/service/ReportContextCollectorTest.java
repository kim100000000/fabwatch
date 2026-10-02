package com.fabwatch.aireport.service;

import com.fabwatch.aireport.entity.AiReport;
import com.fabwatch.aireport.prompt.ReportContext;
import com.fabwatch.aireport.prompt.PromptBuilder;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 컨텍스트 수집 단위 테스트 — 타 도메인은 전부 인터페이스 목으로 대체해 구간·건수·개인정보 규칙만 검증한다.
 */
class ReportContextCollectorTest {

    private static final Instant OCCURRED = Instant.parse("2026-10-02T14:03:00Z");

    private EquipmentQueryService equipment;
    private AlarmQueryService alarms;
    private SensorQueryService sensors;
    private SensorTrendQueryService trends;
    private InspectionQueryService inspections;
    private UserQueryService users;
    private ReportContextCollector collector;

    @BeforeEach
    void setUp() {
        equipment = mock(EquipmentQueryService.class);
        alarms = mock(AlarmQueryService.class);
        sensors = mock(SensorQueryService.class);
        trends = mock(SensorTrendQueryService.class);
        inspections = mock(InspectionQueryService.class);
        users = mock(UserQueryService.class);
        collector = new ReportContextCollector(equipment, alarms, sensors, trends, inspections, users);

        when(equipment.findCodeById(1L)).thenReturn(Optional.of("LAMI-01"));
        when(equipment.findNameById(1L)).thenReturn(Optional.of("합착기 1호"));
        when(alarms.findSnapshot(10L)).thenReturn(Optional.of(new AlarmSnapshot(10L, 1L, 100L, "SENSOR_THRESHOLD",
                "CRITICAL", "OPEN", new BigDecimal("5.3"), new BigDecimal("5.0"), "진동 초과", OCCURRED, null)));
        when(sensors.findSpec(100L)).thenReturn(Optional.of(new SensorSpec(100L, 1L, "VIBRATION", "mm/s",
                null, null, null, null, null, null)));
        when(trends.findTrends(anyLong(), any(), any())).thenReturn(List.of());
        when(inspections.findRecentByEquipment(anyLong(), anyInt())).thenReturn(List.of());
        when(inspections.findRecentBmByEquipment(anyLong(), anyInt())).thenReturn(List.of());
        when(users.findRolesByIds(anyCollection())).thenReturn(Map.of());
    }

    private static AiReport alarmReport() {
        return AiReport.generating(1L, 10L, null, "고장 리포트 — LAMI-01 2026-10-02 23:03", 5L);
    }

    @Test
    @DisplayName("센서 구간은 알람 발생 30분 전 ~ 10분 후, 점검 5건·BM 3건을 요청한다")
    void window_andCounts() {
        collector.collect(alarmReport());

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(trends).findTrends(eq(1L), from.capture(), to.capture());
        assertThat(from.getValue()).isEqualTo(OCCURRED.minusSeconds(30 * 60));
        assertThat(to.getValue()).isEqualTo(OCCURRED.plusSeconds(10 * 60));
        verify(inspections).findRecentByEquipment(1L, 5);
        verify(inspections).findRecentBmByEquipment(1L, 3);
    }

    @Test
    @DisplayName("알람 정보에 센서 종류·단위가 sensor 인터페이스로 채워진다")
    void alarmInfo_hasSensorType() {
        ReportContext ctx = collector.collect(alarmReport());

        assertThat(ctx.alarm().sensorType()).isEqualTo("VIBRATION");
        assertThat(ctx.alarm().unit()).isEqualTo("mm/s");
        assertThat(ctx.equipmentCode()).isEqualTo("LAMI-01");
        assertThat(ctx.title()).isEqualTo("고장 리포트 — LAMI-01 2026-10-02 23:03");
    }

    @Test
    @DisplayName("센서 미부착(빈 목록) 또는 집계 없는 센서만 있으면 sensors가 비어 '센서 데이터 없음'")
    void noSensorData() {
        when(trends.findTrends(anyLong(), any(), any())).thenReturn(List.of(
                new SensorTrend(100L, "VIBRATION", "mm/s", null, null, null, null, List.of())));

        ReportContext ctx = collector.collect(alarmReport());

        assertThat(ctx.hasSensorData()).isFalse();
        assertThat(PromptBuilder.build(ctx).user()).contains("센서 데이터 없음");
    }

    @Test
    @DisplayName("집계가 있는 센서는 min/max/avg 포인트로 담긴다")
    void sensorPoints() {
        when(trends.findTrends(anyLong(), any(), any())).thenReturn(List.of(
                new SensorTrend(100L, "VIBRATION", "mm/s", null, new BigDecimal("4"), null, new BigDecimal("5"),
                        List.of(new SensorTrend.Point(OCCURRED, new BigDecimal("3"), new BigDecimal("4"),
                                new BigDecimal("3.5"))))));

        ReportContext ctx = collector.collect(alarmReport());

        assertThat(ctx.hasSensorData()).isTrue();
        assertThat(ctx.sensors().get(0).points().get(0).avg()).isEqualByComparingTo("3.5");
    }

    @Test
    @DisplayName("개인정보: 작업자는 역할만 조회하고 이름 조회 API는 호출하지 않는다 — 프롬프트에 이름·이메일 없음")
    void noPersonalData() {
        InspectionSummary bm = new InspectionSummary(50L, 1L, "BM", "N", 77L, OCCURRED.minusSeconds(3600), 30,
                "베어링 소음", "교체", "MACHINE", "마모", true, 10L, List.of("진동 측정 — 초과"));
        when(inspections.findRecentByEquipment(1L, 5)).thenReturn(List.of(bm));
        when(inspections.findRecentBmByEquipment(1L, 3)).thenReturn(List.of(bm));
        when(users.findRolesByIds(anyCollection())).thenReturn(Map.of(77L, "TECHNICIAN"));
        when(users.findNameById(anyLong())).thenReturn(Optional.of("홍길동"));
        when(users.findNamesByIds(anyCollection())).thenReturn(Map.of(77L, "홍길동"));

        ReportContext ctx = collector.collect(alarmReport());
        String prompt = PromptBuilder.build(ctx).user();

        assertThat(ctx.recentInspections().get(0).workerRole()).isEqualTo("TECHNICIAN");
        assertThat(ctx.recentBm()).hasSize(1);
        assertThat(prompt).contains("작업자 역할: TECHNICIAN").contains("NG 항목");
        assertThat(prompt).doesNotContain("홍길동").doesNotContain("@");
        verify(users, never()).findNameById(anyLong());
        verify(users, never()).findNamesByIds(anyCollection());
    }

    @Test
    @DisplayName("알람 없이 점검만 지정: 점검 시작 시각이 기준, 점검에 연계된 알람이 있으면 포함")
    void inspectionOnly_usesLinkedAlarm() {
        Instant started = OCCURRED.minusSeconds(7200);
        when(inspections.findSummary(50L)).thenReturn(Optional.of(new InspectionSummary(50L, 1L, "BM", "N", 77L,
                started, 30, "BM 내용", "조치", "MACHINE", null, false, 10L, List.of())));
        AiReport report = AiReport.generating(1L, null, 50L, "제목", 5L);

        ReportContext ctx = collector.collect(report);

        // 연계 알람(10L)이 컨텍스트에 포함되고 센서 구간은 알람 시각 기준
        assertThat(ctx.alarm()).isNotNull();
        assertThat(ctx.windowFrom()).isEqualTo(OCCURRED.minusSeconds(1800));
    }

    @Test
    @DisplayName("알람도 연계 알람도 없는 BM: 알람 null, 점검 시작 시각이 센서 구간 기준")
    void inspectionOnly_noAlarm() {
        Instant started = OCCURRED.minusSeconds(7200);
        when(inspections.findSummary(51L)).thenReturn(Optional.of(new InspectionSummary(51L, 1L, "BM", "D", 77L,
                started, 30, "BM 내용", null, "METHOD", null, false, null, List.of())));
        AiReport report = AiReport.generating(1L, null, 51L, "제목", 5L);

        ReportContext ctx = collector.collect(report);

        assertThat(ctx.alarm()).isNull();
        assertThat(ctx.windowFrom()).isEqualTo(started.minusSeconds(1800));
        assertThat(ctx.windowTo()).isEqualTo(started.plusSeconds(600));
    }
}
