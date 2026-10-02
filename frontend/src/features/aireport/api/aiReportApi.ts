import { apiClient } from '@/shared/api'
import type { PageResponse } from '@/shared/api'
import type {
  AiReport,
  AiReportAccepted,
  AiReportCreateRequest,
  AiReportListFilter,
  AiReportSummary,
} from '../types'

/** GET /ai-reports — 목록 (최신순, 목록 공통 래핑) */
export async function fetchAiReports(
  filter: AiReportListFilter,
  signal?: AbortSignal,
): Promise<PageResponse<AiReportSummary>> {
  const { data } = await apiClient.get<PageResponse<AiReportSummary>>('/ai-reports', {
    params: {
      equipmentId: filter.equipmentId ?? undefined,
      status: filter.status ?? undefined,
      alarmId: filter.alarmId ?? undefined,
      inspectionId: filter.inspectionId ?? undefined,
      page: filter.page ?? 0,
      size: filter.size ?? 20,
    },
    signal,
  })
  return data
}

/** GET /ai-reports/{id} — 상세 (GENERATING 폴링에도 사용) */
export async function fetchAiReport(reportId: number, signal?: AbortSignal): Promise<AiReport> {
  const { data } = await apiClient.get<AiReport>(`/ai-reports/${reportId}`, { signal })
  return data
}

/**
 * POST /ai-reports — 생성 요청 (202, 비동기).
 * 에러: 400 VALIDATION_ERROR / 403 AI_QUOTA_EXCEEDED·FORBIDDEN / 404 NOT_FOUND / 409 ALREADY_GENERATING
 */
export async function createAiReport(request: AiReportCreateRequest): Promise<AiReportAccepted> {
  const { data } = await apiClient.post<AiReportAccepted>('/ai-reports', request)
  return data
}

/** PUT /ai-reports/{id} — 확정본(finalContent) 저장. DRAFT / FAILED(수동 작성)에서만, 그 외 409 INVALID_REPORT_STATE */
export async function updateAiReport(reportId: number, finalContent: string): Promise<AiReport> {
  const { data } = await apiClient.put<AiReport>(`/ai-reports/${reportId}`, { finalContent })
  return data
}

/** PATCH /ai-reports/{id}/confirm — 확정. 이미 CONFIRMED/GENERATING 이면 409 INVALID_REPORT_STATE */
export async function confirmAiReport(reportId: number): Promise<AiReport> {
  const { data } = await apiClient.patch<AiReport>(`/ai-reports/${reportId}/confirm`)
  return data
}

/** POST /ai-reports/{id}/retry — FAILED 만 (202, 같은 reportId 재사용). 쿼터 초과 시 403 AI_QUOTA_EXCEEDED */
export async function retryAiReport(reportId: number): Promise<AiReportAccepted> {
  const { data } = await apiClient.post<AiReportAccepted>(`/ai-reports/${reportId}/retry`)
  return data
}
