package com.fabwatch.equipment.service;

import com.fabwatch.equipment.dto.EquipmentKpiListResponse;
import com.fabwatch.equipment.dto.EquipmentKpiResponse;
import com.fabwatch.equipment.entity.Equipment;
import com.fabwatch.equipment.entity.EquipmentStatus;
import com.fabwatch.equipment.entity.EquipmentStatusLog;
import com.fabwatch.equipment.entity.Process;
import com.fabwatch.equipment.repository.EquipmentRepository;
import com.fabwatch.equipment.repository.EquipmentStatusLogRepository;
import com.fabwatch.equipment.repository.ProcessRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KPI 조회 통합 테스트 (H2) — 로그 조회 쿼리(시작 시점 상태 서브쿼리·기간 범위)와 API 계약을 검증한다.
 * 서비스의 package-private 오버로드로 now를 고정해 결정적 수치를 확인한다(같은 패키지).
 * 시드 설비 로그는 실행 시각 기준이라 고정 과거 시각(2026-10-05)과 섞여도 영향이 없도록
 * 수치 검증은 테스트가 직접 만든 설비에 대해서만 한다.
 */
@SpringBootTest(properties = "fabwatch.seed.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
@Transactional
class EquipmentKpiIntegrationTest {

    @Autowired
    private EquipmentKpiService kpiService;
    @Autowired
    private EquipmentRepository equipmentRepository;
    @Autowired
    private ProcessRepository processRepository;
    @Autowired
    private EquipmentStatusLogRepository logRepository;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private static Instant kst(String local) {
        return LocalDateTime.parse(local).atZone(com.fabwatch.common.util.ShiftUtil.KST).toInstant();
    }

    private Equipment newEquipment(String code) {
        Process process = processRepository.findAll().get(0);
        Equipment equipment = Equipment.builder().code(code).name(code + " 설비").status(EquipmentStatus.RUN).build();
        equipment.moveToProcess(process);
        return equipmentRepository.save(equipment);
    }

    private void log(Equipment equipment, EquipmentStatus to, String kstTime) {
        logRepository.save(new EquipmentStatusLog(equipment, null, to, "테스트", null, kst(kstTime)));
    }

    @Test
    @DisplayName("DB 로그로 일 KPI 계산 — 자정 전 진입 DOWN 절삭, 진행 중 DOWN 제외, 다른 설비 로그 미혼입")
    void 일_KPI_로그_조회_계산() {
        Equipment target = newEquipment("KPI-T1");
        log(target, EquipmentStatus.RUN, "2026-10-04T08:00:00");
        log(target, EquipmentStatus.DOWN, "2026-10-04T23:00:00");
        log(target, EquipmentStatus.IDLE, "2026-10-05T01:00:00");
        log(target, EquipmentStatus.RUN, "2026-10-05T02:00:00");
        log(target, EquipmentStatus.DOWN, "2026-10-05T10:00:00"); // 진행 중
        Equipment other = newEquipment("KPI-T2");
        log(other, EquipmentStatus.RUN, "2026-10-05T03:00:00");

        Instant now = kst("2026-10-05T12:00:00");
        EquipmentKpiResponse kpi = kpiService.getKpi(target.getId(), KpiPeriod.DAY, now);

        assertThat(kpi.periodStart()).isEqualTo(kst("2026-10-05T00:00:00"));
        assertThat(kpi.periodEnd()).isEqualTo(now);
        assertThat(kpi.downCount()).isEqualTo(1);                         // 10:00 진입만 (23:00 진입은 기간 밖)
        assertThat(kpi.mttrMin()).isCloseTo(60.0, within(1e-9));          // 00:00~01:00 절삭, 진행 중 건 제외
        assertThat(kpi.mtbfHours()).isCloseTo(8.0, within(1e-9));         // RUN 02:00~10:00
        assertThat(kpi.availability()).isCloseTo(8.0 / 12, within(1e-4)); // 0.6667

        EquipmentKpiResponse otherKpi = kpiService.getKpi(other.getId(), KpiPeriod.DAY, now);
        assertThat(otherKpi.downCount()).isZero();
        assertThat(otherKpi.mtbfHours()).isNull();
        // 첫 로그(03:00) 이후만 집계 — 9시간 전부 RUN
        assertThat(otherKpi.availability()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("로그가 없는 설비 → 모든 수치 null, downCount 0")
    void 로그_없는_설비() {
        Equipment bare = newEquipment("KPI-T3");

        EquipmentKpiResponse kpi = kpiService.getKpi(bare.getId(), KpiPeriod.MONTH, kst("2026-10-05T12:00:00"));

        assertThat(kpi.availability()).isNull();
        assertThat(kpi.mtbfHours()).isNull();
        assertThat(kpi.mttrMin()).isNull();
        assertThat(kpi.downCount()).isZero();
    }

    @Test
    @DisplayName("목록 KPI: 요약은 설비별 원재료의 합산 재계산, 삭제된 설비는 제외")
    void 목록_KPI_요약_합산_삭제설비_제외() {
        Equipment a = newEquipment("KPI-A");
        log(a, EquipmentStatus.RUN, "2026-10-05T00:00:00");  // 12h RUN (가동률 1.0)
        Equipment b = newEquipment("KPI-B");
        log(b, EquipmentStatus.RUN, "2026-10-05T06:00:00");  // 6h RUN, 6h IDLE → 0.5, 고장 1회 없음
        log(b, EquipmentStatus.IDLE, "2026-10-05T09:00:00");
        Equipment deleted = newEquipment("KPI-DEL");
        log(deleted, EquipmentStatus.DOWN, "2026-10-05T00:00:00");
        deleted.markDeleted();
        equipmentRepository.saveAndFlush(deleted);

        Instant now = kst("2026-10-05T12:00:00");
        EquipmentKpiListResponse list = kpiService.getKpiList(KpiPeriod.DAY, null, now);

        assertThat(list.equipments()).extracting(EquipmentKpiListResponse.Item::equipmentCode)
                .contains("KPI-A", "KPI-B").doesNotContain("KPI-DEL");
        EquipmentKpiListResponse.Item itemA = item(list, "KPI-A");
        EquipmentKpiListResponse.Item itemB = item(list, "KPI-B");
        assertThat(itemA.availability()).isEqualTo(1.0);
        assertThat(itemB.availability()).isCloseTo(3.0 / 6, within(1e-4)); // 첫 로그 06:00부터 6h 중 RUN 3h

        // 요약.downCount = 설비별 downCount 합
        int sumDown = list.equipments().stream().mapToInt(EquipmentKpiListResponse.Item::downCount).sum();
        assertThat(list.summary().downCount()).isEqualTo(sumDown);
    }

    @Test
    @DisplayName("lineId 필터: 존재하지 않는 라인은 404, 존재하는 라인은 해당 설비만")
    void 라인_필터() {
        Long lineId = processRepository.findAll().get(0).getLine().getId();
        newEquipment("KPI-L1");

        EquipmentKpiListResponse list = kpiService.getKpiList(KpiPeriod.DAY, lineId, kst("2026-10-05T12:00:00"));
        assertThat(list.equipments()).extracting(EquipmentKpiListResponse.Item::equipmentCode).contains("KPI-L1");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> kpiService.getKpiList(KpiPeriod.DAY, 999_999L, kst("2026-10-05T12:00:00")))
                .isInstanceOf(com.fabwatch.common.exception.BusinessException.class);
    }

    // ---------- API 계약 ----------

    @Test
    @DisplayName("GET /equipments/{id}/kpi — 기본 period=DAY, 확정 shape(equipmentId/period/periodStart/periodEnd/…)")
    void API_단건_shape() throws Exception {
        String token = login();
        Long id = equipmentRepository.findAll().get(0).getId();

        mockMvc.perform(get("/api/v1/equipments/{id}/kpi", id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.equipmentId").value(id))
                .andExpect(jsonPath("$.period").value("DAY"))
                .andExpect(jsonPath("$.periodStart").value(org.hamcrest.Matchers.endsWith("Z")))
                .andExpect(jsonPath("$.periodEnd").value(org.hamcrest.Matchers.endsWith("Z")))
                .andExpect(jsonPath("$.downCount").isNumber())
                // null 가능 필드는 키가 생략되지 않고 null로 내려가야 프론트 타입(number | null)과 맞는다
                .andExpect(result -> assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                        .fieldNames()).toIterable()
                        .contains("mtbfHours", "mttrMin", "availability"));
    }

    @Test
    @DisplayName("GET /equipments/kpi 는 /{id}로 해석되지 않고 summary+equipments shape을 돌려준다")
    void API_목록_경로_충돌_없음() throws Exception {
        String token = login();

        mockMvc.perform(get("/api/v1/equipments/kpi").param("period", "WEEK")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("WEEK"))
                .andExpect(jsonPath("$.summary.downCount").isNumber())
                .andExpect(result -> assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                        .get("summary").fieldNames()).toIterable()
                        .contains("mtbfHours", "mttrMin", "availability", "downCount"))
                .andExpect(jsonPath("$.equipments.length()").value(5))
                .andExpect(jsonPath("$.equipments[0].equipmentCode").exists())
                .andExpect(jsonPath("$.equipments[0].equipmentName").exists());
    }

    @Test
    @DisplayName("잘못된 period → 400 VALIDATION_ERROR, 없는 설비 → 404 NOT_FOUND, 없는 라인 → 404, 토큰 없음 → 401")
    void API_에러() throws Exception {
        String token = login();
        Long id = equipmentRepository.findAll().get(0).getId();

        mockMvc.perform(get("/api/v1/equipments/{id}/kpi", id).param("period", "YEAR")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(get("/api/v1/equipments/kpi").param("period", "x")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(get("/api/v1/equipments/{id}/kpi", 999_999L).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/v1/equipments/kpi").param("lineId", "999999")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/equipments/kpi"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("TECHNICIAN을 포함한 전체 로그인 사용자가 조회 가능")
    void API_전체_역할_허용() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"tech@fabwatch.dev\",\"password\":\"fabwatch123\"}"))
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(body).get("accessToken").asText();

        mockMvc.perform(get("/api/v1/equipments/kpi").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private EquipmentKpiListResponse.Item item(EquipmentKpiListResponse list, String code) {
        return list.equipments().stream().filter(i -> i.equipmentCode().equals(code)).findFirst().orElseThrow();
    }

    private String login() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@fabwatch.dev\",\"password\":\"fabwatch123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }
}
