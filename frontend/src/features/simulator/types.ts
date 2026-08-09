/**
 * 시뮬레이터(데모 제어) 타입 — docs/06 §8 + docs/03 F-4.2.
 * 시연 중 드리프트/스파이크/스텝을 주입해 알람 흐름을 보여주기 위한 화면 전용 도메인이다.
 * 실설비 제어가 아니라 가상 센서값 생성 로직만 바꾼다 (docs/11 §10).
 */

/** 주입 가능한 시나리오 종류 */
export type ScenarioType = 'DRIFT' | 'SPIKE' | 'STEP'

/** POST /simulator/scenarios 의 param — 종류별로 쓰는 키가 다르다 (docs/06 §8 기본값) */
export interface ScenarioParam {
  /** DRIFT: 몇 분에 걸쳐 crit 에 도달할지 (기본 10) */
  durationMin?: number
  /** SPIKE: 발생 확률 (기본 0.1) */
  probability?: number
  /** SPIKE: 배율 (기본 1.8) */
  multiplier?: number
  /** STEP: 기준선 이동 비율 (기본 0.15) */
  offsetRatio?: number
}

/** GET /simulator/scenarios — 활성 시나리오 */
export interface Scenario {
  id: number
  sensorId: number
  equipmentId?: number
  equipmentCode?: string | null
  sensorType?: string | null
  type: ScenarioType
  param?: ScenarioParam | null
  startedAt?: string | null
}

/** POST /simulator/scenarios 요청 */
export interface ScenarioCreateRequest {
  sensorId: number
  type: ScenarioType
  param?: ScenarioParam
}

export const SCENARIO_TYPES: ScenarioType[] = ['DRIFT', 'SPIKE', 'STEP']

/** 종류별 기본 param (docs/06 §8) — 화면에서 그대로 채워 보낸다 */
export const DEFAULT_SCENARIO_PARAM: Record<ScenarioType, ScenarioParam> = {
  DRIFT: { durationMin: 10 },
  SPIKE: { probability: 0.1, multiplier: 1.8 },
  STEP: { offsetRatio: 0.15 },
}

export const SCENARIO_TYPE_HINT: Record<ScenarioType, string> = {
  DRIFT: '기울기 상승 — 지정 시간에 걸쳐 crit 도달',
  SPIKE: '확률적 급등 — 순간 이상치',
  STEP: '기준선 이동 — 부품 교체 후 틀어짐 재현',
}
