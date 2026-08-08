import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '@/app/providers/useAuth'
import {
  EquipmentCardGrid,
  EquipmentFormDialog,
  EquipmentStatusFilter,
  EquipmentTable,
  useEquipmentList,
} from '@/features/equipment'
import type { EquipmentDetail, EquipmentStatus } from '@/features/equipment'
import { EmptyState, ErrorState, LoadingBlock } from '@/shared/ui'

type ViewMode = 'table' | 'card'

/** 열려 있는 폼: null=닫힘 / 'create'=등록 / 숫자=해당 설비 수정 */
type FormTarget = 'create' | number | null

/** S-2 설비 목록/마스터 관리 (/equipment) — GET /equipments + 등록/수정(ADMIN) */
export function EquipmentListPage() {
  const navigate = useNavigate()
  const { user } = useAuth()
  // 등록/수정은 ADMIN 전용 (docs/06 §2 권한, docs/11 §3) — 서버도 동일하게 막는다
  const canManage = user?.role === 'ADMIN'

  const [status, setStatus] = useState<EquipmentStatus | null>(null)
  const [view, setView] = useState<ViewMode>('table')
  const [formTarget, setFormTarget] = useState<FormTarget>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const { equipments, totalElements, loading, error, refetch } = useEquipmentList({
    status: status ?? undefined,
    size: 50,
  })

  const goDetail = (equipmentId: number) => navigate(`/equipment/${equipmentId}`)

  const handleSaved = (saved: EquipmentDetail, mode: 'create' | 'edit') => {
    setFormTarget(null)
    setNotice(
      mode === 'create'
        ? `설비 ${saved.code} 을(를) 등록했습니다.`
        : `설비 ${saved.code} 정보를 수정했습니다.`,
    )
    refetch()
  }

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">설비 목록</h1>
          <p className="page-subtitle">
            총 <span className="mono">{totalElements}</span>대 · 행을 클릭하면 설비 상세로 이동합니다.
          </p>
        </div>
        {canManage && (
          <button
            type="button"
            className="btn btn-primary"
            onClick={() => {
              setNotice(null)
              setFormTarget('create')
            }}
          >
            설비 등록
          </button>
        )}
      </div>

      {notice && (
        <p className="form-notice" role="status">
          {notice}
          <button type="button" onClick={() => setNotice(null)} aria-label="알림 닫기">
            ✕
          </button>
        </p>
      )}

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
          <EquipmentTable
            equipments={equipments}
            onSelect={goDetail}
            onEdit={canManage ? (equipmentId) => setFormTarget(equipmentId) : undefined}
          />
        ) : (
          <EquipmentCardGrid equipments={equipments} onSelect={goDetail} />
        )
      )}

      {canManage && formTarget !== null && (
        <EquipmentFormDialog
          equipmentId={formTarget === 'create' ? null : formTarget}
          onClose={() => setFormTarget(null)}
          onSaved={handleSaved}
        />
      )}
    </div>
  )
}
