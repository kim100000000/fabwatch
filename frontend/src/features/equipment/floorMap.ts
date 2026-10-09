/**
 * 설비 배치도(Floor Map) 순수 로직 — 화면(SVG)과 분리해 단위 테스트한다 (docs/04 S-1 '배치도 뷰').
 *
 * 다른 feature(sensor/alarm)를 import 하지 않는다. 필요한 데이터는 pages 가 props 로 주입하고,
 * 여기서는 그 모양을 구조적 타입(FloorMapSensor/FloorMapAlarm)으로만 정의한다.
 * 색은 hex 가 아니라 전역 토큰 이름(`var(--status-run)` 등)만 돌려준다 — 색의 단일 출처는 global.css.
 */

import { statusLabel } from '@/shared/lib/equipmentStatus'
import type { EquipmentStatus, SensorType } from './types'

/* ------------------------------------------------------------------
 * 입력 구조 타입 (sensor/alarm 쪽 타입과 구조적으로 호환되는 최소 모양)
 * ------------------------------------------------------------------ */

export type FloorMapSensorLevel = 'NORMAL' | 'WARNING' | 'CRITICAL'
export type FloorMapAlarmSeverity = 'WARNING' | 'MAJOR' | 'CRITICAL'

/** 설비의 센서 최신값 1건 — sensor 피처의 SensorLatest 가 그대로 대입 가능 */
export interface FloorMapSensor {
  sensorId: number
  sensorType: SensorType
  value: number | null
  unit?: string | null
  level?: FloorMapSensorLevel
  /** UTC ISO. 아직 수신 전이면 null */
  measuredAt: string | null
}

/** 설비의 미해결 알람 집계 원본 — alarm 피처의 OpenAlarmSummary.byEquipment 항목이 그대로 대입 가능 */
export interface FloorMapAlarmSummary {
  /** 미해결(OPEN+ACK) 전체 건수 (PM_OVERDUE 포함) */
  count: number
  /** 그중 아직 확인(ACK)되지 않은 OPEN 건수 */
  openCount: number
  maxSeverity: FloorMapAlarmSeverity
  /** 그중 PM_OVERDUE(PM 지연) 알람 건수 / OPEN 건수 */
  pmOverdueCount: number
  pmOverdueOpenCount: number
  /** PM_OVERDUE 를 뺀 알람의 최고 심각도. 없으면 null */
  nonPmMaxSeverity: FloorMapAlarmSeverity | null
}

/** 링·건수 뱃지에 쓰는 집계 — PM 지연 알람은 뺀 센서/수동 알람 기준 */
export interface FloorMapAlarm {
  /** 센서·수동 미해결 알람 건수 */
  count: number
  /** 그중 OPEN(미확인) 건수 */
  openCount: number
  maxSeverity: FloorMapAlarmSeverity
  /** 링에서 제외한 PM 지연 알람 건수 (툴팁에 별도 표시) */
  pmOverdueCount?: number
}

/**
 * 알람 집계 원본 → 링용 집계. PM 지연은 이미 전용 뱃지(PM 지연)가 있고,
 * 링 심각도에 섞이면 센서 WARNING→CRITICAL 변화가 MAJOR 에 가려지므로 링·건수 뱃지에서 뺀다.
 */
export function toFloorMapAlarm(summary: FloorMapAlarmSummary | null | undefined): FloorMapAlarm | undefined {
  if (!summary) return undefined
  return {
    count: summary.count - summary.pmOverdueCount,
    openCount: summary.openCount - summary.pmOverdueOpenCount,
    // 센서/수동 알람이 없으면 count 가 0 이라 링이 그려지지 않는다 (심각도 값은 쓰이지 않음)
    maxSeverity: summary.nonPmMaxSeverity ?? 'WARNING',
    pmOverdueCount: summary.pmOverdueCount,
  }
}

/* ------------------------------------------------------------------
 * (a) 설비 종류 판별 — 시드 기준 가상 일반형 4종, 모르는 접두사는 범용 박스
 * ------------------------------------------------------------------ */

export type EquipmentKind = 'LAMI' | 'OVEN' | 'SCRB' | 'AOI' | 'GENERIC'

export const EQUIPMENT_KIND_LABEL: Record<EquipmentKind, string> = {
  LAMI: '합착기',
  OVEN: '경화로',
  SCRB: '스크라이버',
  AOI: '검사기',
  GENERIC: '설비',
}

const KNOWN_KINDS: ReadonlySet<string> = new Set(['LAMI', 'OVEN', 'SCRB', 'AOI'])

