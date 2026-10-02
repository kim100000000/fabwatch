/**
 * 점검 이력 도메인 타입 (docs/06 §4·§5 + 백엔드 확정 계약).
 * 서버는 시각을 UTC ISO-8601 로 주고받는다 — 화면 표시는 KST 변환.
 */

import type { Shift } from '@/shared/lib/datetime'

export type { Shift }

/** inspections.type */
export type InspectionType = 'PM' | 'BM'

/** inspections.cause_4m — BM 일 때 필수 */
export type Cause4m = 'MAN' | 'MACHINE' | 'MATERIAL' | 'METHOD'

/** 체크리스트 판정 */
export type CheckResultValue = 'OK' | 'NG' | 'NA'

/** PM 스케줄 주기 */
export type PmCycleType = 'DAILY' | 'WEEKLY' | 'MONTHLY'

/** GET /inspections — 목록 항목 */
export interface Inspection {
  id: number
  equipmentId: number
  equipmentCode: string
  equipmentName: string
  type: InspectionType
  shift: Shift
  workerId: number
  workerName: string
  startedAt: string
  endedAt: string
  durationMin: number
  content: string
  actionTaken?: string | null
  cause4m?: Cause4m | null
  causeDetail?: string | null
  /** 연계 알람 (BM) */
  alarmId?: number | null
  /** 체크리스트에 NG 가 1건이라도 있으면 true — 엔지니어 확인 대상 */
  hasNg: boolean
  reviewedBy?: number | null
  reviewedByName?: string | null
  reviewedAt?: string | null
  createdAt: string
}

/** 상세 응답의 체크리스트 결과 한 줄 */
export interface InspectionCheckResult {
  checklistItemId: number
  itemName: string
  criteria?: string | null
  result: CheckResultValue
  note?: string | null
}

/** GET /inspections/{id}, POST·PUT·PATCH 응답 */
export interface InspectionDetail extends Inspection {
  checkResults: InspectionCheckResult[]
}

/** GET /inspections 쿼리 */
export interface InspectionListFilter {
  equipmentId?: number | null
  type?: InspectionType | null
  shift?: Shift | null
  workerId?: number | null
  hasNg?: boolean | null
  /** UTC ISO */
  from?: string | null
  to?: string | null
  page?: number
  size?: number
}

/** 요청 본문의 체크리스트 결과 */
export interface CheckResultInput {
  checklistItemId: number
  result: CheckResultValue
  note?: string | null
}

/** POST /inspections 요청 — workerId 는 보내지 않는다(서버가 로그인 사용자로 채움) */
export interface InspectionCreateRequest {
  equipmentId: number
  type: InspectionType
  shift?: Shift
  startedAt: string
  endedAt: string
  content: string
  actionTaken?: string | null
  cause4m?: Cause4m | null
  causeDetail?: string | null
  alarmId?: number | null
  checkResults?: CheckResultInput[]
}

/** PUT /inspections/{id} 요청 — 설비·유형·알람은 불변 */
export interface InspectionUpdateRequest {
  startedAt: string
  endedAt: string
  content: string
  actionTaken?: string | null
  cause4m?: Cause4m | null
  causeDetail?: string | null
  checkResults?: CheckResultInput[]
}

/** GET /equipments/{id}/checklist 항목 (조회 전용 — 템플릿 관리 UI 는 이번 범위 아님) */
export interface ChecklistItem {
  id: number
  itemName: string
  criteria?: string | null
  seq: number
  active: boolean
}

/** GET /pm-schedules 항목 */
export interface PmSchedule {
  id: number
  equipmentId: number
  equipmentCode: string
  equipmentName: string
  cycleType: PmCycleType
  /** DAILY 는 null, WEEKLY 1~7, MONTHLY 1~28 */
  cycleValue: number | null
  lastDoneAt?: string | null
  nextDueAt: string
  overdue: boolean
  overdueDays: number
}

/** PUT /equipments/{id}/pm-schedule 요청 (ENGINEER+) */
export interface PmScheduleUpdateRequest {
  cycleType: PmCycleType
  cycleValue: number | null
}

export interface PmScheduleQuery {
  overdueOnly?: boolean
  equipmentId?: number | null
}

/** 화면 필터 상태 — 기간은 date input 값(KST 'YYYY-MM-DD') */
export interface InspectionFilterValue {
  equipmentId: number | null
  type: InspectionType | null
  shift: Shift | null
  /** true 면 로그인 사용자 본인 점검만 (작업자 목록 API 가 없어 '내 점검' 토글로 대체) */
  mineOnly: boolean
  hasNg: boolean
  fromDate: string
  toDate: string
}

export const EMPTY_INSPECTION_FILTER: InspectionFilterValue = {
  equipmentId: null,
  type: null,
  shift: null,
  mineOnly: false,
  hasNg: false,
  fromDate: '',
  toDate: '',
}

export const INSPECTION_TYPES: InspectionType[] = ['PM', 'BM']

export const CAUSE_4M_OPTIONS: Cause4m[] = ['MAN', 'MACHINE', 'MATERIAL', 'METHOD']

/** 4M 한국어 라벨 (API 값은 그대로 유지) */
export const CAUSE_4M_LABEL: Record<Cause4m, string> = {
  MAN: '사람',
  MACHINE: '설비',
  MATERIAL: '자재',
  METHOD: '방법',
}

export const CHECK_RESULT_OPTIONS: CheckResultValue[] = ['OK', 'NG', 'NA']

export const CHECK_RESULT_LABEL: Record<CheckResultValue, string> = {
  OK: 'OK',
  NG: 'NG',
  NA: 'N/A',
}

export const SHIFT_LABEL: Record<Shift, string> = {
  D: 'D (주간)',
  N: 'N (야간)',
}

export const PM_CYCLE_LABEL: Record<PmCycleType, string> = {
  DAILY: '매일',
  WEEKLY: '매주',
  MONTHLY: '매월',
}

/** WEEKLY cycleValue(1~7) 요일 라벨 — 1=월 … 7=일 (ISO 요일 가정) */
export const WEEKDAY_LABEL: Record<number, string> = {
  1: '월',
  2: '화',
  3: '수',
  4: '목',
  5: '금',
  6: '토',
  7: '일',
}

/** 주기 요약 문구 — 예) '매주 수요일', '매월 15일', '매일' */
export function describeCycle(cycleType: PmCycleType, cycleValue: number | null | undefined): string {
  if (cycleType === 'DAILY') return '매일'
  if (cycleType === 'WEEKLY') {
    const day = cycleValue != null ? WEEKDAY_LABEL[cycleValue] : undefined
    return day ? `매주 ${day}요일` : '매주'
  }
  return cycleValue != null ? `매월 ${cycleValue}일` : '매월'
}

/** 소요시간 24시간 초과 경고 기준 (docs/03 3.1 — 경고만, 입력은 허용) */
export const LONG_DURATION_WARN_MIN = 24 * 60
