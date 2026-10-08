import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useAuth } from '@/app/providers/useAuth'
import { EquipmentReportCreateButton } from '@/features/aireport'
import { useUnresolvedAlarmCount } from '@/features/alarm'
import {
  EquipmentDetailTabs,
  EquipmentFormDialog,
  EquipmentInfoPanel,
  EquipmentKpiCard,
  EquipmentStatusDialog,
  getStatusCandidates,
  useEquipmentDetail,
} from '@/features/equipment'
import type { EquipmentHeaderSummary, EquipmentStatus } from '@/features/equipment'
import { usePmSchedules } from '@/features/inspection'
import { summarizeSensorTypes, useEquipmentSensors, useSensorFeed } from '@/features/sensor'
import { usePageTitle } from '@/shared/hooks/usePageTitle'
import { statusLabel } from '@/shared/lib/equipmentStatus'
import { EmptyState, ErrorState, LoadingBlock, StatusBadge } from '@/shared/ui'

/** 실시간 이벤트 직후 서버 재조회까지 기다리는 시간 — 연속 이벤트를 한 번의 재조회로 묶는다 */
const REFETCH_DEBOUNCE_MS = 600

/**
 * S-3 설비 상세 (/equipment/:id) — GET /equipments/{id} + 수정(ADMIN) / 상태 변경(ENGINEER+, DOWN 일 때는 TECHNICIAN 도)
 *
 * 헤더의 '센서 / 미해결 알람 / 다음 PM 예정'(FR-2.4)은 서버가 합쳐 주지 않는다 — equipment 도메인이
 * sensor/alarm/inspection 에 직접 의존하면 도메인 경계 위반이라, 이 조립 지점(pages)에서 각 도메인의
 * 기존 API 를 병렬 호출해 EquipmentInfoPanel 에 주입한다. 세 조회는 서로 독립적으로 로딩/실패를 처리한다.
 *
 * 실시간: 이 페이지가 SSE 연결 1개를 소유한다(useSensorFeed). 센서 이벤트는 센서 탭이 구독하고,
 * status(자동 DOWN 포함)/alarm 이벤트는 여기서 받아 헤더 상태 배지·알람 건수·상태 이력에 즉시 반영한다.
 */
