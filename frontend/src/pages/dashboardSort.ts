import type { EquipmentSummary } from '@/features/equipment'
import type { AlarmSeverity, OpenAlarmSummary } from '@/features/alarm'

const SEVERITY_RANK: Record<AlarmSeverity, number> = { WARNING: 1, MAJOR: 2, CRITICAL: 3 }

/**
 * 대시보드 카드 정렬 — 지금 봐야 하는 설비가 위로 오게 한다.
 *  1) DOWN  2) 미해결 알람 있음(심각도 높은 순 → 건수 많은 순)  3) PM 지연  4) 나머지(설비 코드순)
 */
export function sortEquipmentsForDashboard(
  equipments: EquipmentSummary[],
  summary: OpenAlarmSummary | null,
): EquipmentSummary[] {
  const tierOf = (equipment: EquipmentSummary): number => {
    if (equipment.status === 'DOWN') return 0
    if ((summary?.byEquipment[equipment.id]?.count ?? 0) > 0) return 1
    if (equipment.pmOverdue) return 2
    return 3
  }
  return [...equipments].sort((a, b) => {
    const byTier = tierOf(a) - tierOf(b)
    if (byTier !== 0) return byTier
    // DOWN·알람 그룹 안에서는 알람 심각도/건수가 높은 설비 먼저
    const alarmA = summary?.byEquipment[a.id]
    const alarmB = summary?.byEquipment[b.id]
    const bySeverity = (alarmB ? SEVERITY_RANK[alarmB.maxSeverity] : 0) - (alarmA ? SEVERITY_RANK[alarmA.maxSeverity] : 0)
    if (bySeverity !== 0) return bySeverity
    const byCount = (alarmB?.count ?? 0) - (alarmA?.count ?? 0)
    if (byCount !== 0) return byCount
    return a.code.localeCompare(b.code)
  })
}
