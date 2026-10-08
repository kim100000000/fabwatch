package com.fabwatch.simulator.service;

import com.fabwatch.simulator.config.SimulatorProperties;
import com.fabwatch.simulator.entity.SimulationScenario;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 시나리오 자동 만료 시각 계산 (docs/03 F-4.2, 안정성 감사 M-13).
 *
 * 시나리오를 사용자가 해제하지 않아도 일정 시간 뒤 스스로 꺼져야 한다. 안 그러면 사용자가 알람을 해제하고 DOWN→IDLE로
 * 복구해도 DRIFT plateau 값이 계속 CRITICAL을 만들어 알람·자동 DOWN이 2초 안에 재발하고, 서버를 재시작해도
 * active=true가 DB에 남아 그대로 복원된다.
 *
 * - DRIFT : 시작 + 상한 시간(maxElapsedSec, 없는 과거 시나리오는 durationMin×60) + plateau-hold-minutes
 *           (상한 정보가 아예 없으면 max-lifetime-minutes)
 * - STEP/SPIKE : 시작 + max-lifetime-minutes
 *
 * 순수 계산이라 시각을 받아 단위 테스트한다. 만료 판정은 "경과 시간 ≥ 수명"(정확히 만료 시각이면 만료).
 */
public final class ScenarioExpiry {

    private ScenarioExpiry() {
    }

    /**
     * @return 만료 시각. 시작 시각을 알 수 없으면(null) 만료 없음(null).
     */
    public static Instant expiresAt(SimulationScenario scenario, Map<String, Object> param, SimulatorProperties props) {
        Instant started = scenario.getStartedAt() != null ? scenario.getStartedAt() : scenario.getCreatedAt();
        if (started == null) {
            return null;
        }
        Duration lifetime = switch (scenario.getType()) {
            case DRIFT -> {
                long maxElapsedSec = ScenarioParams.driftMaxElapsedSeconds(param);
                yield maxElapsedSec > 0
                        ? Duration.ofSeconds(maxElapsedSec).plusMinutes(props.plateauHoldMinutes())
                        : Duration.ofMinutes(props.maxLifetimeMinutes());
            }
            case STEP, SPIKE -> Duration.ofMinutes(props.maxLifetimeMinutes());
        };
        return started.plus(lifetime);
    }

    public static boolean isExpired(Instant expiresAt, Instant at) {
        return expiresAt != null && !at.isBefore(expiresAt);
    }
}
