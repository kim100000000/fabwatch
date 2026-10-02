package com.fabwatch.aireport.prompt;

import java.math.BigDecimal;
import java.util.List;

/**
 * AI_PROVIDER=mock 일 때 외부 호출 없이 수집한 컨텍스트로 5섹션 마크다운을 조립한다 (데모·테스트·키 없는 환경용).
 * 실제 AI가 쓴 글이 아니므로 맨 위에 반드시 [MOCK] 표기를 붙인다 — 사람이 AI 결과로 오인하지 않도록.
 */
public final class MockReportTemplate {

    public static final String MOCK_NOTICE = "> [MOCK] 실제 AI가 생성한 리포트가 아닙니다";

    private MockReportTemplate() {
    }

    public static String render(ReportContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append(MOCK_NOTICE).append("\n\n");
        sb.append("# ").append(ctx.title()).append("\n\n");

        sb.append("## 1. 현상 요약\n");
        ReportContext.AlarmInfo alarm = ctx.alarm();
        if (alarm == null) {
            sb.append("- ").append(ctx.equipmentCode()).append(" 설비의 BM 점검 이력을 기준으로 작성했습니다(연계 알람 없음).\n");
        } else {
            sb.append("- ").append(ReportTimeFormat.kst(alarm.occurredAt())).append(" KST, ")
                    .append(ctx.equipmentCode()).append(" 설비에서 ").append(alarm.severity())
                    .append(" 알람(").append(alarm.alarmType()).append(")이 발생했습니다.\n");
            if (alarm.sensorType() != null && alarm.triggerValue() != null) {
                sb.append("- ").append(alarm.sensorType()).append(" 값 ").append(num(alarm.triggerValue()))
                        .append(alarm.unit() == null ? "" : " " + alarm.unit());
                if (alarm.thresholdValue() != null) {
                    sb.append(" (임계치 ").append(num(alarm.thresholdValue())).append(')');
                }
                sb.append('\n');
            }
            sb.append("- 알람 메시지: ").append(oneLine(alarm.message())).append('\n');
        }

        sb.append("\n## 2. 센서 데이터 분석\n");
        if (!ctx.hasSensorData()) {
            sb.append("- ").append(PromptBuilder.NO_SENSOR_DATA).append(" — 점검 이력만으로 작성했습니다.\n");
        } else {
            for (ReportContext.SensorInfo sensor : ctx.sensors()) {
                List<ReportContext.Point> pts = sensor.points();
                ReportContext.Point first = pts.get(0);
                ReportContext.Point last = pts.get(pts.size() - 1);
                BigDecimal peak = pts.stream().map(ReportContext.Point::max)
                        .filter(java.util.Objects::nonNull).max(BigDecimal::compareTo).orElse(null);
                sb.append("- ").append(sensor.type()).append(": 구간 평균 ")
                        .append(num(first.avg())).append(" → ").append(num(last.avg()))
                        .append(" (최대 ").append(num(peak)).append(sensor.unit() == null ? "" : " " + sensor.unit())
                        .append("). 추세 해석은 추정입니다.\n");
            }
        }

        sb.append("\n## 3. 추정 원인 (4M)\n");
        sb.append("- (MOCK) 실제 분석 없음. 최근 BM ").append(ctx.recentBm().size()).append("건과 최근 점검 ")
                .append(ctx.recentInspections().size()).append("건을 참고하세요.\n");

        sb.append("\n## 4. 권장 조치\n");
        sb.append("- 즉시 조치: (MOCK) 담당 엔지니어가 현장 확인 후 작성\n");
        sb.append("- 후속 조치: (MOCK) 담당 엔지니어가 작성\n");

        sb.append("\n## 5. 재발 방지 제안\n");
        sb.append("- (MOCK) PM 항목 추가, 임계치 조정, 점검 주기 단축 여부를 검토하세요.\n");
        return sb.toString();
    }

    private static String oneLine(String text) {
        return text == null ? "-" : text.replaceAll("\\s*[\\r\\n]+\\s*", " ").strip();
    }

    private static String num(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }
}
