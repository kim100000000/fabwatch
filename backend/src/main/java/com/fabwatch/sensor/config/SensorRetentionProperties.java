package com.fabwatch.sensor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 센서 원본 보존 배치 설정 (fabwatch.sensor.retention.*, docs/15 §7).
 *
 * @param chunkSize    한 번(트랜잭션)에 삭제할 최대 행 수. 기본 10,000. 작을수록 락이 짧고 총 소요가 길다.
 * @param chunkPauseMs 청크 사이 대기(ms). 기본 100. 수집 INSERT에 락·IO 여유를 준다. 0이면 대기 없음.
 */
@ConfigurationProperties(prefix = "fabwatch.sensor.retention")
public record SensorRetentionProperties(Integer chunkSize, Long chunkPauseMs) {

    public static final int DEFAULT_CHUNK_SIZE = 10_000;
    public static final long DEFAULT_CHUNK_PAUSE_MS = 100L;
    /** 한 번에 지우는 양의 상한 — 실수로 거대 값을 넣어 단일 트랜잭션이 되돌아가는 것을 막는다 */
    public static final int MAX_CHUNK_SIZE = 100_000;

    public SensorRetentionProperties {
        if (chunkSize == null || chunkSize <= 0) {
            chunkSize = DEFAULT_CHUNK_SIZE;
        }
        chunkSize = Math.min(chunkSize, MAX_CHUNK_SIZE);
        if (chunkPauseMs == null || chunkPauseMs < 0) {
            chunkPauseMs = DEFAULT_CHUNK_PAUSE_MS;
        }
    }
}
