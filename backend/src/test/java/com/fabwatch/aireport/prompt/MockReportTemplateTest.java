package com.fabwatch.aireport.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockReportTemplateTest {

    private static final Instant OCCURRED = Instant.parse("2026-10-02T14:03:00Z");

    private static ReportContext ctx(List<ReportContext.SensorInfo> sensors) {
        return new ReportContext("고장 리포트 — LAMI-01 2026-10-02 23:03", "LAMI-01", "합착기 1호",
                new ReportContext.AlarmInfo("SENSOR_THRESHOLD", "CRITICAL", "OPEN", "VIBRATION", "mm/s",
                        new BigDecimal("5.30"), new BigDecimal("5.00"), OCCURRED, "진동 임계 초과", null),
                OCCURRED.minusSeconds(1800), OCCURRED.plusSeconds(600), sensors, List.of(), List.of());
    }

    @Test
    @DisplayName("mock 리포트: 맨 위 [MOCK] 표기 + 5섹션 구조 + 제목")
    void render_hasMockNoticeAndFiveSections() {
        String md = MockReportTemplate.render(ctx(List.of(new ReportContext.SensorInfo("VIBRATION", "mm/s",
                null, null, null, null,
                List.of(new ReportContext.Point(OCCURRED, new BigDecimal("3.0"), new BigDecimal("3.5"),
                        new BigDecimal("3.2")))))));

        assertThat(md).startsWith("> [MOCK] 실제 AI가 생성한 리포트가 아닙니다");
        assertThat(md).contains("# 고장 리포트 — LAMI-01 2026-10-02 23:03");
        assertThat(md).contains("## 1. 현상 요약", "## 2. 센서 데이터 분석", "## 3. 추정 원인 (4M)",
                "## 4. 권장 조치", "## 5. 재발 방지 제안");
        assertThat(md).contains("CRITICAL").contains("5.3 mm/s");
    }

    @Test
    @DisplayName("mock 리포트: 센서 데이터가 없으면 '센서 데이터 없음' 명시")
    void render_marksNoSensorData() {
        assertThat(MockReportTemplate.render(ctx(List.of()))).contains("센서 데이터 없음");
    }
}
