package com.fabwatch.common.security;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 키(IP+엔드포인트)별 슬라이딩 윈도우 레이트 리미터 (인메모리).
 * 윈도우 안의 요청 시각을 최대 limit개만 보관하므로 키당 메모리가 상한이고, 오래된 키는 주기적으로 정리한다.
 *
 * // SCALE: 단일 인스턴스 전제. 스케일아웃하면 인스턴스마다 한도가 따로 적용되므로 공유 저장소(Redis 등)나
 *   프록시 레벨 리밋으로 옮겨야 한다.
 */
public class SlidingWindowRateLimiter {

    /** 허용 결과. 거절이면 retryAfterSeconds(최소 1)를 Retry-After 헤더에 쓴다. */
    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    private static final Duration CLEANUP_INTERVAL = Duration.ofMinutes(1);

    private final int limit;
    private final Duration window;
    private final Clock clock;
    private final ConcurrentHashMap<String, Deque<Long>> buckets = new ConcurrentHashMap<>();
    private final AtomicLong lastCleanupMillis;

    public SlidingWindowRateLimiter(int limit, Duration window, Clock clock) {
        this.limit = limit;
        this.window = window;
        this.clock = clock;
        this.lastCleanupMillis = new AtomicLong(clock.millis());
    }

    public Decision tryAcquire(String key) {
        long now = clock.millis();
        cleanupIfDue(now);

        Deque<Long> bucket = buckets.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (bucket) {
            long windowStart = now - window.toMillis();
            while (!bucket.isEmpty() && bucket.peekFirst() <= windowStart) {
                bucket.pollFirst();
            }
            if (bucket.size() >= limit) {
                long retryMillis = bucket.peekFirst() + window.toMillis() - now;
                return new Decision(false, Math.max(1, (retryMillis + 999) / 1000));
            }
            bucket.addLast(now);
            return new Decision(true, 0);
        }
    }

    /** 윈도우가 지나 비어 있어야 할 버킷을 제거한다 (메모리 누수 방지). 1분에 한 번만 훑는다. */
    private void cleanupIfDue(long now) {
        long last = lastCleanupMillis.get();
        if (now - last < CLEANUP_INTERVAL.toMillis() || !lastCleanupMillis.compareAndSet(last, now)) {
            return;
        }
        long windowStart = now - window.toMillis();
        buckets.entrySet().removeIf(entry -> {
            Deque<Long> bucket = entry.getValue();
            synchronized (bucket) {
                return bucket.isEmpty() || bucket.peekLast() <= windowStart;
            }
        });
    }

    /** 테스트용 — 현재 보관 중인 키 수 */
    int bucketCount() {
        return buckets.size();
    }
}
