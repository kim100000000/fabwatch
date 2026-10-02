import { AI_REPORT_STATUS_LABEL } from '../types'
import type { AiReportStatus } from '../types'
import './report.css'

interface ReportStatusBadgeProps {
  status: AiReportStatus
}

/** 리포트 상태 배지 — GENERATING 회색(스피너) / DRAFT 노랑 / CONFIRMED 초록 / FAILED 빨강 (docs/04 S-3 탭4·S-7) */
export function ReportStatusBadge({ status }: ReportStatusBadgeProps) {
  return (
    <span className="report-status" data-status={status}>
      {status === 'GENERATING' && <span className="spinner report-status-spinner" aria-hidden="true" />}
      {AI_REPORT_STATUS_LABEL[status] ?? status}
    </span>
  )
}
