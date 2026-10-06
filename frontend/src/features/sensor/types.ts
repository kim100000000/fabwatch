/**
 * 센서 도메인 타입.
 * 백엔드 실제 DTO(`sensor/dto/*.java`)에 맞춰 정의한다 — docs/06 §3 스펙과 다른 지점은 주석으로 표시.
 *
 * 백엔드와 대조 완료(2주차):
 *  - SensorLatestResponse : sensorType (docs 의 `type` 아님), 목록 공통 래핑({content,...})
 *  - SensorSeriesResponse : { granularity, from, to, series: [{ ..., points: [{ at, value, ... }] }] }
 *                           → 포인트 시각 필드는 `at` 이고, RAW/1M 이 같은 shape 이라 프론트가 분기하지 않는다
 *  - SensorStreamPayload  : docs 스펙 + equipmentId·unit 추가 (전체 구독 시 카드 매칭용)
 */

import type { EquipmentStatus, SensorType } from '@/features/equipment'

/** 임계치 판정 결과 (docs/06 §3 SSE sensor 이벤트 level) */
export type SensorLevel = 'NORMAL' | 'WARNING' | 'CRITICAL'

/** 이력 조회 시 서버가 선택한 데이터 소스 — RAW=원본, 1M=1분 집계 (docs/06 §3) */
export type SensorGranularity = 'RAW' | '1M'

/** 이력 차트 기간 선택 (docs/03 F-5.2) */
export type HistoryRange = '1H' | '24H' | '7D'

export const HISTORY_RANGES: { key: HistoryRange; label: string; minutes: number }[] = [
  { key: '1H', label: '1시간', minutes: 60 },
  { key: '24H', label: '24시간', minutes: 60 * 24 },
  { key: '7D', label: '7일', minutes: 60 * 24 * 7 },
]

/** 임계치 4값 — latest / series 응답이 공통으로 싣는다 (NULL = 해당 방향 미사용) */
export interface SensorThresholds {
  warnLow?: number | null
  warnHigh?: number | null
  critLow?: number | null
  critHigh?: number | null
}

/** GET /equipments/{id}/sensor-data/latest — 센서별 최신값 1건 (SensorLatestResponse) */
export interface SensorLatest extends SensorThresholds {
  sensorId: number
  equipmentId?: number
  /** 백엔드 필드명은 sensorType (docs/06 표기의 `type` 아님) */
  sensorType: SensorType
  unit?: string | null
  value: number | null
  /** UTC ISO. 값이 아직 없으면 null */
  measuredAt: string | null
  level?: SensorLevel
}

/**
 * GET /equipments/{id}/sensor-data 의 포인트 (SensorSeriesResponse.Point).
 * RAW/1M 이 같은 shape 이다: RAW 는 at=measured_at·value=원본, 1M 은 at=bucket_at·value=avg.
 */
export interface SensorSeriesPoint {
  /** UTC ISO */
  at: string
  value: number
  minValue?: number | null
  maxValue?: number | null
  sampleCount?: number | null
}

/** 센서 1개분 시계열 (SensorSeriesResponse.Series) */
export interface SensorSeries extends SensorThresholds {
  sensorId: number
  sensorType: SensorType
  unit?: string | null
  points: SensorSeriesPoint[]
}

/** GET /equipments/{id}/sensor-data 응답 */
export interface SensorSeriesResponse {
  equipmentId?: number
  /** 서버가 RAW/1M 중 무엇을 썼는지 명시 — 프론트가 기간으로 추측하지 않는다 */
  granularity: SensorGranularity
  from?: string
  to?: string
  series: SensorSeries[]
}

/** GET /equipments/{id}/sensor-data 쿼리 */
export interface SensorSeriesQuery {
  sensorType: SensorType
  /** UTC ISO */
  from: string
  to: string
}

/* ------------------------------------------------------------------
 * SSE 이벤트 페이로드 (docs/06 §3)
 * ------------------------------------------------------------------ */

/** event: sensor (SensorStreamPayload) — 이 이벤트만 필드명이 `type` 이다(latest/series 는 sensorType) */
export interface SensorEventPayload {
  sensorId: number
  /** 전체 라인 구독 시 카드 매칭용 — 백엔드가 docs 스펙에 추가로 실어 준다 */
  equipmentId: number
  type: SensorType
  unit?: string | null
  value: number
  /** UTC ISO */
  measuredAt: string
  level: SensorLevel
}

/** event: status (EquipmentStatusChangedEvent) */
export interface EquipmentStatusEventPayload {
  equipmentId: number
  equipmentCode?: string | null
  /** 최초 등록 시 null */
  fromStatus?: EquipmentStatus | null
  toStatus: EquipmentStatus
  reason?: string | null
  /** 시스템(자동 DOWN)이면 null */
  changedBy?: number | null
  changedAt?: string
}

