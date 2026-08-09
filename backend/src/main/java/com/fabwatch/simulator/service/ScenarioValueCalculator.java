package com.fabwatch.simulator.service;

/**
 * 시나리오 값 변형 공식 (docs/03 F-4.2) — 순수 함수만 모아 단위 테스트 대상으로 격리한다.
 *
 * <pre>
 * DRIFT: value += slope × 경과초            (10분에 걸쳐 crit 도달하는 기울기가 기본)
 * SPIKE: 지정 확률로 value × 1.5~2.0        (기본 확률 10%, 배수 1.8)
 * STEP : 즉시 value += offset 고정          (부품 교체 후 기준선 틀어짐 재현)
 * </pre>
 *
 * 난수(SPIKE 확률 판정)는 인자로 받는다 — 테스트에서 결정적으로 검증하기 위함.
 */
public final class ScenarioValueCalculator {

    private ScenarioValueCalculator() {
    }

    /** DRIFT: 시나리오 시작 이후 경과 시간에 비례해 값이 흐른다. 경과초가 음수면 변형 없음. */
    public static double applyDrift(double value, double slopePerSec, long elapsedSeconds) {
        if (elapsedSeconds <= 0) {
            return value;
        }
        return value + slopePerSec * elapsedSeconds;
    }

    /** SPIKE: roll(0.0~1.0 난수)이 확률 미만일 때만 배수를 곱한다. */
    public static double applySpike(double value, double probability, double multiplier, double roll) {
        return roll < probability ? value * multiplier : value;
    }

    /** STEP: 상수 오프셋을 더한다(기준선 이동). */
    public static double applyStep(double value, double offset) {
        return value + offset;
    }
}
