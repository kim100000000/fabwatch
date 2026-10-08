import type { OpenAlarmCounts } from '../types'
import './equipment.css'

interface AlarmCountProps {
  counts: OpenAlarmCounts
  equipmentId: number
  /** 카드 뷰에서 ⚠ 아이콘을 붙일지 */
  withIcon?: boolean
}

/** 설비별 미해결 알람 수 셀 — 로딩/실패를 0 으로 폴백하지 않는다 */
export function AlarmCount({ counts, equipmentId, withIcon = false }: AlarmCountProps) {
  const icon = withIcon ? '⚠ ' : ''
  if (counts.state === 'loading') {
    return (
      <span className="alarm-count" data-zero="true" aria-label="미해결 알람 집계 중">
        {icon}…
      </span>
    )
  }
  if (counts.state === 'error') {
    return (
      <span className="alarm-count" data-zero="true" title="알람 집계를 불러오지 못했습니다">
        {icon}확인 실패
      </span>
    )
  }
  const count = counts.byEquipment[equipmentId] ?? 0
  return (
    <span className="alarm-count" data-zero={count === 0} aria-label={`미해결 알람 ${count}건`}>
      {icon}
      {count}
    </span>
  )
}
