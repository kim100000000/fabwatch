package com.fabwatch.simulator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * 시뮬레이터 설정 (fabwatch.simulator.*).
 *
 * @param demoDurationMin      데모 자동 시작(FR-4.5, POST /simulator/demo)의 DRIFT 기본 지속 시간(분).
 *                             3분 시연 안에 정상 → 드리프트 → WARNING → CRITICAL → 자동 DOWN이 끝나도록 기본 2분.
 * @param plateauHoldMinutes   DRIFT가 목표값(plateau)에 도달한 뒤 유지하는 시간(분, 기본 5). 지나면 시나리오를 자동 비활성화한다 —
 *                             사용자가 알람을 해제하고 설비를 복구한 뒤에도 시나리오가 살아 있어 알람·DOWN이 재발하는 것을 막는다.
 * @param maxLifetimeMinutes   STEP/SPIKE(plateau 개념이 없는 유형)의 최대 수명(분, 기본 30). 주입 후 이 시간이 지나면 자동 비활성화.
 */
@ConfigurationProperties(prefix = "fabwatch.simulator")
public record SimulatorProperties(Integer demoDurationMin, Integer plateauHoldMinutes, Integer maxLifetimeMinutes) {

    public static final int DEFAULT_DEMO_DURATION_MIN = 2;
    public static final int DEFAULT_PLATEAU_HOLD_MINUTES = 5;
    public static final int DEFAULT_MAX_LIFETIME_MINUTES = 30;

    @ConstructorBinding
    public SimulatorProperties {
        if (demoDurationMin == null || demoDurationMin <= 0) {
            demoDurationMin = DEFAULT_DEMO_DURATION_MIN;
        }
        if (plateauHoldMinutes == null || plateauHoldMinutes <= 0) {
            plateauHoldMinutes = DEFAULT_PLATEAU_HOLD_MINUTES;
        }
        if (maxLifetimeMinutes == null || maxLifetimeMinutes <= 0) {
            maxLifetimeMinutes = DEFAULT_MAX_LIFETIME_MINUTES;
        }
    }

    /** 데모 지속 시간만 지정하는 편의 생성자 (나머지는 기본값) */
    public SimulatorProperties(Integer demoDurationMin) {
        this(demoDurationMin, null, null);
    }
}