/** 설비 code 접두사('LAMI-01' → LAMI)로 종류를 판별한다. 비었거나 모르는 접두사는 GENERIC. */
export function detectEquipmentKind(code: string | null | undefined): EquipmentKind {
  // 첫 구분자(하이픈·밑줄·공백·숫자) 앞까지가 접두사
  const prefix = (code ?? '').trim().toUpperCase().split(/[-_\s\d]/)[0]
  return KNOWN_KINDS.has(prefix) ? (prefix as EquipmentKind) : 'GENERIC'
}

/* ------------------------------------------------------------------
 * (b) 상태 → 본체 색/라벨 매핑 + 컨베이어 흐름
 * ------------------------------------------------------------------ */

export interface StatusVisual {
  /** 화면 라벨 — shared/lib/equipmentStatus 단일 출처 (DOWN 은 'DOWN (BM)') */
  label: string
  /** 본체 색 CSS 토큰 (단일 출처: global.css --status-*) */
  colorVar: string
}

const STATUS_COLOR_VAR: Record<EquipmentStatus, string> = {
  RUN: 'var(--status-run)',
  IDLE: 'var(--status-idle)',
  PM: 'var(--status-pm)',
  DOWN: 'var(--status-down)',
}

/** 상태 → 본체 색 + 라벨. 알 수 없는 상태 값은 중립 회색으로 폴백(크래시 방지). */
export function statusVisual(status: EquipmentStatus): StatusVisual {
  return {
    label: statusLabel(status),
    colorVar: STATUS_COLOR_VAR[status] ?? 'var(--neutral)',
  }
}

/** 설비 사이 컨베이어가 흐르는지 — 양쪽 모두 RUN 일 때만 (나머지는 정지) */
export function isConveyorFlowing(from: EquipmentStatus, to: EquipmentStatus): boolean {
  return from === 'RUN' && to === 'RUN'
}

/* ------------------------------------------------------------------
 * (c) 알람 → 외곽 링 표현 (본체 색은 바꾸지 않는다)
 * ------------------------------------------------------------------ */

export type RingBlink = 'fast' | 'slow' | 'none'

export interface AlarmRing {
  severity: FloorMapAlarmSeverity
  /** 심각도 한글 라벨 (색 외 글자 표식) */
  label: string
  colorVar: string
  /** 링 선 두께(px) — 심각도가 높을수록 굵다 */
  width: number
  /** 점선 패턴 — WARNING 만 점선(IDLE 노랑 본체와 모양으로도 구분) */
  dasharray: string | null
  /** MAJOR/CRITICAL 은 바깥에 얇은 보조 링을 하나 더 그린다 */
  doubleRing: boolean
  /** 미확인(OPEN) 알람이 있으면 깜빡임, 전부 확인(ACK)이면 정지. CRITICAL 은 더 빠르게 */
  blink: RingBlink
  /** OPEN 이 하나라도 남아 있는지 — 뱃지를 채움(미확인)/윤곽(확인됨)으로 구분 */
  unacked: boolean
  count: number
  openCount: number
}

const RING_STYLE: Record<
  FloorMapAlarmSeverity,
  Pick<AlarmRing, 'label' | 'colorVar' | 'width' | 'dasharray' | 'doubleRing'>
> = {
  WARNING: { label: '경고', colorVar: 'var(--alarm-warning)', width: 3, dasharray: '5 4', doubleRing: false },
  MAJOR: { label: '주요', colorVar: 'var(--alarm-major)', width: 4, dasharray: null, doubleRing: true },
  CRITICAL: { label: '위험', colorVar: 'var(--alarm-critical)', width: 4.5, dasharray: null, doubleRing: true },
}

/** 미해결 알람 집계 → 링 표현. 알람이 없거나 집계 전(null)이면 링 없음. */
export function alarmRing(alarm: FloorMapAlarm | null | undefined): AlarmRing | null {
  if (!alarm || alarm.count <= 0) return null
  const style = RING_STYLE[alarm.maxSeverity]
  if (!style) return null
  const unacked = alarm.openCount > 0
  return {
    severity: alarm.maxSeverity,
    ...style,
    blink: unacked ? (alarm.maxSeverity === 'CRITICAL' ? 'fast' : 'slow') : 'none',
    unacked,
    count: alarm.count,
    openCount: alarm.openCount,
  }
}

/* ------------------------------------------------------------------
 * (d) 센서 level → 파트 강조
 * ------------------------------------------------------------------ */

/** 파트 상태 — NONE 은 이 설비에 해당 센서가 없는 슬롯 */
export type PartState = 'NORMAL' | 'WARNING' | 'CRITICAL' | 'NONE'

export interface PartInfo {
  sensorType: SensorType
  /** 센서 종류 한글 이름 (온도/진동/압력/전류) */
  sensorLabel: string
  /** SVG 안에 쓰는 짧은 라벨 */
  shortLabel: string
  /** 툴팁/aria 용 전체 라벨 */
  fullLabel: string
  state: PartState
  value: number | null
  unit: string | null
}

