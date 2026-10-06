import { useMemo, useState } from 'react'
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
import type { EquipmentHeaderSummary } from '@/features/equipment'
import { usePmSchedules } from '@/features/inspection'
import { summarizeSensorTypes, useEquipmentSensors } from '@/features/sensor'
import { statusLabel } from '@/shared/lib/equipmentStatus'
import { ErrorState, LoadingBlock, StatusBadge } from '@/shared/ui'

/**
 * S-3 설비 상세 (/equipment/:id) — GET /equipments/{id} + 수정(ADMIN) / 상태 변경(ENGINEER+, DOWN 일 때는 TECHNICIAN 도)
 *
 * 헤더의 '센서 / 미해결 알람 / 다음 PM 예정'(FR-2.4)은 서버가 합쳐 주지 않는다 — equipment 도메인이
 * sensor/alarm/inspection 에 직접 의존하면 도메인 경계 위반이라, 이 조립 지점(pages)에서 각 도메인의
 * 기존 API 를 병렬 호출해 EquipmentInfoPanel 에 주입한다. 세 조회는 서로 독립적으로 로딩/실패를 처리한다.
 */
export function EquipmentDetailPage() {
  const navigate = useNavigate()
  const { id } = useParams<{ id: string }>()
  const equipmentId = id ? Number(id) : null
  const { user } = useAuth()

  // 권한: 수정=ADMIN / 상태 전환=ENGINEER+ 이며 DOWN→IDLE 만 전 역할 (docs/03 F-2, docs/06 §2) — 서버도 동일하게 막는다
  const canEdit = user?.role === 'ADMIN'

  const { equipment, loading, error, refetch } = useEquipmentDetail(equipmentId)

  // 헤더 값 3종 — 기본정보 조회와 병렬로 시작한다 (유효한 id 일 때만)
  const validId = equipmentId !== null && !Number.isNaN(equipmentId)
  const sensorsQuery = useEquipmentSensors(equipmentId)
  const alarmCountQuery = useUnresolvedAlarmCount(equipmentId) // 30초 주기 갱신, 탭 숨김 시 중지
  const pmQuery = usePmSchedules({ equipmentId, enabled: validId })

  const headerSummary = useMemo((): EquipmentHeaderSummary => {
    const pm = pmQuery.schedules.find((schedule) => schedule.equipmentId === equipmentId) ?? null
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
    equipmentId,
    sensorsQuery.error,
    sensorsQuery.sensors,
    alarmCountQuery.error,
    alarmCountQuery.count,
    pmQuery.error,
    pmQuery.loading,
    pmQuery.schedules,
  ])

  // 역할 × 현재 상태로 후보가 하나라도 있어야 버튼을 보인다 (TECHNICIAN 은 DOWN 일 때만 IDLE 후보)
  const canChangeStatus = equipment != null && getStatusCandidates(equipment.status, user?.role).length > 0

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
            sensors={equipment.sensors}
            statusLogReloadKey={statusLogReloadKey}
          />

          {canChangeStatus && statusOpen && (
            <EquipmentStatusDialog
              equipment={equipment}
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
