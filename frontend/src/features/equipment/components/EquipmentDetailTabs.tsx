import { useState } from 'react'
import { EmptyState } from '@/shared/ui'
import { SensorTabPanel } from '@/features/sensor'
import { EquipmentAlarmTabPanel } from '@/features/alarm'
import { EquipmentStatusLogTable } from './EquipmentStatusLogTable'
import type { EquipmentSensor } from '../types'
import './equipment.css'

/** S-3 탭 구성 (docs/04 §3) + 상태 이력(F-2 상태 로그) */
const TABS = [
  { key: 'sensor', label: '센서', next: '' },
  { key: 'inspection', label: '점검', next: '3주차: 설비별 점검 이력 테이블 + PM 스케줄 카드' },
  { key: 'alarm', label: '알람', next: '' },
  { key: 'report', label: '리포트', next: '3주차: AI 리포트 카드 목록 (DRAFT/CONFIRMED 배지)' },
  { key: 'statusLog', label: '상태 이력', next: '' },
] as const

type TabKey = (typeof TABS)[number]['key']

interface EquipmentDetailTabsProps {
  equipmentId: number
  /** GET /equipments/{id} 가 준 센서 정보 (임계치·단위 출처) — 센서 탭에서 사용 */
  sensors?: EquipmentSensor[]
  /** 상태 변경 직후 상태 이력 탭을 다시 읽게 하는 키 */
  statusLogReloadKey?: number
}

/**
 * 설비 상세 탭.
 * 센서·알람·상태 이력은 구현 완료, 점검·리포트는 각 도메인 구현 라운드(3주차)에서 채운다.
 */
export function EquipmentDetailTabs({
  equipmentId,
  sensors,
  statusLogReloadKey = 0,
}: EquipmentDetailTabsProps) {
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
        {active === 'sensor' && <SensorTabPanel equipmentId={equipmentId} sensors={sensors} />}
        {active === 'alarm' && <EquipmentAlarmTabPanel equipmentId={equipmentId} />}
        {active === 'statusLog' && (
          <EquipmentStatusLogTable equipmentId={equipmentId} reloadKey={statusLogReloadKey} />
        )}
        {(active === 'inspection' || active === 'report') && (
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
