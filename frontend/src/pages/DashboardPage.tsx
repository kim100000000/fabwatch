import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useOutletContext } from 'react-router-dom'
import type { AppOutletContext } from '@/app/layouts/outletContext'
import { useAuth } from '@/app/providers/useAuth'
import { EQUIPMENT_STATUSES, LineKpiSection, useEquipmentList } from '@/features/equipment'
import type { EquipmentStatus, EquipmentSummary } from '@/features/equipment'
import {
  AlarmStreamList,
  alarmFromEvent,
  mergeAlarms,
  useAlarmList,
  useOpenAlarmSummary,
} from '@/features/alarm'
import type { Alarm, AlarmEventPayload } from '@/features/alarm'
import { EquipmentLiveCard, useLatestSensors, useSensorStream } from '@/features/sensor'
import type { EquipmentStatusEventPayload, SensorEventPayload } from '@/features/sensor'
import { useOverdueEquipmentIds } from '@/features/inspection'
import { DemoControlPanel } from '@/features/simulator'
import { usePageTitle } from '@/shared/hooks/usePageTitle'
import { statusLabel } from '@/shared/lib/equipmentStatus'
import { EmptyState, ErrorState, LoadingBlock, StreamStatusBadge, Term } from '@/shared/ui'
import { sortEquipmentsForDashboard } from './dashboardSort'
import './dashboard.css'

/** 스트림에 유지할 최근 알람 건수 (docs/03 F-5.1) */
const ALARM_STREAM_SIZE = 10

/** 실시간 이벤트 직후 서버 재조회까지 기다리는 시간 — 연속 이벤트를 한 번의 재조회로 묶는다 */
const REFETCH_DEBOUNCE_MS = 600

/**
 * S-1 라인 현황 대시보드 (/)
 *
 * 데이터 소스
 *  - GET /equipments            : 설비 카드 목록
 *  - GET /equipments/{id}/sensor-data/latest : 카드에 표시할 센서 4값
 *  - GET /alarms                : 하단 최근 알람 스트림 (카드의 미해결 알람 수는 useOpenAlarmSummary 로 별도 집계)
 *  - GET /equipments/kpi       : 라인 KPI(가동률/MTBF/MTTR/DOWN 횟수) — SSE/폴링과 별도 쿼리, 1분 갱신
 *  - SSE /stream/sensors        : sensor/alarm/status 3종 실시간 반영 (실패 시 3초 폴링 폴백)
 */
