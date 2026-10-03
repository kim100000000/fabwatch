import { statusLabel } from '@/shared/lib/equipmentStatus'
import { formatAvailability, formatMtbf, formatMttr } from '@/shared/lib/kpiFormat'
import type { KpiValues } from '../types'
import './kpi.css'

interface KpiTilesProps {
  values: KpiValues
  /** 대시보드용 큰 타일 / 설비 상세용 컴팩트 타일 */
  size?: 'compact' | 'large'
}

interface Tile {
  key: string
  label: string
  value: string
  /** 값 해석 도움말 — 타일 아래에 작게 항상 보인다 */
  hint: string
  /** 상태 색 포인트 (현장 표준 색 토큰 매핑용) */
  accent?: 'run' | 'down'
  /** 값이 '고장 없음'/'-' 처럼 수치가 아닐 때 흐리게 */
  muted?: boolean
}

/** 가동률/MTBF/MTTR/DOWN 횟수 4타일 (docs/03 F-5.4) */
export function KpiTiles({ values, size = 'compact' }: KpiTilesProps) {
  const down = statusLabel('DOWN')
  const tiles: Tile[] = [
    {
      key: 'availability',
      label: '가동률',
      value: formatAvailability(values.availability),
      hint: 'RUN ÷ (전체 − PM). PM 시간은 제외',
      accent: 'run',
      muted: values.availability === null,
    },
    {
      key: 'mtbf',
      label: 'MTBF',
      value: formatMtbf(values.mtbfHours),
      hint: `RUN 시간 ÷ ${down} 횟수`,
      muted: values.mtbfHours === null,
    },
    {
      key: 'mttr',
      label: 'MTTR',
      value: formatMttr(values.mttrMin),
      hint: `${down} 진입~이탈 평균 (진행 중 제외, 기간 이전에 진입한 건은 기간 내 구간만 반영)`,
      muted: values.mttrMin === null,
    },
    {
      key: 'down',
      label: `${down} 횟수`,
      value: `${values.downCount}회`,
      hint: `기간 내 ${down} 진입 횟수`,
      accent: values.downCount > 0 ? 'down' : undefined,
    },
  ]

  return (
    <div className="kpi-tiles" data-size={size}>
      {tiles.map((tile) => (
        <div
          key={tile.key}
          className="kpi-tile"
          data-accent={tile.accent}
          role="group"
          aria-label={`${tile.label} ${tile.value}`}
          title={tile.hint}
        >
          <span className="kpi-tile-label">{tile.label}</span>
          <span className="kpi-tile-value mono" data-muted={tile.muted || undefined}>
            {tile.value}
          </span>
          <span className="kpi-tile-hint">{tile.hint}</span>
        </div>
      ))}
    </div>
  )
}
