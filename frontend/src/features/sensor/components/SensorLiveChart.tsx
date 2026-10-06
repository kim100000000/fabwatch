import { useMemo } from 'react'
import type { ReactNode } from 'react'
import {
  CartesianGrid,
  Line,
  LineChart,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { useChartTokens } from '@/shared/lib/cssToken'
import { formatKstTime } from '@/shared/lib/datetime'
import { SENSOR_TYPE_LABEL } from '../types'
import type { LivePoint, SensorLatest } from '../types'
import './sensor.css'

interface SensorLiveChartProps {
  sensor: SensorLatest
  /** 최근 5분 슬라이딩 포인트 (최대 150개 — 부모가 관리) */
  points: LivePoint[]
  /** 방금 값이 갱신됐는지 (임계치 초과 시 1회 깜빡임) */
  flash?: boolean
  /** 차트 헤더 아래 조치 영역 슬롯 (임계치 설정/이력 버튼 등 — 역할별 노출 판단은 호출부) */
  headerActions?: ReactNode
}

/** null/undefined 를 제외한 임계치만 모은다 */
function thresholdEntries(sensor: SensorLatest): { value: number; kind: 'warn' | 'crit'; label: string }[] {
  const entries: { value: number; kind: 'warn' | 'crit'; label: string }[] = []
  if (sensor.warnLow !== null && sensor.warnLow !== undefined) {
    entries.push({ value: sensor.warnLow, kind: 'warn', label: 'warnLow' })
  }
  if (sensor.warnHigh !== null && sensor.warnHigh !== undefined) {
    entries.push({ value: sensor.warnHigh, kind: 'warn', label: 'warnHigh' })
  }
  if (sensor.critLow !== null && sensor.critLow !== undefined) {
    entries.push({ value: sensor.critLow, kind: 'crit', label: 'critLow' })
  }
  if (sensor.critHigh !== null && sensor.critHigh !== undefined) {
    entries.push({ value: sensor.critHigh, kind: 'crit', label: 'critHigh' })
  }
  return entries
}

/**
 * 센서 실시간 차트 (docs/04 §4 SensorLiveChart, docs/03 F-5.2).
 * - 라인 색 --accent, warn 점선 --alarm-warning, crit 점선 --alarm-critical
 * - SSE 구독은 부모(SensorTabPanel)가 1개 연결로 처리하고 여기는 표시만 한다.
 */
export function SensorLiveChart({ sensor, points, flash = false, headerActions }: SensorLiveChartProps) {
  const tokens = useChartTokens()
  const thresholds = useMemo(() => thresholdEntries(sensor), [sensor])

  // 임계치 선이 잘리지 않도록 Y축 범위에 임계치를 포함시킨다.
  const yDomain = useMemo((): [number | 'auto', number | 'auto'] => {
    const values = points.map((point) => point.value)
    const candidates = [...values, ...thresholds.map((item) => item.value)]
    if (candidates.length === 0) return ['auto', 'auto']
    const min = Math.min(...candidates)
    const max = Math.max(...candidates)
    const pad = Math.max((max - min) * 0.15, 0.5)
    return [Number((min - pad).toFixed(2)), Number((max + pad).toFixed(2))]
  }, [points, thresholds])

  const level = sensor.level ?? 'NORMAL'
  const unit = sensor.unit ?? ''

  return (
    <div className="sensor-chart">
      <div className="sensor-chart-head">
        <span className="sensor-chart-title">
          {SENSOR_TYPE_LABEL[sensor.sensorType] ?? sensor.sensorType}
          <span className="sensor-type">{sensor.sensorType}</span>
        </span>
        <span className="sensor-value" data-level={level} data-flash={flash ? 'true' : undefined}>
          {sensor.value === null || sensor.value === undefined ? '-' : sensor.value.toFixed(1)}
          <span className="sensor-unit">{unit}</span>
        </span>
      </div>

      {(thresholds.length > 0 || headerActions) && (
        <div className="sensor-chart-sub">
          <div className="sensor-threshold-legend">
            {thresholds.map((item) => (
              <span key={item.label} className={item.kind}>
                {item.label} {item.value}
              </span>
            ))}
          </div>
          {headerActions && <div className="sensor-chart-actions">{headerActions}</div>}
        </div>
      )}

      {points.length === 0 ? (
        <div className="sensor-chart-empty">데이터 수신 대기 중… (2초 주기)</div>
      ) : (
        <div className="sensor-chart-body">
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={points} margin={{ top: 6, right: 8, bottom: 0, left: 0 }}>
              <CartesianGrid stroke={tokens.border} strokeDasharray="2 4" vertical={false} />
              <XAxis
                dataKey="t"
                type="number"
                scale="time"
                domain={['dataMin', 'dataMax']}
                tickFormatter={(value: number) => formatKstTime(value)}
                tick={{ fill: tokens.textSecondary, fontSize: 10 }}
                stroke={tokens.border}
                minTickGap={40}
              />
              <YAxis
                domain={yDomain}
                tick={{ fill: tokens.textSecondary, fontSize: 10 }}
                stroke={tokens.border}
                /* 압력처럼 음수 3자리(-110.5)가 잘리지 않도록 넉넉히 */
                width={60}
                tickFormatter={(value: number) => `${Number(value).toFixed(1)}`}
              />
              <Tooltip
                isAnimationActive={false}
                content={({ active, payload }) => {
                  if (!active || !payload || payload.length === 0) return null
                  const point = payload[0].payload as LivePoint
                  return (
                    <div className="sensor-tooltip">
                      <span className="tooltip-time">{formatKstTime(point.t)} KST</span>
                      <span className="tooltip-value">
                        {point.value.toFixed(2)} {unit}
                      </span>
                    </div>
                  )
                }}
              />
              {thresholds.map((item) => (
                <ReferenceLine
                  key={item.label}
                  y={item.value}
                  stroke={item.kind === 'warn' ? tokens.warning : tokens.critical}
                  strokeDasharray="5 4"
                  ifOverflow="extendDomain"
                />
              ))}
              <Line
                type="monotone"
                dataKey="value"
                stroke={tokens.accent}
                strokeWidth={2}
                dot={false}
                isAnimationActive={false}
              />
            </LineChart>
          </ResponsiveContainer>
        </div>
      )}
    </div>
  )
}
