package com.fabwatch.aireport.service;

import com.fabwatch.aireport.config.AiProperties;
import com.fabwatch.aireport.entity.AiCallLog;
import com.fabwatch.aireport.repository.AiCallLogRepository;
import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 일일 쿼터 경계 — N-1 허용 / N 거부, mock도 카운트, KST 일자 경계 (docs/03 F-6.4, docs/12 AI-3) */
class AiQuotaServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-02T03:00:00Z"); // KST 12:00

    private static AiProperties props(String provider, int quota) {
        return new AiProperties(provider, "", "claude-sonnet-5-5", 4096, quota, 30,
                "https://api.anthropic.com", "2023-06-01", 0, 2, 10, 5, "low");
    }

    private static AiQuotaService service(AiCallLogRepository repo, String provider, int quota, Instant now) {
        return new AiQuotaService(repo, props(provider, quota), Clock.fixed(now, ZoneOffset.UTC));
    }

    private static AiCallLogRepository repoWithUsed(long used) {
        AiCallLogRepository repo = mock(AiCallLogRepository.class);
        when(repo.countByRequestedAtGreaterThanEqualAndRequestedAtLessThan(any(), any())).thenReturn(used);
        when(repo.save(any(AiCallLog.class))).thenAnswer(inv -> inv.getArgument(0));
        return repo;
    }

    @Test
    @DisplayName("순수 판정: used >= quota 일 때만 초과")
    void isExceeded_boundary() {
        assertThat(AiQuotaService.isExceeded(19, 20)).isFalse();
        assertThat(AiQuotaService.isExceeded(20, 20)).isTrue();
        assertThat(AiQuotaService.isExceeded(21, 20)).isTrue();
        assertThat(AiQuotaService.isExceeded(0, 0)).isTrue();
    }

    @Test
    @DisplayName("N-1건 사용 시 허용 — 호출 이력 1행이 기록된다")
    void reserve_allowedAtNMinusOne() {
        AiCallLogRepository repo = repoWithUsed(19);

        AiCallLog log = service(repo, "claude", 20, NOW).reserve(7L, 3L);

        assertThat(log.getReportId()).isEqualTo(7L);
        assertThat(log.getUserId()).isEqualTo(3L);
        assertThat(log.getProvider()).isEqualTo("claude");
        assertThat(log.getModel()).isEqualTo("claude-sonnet-5-5");
        assertThat(log.getRequestedAt()).isEqualTo(NOW);
        assertThat(log.isPending()).isTrue();
        verify(repo).save(any(AiCallLog.class));
    }

    @Test
    @DisplayName("N건 사용 시 403 AI_QUOTA_EXCEEDED — 이력도 기록하지 않는다(호출 자체가 발생하지 않음)")
    void reserve_rejectedAtN() {
        AiCallLogRepository repo = repoWithUsed(20);

        assertThatThrownBy(() -> service(repo, "claude", 20, NOW).reserve(7L, 3L))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_QUOTA_EXCEEDED));
        verify(repo, never()).save(any());
        assertThat(ErrorCode.AI_QUOTA_EXCEEDED.getStatus().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("mock provider 호출도 쿼터에 센다 (model=mock으로 기록)")
    void reserve_mockCountsToo() {
        AiCallLogRepository allowed = repoWithUsed(19);
        AiCallLog log = service(allowed, "mock", 20, NOW).reserve(1L, 1L);
        assertThat(log.getProvider()).isEqualTo("mock");
        assertThat(log.getModel()).isEqualTo("mock");

        AiCallLogRepository full = repoWithUsed(20);
        assertThatThrownBy(() -> service(full, "mock", 20, NOW).reserve(1L, 1L))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_QUOTA_EXCEEDED));
    }

    @Test
    @DisplayName("집계 구간은 KST 일자 [00:00, 다음날 00:00) — 14:59:59Z 와 15:00:00Z 는 서로 다른 날")
    void reserve_usesKstDayWindow() {
        AiCallLogRepository before = repoWithUsed(0);
        service(before, "claude", 20, Instant.parse("2026-10-02T14:59:59Z")).reserve(1L, 1L);
        AiCallLogRepository after = repoWithUsed(0);
        service(after, "claude", 20, Instant.parse("2026-10-02T15:00:00Z")).reserve(1L, 1L);

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(before).countByRequestedAtGreaterThanEqualAndRequestedAtLessThan(from.capture(), to.capture());
        assertThat(from.getValue()).isEqualTo(Instant.parse("2026-10-01T15:00:00Z"));
        assertThat(to.getValue()).isEqualTo(Instant.parse("2026-10-02T15:00:00Z"));

        ArgumentCaptor<Instant> from2 = ArgumentCaptor.forClass(Instant.class);
        verify(after).countByRequestedAtGreaterThanEqualAndRequestedAtLessThan(from2.capture(), any());
        assertThat(from2.getValue()).isEqualTo(Instant.parse("2026-10-02T15:00:00Z"));
    }

    @Test
    @DisplayName("한도 설정값이 바뀌면 그 값을 따른다 (quota=3, 사용 2 허용 / 3 거부)")
    void reserve_followsConfiguredQuota() {
        service(repoWithUsed(2), "claude", 3, NOW).reserve(1L, 1L);
        assertThatThrownBy(() -> service(repoWithUsed(3), "claude", 3, NOW).reserve(1L, 1L))
                .isInstanceOf(BusinessException.class);
    }
}
