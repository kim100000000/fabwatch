package com.fabwatch.config;

import com.fabwatch.sensor.config.SensorQueryProperties;
import com.fabwatch.sensor.config.SensorRetentionProperties;
import com.fabwatch.sensor.config.SseProperties;
import com.fabwatch.simulator.config.SimulatorProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** 안정성 감사 2차에서 추가한 설정 키가 yml/환경변수로 실제 바인딩되는지 (record 설정 클래스 + 다중 생성자 포함) */
@SpringBootTest(properties = {
        "fabwatch.sse.queue-capacity=7",
        "fabwatch.sse.send-timeout-ms=1234",
        "fabwatch.sse.sender-threads=3",
        "fabwatch.sensor.query.max-range-days=9",
        "fabwatch.sensor.query.max-points-per-sensor=555",
        "fabwatch.sensor.retention.chunk-size=77",
        "fabwatch.simulator.plateau-hold-minutes=11",
        "fabwatch.simulator.max-lifetime-minutes=22",
        "fabwatch.simulator.demo-duration-min=4"})
@ActiveProfiles({"local", "test"})
class NewOperationalPropertiesBindingTest {

    @Autowired
    private SseProperties sse;
    @Autowired
    private SensorQueryProperties query;
    @Autowired
    private SensorRetentionProperties retention;
    @Autowired
    private SimulatorProperties simulator;

    @Test
    @DisplayName("SSE·센서 조회·보존 배치·시뮬레이터 신규 설정 키가 바인딩된다 (테스트 프로파일은 SSE 동기 전송)")
    void 바인딩() {
        assertThat(sse.async()).as("application-test.yml: 동기 전송").isFalse();
        assertThat(sse.queueCapacity()).isEqualTo(7);
        assertThat(sse.sendTimeoutMs()).isEqualTo(1234L);
        assertThat(sse.senderThreads()).isEqualTo(3);
        assertThat(query.maxRangeDays()).isEqualTo(9);
        assertThat(query.maxPointsPerSensor()).isEqualTo(555);
        assertThat(retention.chunkSize()).isEqualTo(77);
        assertThat(retention.chunkPauseMs()).as("application-test.yml: 대기 없음").isZero();
        assertThat(simulator.plateauHoldMinutes()).isEqualTo(11);
        assertThat(simulator.maxLifetimeMinutes()).isEqualTo(22);
        assertThat(simulator.demoDurationMin()).isEqualTo(4);
    }
}
