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
 * @param enabled              시뮬레이터 센서 데이터 생성 on/off(환경변수 SIMULATOR_ENABLED, 기본 true). false면 수집 틱마다 빈 목록을 반환해
 *                             센서 데이터가 생성되지 않는다 — 실센서 어댑터(SensorDataSource 다른 구현체)로 교체하기 전의 운영 스위치.
 *                             점검·알람·설비관리 등 센서 무관 기능은 영향 없다.
 */
@ConfigurationProperties(prefix = "fabwatch.simulator")
public record SimulatorProperties(Integer demoDurationMin, Integer plateauHoldMinutes, Integer maxLifetimeMinutes,
                                  Boolean enabled) {

    public static final int DEFAULT_DEMO_DURATION_MIN = 2;
    public static final int DEFAULT_PLATEAU_HOLD_MINUTES = 5;
    public static final int DEFAULT_MAX_LIFETIME_MINUTES = 30;

    @ConstructorBinding
    public SimulatorProperties {
        if (enabled == null) {
            enabled = Boolean.TRUE;
        }
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

    /** 시뮬레이터 켜짐 여부(기존 3인자 생성자 호환 — enabled는 기본 true) */
    public SimulatorProperties(Integer demoDurationMin, Integer plateauHoldMinutes, Integer maxLifetimeMinutes) {
        this(demoDurationMin, plateauHoldMinutes, maxLifetimeMinutes, null);
    }

    /** 데모 지속 시간만 지정하는 편의 생성자 (나머지는 기본값) */
    public SimulatorProperties(Integer demoDurationMin) {
        this(demoDurationMin, null, null);
    }
}
