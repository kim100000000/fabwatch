import { useNavigate, useParams } from 'react-router-dom'
import { ReportDetailPanel } from '@/features/aireport'
import { EmptyState } from '@/shared/ui'

/** S-7 AI 리포트 상세/편집 (/reports/:id) */
export function ReportDetailPage() {
  const navigate = useNavigate()
  const { id } = useParams<{ id: string }>()
  const reportId = Number(id)
  const valid = Number.isInteger(reportId) && reportId > 0

  return (
    <div className="page">
      <div className="page-header">
        <button type="button" className="btn btn-ghost" onClick={() => navigate('/reports')}>
          ← 리포트 목록
        </button>
      </div>
      {valid ? (
        // id 가 바뀌면(다른 리포트로 이동) 편집·폴링 상태를 모두 새로 시작한다
        <ReportDetailPanel key={reportId} reportId={reportId} />
      ) : (
        <EmptyState title="잘못된 리포트 경로입니다" icon="✎" />
      )}
    </div>
  )
}
