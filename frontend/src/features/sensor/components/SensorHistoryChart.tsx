import { useMemo, useState } from 'react'
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
import type { SensorType } from '@/features/equipment'
import { EmptyState, ErrorState, LoadingBlock } from '@/shared/ui'
import { useChartTokens } from '@/shared/lib/cssToken'
import { formatKst, formatKstShort } from '@/shared/lib/datetime'
import { useSensorHistory } from '../api/useSensorHistory'
import { HISTORY_RANGES, SENSOR_TYPE_LABEL } from '../types'
import type { HistoryRange, SensorLatest } from '../types'
import './sensor.css'

interface SensorHistoryChartProps {
  equipmentId: number
  /** 임계치·단위를 얻기 위한 센서 목록 (선택 칩도 여기서 만든다) */
  sensors: SensorLatest[]
}

/** 차트에 넣기 좋은 형태로 변환 (x=epoch ms) */
interface HistoryChartPoint {
  t: number
  value: number
  minValue?: number | null
  maxValue?: number | null
}

/**
 * 기간 선택 이력 차트 (docs/03 F-5.2, docs/04 §3 S-3 탭1).
 * 1시간/24시간/7일 중 선택 → RAW/1M 전환은 백엔드가 판단하고 응답 granularity 로 알려준다.
 */
export function SensorHistoryChart({ equipmentId, sensors }: SensorHistoryChartProps) {
  const [sensorType, setSensorType] = useState<SensorType | null>(sensors[0]?.sensorType ?? null)
  const [range, setRange] = useState<HistoryRange>('1H')

  const activeType = sensorType ?? sensors[0]?.sensorType ?? null
  const sensor = sensors.find((item) => item.sensorType === activeType) ?? null

  const tokens = useChartTokens()
  const { points, granularity, loading, error, refetch } = useSensorHistory(
    equipmentId,
    activeType,
    range,
  )

  const chartPoints = useMemo(
    (): HistoryChartPoint[] =>
      points
        .map((point) => ({
          t: new Date(point.at).getTime(),
          value: point.value,
          minValue: point.minValue,
          maxValue: point.maxValue,
        }))
        .filter((point) => !Number.isNaN(point.t))
        .sort((a, b) => a.t - b.t),
    [points],
  )

  const thresholds = useMemo(() => {
    if (!sensor) return []
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
  }, [sensor])

  const unit = sensor?.unit ?? ''

  return (
    <section className="sensor-history">
      <div className="sensor-history-head">
        <h3 className="sensor-chart-title">이력 차트</h3>

        <div className="status-filter">
          {sensors.map((item) => (
            <button
              key={item.sensorId}
              type="button"
              className={item.sensorType === activeType ? 'filter-chip active' : 'filter-chip'}
              onClick={() => setSensorType(item.sensorType)}
            >
              {SENSOR_TYPE_LABEL[item.sensorType] ?? item.sensorType}
            </button>
          ))}
        </div>

        <div className="spacer" />

        <div className="status-filter">
          {HISTORY_RANGES.map((item) => (
            <button
              key={item.key}
              type="button"
              className={item.key === range ? 'filter-chip active' : 'filter-chip'}
              onClick={() => setRange(item.key)}
            >
              {item.label}
            </button>
          ))}
        </div>

        {granularity && (
          <span className="granularity-badge" data-granularity={granularity} title="서버가 선택한 데이터 소스">
            {granularity === 'RAW' ? 'RAW 원본' : '1M 집계'}
          </span>
        )}

        <button type="button" className="btn btn-sm btn-ghost" onClick={refetch}>
          새로고침
        </button>
      </div>

      {loading && chartPoints.length === 0 && <LoadingBlock label="이력을 불러오는 중…" />}

      {!loading && error && <ErrorState error={error} onRetry={refetch} />}

      {!error && !loading && chartPoints.length === 0 && (
        <EmptyState
          title="해당 기간 데이터가 없습니다"
          description="시뮬레이터가 동작 중인지 확인하거나 기간을 넓혀 보세요."
          icon="◷"
        />
      )}

      {!error && chartPoints.length > 0 && (
        <div className="sensor-history-body">
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={chartPoints} margin={{ top: 8, right: 12, bottom: 0, left: 0 }}>
              <CartesianGrid stroke={tokens.border} strokeDasharray="2 4" vertical={false} />
              <XAxis
                dataKey="t"
                type="number"
                scale="time"
                domain={['dataMin', 'dataMax']}
                tickFormatter={(value: number) => formatKstShort(value)}
                tick={{ fill: tokens.textSecondary, fontSize: 10 }}
                stroke={tokens.border}
                minTickGap={48}
              />
              <YAxis
                tick={{ fill: tokens.textSecondary, fontSize: 10 }}
                stroke={tokens.border}
                /* 압력처럼 음수 3자리(-110.5)가 잘리지 않도록 넉넉히 */
                width={60}
                domain={['auto', 'auto']}
              />
              <Tooltip
                isAnimationActive={false}
                content={({ active, payload }) => {
                  if (!active || !payload || payload.length === 0) return null
                  const point = payload[0].payload as HistoryChartPoint
                  return (
                    <div className="sensor-tooltip">
                      <span className="tooltip-time">{formatKst(point.t)} KST</span>
                      <span className="tooltip-value">
                        {point.value?.toFixed(2)} {unit}
                      </span>
                      {granularity === '1M' &&
                        point.minValue !== null &&
                        point.minValue !== undefined && (
                          <span className="tooltip-time">
                            min {point.minValue} / max {point.maxValue}
                          </span>
                        )}
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
    </section>
  )
}
