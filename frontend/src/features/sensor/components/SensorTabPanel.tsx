import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useAuth } from '@/app/providers/useAuth'
import type { EquipmentSensor } from '@/features/equipment'
import { EmptyState, ErrorState, LoadingBlock, StreamStatusBadge } from '@/shared/ui'
import { useEquipmentSensors } from '../api/useEquipmentSensors'
import { useLatestSensors } from '../api/useLatestSensors'
import { useSensorStream } from '../api/useSensorStream'
import { LIVE_MAX_POINTS, LIVE_WINDOW_MINUTES, SENSOR_TYPE_LABEL, compareSensorType } from '../types'
import type { LivePoint, SensorDefinition, SensorEventPayload, SensorLatest } from '../types'
import { SensorLiveChart } from './SensorLiveChart'
import { SensorHistoryChart } from './SensorHistoryChart'
import { ThresholdEditDialog } from './ThresholdEditDialog'
import { ThresholdHistoryDialog } from './ThresholdHistoryDialog'
import './sensor.css'

interface SensorTabPanelProps {
  equipmentId: number
  /** GET /equipments/{id} 가 준 센서 정보 — 임계치·단위의 출처 */
  sensors?: EquipmentSensor[]
}

/** 상세 응답의 센서 정보를 실시간 표시용 shape 로 맞춘다 (id 필드명이 sensorId 로 다름) */
function toSensorLatest(sensor: EquipmentSensor, equipmentId: number): SensorLatest {
  return {
    sensorId: sensor.id,
    equipmentId,
    sensorType: sensor.type,
    unit: sensor.unit,
    value: sensor.latestValue ?? null,
    measuredAt: sensor.measuredAt ?? null,
    warnLow: sensor.warnLow,
    warnHigh: sensor.warnHigh,
    critLow: sensor.critLow,
    critHigh: sensor.critHigh,
  }
}

/**
 * S-3 센서 탭 (docs/04 §3, docs/03 F-5.2).
 * - 상단: 센서별 실시간 차트 2×2 (SSE 수신, 최근 5분 / 최대 150포인트 슬라이딩)
 * - 하단: 기간 선택 이력 차트 (1시간/24시간/7일, RAW/1M 은 서버 판단)
 * SSE 연결은 이 컴포넌트에서 1개만 열고 차트들에 값을 나눠 준다(차트마다 연결하지 않는다).
 * 각 센서 카드에는 임계치 버튼이 붙는다 (FR-2.3): ADMIN=[임계치 설정] 다이얼로그, 그 외 역할=[임계치 이력] 조회 전용.
 */
