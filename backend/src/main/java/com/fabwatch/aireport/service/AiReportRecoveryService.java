package com.fabwatch.aireport.service;

import com.fabwatch.aireport.config.AiProperties;
import com.fabwatch.aireport.entity.AiReport;
import com.fabwatch.aireport.repository.AiCallLogRepository;
import com.fabwatch.aireport.repository.AiReportRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * GENERATING에 갇힌 리포트 복구 — 응답 대기 중 앱이 재시작되면 워커 스레드가 사라져 영원히 GENERATING으로 남는다.
 * N분(기본 5분) 넘게 GENERATING인 건을 FAILED(사유: 서버 재시작/시간 초과)로 정리한다.
 * - 앱 시작 시 1회 + 1분 주기 점검(스케줄러가 켜진 환경).
 * - 기준 시각은 리포트 updated_at(=GENERATING 진입 시각)이다. 정상 생성 1건은 읽기 타임아웃 60초 x 2회 + 대기로 최대 약 2분이라
 *   5분은 '실행 중' 기준으로는 안전하지만, 워커 대기열에서 기다린 시간도 포함된다 — 대기열(10)+워커(2)가 모두
 *   최악 소요(약 2분)로 도는 경우 마지막 대기 건은 5분을 넘겨 FAILED로 마감될 수 있다(워커는 상태 확인 후 건너뜀, 비용 낭비 없음).
 *   일반 소요(20~40초)에서는 대기 상한이 약 2~3분이라 문제가 없고, 기동 시 {@link AiReportDispatcher}가 최악 대기 > stuckMinutes면 로그로 남긴다 (QA L-2).
 *
 * // SCALE: 단일 인스턴스 전제 — 앱 시작 시 복구가 '다른 인스턴스가 실행 중인 GENERATING 건'까지 FAILED로 만든다.
 *   스케일아웃 시에는 인스턴스 식별/하트비트 컬럼 또는 분산락이 필요하다.
 */
@Slf4j
@Service
public class AiReportRecoveryService {

    public static final String RECOVERY_REASON = "서버 재시작 또는 시간 초과로 생성이 중단되었습니다. 다시 시도하거나 수동으로 작성하세요";

    private final AiReportRepository reportRepository;
    private final AiCallLogRepository callLogRepository;
    private final AiProperties props;
    private final Clock aiClock;
    private final TransactionTemplate tx;

    public AiReportRecoveryService(AiReportRepository reportRepository, AiCallLogRepository callLogRepository,
                                   AiProperties props, Clock aiClock, PlatformTransactionManager txManager) {
        this.reportRepository = reportRepository;
        this.callLogRepository = callLogRepository;
        this.props = props;
        this.aiClock = aiClock;
        this.tx = new TransactionTemplate(txManager);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        int recovered = recoverStuck();
        if (recovered > 0) {
            log.warn("시작 시 GENERATING 정체 리포트 {}건을 FAILED로 복구했습니다", recovered);
        }
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    public void sweep() {
        recoverStuck();
    }

    /** @return 복구(FAILED 처리)한 건수 */
    public int recoverStuck() {
        Instant cutoff = aiClock.instant().minus(Duration.ofMinutes(props.stuckMinutes()));
        Integer count = tx.execute(status -> {
            int n = 0;
            for (AiReport candidate : reportRepository.findByStatusAndUpdatedAtBefore(AiReport.Status.GENERATING, cutoff)) {
                // 늦은 워커 저장과 경합하지 않도록 행 잠금 후 상태를 다시 확인한다 (M-3)
                AiReport report = reportRepository.findByIdForUpdate(candidate.getId()).orElse(null);
                if (report == null || report.getStatus() != AiReport.Status.GENERATING) {
                    continue;
                }
                report.fail(RECOVERY_REASON, null);
                callLogRepository.findByReportIdAndSuccessIsNull(report.getId())
                        .forEach(l -> l.fail(RECOVERY_REASON));
                log.warn("GENERATING 정체 리포트 복구: reportId={}", report.getId());
                n++;
            }
            return n;
        });
        return count == null ? 0 : count;
    }
}
