import { useMemo, useState } from 'react'
import type { FormEvent } from 'react'
import { useAlarmList, isUnresolved, ALARM_STATUS_LABEL } from '@/features/alarm'
import type { EquipmentSummary } from '@/features/equipment'
import { toApiError } from '@/shared/api'
import {
  currentShift,
  formatDuration,
  kstLocalToUtcIso,
  toKstLocalInput,
} from '@/shared/lib/datetime'
import type { Shift } from '@/shared/lib/datetime'
import { toUserMessage } from '@/shared/lib/errorMessage'
import { ErrorState, LoadingBlock, Spinner } from '@/shared/ui'
import { createInspection, updateInspection } from '../api/inspectionApi'
import { useChecklist } from '../api/useChecklist'
import {
  CAUSE_4M_LABEL,
  CAUSE_4M_OPTIONS,
  CHECK_RESULT_LABEL,
  CHECK_RESULT_OPTIONS,
  LONG_DURATION_WARN_MIN,
  SHIFT_LABEL,
} from '../types'
import type {
  Cause4m,
  CheckResultInput,
  CheckResultValue,
  InspectionDetail,
  InspectionType,
} from '../types'
import './inspection.css'

/** 폼이 다루는 체크리스트 항목 (템플릿/기존 결과 공통) */
interface ChecklistRowItem {
  id: number
  itemName: string
  criteria?: string | null
}

interface CheckState {
  result: CheckResultValue | null
  note: string
}

interface InspectionFormProps {
  mode: 'create' | 'edit'
  /** create: 설비 선택 후보 */
  equipments?: EquipmentSummary[]
  /** create: ?equipmentId= 로 사전 선택된 설비 */
  defaultEquipmentId?: number | null
  /** edit: 수정 대상 (설비·유형·알람은 불변) */
  initial?: InspectionDetail
  onSaved: (saved: InspectionDetail) => void
  onCancel?: () => void
}

/** 연계 알람 드롭다운 — 선택 설비의 OPEN/ACK 알람 (alarm 피처 공개 훅 재사용) */
function LinkedAlarmSelect({
  equipmentId,
  value,
  onChange,
}: {
  equipmentId: number
  value: number | null
  onChange: (alarmId: number | null) => void
}) {
  const { alarms, loading, error } = useAlarmList({ equipmentId, size: 50 })
  const candidates = useMemo(() => alarms.filter(isUnresolved), [alarms])

  return (
    <div className="field">
      <label htmlFor="insp-alarm">연계 알람 (선택)</label>
      <select
        id="insp-alarm"
        value={value ?? ''}
        disabled={loading}
        onChange={(event) => onChange(event.target.value ? Number(event.target.value) : null)}
      >
        <option value="">{loading ? '불러오는 중…' : '연계 안 함'}</option>
        {candidates.map((alarm) => (
          <option key={alarm.id} value={alarm.id}>
            #{alarm.id} · {alarm.severity} · {alarm.message} ({ALARM_STATUS_LABEL[alarm.status]})
          </option>
        ))}
      </select>
      {error ? (
        <span className="field-hint">알람 목록을 불러오지 못했습니다. 연계 없이 저장할 수 있습니다.</span>
      ) : (
        <span className="field-hint">
          연계한 알람은 저장 시 자동으로 RESOLVED 처리됩니다.
          {!loading && candidates.length === 0 ? ' (이 설비에 미조치 알람이 없습니다)' : ''}
        </span>
      )}
    </div>
  )
}

