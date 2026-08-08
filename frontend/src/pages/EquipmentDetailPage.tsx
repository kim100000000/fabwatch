import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useAuth } from '@/app/providers/useAuth'
import {
  EquipmentDetailTabs,
  EquipmentFormDialog,
  EquipmentInfoPanel,
  EquipmentStatusDialog,
  useEquipmentDetail,
} from '@/features/equipment'
import { ErrorState, LoadingBlock, StatusBadge } from '@/shared/ui'

/** S-3 설비 상세 (/equipment/:id) — GET /equipments/{id} + 수정(ADMIN) / 상태 변경(ADMIN·ENGINEER) */
export function EquipmentDetailPage() {
  const navigate = useNavigate()
  const { id } = useParams<{ id: string }>()
  const equipmentId = id ? Number(id) : null
  const { user } = useAuth()

  // 권한: 수정=ADMIN / 상태 전환=ADMIN·ENGINEER (docs/06 §2, docs/11 §3) — 서버도 동일하게 막는다
  const canEdit = user?.role === 'ADMIN'
  const canChangeStatus = user?.role === 'ADMIN' || user?.role === 'ENGINEER'

  const { equipment, loading, error, refetch } = useEquipmentDetail(equipmentId)

  const [editOpen, setEditOpen] = useState(false)
  const [statusOpen, setStatusOpen] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  // 상태 변경 성공 시 증가 — 상태 이력 탭이 이 값을 보고 재조회한다
  const [statusLogReloadKey, setStatusLogReloadKey] = useState(0)

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
              {canChangeStatus && (
                <button
                  type="button"
                  className="btn"
                  onClick={() => {
                    setNotice(null)
                    setStatusOpen(true)
                  }}
                >
                  상태 변경
                </button>
              )}
              {canEdit && (
                <button
                  type="button"
                  className="btn"
                  onClick={() => {
                    setNotice(null)
                    setEditOpen(true)
                  }}
                >
                  수정
                </button>
              )}
              {/* AI 리포트 생성은 aireport 도메인 구현 라운드에 활성화 */}
              <button type="button" className="btn" disabled>
                AI 리포트 생성
              </button>
            </div>
          </div>

          {notice && (
            <p className="form-notice" role="status">
              {notice}
              <button type="button" onClick={() => setNotice(null)} aria-label="알림 닫기">
                ✕
              </button>
            </p>
          )}

          <EquipmentInfoPanel equipment={equipment} />
          <EquipmentDetailTabs
            equipmentId={equipment.id}
            statusLogReloadKey={statusLogReloadKey}
          />

          {canChangeStatus && statusOpen && (
            <EquipmentStatusDialog
              equipment={equipment}
              onClose={() => setStatusOpen(false)}
              onChanged={(updated) => {
                setStatusOpen(false)
                setNotice(`상태를 ${updated.status} 로 변경했습니다. 상태 이력 탭에서 확인할 수 있습니다.`)
                setStatusLogReloadKey((key) => key + 1)
                refetch()
              }}
            />
          )}

          {canEdit && editOpen && (
            <EquipmentFormDialog
              equipmentId={equipment.id}
              onClose={() => setEditOpen(false)}
              onSaved={(saved) => {
                setEditOpen(false)
                setNotice(`설비 ${saved.code} 정보를 수정했습니다.`)
                refetch()
              }}
            />
          )}
        </>
      )}
    </div>
  )
}
