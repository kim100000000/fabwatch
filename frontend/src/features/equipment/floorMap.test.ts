import { describe, expect, it } from 'vitest'
import {
  STALE_AFTER_MS,
  alarmRing,
  buildFloorLayout,
  describeUnit,
  detectEquipmentKind,
  isConveyorFlowing,
  isPartAbnormal,
  isSensorStale,
  processSeqFromLines,
  resolveParts,
  statusVisual,
  toFloorMapAlarm,
} from './floorMap'
import type { FloorMapSensor, LayoutEquipment } from './floorMap'

const NOW = Date.parse('2026-10-09T03:00:00Z')

function sensor(partial: Partial<FloorMapSensor> & Pick<FloorMapSensor, 'sensorType'>): FloorMapSensor {
  return { sensorId: 1, value: 1, measuredAt: '2026-10-09T03:00:00Z', ...partial }
}

describe('(a) 설비 종류 판별', () => {
  it('code 접두사로 4종을 판별한다', () => {
    expect(detectEquipmentKind('LAMI-01')).toBe('LAMI')
    expect(detectEquipmentKind('OVEN-02')).toBe('OVEN')
    expect(detectEquipmentKind('SCRB-01')).toBe('SCRB')
    expect(detectEquipmentKind('AOI-01')).toBe('AOI')
  })

  it('대소문자·공백·구분자 없는 숫자 접미를 허용한다', () => {
    expect(detectEquipmentKind(' lami-01 ')).toBe('LAMI')
    expect(detectEquipmentKind('aoi01')).toBe('AOI')
    expect(detectEquipmentKind('OVEN_3')).toBe('OVEN')
  })

  it('모르는 접두사·빈 값은 범용 박스로 폴백한다', () => {
    expect(detectEquipmentKind('XYZ-01')).toBe('GENERIC')
    expect(detectEquipmentKind('LAMINATOR-1')).toBe('GENERIC')
    expect(detectEquipmentKind('')).toBe('GENERIC')
    expect(detectEquipmentKind(null)).toBe('GENERIC')
    expect(detectEquipmentKind(undefined)).toBe('GENERIC')
  })
})

describe('(b) 상태 → 색/라벨 매핑', () => {
  it('4개 상태가 합의된 토큰과 라벨로 매핑된다', () => {
    expect(statusVisual('RUN')).toEqual({ label: 'RUN', colorVar: 'var(--status-run)' })
    expect(statusVisual('IDLE')).toEqual({ label: 'IDLE', colorVar: 'var(--status-idle)' })
    expect(statusVisual('PM')).toEqual({ label: 'PM', colorVar: 'var(--status-pm)' })
    // DOWN 은 화면 라벨만 'DOWN (BM)', 토큰은 --status-down
    expect(statusVisual('DOWN')).toEqual({ label: 'DOWN (BM)', colorVar: 'var(--status-down)' })
  })

  it('알 수 없는 상태는 중립 색으로 폴백한다', () => {
    expect(statusVisual('UNKNOWN' as never).colorVar).toBe('var(--neutral)')
  })

  it('컨베이어는 양쪽이 모두 RUN 일 때만 흐른다', () => {
    expect(isConveyorFlowing('RUN', 'RUN')).toBe(true)
    expect(isConveyorFlowing('RUN', 'IDLE')).toBe(false)
    expect(isConveyorFlowing('DOWN', 'RUN')).toBe(false)
    expect(isConveyorFlowing('PM', 'PM')).toBe(false)
  })
})

