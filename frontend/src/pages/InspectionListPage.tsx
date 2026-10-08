import { usePageTitle } from '@/shared/hooks/usePageTitle'
import { useCallback, useMemo, useState } from 'react'
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { useAuth } from '@/app/providers/useAuth'
import { InspectionReportButton } from '@/features/aireport'
import { useUserLookup } from '@/features/auth'
import { useEquipmentList } from '@/features/equipment'
import {
  EMPTY_INSPECTION_FILTER,
  InspectionDetailDialog,
  InspectionFilterBar,
  InspectionTable,
  useInspectionList,
} from '@/features/inspection'
import type { InspectionFilterValue } from '@/features/inspection'
import { kstDateToUtcIso } from '@/shared/lib/datetime'
import { EmptyState, ErrorState, LoadingBlock, Pagination } from '@/shared/ui'

/**
 * S-4 점검 이력 목록/검색 (/inspections)
 * - GET /inspections (설비·기간·유형·교대조·작업자·NG 여부, 최신순 20건 페이징)
 * - 행 클릭 → 상세 모달 (승인=ENGINEER+, 수정=작성자 본인/ENGINEER+)
 * - ?id= 쿼리로 특정 이력 상세를 바로 연다 (알람 센터 해제 사유 링크용)
 * - S-5 저장 후 이동하면 location.state.notice 를 성공 배너로 보여준다
 */
export function InspectionListPage() {
  usePageTitle('점검 이력')
  const navigate = useNavigate()
  const location = useLocation()
  const [searchParams, setSearchParams] = useSearchParams()
  const { user } = useAuth()

  const [filter, setFilter] = useState<InspectionFilterValue>(EMPTY_INSPECTION_FILTER)
  const [page, setPage] = useState(0)
  const [reloadKey, setReloadKey] = useState(0)
  const [notice, setNotice] = useState<string | null>(
    (location.state as { notice?: string } | null)?.notice ?? null,
  )

  const idParam = Number(searchParams.get('id'))
  const [selectedId, setSelectedId] = useState<number | null>(
    Number.isInteger(idParam) && idParam > 0 ? idParam : null,
  )

  const { equipments } = useEquipmentList({ size: 100 })
  // 작업자 셀렉트용 사용자 목록 — 실패해도 셀렉트만 비활성화되고 나머지 필터는 동작한다
  const workerLookup = useUserLookup()

  const listFilter = useMemo(
    () => ({
      equipmentId: filter.equipmentId,
      type: filter.type,
      shift: filter.shift,
      workerId: filter.workerId,
      // NG 포함만 — 해제 상태일 때는 파라미터 자체를 보내지 않는다 (false 를 보내면 'NG 없음' 만 걸린다)
      hasNg: filter.hasNg ? true : null,
      from: kstDateToUtcIso(filter.fromDate, 'start') ?? null,
      to: kstDateToUtcIso(filter.toDate, 'end') ?? null,
      page,
      size: 20,
    }),
    [filter, page],
  )

  const { inspections, loading, error, totalElements, totalPages, page: currentPage, refetch } =
    useInspectionList(listFilter, reloadKey)

  const handleFilterChange = (next: InspectionFilterValue) => {
    setFilter(next)
    setPage(0)
  }

  const closeDetail = useCallback(() => {
    setSelectedId(null)
    if (searchParams.has('id')) {
      const next = new URLSearchParams(searchParams)
      next.delete('id')
      setSearchParams(next, { replace: true })
    }
  }, [searchParams, setSearchParams])

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">점검 이력</h1>
          <p className="page-subtitle">PM(예방점검) · BM(고장수리) 이력을 최신순으로 조회합니다.</p>
        </div>
        <button type="button" className="btn btn-primary" onClick={() => navigate('/inspections/new')}>
          점검 등록
        </button>
      </div>

      {notice && (
        <p className="form-notice" role="status">
          {notice}
          <button type="button" onClick={() => setNotice(null)} aria-label="알림 닫기">
            ✕
          </button>
        </p>
      )}

      <InspectionFilterBar
        value={filter}
        onChange={handleFilterChange}
        equipments={equipments}
        workers={workerLookup}
        currentUserId={user?.id ?? null}
      />

      {loading && inspections.length === 0 && <LoadingBlock label="점검 이력을 불러오는 중…" />}

      {!loading && error && inspections.length === 0 && <ErrorState error={error} onRetry={refetch} />}

      {!loading && !error && inspections.length === 0 && (
        <EmptyState
          title="조건에 맞는 점검 이력이 없습니다"
          description="필터를 완화하거나 기간을 넓혀 보세요."
          icon="✎"
        />
      )}

      {inspections.length > 0 && (
        <>
          <InspectionTable inspections={inspections} onSelect={(inspection) => setSelectedId(inspection.id)} />
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
          onClose={closeDetail}
          renderExtraActions={(inspection) => (
            <InspectionReportButton inspectionId={inspection.id} alarmId={inspection.alarmId} type={inspection.type} />
          )}
          onChanged={(_updated, message) => {
            setNotice(message)
            setReloadKey((key) => key + 1)
          }}
        />
      )}
    </div>
  )
}
