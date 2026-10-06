package com.fabwatch.auth;

import com.fabwatch.auth.entity.Role;
import com.fabwatch.auth.entity.User;
import com.fabwatch.auth.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /users/lookup 통합 테스트 (시드 사용자 3명: 김관리 ADMIN / 박엔지니어 ENGINEER / 이테크니션 TECHNICIAN).
 * 클래스 단위 @Transactional — 테스트가 추가한 사용자는 롤백된다.
 */
@SpringBootTest(properties = "fabwatch.seed.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
@Transactional
class UserLookupApiIntegrationTest {

    private static final String URL = "/api/v1/users/lookup";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("토큰 없음 → 401 공통 에러 포맷 { code, message, timestamp }")
    void 토큰_없으면_401() throws Exception {
        JsonNode body = json(mockMvc.perform(get(URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED")));

        assertThat(fieldNames(body)).containsExactlyInAnyOrder("code", "message", "timestamp");
    }

    @Test
    @DisplayName("위조/잘못된 토큰 → 401")
    void 잘못된_토큰이면_401() throws Exception {
        mockMvc.perform(get(URL).header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").exists());
    }

    @ParameterizedTest(name = "{0} 로그인 → 200")
    @ValueSource(strings = {"admin@fabwatch.dev", "engineer@fabwatch.dev", "tech@fabwatch.dev"})
    @DisplayName("3개 역할 모두 200 + 단순 배열(PageResponse 아님)")
    void 모든_역할_200(String email) throws Exception {
        JsonNode body = json(mockMvc.perform(get(URL).header("Authorization", bearer(login(email))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray()));

        assertThat(body.isArray()).isTrue();
        assertThat(body).hasSize(3);
    }

    @Test
    @DisplayName("응답 항목의 JSON 키 집합은 정확히 {id, name, role} — 이메일·비밀번호·토큰·잠금 상태 노출 없음")
    void 응답_키_집합() throws Exception {
        JsonNode body = json(mockMvc.perform(get(URL).header("Authorization", bearer(login("tech@fabwatch.dev"))))
                .andExpect(status().isOk()));

        for (JsonNode row : body) {
            assertThat(fieldNames(row)).containsExactlyInAnyOrder("id", "name", "role");
            assertThat(row.get("id").isNumber()).isTrue();
            assertThat(row.get("name").isTextual()).isTrue();
            assertThat(Set.of("ADMIN", "ENGINEER", "TECHNICIAN")).contains(row.get("role").asText());
        }
        String raw = body.toString();
        assertThat(raw).doesNotContain("@fabwatch.dev").doesNotContain("fabwatch123").doesNotContain("$2a$")
                .doesNotContain("password").doesNotContain("email").doesNotContain("refresh");
    }

    @Test
    @DisplayName("이름순 정렬 + 시드 3명의 역할 매핑")
    void 이름순_정렬() throws Exception {
        userRepository.save(newUser("zz@fabwatch.dev", "황추가", Role.ENGINEER, true));
        userRepository.save(newUser("aa@fabwatch.dev", "가나다", Role.TECHNICIAN, true));

        JsonNode body = json(mockMvc.perform(get(URL).header("Authorization", bearer(login("admin@fabwatch.dev"))))
                .andExpect(status().isOk()));

        List<String> names = new ArrayList<>();
        body.forEach(row -> names.add(row.get("name").asText()));
        assertThat(names).containsExactly("가나다", "김관리", "박엔지니어", "이테크니션", "황추가");
        assertThat(body.get(1).get("role").asText()).isEqualTo("ADMIN");
        assertThat(body.get(2).get("role").asText()).isEqualTo("ENGINEER");
        assertThat(body.get(3).get("role").asText()).isEqualTo("TECHNICIAN");
    }

    @Test
    @DisplayName("비활성(enabled=false) 사용자와 soft delete 사용자는 제외")
    void 비활성_삭제_제외() throws Exception {
        String token = login("tech@fabwatch.dev");
        userRepository.save(newUser("off@fabwatch.dev", "비활성자", Role.TECHNICIAN, false));
        User deleted = newUser("del@fabwatch.dev", "삭제자", Role.TECHNICIAN, true);
        deleted.markDeleted();
        userRepository.saveAndFlush(deleted);
        userRepository.save(newUser("on@fabwatch.dev", "활성자", Role.TECHNICIAN, true));

        JsonNode body = json(mockMvc.perform(get(URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk()));

        List<String> names = new ArrayList<>();
        body.forEach(row -> names.add(row.get("name").asText()));
        assertThat(names).contains("활성자").doesNotContain("비활성자", "삭제자");
        assertThat(names).hasSize(4);
    }

    @Test
    @DisplayName("경로 충돌 없음 — /users/lookup은 정적 경로로 매핑되고 POST는 허용되지 않는다")
    void 경로_충돌_없음() throws Exception {
        String token = login("admin@fabwatch.dev");
        mockMvc.perform(post(URL).header("Authorization", bearer(token)))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(get("/api/v1/users/1").header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------ 헬퍼

    private static User newUser(String email, String name, Role role, boolean enabled) {
        return User.builder().email(email).password("{bcrypt}unused").name(name).role(role).enabled(enabled).build();
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

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
