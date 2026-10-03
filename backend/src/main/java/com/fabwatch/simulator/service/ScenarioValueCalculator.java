package com.fabwatch.simulator.service;

/**
 * 시나리오 값 변형 공식 (docs/03 F-4.2) — 순수 함수만 모아 단위 테스트 대상으로 격리한다.
 *
 * <pre>
 * DRIFT: value += slope × min(경과초, 상한초)   (durationMin에 걸쳐 crit 도달하는 기울기, 도달 후 목표값에서 고정)
 * SPIKE: 지정 확률로 value × 1.5~2.0        (기본 확률 10%, 배수 1.8)
 * STEP : 즉시 value += offset 고정          (부품 교체 후 기준선 틀어짐 재현)
 * </pre>
 *
 * 난수(SPIKE 확률 판정)는 인자로 받는다 — 테스트에서 결정적으로 검증하기 위함.
 */
public final class ScenarioValueCalculator {

    private ScenarioValueCalculator() {
    }

    /**
     * DRIFT: 시나리오 시작 이후 경과 시간에 비례해 값이 흐르되, 상한 시간(maxElapsedSeconds = durationMin×60)에
     * 도달하면 그 시점의 오프셋(= 목표값 − base)에서 고정한다(plateau). 시나리오를 안 꺼도 값이 무한 상승하지 않는다.
     * 경과초가 0 이하면 변형 없음. maxElapsedSeconds가 0 이하면 상한 없음(상한 정보가 없는 과거 시나리오 호환).
     * 노이즈는 value에 이미 섞여 있으므로 plateau 후에도 목표값 주변에서 흔들린다.
     */
    public static double applyDrift(double value, double slopePerSec, long elapsedSeconds, long maxElapsedSeconds) {
        if (elapsedSeconds <= 0) {
            return value;
        }
        long effective = maxElapsedSeconds > 0 ? Math.min(elapsedSeconds, maxElapsedSeconds) : elapsedSeconds;
        return value + slopePerSec * effective;
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
