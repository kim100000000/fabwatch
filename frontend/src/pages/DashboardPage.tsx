import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '@/app/providers/useAuth'
import { EQUIPMENT_STATUSES, useEquipmentList } from '@/features/equipment'
import type { EquipmentStatus, EquipmentSummary } from '@/features/equipment'
import { AlarmStreamList, alarmFromEvent, compareAlarms, useAlarmList } from '@/features/alarm'
import type { Alarm, AlarmEventPayload } from '@/features/alarm'
import { EquipmentLiveCard, useLatestSensors, useSensorStream } from '@/features/sensor'
import type { EquipmentStatusEventPayload, SensorEventPayload } from '@/features/sensor'
import { DemoControlPanel } from '@/features/simulator'
import { ErrorState, LoadingBlock, StreamStatusBadge } from '@/shared/ui'
import './dashboard.css'

/** 스트림에 유지할 최근 알람 건수 (docs/03 F-5.1) */
const ALARM_STREAM_SIZE = 10

/**
 * S-1 라인 현황 대시보드 (/)
 *
 * 데이터 소스
 *  - GET /equipments            : 설비 카드 목록
 *  - GET /equipments/{id}/sensor-data/latest : 카드에 표시할 센서 4값
 *  - GET /alarms                : 하단 최근 알람 스트림 + 카드 미조치 알람 수
 *  - SSE /stream/sensors        : sensor/alarm/status 3종 실시간 반영 (실패 시 3초 폴링 폴백)
 */