/** 센서 4종 ↔ 파트 4슬롯 대응 (TEMP=히터/챔버부, VIBRATION=구동 모터부, PRESSURE=진공/에어부, CURRENT=전장 제어부) */
export const PART_DEFS: { sensorType: SensorType; sensorLabel: string; shortLabel: string; fullLabel: string }[] = [
  { sensorType: 'TEMP', sensorLabel: '온도', shortLabel: '히터', fullLabel: '히터/챔버부' },
  { sensorType: 'VIBRATION', sensorLabel: '진동', shortLabel: '모터', fullLabel: '구동 모터부' },
  { sensorType: 'PRESSURE', sensorLabel: '압력', shortLabel: '진공', fullLabel: '진공/에어부' },
  { sensorType: 'CURRENT', sensorLabel: '전류', shortLabel: '전장', fullLabel: '전장 제어부' },
]

const LEVEL_RANK: Record<FloorMapSensorLevel, number> = { NORMAL: 0, WARNING: 1, CRITICAL: 2 }

/**
 * 센서 최신값 → 파트 4슬롯 상태. 항상 4개(TEMP→VIBRATION→PRESSURE→CURRENT 순)를 돌려준다.
 * 같은 종류 센서가 여럿이면 가장 나쁜 level, level 이 없으면 NORMAL, 센서가 없으면 NONE.
 */
export function resolveParts(sensors: readonly FloorMapSensor[] | undefined): PartInfo[] {
  return PART_DEFS.map((def) => {
    const ofType = (sensors ?? []).filter((sensor) => sensor.sensorType === def.sensorType)
    if (ofType.length === 0) {
      return { ...def, state: 'NONE' as const, value: null, unit: null }
    }
    const worst = ofType.reduce((acc, sensor) =>
      LEVEL_RANK[sensor.level ?? 'NORMAL'] > LEVEL_RANK[acc.level ?? 'NORMAL'] ? sensor : acc,
    )
    return {
      ...def,
      state: worst.level && worst.level !== 'NORMAL' ? worst.level : 'NORMAL',
      value: worst.value,
      unit: worst.unit ?? null,
    }
  })
}

/** 파트를 강조(색 변경 + 라벨 표시)해야 하는지 */
export function isPartAbnormal(state: PartState): state is 'WARNING' | 'CRITICAL' {
  return state === 'WARNING' || state === 'CRITICAL'
}

/* ------------------------------------------------------------------
 * 센서 수신 끊김(stale)
 * ------------------------------------------------------------------ */

/** 센서는 2초 주기라, 이 시간 동안 새 값을 한 건도 못 받으면 '수신 끊김' */
export const STALE_AFTER_MS = 15_000

/**
 * 센서 수신 끊김 판정 — **브라우저가 새 센서값을 마지막으로 받은 시각(lastReceivedAtMs)** 기준이다.
 * 서버 measuredAt 과 브라우저 시계를 비교하지 않으므로 두 시계의 오차(예: +20초)가 있어도
 * 정상 수신 중인 설비가 끊김으로 보이지 않는다.
 * 센서 목록을 아직 못 받았거나(undefined) 센서가 없는 설비, 수신 기록이 아직 없는 설비는 판정하지 않는다.
 */
export function isSensorStale(
  sensors: readonly FloorMapSensor[] | undefined,
  lastReceivedAtMs: number | undefined,
  nowMs: number,
  staleAfterMs: number = STALE_AFTER_MS,
): boolean {
  if (!sensors || sensors.length === 0) return false
  if (lastReceivedAtMs === undefined) return false
  return nowMs - lastReceivedAtMs > staleAfterMs
}

/* ------------------------------------------------------------------
 * (e) 공정 순서 정렬 / 그룹핑 — 좌표를 저장하지 않는 자동 배치
 * ------------------------------------------------------------------ */

/** 배치에 필요한 설비의 최소 모양 (EquipmentSummary 가 대입 가능) */
export interface LayoutEquipment {
  id: number
  code: string
  lineId?: number
  lineName?: string
  processId?: number
  processName?: string
}

export interface ProcessGroup<T extends LayoutEquipment> {
  /** 공정 정보가 없는 설비는 null */
  processId: number | null
  processName: string
  equipments: T[]
}

export interface LineLayout<T extends LayoutEquipment> {
  lineId: number | null
  lineName: string
  groups: ProcessGroup<T>[]
}

