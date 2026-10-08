import { useState } from 'react'
import { SensorTabPanel } from '@/features/sensor'
import type { SensorFeed } from '@/features/sensor'
import { EquipmentAlarmTabPanel } from '@/features/alarm'
import { EquipmentInspectionTabPanel } from '@/features/inspection'
import { AlarmReportButton, EquipmentReportTabPanel, InspectionReportButton } from '@/features/aireport'
import { EquipmentStatusLogTable } from './EquipmentStatusLogTable'
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
  /** 페이지 수준에서 연 실시간 스트림 피드 (센서 탭이 구독) */
  sensorFeed: SensorFeed
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
  sensorFeed,
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
        {active === 'sensor' && <SensorTabPanel equipmentId={equipmentId} feed={sensorFeed} />}
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
            // AI 리포트는 센서/수동 알람의 미해결 건에만 — PM 지연·해제 완료 알람에는 숨긴다
            renderExtraActions={(alarm) =>
              alarm.alarmType !== 'PM_OVERDUE' && alarm.status !== 'RESOLVED' ? (
                <AlarmReportButton alarmId={alarm.id} />
              ) : null
            }
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
