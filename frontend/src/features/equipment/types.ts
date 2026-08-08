/**
 * 설비 도메인 타입 (docs/06 §2 + docs/05 테이블 정의 기준).
 * 백엔드 병렬 구현 중 — 실제 응답 shape 확정되면 이 파일을 갱신한다.
 * 존재가 불확실한 필드는 optional 로 두되 임의 변환 로직은 넣지 않는다.
 */

/** equipments.status */
export type EquipmentStatus = 'RUN' | 'IDLE' | 'DOWN' | 'PM'

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
  /** 미해결(OPEN/ACK) 알람 수 */
  openAlarmCount?: number
}

/** GET /equipments/{id} 응답에 포함되는 센서 정보 */
export interface EquipmentSensor {
  id: number
  type: SensorType
  unit: string
  warnLow?: number | null
  warnHigh?: number | null
  critLow?: number | null
  critHigh?: number | null
  /** 최신 측정값 (없을 수 있음) */
  latestValue?: number | null
  measuredAt?: string | null
}

/** GET /equipments/{id} 응답에 포함되는 PM 스케줄 */
export interface EquipmentPmSchedule {
  cycleType: PmCycleType
  cycleValue?: number | null
  lastDoneAt?: string | null
  nextDueAt: string
  /** 지연 여부 (백엔드 계산값) */
  overdue?: boolean
}

/** GET /equipments/{id} — 상세 (기본정보 + 센서 + PM스케줄 + 미해결알람수) */
export interface EquipmentDetail extends EquipmentSummary {
  /** LocalDate 'YYYY-MM-DD' (시각 아님 — 날짜 입력 필드에 그대로 쓴다) */
  installedAt?: string | null
  note?: string | null
  sensors?: EquipmentSensor[]
  pmSchedule?: EquipmentPmSchedule | null
  openAlarmCount?: number
}

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

/** PATCH /equipments/{id}/status 요청 (ADMIN·ENGINEER) */
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
 * 화면에서는 후보 버튼을 좁히는 UX 용도로만 쓰고, 최종 검증은 항상 서버(400 INVALID_STATUS_TRANSITION)가 한다.
 */
export const ALLOWED_STATUS_TRANSITIONS: Record<EquipmentStatus, EquipmentStatus[]> = {
  RUN: ['IDLE', 'DOWN', 'PM'],
  IDLE: ['RUN', 'DOWN', 'PM'],
  DOWN: ['PM'],
  PM: ['IDLE'],
}
