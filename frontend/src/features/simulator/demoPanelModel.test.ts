import { describe, expect, it } from 'vitest'
import {
  DEFAULT_DRIFT_DURATION_MIN,
  PANEL_MARGIN,
  buildScenarioParam,
  clampPosition,
  isEquipmentSelectable,
  keyDelta,
  parseStoredPosition,
  scenarioHint,
} from './demoPanelModel'

const viewport = { w: 1400, h: 900 }
const panel = { w: 280, h: 300 }

describe('clampPosition', () => {
  it('뷰포트 안이면 그대로 둔다', () => {
    expect(clampPosition({ x: 100, y: 120 }, panel, viewport)).toEqual({ x: 100, y: 120 })
  })

  it('왼쪽·위로 나가면 여백까지 당긴다', () => {
    expect(clampPosition({ x: -50, y: -10 }, panel, viewport)).toEqual({ x: PANEL_MARGIN, y: PANEL_MARGIN })
  })

  it('오른쪽·아래로 나가면 패널이 다 보이는 위치로 당긴다', () => {
    expect(clampPosition({ x: 2000, y: 2000 }, panel, viewport)).toEqual({
      x: 1400 - 280 - PANEL_MARGIN,
      y: 900 - 300 - PANEL_MARGIN,
    })
  })

  it('뷰포트가 패널보다 작으면(390px 등) 좌상단이 보이게 한다', () => {
    expect(clampPosition({ x: 300, y: 300 }, { w: 380, h: 1000 }, { w: 390, h: 800 })).toEqual({
      x: PANEL_MARGIN,
      y: PANEL_MARGIN,
    })
  })

  it('윈도 리사이즈 후 재clamp: 큰 화면 위치가 작은 화면에서 안으로 들어온다', () => {
    const saved = { x: 1100, y: 600 }
    expect(clampPosition(saved, panel, { w: 800, h: 700 })).toEqual({ x: 800 - 280 - PANEL_MARGIN, y: 700 - 300 - PANEL_MARGIN })
  })
})

describe('parseStoredPosition', () => {
  it('정상 값을 읽는다', () => {
    expect(parseStoredPosition('{"x":10,"y":20}')).toEqual({ x: 10, y: 20 })
  })

  it('없거나 깨졌거나 숫자가 아니면 null', () => {
    expect(parseStoredPosition(null)).toBeNull()
    expect(parseStoredPosition('')).toBeNull()
    expect(parseStoredPosition('not json')).toBeNull()
    expect(parseStoredPosition('{"x":"1","y":2}')).toBeNull()
    expect(parseStoredPosition('{"x":1}')).toBeNull()
    expect(parseStoredPosition('null')).toBeNull()
    expect(parseStoredPosition('[1,2]')).toBeNull()
  })
})

describe('keyDelta', () => {
  it('방향키는 기본 16px, Shift 는 64px 이동', () => {
    expect(keyDelta('ArrowLeft', false)).toEqual({ x: -16, y: 0 })
    expect(keyDelta('ArrowDown', false)).toEqual({ x: 0, y: 16 })
    expect(keyDelta('ArrowRight', true)).toEqual({ x: 64, y: 0 })
    expect(keyDelta('ArrowUp', true)).toEqual({ x: 0, y: -64 })
  })

  it('방향키가 아니면 null', () => {
    expect(keyDelta('Enter', false)).toBeNull()
    expect(keyDelta('a', false)).toBeNull()
  })
})

describe('수동 주입 파라미터', () => {
  it('DRIFT 는 선택한 지속시간을 durationMin 으로 보낸다', () => {
    expect(buildScenarioParam('DRIFT', 5)).toEqual({ durationMin: 5 })
    expect(DEFAULT_DRIFT_DURATION_MIN).toBe(2)
  })

  it('SPIKE/STEP 은 지속시간을 무시하고 기본 param 을 쓴다', () => {
    expect(buildScenarioParam('SPIKE', 10)).toEqual({ probability: 0.1, multiplier: 1.8 })
    expect(buildScenarioParam('STEP', 10)).toEqual({ offsetRatio: 0.15 })
  })

  it('DRIFT 안내에는 선택한 지속시간과 데모 자동 시작(2분)이 들어간다', () => {
    const hint = scenarioHint('DRIFT', 10)
    expect(hint).toContain('10분')
    expect(hint).toContain('2분')
  })
})

describe('isEquipmentSelectable', () => {
  it('센서 목록을 받았는데 비어 있으면 선택할 수 없다', () => {
    expect(isEquipmentSelectable([])).toBe(false)
    expect(isEquipmentSelectable([{ id: 1 }])).toBe(true)
  })

  it('아직 못 받았으면(undefined) 막지 않는다', () => {
    expect(isEquipmentSelectable(undefined)).toBe(true)
  })
})