export function EquipmentDetailPage() {
  const navigate = useNavigate()
  const { id } = useParams<{ id: string }>()
  const equipmentId = id ? Number(id) : null
  const { user } = useAuth()

  // 권한: 수정=ADMIN / 상태 전환=ENGINEER+ 이며 DOWN→IDLE 만 전 역할 (docs/03 F-2, docs/06 §2) — 서버도 동일하게 막는다
  const canEdit = user?.role === 'ADMIN'

  // 주소의 id 가 양의 정수가 아니면(/equipment/abc 등) 조회하지 않고 안내만 보여준다
  const validId = equipmentId !== null && Number.isInteger(equipmentId) && equipmentId > 0
  const queryId = validId ? equipmentId : null

  const { equipment: fetchedEquipment, loading, error, refetch } = useEquipmentDetail(queryId)
  // 다른 설비로 이동한 직후 이전 설비 데이터가 잠깐 남는 것을 막는다
  const equipment = fetchedEquipment && fetchedEquipment.id === queryId ? fetchedEquipment : null
  usePageTitle(equipment ? `${equipment.code} ${equipment.name}` : '설비 상세')

  // 헤더 값 3종 — 기본정보 조회와 병렬로 시작한다 (유효한 id 일 때만)
  const sensorsQuery = useEquipmentSensors(queryId)
  const alarmCountQuery = useUnresolvedAlarmCount(queryId) // 30초 주기 갱신, 탭 숨김 시 중지
  const pmQuery = usePmSchedules({ equipmentId: queryId, enabled: validId })

  const [editOpen, setEditOpen] = useState(false)
  const [statusOpen, setStatusOpen] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  // 상태 변경 성공/실시간 상태 이벤트 시 증가 — 상태 이력 탭이 이 값을 보고 재조회한다
  const [statusLogReloadKey, setStatusLogReloadKey] = useState(0)

  /* ---------- 실시간 반영 (SSE status / alarm) ---------- */
  // SSE status 이벤트로 받은 상태 — 서버 재조회 결과가 도착하면 비운다(서버값이 항상 우선)
  const [liveStatus, setLiveStatus] = useState<EquipmentStatus | null>(null)
  const refetchTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(
    () => () => {
      if (refetchTimerRef.current) clearTimeout(refetchTimerRef.current)
    },
    [],
  )
  // 설비가 바뀌면 이전 설비의 실시간 상태는 버린다
  useEffect(() => {
    setLiveStatus(null)
  }, [queryId])
  // 서버 재조회 결과가 오면 서버값이 우선 — 오버레이를 비운다
  useEffect(() => {
    setLiveStatus(null)
  }, [fetchedEquipment])

  const scheduleRefetch = useCallback(() => {
    if (refetchTimerRef.current) clearTimeout(refetchTimerRef.current)
    refetchTimerRef.current = setTimeout(() => {
      refetch()
      alarmCountQuery.refetch()
    }, REFETCH_DEBOUNCE_MS)
  }, [refetch, alarmCountQuery])

  const feed = useSensorFeed(queryId, {
    onStatus: (payload) => {
      if (payload.equipmentId !== queryId) return
      setLiveStatus(payload.toStatus)
      setStatusLogReloadKey((key) => key + 1)
      scheduleRefetch()
    },
    onAlarm: (payload) => {
      if (payload.equipmentId !== queryId) return
      alarmCountQuery.refetch()
    },
    // 폴백 폴링(3초) 중에는 설비 상태를 서버에서 다시 읽는다
    onPoll: () => refetch(),
  })

  const currentStatus: EquipmentStatus | null = equipment ? (liveStatus ?? equipment.status) : null

  const headerSummary = useMemo((): EquipmentHeaderSummary => {
    const pm = pmQuery.schedules.find((schedule) => schedule.equipmentId === queryId) ?? null
    return {
      // 실패를 우선 판정 — 로딩/실패 중에 0건·미설정으로 폴백하지 않는다
      sensors: sensorsQuery.error
        ? { state: 'error' }
        : sensorsQuery.sensors
          ? { state: 'ready', value: summarizeSensorTypes(sensorsQuery.sensors) }
          : { state: 'loading' },
      openAlarmCount: alarmCountQuery.error
        ? { state: 'error' }
        : alarmCountQuery.count !== null
          ? { state: 'ready', value: alarmCountQuery.count }
          : { state: 'loading' },
      nextPm: pmQuery.error
        ? { state: 'error' }
        : pmQuery.loading
          ? { state: 'loading' }
          : {
              state: 'ready',
              value: pm
                ? { nextDueAt: pm.nextDueAt, overdue: pm.overdue, overdueDays: pm.overdueDays }
                : null,
            },
    }
  }, [
    queryId,
    sensorsQuery.error,
    sensorsQuery.sensors,
    alarmCountQuery.error,
    alarmCountQuery.count,
    pmQuery.error,
    pmQuery.loading,
    pmQuery.schedules,
  ])

  // 역할 × 현재 상태로 후보가 하나라도 있어야 버튼을 보인다 (TECHNICIAN 은 DOWN 일 때만 IDLE 후보)
  const canChangeStatus =
    currentStatus !== null && getStatusCandidates(currentStatus, user?.role).length > 0

  if (!validId) {
    return (
      <div className="page">
        <EmptyState
          title="잘못된 설비 주소입니다"
          description="설비 목록에서 설비를 선택해 주세요."
          icon="✕"
          action={
            <button type="button" className="btn" onClick={() => navigate('/equipment')}>
              설비 목록으로
            </button>
          }
        />
      </div>
    )
  }

  return (
    <div className="page">
      <div className="page-header">
        <button type="button" className="btn btn-ghost" onClick={() => navigate('/equipment')}>
          ← 설비 목록
        </button>
      </div>

      {/* 최초 로딩일 때만 전체 로딩 — 재조회(refetch) 중에는 화면을 유지해 탭·입력 상태가 초기화되지 않게 한다 */}
      {loading && !equipment && <LoadingBlock label="설비 정보를 불러오는 중…" />}

      {!loading && error && !equipment && <ErrorState error={error} onRetry={refetch} />}

      {equipment && currentStatus && (
        <>
          <div className="detail-header">
            <div className="detail-title">
              <span className="detail-code">{equipment.code}</span>
              <h1 className="page-title">{equipment.name}</h1>
              <StatusBadge status={currentStatus} />
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
              {/* ENGINEER+ 만 노출, 미해결 알람이 없으면 비활성 */}
              <EquipmentReportCreateButton equipmentId={equipment.id} />
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

          <EquipmentInfoPanel equipment={equipment} summary={headerSummary} />
          <EquipmentKpiCard equipmentId={equipment.id} />
          <EquipmentDetailTabs
            equipmentId={equipment.id}
            sensorFeed={feed}
            statusLogReloadKey={statusLogReloadKey}
          />

          {canChangeStatus && statusOpen && (
            <EquipmentStatusDialog
              equipment={{ ...equipment, status: currentStatus }}
              onClose={() => setStatusOpen(false)}
              onChanged={(updated) => {
                setStatusOpen(false)
                setNotice(`상태를 ${statusLabel(updated.status)} 로 변경했습니다. 상태 이력 탭에서 확인할 수 있습니다.`)
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
