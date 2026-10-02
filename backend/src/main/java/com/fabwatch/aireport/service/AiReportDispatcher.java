package com.fabwatch.aireport.service;

import com.fabwatch.aireport.config.AiProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * AI 리포트 생성 전용 비동기 실행기 — 스레드/대기열을 제한해 외부 API 지연이 요청 스레드·다른 작업을 막지 않게 한다.
 *
 * Spring의 @Async 대신 전용 풀을 직접 소유하는 이유: Executor 타입 빈을 하나라도 등록하면
 * Boot가 기본 applicationTaskExecutor(MVC 비동기 등)를 만들지 않으므로, 풀을 빈으로 노출하지 않고 이 컴포넌트가 소유한다.
 * 비동기 스레드에는 SecurityContext가 없다 — 작성자 ID 등은 인자로 넘긴다.
 *
 * // SCALE: 단일 인스턴스 전제의 인메모리 큐/풀이다. 스케일아웃하면 인스턴스마다 풀이 따로 돌고 재시작 시 대기열이 유실되므로
 *   외부 큐(예: DB 작업 테이블, 메시지 브로커)로 옮겨야 한다. 유실 건은 {@link AiReportRecoveryService}가 FAILED로 정리한다.
 */
@Component
@Slf4j
public class AiReportDispatcher {

    private final Executor executor;
    private final ThreadPoolTaskExecutor owned;

    @Autowired
    public AiReportDispatcher(AiProperties props) {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(props.workerThreads());
        pool.setMaxPoolSize(props.workerThreads());
        pool.setQueueCapacity(props.queueCapacity());
        pool.setThreadNamePrefix("ai-report-");
        pool.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTerminationSeconds(10);
        pool.initialize();
        this.executor = pool;
        this.owned = pool;
        noteQueueWaitVsRecovery(props);
    }

    /**
     * 복구 기준(stuckMinutes)은 대기열 대기 시간을 포함한다. 큐 끝 건이 최악 소요(읽기 타임아웃 x 2회) 건들을 기다리는 시간이
     * stuckMinutes를 넘으면 정상 건도 FAILED로 마감될 수 있어 기동 시 기록한다 (QA L-2).
     */
    private static void noteQueueWaitVsRecovery(AiProperties props) {
        int workers = Math.max(1, props.workerThreads());
        long worstSecondsPerTask = 2L * props.timeoutSeconds();
        long waitingRounds = (props.queueCapacity() + workers - 1) / workers; // 마지막 대기 건 앞에 있는 완료 대기 라운드 수
        long worstWaitSeconds = waitingRounds * worstSecondsPerTask;
        if (worstWaitSeconds > props.stuckMinutes() * 60L) {
            log.info("AI 생성 대기열 최악 대기({}초)가 복구 기준(stuck-minutes={}분)보다 깁니다 — 정상 건이 FAILED로 마감될 수 있음",
                    worstWaitSeconds, props.stuckMinutes());
        }
    }

    /** 테스트용 — 동기/가짜 실행기 주입 */
    AiReportDispatcher(Executor executor) {
        this.executor = executor;
        this.owned = null;
    }

    /** 큐가 가득 차면 TaskRejectedException — 호출 측이 FAILED로 마무리한다. */
    public void submit(Runnable task) {
        try {
            executor.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            throw new TaskRejectedException("AI 리포트 생성 대기열이 가득 찼습니다", e);
        }
    }

    @PreDestroy
    void shutdown() {
        if (owned != null) {
            owned.shutdown();
        }
    }
}
