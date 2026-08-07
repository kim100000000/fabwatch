import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  EquipmentCardGrid,
  EquipmentStatusFilter,
  EquipmentTable,
  useEquipmentList,
} from '@/features/equipment'
import type { EquipmentStatus } from '@/features/equipment'
import { EmptyState, ErrorState, LoadingBlock } from '@/shared/ui'

type ViewMode = 'table' | 'card'

/** S-2 설비 목록/마스터 관리 (/equipment) — GET /equipments */
export function EquipmentListPage() {
  const navigate = useNavigate()
  const [status, setStatus] = useState<EquipmentStatus | null>(null)
  const [view, setView] = useState<ViewMode>('table')

  const { equipments, totalElements, loading, error, refetch } = useEquipmentList({
    status: status ?? undefined,
    size: 50,
  })

  const goDetail = (equipmentId: number) => navigate(`/equipment/${equipmentId}`)

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">설비 목록</h1>
          <p className="page-subtitle">
            총 <span className="mono">{totalElements}</span>대 · 행을 클릭하면 설비 상세로 이동합니다.
          </p>
        </div>
      </div>

      <div className="equipment-toolbar">
        <EquipmentStatusFilter value={status} onChange={setStatus} />
        <div className="view-toggle" role="group" aria-label="보기 방식">
          <button
            type="button"
            className={view === 'table' ? 'active' : undefined}
            onClick={() => setView('table')}
          >
            테이블
          </button>
          <button
            type="button"
            className={view === 'card' ? 'active' : undefined}
            onClick={() => setView('card')}
          >
            카드
          </button>
        </div>
      </div>

      {loading && <LoadingBlock label="설비 목록을 불러오는 중…" />}

      {!loading && error && <ErrorState error={error} onRetry={refetch} />}

      {!loading && !error && equipments.length === 0 && (
        <EmptyState
          title="표시할 설비가 없습니다"
          description={status ? `상태 ${status} 에 해당하는 설비가 없습니다.` : '설비 마스터를 먼저 등록하세요.'}
        />
      )}

      {!loading && !error && equipments.length > 0 && (
        view === 'table' ? (
          <EquipmentTable equipments={equipments} onSelect={goDetail} />
        ) : (
          <EquipmentCardGrid equipments={equipments} onSelect={goDetail} />
        )
      )}
    </div>
  )
}
