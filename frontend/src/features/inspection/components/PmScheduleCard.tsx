import { useState } from 'react'
import type { FormEvent } from 'react'
import { useAuth } from '@/app/providers/useAuth'
import { toApiError } from '@/shared/api'
import { formatKst } from '@/shared/lib/datetime'
import { toUserMessage } from '@/shared/lib/errorMessage'
import { Spinner, Term } from '@/shared/ui'
import { updatePmSchedule } from '../api/inspectionApi'
import { PM_CYCLE_LABEL, WEEKDAY_LABEL, describeCycle } from '../types'
import type { PmCycleType, PmSchedule } from '../types'
import './inspection.css'

interface PmScheduleCardProps {
  equipmentId: number
  /** 해당 설비 PM 스케줄 — 아직 설정 전이면 null */
  schedule: PmSchedule | null
  /** 스케줄 조회 중 — true 면 '설정되지 않았습니다' 대신 로딩 문구를 보여준다 */
  loading?: boolean
  /** 스케줄 조회 실패 — true 면 '미설정'으로 오인되지 않게 설정 버튼과 안내를 숨긴다 */
  failed?: boolean
  /** 주기 설정 성공 — 부모가 스케줄을 다시 읽는다 */
  onSaved: () => void
}

const CYCLE_TYPES: PmCycleType[] = ['DAILY', 'WEEKLY', 'MONTHLY']

/**
 * S-3 점검 탭 PM 스케줄 카드 (docs/04 §3 탭2, docs/03 3.3).
 * 주기 / 마지막 완료 / 다음 예정일을 보여주고, OVERDUE 면 빨강 + 경과일.
 * 주기 설정·변경 폼은 ENGINEER+ 만 (PUT /equipments/{id}/pm-schedule — 서버가 nextDueAt 재계산).
 */
export function PmScheduleCard({ equipmentId, schedule, loading = false, failed = false, onSaved }: PmScheduleCardProps) {
  const { user } = useAuth()
  // 조회 중/실패 상태에서는 미설정으로 오인한 채 덮어쓰지 않도록 편집을 막는다
  const canEdit = (user?.role === 'ADMIN' || user?.role === 'ENGINEER') && !loading && !failed

  const [editing, setEditing] = useState(false)
  const [cycleType, setCycleType] = useState<PmCycleType>(schedule?.cycleType ?? 'WEEKLY')
  const [cycleValue, setCycleValue] = useState<number>(schedule?.cycleValue ?? 1)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (submitting) return
    setSubmitting(true)
    setError(null)
    try {
      await updatePmSchedule(equipmentId, {
        cycleType,
        // DAILY 는 값이 없다 (서버 규칙: WEEKLY 1~7, MONTHLY 1~28, DAILY null)
        cycleValue: cycleType === 'DAILY' ? null : cycleValue,
      })
      setEditing(false)
      onSaved()
    } catch (cause) {
      setError(toUserMessage(toApiError(cause)))
    } finally {
      setSubmitting(false)
    }
  }

  const openEditor = () => {
    // 열 때마다 현재 스케줄 값으로 폼을 초기화
    setCycleType(schedule?.cycleType ?? 'WEEKLY')
    setCycleValue(schedule?.cycleValue ?? 1)
    setError(null)
    setEditing(true)
  }

  const changeCycleType = (next: PmCycleType) => {
    setCycleType(next)
    // 유형이 바뀌면 값 범위가 달라지므로 기본값으로 되돌린다
    setCycleValue(1)
  }

  return (
    <div className="pm-card" data-overdue={schedule?.overdue ? 'true' : 'false'}>
      <div className="pm-card-head">
        <span className="pm-card-title">
          <Term term="PM" /> 스케줄
        </span>
        {canEdit && !editing && (
          <button type="button" className="btn btn-sm" onClick={openEditor}>
            {schedule ? '주기 변경' : '주기 설정'}
          </button>
        )}
      </div>

      {schedule ? (
        <dl className="detail-info" style={{ marginBottom: 0 }}>
          <div>
            <dt>주기</dt>
            <dd>{describeCycle(schedule.cycleType, schedule.cycleValue)}</dd>
          </div>
          <div>
            <dt>마지막 완료 (KST)</dt>
            <dd>{schedule.lastDoneAt ? formatKst(schedule.lastDoneAt) : '-'}</dd>
          </div>
          <div>
            <dt>다음 예정 (KST)</dt>
            <dd className={schedule.overdue ? 'pm-overdue-text' : undefined}>
              {formatKst(schedule.nextDueAt)}
              {schedule.overdue
                ? schedule.overdueDays > 0
                  ? ` · PM 지연 ${schedule.overdueDays}일 경과`
                  : ' · PM 지연'
                : ''}
            </dd>
          </div>
        </dl>
      ) : loading ? (
        <p className="field-hint" role="status">
          PM 스케줄을 불러오는 중…
        </p>
      ) : failed ? null : (
        <p className="field-hint">PM 스케줄이 설정되지 않았습니다.{canEdit ? ' [주기 설정]으로 등록하세요.' : ''}</p>
      )}

      {canEdit && editing && (
        <form className="pm-form" onSubmit={handleSubmit}>
          <div className="field">
            <label htmlFor="pm-cycle-type">주기</label>
            <select
              id="pm-cycle-type"
              value={cycleType}
              onChange={(event) => changeCycleType(event.target.value as PmCycleType)}
            >
              {CYCLE_TYPES.map((type) => (
                <option key={type} value={type}>
                  {PM_CYCLE_LABEL[type]} ({type})
                </option>
              ))}
            </select>
          </div>

          {cycleType === 'WEEKLY' && (
            <div className="field">
              <label htmlFor="pm-cycle-weekday">요일</label>
              <select
                id="pm-cycle-weekday"
                value={cycleValue}
                onChange={(event) => setCycleValue(Number(event.target.value))}
              >
                {Object.entries(WEEKDAY_LABEL).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}요일
                  </option>
                ))}
              </select>
            </div>
          )}

          {cycleType === 'MONTHLY' && (
            <div className="field">
              <label htmlFor="pm-cycle-day">일자 (1~28)</label>
              <input
                id="pm-cycle-day"
                type="number"
                min={1}
                max={28}
                value={cycleValue}
                onChange={(event) => setCycleValue(Number(event.target.value))}
              />
            </div>
          )}

          <button
            type="submit"
            className="btn btn-primary"
            disabled={
              submitting || (cycleType === 'MONTHLY' && (cycleValue < 1 || cycleValue > 28 || !Number.isInteger(cycleValue)))
            }
          >
            {submitting && <Spinner />}
            저장
          </button>
          <button type="button" className="btn btn-ghost" onClick={() => setEditing(false)} disabled={submitting}>
            취소
          </button>
          {error && (
            <p className="form-error" role="alert" style={{ flexBasis: '100%' }}>
              {error}
            </p>
          )}
        </form>
      )}
    </div>
  )
}
