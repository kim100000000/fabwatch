/**
 * 데모 제어 패널의 순수 로직 — 위치(드래그·키보드 이동·clamp·저장 복원)와 수동 주입 파라미터.
 * 화면(DemoControlPanel)과 분리해 단위 테스트한다.
 */

import type { ScenarioParam, ScenarioType } from './types'
import { DEFAULT_SCENARIO_PARAM } from './types'

export interface Point {
  x: number
  y: number
}

export interface Size {
  w: number
  h: number
}

/** 뷰포트 가장자리에서 띄우는 최소 여백(px) */
export const PANEL_MARGIN = 8

/** 값을 [min, max] 로 제한 (max < min 이면 min 우선) */
function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(value, Math.max(min, max)))
}

/** 패널(좌상단 기준 위치)이 뷰포트 밖으로 나가지 않게 제한한다. 패널이 뷰포트보다 크면 좌상단을 보이게 한다. */
export function clampPosition(pos: Point, panel: Size, viewport: Size, margin: number = PANEL_MARGIN): Point {
  return {
    x: clamp(pos.x, margin, viewport.w - panel.w - margin),
    y: clamp(pos.y, margin, viewport.h - panel.h - margin),
  }
}

/** localStorage 에 저장한 문자열 → 위치. 깨졌거나 숫자가 아니면 null (기본 위치로 폴백) */
export function parseStoredPosition(raw: string | null): Point | null {
  if (!raw) return null
  try {
    const value: unknown = JSON.parse(raw)
    if (typeof value !== 'object' || value === null) return null
    const { x, y } = value as Record<string, unknown>
    if (typeof x !== 'number' || typeof y !== 'number' || !Number.isFinite(x) || !Number.isFinite(y)) return null
    return { x, y }
  } catch {
    return null
  }
}

/** 키보드 이동 간격(px) — Shift 를 같이 누르면 크게 */
export const KEY_STEP = 16
export const KEY_STEP_LARGE = 64

/** 방향키 → 이동량. 방향키가 아니면 null */
export function keyDelta(key: string, shift: boolean): Point | null {
  const step = shift ? KEY_STEP_LARGE : KEY_STEP
  switch (key) {
    case 'ArrowLeft':
      return { x: -step, y: 0 }
    case 'ArrowRight':
      return { x: step, y: 0 }
    case 'ArrowUp':
      return { x: 0, y: -step }
    case 'ArrowDown':
      return { x: 0, y: step }
    default:
      return null
  }
}

/* ------------------------------------------------------------------
 * 수동 주입 파라미터
 * ------------------------------------------------------------------ */

/** DRIFT 지속시간 선택지(분) — 첫 값이 기본. 2분은 '데모 자동 시작'과 같아 시연 때 바로 변화가 보인다 */
export const DRIFT_DURATION_OPTIONS = [2, 5, 10] as const
export const DEFAULT_DRIFT_DURATION_MIN = DRIFT_DURATION_OPTIONS[0]

/** 시나리오 종류 + DRIFT 지속시간 → 요청 param. DRIFT 가 아니면 지속시간은 무시한다. */
export function buildScenarioParam(type: ScenarioType, driftDurationMin: number): ScenarioParam {
  if (type === 'DRIFT') return { ...DEFAULT_SCENARIO_PARAM.DRIFT, durationMin: driftDurationMin }
  return { ...DEFAULT_SCENARIO_PARAM[type] }
}

/** 시나리오 종류별 안내 — DRIFT 는 선택한 지속시간을 문구에 반영해 '왜 변화가 안 보이는지' 혼란을 막는다 */
export function scenarioHint(type: ScenarioType, driftDurationMin: number): string {
  switch (type) {
    case 'DRIFT':
      return `${driftDurationMin}분에 걸쳐 상승해 위험(crit)에 도달, 경고는 그 절반쯤. 변화가 안 보이면 지속시간을 줄이세요. (데모 자동 시작은 2분)`
    case 'SPIKE':
      return '확률적으로 순간 급등 값이 섞입니다. 알람이 간헐적으로 발생합니다.'
    case 'STEP':
      return '기준선이 한 번에 이동합니다. 부품 교체 후 틀어짐 재현용입니다.'
  }
}

/** 설비 선택 가능 여부 — 센서 목록을 받았는데 비어 있으면 주입할 수 없다. 아직 못 받았으면(undefined) 막지 않는다. */
export function isEquipmentSelectable(sensors: readonly unknown[] | undefined): boolean {
  return sensors === undefined || sensors.length > 0
}
