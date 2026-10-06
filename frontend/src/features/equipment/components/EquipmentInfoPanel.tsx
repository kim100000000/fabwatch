import type { ReactNode } from 'react'
import { formatKstDate } from '@/shared/lib/datetime'
import type { EquipmentDetail, EquipmentHeaderSummary, HeaderValue } from '../types'
import './equipment.css'

interface EquipmentInfoPanelProps {
  equipment: EquipmentDetail
  /**
   * 센서 / 미해결 알람 / 다음 PM 예정 — GET /equipments/{id} 는 기본정보만 주므로(도메인 경계 원칙)
   * 페이지가 sensor·alarm·inspection 의 기존 API 를 조합해 주입한다 (FR-2.4).
   */
  summary: EquipmentHeaderSummary
}

/** 로딩 '…' / 실패 '확인 실패' / 성공 render — 로딩·실패를 0 이나 '-' 로 폴백하지 않는다 */
function renderHeaderValue<T>(value: HeaderValue<T>, render: (value: T) => ReactNode): ReactNode {
  if (value.state === 'loading') {
    return (
      <span className="info-value-muted" aria-busy="true" aria-label="불러오는 중">
        …
      </span>
    )
  }
  if (value.state === 'error') return <span className="info-value-danger">확인 실패</span>
  return render(value.value)
}

/** S-3 상단 설비 기본정보 — 시각은 전부 KST 변환 후 표시 */
export function EquipmentInfoPanel({ equipment, summary }: EquipmentInfoPanelProps) {
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
        <dd>{renderHeaderValue(summary.sensors, (text) => text)}</dd>
      </div>
      <div>
        <dt>미해결 알람</dt>
        <dd>
          {renderHeaderValue(summary.openAlarmCount, (count) => (
            <span className={count > 0 ? 'info-value-alert' : undefined}>{count}건</span>
          ))}
        </dd>
      </div>
      <div>
        <dt>다음 PM 예정 (KST)</dt>
        <dd>
          {renderHeaderValue(summary.nextPm, (pm) => {
            if (!pm) return <span className="info-value-muted">미설정</span>
            if (!pm.overdue) return formatKstDate(pm.nextDueAt)
            return (
              <span className="info-value-danger">
                {formatKstDate(pm.nextDueAt)} · {pm.overdueDays > 0 ? `${pm.overdueDays}일 경과` : 'OVERDUE'}
              </span>
            )
          })}
        </dd>
      </div>
    </dl>
  )
}
