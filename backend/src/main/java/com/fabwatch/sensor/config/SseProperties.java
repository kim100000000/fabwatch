package com.fabwatch.sensor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SSE 설정 (fabwatch.sse.*, docs/11 §4, 보안 감사 M-3, docs/15 §6).
 *
 * @param maxConnections 전체 동시 연결 상한(기본 200). 초과하면 새 연결을 429 RATE_LIMITED로 거절한다.
 * @param maxPerUser     사용자당 동시 연결 상한(기본 5). 초과하면 그 사용자의 가장 오래된 연결을 닫고 새 연결을 받는다
 *                       (탭을 여러 번 열거나 재연결이 겹쳐도 자기 연결이 알아서 정리되게 하려는 의도).
 * @param async          true(기본)면 이벤트를 구독자별 유한 큐에 넣고 전용 스레드풀이 전송한다 — 수집 스레드는 전송에 블로킹되지 않는다.
 *                       false면 호출 스레드에서 동기 전송한다(테스트의 결정성용, 운영에서는 쓰지 않는다).
 * @param queueCapacity  구독자별 대기 큐 크기(기본 100). 가득 차면 느린 구독자로 보고 연결을 끊는다.
 * @param sendTimeoutMs  한 번의 전송이 이 시간(ms, 기본 5000)을 넘게 막혀 있으면 느린 구독자로 보고 연결을 끊는다.
 * @param senderThreads  전송 전용 스레드 수(기본 8). 구독자 하나의 전송은 항상 한 스레드에서 직렬로 일어난다.
 */
@ConfigurationProperties(prefix = "fabwatch.sse")
public record SseProperties(Integer maxConnections, Integer maxPerUser, Boolean async,
                            Integer queueCapacity, Long sendTimeoutMs, Integer senderThreads) {

    public static final int DEFAULT_MAX_CONNECTIONS = 200;
    public static final int DEFAULT_MAX_PER_USER = 5;
    public static final int DEFAULT_QUEUE_CAPACITY = 100;
    public static final long DEFAULT_SEND_TIMEOUT_MS = 5_000L;
    public static final int DEFAULT_SENDER_THREADS = 8;

    public SseProperties {
        if (maxConnections == null || maxConnections <= 0) {
            maxConnections = DEFAULT_MAX_CONNECTIONS;
        }
        if (maxPerUser == null || maxPerUser <= 0) {
            maxPerUser = DEFAULT_MAX_PER_USER;
        }
        if (async == null) {
            async = Boolean.TRUE;
        }
        if (queueCapacity == null || queueCapacity <= 0) {
            queueCapacity = DEFAULT_QUEUE_CAPACITY;
        }
        if (sendTimeoutMs == null || sendTimeoutMs <= 0) {
            sendTimeoutMs = DEFAULT_SEND_TIMEOUT_MS;
        }
        if (senderThreads == null || senderThreads <= 0) {
            senderThreads = DEFAULT_SENDER_THREADS;
        }
    }
}
