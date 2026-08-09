import { useCallback, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  AlarmFilterBar,
  AlarmResolveDialog,
  AlarmTable,
  EMPTY_ALARM_FILTER,
  ManualAlarmDialog,
  ackAlarm,
  alarmFromEvent,
  compareAlarms,
  useAlarmList,
} from '@/features/alarm'
import type { Alarm, AlarmFilterValue } from '@/features/alarm'
import { useEquipmentList } from '@/features/equipment'
import { useSensorStream } from '@/features/sensor'
import { EmptyState, ErrorState, LoadingBlock, StreamStatusBadge } from '@/shared/ui'
import { toApiError } from '@/shared/api'
import { toUserMessage } from '@/shared/lib/errorMessage'
import { kstDateToUtcIso } from '@/shared/lib/datetime'

/**
 * S-6 알람 센터 (/alarms)
 *
 * - GET /alarms (OPEN 우선 정렬은 서버 기본, 화면에서도 compareAlarms 로 유지)
 * - 필터: 설비 / 상태 / 심각도 / 기간(KST 날짜 → UTC ISO 변환 후 전송)
 * - PATCH /alarms/{id}/ack, PATCH /alarms/{id}/resolve (ACK 상태에서만, 해제 사유 필수)
 * - POST /alarms/manual 수동 고장 보고
 * - SSE /stream/sensors 의 alarm 이벤트로 신규 알람 실시간 추가 (실패 시 3초 폴링 폴백)
 */
export function AlarmCenterPage() {
  const navigate = useNavigate()
  const [filter, setFilter] = useState<AlarmFilterValue>(EMPTY_ALARM_FILTER)
  const [reloadKey, setReloadKey] = useState(0)
  const [liveAlarms, setLiveAlarms] = useState<Alarm[]>([])
  const [busyAlarmId, setBusyAlarmId] = useState<number | null>(null)
  const [resolveTarget, setResolveTarget] = useState<Alarm | null>(null)
  const [manualOpen, setManualOpen] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  const { equipments } = useEquipmentList({ size: 100 })

  const listFilter = useMemo(
    () => ({
      equipmentId: filter.equipmentId,
      status: filter.status,
      severity: filter.severity,
      from: kstDateToUtcIso(filter.fromDate, 'start') ?? null,
      to: kstDateToUtcIso(filter.toDate, 'end') ?? null,
      size: 50,
    }),
    [filter],
  )

  const { alarms, loading, error } = useAlarmList(listFilter, reloadKey)

  const refresh = useCallback(() => setReloadKey((key) => key + 1), [])

  /** 신규 알람이 현재 필터에 맞는지 — 안 맞으면 목록에 끼워 넣지 않는다 */
  const matchesFilter = useCallback(
    (alarm: Alarm): boolean => {
      if (filter.equipmentId !== null && alarm.equipmentId !== filter.equipmentId) return false
      if (filter.status !== null && alarm.status !== filter.status) return false
      if (filter.severity !== null && alarm.severity !== filter.severity) return false
      const occurred = new Date(alarm.occurredAt).getTime()
      const from = kstDateToUtcIso(filter.fromDate, 'start')
      const to = kstDateToUtcIso(filter.toDate, 'end')
      if (from && occurred < new Date(from).getTime()) return false
      if (to && occurred > new Date(to).getTime()) return false
      return true
    },
    [filter],
  )

  const stream = useSensorStream(filter.equipmentId ?? null, {
    onAlarm: (payload) => {
      // SSE 페이로드는 alarmId 를 쓰므로 반드시 변환해서 목록 shape 로 맞춘다
      const alarm = alarmFromEvent(payload)
      if (!matchesFilter(alarm)) return
      setLiveAlarms((previous) => [alarm, ...previous.filter((item) => item.id !== alarm.id)])
    },
    onPoll: async () => {
      // 폴백 폴링(3초): 목록 재조회로 신규 알람을 따라잡는다
      setReloadKey((key) => key + 1)
    },
  })

  const mergedAlarms = useMemo(() => {
    const seen = new Set<number>()
    return [...liveAlarms, ...alarms]
      .filter((alarm) => {
        if (seen.has(alarm.id)) return false
        seen.add(alarm.id)
        return true
      })
      .sort(compareAlarms)
  }, [liveAlarms, alarms])

  /** 서버 응답으로 로컬 상태를 갱신 (ACK/RESOLVE 직후 즉시 반영) */
  const applyUpdated = useCallback((updated: Alarm) => {
    setLiveAlarms((previous) => {
      const rest = previous.filter((item) => item.id !== updated.id)
      return [updated, ...rest]
    })
  }, [])

  const handleAck = async (alarm: Alarm) => {
    setBusyAlarmId(alarm.id)
    setActionError(null)
    try {
      const updated = await ackAlarm(alarm.id)
      applyUpdated(updated)
      setNotice(`알람 #${alarm.id} 을(를) 확인(ACK) 처리했습니다.`)
      refresh()
    } catch (cause) {
      setActionError(toUserMessage(toApiError(cause)))
    } finally {
      setBusyAlarmId(null)
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">알람 센터</h1>
          <p className="page-subtitle">
            OPEN → ACK → RESOLVED 순서로만 처리됩니다 (ACK 없이 해제 불가).
          </p>
        </div>
        <div className="equipment-toolbar" style={{ margin: 0 }}>
          <StreamStatusBadge state={stream} />
          <button type="button" className="btn" onClick={() => setManualOpen(true)}>
            수동 고장 보고
          </button>
          <button type="button" className="btn btn-ghost" onClick={refresh}>
            새로고침
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

      {actionError && (
        <p className="form-error" role="alert">
          {actionError}
        </p>
      )}

      <AlarmFilterBar value={filter} onChange={setFilter} equipments={equipments} />

      {loading && mergedAlarms.length === 0 && <LoadingBlock label="알람을 불러오는 중…" />}

      {!loading && error && mergedAlarms.length === 0 && (
        <ErrorState error={error} onRetry={refresh} />
      )}

      {!loading && !error && mergedAlarms.length === 0 && (
        <EmptyState
          title="조건에 맞는 알람이 없습니다"
          description="필터를 완화하거나 기간을 넓혀 보세요."
          icon="⚠"
        />
      )}

      {mergedAlarms.length > 0 && (
        <AlarmTable
          alarms={mergedAlarms}
          busyAlarmId={busyAlarmId}
          onAck={(alarm) => void handleAck(alarm)}
          onResolveRequest={setResolveTarget}
          onSelectEquipment={(equipmentId) => navigate(`/equipment/${equipmentId}`)}
        />
      )}

      {resolveTarget && (
        <AlarmResolveDialog
          alarm={resolveTarget}
          onClose={() => setResolveTarget(null)}
          onResolved={(updated) => {
            applyUpdated(updated)
            setResolveTarget(null)
            setNotice(`알람 #${updated.id} 을(를) 해제(RESOLVED) 처리했습니다.`)
            refresh()
          }}
        />
      )}

      {manualOpen && (
        <ManualAlarmDialog
          equipments={equipments}
          defaultEquipmentId={filter.equipmentId}
          onClose={() => setManualOpen(false)}
          onCreated={(created) => {
            setManualOpen(false)
            setLiveAlarms((previous) => [created, ...previous])
            setNotice(`수동 고장 보고를 등록했습니다 (알람 #${created.id}).`)
            refresh()
          }}
        />
      )}
    </div>
  )
}
