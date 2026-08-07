import { apiClient } from '@/shared/api'
import type { PageResponse } from '@/shared/api'
import type { EquipmentDetail, EquipmentListFilter, EquipmentSummary, LineTree } from '../types'

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
 * GET /lines — 라인+공정+설비 트리 (헤더 라인 선택용)
 * 페이징은 없지만 백엔드가 목록 공통 포맷 { content, ... } 으로 감싸 보낸다 (docs/06 목록 공통 규칙).
 * 배열이 아니므로 반드시 content 를 꺼낸다.
 */
export async function fetchLines(signal?: AbortSignal): Promise<LineTree[]> {
  const { data } = await apiClient.get<PageResponse<LineTree>>('/lines', { signal })
  return data.content
}