export function SensorTabPanel({ equipmentId, sensors }: SensorTabPanelProps) {
  const { user } = useAuth()
  // 임계치 수정은 ADMIN 전용 (docs/06 §3) — 서버도 ENGINEER/TECHNICIAN 을 403 으로 막는다
  const canEditThreshold = user?.role === 'ADMIN'

  const equipmentIds = useMemo(() => [equipmentId], [equipmentId])
  const { sensorsByEquipment, loading, error, reload, applySensorEvent, applyThresholds } =
    useLatestSensors(equipmentIds)

  // 정상 기준값(baseValue)은 latest 응답에 없어 센서 정의 목록에서 가져온다 — 편집 다이얼로그 참고 표시용이라 ADMIN 만 조회
  const { sensors: definitions } = useEquipmentSensors(equipmentId, { enabled: canEditThreshold })
  const baseValueOf = (sensorId: number): number | null | undefined =>
    definitions?.find((definition) => definition.sensorId === sensorId)?.baseValue

  // 임계치 다이얼로그 대상 (센서 id + 모드). 값은 렌더 시점의 mergedSensors 에서 찾아 항상 최신을 보여준다
  const [thresholdTarget, setThresholdTarget] = useState<{ sensorId: number; mode: 'edit' | 'history' } | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  // 실시간 포인트: sensorId → 최근 5분 슬라이딩 배열
  const [livePoints, setLivePoints] = useState<Record<number, LivePoint[]>>({})
  // 방금 갱신된 센서 id (깜빡임 1회용)
  const [flashSensorId, setFlashSensorId] = useState<number | null>(null)
  const flashTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // 언마운트 시 깜빡임 타이머 정리
  useEffect(
    () => () => {
      if (flashTimerRef.current) clearTimeout(flashTimerRef.current)
    },
    [],
  )

  const appendPoint = useCallback((payload: SensorEventPayload) => {
    const cutoff = Date.now() - LIVE_WINDOW_MINUTES * 60_000
    const t = new Date(payload.measuredAt).getTime()
    if (Number.isNaN(t)) return

    setLivePoints((previous) => {
      const current = previous[payload.sensorId] ?? []
      const next = [...current, { t, value: payload.value, level: payload.level }]
        .filter((point) => point.t >= cutoff)
        .slice(-LIVE_MAX_POINTS)
      return { ...previous, [payload.sensorId]: next }
    })
  }, [])

  const handleSensor = useCallback(
    (payload: SensorEventPayload) => {
      applySensorEvent(payload)
      appendPoint(payload)
      if (payload.level !== 'NORMAL') {
        setFlashSensorId(payload.sensorId)
        if (flashTimerRef.current) clearTimeout(flashTimerRef.current)
        flashTimerRef.current = setTimeout(() => setFlashSensorId(null), 700)
      }
    },
    [applySensorEvent, appendPoint],
  )

  const stream = useSensorStream(equipmentId, {
    onSensor: handleSensor,
    // 폴백 폴링: 최신값을 다시 읽어 수치를 갱신하고, 차트에도 3초 간격 포인트로 이어붙인다.
    // (SSE 가 막혀도 실시간 차트가 빈 화면으로 남지 않게 한다)
    onPoll: async (signal) => {
      const next = await reload(signal)
      ;(next[equipmentId] ?? []).forEach((sensor) => {
        if (sensor.value === null || sensor.value === undefined || !sensor.measuredAt) return
        appendPoint({
          sensorId: sensor.sensorId,
          equipmentId,
          type: sensor.sensorType,
          value: sensor.value,
          measuredAt: sensor.measuredAt,
          level: sensor.level ?? 'NORMAL',
        })
      })
    },
  })

  // 임계치·단위는 상세 응답을, 현재값은 latest/SSE 를 우선한다.
  const mergedSensors = useMemo((): SensorLatest[] => {
    const fromDetail = (sensors ?? []).map((sensor) => toSensorLatest(sensor, equipmentId))
    const fromLatest = sensorsByEquipment[equipmentId] ?? []

    if (fromDetail.length === 0) return fromLatest
    const latestById = new Map(fromLatest.map((sensor) => [sensor.sensorId, sensor]))

    return fromDetail
      .map((detail) => {
        const latest = latestById.get(detail.sensorId)
        if (!latest) return detail
        return {
          ...detail,
          value: latest.value ?? detail.value,
          measuredAt: latest.measuredAt ?? detail.measuredAt,
          level: latest.level,
          // 임계치는 latest 가 단일 출처 — null 은 '미설정'이라는 값이므로 ?? 로 상세의 옛 값으로 되돌리지 않는다.
          // (임계치 수정 직후 경계를 비웠을 때 이전 값이 되살아나는 것을 막는다. 키 자체가 없을 때만 상세 값 사용)
          warnLow: latest.warnLow !== undefined ? latest.warnLow : detail.warnLow,
          warnHigh: latest.warnHigh !== undefined ? latest.warnHigh : detail.warnHigh,
          critLow: latest.critLow !== undefined ? latest.critLow : detail.critLow,
          critHigh: latest.critHigh !== undefined ? latest.critHigh : detail.critHigh,
        }
      })
      .sort((a, b) => compareSensorType(a.sensorType, b.sensorType))
  }, [sensors, sensorsByEquipment, equipmentId])

  if (loading && mergedSensors.length === 0) {
    return <LoadingBlock label="센서 정보를 불러오는 중…" />
  }

  if (error && mergedSensors.length === 0) {
    return <ErrorState error={error} onRetry={() => void reload()} />
  }

  if (mergedSensors.length === 0) {
    return (
      <EmptyState
        title="등록된 센서가 없습니다"
        description="설비에 센서가 등록되면 실시간 차트가 표시됩니다."
        icon="◷"
      />
    )
  }

  const target = thresholdTarget
    ? mergedSensors.find((sensor) => sensor.sensorId === thresholdTarget.sensorId) ?? null
    : null

  const handleSaved = (updated: SensorDefinition) => {
    // 서버 응답으로 카드·실시간/이력 차트의 기준선을 즉시 갱신 (merged 가 latest 를 따른다)
    applyThresholds(equipmentId, updated.sensorId, updated)
    const label = SENSOR_TYPE_LABEL[updated.sensorType] ?? updated.sensorType
    setNotice(`${label} 임계치를 변경했습니다. 알람 판정에 즉시 반영되며, 변경자·사유가 이력에 기록되었습니다.`)
    setThresholdTarget(null)
  }

  return (
    <div>
      {notice && (
        <p className="form-notice" role="status">
          {notice}
          <button type="button" onClick={() => setNotice(null)} aria-label="알림 닫기">
            ✕
          </button>
        </p>
      )}

      <div className="equipment-toolbar">
        <StreamStatusBadge state={stream} />
        <span className="field-hint">
          최근 {LIVE_WINDOW_MINUTES}분 · 최대 {LIVE_MAX_POINTS}포인트 슬라이딩
        </span>
      </div>

      <div className="sensor-chart-grid">
        {mergedSensors.map((sensor) => (
          <SensorLiveChart
            key={sensor.sensorId}
            sensor={sensor}
            points={livePoints[sensor.sensorId] ?? []}
            flash={flashSensorId === sensor.sensorId}
            headerActions={
              <button
                type="button"
                className="btn btn-sm"
                aria-label={`${SENSOR_TYPE_LABEL[sensor.sensorType] ?? sensor.sensorType} ${canEditThreshold ? '임계치 설정' : '임계치 변경 이력'}`}
                onClick={() => {
                  setNotice(null)
                  setThresholdTarget({ sensorId: sensor.sensorId, mode: canEditThreshold ? 'edit' : 'history' })
                }}
              >
                {canEditThreshold ? '임계치 설정' : '임계치 이력'}
              </button>
            }
          />
        ))}
      </div>

      <SensorHistoryChart equipmentId={equipmentId} sensors={mergedSensors} />

      {target && thresholdTarget?.mode === 'edit' && canEditThreshold && (
        <ThresholdEditDialog
          key={target.sensorId}
          equipmentId={equipmentId}
          sensor={target}
          baseValue={baseValueOf(target.sensorId)}
          onClose={() => setThresholdTarget(null)}
          onSaved={handleSaved}
        />
      )}

      {target && thresholdTarget?.mode === 'history' && (
        <ThresholdHistoryDialog
          key={target.sensorId}
          equipmentId={equipmentId}
          sensor={target}
          onClose={() => setThresholdTarget(null)}
        />
      )}
    </div>
  )
}