describe('(c) 알람 → 링 표현', () => {
  it('알람이 없거나 집계 전이면 링이 없다', () => {
    expect(alarmRing(null)).toBeNull()
    expect(alarmRing(undefined)).toBeNull()
    expect(alarmRing({ count: 0, openCount: 0, maxSeverity: 'WARNING' })).toBeNull()
  })

  it('WARNING 은 점선 얇은 링 (IDLE 노랑과 모양으로 구분)', () => {
    const ring = alarmRing({ count: 1, openCount: 1, maxSeverity: 'WARNING' })
    expect(ring).toMatchObject({
      colorVar: 'var(--alarm-warning)',
      dasharray: '5 4',
      doubleRing: false,
      label: '경고',
      blink: 'slow',
      unacked: true,
    })
  })

  it('MAJOR 는 실선 두 겹, CRITICAL 은 가장 굵은 실선 두 겹이며 선 두께가 점점 굵다', () => {
    const warning = alarmRing({ count: 1, openCount: 1, maxSeverity: 'WARNING' })!
    const major = alarmRing({ count: 1, openCount: 1, maxSeverity: 'MAJOR' })!
    const critical = alarmRing({ count: 1, openCount: 1, maxSeverity: 'CRITICAL' })!
    expect(major).toMatchObject({ colorVar: 'var(--alarm-major)', dasharray: null, doubleRing: true })
    expect(critical).toMatchObject({ colorVar: 'var(--alarm-critical)', dasharray: null, doubleRing: true })
    expect(warning.width).toBeLessThan(major.width)
    expect(major.width).toBeLessThan(critical.width)
  })

  it('OPEN 은 깜빡임(CRITICAL 이 더 빠름), ACK 만 남으면 정지', () => {
    expect(alarmRing({ count: 2, openCount: 1, maxSeverity: 'CRITICAL' })?.blink).toBe('fast')
    expect(alarmRing({ count: 2, openCount: 2, maxSeverity: 'MAJOR' })?.blink).toBe('slow')
    const acked = alarmRing({ count: 2, openCount: 0, maxSeverity: 'CRITICAL' })!
    expect(acked.blink).toBe('none')
    expect(acked.unacked).toBe(false)
  })
})

describe('(c-2) PM 지연 알람은 링에서 제외', () => {
  const base = { count: 0, openCount: 0, maxSeverity: 'WARNING' as const, pmOverdueCount: 0, pmOverdueOpenCount: 0, nonPmMaxSeverity: null }

  it('PM_OVERDUE(MAJOR)만 있으면 링이 없다', () => {
    const summary = { ...base, count: 1, openCount: 1, maxSeverity: 'MAJOR' as const, pmOverdueCount: 1, pmOverdueOpenCount: 1 }
    expect(alarmRing(toFloorMapAlarm(summary))).toBeNull()
    // 툴팁용 PM 지연 알람 건수는 남는다
    expect(toFloorMapAlarm(summary)?.pmOverdueCount).toBe(1)
  })

  it('센서 WARNING + PM_OVERDUE(MAJOR)면 WARNING 링이고 건수는 센서 알람만 센다', () => {
    const ring = alarmRing(
      toFloorMapAlarm({
        ...base,
        count: 2,
        openCount: 2,
        maxSeverity: 'MAJOR',
        pmOverdueCount: 1,
        pmOverdueOpenCount: 1,
        nonPmMaxSeverity: 'WARNING',
      }),
    )
    expect(ring).toMatchObject({ severity: 'WARNING', count: 1, openCount: 1, dasharray: '5 4' })
  })

  it('PM_OVERDUE 가 ACK 이고 센서 알람이 OPEN 이면 센서 기준으로 깜빡인다', () => {
    const ring = alarmRing(
      toFloorMapAlarm({ ...base, count: 2, openCount: 1, maxSeverity: 'CRITICAL', pmOverdueCount: 1, nonPmMaxSeverity: 'CRITICAL' }),
    )
    expect(ring).toMatchObject({ severity: 'CRITICAL', blink: 'fast', count: 1, openCount: 1 })
  })

  it('집계가 없으면 undefined', () => {
    expect(toFloorMapAlarm(undefined)).toBeUndefined()
    expect(toFloorMapAlarm(null)).toBeUndefined()
  })
})

