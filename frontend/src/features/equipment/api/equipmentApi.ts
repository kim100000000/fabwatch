import { apiClient } from '@/shared/api'
import type { PageResponse } from '@/shared/api'
import type {
  EquipmentCreateRequest,
  EquipmentDetail,
  EquipmentListFilter,
  EquipmentStatusChangeRequest,
  EquipmentStatusLog,
  EquipmentSummary,
  EquipmentUpdateRequest,
  LineTree,
} from '../types'

/**
 * GET /equipments — 목록 (docs/06 §2)
 * 응답은 { content, totalElements, totalPages, number } 래핑.
 */
export async function fetchEquipments(
  filter: EquipmentListFilter,
  signal?: AbortSignal,
): Promise<PageResponse<EquipmentSummary>> {
  const { data } = await apiClient.get<PageResponse<EquipmentSummary>>('/equipments', {
    params: {
      processId: filter.processId,
      status: filter.status,
      page: filter.page ?? 0,
      size: filter.size ?? 20,
    },
    signal,
  })
  return data
}

/** GET /equipments/{id} — 상세 (기본정보 + 센서 + PM스케줄 + 미해결알람수) */
export async function fetchEquipmentDetail(
  equipmentId: number,
  signal?: AbortSignal,
): Promise<EquipmentDetail> {
  const { data } = await apiClient.get<EquipmentDetail>(`/equipments/${equipmentId}`, { signal })
  return data
}

/**
 * POST /equipments — 설비 등록 (ADMIN). 201 로 상세를 돌려준다.
 * 409 DUPLICATE_EQUIPMENT_CODE / 404 NOT_FOUND(공정) / 400 VALIDATION_ERROR
 */
export async function createEquipment(request: EquipmentCreateRequest): Promise<EquipmentDetail> {
  const { data } = await apiClient.post<EquipmentDetail>('/equipments', request)
  return data
}

/** PUT /equipments/{id} — 설비 수정 (ADMIN). code/status 는 바꿀 수 없다. */
export async function updateEquipment(
  equipmentId: number,
  request: EquipmentUpdateRequest,
): Promise<EquipmentDetail> {
  const { data } = await apiClient.put<EquipmentDetail>(`/equipments/${equipmentId}`, request)
  return data
}

/**
 * PATCH /equipments/{id}/status — 상태 전환 (ENGINEER+, DOWN→IDLE 은 전 역할).
 * 상태 머신 밖의 전이는 400 INVALID_STATUS_TRANSITION (message 에 허용 전이 목록 포함).
 */
export async function changeEquipmentStatus(
  equipmentId: number,
  request: EquipmentStatusChangeRequest,
): Promise<EquipmentDetail> {
  const { data } = await apiClient.patch<EquipmentDetail>(
    `/equipments/${equipmentId}/status`,
    request,
  )
  return data
}

/** GET /equipments/{id}/status-logs — 상태 변경 이력 (최신순, 목록 공통 래핑) */
export async function fetchEquipmentStatusLogs(
  equipmentId: number,
  signal?: AbortSignal,
): Promise<PageResponse<EquipmentStatusLog>> {
  const { data } = await apiClient.get<PageResponse<EquipmentStatusLog>>(
    `/equipments/${equipmentId}/status-logs`,
    { params: { page: 0, size: 20 }, signal },
  )
  return data
}

/**
 * GET /lines — 라인+공정+설비 트리 (헤더 라인 선택용)
 * 페이징은 없지만 백엔드가 목록 공통 포맷 { content, ... } 으로 감싸 보낸다 (docs/06 목록 공통 규칙).
 * 배열이 아니므로 반드시 content 를 꺼낸다.
 */
export async function fetchLines(signal?: AbortSignal): Promise<LineTree[]> {
  const { data } = await apiClient.get<PageResponse<LineTree>>('/lines', { signal })
  return data.content
}