export function DashboardPage() {
  const navigate = useNavigate()
  const { user } = useAuth()
  // 데모 컨트롤은 ENGINEER+ (docs/04 §3, docs/06 §8)
  const canControlSimulator = user?.role === 'ADMIN' || user?.role === 'ENGINEER'

  const {
    equipments,
    loading: equipmentLoading,
    error: equipmentError,
    refetch: refetchEquipments,
  } = useEquipmentList({ size: 100 })

  // SSE status 이벤트로 갱신된 설비 상태 (서버 재조회 전까지의 오버레이)
  const [statusOverride, setStatusOverride] = useState<Record<number, EquipmentStatus>>({})

  const mergedEquipments = useMemo(
    (): EquipmentSummary[] =>
      equipments.map((equipment) =>
        statusOverride[equipment.id]
          ? { ...equipment, status: statusOverride[equipment.id] }
          : equipment,
      ),
    [equipments, statusOverride],
  )

  const equipmentIds = useMemo(() => equipments.map((item) => item.id), [equipments])
  const { sensorsByEquipment, reload: reloadSensors, applySensorEvent } =
    useLatestSensors(equipmentIds)

  // 최근 알람 (미조치 우선) — 스트림 + 카드 배지 산출에 함께 쓴다
  const [alarmReloadKey, setAlarmReloadKey] = useState(0)
  const {
    alarms: fetchedAlarms,
    loading: alarmLoading,
    error: alarmError,
  } = useAlarmList({ size: 30 }, alarmReloadKey)

  // SSE 로 들어온 신규 알람 (아직 서버 재조회에 안 잡힌 것)
  const [liveAlarms, setLiveAlarms] = useState<Alarm[]>([])
  const [freshAlarmIds, setFreshAlarmIds] = useState<ReadonlySet<number>>(new Set())
  const [flashSensorIds, setFlashSensorIds] = useState<ReadonlySet<number>>(new Set())
  const flashTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(
    () => () => {
      if (flashTimerRef.current) clearTimeout(flashTimerRef.current)
    },
    [],
  )

  const allAlarms = useMemo(() => {
    const seen = new Set<number>()
    return [...liveAlarms, ...fetchedAlarms]
      .filter((alarm) => {
        if (seen.has(alarm.id)) return false
        seen.add(alarm.id)
        return true
      })
      .sort(compareAlarms)
  }, [liveAlarms, fetchedAlarms])

  const streamAlarms = useMemo(() => allAlarms.slice(0, ALARM_STREAM_SIZE), [allAlarms])

  /** 설비별 미조치(OPEN/ACK) 알람 수 — 서버가 목록 응답에 openAlarmCount 를 주면 그것이 우선 */
  const openAlarmCountByEquipment = useMemo(() => {
    const counts: Record<number, number> = {}
    allAlarms
      .filter((alarm) => alarm.status !== 'RESOLVED')
      .forEach((alarm) => {
        counts[alarm.equipmentId] = (counts[alarm.equipmentId] ?? 0) + 1
      })
    return counts
  }, [allAlarms])

  const handleSensorEvent = useCallback(
    (payload: SensorEventPayload) => {
      applySensorEvent(payload)
      if (payload.level === 'NORMAL') return
      // 임계치 초과 값은 1회 깜빡임 (docs/04 §3)
      setFlashSensorIds(new Set([payload.sensorId]))
      if (flashTimerRef.current) clearTimeout(flashTimerRef.current)
      flashTimerRef.current = setTimeout(() => setFlashSensorIds(new Set()), 700)
    },
    [applySensorEvent],
  )

  const handleAlarmEvent = useCallback((payload: AlarmEventPayload) => {
    // SSE 페이로드는 alarmId 를 쓰므로 반드시 변환해서 목록 shape 로 맞춘다
    const alarm = alarmFromEvent(payload)
    setLiveAlarms((previous) =>
      [alarm, ...previous.filter((item) => item.id !== alarm.id)].slice(0, ALARM_STREAM_SIZE),
    )
    setFreshAlarmIds(new Set([alarm.id]))
  }, [])

  const handleStatusEvent = useCallback((payload: EquipmentStatusEventPayload) => {
    setStatusOverride((previous) => ({ ...previous, [payload.equipmentId]: payload.toStatus }))
  }, [])

  const stream = useSensorStream(null, {
    onSensor: handleSensorEvent,
    onAlarm: handleAlarmEvent,
    onStatus: handleStatusEvent,
    // 폴백 폴링(3초): 센서 최신값 + 알람 목록 + 설비 상태를 다시 읽는다
    onPoll: async (signal) => {
      await reloadSensors(signal)
      setAlarmReloadKey((key) => key + 1)
      refetchEquipments()
    },
  })

  /** 상태별 설비 수 — 현재 시점 스냅샷(누적 가동률이 아님을 라벨에 명시) */
  const statusCount = useMemo(() => {
    const counts: Record<EquipmentStatus, number> = { RUN: 0, IDLE: 0, DOWN: 0, PM: 0 }
    mergedEquipments.forEach((equipment) => {
      counts[equipment.status] = (counts[equipment.status] ?? 0) + 1
    })
    return counts
  }, [mergedEquipments])

  const unresolvedTotal = useMemo(
    () => allAlarms.filter((alarm) => alarm.status !== 'RESOLVED').length,
    [allAlarms],
  )

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">라인 현황</h1>
          <p className="page-subtitle">
            설비 {mergedEquipments.length}대 · 센서값은 SSE 로 2초마다 갱신됩니다.
          </p>
        </div>
        <StreamStatusBadge state={stream} />
      </div>

      {/* 현황 스트립 — 현재 상태 스냅샷 (기간 누적 KPI 는 4주차 /kpi API 연동 예정) */}
      <div className="kpi-strip">
        {EQUIPMENT_STATUSES.map((status) => (
          <div key={status} className="kpi-item" data-status={status}>
            <span className="kpi-label">{status}</span>
            <span className="kpi-value mono">{statusCount[status]}</span>
          </div>
        ))}
        <div className="kpi-item" data-alarm="true">
          <span className="kpi-label">미조치 알람</span>
          <span className="kpi-value mono">{unresolvedTotal}</span>
        </div>
      </div>

      {equipmentLoading && mergedEquipments.length === 0 && (
        <LoadingBlock label="설비 목록을 불러오는 중…" />
      )}

      {equipmentError && mergedEquipments.length === 0 && (
        <ErrorState error={equipmentError} onRetry={refetchEquipments} />
      )}

      {mergedEquipments.length > 0 && (
        <div className="live-grid">
          {mergedEquipments.map((equipment) => (
            <EquipmentLiveCard
              key={equipment.id}
              equipment={equipment}
              sensors={sensorsByEquipment[equipment.id] ?? []}
              openAlarmCount={
                openAlarmCountByEquipment[equipment.id] ?? equipment.openAlarmCount ?? 0
              }
              flashSensorIds={flashSensorIds}
              onSelect={(id) => navigate(`/equipment/${id}`)}
            />
          ))}
        </div>
      )}

      {alarmError && streamAlarms.length === 0 ? (
        <ErrorState error={alarmError} onRetry={() => setAlarmReloadKey((key) => key + 1)} />
      ) : alarmLoading && streamAlarms.length === 0 ? (
        <LoadingBlock label="알람을 불러오는 중…" />
      ) : (
        <AlarmStreamList
          alarms={streamAlarms}
          freshIds={freshAlarmIds}
          onSelect={(alarm) => navigate(`/equipment/${alarm.equipmentId}`)}
          aside={
            <button type="button" className="btn btn-sm btn-ghost" onClick={() => navigate('/alarms')}>
              알람 센터로 →
            </button>
          }
        />
      )}

      {canControlSimulator && (
        <DemoControlPanel
          equipments={mergedEquipments}
          sensorsByEquipment={sensorsByEquipment}
        />
      )}
    </div>
  )
}