/** GET /lines 트리(공정 seq 포함)에서 processId → seq 맵을 만든다 — 새 API 없이 기존 호출 결과를 재사용 */
export function processSeqFromLines(
  lines: readonly { processes?: readonly { id: number; seq?: number | null }[] }[],
): Record<number, number> {
  const result: Record<number, number> = {}
  for (const line of lines) {
    for (const process of line.processes ?? []) {
      if (typeof process.seq === 'number') result[process.id] = process.seq
    }
  }
  return result
}

const UNASSIGNED_LINE = '라인 미지정'
const UNASSIGNED_PROCESS = '공정 미지정'

const compareCode = (a: string, b: string): number => a.localeCompare(b, 'en', { numeric: true })

/** null(미지정)은 항상 뒤로 보내는 숫자 비교 */
const compareNullable = (a: number | null, b: number | null): number => {
  if (a === b) return 0
  if (a === null) return 1
  if (b === null) return -1
  return a - b
}

/**
 * 설비 목록 → 라인별 한 줄 흐름 배치.
 *  - 라인: lineId 오름차순
 *  - 공정: processSeq(공정 순서 API, processId→seq)가 있으면 seq 순, 없거나 같으면 processId 오름차순
 *  - 같은 공정 안: code 순(숫자 자연 정렬 — LAMI-2 < LAMI-10)
 * 입력 배열은 변경하지 않는다.
 */
export function buildFloorLayout<T extends LayoutEquipment>(
  equipments: readonly T[],
  processSeq?: Readonly<Record<number, number>>,
): LineLayout<T>[] {
  const lines = new Map<number | null, { lineName: string; processes: Map<number | null, ProcessGroup<T>> }>()

  for (const equipment of equipments) {
    const lineKey = equipment.lineId ?? null
    let line = lines.get(lineKey)
    if (!line) {
      line = { lineName: equipment.lineName || UNASSIGNED_LINE, processes: new Map() }
      lines.set(lineKey, line)
    }
    const processKey = equipment.processId ?? null
    let group = line.processes.get(processKey)
    if (!group) {
      group = {
        processId: processKey,
        processName: equipment.processName || UNASSIGNED_PROCESS,
        equipments: [],
      }
      line.processes.set(processKey, group)
    }
    group.equipments.push(equipment)
  }

  const seqOf = (processId: number | null): number | null =>
    processId === null ? null : (processSeq?.[processId] ?? null)

  return [...lines.entries()]
    .sort(([a], [b]) => compareNullable(a, b))
    .map(([lineId, line]) => ({
      lineId,
      lineName: line.lineName,
      groups: [...line.processes.values()]
        .sort(
          (a, b) =>
            compareNullable(seqOf(a.processId), seqOf(b.processId)) || compareNullable(a.processId, b.processId),
        )
        .map((group) => ({
          ...group,
          equipments: [...group.equipments].sort((a, b) => compareCode(a.code, b.code)),
        })),
    }))
}

/* ------------------------------------------------------------------
 * 접근성 문구 — 색만으로 구분하지 않도록 상태·알람·PM 지연·끊김을 글자로도 낸다
 * ------------------------------------------------------------------ */

export interface UnitDescribeInput {
  code: string
  name: string
  status: EquipmentStatus
  kind: EquipmentKind
  ring: AlarmRing | null
  /** 알람 집계 전/실패로 알 수 없는지 */
  alarmUnknown?: boolean
  /** 알 수 없는 이유가 조회 실패인지 (아니면 아직 집계 중) */
  alarmFailed?: boolean
  pmOverdue?: boolean
  stale?: boolean
  parts: readonly PartInfo[]
}

/** 설비 박스의 aria-label — 스크린리더용 한 줄 요약 */
export function describeUnit(input: UnitDescribeInput): string {
  const parts: string[] = [
    `${input.code} ${input.name}`,
    `${EQUIPMENT_KIND_LABEL[input.kind]}`,
    `상태 ${statusLabel(input.status)}`,
  ]
  if (input.ring) {
    parts.push(
      `미해결 알람 ${input.ring.count}건 최고 ${input.ring.label}` +
        (input.ring.unacked ? ` 미확인 ${input.ring.openCount}건` : ' 모두 확인됨'),
    )
  } else if (input.alarmUnknown) {
    parts.push(input.alarmFailed ? '미해결 알람 집계 실패' : '미해결 알람 집계 중')
  } else {
    parts.push('미해결 알람 없음')
  }
  if (input.pmOverdue) parts.push('PM 지연')
  if (input.stale) parts.push('센서 수신 끊김')
  const abnormal = input.parts.filter((part) => isPartAbnormal(part.state))
  if (abnormal.length > 0) {
    parts.push(
      `센서 이상 ${abnormal.map((part) => `${part.fullLabel} ${part.state === 'CRITICAL' ? '위험' : '경고'}`).join(', ')}`,
    )
  }
  parts.push('상세 보기')
  return parts.join(', ')
}
