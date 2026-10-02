/**
 * AI 리포트 도메인 타입 (docs/06 §7 + 백엔드 확정 계약).
 * 서버는 시각을 UTC ISO-8601 로 주고받는다 — 화면 표시는 KST 변환.
 *
 * ★ AI 원본(draftContent)은 서버에서 절대 수정되지 않는다 — 편집은 finalContent 에만 한다 (docs/03 6.3).
 */

import type { UserRole } from '@/features/auth'

/** ai_reports.status — GENERATING → DRAFT → CONFIRMED / GENERATING → FAILED (재시도 시 FAILED → GENERATING) */
export type AiReportStatus = 'GENERATING' | 'DRAFT' | 'CONFIRMED' | 'FAILED'

/** GET /ai-reports — 목록 항목 */
export interface AiReportSummary {
  id: number
  equipmentId: number
  /** 설비가 삭제됐으면 서버가 null — 표시 시 '-' 처리 */
  equipmentCode: string | null
  equipmentName: string | null
  alarmId?: number | null
  inspectionId?: number | null
  title: string
  status: AiReportStatus
  /** 시스템/삭제 사용자면 null */
  createdByName: string | null
  /** UTC ISO */
  createdAt: string
  confirmedAt?: string | null
}

/** GET /ai-reports/{id}, PUT·PATCH 응답 */
export interface AiReport {
  id: number
  equipmentId: number
  /** 설비가 삭제됐으면 서버가 null — 표시 시 '-' 처리 */
  equipmentCode: string | null
  equipmentName: string | null
  alarmId?: number | null
  inspectionId?: number | null
  title: string
  status: AiReportStatus
  /** AI 원본 (읽기 전용) */
  draftContent?: string | null
  /** 사람이 수정·확정한 본문 */
  finalContent?: string | null
  failReason?: string | null
  model?: string | null
  promptTokens?: number | null
  completionTokens?: number | null
  /** 시스템/삭제 사용자면 null */
  createdBy: number | null
  createdByName: string | null
  confirmedBy?: number | null
  confirmedByName?: string | null
  createdAt: string
  confirmedAt?: string | null
}

/** GET /ai-reports 쿼리 필터 */
export interface AiReportListFilter {
  equipmentId?: number | null
  status?: AiReportStatus | null
  alarmId?: number | null
  inspectionId?: number | null
  page?: number
  size?: number
}

/** POST /ai-reports 요청 — alarmId 또는 inspectionId 중 하나 */
export interface AiReportCreateRequest {
  alarmId?: number
  inspectionId?: number
}

/** POST /ai-reports, POST /ai-reports/{id}/retry 응답 (202 Accepted) */
export interface AiReportAccepted {
  reportId: number
  status: 'GENERATING'
}

/** 화면 필터 상태 */
export interface ReportFilterValue {
  equipmentId: number | null
  status: AiReportStatus | null
}

export const EMPTY_REPORT_FILTER: ReportFilterValue = { equipmentId: null, status: null }

export const AI_REPORT_STATUSES: AiReportStatus[] = ['GENERATING', 'DRAFT', 'CONFIRMED', 'FAILED']

/** 상태 라벨 (API 값은 그대로 유지) */
export const AI_REPORT_STATUS_LABEL: Record<AiReportStatus, string> = {
  GENERATING: '생성 중',
  DRAFT: '초안',
  CONFIRMED: '확정',
  FAILED: '실패',
}

/** 확정본(finalContent) 최대 길이 — 서버 AiReportUpdateRequest @Size(max=100_000) 와 동일 */
export const FINAL_CONTENT_MAX_LENGTH = 100_000

/** null/빈 문자열 표시값 */
export function orDash(value: string | null | undefined): string {
  return value && value.trim() ? value : '-'
}

/** 생성·편집·확정·재시도 권한 — ENGINEER+ (TECHNICIAN 에게는 관련 UI 를 노출하지 않는다) */
export function canManageReport(role: UserRole | undefined | null): boolean {
  return role === 'ADMIN' || role === 'ENGINEER'
}

/**
 * 생성 대상 — 알람 또는 BM 점검 이력.
 * 점검 대상이면 linkedAlarmId(점검에 연계된 알람)를 함께 넘겨 알람 경로로 만든 기존 리포트도 찾는다 (중복 생성 방지).
 */
export type ReportTarget =
  | { alarmId: number; inspectionId?: undefined; linkedAlarmId?: undefined }
  | { inspectionId: number; alarmId?: undefined; linkedAlarmId?: number | null }
