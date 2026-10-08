import { useCallback, useState } from 'react'
import type { ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { EmptyState, ErrorState, LoadingBlock } from '@/shared/ui'
import { toApiError } from '@/shared/api'
import { toUserMessage } from '@/shared/lib/errorMessage'
import { ackAlarm } from '../api/alarmApi'
import { useAlarmList } from '../api/useAlarmList'
import { compareAlarms, inspectionRegisterPath } from '../types'
import type { Alarm } from '../types'
import { AlarmTable } from './AlarmTable'
import { AlarmResolveDialog } from './AlarmResolveDialog'
import './alarm.css'

interface EquipmentAlarmTabPanelProps {
  equipmentId: number
  /** 알람 행 조치 칸 추가 버튼 슬롯 (AlarmTable.renderExtraActions 로 전달) */
  renderExtraActions?: (alarm: Alarm) => ReactNode
}

/**
 * S-3 알람 탭 (docs/04 §3 탭3) — 해당 설비 알람만, OPEN 상단 고정 + ACK/RESOLVE 인라인.
 * 실시간 구독은 센서 탭의 SSE 연결과 중복되지 않도록 여기서는 열지 않는다
 * (탭 전환/조치 후 재조회로 충분 — 신규 알람 실시간 수신은 S-1/S-6 담당).
 */
export function EquipmentAlarmTabPanel({ equipmentId, renderExtraActions }: EquipmentAlarmTabPanelProps) {
  const navigate = useNavigate()
  const [reloadKey, setReloadKey] = useState(0)
  const [busyAlarmId, setBusyAlarmId] = useState<number | null>(null)
  const [resolveTarget, setResolveTarget] = useState<Alarm | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  const { alarms, loading, error } = useAlarmList({ equipmentId, size: 50 }, reloadKey)
  const refresh = useCallback(() => setReloadKey((key) => key + 1), [])

  const sorted = [...alarms].sort(compareAlarms)

  const handleAck = async (alarm: Alarm) => {
    setBusyAlarmId(alarm.id)
    setActionError(null)
    try {
      await ackAlarm(alarm.id)
      refresh()
    } catch (cause) {
      setActionError(toUserMessage(toApiError(cause)))
    } finally {
      setBusyAlarmId(null)
    }
  }

  if (loading && sorted.length === 0) return <LoadingBlock label="알람을 불러오는 중…" />
  if (error && sorted.length === 0) return <ErrorState error={error} onRetry={refresh} />

  return (
    <div>
      {actionError && (
        <p className="form-error" role="alert">
          {actionError}
        </p>
      )}

      {sorted.length === 0 ? (
        <EmptyState title="이 설비의 알람이 없습니다" icon="⚠" />
      ) : (
        <AlarmTable
          alarms={sorted}
          hideEquipment
          busyAlarmId={busyAlarmId}
          onAck={(alarm) => void handleAck(alarm)}
          onResolveRequest={setResolveTarget}
          onOpenInspection={(inspectionId) => navigate(`/inspections?id=${inspectionId}`)}
          onRegisterInspection={(alarm) => navigate(inspectionRegisterPath(alarm))}
          renderExtraActions={renderExtraActions}
        />
      )}

      {resolveTarget && (
        <AlarmResolveDialog
          alarm={resolveTarget}
          onClose={() => setResolveTarget(null)}
          onResolved={() => {
            setResolveTarget(null)
            refresh()
          }}
        />
      )}
    </div>
  )
}
