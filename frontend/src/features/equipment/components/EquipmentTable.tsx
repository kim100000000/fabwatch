import { StatusBadge } from '@/shared/ui'
import type { EquipmentSummary } from '../types'
import './equipment.css'

interface EquipmentTableProps {
  equipments: EquipmentSummary[]
  onSelect: (equipmentId: number) => void
  /** 주면 "수정" 액션 컬럼이 붙는다 (ADMIN 만 넘긴다) */
  onEdit?: (equipmentId: number) => void
}

/** 설비 목록 테이블 (S-2) — 행 클릭 시 S-3 상세로 이동 */
export function EquipmentTable({ equipments, onSelect, onEdit }: EquipmentTableProps) {
  return (
    <div className="table-scroll">
      <table className="data-table">
        <thead>
          <tr>
            <th>설비 코드</th>
            <th>설비명</th>
            <th>상태</th>
            <th>라인 / 공정</th>
            <th>모델</th>
            <th>제조사</th>
            <th>담당</th>
            <th>미해결 알람</th>
            {onEdit && <th aria-label="액션" />}
          </tr>
        </thead>
        <tbody>
          {equipments.map((equipment) => (
            <tr
              key={equipment.id}
              onClick={() => onSelect(equipment.id)}
              tabIndex={0}
              onKeyDown={(event) => {
                if (event.key === 'Enter') onSelect(equipment.id)
              }}
            >
              <td className="mono">{equipment.code}</td>
              <td>{equipment.name}</td>
              <td>
                <StatusBadge status={equipment.status} />
              </td>
              <td>{formatLocation(equipment)}</td>
              <td>{equipment.modelName ?? '-'}</td>
              <td>{equipment.maker ?? '-'}</td>
              <td>{equipment.managerName ?? '-'}</td>
              <td>
                <span className="alarm-count" data-zero={!equipment.openAlarmCount}>
                  {equipment.openAlarmCount ?? 0}
                </span>
              </td>
              {onEdit && (
                <td>
                  {/* 행 클릭(상세 이동)과 겹치지 않도록 전파를 막는다 */}
                  <button
                    type="button"
                    className="btn btn-ghost btn-sm"
                    onClick={(event) => {
                      event.stopPropagation()
                      onEdit(equipment.id)
                    }}
                  >
                    수정
                  </button>
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function formatLocation(equipment: EquipmentSummary): string {
  const parts = [equipment.lineName, equipment.processName].filter(Boolean)
  return parts.length > 0 ? parts.join(' / ') : '-'
}
