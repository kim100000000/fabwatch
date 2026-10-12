package com.fabwatch.common.seed;

import com.fabwatch.auth.entity.Role;
import com.fabwatch.auth.entity.User;
import com.fabwatch.auth.repository.UserRepository;
import com.fabwatch.inspection.entity.PmSchedule;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공개 데모 옵트인 로더 통합 — prod형(local 아닌) 프로파일에서 DEMO_DATA_ENABLED=true일 때
 * 마스터 데이터와 ENGINEER/TECHNICIAN 계정만 생기고(ADMIN 없음), 재실행해도 중복 생성되지 않는다.
 */
@SpringBootTest(properties = {
        "fabwatch.demo.enabled=true",
        "fabwatch.demo.accounts-password=Demo-Account-Pw1",
        "fabwatch.test.context-isolation=demo-loader"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DemoDataLoaderIntegrationTest {

    @Autowired
    private EntityManager em;
    @Autowired
    private DemoDataService demoDataService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MockMvc mockMvc;

    /** JPQL 엔티티 단위 카운트 (H2/MySQL 식별자 인용 차이를 피한다) */
    private long count(String entity) {
        return ((Number) em.createQuery("select count(x) from " + entity + " x").getSingleResult()).longValue();
    }

    @Test
    @DisplayName("기동 시 라인1·공정3·설비5·센서18·체크리스트5·PM스케줄5·최초 상태이력5, 알람/점검 이력은 없음")
    void 마스터_데이터만_생성() {
        assertThat(count("Line")).isEqualTo(1);
        assertThat(count("Process")).isEqualTo(3);
        assertThat(count("Equipment")).isEqualTo(5);
        assertThat(count("Sensor")).isEqualTo(18);
        assertThat(count("ChecklistItem")).isEqualTo(5);
        assertThat(count("PmSchedule")).isEqualTo(5);
        assertThat(count("EquipmentStatusLog")).isEqualTo(5);
        assertThat(count("Alarm")).isZero();
        assertThat(count("Inspection")).isZero();
        assertThat(count("AiReport")).isZero();
    }

    @Test
    @DisplayName("PM 스케줄은 전부 기동 시점 기준 미래 — 5대가 한꺼번에 OVERDUE가 되지 않는다")
    @SuppressWarnings("unchecked")
    void PM_전부_미래() {
        List<PmSchedule> schedules = em.createQuery("select p from PmSchedule p", PmSchedule.class).getResultList();
        assertThat(schedules).hasSize(5);
        assertThat(schedules).allSatisfy(p -> assertThat(p.getNextDueAt()).isAfter(Instant.now()));
    }

    @Test
    @DisplayName("ENGINEER·TECHNICIAN 데모 계정만 생성되고 ADMIN은 없다 — 로그인 가능, 평문 저장 없음")
    void 데모_계정_ADMIN_없음() throws Exception {
        List<User> users = userRepository.findAll();
        assertThat(users).extracting(User::getRole).containsExactlyInAnyOrder(Role.ENGINEER, Role.TECHNICIAN);
        assertThat(users).extracting(User::getEmail).containsExactlyInAnyOrder("engineer@fabwatch.dev", "tech@fabwatch.dev");
        assertThat(users).allSatisfy(u -> assertThat(u.getPassword()).startsWith("$2"));

        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"engineer@fabwatch.dev\",\"password\":\"Demo-Account-Pw1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("ENGINEER"));
    }

    @Test
    @DisplayName("재실행(재기동 시뮬레이션)해도 중복 생성되지 않는다")
    void 멱등() {
        DemoDataService.Result second = demoDataService.load();

        assertThat(second.masterDataLoaded()).isFalse();
        assertThat(second.accountsCreated()).isZero();
        assertThat(count("Line")).isEqualTo(1);
        assertThat(count("Equipment")).isEqualTo(5);
        assertThat(count("Sensor")).isEqualTo(18);
        assertThat(userRepository.count()).isEqualTo(2);
    }
}
