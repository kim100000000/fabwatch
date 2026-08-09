import { formatKst, formatKstDate } from '@/shared/lib/datetime'
import type { EquipmentDetail } from '../types'
import './equipment.css'

interface EquipmentInfoPanelProps {
  equipment: EquipmentDetail
}

/** S-3 상단 설비 기본정보 — 시각은 전부 KST 변환 후 표시 */
export function EquipmentInfoPanel({ equipment }: EquipmentInfoPanelProps) {
  // 백엔드 EquipmentDetailResponse 는 아직 sensors/openAlarmCount/pmSchedule 을 내려주지 않는다.
  // 이때 0 으로 폴백하면 "미해결 알람 0건" 옆에 알람 탭이 실제 알람을 띄우는 모순이 생기므로,
  // "미제공(-)"과 "실제 0"을 구분해 표시한다. 필드가 추가되면 자동으로 숫자가 나온다.
  const sensorCount = equipment.sensors?.length
  const openAlarmCount = equipment.openAlarmCount
  const nextDueAt = equipment.pmSchedule?.nextDueAt

  return (
    <dl className="detail-info">
      <div>
        <dt>라인 / 공정</dt>
        <dd>{[equipment.lineName, equipment.processName].filter(Boolean).join(' / ') || '-'}</dd>
      </div>
      <div>
        <dt>모델 / 제조사</dt>
        <dd>{[equipment.modelName, equipment.maker].filter(Boolean).join(' / ') || '-'}</dd>
      </div>
      <div>
        <dt>설치일</dt>
        <dd>{equipment.installedAt ? formatKstDate(equipment.installedAt) : '-'}</dd>
      </div>
      <div>
        <dt>담당 엔지니어</dt>
        <dd>{equipment.managerName ?? '-'}</dd>
      </div>
      <div>
        <dt>센서</dt>
        <dd>{sensorCount === undefined ? '-' : `${sensorCount}개`}</dd>
      </div>
      <div>
        <dt>미해결 알람</dt>
        <dd>{openAlarmCount === undefined ? '-' : `${openAlarmCount}건`}</dd>
      </div>
      <div>
        <dt>다음 PM 예정</dt>
        <dd style={{ color: equipment.pmSchedule?.overdue ? 'var(--status-down)' : undefined }}>
          {nextDueAt ? formatKst(nextDueAt) : '-'}
          {equipment.pmSchedule?.overdue ? ' (OVERDUE)' : ''}
        </dd>
      </div>
    </dl>
  )
}
