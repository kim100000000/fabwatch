/**
 * 설비 도메인 타입 (docs/06 §2 + docs/05 테이블 정의 기준).
 * 백엔드 병렬 구현 중 — 실제 응답 shape 확정되면 이 파일을 갱신한다.
 * 존재가 불확실한 필드는 optional 로 두되 임의 변환 로직은 넣지 않는다.
 */

import type { UserRole } from '@/features/auth'
import type { EquipmentStatus } from '@/shared/lib/equipmentStatus'

export type { EquipmentStatus }

/** sensors.type */
export type SensorType = 'TEMP' | 'VIBRATION' | 'PRESSURE' | 'CURRENT'

/** pm_schedules.cycle_type */
export type PmCycleType = 'DAILY' | 'WEEKLY' | 'MONTHLY'

/** GET /equipments — 목록 항목 */
export interface EquipmentSummary {
  id: number
  code: string
  name: string
  status: EquipmentStatus
  processId?: number
  processName?: string
  lineId?: number
  lineName?: string
  modelName?: string | null
  maker?: string | null
  managerId?: number | null
  managerName?: string | null
  /**
   * PM 예정일 초과 여부 — **서버 응답 필드가 아니다.**
   * 대시보드가 GET /pm-schedules?overdueOnly=true 결과를 합쳐 넣는 클라이언트 합성 값이다 (docs/04 §3 S-1).
   * (미해결 알람 수도 서버가 주지 않아 alarm 피처의 useOpenAlarmSummary 로 별도 집계한다)
   */
  pmOverdue?: boolean
}

/**
 * 설비 상세 헤더 값의 비동기 상태 — 로딩/실패를 '0' 이나 '-' 로 뭉개지 않고 구분해서 표시하기 위한 타입.
 * (조합하는 쪽은 pages — equipment 가 sensor/alarm/inspection 을 직접 조회하면 도메인 경계 위반)
 */
export type HeaderValue<T> =
  | { state: 'loading' }
  | { state: 'error' }
  | { state: 'ready'; value: T }

/** 설비 상세 헤더에 주입하는 '센서 / 미해결 알람 / 다음 PM 예정' 값 (FR-2.4) */
export interface EquipmentHeaderSummary {
  /** '온도·진동·압력·전류 (4종)' 같은 요약 문자열 */
  sensors: HeaderValue<string>
  /** 미해결(OPEN+ACK) 알람 건수 */
  openAlarmCount: HeaderValue<number>
  /** 다음 PM 스케줄 — 스케줄이 없으면 ready 의 value 가 null ('미설정') */
  nextPm: HeaderValue<{ nextDueAt: string; overdue: boolean; overdueDays: number } | null>
}

/**
 * GET /equipments/{id} — 상세.
 * 서버는 기본 정보만 돌려준다(센서·PM·미해결 알람은 각 도메인 API 를 조합 — pages/EquipmentDetailPage 참고).
 */
export interface EquipmentDetail extends EquipmentSummary {
  /** LocalDate 'YYYY-MM-DD' (시각 아님 — 날짜 입력 필드에 그대로 쓴다) */
  installedAt?: string | null
  note?: string | null
}

/**
 * 설비별 미해결 알람 수의 비동기 상태 — 로딩/실패를 0 으로 뭉개지 않고 구분해서 표시하기 위한 타입.
 * 알람 집계는 alarm 피처(useOpenAlarmSummary)가 하고, 조립은 pages 가 한다(피처 간 직접 참조 금지).
 */
export type OpenAlarmCounts =
  | { state: 'loading' }
  | { state: 'error' }
  | { state: 'ready'; byEquipment: Record<number, number> }

/** POST /equipments 요청 (ADMIN) — status 생략 시 백엔드가 IDLE 로 생성한다 */
export interface EquipmentCreateRequest {
  processId: number
  code: string
  name: string
  modelName?: string | null
  maker?: string | null
  /** 'YYYY-MM-DD' */
  installedAt?: string | null
  status?: EquipmentStatus
  managerId?: number | null
  note?: string | null
}

/** PUT /equipments/{id} 요청 (ADMIN) — code/status 는 여기서 바꿀 수 없다 */
export interface EquipmentUpdateRequest {
  processId: number
  name: string
  modelName?: string | null
  maker?: string | null
  installedAt?: string | null
  managerId?: number | null
  note?: string | null
}

/** PATCH /equipments/{id}/status 요청 (ENGINEER+, DOWN→IDLE 은 전 역할). DOWN→IDLE/RUN 은 reason 필수 */
export interface EquipmentStatusChangeRequest {
  toStatus: EquipmentStatus
  reason?: string | null
}

/** GET /equipments/{id}/status-logs — 상태 변경 이력. changedBy 가 null 이면 시스템 자동 전환 */
export interface EquipmentStatusLog {
  id: number
  equipmentId: number
  fromStatus: EquipmentStatus | null
  toStatus: EquipmentStatus
  reason?: string | null
  changedBy?: number | null
  changedByName?: string | null
  changedAt: string
}