/** 체크리스트 한 줄 — OK/NG/NA 라디오 + 메모 */
function ChecklistRow({
  item,
  state,
  showError,
  onChange,
}: {
  item: ChecklistRowItem
  state: CheckState
  showError: boolean
  onChange: (next: CheckState) => void
}) {
  const name = `check-${item.id}`
  return (
    <div className="checklist-row" data-result={state.result ?? ''}>
      <div>
        <span className="checklist-name">{item.itemName}</span>
        {item.criteria && <span className="checklist-criteria">기준: {item.criteria}</span>}
        {showError && state.result === null && <p className="field-error">판정을 선택하세요.</p>}
      </div>
      <div className="result-radios" role="radiogroup" aria-label={`${item.itemName} 판정`}>
        {CHECK_RESULT_OPTIONS.map((option) => (
          <label key={option} data-result={option} data-checked={state.result === option}>
            <input
              type="radio"
              name={name}
              value={option}
              checked={state.result === option}
              onChange={() => onChange({ ...state, result: option })}
            />
            {CHECK_RESULT_LABEL[option]}
          </label>
        ))}
      </div>
      <div className="check-note">
        <input
          type="text"
          value={state.note}
          maxLength={300}
          placeholder="메모 (선택)"
          aria-label={`${item.itemName} 메모`}
          onChange={(event) => onChange({ ...state, note: event.target.value })}
        />
      </div>
    </div>
  )
}

/** 현재 시각을 분 단위로 내린 datetime-local 값 (KST) */
function nowLocal(offsetMin = 0): string {
  return toKstLocalInput(Date.now() + offsetMin * 60_000)
}

/**
 * S-5 점검 이력 등록 / 수정 폼 (docs/03 3.1, docs/04 §3 S-5).
 * - 유형 토글(PM/BM)로 폼 분기: PM=체크리스트 OK/NG/NA, BM=4M(필수)+상세원인+연계 알람
 * - 시작/종료(KST) → 소요시간 자동 표시, 교대조 자동 판정(수정 가능), 24시간 초과 경고(제출 허용)
 * - 미래 시각 / 종료<=시작은 인라인 에러로 막는다 (서버도 400 VALIDATION_ERROR)
 * - workerId 는 보내지 않는다 (서버가 로그인 사용자로 채운다)
 */
