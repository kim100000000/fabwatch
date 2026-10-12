package com.fabwatch.common.seed;

import com.fabwatch.auth.repository.UserRepository;
import com.fabwatch.equipment.repository.EquipmentStatusLogRepository;
import com.fabwatch.equipment.repository.LineRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** 데모 로더 설정 검증 — 정책 위반 비밀번호는 아무것도 쓰기 전에 기동 실패, 메시지에 비밀번호 비노출. */
class DemoDataServiceTest {

    private final EntityManager em = mock(EntityManager.class);
    private final UserRepository userRepository = mock(UserRepository.class);

    private DemoDataService service(String accountsPassword) {
        return new DemoDataService(em, mock(LineRepository.class), mock(EquipmentStatusLogRepository.class),
                userRepository, new BCryptPasswordEncoder(4), new DemoDataProperties(true, accountsPassword));
    }

    @Test
    @DisplayName("정책 위반 비밀번호는 DB에 쓰기 전에 실패하고 메시지에 비밀번호가 없다")
    void 약한_비밀번호_거부() {
        for (String weak : new String[]{"short1", "onlyletters", "12345678"}) {
            assertThatThrownBy(() -> service(weak).load())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining(weak);
        }
        verifyNoInteractions(em, userRepository);
    }

    @Test
    @DisplayName("설정 객체 toString에 비밀번호가 노출되지 않는다")
    void toString_마스킹() {
        assertThat(new DemoDataProperties(true, "Demo-Account-Pw1").toString()).doesNotContain("Demo-Account-Pw1");
    }

    @Test
    @DisplayName("비밀번호 미설정이면 계정 생성 대상이 아니다")
    void 비밀번호_없으면_계정_없음() {
        assertThat(new DemoDataProperties(true, "").hasAccountsPassword()).isFalse();
        assertThat(new DemoDataProperties(true, null).hasAccountsPassword()).isFalse();
    }
}