/** GET /equipments 쿼리 필터 */
export interface EquipmentListFilter {
  processId?: number
  status?: EquipmentStatus
  page?: number
  size?: number
}

/** GET /lines — 라인 > 공정 > 설비 트리 */
export interface LineTree {
  id: number
  name: string
  processes?: ProcessTree[]
}

export interface ProcessTree {
  id: number
  name: string
  seq?: number
  equipments?: EquipmentSummary[]
}

export const EQUIPMENT_STATUSES: EquipmentStatus[] = ['RUN', 'IDLE', 'DOWN', 'PM']

/**
 * 상태 전이표 (docs/03 F-2 — 백엔드 EquipmentStatus 와 동일).
 *   RUN↔IDLE / RUN·IDLE→DOWN / RUN·IDLE→PM / PM→IDLE
 *   DOWN→IDLE(전 역할, 사유 필수) / DOWN→RUN(ENGINEER+, 사유 필수) / DOWN→PM(정비 병행 정기 PM)
 * PM→RUN 은 후보에 없다(정비 후 시운전 대기를 거쳐야 하는 현장 원칙).
 * 화면에서는 후보 버튼을 좁히는 UX 용도로만 쓰고, 최종 검증은 항상 서버(400 INVALID_STATUS_TRANSITION / 403 FORBIDDEN)가 한다.
 */
export const ALLOWED_STATUS_TRANSITIONS: Record<EquipmentStatus, EquipmentStatus[]> = {
  RUN: ['IDLE', 'DOWN', 'PM'],
  IDLE: ['RUN', 'DOWN', 'PM'],
  DOWN: ['IDLE', 'RUN', 'PM'],
  PM: ['IDLE'],
}

/** 사유(reason) 필수 전이 — DOWN 이탈(복귀) 시 조치 내용을 남긴다 (400 VALIDATION_ERROR) */
export function isReasonRequired(from: EquipmentStatus, to: EquipmentStatus): boolean {
  return from === 'DOWN' && (to === 'IDLE' || to === 'RUN')
}

/**
 * 역할별 전환 후보 (docs/03 F-2, docs/06 PATCH /equipments/{id}/status).
 * - ADMIN/ENGINEER: 전이표 전체
 * - TECHNICIAN: DOWN→IDLE 만 (DOWN→RUN 은 시운전 생략이라 불가 — 후보로 노출하지 않는다)
 */
export function getStatusCandidates(from: EquipmentStatus, role: UserRole | undefined): EquipmentStatus[] {
  const all = ALLOWED_STATUS_TRANSITIONS[from] ?? []
  if (role === 'ADMIN' || role === 'ENGINEER') return all
  if (role === 'TECHNICIAN') return from === 'DOWN' ? all.filter((to) => to === 'IDLE') : []
  return []
}

// ---------- KPI (FR-5.7, docs/03 F-5.4) ----------

/** 집계 기간 — KST 기준 오늘 / 이번 주(월~) / 이번 달 */
export type KpiPeriod = 'DAY' | 'WEEK' | 'MONTH'

export const KPI_PERIODS: KpiPeriod[] = ['DAY', 'WEEK', 'MONTH']

/** 기간 토글 라벨 */
export const KPI_PERIOD_LABEL: Record<KpiPeriod, string> = {
  DAY: '오늘',
  WEEK: '이번 주',
  MONTH: '이번 달',
}

/**
 * KPI 수치 묶음.
 * null 의미: mtbfHours=null → DOWN 0회('고장 없음') / mttrMin=null → 완료된 DOWN 없음('-') / availability=null → 집계 대상 시간 없음('-')
 */
export interface KpiValues {
  /** MTBF (시간) = Σ RUN 시간 / DOWN 횟수 */
  mtbfHours: number | null
  /** MTTR (분) = DOWN 진입~이탈 평균 (진행 중 DOWN 제외) */
  mttrMin: number | null
  /** 가동률 0~1 비율 = RUN / (전체 − PM) */
  availability: number | null
  /** 기간 내 DOWN 진입 횟수 */
  downCount: number
}

/** GET /equipments/{id}/kpi */
export interface EquipmentKpi extends KpiValues {
  equipmentId: number
  period: KpiPeriod
  /** ISO-8601 UTC */
  periodStart: string
  periodEnd: string
}

/** GET /equipments/kpi 의 설비별 행 */
export interface EquipmentKpiRow extends KpiValues {
  equipmentId: number
  equipmentCode: string
  equipmentName: string
}

/** GET /equipments/kpi — summary 는 합산 후 재계산한 라인 전체 값 */
export interface LineKpi {
  period: KpiPeriod
  periodStart: string
  periodEnd: string
  summary: KpiValues
  equipments: EquipmentKpiRow[]
}

/** '집계할 데이터 자체가 없음' 판정 — 가동률 null(집계 대상 시간 없음) + DOWN 0회 */
export function isKpiEmpty(values: KpiValues): boolean {
  return values.availability === null && values.downCount === 0
}
