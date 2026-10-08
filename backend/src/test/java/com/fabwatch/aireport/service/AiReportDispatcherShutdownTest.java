package com.fabwatch.aireport.service;

import com.fabwatch.aireport.config.AiProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** AI 디스패처 종료: shutdown 후 대기, 시간 초과 시 shutdownNow (L-13) */
class AiReportDispatcherShutdownTest {

    private AiReportDispatcher dispatcher(long waitSeconds) {
        AiProperties props = new AiProperties("mock", "", "m", 4096, 20, 60, "u", "v", 0, 1, 10, 5, "low");
        AiReportDispatcher dispatcher = new AiReportDispatcher(props);
        ReflectionTestUtils.setField(dispatcher, "shutdownWaitSeconds", waitSeconds);
        return dispatcher;
    }

    @Test
    @DisplayName("진행 중 작업이 대기 시간 안에 끝나면 강제 중단 없이 정상 종료된다")
    void 정상_종료_대기() throws Exception {
        AiReportDispatcher dispatcher = dispatcher(5);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        AtomicBoolean interrupted = new AtomicBoolean();
        dispatcher.submit(() -> {
            started.countDown();
            try {
                Thread.sleep(300);
                finished.set(true);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        dispatcher.shutdown();

        assertThat(finished.get()).as("진행 중이던 작업은 끝까지 수행").isTrue();
        assertThat(interrupted.get()).isFalse();
    }

    @Test
    @DisplayName("대기 시간을 넘기는 작업은 shutdownNow로 인터럽트되고 shutdown()은 제한 시간 안에 반환한다")
    void 시간_초과시_강제_종료() throws Exception {
        AiReportDispatcher dispatcher = dispatcher(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        dispatcher.submit(() -> {
            started.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        long begin = System.nanoTime();
        dispatcher.shutdown();
        long elapsedMs = (System.nanoTime() - begin) / 1_000_000;

        assertThat(interrupted.await(5, TimeUnit.SECONDS)).as("shutdownNow로 인터럽트").isTrue();
        assertThat(elapsedMs).isBetween(900L, 5_000L);
    }
}
