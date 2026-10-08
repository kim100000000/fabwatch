package com.fabwatch.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class SlidingWindowRateLimiterTest {

    /** 테스트가 시간을 직접 움직이는 시계 */
    static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-06T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    @DisplayName("한도까지 허용하고 초과는 거절 + Retry-After(초)를 계산한다")
    void 한도_초과_거절() {
        MutableClock clock = new MutableClock();
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(3, Duration.ofMinutes(1), clock);

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("ip-a").allowed()).isTrue();
            clock.advance(Duration.ofSeconds(1));
        }
        SlidingWindowRateLimiter.Decision denied = limiter.tryAcquire("ip-a");

        assertThat(denied.allowed()).isFalse();
        // 가장 오래된 요청이 3초 전 → 57초 뒤에 자리가 난다
        assertThat(denied.retryAfterSeconds()).isEqualTo(57);
    }

    @Test
    @DisplayName("키(IP)별로 독립 — 다른 IP는 영향을 받지 않는다")
    void 키별_독립() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(1, Duration.ofMinutes(1), new MutableClock());
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
        assertThat(limiter.tryAcquire("b").allowed()).isTrue();
    }

    @Test
    @DisplayName("슬라이딩 — 윈도우가 지나면 다시 허용되고, 경계 직전엔 계속 거절")
    void 윈도우_경과_후_재허용() {
        MutableClock clock = new MutableClock();
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(2, Duration.ofMinutes(1), clock);
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        clock.advance(Duration.ofSeconds(30));
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();

        clock.advance(Duration.ofSeconds(29)); // 첫 요청 후 59초
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();

        clock.advance(Duration.ofSeconds(1));  // 첫 요청 후 60초 — 첫 요청 만료, 자리 1개
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
    }

    @Test
    @DisplayName("오래된 버킷은 정리된다 (메모리 누수 방지)")
    void 오래된_버킷_정리() {
        MutableClock clock = new MutableClock();
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(5, Duration.ofMinutes(1), clock);
        for (int i = 0; i < 1000; i++) {
            limiter.tryAcquire("ip-" + i);
        }
        assertThat(limiter.bucketCount()).isEqualTo(1000);

        clock.advance(Duration.ofMinutes(3));
        limiter.tryAcquire("fresh"); // 정리 주기(1분)를 넘긴 호출이 정리를 일으킨다

        assertThat(limiter.bucketCount()).isEqualTo(1); // fresh만 남는다
    }
}