export function InspectionForm({
  mode,
  equipments = [],
  defaultEquipmentId = null,
  initial,
  onSaved,
  onCancel,
}: InspectionFormProps) {
  const isEdit = mode === 'edit'

  const [equipmentId, setEquipmentId] = useState<number | null>(
    isEdit ? (initial?.equipmentId ?? null) : defaultEquipmentId,
  )
  const [type, setType] = useState<InspectionType>(initial?.type ?? 'PM')
  const [startedAt, setStartedAt] = useState(
    initial ? toKstLocalInput(initial.startedAt) : nowLocal(-30),
  )
  const [endedAt, setEndedAt] = useState(initial ? toKstLocalInput(initial.endedAt) : nowLocal())
  // null 이면 시작 시각으로 자동 판정, 값이 있으면 사용자가 직접 고른 교대조
  const [shiftOverride, setShiftOverride] = useState<Shift | null>(null)
  const [content, setContent] = useState(initial?.content ?? '')
  const [actionTaken, setActionTaken] = useState(initial?.actionTaken ?? '')
  const [cause4m, setCause4m] = useState<Cause4m | ''>(initial?.cause4m ?? '')
  const [causeDetail, setCauseDetail] = useState(initial?.causeDetail ?? '')
  const [alarmId, setAlarmId] = useState<number | null>(initial?.alarmId ?? null)
  const [checks, setChecks] = useState<Record<number, CheckState>>(() => {
    const seeded: Record<number, CheckState> = {}
    initial?.checkResults.forEach((row) => {
      seeded[row.checklistItemId] = { result: row.result, note: row.note ?? '' }
    })
    return seeded
  })

  const [attempted, setAttempted] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)

  // PM 체크리스트: 등록은 설비 템플릿 로드, 수정은 기존 결과 행을 그대로 사용
  const checklist = useChecklist(!isEdit && type === 'PM' ? equipmentId : null)
  const checkItems: ChecklistRowItem[] = useMemo(() => {
    if (isEdit) {
      return (initial?.checkResults ?? []).map((row) => ({
        id: row.checklistItemId,
        itemName: row.itemName,
        criteria: row.criteria,
      }))
    }
    // 로딩·에러 중에는 이전 설비의 항목이 남아 보이거나 제출되지 않도록 비운다
    if (checklist.loading || checklist.error) return []
    return checklist.items.map((item) => ({ id: item.id, itemName: item.itemName, criteria: item.criteria }))
  }, [isEdit, initial, checklist.items, checklist.loading, checklist.error])

  const stateOf = (itemId: number): CheckState => checks[itemId] ?? { result: null, note: '' }
  const hasNg = type === 'PM' && checkItems.some((item) => stateOf(item.id).result === 'NG')

  // ---- 시각 검증 / 소요시간 / 교대 자동 판정 ----
  const startUtc = kstLocalToUtcIso(startedAt)
  const endUtc = kstLocalToUtcIso(endedAt)
  const startMs = startUtc ? new Date(startUtc).getTime() : null
  const endMs = endUtc ? new Date(endUtc).getTime() : null

  const startError =
    !startUtc
      ? '시작 일시를 입력하세요.'
      : startMs! > Date.now()
        ? '미래 시각은 입력할 수 없습니다.'
        : null
  const endError =
    !endUtc
      ? '종료 일시를 입력하세요.'
      : endMs! > Date.now()
        ? '미래 시각은 입력할 수 없습니다.'
        : startMs !== null && endMs! <= startMs
          ? '종료 일시는 시작 일시보다 늦어야 합니다.'
          : null

  const durationMin = startMs !== null && endMs !== null && endMs > startMs ? (endMs - startMs) / 60_000 : null
  const autoShift: Shift = startUtc ? currentShift(startUtc) : currentShift()
  const shift: Shift = shiftOverride ?? autoShift

  // ---- 필드 검증 (제출 시도 후에 표시) ----
  const equipmentError = !isEdit && equipmentId === null ? '설비를 선택하세요.' : null
  const contentError = content.trim() === '' ? '점검 내용을 입력하세요.' : null
  const causeError = type === 'BM' && cause4m === '' ? 'BM 점검은 4M 원인 분류가 필수입니다.' : null
  const checklistIncomplete =
    type === 'PM' && checkItems.some((item) => stateOf(item.id).result === null)

  // 체크리스트를 못 불러온 상태로 PM을 저장하면 "전 항목 판정 필수" 정책이 우회되므로 막는다
  const checklistUnavailable =
    !isEdit && type === 'PM' && equipmentId !== null && (checklist.loading || !!checklist.error)

  const hasBlockingError =
    !!(startError || endError || equipmentError || contentError || causeError || checklistIncomplete) ||
    checklistUnavailable

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (submitting) return
    setAttempted(true)
    setSubmitError(null)
    if (hasBlockingError || !startUtc || !endUtc || (!isEdit && equipmentId === null)) return

    const checkResults: CheckResultInput[] | undefined =
      type === 'PM'
        ? checkItems.map((item) => ({
            checklistItemId: item.id,
            result: stateOf(item.id).result as CheckResultValue,
            note: stateOf(item.id).note.trim() || null,
          }))
        : undefined

    const common = {
      startedAt: startUtc,
      endedAt: endUtc,
      content: content.trim(),
      actionTaken: actionTaken.trim() || null,
      cause4m: type === 'BM' && cause4m !== '' ? cause4m : null,
      causeDetail: type === 'BM' ? causeDetail.trim() || null : null,
      checkResults,
    }

    setSubmitting(true)
    try {
      const saved = isEdit
        ? await updateInspection(initial!.id, common)
        : await createInspection({
            ...common,
            equipmentId: equipmentId!,
            type,
            shift,
            alarmId: type === 'BM' ? alarmId : null,
          })
      onSaved(saved)
    } catch (cause) {
      // 분기는 code 기준 (CAUSE_4M_REQUIRED / VALIDATION_ERROR / FORBIDDEN …)
      setSubmitError(toUserMessage(toApiError(cause)))
    } finally {
      setSubmitting(false)
    }
  }

  const maxLocal = nowLocal()

  return (
    <form className="inspection-form" onSubmit={handleSubmit} noValidate>
      <section className="form-section">
        <h2 className="form-section-title">대상 설비 / 유형</h2>

        {isEdit ? (
          <p className="field-readonly">
            <span>
              <span className="mono">{initial?.equipmentCode}</span> {initial?.equipmentName}
            </span>
            <span className="field-hint">설비·유형·연계 알람은 수정할 수 없습니다.</span>
          </p>
        ) : (
          <div className="field">
            <label htmlFor="insp-equipment">설비 (필수)</label>
            <select
              id="insp-equipment"
              value={equipmentId ?? ''}
              aria-invalid={attempted && !!equipmentError}
              onChange={(event) => {
                setEquipmentId(event.target.value ? Number(event.target.value) : null)
                // 설비가 바뀌면 이전 설비의 연계 알람은 무효
                setAlarmId(null)
              }}
            >
              <option value="">설비를 선택하세요</option>
              {equipments.map((equipment) => (
                <option key={equipment.id} value={equipment.id}>
                  {equipment.code} · {equipment.name}
                </option>
              ))}
            </select>
            {attempted && equipmentError && <p className="field-error">{equipmentError}</p>}
          </div>
        )}

        <div className="field">
          <label id="insp-type-label">점검 유형</label>
          <div className="type-toggle" role="group" aria-labelledby="insp-type-label">
            <button
              type="button"
              data-type="PM"
              className={type === 'PM' ? 'active' : undefined}
              aria-pressed={type === 'PM'}
              disabled={isEdit}
              onClick={() => setType('PM')}
            >
              PM (예방점검)
            </button>
            <button
              type="button"
              data-type="BM"
              className={type === 'BM' ? 'active' : undefined}
              aria-pressed={type === 'BM'}
              disabled={isEdit}
              onClick={() => setType('BM')}
            >
              BM (고장수리)
            </button>
          </div>
        </div>
      </section>

      <section className="form-section">
        <h2 className="form-section-title">작업 시간 (KST)</h2>
        <div className="form-row-3">
          <div className="field">
            <label htmlFor="insp-start">시작 일시</label>
            <input
              id="insp-start"
              type="datetime-local"
              value={startedAt}
              max={maxLocal}
              aria-invalid={!!startError}
              onChange={(event) => setStartedAt(event.target.value)}
            />
            {startError && <p className="field-error">{startError}</p>}
          </div>
          <div className="field">
            <label htmlFor="insp-end">종료 일시</label>
            <input
              id="insp-end"
              type="datetime-local"
              value={endedAt}
              max={maxLocal}
              aria-invalid={!!endError}
              onChange={(event) => setEndedAt(event.target.value)}
            />
            {endError && <p className="field-error">{endError}</p>}
          </div>
          <div className="field">
            <label>소요시간</label>
            <div className="duration-readout" aria-live="polite">
              {durationMin !== null ? formatDuration(durationMin) : '-'}
            </div>
          </div>
        </div>
        {durationMin !== null && durationMin > LONG_DURATION_WARN_MIN && (
          <p className="field-warn" role="status">
            소요시간이 24시간을 초과했습니다. 입력이 맞는지 확인하세요 (대수리라면 그대로 저장할 수 있습니다).
          </p>
        )}

        {isEdit ? (
          <p className="field-hint">
            교대조: <strong className="mono">{initial?.shift}</strong> (수정 불가)
          </p>
        ) : (
          <div className="field" style={{ maxWidth: 240 }}>
            <label htmlFor="insp-shift">교대조</label>
            <select
              id="insp-shift"
              value={shift}
              onChange={(event) => setShiftOverride(event.target.value as Shift)}
            >
              {(Object.keys(SHIFT_LABEL) as Shift[]).map((option) => (
                <option key={option} value={option}>
                  {SHIFT_LABEL[option]}
                </option>
              ))}
            </select>
            <span className="field-hint">
              시작 시각 기준 자동 판정(D=08~20시 / N=20~08시){shiftOverride ? ' — 직접 선택됨' : ''}
            </span>
          </div>
        )}
      </section>

      {type === 'PM' ? (
        <section className="form-section">
          <h2 className="form-section-title">PM 체크리스트</h2>
          {!isEdit && equipmentId === null && (
            <p className="field-hint">설비를 선택하면 체크리스트가 자동으로 불러와집니다.</p>
          )}
          {!isEdit && equipmentId !== null && checklist.loading && (
            <LoadingBlock label="체크리스트를 불러오는 중…" />
          )}
          {!isEdit && equipmentId !== null && checklist.error && (
            <ErrorState error={checklist.error} onRetry={checklist.refetch} />
          )}
          {!isEdit &&
            equipmentId !== null &&
            !checklist.loading &&
            !checklist.error &&
            checkItems.length === 0 && (
              <p className="field-hint">이 설비에 등록된 PM 체크리스트 항목이 없습니다. 내용만 기록해 저장할 수 있습니다.</p>
            )}
          {checkItems.map((item) => (
            <ChecklistRow
              key={item.id}
              item={item}
              state={stateOf(item.id)}
              showError={attempted}
              onChange={(next) => setChecks((previous) => ({ ...previous, [item.id]: next }))}
            />
          ))}
          {hasNg && (
            <p className="ng-notice" role="status">
              NG 항목이 있어 엔지니어 확인 대상으로 표시됩니다.
            </p>
          )}
        </section>
      ) : (
        <section className="form-section">
          <h2 className="form-section-title">BM 원인 분석</h2>
          <div className="form-row">
            <div className="field">
              <label htmlFor="insp-cause">4M 원인 분류 (필수)</label>
              <select
                id="insp-cause"
                value={cause4m}
                aria-invalid={attempted && !!causeError}
                onChange={(event) => setCause4m(event.target.value as Cause4m | '')}
              >
                <option value="">선택하세요</option>
                {CAUSE_4M_OPTIONS.map((option) => (
                  <option key={option} value={option}>
                    {option} ({CAUSE_4M_LABEL[option]})
                  </option>
                ))}
              </select>
              {attempted && causeError && <p className="field-error">{causeError}</p>}
            </div>
            <div className="field">
              <label htmlFor="insp-cause-detail">상세 원인 (선택)</label>
              <input
                id="insp-cause-detail"
                type="text"
                value={causeDetail}
                maxLength={300}
                placeholder="예) 롤러 베어링 마모"
                onChange={(event) => setCauseDetail(event.target.value)}
              />
            </div>
          </div>

          {isEdit ? (
            <p className="field-hint">
              연계 알람: {initial?.alarmId != null ? `#${initial.alarmId}` : '없음'} (수정 불가)
            </p>
          ) : equipmentId !== null ? (
            <LinkedAlarmSelect key={equipmentId} equipmentId={equipmentId} value={alarmId} onChange={setAlarmId} />
          ) : (
            <p className="field-hint">설비를 선택하면 연계 가능한 알람(OPEN/ACK)이 표시됩니다.</p>
          )}
        </section>
      )}

      <section className="form-section">
        <h2 className="form-section-title">점검 내용</h2>
        <div className="field">
          <label htmlFor="insp-content">내용 (필수)</label>
          <textarea
            id="insp-content"
            rows={4}
            value={content}
            maxLength={5000}
            aria-invalid={attempted && !!contentError}
            placeholder="예) 합착 롤러 진동 이상으로 정지"
            onChange={(event) => setContent(event.target.value)}
          />
          {attempted && contentError && <p className="field-error">{contentError}</p>}
        </div>
        <div className="field">
          <label htmlFor="insp-action">조치 사항 (선택)</label>
          <textarea
            id="insp-action"
            rows={3}
            value={actionTaken}
            maxLength={5000}
            placeholder="예) 베어링 교체 후 시운전"
            onChange={(event) => setActionTaken(event.target.value)}
          />
        </div>
      </section>

      {attempted && hasBlockingError && (
        <p className="form-error" role="alert">
          입력값을 확인하세요. 표시된 항목을 수정해야 저장할 수 있습니다.
        </p>
      )}
      {submitError && (
        <p className="form-error" role="alert">
          {submitError}
        </p>
      )}

      <div className="form-actions">
        {onCancel && (
          <button type="button" className="btn btn-ghost" onClick={onCancel} disabled={submitting}>
            취소
          </button>
        )}
        <button type="submit" className="btn btn-primary" disabled={submitting}>
          {submitting && <Spinner />}
          {submitting ? '저장 중…' : isEdit ? '수정 저장' : '점검 등록'}
        </button>
      </div>
    </form>
  )
}
