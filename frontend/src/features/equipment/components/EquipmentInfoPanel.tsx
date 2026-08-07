import { formatKst, formatKstDate } from '@/shared/lib/datetime'
import type { EquipmentDetail } from '../types'
import './equipment.css'

interface EquipmentInfoPanelProps {
  equipment: EquipmentDetail
}

/** S-3 상단 설비 기본정보 — 시각은 전부 KST 변환 후 표시 */
export function EquipmentInfoPanel({ equipment }: EquipmentInfoPanelProps) {
  const sensorCount = equipment.sensors?.length ?? 0
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
        <dd>{sensorCount}개</dd>
      </div>
      <div>
        <dt>미해결 알람</dt>
        <dd>{equipment.openAlarmCount ?? 0}건</dd>
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