describe('(d) 센서 level → 파트 강조', () => {
  it('항상 4개 파트를 TEMP→VIBRATION→PRESSURE→CURRENT 순으로 돌려준다', () => {
    const parts = resolveParts([])
    expect(parts.map((part) => part.sensorType)).toEqual(['TEMP', 'VIBRATION', 'PRESSURE', 'CURRENT'])
    expect(parts.every((part) => part.state === 'NONE')).toBe(true)
  })

  it('이상 level 인 센서에 대응하는 파트만 강조된다', () => {
    const parts = resolveParts([
      sensor({ sensorId: 1, sensorType: 'TEMP', level: 'WARNING' }),
      sensor({ sensorId: 2, sensorType: 'VIBRATION', level: 'NORMAL' }),
      sensor({ sensorId: 3, sensorType: 'PRESSURE', level: 'CRITICAL' }),
      sensor({ sensorId: 4, sensorType: 'CURRENT' }), // level 없음 → 정상
    ])
    expect(parts.map((part) => part.state)).toEqual(['WARNING', 'NORMAL', 'CRITICAL', 'NORMAL'])
    expect(parts.filter((part) => isPartAbnormal(part.state)).map((part) => part.shortLabel)).toEqual([
      '히터',
      '진공',
    ])
  })

  it('센서가 없는 슬롯은 NONE (강조 대상 아님) — AOI/OVEN 처럼 센서 3종인 설비', () => {
    const parts = resolveParts([
      sensor({ sensorType: 'TEMP', level: 'NORMAL' }),
      sensor({ sensorType: 'CURRENT', level: 'CRITICAL' }),
    ])
    expect(parts[1].state).toBe('NONE')
    expect(isPartAbnormal(parts[1].state)).toBe(false)
    expect(parts[3].state).toBe('CRITICAL')
  })

  it('같은 종류 센서가 둘이면 더 나쁜 level 을 쓴다', () => {
    const parts = resolveParts([
      sensor({ sensorId: 1, sensorType: 'TEMP', level: 'WARNING' }),
      sensor({ sensorId: 2, sensorType: 'TEMP', level: 'CRITICAL', value: 99 }),
    ])
    expect(parts[0]).toMatchObject({ state: 'CRITICAL', value: 99 })
  })
})

describe('센서 수신 끊김(stale) — 브라우저 수신 시각 기준', () => {
  const sensors = [sensor({ sensorType: 'TEMP', measuredAt: '2026-10-09T02:59:00Z' })]

  it('센서 목록을 못 받았거나 센서가 없거나 수신 기록이 없으면 판정하지 않는다', () => {
    expect(isSensorStale(undefined, NOW, NOW)).toBe(false)
    expect(isSensorStale([], NOW, NOW)).toBe(false)
    expect(isSensorStale(sensors, undefined, NOW)).toBe(false)
  })

  it('마지막 수신이 기준 이내면 정상, 넘으면 끊김', () => {
    expect(isSensorStale(sensors, NOW - 2_000, NOW)).toBe(false)
    expect(isSensorStale(sensors, NOW - STALE_AFTER_MS, NOW)).toBe(false)
    expect(isSensorStale(sensors, NOW - STALE_AFTER_MS - 1, NOW)).toBe(true)
  })

  it('브라우저 시계가 서버보다 +20초 빨라도(measuredAt 이 20초 전으로 보여도) 방금 받은 설비는 정상이다', () => {
    const skewed = [sensor({ sensorType: 'TEMP', measuredAt: new Date(NOW - 20_000).toISOString() })]
    // 수신 시각은 브라우저 시계 기준이라 measuredAt 의 오차와 무관하다
    expect(isSensorStale(skewed, NOW - 1_000, NOW)).toBe(false)
  })

  it('실제로 수신이 멈추면 시계 오차와 무관하게 끊김으로 바뀐다', () => {
    expect(isSensorStale(sensors, NOW - 1_000, NOW + 20_000)).toBe(true)
  })
})

