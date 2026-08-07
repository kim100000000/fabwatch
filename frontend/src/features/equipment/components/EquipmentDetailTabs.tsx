import { useState } from 'react'
import { EmptyState } from '@/shared/ui'
import './equipment.css'

/** S-3 탭 구성 (docs/04 §3) */
const TABS = [
  { key: 'sensor', label: '센서', next: '2주차: 실시간 차트 4개(2×2) + 기간 이력 차트 (SSE 구독)' },
  { key: 'inspection', label: '점검', next: '3주차: 설비별 점검 이력 테이블 + PM 스케줄 카드' },
  { key: 'alarm', label: '알람', next: '2주차: 알람 테이블(OPEN 상단 고정) + ACK/RESOLVE 인라인' },
  { key: 'report', label: '리포트', next: '3주차: AI 리포트 카드 목록 (DRAFT/CONFIRMED 배지)' },
] as const

type TabKey = (typeof TABS)[number]['key']

/**
 * 설비 상세 탭 골격.
 * 이번 라운드는 탭 전환만 동작하고 내용은 플레이스홀더 — 각 탭 내용은 해당 도메인 feature 에서 구현한다.
 */
export function EquipmentDetailTabs() {
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
        <EmptyState
          title="다음 라운드 구현"
          description={activeTab.next}
          icon="◷"
          action={<span className="placeholder-note">placeholder</span>}
        />
      </div>
    </section>
  )
}
