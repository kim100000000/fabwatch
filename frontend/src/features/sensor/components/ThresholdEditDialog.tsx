import { useId, useMemo, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { toApiError } from '@/shared/api'
import { errorCodeOf, toUserMessage } from '@/shared/lib/errorMessage'
import { Modal, Spinner } from '@/shared/ui'
import { updateSensorThresholds } from '../api/sensorApi'
import {
  THRESHOLD_FIELDS,
  isSameThresholds,
  toInputs,
  validateThresholds,
} from '../thresholdForm'
import type { ThresholdField, ThresholdInputs } from '../thresholdForm'
import { SENSOR_TYPE_LABEL, THRESHOLD_REASON_MAX } from '../types'
import type { SensorDefinition, SensorLatest } from '../types'
import { ThresholdLogSection } from './ThresholdLogSection'
import { ThresholdValuesSummary } from './ThresholdValuesSummary'
import './sensor.css'

interface ThresholdEditDialogProps {
  equipmentId: number
  /** 편집 대상 센서 — 현재 임계치/단위의 출처 */
  sensor: SensorLatest
  /** 센서 정의의 정상 기준값(시뮬레이터 평균). 있을 때만 참고로 표시 */
  baseValue?: number | null
  onClose: () => void
  /** 저장 성공 — 서버가 돌려준 갱신된 센서 정의 */
  onSaved: (updated: SensorDefinition) => void
}

/**
 * 임계치 편집 다이얼로그 (FR-2.3, ADMIN 전용 — 노출 판단은 호출부, 최종 판정은 서버 403).
 * - 현재 값으로 초기화된 4값 입력(빈 값=null), crit_low ≤ warn_low < warn_high ≤ crit_high 즉시 검증
 * - 사유 필수(공백 불가, 300자 이하) — 변경자·사유가 이력에 남는다
 * - 임계치 변경은 알람 판정에 즉시 반영되므로 경고 문구를 항상 노출한다
 */
export function ThresholdEditDialog({
  equipmentId,
  sensor,
  baseValue,
  onClose,
  onSaved,
}: ThresholdEditDialogProps) {
  const formId = useId()
  const [inputs, setInputs] = useState<ThresholdInputs>(() => toInputs(sensor))
  const [reason, setReason] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // 이중 제출 방지 — state 갱신 전에 들어오는 연속 클릭/Enter 도 막는다
  const submittingRef = useRef(false)

  const unit = sensor.unit ?? ''
  const label = SENSOR_TYPE_LABEL[sensor.sensorType] ?? sensor.sensorType

  const validation = useMemo(() => validateThresholds(inputs), [inputs])
  const unchanged = isSameThresholds(validation.values, sensor)
  const reasonMissing = reason.trim() === ''
  const allEmpty = THRESHOLD_FIELDS.every(({ key }) => inputs[key].trim() === '')
  const canSubmit = validation.valid && !unchanged && !reasonMissing && !submitting

  const setField = (key: ThresholdField, value: string) => {
    setInputs((previous) => ({ ...previous, [key]: value }))
    setError(null)
  }

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!canSubmit || submittingRef.current) return
    submittingRef.current = true
    setSubmitting(true)
    setError(null)
    try {
      const updated = await updateSensorThresholds(equipmentId, sensor.sensorId, {
        ...validation.values,
        reason: reason.trim(),
      })
      onSaved(updated)
    } catch (cause) {
      const apiError = toApiError(cause)
      // 403 은 공통 문구('권한이 없습니다.')보다 구체적으로 — 분기는 항상 code 로 한다
      setError(
        errorCodeOf(apiError) === 'FORBIDDEN'
          ? '임계치 변경은 관리자(ADMIN)만 할 수 있습니다.'
          : toUserMessage(apiError),
      )
    } finally {
      submittingRef.current = false
      setSubmitting(false)
    }
  }

  return (
    <Modal
      title={`${label} 임계치 설정`}
      wide
      onClose={onClose}
      busy={submitting}
      footer={
        <>
          <button type="button" className="btn btn-ghost" onClick={onClose} disabled={submitting}>
            취소
          </button>
          <button type="submit" form={formId} className="btn btn-primary" disabled={!canSubmit}>
            {submitting && <Spinner />}
            {submitting ? '저장 중…' : '임계치 저장'}
          </button>
        </>
      }
    >
      <div className="threshold-dialog">
        <p className="threshold-warning" role="note">
          임계치 변경은 알람 판정에 즉시 반영됩니다. 현장에서 임의 변경은 사고의 씨앗이므로, 변경자·변경 시각·사유가
          이력에 그대로 남습니다.
        </p>

        <div className="threshold-meta">
          <span>
            {label} <span className="mono">{sensor.sensorType}</span>
            {unit && <> · 단위 <span className="mono">{unit}</span></>}
          </span>
          {baseValue !== null && baseValue !== undefined && (
            <span>
              정상 기준값 <span className="mono">{baseValue}{unit ? ` ${unit}` : ''}</span>
            </span>
          )}
        </div>

        <ThresholdValuesSummary sensor={sensor} />

        <form id={formId} className="equipment-form" onSubmit={handleSubmit} noValidate>
          <fieldset className="threshold-fields" disabled={submitting}>
            <legend>
              새 임계치 <span className="field-hint">(위험 하한 ≤ 경고 하한 &lt; 경고 상한 ≤ 위험 상한 · 빈 값은 해당 방향 미사용)</span>
            </legend>
            {THRESHOLD_FIELDS.map(({ key, label: fieldLabel, kind }) => {
              const fieldError = validation.fieldErrors[key]
              const inputId = `${formId}-${key}`
              return (
                <div className="field" key={key} data-kind={kind}>
                  <label htmlFor={inputId}>
                    {fieldLabel}
                    {unit && ` (${unit})`}
                  </label>
                  <input
                    id={inputId}
                    className="mono"
                    type="text"
                    inputMode="decimal"
                    autoComplete="off"
                    value={inputs[key]}
                    placeholder="미설정"
                    aria-invalid={fieldError ? true : undefined}
                    aria-describedby={fieldError ? `${inputId}-error` : undefined}
                    onChange={(event) => setField(key, event.target.value)}
                  />
                  {fieldError && (
                    <p className="field-error" id={`${inputId}-error`} role="alert">
                      {fieldError}
                    </p>
                  )}
                </div>
              )
            })}
          </fieldset>

          {allEmpty && (
            <p className="field-hint" role="alert">
              4개 값을 모두 비울 수 없습니다. 최소 1개는 입력해야 합니다(전부 비우면 이 센서의 알람 판정이 꺼집니다).
            </p>
          )}
          {unchanged && validation.valid && (
            <p className="field-hint" role="status">
              현재 값과 같습니다. 변경할 값을 입력해 주세요.
            </p>
          )}

          <div className="field">
            <label htmlFor={`${formId}-reason`}>변경 사유 (필수)</label>
            <textarea
              id={`${formId}-reason`}
              value={reason}
              rows={3}
              maxLength={THRESHOLD_REASON_MAX}
              required
              disabled={submitting}
              aria-invalid={reasonMissing && reason.length > 0 ? true : undefined}
              placeholder="예: 정기 교정 후 기준값 조정 — 필수, 공백만은 불가"
              onChange={(event) => {
                setReason(event.target.value)
                setError(null)
              }}
            />
            <span className="field-hint">
              {reason.length}/{THRESHOLD_REASON_MAX}
            </span>
          </div>

          {error && (
            <p className="form-error" role="alert">
              {error}
            </p>
          )}
        </form>

        <ThresholdLogSection equipmentId={equipmentId} sensorId={sensor.sensorId} unit={sensor.unit} />
      </div>
    </Modal>
  )
}
