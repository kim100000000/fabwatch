import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import {
  changeEquipmentStatus,
  fetchEquipmentDetail,
  useEquipmentList,
} from '@/features/equipment'
import { InspectionForm } from '@/features/inspection'
import type { InspectionDetail, InspectionType } from '@/features/inspection'
import { toApiError } from '@/shared/api'
import { usePageTitle } from '@/shared/hooks/usePageTitle'
import { statusLabel } from '@/shared/lib/equipmentStatus'
import { toUserMessage } from '@/shared/lib/errorMessage'
import { ErrorState, LoadingBlock, Modal, Spinner } from '@/shared/ui'

/** BM 저장 직후 DOWN 설비의 IDLE 전환을 묻는 대기 상태 */
interface PendingIdleConfirm {
  inspectionId: number
  equipmentId: number
  equipmentCode: string
}

/**
 * S-5 점검 이력 등록 (/inspections/new?equipmentId=)
 * - 저장 성공 → 목록 이동 + 성공 배너
 * - BM 저장 후 설비가 DOWN 이면 "정비 완료 → IDLE 전환할까요?" 확인 (자동 전환 금지 — 시운전 확인 후 사람이 결정)
 *   확인 시 기존 상태 변경 API(PATCH /equipments/{id}/status)를 호출한다.
 */
export function InspectionCreatePage() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const equipmentIdParam = Number(searchParams.get('equipmentId'))
  const defaultEquipmentId =
    Number.isInteger(equipmentIdParam) && equipmentIdParam > 0 ? equipmentIdParam : null

  // 알람 센터의 '점검 등록' 바로가기: ?alarmId= 면 BM + 해당 알람 연계, ?type=PM|BM 이면 유형만 사전 선택
  const alarmIdParam = Number(searchParams.get('alarmId'))
  const defaultAlarmId = Number.isInteger(alarmIdParam) && alarmIdParam > 0 ? alarmIdParam : null
  const typeParam = searchParams.get('type')
  const defaultType: InspectionType | null = typeParam === 'PM' || typeParam === 'BM' ? typeParam : null

  usePageTitle('점검 이력 등록')

  const { equipments, loading, error, refetch } = useEquipmentList({ size: 100 })

  const [saving, setSaving] = useState(false)
  const [pending, setPending] = useState<PendingIdleConfirm | null>(null)
  const [switching, setSwitching] = useState(false)
  const [switchError, setSwitchError] = useState<string | null>(null)
  const [savedMessage, setSavedMessage] = useState('')

  const goToList = (notice: string) => navigate('/inspections', { state: { notice } })

  const handleSaved = async (saved: InspectionDetail) => {
    const message = `점검 이력 #${saved.id} 을(를) 등록했습니다.${
      saved.hasNg ? ' NG 항목이 있어 엔지니어 확인 대상으로 표시됩니다.' : ''
    }${saved.alarmId != null ? ` 연계 알람 #${saved.alarmId} 이(가) 해제되었습니다.` : ''}`

    if (saved.type !== 'BM') {
      goToList(message)
      return
    }

    // BM: 저장 시점의 최신 설비 상태를 조회해 DOWN 일 때만 IDLE 전환 여부를 묻는다
    setSaving(true)
    try {
      const equipment = await fetchEquipmentDetail(saved.equipmentId)
      if (equipment.status === 'DOWN') {
        setSavedMessage(message)
        setPending({
          inspectionId: saved.id,
          equipmentId: saved.equipmentId,
          equipmentCode: saved.equipmentCode,
        })
        return
      }
    } catch {
      // 상태 조회 실패는 등록 성공을 막지 않는다 — 전환 확인 없이 목록으로 이동
    } finally {
      setSaving(false)
    }
    goToList(message)
  }

  const confirmIdle = async () => {
    if (!pending || switching) return
    setSwitching(true)
    setSwitchError(null)
    try {
      await changeEquipmentStatus(pending.equipmentId, {
        toStatus: 'IDLE',
        reason: `BM 점검 이력 #${pending.inspectionId} 수리 완료`,
      })
      goToList(`${savedMessage} 설비 ${pending.equipmentCode} 을(를) IDLE 로 전환했습니다.`)
    } catch (cause) {
      setSwitchError(toUserMessage(toApiError(cause)))
    } finally {
      setSwitching(false)
    }
  }

  return (
    <div className="page inspection-form-page">
      <div className="page-header">
        <div>
          <h1 className="page-title">점검 이력 등록</h1>
          <p className="page-subtitle">PM 은 체크리스트, BM 은 4M 원인 분류를 기록합니다.</p>
        </div>
        <button type="button" className="btn btn-ghost" onClick={() => navigate('/inspections')}>
          ← 점검 이력
        </button>
      </div>

      {loading && equipments.length === 0 && <LoadingBlock label="설비 목록을 불러오는 중…" />}
      {error && equipments.length === 0 && <ErrorState error={error} onRetry={refetch} />}

      {(equipments.length > 0 || (!loading && !error)) && (
        <fieldset disabled={saving} style={{ border: 'none', margin: 0, padding: 0, minWidth: 0 }}>
          <InspectionForm
            mode="create"
            equipments={equipments}
            defaultEquipmentId={defaultEquipmentId}
            defaultType={defaultType}
            defaultAlarmId={defaultAlarmId}
            onSaved={(saved) => void handleSaved(saved)}
            onCancel={() => navigate('/inspections')}
          />
        </fieldset>
      )}

      {pending && (
        <Modal
          title="설비 상태 전환 확인"
          onClose={() => {
            if (!switching) goToList(savedMessage)
          }}
          footer={
            <>
              <button
                type="button"
                className="btn btn-ghost"
                disabled={switching}
                onClick={() => goToList(savedMessage)}
              >
                나중에
              </button>
              <button
                type="button"
                className="btn btn-primary"
                disabled={switching}
                onClick={() => void confirmIdle()}
              >
                {switching && <Spinner />}
                IDLE 전환
              </button>
            </>
          }
        >
          <p style={{ margin: 0 }}>
            <strong className="mono">{pending.equipmentCode}</strong> 설비가 현재 {statusLabel('DOWN')} 상태입니다.
          </p>
          <p style={{ margin: '8px 0 0' }}>정비 완료 → IDLE 전환할까요? (시운전 확인 후 전환하세요)</p>
          <p className="field-hint" style={{ marginTop: 8 }}>
            [나중에] 를 누르면 상태는 그대로 유지되며, 설비 상세에서 직접 전환할 수 있습니다.
          </p>
          {switchError && (
            <p className="form-error" role="alert" style={{ marginTop: 12 }}>
              {switchError}
            </p>
          )}
        </Modal>
      )}
    </div>
  )
}
