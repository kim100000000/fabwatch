package com.fabwatch.aireport;

import com.fabwatch.aireport.entity.AiCallLog;
import com.fabwatch.aireport.repository.AiCallLogRepository;
import com.fabwatch.aireport.service.AiQuotaService;
import com.fabwatch.aireport.service.KstDayRange;
import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 개인 일일 쿼터(기본 5)와 전체 일일 쿼터(기본 20)를 실제 DB 집계로 검증한다 — 사용자 A 소진이 B에 영향 없고,
 * 전체 20 도달 시 모두 차단되며, KST 자정 이전(어제) 호출은 세지 않는다. 각 테스트는 롤백되므로 데이터가 남지 않는다.
 */
@SpringBootTest(properties = "fabwatch.test.context-isolation=ai-user-quota")
@ActiveProfiles("test")
@Transactional
class AiUserQuotaIntegrationTest {

    @Autowired
    private AiQuotaService quotaService;
    @Autowired
    private AiCallLogRepository callLogRepository;

    private void log(Long userId, int count, Instant at) {
        for (int i = 0; i < count; i++) {
            callLogRepository.save(new AiCallLog(null, userId, at, "mock", "mock"));
        }
    }

    private static Instant todayStart() {
        return KstDayRange.of(Instant.now()).from();
    }

    @Test
    @DisplayName("사용자 A가 5건 소진해도 B는 가능, A의 6번째는 개인 한도 메시지로 403")
    void A_소진해도_B는_가능() {
        log(1L, 5, Instant.now());

        assertThatThrownBy(() -> quotaService.reserve(null, 1L))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_QUOTA_EXCEEDED);
                    assertThat(e.getMessage()).contains("개인").doesNotContain("전체");
                });

        assertThat(quotaService.reserve(null, 2L)).isNotNull(); // B 가능
        assertThat(callLogRepository.count()).isEqualTo(6);
    }

    @Test
    @DisplayName("전체 20건에 도달하면 한도가 남은 사용자도 전체 한도 메시지로 차단된다")
    void 전체_20이면_모두_차단() {
        // 4명 × 5건 = 20건 (각자 개인 한도에 딱 도달)
        for (long user = 1; user <= 4; user++) {
            log(user, 5, Instant.now());
        }

        assertThatThrownBy(() -> quotaService.reserve(null, 99L)) // 한 건도 안 쓴 사용자
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_QUOTA_EXCEEDED);
                    assertThat(e.getMessage()).contains("전체");
                });
    }

    @Test
    @DisplayName("개인 한도는 4건까지는 허용(N-1), 5번째 시도가 막히는 것은 6번째 요청부터")
    void 개인_경계() {
        log(1L, 4, Instant.now());
        assertThat(quotaService.reserve(null, 1L)).isNotNull(); // 5번째 허용
        assertThatThrownBy(() -> quotaService.reserve(null, 1L)).isInstanceOf(BusinessException.class); // 6번째 거부
    }

    @Test
    @DisplayName("KST 자정 경계: 자정 1초 전(어제) 호출은 세지 않고, 자정 정각(오늘) 호출은 센다")
    void KST_자정_경계() {
        log(1L, 5, todayStart().minusSeconds(1)); // 어제 KST 23:59:59
        assertThat(quotaService.reserve(null, 1L)).isNotNull(); // 어제 5건은 무시 → 허용

        log(3L, 5, todayStart()); // 오늘 KST 00:00:00 정각 → 오늘로 센다
        assertThatThrownBy(() -> quotaService.reserve(null, 3L)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("실패(FAILED)·진행 중·mock 호출도 개인 한도에 동일하게 센다 (전체 쿼터와 같은 기준)")
    void 성공_실패_모두_카운트() {
        for (int i = 0; i < 5; i++) {
            AiCallLog row = callLogRepository.save(new AiCallLog(null, 1L, Instant.now(), "claude", "m"));
            if (i % 2 == 0) {
                row.fail("[TEST] 실패 사유"); // 일부는 실패, 일부는 진행 중(success=null)
            }
        }
        assertThatThrownBy(() -> quotaService.reserve(null, 1L)).isInstanceOf(BusinessException.class);
    }
}
