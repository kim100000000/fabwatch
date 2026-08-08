import { useState } from 'react'
import { EmptyState } from '@/shared/ui'
import { EquipmentStatusLogTable } from './EquipmentStatusLogTable'
import './equipment.css'

/** S-3 탭 구성 (docs/04 §3) + 상태 이력(F-2 상태 로그) */
const TABS = [
  { key: 'sensor', label: '센서', next: '2주차: 실시간 차트 4개(2×2) + 기간 이력 차트 (SSE 구독)' },
  { key: 'inspection', label: '점검', next: '3주차: 설비별 점검 이력 테이블 + PM 스케줄 카드' },
  { key: 'alarm', label: '알람', next: '2주차: 알람 테이블(OPEN 상단 고정) + ACK/RESOLVE 인라인' },
  { key: 'report', label: '리포트', next: '3주차: AI 리포트 카드 목록 (DRAFT/CONFIRMED 배지)' },
  { key: 'statusLog', label: '상태 이력', next: '' },
] as const

type TabKey = (typeof TABS)[number]['key']

interface EquipmentDetailTabsProps {
  equipmentId: number
  /** 상태 변경 직후 상태 이력 탭을 다시 읽게 하는 키 */
  statusLogReloadKey?: number
}

/**
 * 설비 상세 탭.
 * 상태 이력 탭만 구현돼 있고 나머지는 각 도메인 feature 구현 라운드에서 채운다.
 */
export function EquipmentDetailTabs({ equipmentId, statusLogReloadKey = 0 }: EquipmentDetailTabsProps) {
  const [active, setActive] = useState<TabKey>('sensor')
  const activeTab = TABS.find((tab) => tab.key === active) ?? TABS[0]

  return (
    <section>
      <div className="tab-bar" role="tablist">
        {TABS.map((tab) => (
          <button
            key={tab.key}
            type="button"
            role="tab"
            aria-selected={active === tab.key}
            className={active === tab.key ? 'active' : undefined}
            onClick={() => setActive(tab.key)}
          >
            {tab.label}
          </button>
        ))}
      </div>

      <div className="tab-panel" role="tabpanel">
        {active === 'statusLog' ? (
          <EquipmentStatusLogTable equipmentId={equipmentId} reloadKey={statusLogReloadKey} />
        ) : (
          <EmptyState
            title="다음 라운드 구현"
            description={activeTab.next}
            icon="◷"
            action={<span className="placeholder-note">placeholder</span>}
          />
        )}
      </div>
    </section>
  )
}
