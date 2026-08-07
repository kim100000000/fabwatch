import { useNavigate, useParams } from 'react-router-dom'
import {
  EquipmentDetailTabs,
  EquipmentInfoPanel,
  useEquipmentDetail,
} from '@/features/equipment'
import { ErrorState, LoadingBlock, StatusBadge } from '@/shared/ui'

/** S-3 설비 상세 (/equipment/:id) — GET /equipments/{id} + 탭 골격 */
export function EquipmentDetailPage() {
  const navigate = useNavigate()
  const { id } = useParams<{ id: string }>()
  const equipmentId = id ? Number(id) : null

  const { equipment, loading, error, refetch } = useEquipmentDetail(equipmentId)

  return (
    <div className="page">
      <div className="page-header">
        <button type="button" className="btn btn-ghost" onClick={() => navigate('/equipment')}>
          ← 설비 목록
        </button>
      </div>

      {loading && <LoadingBlock label="설비 정보를 불러오는 중…" />}

      {!loading && error && <ErrorState error={error} onRetry={refetch} />}

      {!loading && !error && equipment && (
        <>
          <div className="detail-header">
            <div className="detail-title">
              <span className="detail-code">{equipment.code}</span>
              <h1 className="page-title">{equipment.name}</h1>
              <StatusBadge status={equipment.status} />
            </div>
            <div className="equipment-toolbar" style={{ margin: 0 }}>
              {/* 상태 변경(ENGINEER+) / AI 리포트 생성 버튼은 해당 도메인 구현 라운드에 활성화 */}
              <button type="button" className="btn" disabled>
                상태 변경
              </button>
              <button type="button" className="btn" disabled>
                AI 리포트 생성
              </button>
            </div>
          </div>

          <EquipmentInfoPanel equipment={equipment} />
          <EquipmentDetailTabs />
        </>
      )}
    </div>
  )
}
