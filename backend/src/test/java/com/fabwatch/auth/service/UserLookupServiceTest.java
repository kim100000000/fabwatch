package com.fabwatch.auth.service;

import com.fabwatch.auth.dto.UserLookupResponse;
import com.fabwatch.auth.entity.Role;
import com.fabwatch.auth.entity.User;
import com.fabwatch.auth.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * 사용자 경량 조회 서비스 단위 테스트 — 활성 사용자 조회 위임과 DTO 매핑(개인정보 미포함).
 * 비활성/삭제 필터링 자체는 쿼리·엔티티 제약이라 UserLookupApiIntegrationTest에서 실DB로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class UserLookupServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserLookupService service;

    @Test
    @DisplayName("활성 사용자 목록을 id/name/role로 매핑하고 repository가 준 순서(이름순)를 유지")
    void 매핑과_순서_유지() {
        given(userRepository.findByEnabledTrueOrderByNameAscIdAsc())
                .willReturn(List.of(user(2L, "김관리", Role.ADMIN), user(1L, "이테크니션", Role.TECHNICIAN),
                        user(3L, "정엔지니어", Role.ENGINEER)));

        List<UserLookupResponse> result = service.lookupActiveUsers();

        assertThat(result).containsExactly(
                new UserLookupResponse(2L, "김관리", "ADMIN"),
                new UserLookupResponse(1L, "이테크니션", "TECHNICIAN"),
                new UserLookupResponse(3L, "정엔지니어", "ENGINEER"));
    }

    @Test
    @DisplayName("사용자가 없으면 빈 배열")
    void 빈_목록() {
        given(userRepository.findByEnabledTrueOrderByNameAscIdAsc()).willReturn(List.of());

        assertThat(service.lookupActiveUsers()).isEmpty();
    }

    @Test
    @DisplayName("응답 DTO는 id/name/role 외 필드(이메일·비밀번호·토큰·잠금)를 갖지 않는다")
    void DTO_필드_집합() {
        assertThat(Arrays.stream(UserLookupResponse.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("id", "name", "role");
    }

    private static User user(Long id, String name, Role role) {
        User user = User.builder().email(name + "@fabwatch.dev").password("hash").name(name).role(role)
                .enabled(true).build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
