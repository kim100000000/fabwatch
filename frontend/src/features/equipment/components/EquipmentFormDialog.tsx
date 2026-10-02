import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { toApiError } from '@/shared/api'
import { statusLabel } from '@/shared/lib/equipmentStatus'
import { toUserMessage } from '@/shared/lib/errorMessage'
import { LoadingBlock, Modal, Spinner } from '@/shared/ui'
import { createEquipment, updateEquipment } from '../api/equipmentApi'
import { useEquipmentDetail } from '../api/useEquipmentDetail'
import { useLineTree } from '../api/useLineTree'
import type { EquipmentDetail } from '../types'
import './equipment.css'

interface EquipmentFormDialogProps {
  /** null 이면 등록(POST), 값이 있으면 수정(PUT) */
  equipmentId: number | null
  onClose: () => void
  /** 저장 성공 — 갱신된 상세를 넘긴다 */
  onSaved: (saved: EquipmentDetail, mode: 'create' | 'edit') => void
}

interface FormState {
  processId: string
  code: string
  name: string
  modelName: string
  maker: string
  installedAt: string
  managerId: string
  note: string
}

const EMPTY_FORM: FormState = {
  processId: '',
  code: '',
  name: '',
  modelName: '',
  maker: '',
  installedAt: '',
  managerId: '',
  note: '',
}

/**
 * 설비 등록/수정 폼 (S-2, ADMIN 전용 — 노출 판단은 호출부에서 한다).
 * 수정 모드에서 설비코드·상태는 읽기 전용 — 코드는 물리 설비 식별자, 상태는 PATCH 상태 전환에서만 바뀐다.
 */
