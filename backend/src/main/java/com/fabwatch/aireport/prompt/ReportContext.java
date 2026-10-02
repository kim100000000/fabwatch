package com.fabwatch.aireport.prompt;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 리포트 생성에 쓰는 컨텍스트 (docs/03 F-6.1 ①~⑤). 프롬프트·mock 템플릿의 순수 입력값이다.
 *
 * <p>개인정보 원칙(docs/11 §7): 이메일·비밀번호는 물론 작업자 이름도 담지 않는다 — 역할(workerRole)과 교대(shift)만.
 * 사용자가 입력한 자유 텍스트(알람 메시지, 점검 내용 등)는 PromptBuilder가 구분자로 감싸 데이터로만 전달한다.
 */
public record ReportContext(
        /** 서버가 만든 리포트 제목 — 모델 출력의 H1에 그대로 쓰게 한다 */
        String title,
        String equipmentCode,
        String equipmentName,
        /** 알람이 없는 BM 기반 리포트면 null */
        AlarmInfo alarm,
        /** 센서 집계 구간 [from, to) — 기준 시각 -30분 ~ +10분 */
        Instant windowFrom,
        Instant windowTo,
        /** 집계 포인트가 1개 이상인 센서만. 비어 있으면 "센서 데이터 없음" */
        List<SensorInfo> sensors,
        List<InspectionInfo> recentInspections,
        List<InspectionInfo> recentBm) {

    public record AlarmInfo(
            String alarmType,
            String severity,
            String status,
            /** 센서 알람이 아니면 null */
            String sensorType,
            String unit,
            BigDecimal triggerValue,
            BigDecimal thresholdValue,
            Instant occurredAt,
            /** 자유 텍스트 — 수동 보고는 사용자 입력 */
            String message,
            String resolveNote) {
    }

    public record SensorInfo(
            String type,
            String unit,
            BigDecimal warnLow,
            BigDecimal warnHigh,
            BigDecimal critLow,
            BigDecimal critHigh,
            List<Point> points) {
    }

    public record Point(Instant at, BigDecimal min, BigDecimal max, BigDecimal avg) {
    }

    public record InspectionInfo(
            /** PM / BM */
            String type,
            /** D / N */
            String shift,
            /** 작업자 이름 대신 역할(ADMIN/ENGINEER/TECHNICIAN). 모르면 null */
            String workerRole,
            Instant startedAt,
            Integer durationMin,
            String content,
            String actionTaken,
            String cause4m,
            String causeDetail,
            boolean hasNg,
            List<String> ngItems) {
    }

    public boolean hasSensorData() {
        return sensors != null && !sensors.isEmpty();
    }
}