/** 실시간 차트가 들고 있는 슬라이딩 포인트 */
export interface LivePoint {
  /** epoch ms — Recharts 숫자 축 */
  t: number
  value: number
  level: SensorLevel
}

/** 실시간 차트 슬라이딩 상한 (docs/03 F-5.2 — 최근 5분 / 최대 150포인트) */
export const LIVE_WINDOW_MINUTES = 5
export const LIVE_MAX_POINTS = 150

/** 센서 종류 표기 라벨 (API 값은 그대로 유지) */
export const SENSOR_TYPE_LABEL: Record<SensorType, string> = {
  TEMP: '온도',
  VIBRATION: '진동',
  PRESSURE: '압력',
  CURRENT: '전류',
}

/** 카드/차트 정렬 순서 고정 (설비마다 센서 순서가 흔들리지 않게) */
export const SENSOR_TYPE_ORDER: SensorType[] = ['TEMP', 'VIBRATION', 'PRESSURE', 'CURRENT']

export function compareSensorType(a: SensorType, b: SensorType): number {
  return SENSOR_TYPE_ORDER.indexOf(a) - SENSOR_TYPE_ORDER.indexOf(b)
}

/**
 * 임계치 기준 레벨 판정 — 서버가 level 을 안 보낼 때만 쓰는 보조 계산.
 * 판정의 단일 출처는 백엔드(docs/03 F-4.3)이고, 여기서는 표시용으로만 쓴다.
 */
export function levelOf(
  value: number | null | undefined,
  thresholds: SensorThresholds,
): SensorLevel {
  if (value === null || value === undefined) return 'NORMAL'
  const { warnLow, warnHigh, critLow, critHigh } = thresholds
  if ((critHigh !== null && critHigh !== undefined && value > critHigh) ||
      (critLow !== null && critLow !== undefined && value < critLow)) {
    return 'CRITICAL'
  }
  if ((warnHigh !== null && warnHigh !== undefined && value > warnHigh) ||
      (warnLow !== null && warnLow !== undefined && value < warnLow)) {
    return 'WARNING'
  }
  return 'NORMAL'
}

/* ------------------------------------------------------------------
 * 센서 정의 · 임계치 편집 (FR-2.3) — 백엔드 sensor/dto/SensorResponse·ThresholdLogResponse 기준
 * ------------------------------------------------------------------ */

/** GET /equipments/{id}/sensors 항목 · PUT thresholds 응답 (SensorResponse) — 임계치 4값은 NULL 가능 */
export interface SensorDefinition extends SensorThresholds {
  sensorId: number
  equipmentId: number
  sensorType: SensorType
  unit?: string | null
  /** 시뮬레이터 정상 기준값(평균) — 임계치 입력 시 참고용 */
  baseValue?: number | null
  noiseSigma?: number | null
}

/** 임계치 4값 (편집 요청/로그 공통, NULL = 해당 방향 미사용) */
export interface ThresholdValues {
  warnLow: number | null
  warnHigh: number | null
  critLow: number | null
  critHigh: number | null
}

/** PUT /equipments/{id}/sensors/{sensorId}/thresholds 요청 — ADMIN 전용, reason 필수(300자 이하) */
export interface ThresholdUpdateRequest extends ThresholdValues {
  reason: string
}

/** GET /equipments/{id}/sensors/{sensorId}/thresholds/logs 항목 (ThresholdLogResponse) */
export interface ThresholdLog {
  id: number
  sensorId: number
  equipmentId: number
  oldWarnLow: number | null
  oldWarnHigh: number | null
  oldCritLow: number | null
  oldCritHigh: number | null
  newWarnLow: number | null
  newWarnHigh: number | null
  newCritLow: number | null
  newCritHigh: number | null
  reason: string
  changedBy: number | null
  /** 사용자 조회 실패/시스템이면 비어 있을 수 있다 */
  changedByName?: string | null
  /** UTC ISO */
  changedAt: string
}

/** 변경 사유 최대 길이 (백엔드 @Size(max = 300)) */
export const THRESHOLD_REASON_MAX = 300

/**
 * 설비 헤더용 센서 종류 요약 — '온도·진동·압력·전류 (4종)'.
 * 종류 순서는 SENSOR_TYPE_ORDER 로 고정하고, 센서가 없으면 '센서 없음'.
 */
export function summarizeSensorTypes(sensors: { sensorType: SensorType }[]): string {
  const types = [...new Set(sensors.map((sensor) => sensor.sensorType))].sort(compareSensorType)
  if (types.length === 0) return '센서 없음'
  return `${types.map((type) => SENSOR_TYPE_LABEL[type] ?? type).join('·')} (${types.length}종)`
}