export function DashboardPage() {
  usePageTitle('라인 현황')
  const navigate = useNavigate()
  const { user } = useAuth()
  // 헤더 라인 선택 — 설비 카드와 라인 KPI 양쪽에 적용한다
  const { lineId } = useOutletContext<AppOutletContext>()
  // 데모 컨트롤은 ENGINEER+ (docs/04 §3, docs/06 §8)
  const canControlSimulator = user?.role === 'ADMIN' || user?.role === 'ENGINEER'

  const {
    equipments,
    totalElements: equipmentTotal,
    loading: equipmentLoading,
    error: equipmentError,
    refetch: refetchEquipments,
  } = useEquipmentList({ size: 100 })

  // SSE status 이벤트로 갱신된 설비 상태 — 서버 재조회 결과가 도착하면 비운다(서버값이 항상 우선)
  const [statusOverride, setStatusOverride] = useState<Record<number, EquipmentStatus>>({})
  useEffect(() => {
    if (equipments.length > 0) setStatusOverride({})
  }, [equipments])

  // PM OVERDUE 설비 (GET /pm-schedules?overdueOnly=true, 1분 주기 별도 쿼리 — SSE/폴링과 독립)
  const overdueEquipmentIds = useOverdueEquipmentIds()

  // 설비별 미해결(OPEN+ACK) 알람 집계 — 서버 목록 DTO 에 건수가 없어 알람 API 를 집계한다 (30초 주기 + 알람 이벤트 시 즉시)
  const alarmSummary = useOpenAlarmSummary()

  const visibleEquipments = useMemo(
    (): EquipmentSummary[] =>
      equipments
        .filter((equipment) => lineId === null || equipment.lineId === lineId)
        .map((equipment) => ({
          ...equipment,
          status: statusOverride[equipment.id] ?? equipment.status,
          pmOverdue: overdueEquipmentIds.has(equipment.id),
        })),
    [equipments, lineId, statusOverride, overdueEquipmentIds],
  )

  const sortedEquipments = useMemo(
    () => sortEquipmentsForDashboard(visibleEquipments, alarmSummary.summary),
    [visibleEquipments, alarmSummary.summary],
  )

  const equipmentIds = useMemo(() => equipments.map((item) => item.id), [equipments])
  const { sensorsByEquipment, reload: reloadSensors, applySensorEvent } =
    useLatestSensors(equipmentIds)

  // 최근 알람 스트림용 목록
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
  const refetchTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(
    () => () => {
      if (flashTimerRef.current) clearTimeout(flashTimerRef.current)
      if (refetchTimerRef.current) clearTimeout(refetchTimerRef.current)
    },
    [],
  )

  // 서버 목록이 갱신되면 서버가 이미 아는 실시간 알람은 정리한다 (서버값 우선 — mergeAlarms 가 처리)
  const allAlarms = useMemo(() => mergeAlarms(fetchedAlarms, liveAlarms), [fetchedAlarms, liveAlarms])
  const streamAlarms = useMemo(() => allAlarms.slice(0, ALARM_STREAM_SIZE), [allAlarms])

  /** 실시간 이벤트 뒤 서버 값을 다시 읽는다 — 연속 이벤트는 한 번으로 묶는다 */
  const scheduleServerRefresh = useCallback(
    (what: { equipments?: boolean; alarms?: boolean }) => {
      if (refetchTimerRef.current) clearTimeout(refetchTimerRef.current)
      refetchTimerRef.current = setTimeout(() => {
        if (what.equipments) refetchEquipments()
        if (what.alarms) {
          setAlarmReloadKey((key) => key + 1)
          alarmSummary.reload()
        }
      }, REFETCH_DEBOUNCE_MS)
    },
    [refetchEquipments, alarmSummary],
  )

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

  const handleAlarmEvent = useCallback(
    (payload: AlarmEventPayload) => {
      // SSE 페이로드는 alarmId 를 쓰므로 반드시 변환해서 목록 shape 로 맞춘다
      const alarm = alarmFromEvent(payload)
      setLiveAlarms((previous) =>
        [alarm, ...previous.filter((item) => item.id !== alarm.id)].slice(0, ALARM_STREAM_SIZE),
      )
      setFreshAlarmIds(new Set([alarm.id]))
      scheduleServerRefresh({ alarms: true, equipments: true })
    },
    [scheduleServerRefresh],
  )

  const handleStatusEvent = useCallback(
    (payload: EquipmentStatusEventPayload) => {
      setStatusOverride((previous) => ({ ...previous, [payload.equipmentId]: payload.toStatus }))
      scheduleServerRefresh({ equipments: true })
    },
    [scheduleServerRefresh],
  )

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

  /** 상태별 설비 수 — 현재 시점 스냅샷 */
  const statusCount = useMemo(() => {
    const counts: Record<EquipmentStatus, number> = { RUN: 0, IDLE: 0, DOWN: 0, PM: 0 }
    visibleEquipments.forEach((equipment) => {
      counts[equipment.status] = (counts[equipment.status] ?? 0) + 1
    })
    return counts
  }, [visibleEquipments])

  // 미해결 알람 합계 — 보이는 설비 기준(라인 필터 반영). 집계 전/실패 시 null
  const unresolvedTotal = useMemo(() => {
    if (!alarmSummary.summary) return null
    return visibleEquipments.reduce(
      (sum, equipment) => sum + (alarmSummary.summary?.byEquipment[equipment.id]?.count ?? 0),
      0,
    )
  }, [alarmSummary.summary, visibleEquipments])
  const unresolvedLabel =
    unresolvedTotal !== null ? `${unresolvedTotal}` : alarmSummary.error ? '확인 실패' : '…'

  const pmOverdueCount = useMemo(
    () => visibleEquipments.filter((equipment) => equipment.pmOverdue).length,
    [visibleEquipments],
  )

  const downCount = statusCount.DOWN
  const needsAttention = downCount > 0 || (unresolvedTotal ?? 0) > 0 || pmOverdueCount > 0

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">라인 현황</h1>
          <p className="page-subtitle">
            설비 {visibleEquipments.length}대 · 센서값은 SSE 로 2초마다 갱신됩니다.
          </p>
        </div>
        <StreamStatusBadge state={stream} />
      </div>

      {/* 지금 조치가 필요한 항목 요약 — 카드마다 반복 표시하지 않고 여기서 건수로 먼저 알린다 */}
      {needsAttention && (
        <div className="attention-banner" role="status">
          <strong>조치 필요</strong>
          {downCount > 0 && (
            <span>
              {statusLabel('DOWN')} <b className="mono">{downCount}</b>대
            </span>
          )}
          {(unresolvedTotal ?? 0) > 0 && (
            <button type="button" className="inline-link" onClick={() => navigate('/alarms')}>
              미해결 알람 <b className="mono">{unresolvedTotal}</b>건 →
            </button>
          )}
          {pmOverdueCount > 0 && (
            <span>
              <Term term="OVERDUE" /> <b className="mono">{pmOverdueCount}</b>대
            </span>
          )}
        </div>
      )}

      {/* 현황 스트립 — 현재 상태 스냅샷. 기간 누적 KPI 는 아래 라인 KPI 영역 */}
      <div className="kpi-strip">
        {EQUIPMENT_STATUSES.map((status) => (
          <div key={status} className="kpi-item" data-status={status}>
            <span className="kpi-label">{status === 'DOWN' ? <Term term="DOWN" /> : statusLabel(status)}</span>
            <span className="kpi-value mono">{statusCount[status]}</span>
          </div>
        ))}
        <div className="kpi-item" data-alarm="true">
          <span className="kpi-label">미해결 알람</span>
          <span className="kpi-value mono">{unresolvedLabel}</span>
        </div>
        <div className="kpi-item" data-pm-overdue="true">
          <span className="kpi-label">
            <Term term="OVERDUE" />
          </span>
          <span className="kpi-value mono">{pmOverdueCount}</span>
        </div>
      </div>

      {canControlSimulator && (
        <p className="demo-hint">
          데모 제어(오른쪽 아래 <b>⚗ 데모 제어</b>)로 센서 이상 상황을 주입하면 알람 → 자동 DOWN 흐름을 바로 볼 수 있습니다.
        </p>
      )}

      {/* 라인 KPI — 기간 누적 (FR-5.7). 현재 상태 스트립과 별개 */}
      <LineKpiSection lineId={lineId} />

      {equipmentLoading && equipments.length === 0 && (
        <LoadingBlock label="설비 목록을 불러오는 중…" />
      )}

      {equipmentError && equipments.length === 0 && (
        <ErrorState error={equipmentError} onRetry={refetchEquipments} />
      )}

      {!equipmentLoading && !equipmentError && sortedEquipments.length === 0 && (
        <EmptyState
          title="표시할 설비가 없습니다"
          description={
            lineId !== null && equipments.length > 0
              ? '선택한 라인에 등록된 설비가 없습니다. 헤더에서 "전체 라인"을 선택해 보세요.'
              : '설비가 등록되면 실시간 현황이 여기에 표시됩니다. 설비 메뉴에서 먼저 등록해 주세요.'
          }
        />
      )}

      {equipmentTotal > equipments.length && (
        <p className="field-hint" role="status">
          설비 {equipmentTotal}대 중 {equipments.length}대만 표시됩니다.
        </p>
      )}

      {sortedEquipments.length > 0 && (
        <div className="live-grid">
          {sortedEquipments.map((equipment) => (
            <EquipmentLiveCard
              key={equipment.id}
              equipment={equipment}
              sensors={sensorsByEquipment[equipment.id] ?? []}
              openAlarmCount={
                alarmSummary.summary ? (alarmSummary.summary.byEquipment[equipment.id]?.count ?? 0) : null
              }
              alarmCountFailed={!!alarmSummary.error}
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
          equipments={equipments}
          sensorsByEquipment={sensorsByEquipment}
        />
      )}
    </div>
  )
}
