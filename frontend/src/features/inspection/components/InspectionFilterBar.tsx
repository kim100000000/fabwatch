import { ROLE_LABEL } from '@/features/auth'
import type { UserLookupResult } from '@/features/auth'
import type { EquipmentSummary } from '@/features/equipment'
import {
  EMPTY_INSPECTION_FILTER,
  INSPECTION_TYPES,
  SHIFT_LABEL,
} from '../types'
import type { InspectionFilterValue, InspectionType, Shift } from '../types'
import './inspection.css'

interface InspectionFilterBarProps {
  value: InspectionFilterValue
  onChange: (value: InspectionFilterValue) => void
  equipments: EquipmentSummary[]
  /** 작업자 셀렉트용 사용자 목록 (GET /users/lookup) — 실패하면 셀렉트만 비활성화한다 */
  workers: UserLookupResult
  /** 로그인 사용자 id — '나' 버튼이 작업자로 고르는 값 */
  currentUserId: number | null
}

/** S-4 필터 — 설비 / 기간 / 유형 / 교대조 / 작업자(셀렉트 + '나' 빠른 선택) / NG 여부 (docs/03 3.4, FR-3.5) */
export function InspectionFilterBar({
  value,
  onChange,
  equipments,
  workers,
  currentUserId,
}: InspectionFilterBarProps) {
  const update = (patch: Partial<InspectionFilterValue>) => onChange({ ...value, ...patch })

  return (
    <div className="inspection-filter">
      <div className="field">
        <label htmlFor="insp-filter-equipment">설비</label>
        <select
          id="insp-filter-equipment"
          value={value.equipmentId ?? ''}
          onChange={(event) =>
            update({ equipmentId: event.target.value ? Number(event.target.value) : null })
          }
        >
          <option value="">전체</option>
          {equipments.map((equipment) => (
            <option key={equipment.id} value={equipment.id}>
              {equipment.code} · {equipment.name}
            </option>
          ))}
        </select>
      </div>

      <div className="field">
        <label htmlFor="insp-filter-from">시작일 (KST)</label>
        <input
          id="insp-filter-from"
          type="date"
          value={value.fromDate}
          max={value.toDate || undefined}
          onChange={(event) => update({ fromDate: event.target.value })}
        />
      </div>

      <div className="field">
        <label htmlFor="insp-filter-to">종료일 (KST)</label>
        <input
          id="insp-filter-to"
          type="date"
          value={value.toDate}
          min={value.fromDate || undefined}
          onChange={(event) => update({ toDate: event.target.value })}
        />
      </div>

      <div className="field">
        <label htmlFor="insp-filter-type">유형</label>
        <select
          id="insp-filter-type"
          value={value.type ?? ''}
          onChange={(event) =>
            update({ type: event.target.value ? (event.target.value as InspectionType) : null })
          }
        >
          <option value="">전체</option>
          {INSPECTION_TYPES.map((type) => (
            <option key={type} value={type}>
              {type}
            </option>
          ))}
        </select>
      </div>

      <div className="field">
        <label htmlFor="insp-filter-shift">교대조</label>
        <select
          id="insp-filter-shift"
          value={value.shift ?? ''}
          onChange={(event) =>
            update({ shift: event.target.value ? (event.target.value as Shift) : null })
          }
        >
          <option value="">전체</option>
          {(Object.keys(SHIFT_LABEL) as Shift[]).map((shift) => (
            <option key={shift} value={shift}>
              {SHIFT_LABEL[shift]}
            </option>
          ))}
        </select>
      </div>

      <div className="field worker-field">
        <label htmlFor="insp-filter-worker">작업자</label>
        <div className="worker-field-row">
          <select
            id="insp-filter-worker"
            value={workers.error || workers.loading ? '' : (value.workerId ?? '')}
            disabled={workers.loading || workers.error !== null}
            onChange={(event) =>
              update({ workerId: event.target.value ? Number(event.target.value) : null })
            }
          >
            {workers.loading ? (
              <option value="">불러오는 중…</option>
            ) : workers.error ? (
              <option value="">불러오지 못했습니다</option>
            ) : (
              <>
                <option value="">전체</option>
                {workers.users.map((worker) => (
                  <option key={worker.id} value={worker.id}>
                    {worker.name} ({ROLE_LABEL[worker.role] ?? worker.role})
                    {worker.id === currentUserId ? ' · 나' : ''}
                  </option>
                ))}
              </>
            )}
          </select>
          {/* '내 점검만' 편의 기능 — 사용자 목록 조회가 실패해도 id 로 바로 걸 수 있다. 다시 누르면 해제 */}
          {currentUserId !== null && (
            <button
              type="button"
              className={value.workerId === currentUserId ? 'filter-chip active' : 'filter-chip'}
              aria-pressed={value.workerId === currentUserId}
              title="내가 작업한 점검만 보기"
              onClick={() => update({ workerId: value.workerId === currentUserId ? null : currentUserId })}
            >
              나
            </button>
          )}
        </div>
        {workers.error && (
          <button type="button" className="field-hint worker-retry" onClick={workers.refetch}>
            작업자 목록 다시 불러오기
          </button>
        )}
      </div>

      <label className="check-inline">
        <input
          type="checkbox"
          checked={value.hasNg}
          onChange={(event) => update({ hasNg: event.target.checked })}
        />
        NG 포함만
      </label>

      <div className="filter-actions">
        <button type="button" className="btn btn-ghost" onClick={() => onChange(EMPTY_INSPECTION_FILTER)}>
          초기화
        </button>
      </div>
    </div>
  )
}
