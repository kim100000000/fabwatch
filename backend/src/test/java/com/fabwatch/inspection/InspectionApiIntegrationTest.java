package com.fabwatch.inspection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F-3 점검 이력 + PM 스케줄 API 통합 테스트 (시드 데이터 기준: 점검 20건 = PM 15 / BM 5, PM NG 3건, 설비 5대).
 * 클래스 단위 @Transactional — 각 테스트의 쓰기는 끝나면 롤백되어 시드 건수가 테스트 순서에 영향받지 않는다.
 * (MockMvc는 테스트 스레드에서 실행되므로 서비스 트랜잭션이 테스트 트랜잭션에 합류한다)
 */
@SpringBootTest(properties = "fabwatch.seed.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
@Transactional
class InspectionApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    // ------------------------------------------------------------ 목록: 필터 / 페이징 / 응답 shape

    @Test
    @DisplayName("목록: PageResponse 래핑 + 최신순 + 표시용 필드(설비 코드/이름·작업자명) 포함")
    void list_shapeAndOrder() throws Exception {
        String token = login("tech@fabwatch.dev");

        JsonNode body = json(mockMvc.perform(get("/api/v1/inspections").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(20))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.content.length()").value(20))
                .andExpect(jsonPath("$.content[0].equipmentCode").exists())
                .andExpect(jsonPath("$.content[0].equipmentName").exists())
                .andExpect(jsonPath("$.content[0].workerName").exists())
                .andExpect(jsonPath("$.content[0].durationMin").exists()));

        Instant previous = null;
        for (JsonNode row : body.get("content")) {
            Instant startedAt = Instant.parse(row.get("startedAt").asText());
            if (previous != null) {
                assertThat(startedAt).isBeforeOrEqualTo(previous);
            }
            previous = startedAt;
        }
    }

    @Test
    @DisplayName("목록: 페이징 — size=5면 4페이지, page=1은 다음 5건")
    void list_paging() throws Exception {
        String token = login("tech@fabwatch.dev");

        mockMvc.perform(get("/api/v1/inspections").param("size", "5").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(5))
                .andExpect(jsonPath("$.totalElements").value(20))
                .andExpect(jsonPath("$.totalPages").value(4));
        mockMvc.perform(get("/api/v1/inspections").param("size", "5").param("page", "3")
                        .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.number").value(3))
                .andExpect(jsonPath("$.content.length()").value(5));
    }

    @Test
    @DisplayName("목록: type / hasNg / workerId / equipmentId / 기간 필터")
    void list_filters() throws Exception {
        String token = login("engineer@fabwatch.dev");

        mockMvc.perform(get("/api/v1/inspections").param("type", "BM").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.content[0].cause4m").exists());
        mockMvc.perform(get("/api/v1/inspections").param("type", "PM").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(15));
        mockMvc.perform(get("/api/v1/inspections").param("hasNg", "true").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].hasNg").value(true));
        mockMvc.perform(get("/api/v1/inspections").param("hasNg", "false").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(17));

        // 설비 필터 — LAMI-01
        long lami01 = equipmentId(token, "LAMI-01");
        JsonNode byEquipment = json(mockMvc.perform(get("/api/v1/inspections")
                        .param("equipmentId", String.valueOf(lami01)).header("Authorization", bearer(token)))
                .andExpect(status().isOk()));
        assertThat(byEquipment.get("totalElements").asInt()).isPositive();
        byEquipment.get("content").forEach(row -> assertThat(row.get("equipmentCode").asText()).isEqualTo("LAMI-01"));

        // 교대·작업자 필터는 결과 전부가 조건을 만족해야 한다
        JsonNode day = json(mockMvc.perform(get("/api/v1/inspections").param("shift", "D")
                .header("Authorization", bearer(token))).andExpect(status().isOk()));
        day.get("content").forEach(row -> assertThat(row.get("shift").asText()).isEqualTo("D"));
        long workerId = byEquipment.get("content").get(0).get("workerId").asLong();
        JsonNode byWorker = json(mockMvc.perform(get("/api/v1/inspections").param("workerId", String.valueOf(workerId))
                .header("Authorization", bearer(token))).andExpect(status().isOk()));
        assertThat(byWorker.get("totalElements").asInt()).isPositive();
        byWorker.get("content").forEach(row -> assertThat(row.get("workerId").asLong()).isEqualTo(workerId));

        // 기간 — 미래 구간은 0건, 전체 구간은 20건
        Instant now = Instant.now();
        mockMvc.perform(get("/api/v1/inspections").param("from", now.plus(1, ChronoUnit.DAYS).toString())
                        .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content.length()").value(0));
        mockMvc.perform(get("/api/v1/inspections").param("from", now.minus(60, ChronoUnit.DAYS).toString())
                        .param("to", now.plus(1, ChronoUnit.DAYS).toString()).header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(20));
    }

    @Test
    @DisplayName("목록: 잘못된 enum 값은 400 VALIDATION_ERROR, 인증 없으면 401 — 공통 에러 포맷")
    void list_errors() throws Exception {
        String token = login("tech@fabwatch.dev");
        mockMvc.perform(get("/api/v1/inspections").param("type", "XX").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());
        mockMvc.perform(get("/api/v1/inspections"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // ------------------------------------------------------------ 상세

    @Test
    @DisplayName("상세: LAMI-01 PM 이력은 체크리스트 결과(항목명·기준 포함)를 가진다, 없는 id는 404")
    void detail() throws Exception {
        String token = login("tech@fabwatch.dev");
        long lami01 = equipmentId(token, "LAMI-01");
        JsonNode list = json(mockMvc.perform(get("/api/v1/inspections")
                .param("equipmentId", String.valueOf(lami01)).param("type", "PM").header("Authorization", bearer(token))));
        long id = list.get("content").get(0).get("id").asLong();

        mockMvc.perform(get("/api/v1/inspections/{id}", id).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.type").value("PM"))
                .andExpect(jsonPath("$.checkResults.length()").value(5))
                .andExpect(jsonPath("$.checkResults[0].checklistItemId").exists())
                .andExpect(jsonPath("$.checkResults[0].itemName").value("합착 롤러 마모 확인"))
                .andExpect(jsonPath("$.checkResults[0].criteria").exists())
                .andExpect(jsonPath("$.checkResults[0].result").exists());

        mockMvc.perform(get("/api/v1/inspections/{id}", 999999).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ------------------------------------------------------------ 등록: 검증

    @Test
    @DisplayName("등록 검증: BM 4M 누락 400 CAUSE_4M_REQUIRED / 시각 역전·미래 400 VALIDATION_ERROR / 필수값 누락 400")
    void create_validation() throws Exception {
        String token = login("tech@fabwatch.dev");
        long equipmentId = equipmentId(token, "AOI-01");
        Instant started = Instant.now().minus(3, ChronoUnit.HOURS);
        Instant ended = Instant.now().minus(1, ChronoUnit.HOURS);

        postInspection(token, bmBody(equipmentId, started, ended, null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAUSE_4M_REQUIRED"))
                .andExpect(jsonPath("$.timestamp").exists());
        postInspection(token, bmBody(equipmentId, ended, started, "MACHINE", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        postInspection(token, bmBody(equipmentId, Instant.now().plus(1, ChronoUnit.HOURS),
                Instant.now().plus(2, ChronoUnit.HOURS), "MACHINE", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        postInspection(token, "{\"equipmentId\":" + equipmentId + ",\"type\":\"PM\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("등록: 201 + 상세 shape, 작성자=로그인 사용자, shift 자동 판정, 소요시간 계산, 24시간 초과 허용, 설비 상태 불변")
    void create_success() throws Exception {
        String token = login("tech@fabwatch.dev");
        long equipmentId = equipmentId(token, "AOI-01");
        String statusBefore = equipmentStatus(token, equipmentId);
        // KST 22:00 ~ 익일 23:30 (25시간 30분) — N 교대 시작, 24시간 초과
        Instant started = Instant.now().minus(60, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
        Instant ended = started.plus(25 * 60 + 30, ChronoUnit.MINUTES);

        mockMvc.perform(post("/api/v1/inspections").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bmBody(equipmentId, started, ended, "MACHINE", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.equipmentCode").value("AOI-01"))
                .andExpect(jsonPath("$.type").value("BM"))
                .andExpect(jsonPath("$.workerName").value("이테크니션"))
                .andExpect(jsonPath("$.durationMin").value(25 * 60 + 30))
                .andExpect(jsonPath("$.cause4m").value("MACHINE"))
                .andExpect(jsonPath("$.hasNg").value(false))
                .andExpect(jsonPath("$.reviewedBy").doesNotExist())
                .andExpect(jsonPath("$.checkResults.length()").value(0))
                .andExpect(jsonPath("$.createdAt").exists());

        // BM 등록은 설비 상태를 바꾸지 않는다 (DOWN→IDLE은 프론트가 확인 후 PATCH status로)
        assertThat(equipmentStatus(token, equipmentId)).isEqualTo(statusBefore);
    }

    @Test
    @DisplayName("PM 등록: 체크리스트 NG → hasNg=true, 결과 저장, PM 스케줄 last_done/next_due 갱신 + 지연 해소")
    void create_pmUpdatesScheduleAndNg() throws Exception {
        String token = login("tech@fabwatch.dev");
        long lami02 = equipmentId(token, "LAMI-02"); // 시드: 예정일 2일 경과(OVERDUE)
        long lami01 = equipmentId(token, "LAMI-01"); // 체크리스트 5항목 보유

        JsonNode before = json(mockMvc.perform(get("/api/v1/pm-schedules").param("equipmentId", String.valueOf(lami02))
                .header("Authorization", bearer(token))));
        assertThat(before.get(0).get("overdue").asBoolean()).isTrue();

        // LAMI-01: 체크리스트 NG 포함 PM
        JsonNode items = json(mockMvc.perform(get("/api/v1/equipments/{id}/checklist", lami01)
                .header("Authorization", bearer(token))).andExpect(status().isOk()));
        Instant ended = Instant.now().minus(30, ChronoUnit.MINUTES);
        String body = """
                {"equipmentId":%d,"type":"PM","startedAt":"%s","endedAt":"%s","content":"정기 PM",
                 "checkResults":[{"checklistItemId":%d,"result":"OK"},{"checklistItemId":%d,"result":"NG","note":"기준 미달"}]}
                """.formatted(lami01, ended.minus(1, ChronoUnit.HOURS), ended,
                items.get(0).get("id").asLong(), items.get(1).get("id").asLong());
        mockMvc.perform(post("/api/v1/inspections").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hasNg").value(true))
                .andExpect(jsonPath("$.checkResults.length()").value(2))
                .andExpect(jsonPath("$.checkResults[1].result").value("NG"))
                .andExpect(jsonPath("$.checkResults[1].note").value("기준 미달"));

        // LAMI-02: 체크 결과 없는 PM → 스케줄 갱신
        postInspection(token, """
                {"equipmentId":%d,"type":"PM","startedAt":"%s","endedAt":"%s","content":"정기 PM"}
                """.formatted(lami02, ended.minus(1, ChronoUnit.HOURS), ended))
                .andExpect(status().isCreated());

        JsonNode after = json(mockMvc.perform(get("/api/v1/pm-schedules").param("equipmentId", String.valueOf(lami02))
                .header("Authorization", bearer(token))));
        JsonNode schedule = after.get(0);
        assertThat(schedule.get("overdue").asBoolean()).isFalse();
        assertThat(schedule.get("overdueDays").asInt()).isZero();
        assertThat(Instant.parse(schedule.get("lastDoneAt").asText()).truncatedTo(ChronoUnit.SECONDS))
                .isEqualTo(ended.truncatedTo(ChronoUnit.SECONDS));
        assertThat(Instant.parse(schedule.get("nextDueAt").asText())).isAfter(Instant.now());
    }

    // ------------------------------------------------------------ BM + 알람 연계

    @Test
    @DisplayName("BM+alarmId: OPEN 알람을 작성자 명의로 ACK→RESOLVED 처리, 사유 'BM 점검 이력 #id로 조치 완료'")
    void bmResolvesOpenAlarm() throws Exception {
        String token = login("tech@fabwatch.dev");
        long equipmentId = equipmentId(token, "OVEN-01");
        long alarmId = createManualAlarm(token, equipmentId, "WARNING");

        Instant ended = Instant.now().minus(10, ChronoUnit.MINUTES);
        JsonNode created = json(postInspection(token,
                bmBody(equipmentId, ended.minus(1, ChronoUnit.HOURS), ended, "MATERIAL", alarmId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.alarmId").value(alarmId)));
        long inspectionId = created.get("id").asLong();

        JsonNode alarm = findAlarm(token, equipmentId, alarmId);
        assertThat(alarm.get("status").asText()).isEqualTo("RESOLVED");
        assertThat(alarm.get("resolveNote").asText()).isEqualTo("BM 점검 이력 #" + inspectionId + "로 조치 완료");
        assertThat(alarm.get("ackByName").asText()).isEqualTo("이테크니션");
        assertThat(alarm.get("resolvedByName").asText()).isEqualTo("이테크니션");
    }

    @Test
    @DisplayName("BM+alarmId: 이미 RESOLVED 알람이면 에러 없이 201, 기존 해제 정보 유지")
    void bmWithAlreadyResolvedAlarm() throws Exception {
        String token = login("tech@fabwatch.dev");
        JsonNode resolved = json(mockMvc.perform(get("/api/v1/alarms").param("status", "RESOLVED").param("size", "1")
                .header("Authorization", bearer(token))).andExpect(status().isOk())).get("content").get(0);
        long equipmentId = resolved.get("equipmentId").asLong();
        long alarmId = resolved.get("id").asLong();
        String noteBefore = resolved.get("resolveNote").asText();

        Instant ended = Instant.now().minus(10, ChronoUnit.MINUTES);
        postInspection(token, bmBody(equipmentId, ended.minus(1, ChronoUnit.HOURS), ended, "METHOD", alarmId))
                .andExpect(status().isCreated());

        JsonNode after = findAlarm(token, equipmentId, alarmId);
        assertThat(after.get("status").asText()).isEqualTo("RESOLVED");
        assertThat(after.get("resolveNote").asText()).isEqualTo(noteBefore);
    }

    @Test
    @DisplayName("BM+alarmId: 다른 설비의 알람이면 400, 없는 알람이면 404 — 이력은 저장되지 않는다")
    void bmWithWrongAlarm() throws Exception {
        String token = login("tech@fabwatch.dev");
        long oven = equipmentId(token, "OVEN-01");
        long aoi = equipmentId(token, "AOI-01");
        long ovenAlarm = createManualAlarm(token, oven, "WARNING");
        Instant ended = Instant.now().minus(10, ChronoUnit.MINUTES);
        Instant started = ended.minus(1, ChronoUnit.HOURS);

        postInspection(token, bmBody(aoi, started, ended, "MAN", ovenAlarm))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        postInspection(token, bmBody(aoi, started, ended, "MAN", 999999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(findAlarm(token, oven, ovenAlarm).get("status").asText()).isEqualTo("OPEN");
    }

    // ------------------------------------------------------------ 수정 / 승인

    @Test
    @DisplayName("수정: 작성자 200 / 타인 TECHNICIAN 403 / ENGINEER 200 — 403은 공통 에러 포맷")
    void update_permissions() throws Exception {
        String engineer = login("engineer@fabwatch.dev");
        String tech = login("tech@fabwatch.dev");
        long equipmentId = equipmentId(engineer, "SCRB-01");
        Instant ended = Instant.now().minus(10, ChronoUnit.MINUTES);
        Instant started = ended.minus(1, ChronoUnit.HOURS);

        // 엔지니어가 작성한 이력을 테크니션이 수정 → 403
        long id = json(postInspection(engineer, bmBody(equipmentId, started, ended, "MACHINE", null))
                .andExpect(status().isCreated())).get("id").asLong();
        mockMvc.perform(put("/api/v1/inspections/{id}", id).header("Authorization", bearer(tech))
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody(started, ended, "MAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.timestamp").exists());

        // 작성자 본인(엔지니어) 수정 OK
        mockMvc.perform(put("/api/v1/inspections/{id}", id).header("Authorization", bearer(engineer))
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody(started, ended, "MAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("수정된 내용"))
                .andExpect(jsonPath("$.cause4m").value("MAN"))
                .andExpect(jsonPath("$.equipmentId").value(equipmentId))
                .andExpect(jsonPath("$.type").value("BM"));

        // 테크니션이 작성한 이력은 엔지니어가 수정 가능 (ENGINEER+)
        long techId = json(postInspection(tech, bmBody(equipmentId, started, ended, "MACHINE", null))
                .andExpect(status().isCreated())).get("id").asLong();
        mockMvc.perform(put("/api/v1/inspections/{id}", techId).header("Authorization", bearer(engineer))
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody(started, ended, "METHOD")))
                .andExpect(status().isOk());

        // 검증도 POST와 동일 — BM 4M 누락, 시각 역전
        mockMvc.perform(put("/api/v1/inspections/{id}", techId).header("Authorization", bearer(tech))
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody(started, ended, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAUSE_4M_REQUIRED"));
        mockMvc.perform(put("/api/v1/inspections/{id}", techId).header("Authorization", bearer(tech))
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody(ended, started, "MAN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("승인: TECHNICIAN 403 / ENGINEER 200 + reviewedBy·reviewedByName·reviewedAt / 재승인은 멱등")
    void review() throws Exception {
        String engineer = login("engineer@fabwatch.dev");
        String tech = login("tech@fabwatch.dev");
        long equipmentId = equipmentId(tech, "SCRB-01");
        Instant ended = Instant.now().minus(10, ChronoUnit.MINUTES);
        long id = json(postInspection(tech, bmBody(equipmentId, ended.minus(1, ChronoUnit.HOURS), ended, "MACHINE", null))
                .andExpect(status().isCreated())).get("id").asLong();

        mockMvc.perform(patch("/api/v1/inspections/{id}/review", id).header("Authorization", bearer(tech)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        JsonNode first = json(mockMvc.perform(patch("/api/v1/inspections/{id}/review", id)
                        .header("Authorization", bearer(engineer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewedByName").value("박엔지니어"))
                .andExpect(jsonPath("$.reviewedAt").exists()));
        JsonNode second = json(mockMvc.perform(patch("/api/v1/inspections/{id}/review", id)
                        .header("Authorization", bearer(login("admin@fabwatch.dev"))))
                .andExpect(status().isOk()));
        assertThat(second.get("reviewedBy").asLong()).isEqualTo(first.get("reviewedBy").asLong());
        assertThat(second.get("reviewedAt").asText()).isEqualTo(first.get("reviewedAt").asText());

        mockMvc.perform(patch("/api/v1/inspections/{id}/review", 999999).header("Authorization", bearer(engineer)))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------ 체크리스트 템플릿

    @Test
    @DisplayName("체크리스트: 조회=활성 항목 seq순 단순 배열 / 추가·수정은 ENGINEER+ / active=false로 삭제")
    void checklist() throws Exception {
        String tech = login("tech@fabwatch.dev");
        String engineer = login("engineer@fabwatch.dev");
        long lami01 = equipmentId(tech, "LAMI-01");

        JsonNode list = json(mockMvc.perform(get("/api/v1/equipments/{id}/checklist", lami01)
                        .header("Authorization", bearer(tech)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].seq").value(1))
                .andExpect(jsonPath("$[0].active").value(true)));
        assertThat(list.isArray()).isTrue();

        String newItem = "{\"itemName\":\"냉각수 유량 확인\",\"criteria\":\"기준 유량 이상\"}";
        mockMvc.perform(post("/api/v1/equipments/{id}/checklist", lami01).header("Authorization", bearer(tech))
                        .contentType(MediaType.APPLICATION_JSON).content(newItem))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        JsonNode created = json(mockMvc.perform(post("/api/v1/equipments/{id}/checklist", lami01)
                        .header("Authorization", bearer(engineer)).contentType(MediaType.APPLICATION_JSON).content(newItem))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seq").value(6))
                .andExpect(jsonPath("$.active").value(true)));
        long itemId = created.get("id").asLong();

        mockMvc.perform(put("/api/v1/equipments/{id}/checklist/{itemId}", lami01, itemId)
                        .header("Authorization", bearer(engineer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemName\":\"냉각수 유량 확인\",\"criteria\":\"기준 유량 이상\",\"seq\":6,\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        mockMvc.perform(get("/api/v1/equipments/{id}/checklist", lami01).header("Authorization", bearer(tech)))
                .andExpect(jsonPath("$.length()").value(5));

        mockMvc.perform(get("/api/v1/equipments/{id}/checklist", 999999).header("Authorization", bearer(tech)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/equipments/{id}/checklist", lami01).header("Authorization", bearer(engineer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"itemName\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ------------------------------------------------------------ PM 스케줄

    @Test
    @DisplayName("PM 스케줄 조회: 단순 배열, 시드 5건, 지연 건은 overdue=true/overdueDays=2, overdueOnly 필터")
    void pmSchedules_list() throws Exception {
        String token = login("tech@fabwatch.dev");

        JsonNode all = json(mockMvc.perform(get("/api/v1/pm-schedules").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].equipmentCode").exists())
                .andExpect(jsonPath("$[0].equipmentName").exists())
                .andExpect(jsonPath("$[0].cycleType").exists())
                .andExpect(jsonPath("$[0].nextDueAt").exists())
                .andExpect(jsonPath("$[0].overdue").exists()));
        assertThat(all.isArray()).isTrue();
        // 예정일 빠른 순 → 지연 건(LAMI-02)이 맨 앞
        assertThat(all.get(0).get("equipmentCode").asText()).isEqualTo("LAMI-02");
        assertThat(all.get(0).get("overdue").asBoolean()).isTrue();
        assertThat(all.get(0).get("overdueDays").asInt()).isEqualTo(2);

        mockMvc.perform(get("/api/v1/pm-schedules").param("overdueOnly", "true").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].equipmentCode").value("LAMI-02"));
    }

    @Test
    @DisplayName("PM 스케줄 설정: ENGINEER+만 / 주기 값 검증 400 / next_due 재계산")
    void pmSchedules_update() throws Exception {
        String tech = login("tech@fabwatch.dev");
        String engineer = login("engineer@fabwatch.dev");
        long aoi = equipmentId(tech, "AOI-01");

        mockMvc.perform(put("/api/v1/equipments/{id}/pm-schedule", aoi).header("Authorization", bearer(tech))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cycleType\":\"DAILY\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        for (String invalid : new String[]{
                "{\"cycleType\":\"WEEKLY\",\"cycleValue\":8}", "{\"cycleType\":\"WEEKLY\"}",
                "{\"cycleType\":\"MONTHLY\",\"cycleValue\":29}", "{\"cycleType\":\"DAILY\",\"cycleValue\":1}",
                "{\"cycleValue\":3}"}) {
            mockMvc.perform(put("/api/v1/equipments/{id}/pm-schedule", aoi).header("Authorization", bearer(engineer))
                            .contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        mockMvc.perform(put("/api/v1/equipments/{id}/pm-schedule", aoi).header("Authorization", bearer(engineer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cycleType\":\"MONTHLY\",\"cycleValue\":15}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.equipmentCode").value("AOI-01"))
                .andExpect(jsonPath("$.cycleType").value("MONTHLY"))
                .andExpect(jsonPath("$.cycleValue").value(15))
                .andExpect(jsonPath("$.nextDueAt").exists())
                .andExpect(jsonPath("$.overdue").exists());

        mockMvc.perform(put("/api/v1/equipments/{id}/pm-schedule", 999999).header("Authorization", bearer(engineer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cycleType\":\"DAILY\"}"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------ 헬퍼

    private ResultActions postInspection(String token, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/inspections").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String bmBody(long equipmentId, Instant started, Instant ended, String cause4m, Long alarmId) {
        return """
                {"equipmentId":%d,"type":"BM","startedAt":"%s","endedAt":"%s","content":"롤러 진동 이상 정지",
                 "actionTaken":"부품 교체 후 시운전"%s%s}
                """.formatted(equipmentId, started, ended,
                cause4m == null ? "" : ",\"cause4m\":\"" + cause4m + "\",\"causeDetail\":\"베어링 마모\"",
                alarmId == null ? "" : ",\"alarmId\":" + alarmId);
    }

    private static String updateBody(Instant started, Instant ended, String cause4m) {
        return """
                {"startedAt":"%s","endedAt":"%s","content":"수정된 내용"%s}
                """.formatted(started, ended, cause4m == null ? "" : ",\"cause4m\":\"" + cause4m + "\"");
    }

    private long createManualAlarm(String token, long equipmentId, String severity) throws Exception {
        return json(mockMvc.perform(post("/api/v1/alarms/manual").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"equipmentId\":%d,\"severity\":\"%s\",\"message\":\"이상 소음 발생\"}".formatted(equipmentId, severity)))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private JsonNode findAlarm(String token, long equipmentId, long alarmId) throws Exception {
        JsonNode page = json(mockMvc.perform(get("/api/v1/alarms").param("equipmentId", String.valueOf(equipmentId))
                .param("size", "100").header("Authorization", bearer(token))).andExpect(status().isOk()));
        for (JsonNode alarm : page.get("content")) {
            if (alarm.get("id").asLong() == alarmId) {
                return alarm;
            }
        }
        throw new IllegalStateException("알람을 찾을 수 없음: " + alarmId);
    }

    private String equipmentStatus(String token, long equipmentId) throws Exception {
        return json(mockMvc.perform(get("/api/v1/equipments/{id}", equipmentId).header("Authorization", bearer(token)))
                .andExpect(status().isOk())).get("status").asText();
    }

    private long equipmentId(String token, String code) throws Exception {
        JsonNode page = json(mockMvc.perform(get("/api/v1/equipments").header("Authorization", bearer(token)))
                .andExpect(status().isOk()));
        for (JsonNode node : page.get("content")) {
            if (code.equals(node.get("code").asText())) {
                return node.get("id").asLong();
            }
        }
        throw new IllegalStateException("시드에 " + code + " 설비가 없습니다.");
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