export function EquipmentFormDialog({ equipmentId, onClose, onSaved }: EquipmentFormDialogProps) {
  const isEdit = equipmentId !== null
  const { lines } = useLineTree()
  // 수정 모드는 목록 요약에 없는 필드(설치일·비고)가 필요해 상세를 다시 읽는다
  const { equipment, loading: detailLoading, error: detailError } = useEquipmentDetail(equipmentId)

  const [form, setForm] = useState<FormState>(EMPTY_FORM)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!equipment) return
    setForm({
      processId: equipment.processId != null ? String(equipment.processId) : '',
      code: equipment.code,
      name: equipment.name,
      modelName: equipment.modelName ?? '',
      maker: equipment.maker ?? '',
      installedAt: equipment.installedAt ?? '',
      managerId: equipment.managerId != null ? String(equipment.managerId) : '',
      note: equipment.note ?? '',
    })
  }, [equipment])

  const setField = (key: keyof FormState, value: string) =>
    setForm((prev) => ({ ...prev, [key]: value }))

  const canSubmit =
    !submitting && form.processId !== '' && form.name.trim() !== '' && (isEdit || form.code.trim() !== '')

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!canSubmit) return

    setSubmitting(true)
    setError(null)
    try {
      const common = {
        processId: Number(form.processId),
        name: form.name.trim(),
        modelName: emptyToNull(form.modelName),
        maker: emptyToNull(form.maker),
        installedAt: emptyToNull(form.installedAt),
        managerId: emptyToNull(form.managerId) === null ? null : Number(form.managerId),
        note: emptyToNull(form.note),
      }
      const saved =
        equipmentId !== null
          ? await updateEquipment(equipmentId, common)
          : await createEquipment({ ...common, code: form.code.trim() })
      onSaved(saved, equipmentId !== null ? 'edit' : 'create')
    } catch (cause) {
      // 409 DUPLICATE_EQUIPMENT_CODE / 404 NOT_FOUND(공정) / 400 VALIDATION_ERROR — 전부 code 기준 매핑
      setError(toUserMessage(toApiError(cause)))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Modal
      title={isEdit ? '설비 수정' : '설비 등록'}
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn btn-ghost" onClick={onClose} disabled={submitting}>
            취소
          </button>
          <button type="submit" form="equipment-form" className="btn btn-primary" disabled={!canSubmit}>
            {submitting && <Spinner />}
            {submitting ? '저장 중…' : '저장'}
          </button>
        </>
      }
    >
      {isEdit && detailLoading && <LoadingBlock label="설비 정보를 불러오는 중…" />}

      {isEdit && !detailLoading && detailError && (
        <p className="form-error" role="alert">
          {toUserMessage(detailError)}
        </p>
      )}

      {(!isEdit || (!detailLoading && !detailError)) && (
        <form id="equipment-form" className="equipment-form" onSubmit={handleSubmit} noValidate>
          <div className="field">
            <label htmlFor="equipment-code">설비 코드</label>
            {isEdit ? (
              <p className="field-readonly mono">
                {form.code}
                <span className="field-hint">설비 코드는 물리 설비 식별자라 수정할 수 없습니다.</span>
              </p>
            ) : (
              <input
                id="equipment-code"
                value={form.code}
                maxLength={30}
                placeholder="LAMI-01"
                onChange={(event) => setField('code', event.target.value)}
                required
              />
            )}
          </div>

          {isEdit && equipment && (
            <div className="field">
              <label>상태</label>
              <p className="field-readonly mono">
                {statusLabel(equipment.status)}
                <span className="field-hint">상태는 상세 화면의 [상태 변경]에서만 바꿀 수 있습니다.</span>
              </p>
            </div>
          )}

          <div className="field">
            <label htmlFor="equipment-name">설비명</label>
            <input
              id="equipment-name"
              value={form.name}
              maxLength={100}
              onChange={(event) => setField('name', event.target.value)}
              required
            />
          </div>

          <div className="field">
            <label htmlFor="equipment-process">공정</label>
            <select
              id="equipment-process"
              value={form.processId}
              onChange={(event) => setField('processId', event.target.value)}
              required
            >
              <option value="">공정 선택</option>
              {lines.map((line) => (
                <optgroup key={line.id} label={line.name}>
                  {(line.processes ?? []).map((process) => (
                    <option key={process.id} value={process.id}>
                      {process.name}
                    </option>
                  ))}
                </optgroup>
              ))}
            </select>
          </div>

          <div className="form-row">
            <div className="field">
              <label htmlFor="equipment-model">모델명</label>
              <input
                id="equipment-model"
                value={form.modelName}
                maxLength={100}
                onChange={(event) => setField('modelName', event.target.value)}
              />
            </div>
            <div className="field">
              <label htmlFor="equipment-maker">제조사</label>
              <input
                id="equipment-maker"
                value={form.maker}
                maxLength={100}
                onChange={(event) => setField('maker', event.target.value)}
              />
            </div>
          </div>

          <div className="form-row">
            <div className="field">
              <label htmlFor="equipment-installed">설치일</label>
              <input
                id="equipment-installed"
                type="date"
                value={form.installedAt}
                onChange={(event) => setField('installedAt', event.target.value)}
              />
            </div>
            <div className="field">
              {/* 담당자 조회 API 가 아직 없어 사용자 ID 를 직접 입력받는다 (존재 검증은 서버) */}
              <label htmlFor="equipment-manager">담당 엔지니어 ID</label>
              <input
                id="equipment-manager"
                type="number"
                min={1}
                value={form.managerId}
                placeholder="선택 — 비워두면 미지정"
                onChange={(event) => setField('managerId', event.target.value)}
              />
            </div>
          </div>

          <div className="field">
            <label htmlFor="equipment-note">비고</label>
            <textarea
              id="equipment-note"
              value={form.note}
              maxLength={500}
              rows={3}
              onChange={(event) => setField('note', event.target.value)}
            />
          </div>

          {error && (
            <p className="form-error" role="alert">
              {error}
            </p>
          )}
        </form>
      )}
    </Modal>
  )
}

/** 빈 입력은 null 로 보내 서버에서 값이 지워지도록 한다 */
function emptyToNull(value: string): string | null {
  const trimmed = value.trim()
  return trimmed === '' ? null : trimmed
}
