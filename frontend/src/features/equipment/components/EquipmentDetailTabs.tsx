import { useState } from 'react'
import { SensorTabPanel } from '@/features/sensor'
import { EquipmentAlarmTabPanel } from '@/features/alarm'
import { EquipmentInspectionTabPanel } from '@/features/inspection'
import { AlarmReportButton, EquipmentReportTabPanel, InspectionReportButton } from '@/features/aireport'
import { EquipmentStatusLogTable } from './EquipmentStatusLogTable'
import type { EquipmentSensor } from '../types'
import './equipment.css'

/** S-3 탭 구성 (docs/04 §3) + 상태 이력(F-2 상태 로그) */
const TABS = [
  { key: 'sensor', label: '센서' },
  { key: 'inspection', label: '점검' },
  { key: 'alarm', label: '알람' },
  { key: 'report', label: '리포트' },
  { key: 'statusLog', label: '상태 이력' },
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
 * 센서·점검·알람·리포트·상태 이력. 알람/점검 탭의 'AI 리포트' 버튼은 aireport 컴포넌트를 슬롯으로 주입한다
 * (alarm/inspection → aireport 직접 import 는 순환 의존이라 이 조립 지점에서 연결).
 */
export function EquipmentDetailTabs({
  equipmentId,
  sensors,
  statusLogReloadKey = 0,
}: EquipmentDetailTabsProps) {
  const [active, setActive] = useState<TabKey>('sensor')

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
        {active === 'inspection' && (
          <EquipmentInspectionTabPanel
            equipmentId={equipmentId}
            renderDetailActions={(inspection) => (
              <InspectionReportButton inspectionId={inspection.id} alarmId={inspection.alarmId} type={inspection.type} />
            )}
          />
        )}
        {active === 'alarm' && (
          <EquipmentAlarmTabPanel
            equipmentId={equipmentId}
            renderExtraActions={(alarm) => <AlarmReportButton alarmId={alarm.id} />}
          />
        )}
        {active === 'statusLog' && (
          <EquipmentStatusLogTable equipmentId={equipmentId} reloadKey={statusLogReloadKey} />
        )}
        {active === 'report' && <EquipmentReportTabPanel equipmentId={equipmentId} />}
      </div>
    </section>
  )
}
