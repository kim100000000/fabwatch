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
  installedAt?: string | null
  sensors?: EquipmentSensor[]
  pmSchedule?: EquipmentPmSchedule | null
  openAlarmCount?: number
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
