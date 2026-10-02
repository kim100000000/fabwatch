import { Link, useNavigate } from 'react-router-dom'
import { formatKst } from '@/shared/lib/datetime'
import { ReportStatusBadge } from './ReportStatusBadge'
import { orDash } from '../types'
import type { AiReportSummary } from '../types'
import './report.css'

interface ReportTableProps {
  reports: AiReportSummary[]
}

/** S-7 리포트 목록 테이블 — 행 클릭(Enter) → /reports/:id. 시각은 KST. */
export function ReportTable({ reports }: ReportTableProps) {
  const navigate = useNavigate()

  return (
    <div className="table-scroll">
      <table className="data-table report-table">
        <thead>
          <tr>
            <th>제목</th>
            <th>설비</th>
            <th>상태</th>
            <th>생성자</th>
            <th>생성 일시 (KST)</th>
            <th>확정 일시 (KST)</th>
          </tr>
        </thead>
        <tbody>
          {reports.map((report) => (
            <tr
              key={report.id}
              tabIndex={0}
              onClick={() => navigate(`/reports/${report.id}`)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') navigate(`/reports/${report.id}`)
              }}
            >
              <td className="report-title-cell">
                <Link to={`/reports/${report.id}`} onClick={(event) => event.stopPropagation()}>
                  {report.title}
                </Link>
              </td>
              <td className="mono">
                {orDash(report.equipmentCode)} <span className="report-sub">{report.equipmentName ?? ''}</span>
              </td>
              <td>
                <ReportStatusBadge status={report.status} />
              </td>
              <td>{orDash(report.createdByName)}</td>
              <td className="mono">{formatKst(report.createdAt)}</td>
              <td className="mono">{formatKst(report.confirmedAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
