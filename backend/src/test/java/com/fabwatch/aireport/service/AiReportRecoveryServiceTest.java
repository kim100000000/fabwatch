package com.fabwatch.aireport.service;

import com.fabwatch.aireport.config.AiProperties;
import com.fabwatch.aireport.entity.AiCallLog;
import com.fabwatch.aireport.entity.AiReport;
import com.fabwatch.aireport.repository.AiCallLogRepository;
import com.fabwatch.aireport.repository.AiReportRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 서버 재시작/시간 초과로 GENERATING에 갇힌 건 복구 (계약 '비동기' 요구) */
class AiReportRecoveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-02T03:00:00Z");

    private final AiReportRepository reportRepository = mock(AiReportRepository.class);
    private final AiCallLogRepository callLogRepository = mock(AiCallLogRepository.class);

    private AiReportRecoveryService service(int stuckMinutes) {
        AiProperties props = new AiProperties("mock", "", "m", 4096, 20, 60, "u", "v", 0, 2, 10, stuckMinutes, "low");
        return new AiReportRecoveryService(reportRepository, callLogRepository, props,
                Clock.fixed(NOW, ZoneOffset.UTC), mock(PlatformTransactionManager.class));
    }

    @Test
    @DisplayName("N분(5분) 넘은 GENERATING → FAILED(사유: 서버 재시작/시간 초과), 진행 중 호출 로그도 실패로 닫는다")
    void recoversStuckReports() {
        AiReport stuck = AiReport.generating(1L, 10L, null, "t", 5L);
        AiCallLog pending = new AiCallLog(1L, 5L, NOW.minusSeconds(600), "claude", "m");
        org.springframework.test.util.ReflectionTestUtils.setField(stuck, "id", 1L);
        when(reportRepository.findByStatusAndUpdatedAtBefore(any(), any())).thenReturn(List.of(stuck));
        when(reportRepository.findByIdForUpdate(1L)).thenReturn(java.util.Optional.of(stuck));
        when(callLogRepository.findByReportIdAndSuccessIsNull(any())).thenReturn(List.of(pending));

        int recovered = service(5).recoverStuck();

        assertThat(recovered).isEqualTo(1);
        assertThat(stuck.getStatus()).isEqualTo(AiReport.Status.FAILED);
        assertThat(stuck.getFailReason()).contains("서버 재시작").contains("시간 초과");
        assertThat(pending.getSuccess()).isFalse();
        assertThat(pending.getErrorSummary()).contains("서버 재시작");

        // 기준 시각 = now - 5분
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(reportRepository).findByStatusAndUpdatedAtBefore(
                org.mockito.ArgumentMatchers.eq(AiReport.Status.GENERATING), cutoff.capture());
        assertThat(cutoff.getValue()).isEqualTo(NOW.minusSeconds(300));
    }

    @Test
    @DisplayName("기준 이내(방금 시작한) 건은 조회되지 않으므로 건드리지 않는다 — 대상 없으면 0건")
    void nothingToRecover() {
        when(reportRepository.findByStatusAndUpdatedAtBefore(any(), any())).thenReturn(List.of());

        assertThat(service(5).recoverStuck()).isZero();
        verify(callLogRepository, never()).findByReportIdAndSuccessIsNull(any());
    }

    @Test
    @DisplayName("복구 기준 분은 설정값을 따른다")
    void cutoffFollowsConfig() {
        when(reportRepository.findByStatusAndUpdatedAtBefore(any(), any())).thenReturn(List.of());

        service(2).recoverStuck();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(reportRepository).findByStatusAndUpdatedAtBefore(any(), cutoff.capture());
        assertThat(cutoff.getValue()).isEqualTo(NOW.minusSeconds(120));
    }

    @Test
    @DisplayName("M-3: 후보 조회 후 잠금을 얻었을 때 이미 GENERATING이 아니면(늦은 워커가 먼저 저장) 건드리지 않는다")
    void skipsReportsThatChangedBeforeLock() {
        AiReport finishedMeanwhile = AiReport.generating(1L, 10L, null, "t", 5L);
        org.springframework.test.util.ReflectionTestUtils.setField(finishedMeanwhile, "id", 2L);
        finishedMeanwhile.completeDraft("원본", "m", 1, 1); // 후보 조회 후 워커가 DRAFT로 저장한 상황
        when(reportRepository.findByStatusAndUpdatedAtBefore(any(), any())).thenReturn(List.of(finishedMeanwhile));
        when(reportRepository.findByIdForUpdate(2L)).thenReturn(java.util.Optional.of(finishedMeanwhile));

        assertThat(service(5).recoverStuck()).isZero();
        assertThat(finishedMeanwhile.getStatus()).isEqualTo(AiReport.Status.DRAFT);
        verify(callLogRepository, never()).findByReportIdAndSuccessIsNull(any());
    }
}
