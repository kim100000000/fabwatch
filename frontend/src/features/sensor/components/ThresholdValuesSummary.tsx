import { THRESHOLD_FIELDS } from '../thresholdForm'
import type { SensorLatest } from '../types'
import './sensor.css'

/** 현재 임계치 4값 요약 (다이얼로그 상단) — 비어 있는 경계는 '미설정' */
export function ThresholdValuesSummary({ sensor }: { sensor: SensorLatest }) {
  const unit = sensor.unit ?? ''
  return (
    <dl className="threshold-current" aria-label="현재 임계치">
      {THRESHOLD_FIELDS.map(({ key, label, kind }) => {
        const value = sensor[key]
        return (
          <div key={key} data-kind={kind}>
            <dt>{label}</dt>
            <dd className="mono">
              {value === null || value === undefined ? '미설정' : `${value}${unit ? ` ${unit}` : ''}`}
            </dd>
          </div>
        )
      })}
    </dl>
  )
}
