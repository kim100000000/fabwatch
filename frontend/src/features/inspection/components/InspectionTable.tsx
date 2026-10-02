import { formatDuration, formatKst } from '@/shared/lib/datetime'
import { CAUSE_4M_LABEL } from '../types'
import type { Inspection } from '../types'
import './inspection.css'

interface InspectionTableProps {
  inspections: Inspection[]
  /** 행 클릭 → 상세 모달 */
  onSelect: (inspection: Inspection) => void
  /** 설비가 고정된 화면(S-3 점검 탭)에서는 설비 컬럼을 숨긴다 */
  hideEquipment?: boolean
}

/** 승인 여부 칸 — 승인됨 / NG 확인 대기 / 미승인 */
function ReviewState({ inspection }: { inspection: Inspection }) {
  if (inspection.reviewedBy != null || inspection.reviewedAt) {
    return (
      <span className="review-state" data-reviewed="true">
        승인{inspection.reviewedByName ? ` (${inspection.reviewedByName})` : ''}
      </span>
    )
  }
  return (
    <span className="review-state" data-reviewed="false" data-pending-ng={inspection.hasNg}>
      {inspection.hasNg ? '확인 필요' : '미승인'}
    </span>
  )
}

/** S-4 점검 이력 테이블 — 시각은 KST 변환 표시, NG 포함 행은 붉은 표시 (docs/03 3.2) */
export function InspectionTable({ inspections, onSelect, hideEquipment = false }: InspectionTableProps) {
  return (
    <div className="table-scroll">
      <table className="data-table inspection-table">
        <thead>
          <tr>
            <th>일시 (KST)</th>
            {!hideEquipment && <th>설비</th>}
            <th>유형</th>
            <th>교대</th>
            <th>작업자</th>
            <th>소요시간</th>
            <th>4M</th>
            <th>연계 알람</th>
            <th>NG</th>
            <th>승인</th>
          </tr>
        </thead>
        <tbody>
          {inspections.map((inspection) => (
            <tr
              key={inspection.id}
              data-ng={inspection.hasNg}
              tabIndex={0}
              onClick={() => onSelect(inspection)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') onSelect(inspection)
              }}
            >
              <td className="mono">{formatKst(inspection.startedAt)}</td>
              {!hideEquipment && (
                <td>
                  <span className="mono">{inspection.equipmentCode}</span> {inspection.equipmentName}
                </td>
              )}
              <td>
                <span className="type-badge" data-type={inspection.type}>
                  {inspection.type}
                </span>
              </td>
              <td className="mono">{inspection.shift}</td>
              <td>{inspection.workerName}</td>
              <td className="mono">{formatDuration(inspection.durationMin)}</td>
              <td>{inspection.cause4m ? CAUSE_4M_LABEL[inspection.cause4m] : '-'}</td>
              <td className="mono">{inspection.alarmId != null ? `#${inspection.alarmId}` : '-'}</td>
              <td>{inspection.hasNg ? <span className="ng-flag">NG</span> : '-'}</td>
              <td>
                <ReviewState inspection={inspection} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
