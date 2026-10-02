import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { EmptyState, ErrorState, LoadingBlock, Pagination } from '@/shared/ui'
import { useInspectionList } from '../api/useInspectionList'
import { usePmSchedules } from '../api/usePmSchedules'
import { InspectionDetailDialog } from './InspectionDetailDialog'
import { InspectionTable } from './InspectionTable'
import { PmScheduleCard } from './PmScheduleCard'
import './inspection.css'

interface EquipmentInspectionTabPanelProps {
  equipmentId: number
}

const TAB_PAGE_SIZE = 10

/**
 * S-3 점검 탭 (docs/04 §3 탭2) — 해당 설비 점검 이력(최신순) + PM 스케줄 카드.
 * [점검 등록] 은 /inspections/new?equipmentId= 로 설비를 사전 선택해 이동한다.
 */
export function EquipmentInspectionTabPanel({ equipmentId }: EquipmentInspectionTabPanelProps) {
  const navigate = useNavigate()
  const [page, setPage] = useState(0)
  const [reloadKey, setReloadKey] = useState(0)
  const [selectedId, setSelectedId] = useState<number | null>(null)

  const { inspections, loading, error, page: currentPage, totalPages, totalElements, refetch } =
    useInspectionList({ equipmentId, page, size: TAB_PAGE_SIZE }, reloadKey)
  const pm = usePmSchedules({ equipmentId })

  return (
    <div>
      <PmScheduleCard
        key={pm.schedules[0]?.id ?? 'none'}
        equipmentId={equipmentId}
        schedule={pm.schedules[0] ?? null}
        onSaved={pm.refetch}
      />
      {pm.error && (
        <p className="form-error" role="alert">
          PM 스케줄을 불러오지 못했습니다.
        </p>
      )}

      <div className="tab-toolbar">
        <span className="field-hint">최근 점검 이력 (최신순)</span>
        <button
          type="button"
          className="btn btn-primary"
          onClick={() => navigate(`/inspections/new?equipmentId=${equipmentId}`)}
        >
          점검 등록
        </button>
      </div>

      {loading && inspections.length === 0 && <LoadingBlock label="점검 이력을 불러오는 중…" />}
      {error && inspections.length === 0 && <ErrorState error={error} onRetry={refetch} />}
      {!loading && !error && inspections.length === 0 && (
        <EmptyState title="이 설비의 점검 이력이 없습니다" icon="✎" />
      )}

      {inspections.length > 0 && (
        <>
          <InspectionTable
            inspections={inspections}
            hideEquipment
            onSelect={(inspection) => setSelectedId(inspection.id)}
          />
          <Pagination
            page={currentPage}
            totalPages={totalPages}
            totalElements={totalElements}
            onChange={setPage}
          />
        </>
      )}

      {selectedId !== null && (
        <InspectionDetailDialog
          key={selectedId}
          inspectionId={selectedId}
          onClose={() => setSelectedId(null)}
          onChanged={() => {
            setReloadKey((key) => key + 1)
            pm.refetch()
          }}
        />
      )}
    </div>
  )
}
