import type { ReactNode } from 'react'
import { statusLabel } from '@/shared/lib/equipmentStatus'
import { Term } from '@/shared/ui'
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
  /** 라벨 자리에 그릴 노드 (용어 풀이 툴팁 등) — 없으면 label */
  labelNode?: ReactNode
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
  // 기간 이전에 DOWN 에 진입해 기간 내내 DOWN 이면 '진입 횟수 0회 + 가동률 0%' 로 모순처럼 보인다 — 힌트로 설명한다
  const carriedDown = values.availability === 0 && values.downCount === 0
  const carriedHint = `기간 이전부터 계속 ${down} 입니다. 횟수는 기간 내 새로 진입한 건만 세어 0회로 보입니다.`
  const tiles: Tile[] = [
    {
      key: 'availability',
      label: '가동률',
      labelNode: <Term term="AVAILABILITY">가동률</Term>,
      value: formatAvailability(values.availability),
      hint: carriedDown ? carriedHint : 'RUN ÷ (전체 − PM). PM 시간은 제외',
      accent: 'run',
      muted: values.availability === null,
    },
    {
      key: 'mtbf',
      label: 'MTBF',
      labelNode: <Term term="MTBF" />,
      value: formatMtbf(values.mtbfHours),
      hint: carriedDown ? `${down} 진입이 기간 밖이라 이 기간에는 "고장 없음"으로 계산됩니다.` : `RUN 시간 ÷ ${down} 횟수`,
      muted: values.mtbfHours === null,
    },
    {
      key: 'mttr',
      label: 'MTTR',
      labelNode: <Term term="MTTR" />,
      value: formatMttr(values.mttrMin),
      hint: `${down} 진입~이탈 평균 (진행 중 제외, 기간 이전에 진입한 건은 기간 내 구간만 반영)`,
      muted: values.mttrMin === null,
    },
    {
      key: 'down',
      label: `${down} 횟수`,
      value: `${values.downCount}회`,
      hint: carriedDown ? carriedHint : `기간 내 ${down} 진입 횟수`,
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
          <span className="kpi-tile-label">{tile.labelNode ?? tile.label}</span>
          <span className="kpi-tile-value mono" data-muted={tile.muted || undefined}>
            {tile.value}
          </span>
          <span className="kpi-tile-hint">{tile.hint}</span>
        </div>
      ))}
    </div>
  )
}
