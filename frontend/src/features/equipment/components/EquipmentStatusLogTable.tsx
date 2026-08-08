import { formatKst } from '@/shared/lib/datetime'
import { EmptyState, ErrorState, LoadingBlock, StatusBadge } from '@/shared/ui'
import { useEquipmentStatusLogs } from '../api/useEquipmentStatusLogs'
import './equipment.css'

interface EquipmentStatusLogTableProps {
  equipmentId: number
  /** 상태 변경 직후 재조회용 — 값이 바뀌면 다시 읽는다 */
  reloadKey?: number
}

/** S-3 상태 이력 탭 — GET /equipments/{id}/status-logs (최신순). 시각은 KST 변환 표시. */
export function EquipmentStatusLogTable({ equipmentId, reloadKey = 0 }: EquipmentStatusLogTableProps) {
  const { logs, loading, error, refetch } = useEquipmentStatusLogs(equipmentId, reloadKey)

  if (loading) return <LoadingBlock label="상태 이력을 불러오는 중…" />
  if (error) return <ErrorState error={error} onRetry={refetch} />
  if (logs.length === 0) {
    return <EmptyState title="상태 변경 이력이 없습니다" icon="◷" />
  }

  return (
    <div className="table-scroll">
      <table className="data-table status-log-table">
        <thead>
          <tr>
            <th>변경 일시 (KST)</th>
            <th>전 상태</th>
            <th>후 상태</th>
            <th>사유</th>
            <th>변경자</th>
          </tr>
        </thead>
        <tbody>
          {logs.map((log) => (
            <tr key={log.id}>
              <td className="mono">{formatKst(log.changedAt)}</td>
              <td>{log.fromStatus ? <StatusBadge status={log.fromStatus} /> : '-'}</td>
              <td>
                <StatusBadge status={log.toStatus} />
              </td>
              <td>{log.reason ?? '-'}</td>
              {/* changedBy 가 없으면 시스템 자동 전환 */}
              <td>{log.changedByName ?? (log.changedBy == null ? '시스템' : `#${log.changedBy}`)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
