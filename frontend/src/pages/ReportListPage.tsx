import { usePageTitle } from '@/shared/hooks/usePageTitle'
import { useMemo, useState } from 'react'
import { EMPTY_REPORT_FILTER, ReportFilterBar, ReportTable, useAiReportList } from '@/features/aireport'
import type { ReportFilterValue } from '@/features/aireport'
import { useEquipmentList } from '@/features/equipment'
import { EmptyState, ErrorState, LoadingBlock, Pagination } from '@/shared/ui'

/**
 * S-7 AI 리포트 목록 (/reports)
 * - GET /ai-reports (설비·상태 필터, 최신순 20건 페이징)
 * - 행 클릭 → /reports/:id (상세/편집)
 * - 리포트 생성은 설비 상세·알람·점검 이력 화면의 진입점에서 한다
 */
export function ReportListPage() {
  usePageTitle('AI 리포트')
  const [filter, setFilter] = useState<ReportFilterValue>(EMPTY_REPORT_FILTER)
  const [page, setPage] = useState(0)

  const { equipments } = useEquipmentList({ size: 100 })

  const listFilter = useMemo(
    () => ({ equipmentId: filter.equipmentId, status: filter.status, page, size: 20 }),
    [filter, page],
  )
  const { reports, loading, error, totalElements, totalPages, page: currentPage, refetch } =
    useAiReportList(listFilter)

  const handleFilterChange = (next: ReportFilterValue) => {
    setFilter(next)
    setPage(0)
  }

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">AI 리포트</h1>
          <p className="page-subtitle">
            AI가 작성한 고장 리포트 초안을 검토·수정한 뒤 확정합니다. AI 원본은 별도로 보관됩니다.
          </p>
        </div>
        <button type="button" className="btn btn-ghost" onClick={refetch}>
          새로고침
        </button>
      </div>

      <ReportFilterBar value={filter} onChange={handleFilterChange} equipments={equipments} />

      {loading && reports.length === 0 && <LoadingBlock label="리포트를 불러오는 중…" />}

      {!loading && error && reports.length === 0 && <ErrorState error={error} onRetry={refetch} />}

      {!loading && !error && reports.length === 0 && (
        <EmptyState
          title="조건에 맞는 AI 리포트가 없습니다"
          description="설비 상세의 'AI 리포트 생성' 또는 알람 센터의 'AI 리포트' 버튼으로 만들 수 있습니다."
          icon="✎"
        />
      )}

      {reports.length > 0 && (
        <>
          <ReportTable reports={reports} />
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
