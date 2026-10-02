import { useState } from 'react'
import { Link } from 'react-router-dom'
import { formatKst } from '@/shared/lib/datetime'
import { EmptyState, ErrorState, LoadingBlock, Pagination } from '@/shared/ui'
import { useAiReportList } from '../api/useAiReportList'
import { orDash } from '../types'
import { EquipmentReportCreateButton } from './EquipmentReportCreateButton'
import { ReportStatusBadge } from './ReportStatusBadge'
import './report.css'

interface EquipmentReportTabPanelProps {
  equipmentId: number
}

const TAB_PAGE_SIZE = 10

/**
 * S-3 리포트 탭 (docs/04 §3 탭4) — 해당 설비 리포트 카드 목록 + 상단 'AI 리포트 생성' 버튼(ENGINEER+).
 * 카드 클릭 → /reports/:id
 */
export function EquipmentReportTabPanel({ equipmentId }: EquipmentReportTabPanelProps) {
  const [page, setPage] = useState(0)
  const { reports, loading, error, totalElements, totalPages, page: currentPage, refetch } = useAiReportList({
    equipmentId,
    page,
    size: TAB_PAGE_SIZE,
  })

  return (
    <div>
      <div className="tab-toolbar">
        <span className="field-hint">이 설비의 AI 고장 리포트 (최신순)</span>
        <EquipmentReportCreateButton equipmentId={equipmentId} showHint />
      </div>

      {loading && reports.length === 0 && <LoadingBlock label="리포트를 불러오는 중…" />}
      {error && reports.length === 0 && <ErrorState error={error} onRetry={refetch} />}
      {!loading && !error && reports.length === 0 && (
        <EmptyState
          title="이 설비의 AI 리포트가 없습니다"
          description="미해결 알람이 있을 때 'AI 리포트 생성'으로 고장 리포트 초안을 만들 수 있습니다."
          icon="✎"
        />
      )}

      {reports.length > 0 && (
        <>
          <ul className="report-card-list">
            {reports.map((report) => (
              <li key={report.id}>
                <Link to={`/reports/${report.id}`} className="report-card">
                  <div className="report-card-head">
                    <span className="report-card-title">{report.title}</span>
                    <ReportStatusBadge status={report.status} />
                  </div>
                  <div className="report-card-meta">
                    <span>{orDash(report.createdByName)}</span>
                    <span className="mono">생성 {formatKst(report.createdAt)}</span>
                    {report.confirmedAt && <span className="mono">확정 {formatKst(report.confirmedAt)}</span>}
                  </div>
                </Link>
              </li>
            ))}
          </ul>
          <Pagination
            page={currentPage}
            totalPages={totalPages}
            totalElements={totalElements}
            onChange={setPage}
          />
        </>
      )}
    </div>
  )
}
