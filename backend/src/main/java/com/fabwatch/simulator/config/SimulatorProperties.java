package com.fabwatch.simulator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 시뮬레이터 설정 (fabwatch.simulator.*).
 *
 * @param demoDurationMin 데모 자동 시작(FR-4.5, POST /simulator/demo)의 DRIFT 기본 지속 시간(분).
 *                        3분 시연 안에 정상 → 드리프트 → WARNING → CRITICAL → 자동 DOWN이 끝나도록 기본 2분.
 */
@ConfigurationProperties(prefix = "fabwatch.simulator")
public record SimulatorProperties(Integer demoDurationMin) {

    public static final int DEFAULT_DEMO_DURATION_MIN = 2;

    public SimulatorProperties {
        if (demoDurationMin == null || demoDurationMin <= 0) {
            demoDurationMin = DEFAULT_DEMO_DURATION_MIN;
        }
    }
}
