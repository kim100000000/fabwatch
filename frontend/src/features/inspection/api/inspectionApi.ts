import { apiClient } from '@/shared/api'
import type { PageResponse } from '@/shared/api'
import type {
  ChecklistItem,
  Inspection,
  InspectionCreateRequest,
  InspectionDetail,
  InspectionListFilter,
  InspectionUpdateRequest,
  PmSchedule,
  PmScheduleQuery,
  PmScheduleUpdateRequest,
} from '../types'

/** GET /inspections — 점검 이력 목록 (최신순, 목록 공통 래핑) */
export async function fetchInspections(
  filter: InspectionListFilter,
  signal?: AbortSignal,
): Promise<PageResponse<Inspection>> {
  const { data } = await apiClient.get<PageResponse<Inspection>>('/inspections', {
    params: {
      equipmentId: filter.equipmentId ?? undefined,
      type: filter.type ?? undefined,
      shift: filter.shift ?? undefined,
      workerId: filter.workerId ?? undefined,
      hasNg: filter.hasNg ?? undefined,
      from: filter.from ?? undefined,
      to: filter.to ?? undefined,
      page: filter.page ?? 0,
      size: filter.size ?? 20,
    },
    signal,
  })
  return data
}

/** GET /inspections/{id} — 상세 (체크리스트 결과 포함) */
export async function fetchInspectionDetail(
  inspectionId: number,
  signal?: AbortSignal,
): Promise<InspectionDetail> {
  const { data } = await apiClient.get<InspectionDetail>(`/inspections/${inspectionId}`, { signal })
  return data
}

/**
 * POST /inspections — 등록 (201).
 * 400 CAUSE_4M_REQUIRED / 400 VALIDATION_ERROR(종료<=시작, 미래 시각) / 403 FORBIDDEN.
 * BM 은 서버가 설비 상태를 바꾸지 않는다. 알람 연계 시 서버가 알람을 RESOLVED 처리한다.
 */
export async function createInspection(request: InspectionCreateRequest): Promise<InspectionDetail> {
  const { data } = await apiClient.post<InspectionDetail>('/inspections', request)
  return data
}

/** PUT /inspections/{id} — 수정 (작성자 본인 또는 ENGINEER+) */
export async function updateInspection(
  inspectionId: number,
  request: InspectionUpdateRequest,
): Promise<InspectionDetail> {
  const { data } = await apiClient.put<InspectionDetail>(`/inspections/${inspectionId}`, request)
  return data
}

/** PATCH /inspections/{id}/review — 엔지니어 승인 (ENGINEER+) */
export async function reviewInspection(inspectionId: number): Promise<InspectionDetail> {
  const { data } = await apiClient.patch<InspectionDetail>(`/inspections/${inspectionId}/review`)
  return data
}

/** GET /equipments/{id}/checklist — PM 체크리스트 템플릿 (단순 배열) */
export async function fetchChecklist(
  equipmentId: number,
  signal?: AbortSignal,
): Promise<ChecklistItem[]> {
  const { data } = await apiClient.get<ChecklistItem[]>(`/equipments/${equipmentId}/checklist`, { signal })
  return data
}

/** GET /pm-schedules — 단순 배열 (overdueOnly / equipmentId 필터) */
export async function fetchPmSchedules(
  query: PmScheduleQuery,
  signal?: AbortSignal,
): Promise<PmSchedule[]> {
  const { data } = await apiClient.get<PmSchedule[]>('/pm-schedules', {
    params: {
      overdueOnly: query.overdueOnly ? true : undefined,
      equipmentId: query.equipmentId ?? undefined,
    },
    signal,
  })
  return data
}

/** PUT /equipments/{id}/pm-schedule — 주기 설정/변경 (ENGINEER+). nextDueAt 은 서버가 재계산한다. */
export async function updatePmSchedule(
  equipmentId: number,
  request: PmScheduleUpdateRequest,
): Promise<PmSchedule> {
  const { data } = await apiClient.put<PmSchedule>(`/equipments/${equipmentId}/pm-schedule`, request)
  return data
}