describe('(e) 공정 순서 정렬/그룹핑', () => {
  const eq = (id: number, code: string, lineId: number | undefined, processId: number | undefined, processName?: string): LayoutEquipment => ({
    id,
    code,
    lineId,
    lineName: lineId ? `LINE-${lineId}` : undefined,
    processId,
    processName,
  })

  it('공정 순서 정보가 없으면 processId 오름차순 → code 순', () => {
    const layout = buildFloorLayout([
      eq(5, 'AOI-01', 1, 30, '검사'),
      eq(3, 'OVEN-01', 1, 10, '합착'),
      eq(2, 'LAMI-02', 1, 10, '합착'),
      eq(1, 'LAMI-01', 1, 10, '합착'),
      eq(4, 'SCRB-01', 1, 20, '절단'),
    ])
    expect(layout).toHaveLength(1)
    expect(layout[0].groups.map((group) => group.processName)).toEqual(['합착', '절단', '검사'])
    expect(layout[0].groups[0].equipments.map((e) => e.code)).toEqual(['LAMI-01', 'LAMI-02', 'OVEN-01'])
  })

  it('공정 순서(seq)가 주어지면 processId 보다 seq 를 우선한다', () => {
    const layout = buildFloorLayout(
      [eq(1, 'A-1', 1, 10, '나중'), eq(2, 'B-1', 1, 20, '먼저')],
      { 10: 2, 20: 1 },
    )
    expect(layout[0].groups.map((group) => group.processName)).toEqual(['먼저', '나중'])
  })

  it('seq 가 없는 공정은 뒤로 가고 같은 seq 면 processId 순', () => {
    const layout = buildFloorLayout(
      [eq(1, 'A-1', 1, 5, '미등록'), eq(2, 'B-1', 1, 30, 'seq1'), eq(3, 'C-1', 1, 20, 'seq1b')],
      { 30: 1, 20: 1 },
    )
    expect(layout[0].groups.map((group) => group.processName)).toEqual(['seq1b', 'seq1', '미등록'])
  })

  it('라인별로 나누고 lineId 오름차순, 라인/공정 미지정은 맨 뒤', () => {
    const layout = buildFloorLayout([
      eq(1, 'X-1', undefined, undefined),
      eq(2, 'A-1', 2, 10, 'P'),
      eq(3, 'B-1', 1, 10, 'P'),
    ])
    expect(layout.map((line) => line.lineId)).toEqual([1, 2, null])
    expect(layout[2].lineName).toBe('라인 미지정')
    expect(layout[2].groups[0].processName).toBe('공정 미지정')
  })

  it('같은 공정 안 code 는 숫자 자연 정렬이고 입력 배열은 바뀌지 않는다', () => {
    const input = [eq(1, 'LAMI-10', 1, 1, 'P'), eq(2, 'LAMI-2', 1, 1, 'P')]
    const snapshot = input.map((e) => e.code)
    const layout = buildFloorLayout(input)
    expect(layout[0].groups[0].equipments.map((e) => e.code)).toEqual(['LAMI-2', 'LAMI-10'])
    expect(input.map((e) => e.code)).toEqual(snapshot)
  })

  it('설비가 없으면 빈 배열', () => {
    expect(buildFloorLayout([])).toEqual([])
  })

  it('GET /lines 트리에서 processId → seq 맵을 만든다 (seq 없는 공정은 제외)', () => {
    expect(
      processSeqFromLines([
        { processes: [{ id: 10, seq: 1 }, { id: 20, seq: 2 }] },
        { processes: [{ id: 30 }] },
        {},
      ]),
    ).toEqual({ 10: 1, 20: 2 })
  })
})

describe('describeUnit (aria-label)', () => {
  it('상태·알람·PM 지연·끊김·이상 파트를 글자로 모두 포함한다', () => {
    const label = describeUnit({
      code: 'LAMI-01',
      name: '합착기 1호기',
      status: 'IDLE',
      kind: 'LAMI',
      ring: alarmRing({ count: 2, openCount: 1, maxSeverity: 'WARNING' }),
      pmOverdue: true,
      stale: true,
      parts: resolveParts([sensor({ sensorType: 'TEMP', level: 'WARNING' })]),
    })
    expect(label).toContain('LAMI-01 합착기 1호기')
    expect(label).toContain('상태 IDLE')
    expect(label).toContain('미해결 알람 2건 최고 경고 미확인 1건')
    expect(label).toContain('PM 지연')
    expect(label).toContain('센서 수신 끊김')
    expect(label).toContain('히터/챔버부 경고')
  })

  it('알람이 없으면 없음, 집계 중이면 집계 중으로 구분한다', () => {
    const base = { code: 'A-1', name: 'n', status: 'RUN' as const, kind: 'GENERIC' as const, parts: resolveParts([]) }
    expect(describeUnit({ ...base, ring: null })).toContain('미해결 알람 없음')
    expect(describeUnit({ ...base, ring: null, alarmUnknown: true })).toContain('집계 중')
    expect(describeUnit({ ...base, ring: null, alarmUnknown: true, alarmFailed: true })).toContain('집계 실패')
    expect(describeUnit({ ...base, status: 'DOWN', ring: null })).toContain('상태 DOWN (BM)')
  })
})
