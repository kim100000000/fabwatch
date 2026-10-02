package com.fabwatch.aireport;

import com.fabwatch.aireport.entity.AiCallLog;
import com.fabwatch.aireport.entity.AiReport;
import com.fabwatch.aireport.prompt.ReportTimeFormat;
import com.fabwatch.aireport.repository.AiCallLogRepository;
import com.fabwatch.aireport.repository.AiReportRepository;
import com.fabwatch.aireport.dto.AiReportUpdateRequest;
import com.fabwatch.aireport.service.AiReportRecoveryService;
import com.fabwatch.aireport.service.AiReportResultWriter;
import com.fabwatch.aireport.service.AiReportService;
import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F-6 AI 리포트 API 통합 테스트 (docs/12 §6, 시드 데이터 기준). provider=mock(application-test.yml) —
 * 외부 네트워크 호출 없음. 비동기 생성은 별도 스레드에서 커밋되므로 클래스 단위 @Transactional을 쓰지 않고,
 * 각 테스트가 만든 ai_reports / ai_call_logs 행은 @AfterEach에서 정리한다(시드 리포트는 보존).
 */
@SpringBootTest(properties = "fabwatch.seed.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
class AiReportApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AiReportRepository reportRepository;
    @Autowired
    private AiCallLogRepository callLogRepository;
    @Autowired
    private AiReportRecoveryService recoveryService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AiReportService reportService;
    @Autowired
    private AiReportResultWriter resultWriter;
    @Autowired
    private PlatformTransactionManager txManager;

    private long baseReportId;

    @BeforeEach
    void rememberBaseline() {
        baseReportId = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM ai_reports", Long.class);
        jdbc.update("DELETE FROM ai_call_logs");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM ai_call_logs");
        jdbc.update("DELETE FROM ai_reports WHERE id > ?", baseReportId);
    }

    // ------------------------------------------------------------ 전체 플로우 (202 → 폴링 → DRAFT → PUT → confirm)

    @Test
    @DisplayName("mock 전체 플로우: POST 202 → 폴링 GET → DRAFT → PUT → confirm → 확정 후 PUT 409")
    void fullFlow() throws Exception {
        String engineer = login("engineer@fabwatch.dev");
        JsonNode alarm = firstAlarm(engineer);
        long alarmId = alarm.get("id").asLong();

        JsonNode accepted = json(mockMvc.perform(post("/api/v1/ai-reports").header("Authorization", bearer(engineer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"alarmId\":" + alarmId + "}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("GENERATING"))
                .andExpect(jsonPath("$.reportId").isNumber()));
        long reportId = accepted.get("reportId").asLong();

        JsonNode draft = awaitNotGenerating(engineer, reportId);

        // 상세 shape (계약 2)
        assertThat(draft.get("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.get("id").asLong()).isEqualTo(reportId);
        assertThat(draft.get("equipmentId").asLong()).isEqualTo(alarm.get("equipmentId").asLong());
        assertThat(draft.get("equipmentCode").asText()).isEqualTo(alarm.get("equipmentCode").asText());
        assertThat(draft.get("equipmentName").asText()).isNotBlank();
        assertThat(draft.get("alarmId").asLong()).isEqualTo(alarmId);
        assertThat(draft.get("inspectionId").isNull()).isTrue();
        assertThat(draft.get("title").asText()).isEqualTo(ReportTimeFormat.title(
                alarm.get("equipmentCode").asText(), Instant.parse(alarm.get("occurredAt").asText())));
        assertThat(draft.get("draftContent").asText())
                .startsWith("> [MOCK] 실제 AI가 생성한 리포트가 아닙니다")
                .contains("## 1. 현상 요약", "## 5. 재발 방지 제안");
        assertThat(draft.get("finalContent").isNull()).isTrue();
        assertThat(draft.get("failReason").isNull()).isTrue();
        assertThat(draft.get("model").asText()).isEqualTo("mock");
        assertThat(draft.get("promptTokens").asInt()).isZero();
        assertThat(draft.get("completionTokens").asInt()).isZero();
        assertThat(draft.get("createdByName").asText()).isNotBlank();
        assertThat(draft.get("confirmedBy").isNull()).isTrue();
        assertThat(draft.get("confirmedAt").isNull()).isTrue();
        assertThat(draft.get("createdAt").asText()).isNotBlank();

        // 호출 로그: mock 1건, 성공
        AiCallLog callLog = callLogRepository.findFirstByReportIdOrderByIdDesc(reportId).orElseThrow();
        assertThat(callLog.getSuccess()).isTrue();
        assertThat(callLog.getProvider()).isEqualTo("mock");
        assertThat(callLog.getModel()).isEqualTo("mock");

        // PUT — final만 바뀌고 draft(AI 원본)는 불변
        String originalDraft = draft.get("draftContent").asText();
        JsonNode edited = json(mockMvc.perform(put("/api/v1/ai-reports/{id}", reportId)
                        .header("Authorization", bearer(engineer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"finalContent\":\"# 편집본\\n정비 결과 반영\"}"))
                .andExpect(status().isOk()));
        assertThat(edited.get("status").asText()).isEqualTo("DRAFT");
        assertThat(edited.get("finalContent").asText()).isEqualTo("# 편집본\n정비 결과 반영");
        assertThat(edited.get("draftContent").asText()).isEqualTo(originalDraft);

        // 확정
        JsonNode confirmed = json(mockMvc.perform(patch("/api/v1/ai-reports/{id}/confirm", reportId)
                        .header("Authorization", bearer(engineer)))
                .andExpect(status().isOk()));
        assertThat(confirmed.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.get("finalContent").asText()).isEqualTo("# 편집본\n정비 결과 반영");
        assertThat(confirmed.get("draftContent").asText()).isEqualTo(originalDraft);
        assertThat(confirmed.get("confirmedBy").asLong()).isEqualTo(confirmed.get("createdBy").asLong());
        assertThat(confirmed.get("confirmedByName").asText()).isNotBlank();
        assertThat(confirmed.get("confirmedAt").asText()).isNotBlank();

        // 확정 이후 PUT / confirm / retry → 409 INVALID_REPORT_STATE
        mockMvc.perform(put("/api/v1/ai-reports/{id}", reportId).header("Authorization", bearer(engineer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"finalContent\":\"더 수정\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_REPORT_STATE"));
        mockMvc.perform(patch("/api/v1/ai-reports/{id}/confirm", reportId).header("Authorization", bearer(engineer)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_REPORT_STATE"));
        mockMvc.perform(post("/api/v1/ai-reports/{id}/retry", reportId).header("Authorization", bearer(engineer)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_REPORT_STATE"));
    }

    @Test
    @DisplayName("DRAFT를 편집 없이 확정하면 draft가 final로 복사된다 (원본 유지)")
    void confirmWithoutEdit_copiesDraft() throws Exception {
        String engineer = login("engineer@fabwatch.dev");
        long alarmId = firstAlarm(engineer).get("id").asLong();
        long reportId = requestReport(engineer, "{\"alarmId\":" + alarmId + "}");
        JsonNode draft = awaitNotGenerating(engineer, reportId);

        JsonNode confirmed = json(mockMvc.perform(patch("/api/v1/ai-reports/{id}/confirm", reportId)
                .header("Authorization", bearer(engineer))).andExpect(status().isOk()));

        assertThat(confirmed.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.get("finalContent").asText()).isEqualTo(draft.get("draftContent").asText());
        assertThat(confirmed.get("draftContent").asText()).isEqualTo(draft.get("draftContent").asText());
    }

    @Test
    @DisplayName("점검 이력만 지정해도 생성된다 — 설비는 점검 기준, inspectionId 저장, alarmId는 점검의 연계 알람(없으면 null)")
    void inspectionOnly() throws Exception {
        String engineer = login("engineer@fabwatch.dev");
        JsonNode inspection = json(mockMvc.perform(get("/api/v1/inspections").param("type", "BM")
                        .header("Authorization", bearer(engineer))).andExpect(status().isOk())).get("content").get(0);
        long inspectionId = inspection.get("id").asLong();

        long reportId = requestReport(engineer, "{\"inspectionId\":" + inspectionId + "}");
        JsonNode draft = awaitNotGenerating(engineer, reportId);

        assertThat(draft.get("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.get("equipmentId").asLong()).isEqualTo(inspection.get("equipmentId").asLong());
        // M-4: 점검에 연계 알람이 있으면 그 알람 id도 저장된다 (없으면 null)
        if (inspection.get("alarmId") == null || inspection.get("alarmId").isNull()) {
            assertThat(draft.get("alarmId").isNull()).isTrue();
        } else {
            assertThat(draft.get("alarmId").asLong()).isEqualTo(inspection.get("alarmId").asLong());
        }
        assertThat(draft.get("inspectionId").asLong()).isEqualTo(inspectionId);
        assertThat(draft.get("title").asText()).startsWith("고장 리포트 — " + inspection.get("equipmentCode").asText());
    }

    @Test
    @DisplayName("알람+점검 둘 다 지정(같은 설비)하면 둘 다 저장, 서로 다른 설비면 400")
    void bothIds() throws Exception {
        String engineer = login("engineer@fabwatch.dev");
        JsonNode alarm = firstAlarm(engineer);
        long equipmentId = alarm.get("equipmentId").asLong();
        JsonNode sameEquipmentInspection = findInspection(engineer, true, equipmentId);
        JsonNode otherEquipmentInspection = findInspection(engineer, false, equipmentId);

        if (sameEquipmentInspection != null) {
            long reportId = requestReport(engineer, "{\"alarmId\":" + alarm.get("id").asLong()
                    + ",\"inspectionId\":" + sameEquipmentInspection.get("id").asLong() + "}");
            JsonNode draft = awaitNotGenerating(engineer, reportId);
            assertThat(draft.get("alarmId").asLong()).isEqualTo(alarm.get("id").asLong());
            assertThat(draft.get("inspectionId").asLong()).isEqualTo(sameEquipmentInspection.get("id").asLong());
        }
        assertThat(otherEquipmentInspection).as("시드에 다른 설비 점검이 있어야 한다").isNotNull();
        mockMvc.perform(post("/api/v1/ai-reports").header("Authorization", bearer(engineer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alarmId\":" + alarm.get("id").asLong()
                                + ",\"inspectionId\":" + otherEquipmentInspection.get("id").asLong() + "}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ------------------------------------------------------------ 입력 검증 / 404 / 권한 / 에러 포맷

    @Test
    @DisplayName("POST 검증: 둘 다 없으면 400 VALIDATION_ERROR, 없는 알람/점검은 404 NOT_FOUND — 공통 에러 포맷")
    void create_validationAndNotFound() throws Exception {
        String engineer = login("engineer@fabwatch.dev");

        mockMvc.perform(post("/api/v1/ai-reports").header("Authorization", bearer(engineer))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());
        mockMvc.perform(post("/api/v1/ai-reports").header("Authorization", bearer(engineer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"alarmId\":999999}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(post("/api/v1/ai-reports").header("Authorization", bearer(engineer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"inspectionId\":999999}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/v1/ai-reports/{id}", 999999).header("Authorization", bearer(engineer)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        // 검증 실패로는 리포트·호출 로그가 생기지 않는다
        assertThat(reportRepository.count()).isEqualTo(baseCount());
        assertThat(callLogRepository.count()).isZero();
    }

    @Test
    @DisplayName("권한: TECHNICIAN은 POST/PUT/confirm/retry 403 FORBIDDEN, GET은 허용 / 인증 없으면 401")
    void permissions() throws Exception {
        String tech = login("tech@fabwatch.dev");
        String admin = login("admin@fabwatch.dev");
        long alarmId = firstAlarm(tech).get("id").asLong();
        AiReport failed = persist(failedReport(alarmId));

        mockMvc.perform(post("/api/v1/ai-reports").header("Authorization", bearer(tech))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"alarmId\":" + alarmId + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());
        mockMvc.perform(put("/api/v1/ai-reports/{id}", failed.getId()).header("Authorization", bearer(tech))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"finalContent\":\"x\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(patch("/api/v1/ai-reports/{id}/confirm", failed.getId()).header("Authorization", bearer(tech)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/ai-reports/{id}/retry", failed.getId()).header("Authorization", bearer(tech)))
                .andExpect(status().isForbidden());
        // 거부된 요청은 쿼터·상태에 영향이 없다
        assertThat(callLogRepository.count()).isZero();
        assertThat(reportRepository.findById(failed.getId()).orElseThrow().getStatus())
                .isEqualTo(AiReport.Status.FAILED);

        // GET은 전체 로그인
        mockMvc.perform(get("/api/v1/ai-reports").header("Authorization", bearer(tech))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/ai-reports/{id}", failed.getId()).header("Authorization", bearer(tech)))
                .andExpect(status().isOk());
        // ADMIN은 ENGINEER+ 권한
        mockMvc.perform(put("/api/v1/ai-reports/{id}", failed.getId()).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"finalContent\":\"관리자 수동 작성\"}"))
                .andExpect(status().isOk());
        // 인증 없음
        mockMvc.perform(get("/api/v1/ai-reports")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/ai-reports").contentType(MediaType.APPLICATION_JSON)
                .content("{\"alarmId\":1}")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------ 409 ALREADY_GENERATING

    @Test
    @DisplayName("같은 알람에 GENERATING 리포트가 있으면 409 ALREADY_GENERATING — 쿼터·리포트 증가 없음")
    void alreadyGenerating_alarm() throws Exception {
        String engineer = login("engineer@fabwatch.dev");
        long alarmId = firstAlarm(engineer).get("id").asLong();
        persist(generatingReport(alarmId, null));
        long before = reportRepository.count();

        mockMvc.perform(post("/api/v1/ai-reports").header("Authorization", bearer(engineer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"alarmId\":" + alarmId + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_GENERATING"))
                .andExpect(jsonPath("$.timestamp").exists());

        assertThat(reportRepository.count()).isEqualTo(before);
        assertThat(callLogRepository.count()).isZero();
    }

    @Test
    @DisplayName("같은 점검에 GENERATING 리포트가 있으면 409 ALREADY_GENERATING")
    void alreadyGenerating_inspection() throws Exception {
        String engineer = login("engineer@fabwatch.dev");
        JsonNode inspection = json(mockMvc.perform(get("/api/v1/inspections")
                .header("Authorization", bearer(engineer))).andExpect(status().isOk())).get("content").get(0);
        long inspectionId = inspection.get("id").asLong();
        persist(AiReport.generating(inspection.get("equipmentId").asLong(), null, inspectionId, "t", 1L));

        mockMvc.perform(post("/api/v1/ai-reports").header("Authorization", bearer(engineer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"inspectionId\":" + inspectionId + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_GENERATING"));
    }

    @Test
    @DisplayName("완료(DRAFT/FAILED)된 리포트가 있어도 같은 알람에 새로 생성할 수 있다")
    void notGenerating_allowsNewReport() throws Exception {
        String engineer = login("engineer@fabwatch.dev");
        long alarmId = firstAlarm(engineer).get("id").asLong();
        persist(failedReport(alarmId));

        long reportId = requestReport(engineer, "{\"alarmId\":" + alarmId + "}");

        assertThat(awaitNotGenerating(engineer, reportId).get("status").asText()).isEqualTo("DRAFT");
    }

    // ------------------------------------------------------------ 쿼터

    @Test
    @DisplayName("쿼터: 오늘 19건이면 허용(20번째), 20건이면 403 AI_QUOTA_EXCEEDED — 리포트·로그 미생성, mock도 카운트")
    void quota_boundary() throws Exception {
        String engineer = login("engineer@fabwatch.dev");
        long alarmId = firstAlarm(engineer).get("id").asLong();
        fillCallLogs(19);

        long reportId = requestReport(engineer, "{\"alarmId\":" + alarmId + "}"); // 20번째 — 허용
        awaitNotGenerating(engineer, reportId);
        assertThat(callLogRepository.count()).isEqualTo(20);

        long reportsBefore = reportRepository.count();
        mockMvc.perform(post("/api/v1/ai-reports").header("Authorization", bearer(engineer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"alarmId\":" + alarmId + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AI_QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());
        assertThat(reportRepository.count()).isEqualTo(reportsBefore);
        assertThat(callLogRepository.count()).isEqualTo(20);
    }

    @Test
    @DisplayName("쿼터 집계는 어제(KST) 호출을 세지 않는다")
    void quota_ignoresYesterday() throws Exception {
        String engineer = login("engineer@fabwatch.dev");
        long alarmId = firstAlarm(engineer).get("id").asLong();
        for (int i = 0; i < 25; i++) {
            callLogRepository.save(new AiCallLog(null, 1L, Instant.now().minus(2, ChronoUnit.DAYS), "mock", "mock"));
        }

        long reportId = requestReport(engineer, "{\"alarmId\":" + alarmId + "}");

        assertThat(awaitNotGenerating(engineer, reportId).get("status").asText()).isEqualTo("DRAFT");
    }

    // ------------------------------------------------------------ retry

    @Test
    @DisplayName("retry: FAILED만 가능, 같은 reportId로 202 → DRAFT. 수동 작성해 둔 final은 유지")
    void retry_failedReusesReportId() throws Exception {
        String engineer = login("engineer@fabwatch.dev");
        long alarmId = firstAlarm(engineer).get("id").asLong();
        AiReport failed = persist(failedReport(alarmId));

        JsonNode accepted = json(mockMvc.perform(post("/api/v1/ai-reports/{id}/retry", failed.getId())
                        .header("Authorization", bearer(engineer)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("GENERATING")));

        assertThat(accepted.get("reportId").asLong()).isEqualTo(failed.getId());
        JsonNode draft = awaitNotGenerating(engineer, failed.getId());
        assertThat(draft.get("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.get("failReason").isNull()).isTrue();
        assertThat(draft.get("draftContent").asText()).startsWith("> [MOCK]");
        // 재시도도 호출 로그 1건
        assertThat(callLogRepository.findFirstByReportIdOrderByIdDesc(failed.getId()).orElseThrow().getSuccess())
                .isTrue();
    }

    @Test
    @DisplayName("retry 거부: DRAFT/GENERATING/CONFIRMED는 409 INVALID_REPORT_STATE, 없는 리포트 404")
    void retry_rejectedStates() throws Exception {
        String engineer = login("engineer@fabwatch.dev");
        long alarmId = firstAlarm(engineer).get("id").asLong();
        AiReport draft = persist(draftReport(alarmId));
        AiReport generating = persist(generatingReport(alarmId, null));
        AiReport confirmed = draftReport(alarmId);
        confirmed.confirm(1L, Instant.now());
        persist(confirmed);

        for (AiReport report : new AiReport[]{draft, generating, confirmed}) {
            mockMvc.perform(post("/api/v1/ai-reports/{id}/retry", report.getId())
                            .header("Authorization", bearer(engineer)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_REPORT_STATE"));
        }
        mockMvc.perform(post("/api/v1/ai-reports/{id}/retry", 999999).header("Authorization", bearer(engineer)))
                .andExpect(status().isNotFound());
        assertThat(callLogRepository.count()).isZero();
    }

    @Test
    @DisplayName("retry도 쿼터를 확인한다 — 한도 초과면 403, 리포트는 FAILED 그대로")
    void retry_quotaExceeded() throws Exception {
        String engineer = login("engineer@fabwatch.dev");
        AiReport failed = persist(failedReport(firstAlarm(engineer).get("id").asLong()));
        fillCallLogs(20);

        mockMvc.perform(post("/api/v1/ai-reports/{id}/retry", failed.getId())
                        .header("Authorization", bearer(engineer)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AI_QUOTA_EXCEEDED"));

        assertThat(reportRepository.findById(failed.getId()).orElseThrow().getStatus())
                .isEqualTo(AiReport.Status.FAILED);
    }

    @Test
    @DisplayName("retry: 같은 알람에 다른 GENERATING 리포트가 있으면 409 ALREADY_GENERATING")
    void retry_alreadyGeneratingOnSameAlarm() throws Exception {
        String engineer = login("engineer@fabwatch.dev");
        long alarmId = firstAlarm(engineer).get("id").asLong();
        AiReport failed = persist(failedReport(alarmId));
        persist(generatingReport(alarmId, null));

        mockMvc.perform(post("/api/v1/ai-reports/{id}/retry", failed.getId())
                        .header("Authorization", bearer(engineer)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_GENERATING"));
        assertThat(reportRepository.findById(failed.getId()).orElseThrow().getStatus())
                .isEqualTo(AiReport.Status.FAILED);
    }

    // ------------------------------------------------------------ PUT / confirm 허용·거부 (API 레벨)

    @Test
    @DisplayName("PUT: DRAFT·FAILED 허용(200), GENERATING 409, 공백 400 — FAILED 수동 작성 후 확정 가능")
    void put_matrix_andManualWriteForFailed() throws Exception {
        String engineer = login("engineer@fabwatch.dev");
        long alarmId = firstAlarm(engineer).get("id").asLong();
        AiReport generating = persist(generatingReport(alarmId, null));
        AiReport failed = persist(failedReport(alarmId));
        AiReport draft = persist(draftReport(alarmId));

        mockMvc.perform(put("/api/v1/ai-reports/{id}", generating.getId()).header("Authorization", bearer(engineer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"finalContent\":\"x\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_REPORT_STATE"));
        for (String blank : new String[]{"{\"finalContent\":\"   \"}", "{\"finalContent\":\"\"}", "{}"}) {
            mockMvc.perform(put("/api/v1/ai-reports/{id}", draft.getId()).header("Authorization", bearer(engineer))
                            .contentType(MediaType.APPLICATION_JSON).content(blank))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        mockMvc.perform(put("/api/v1/ai-reports/{id}", draft.getId()).header("Authorization", bearer(engineer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"finalContent\":\"편집\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));

        // FAILED: 확정본 없이 확정 → 400, 수동 작성 후 확정 → 200
        mockMvc.perform(patch("/api/v1/ai-reports/{id}/confirm", failed.getId())
                        .header("Authorization", bearer(engineer)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(put("/api/v1/ai-reports/{id}", failed.getId()).header("Authorization", bearer(engineer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"finalContent\":\"수동 작성 내용\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.draftContent").value(org.hamcrest.Matchers.nullValue()));
        mockMvc.perform(patch("/api/v1/ai-reports/{id}/confirm", failed.getId())
                        .header("Authorization", bearer(engineer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.finalContent").value("수동 작성 내용"))
                .andExpect(jsonPath("$.draftContent").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("confirm 거부: GENERATING 409 INVALID_REPORT_STATE")
    void confirm_rejectedWhenGenerating() throws Exception {
        String engineer = login("engineer@fabwatch.dev");
        AiReport generating = persist(generatingReport(firstAlarm(engineer).get("id").asLong(), null));

        mockMvc.perform(patch("/api/v1/ai-reports/{id}/confirm", generating.getId())
                        .header("Authorization", bearer(engineer)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_REPORT_STATE"));
    }

    // ------------------------------------------------------------ 조회

    @Test
    @DisplayName("GENERATING 중 상세는 draft/final이 null이다")
    void get_generatingHidesContent() throws Exception {
        String engineer = login("engineer@fabwatch.dev");
        AiReport generating = persist(generatingReport(firstAlarm(engineer).get("id").asLong(), null));

        mockMvc.perform(get("/api/v1/ai-reports/{id}", generating.getId()).header("Authorization", bearer(engineer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("GENERATING"))
                .andExpect(jsonPath("$.draftContent").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.finalContent").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("목록: PageResponse 래핑 + 최신순 + 본문 제외 + 필터(status/alarmId/equipmentId) + 페이징")
    void list_shapeFiltersPaging() throws Exception {
        String tech = login("tech@fabwatch.dev");
        JsonNode alarm = firstAlarm(tech);
        long alarmId = alarm.get("id").asLong();
        AiReport first = persist(failedReport(alarmId));
        AiReport second = persist(draftReport(alarmId));
        AiReport third = persist(generatingReport(alarmId, null));

        JsonNode all = json(mockMvc.perform(get("/api/v1/ai-reports").param("alarmId", String.valueOf(alarmId))
                        .header("Authorization", bearer(tech)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.content.length()").value(3)));
        // 최신순
        assertThat(all.get("content").get(0).get("id").asLong()).isEqualTo(third.getId());
        assertThat(all.get("content").get(2).get("id").asLong()).isEqualTo(first.getId());
        JsonNode row = all.get("content").get(1);
        assertThat(row.get("id").asLong()).isEqualTo(second.getId());
        assertThat(row.get("equipmentCode").asText()).isEqualTo(alarm.get("equipmentCode").asText());
        assertThat(row.get("equipmentName").asText()).isNotBlank();
        assertThat(row.get("title").asText()).isNotBlank();
        assertThat(row.get("status").asText()).isEqualTo("DRAFT");
        assertThat(row.has("createdByName")).isTrue();
        assertThat(row.has("createdAt")).isTrue();
        assertThat(row.has("confirmedAt")).isTrue();
        // 본문 제외
        assertThat(row.has("draftContent")).isFalse();
        assertThat(row.has("finalContent")).isFalse();

        mockMvc.perform(get("/api/v1/ai-reports").param("alarmId", String.valueOf(alarmId))
                        .param("status", "FAILED").header("Authorization", bearer(tech)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(first.getId()));
        mockMvc.perform(get("/api/v1/ai-reports").param("alarmId", String.valueOf(alarmId))
                        .param("equipmentId", alarm.get("equipmentId").asText())
                        .param("size", "2").header("Authorization", bearer(tech)))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
        mockMvc.perform(get("/api/v1/ai-reports").param("status", "XX").header("Authorization", bearer(tech)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ------------------------------------------------------------ 복구

    @Test
    @DisplayName("재시작 복구: 5분 넘게 GENERATING인 리포트는 FAILED(서버 재시작/시간 초과), 최근 것은 유지")
    void recovery_stuckGenerating() throws Exception {
        String engineer = login("engineer@fabwatch.dev");
        long alarmId = firstAlarm(engineer).get("id").asLong();
        AiReport stuck = persist(generatingReport(alarmId, null));
        // 최근 건은 다른 대상(알람 없음)에 걸어 복구 후 retry가 ALREADY_GENERATING에 막히지 않게 한다
        AiReport fresh = persist(generatingReport(null, null));
        AiCallLog pending = callLogRepository.save(new AiCallLog(stuck.getId(), 1L, Instant.now(), "claude", "m"));
        jdbc.update("UPDATE ai_reports SET updated_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(10, ChronoUnit.MINUTES)), stuck.getId());

        int recovered = recoveryService.recoverStuck();

        assertThat(recovered).isEqualTo(1);
        AiReport after = reportRepository.findById(stuck.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(AiReport.Status.FAILED);
        assertThat(after.getFailReason()).contains("서버 재시작").contains("시간 초과");
        assertThat(reportRepository.findById(fresh.getId()).orElseThrow().getStatus())
                .isEqualTo(AiReport.Status.GENERATING);
        assertThat(callLogRepository.findById(pending.getId()).orElseThrow().getSuccess()).isFalse();
        // 복구된 건은 retry 가능
        mockMvc.perform(post("/api/v1/ai-reports/{id}/retry", stuck.getId()).header("Authorization", bearer(engineer)))
                .andExpect(status().isAccepted());
    }

    // ------------------------------------------------------------ M-4 알람 경로/점검 경로 중복

    /** 연계 알람이 실존하는 점검 1건 (시드). {id, equipmentId, alarmId} */
    private Map<String, Object> linkedInspection() {
        return jdbc.queryForMap("SELECT i.id AS id, i.equipment_id AS equipment_id, i.alarm_id AS alarm_id "
                + "FROM inspections i JOIN alarms a ON a.id = i.alarm_id "
                + "WHERE i.deleted_at IS NULL AND a.deleted_at IS NULL ORDER BY i.id LIMIT 1");
    }

    @Test
    @DisplayName("M-4: 점검만 지정해 생성하면 연계 알람이 report.alarmId로 저장되고 ?alarmId= 필터로 조회된다")
    void inspectionOnly_storesLinkedAlarm_andListFilterFindsIt() throws Exception {
        String engineer = login("engineer@fabwatch.dev");
        Map<String, Object> linked = linkedInspection();
        long inspectionId = ((Number) linked.get("id")).longValue();
        long alarmId = ((Number) linked.get("alarm_id")).longValue();

        long reportId = requestReport(engineer, "{\"inspectionId\":" + inspectionId + "}");
        JsonNode draft = awaitNotGenerating(engineer, reportId);

        assertThat(draft.get("alarmId").asLong()).isEqualTo(alarmId);
        assertThat(draft.get("inspectionId").asLong()).isEqualTo(inspectionId);
        JsonNode byAlarm = json(mockMvc.perform(get("/api/v1/ai-reports").param("alarmId", String.valueOf(alarmId))
                .header("Authorization", bearer(engineer))).andExpect(status().isOk())).get("content");
        assertThat(byAlarm).anySatisfy(row -> assertThat(row.get("id").asLong()).isEqualTo(reportId));
    }

    @Test
    @DisplayName("M-4: 알람으로 먼저 생성 중이면, 그 알람에 연계된 점검으로 생성 시 같은 알람 기준 409 ALREADY_GENERATING")
    void alarmFirst_thenLinkedInspection_conflicts() throws Exception {
        String engineer = login("engineer@fabwatch.dev");
        Map<String, Object> linked = linkedInspection();
        seedEquipmentId = ((Number) linked.get("equipment_id")).longValue();
        persist(generatingReport(((Number) linked.get("alarm_id")).longValue(), null)); // 알람 경로 리포트
        long before = reportRepository.count();

        mockMvc.perform(post("/api/v1/ai-reports").header("Authorization", bearer(engineer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inspectionId\":" + ((Number) linked.get("id")).longValue() + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_GENERATING"));

        assertThat(reportRepository.count()).isEqualTo(before);
        assertThat(callLogRepository.count()).isZero();
    }

    @Test
    @DisplayName("M-4: 점검으로 먼저 생성 중(alarm_id 저장됨)이면, 그 알람으로 생성 시 409 ALREADY_GENERATING")
    void inspectionFirst_thenAlarm_conflicts() throws Exception {
        String engineer = login("engineer@fabwatch.dev");
        Map<String, Object> linked = linkedInspection();
        seedEquipmentId = ((Number) linked.get("equipment_id")).longValue();
        long alarmId = ((Number) linked.get("alarm_id")).longValue();
        // 서비스 create가 저장하는 형태 그대로: 점검 경로 + 연계 알람 id
        persist(generatingReport(alarmId, ((Number) linked.get("id")).longValue()));

        mockMvc.perform(post("/api/v1/ai-reports").header("Authorization", bearer(engineer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"alarmId\":" + alarmId + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_GENERATING"));
    }

    // ------------------------------------------------------------ M-3 확정본 불변 (행 잠금)

    @Test
    @DisplayName("M-3: 다른 트랜잭션이 행 잠금을 쥔 채 확정하는 동안 PUT은 대기했다가 409 — 확정본(final_content)이 바뀌지 않는다")
    void put_blockedByConfirmLock_thenRejected_finalUnchanged() throws Exception {
        String engineer = login("engineer@fabwatch.dev");
        AiReport draft = persist(draftReport(firstAlarm(engineer).get("id").asLong()));
        long id = draft.getId();
        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> confirmer = pool.submit(() -> new TransactionTemplate(txManager).executeWithoutResult(st -> {
                AiReport r = reportRepository.findByIdForUpdate(id).orElseThrow();
                locked.countDown();
                sleepQuietly(600); // PUT이 이 사이에 들어와도 잠금 때문에 기다려야 한다
                r.confirm(1L, Instant.now());
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

            long start = System.nanoTime();
            Future<?> editor = pool.submit(() -> reportService.updateFinal(id, new AiReportUpdateRequest("덮어쓰기 시도")));
            confirmer.get(10, TimeUnit.SECONDS);
            ExecutionException e = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                    () -> editor.get(10, TimeUnit.SECONDS));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(e.getCause()).isInstanceOfSatisfying(BusinessException.class,
                    be -> assertThat(be.getErrorCode()).isEqualTo(ErrorCode.INVALID_REPORT_STATE));
            assertThat(elapsedMs).as("PUT은 확정 트랜잭션이 끝날 때까지 대기해야 한다").isGreaterThanOrEqualTo(300);
            AiReport after = reportRepository.findById(id).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(AiReport.Status.CONFIRMED);
            assertThat(after.getFinalContent()).isEqualTo("# AI 원본");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("M-3: PUT과 confirm을 동시에 반복 실행해도 '확정 응답의 finalContent == DB final_content' (확정 후 덮어쓰기 없음)")
    void put_and_confirm_race_neverOverwritesAfterConfirm() throws Exception {
        String engineer = login("engineer@fabwatch.dev");
        long alarmId = firstAlarm(engineer).get("id").asLong();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 25; i++) {
                long id = persist(draftReport(alarmId)).getId();
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<String> confirm = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return reportService.confirm(id, 1L).finalContent();
                });
                Future<Boolean> put = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        reportService.updateFinal(id, new AiReportUpdateRequest("동시 편집"));
                        return true;
                    } catch (BusinessException ex) {
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REPORT_STATE);
                        return false;
                    }
                });

                String confirmedContent = confirm.get(10, TimeUnit.SECONDS);
                boolean putSucceeded = put.get(10, TimeUnit.SECONDS);

                AiReport after = reportRepository.findById(id).orElseThrow();
                assertThat(after.getStatus()).isEqualTo(AiReport.Status.CONFIRMED);
                // 확정이 응답한 본문이 곧 DB 확정본 — 확정 이후 PUT이 덮어썼다면 여기서 어긋난다
                assertThat(after.getFinalContent()).as("iteration %d", i).isEqualTo(confirmedContent);
                assertThat(after.getFinalContent()).isEqualTo(putSucceeded ? "동시 편집" : "# AI 원본");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------ H-1 호출 로그에서 잘림/빈 본문 구분

    @Test
    @DisplayName("H-1: 잘린 본문 DRAFT 저장은 호출 로그에 [MAX_TOKENS_TRUNCATED] 기록(성공 true), 빈 본문 실패는 fail_reason·error_summary가 같은 코드")
    void callLog_distinguishesTruncatedAndEmpty() throws Exception {
        String engineer = login("engineer@fabwatch.dev");
        long alarmId = firstAlarm(engineer).get("id").asLong();

        AiReport truncated = persist(generatingReport(alarmId, null));
        AiCallLog truncatedLog = callLogRepository.save(new AiCallLog(truncated.getId(), 1L, Instant.now(), "claude", "m"));
        assertThat(resultWriter.saveTruncatedDraft(truncated.getId(), truncatedLog.getId(), "# 본문\n\n> 잘림",
                "claude-sonnet-5-5", 1000, 4096)).isTrue();

        AiReport empty = persist(generatingReport(null, null));
        AiCallLog emptyLog = callLogRepository.save(new AiCallLog(empty.getId(), 1L, Instant.now(), "claude", "m"));
        String reason = "[MAX_TOKENS_TRUNCATED] 출력 토큰 한도에 도달해 본문이 생성되지 않았습니다. 다시 시도하거나 수동으로 작성하세요";
        assertThat(resultWriter.saveFailure(empty.getId(), emptyLog.getId(), reason, "claude-sonnet-5-5")).isTrue();

        AiReport t = reportRepository.findById(truncated.getId()).orElseThrow();
        AiCallLog tl = callLogRepository.findById(truncatedLog.getId()).orElseThrow();
        assertThat(t.getStatus()).isEqualTo(AiReport.Status.DRAFT);
        assertThat(tl.getSuccess()).isTrue();
        assertThat(tl.getCompletionTokens()).isEqualTo(4096);
        assertThat(tl.getErrorSummary()).startsWith("[MAX_TOKENS_TRUNCATED]");

        AiReport f = reportRepository.findById(empty.getId()).orElseThrow();
        AiCallLog fl = callLogRepository.findById(emptyLog.getId()).orElseThrow();
        assertThat(f.getStatus()).isEqualTo(AiReport.Status.FAILED);
        assertThat(f.getFailReason()).isEqualTo(reason);
        assertThat(fl.getSuccess()).isFalse();
        assertThat(fl.getErrorSummary()).isEqualTo(reason);
    }

    // ------------------------------------------------------------ 헬퍼

    private long baseCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ai_reports WHERE id <= ?", Long.class, baseReportId);
    }

    private AiReport persist(AiReport report) {
        return reportRepository.save(report);
    }

    /** firstAlarm()으로 정한 시드 알람의 설비 — 직접 심는 리포트가 알람과 같은 설비를 가리키게 한다. */
    private Long seedEquipmentId;

    private AiReport generatingReport(Long alarmId, Long inspectionId) {
        return AiReport.generating(seedEquipmentId, alarmId, inspectionId, "테스트 리포트", 1L);
    }

    private AiReport failedReport(Long alarmId) {
        AiReport r = generatingReport(alarmId, null);
        r.fail("AI 서버 오류(HTTP 503)로 생성에 실패했습니다(재시도 후에도 실패)", "claude-sonnet-5-5");
        return r;
    }

    private AiReport draftReport(Long alarmId) {
        AiReport r = generatingReport(alarmId, null);
        r.completeDraft("# AI 원본", "claude-sonnet-5-5", 1000, 500);
        return r;
    }

    private void fillCallLogs(int count) {
        for (int i = 0; i < count; i++) {
            callLogRepository.save(new AiCallLog(null, 1L, Instant.now(), "mock", "mock"));
        }
    }

    private long requestReport(String token, String body) throws Exception {
        return json(mockMvc.perform(post("/api/v1/ai-reports").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())).get("reportId").asLong();
    }

    /** 폴링 — GENERATING이 끝날 때까지(최대 10초) 기다렸다가 상세를 돌려준다. */
    private JsonNode awaitNotGenerating(String token, long reportId) {
        JsonNode[] holder = new JsonNode[1];
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50)).until(() -> {
            holder[0] = json(mockMvc.perform(get("/api/v1/ai-reports/{id}", reportId)
                    .header("Authorization", bearer(token))).andExpect(status().isOk()));
            return !"GENERATING".equals(holder[0].get("status").asText());
        });
        return holder[0];
    }

    /** 시드 알람 중 첫 건 (GET /alarms는 OPEN 우선·최신순). 설비 코드는 응답에 포함돼 있다. */
    private JsonNode firstAlarm(String token) throws Exception {
        JsonNode alarm = json(mockMvc.perform(get("/api/v1/alarms").param("size", "1")
                .header("Authorization", bearer(token))).andExpect(status().isOk())).get("content").get(0);
        seedEquipmentId = alarm.get("equipmentId").asLong();
        return alarm;
    }

    private JsonNode findInspection(String token, boolean sameEquipment, long equipmentId) throws Exception {
        for (JsonNode row : json(mockMvc.perform(get("/api/v1/inspections").param("size", "50")
                .header("Authorization", bearer(token))).andExpect(status().isOk())).get("content")) {
            if ((row.get("equipmentId").asLong() == equipmentId) == sameEquipment) {
                return row;
            }
        }
        return null;
    }

    private String login(String email) throws Exception {
        return json(mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"fabwatch123\"}"))
                .andExpect(status().isOk())).get("accessToken").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
