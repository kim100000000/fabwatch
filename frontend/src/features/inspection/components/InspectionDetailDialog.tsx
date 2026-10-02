import { useState } from 'react'
import type { ReactNode } from 'react'
import { useAuth } from '@/app/providers/useAuth'
import { toApiError } from '@/shared/api'
import { formatDuration, formatKst } from '@/shared/lib/datetime'
import { toUserMessage } from '@/shared/lib/errorMessage'
import { ErrorState, LoadingBlock, Modal, Spinner } from '@/shared/ui'
import { reviewInspection } from '../api/inspectionApi'
import { useInspectionDetail } from '../api/useInspectionDetail'
import { CAUSE_4M_LABEL, CHECK_RESULT_LABEL } from '../types'
import type { InspectionDetail } from '../types'
import { InspectionForm } from './InspectionForm'
import './inspection.css'

interface InspectionDetailDialogProps {
  inspectionId: number
  onClose: () => void
  /** 승인/수정 성공 — 부모가 목록을 다시 읽는다 */
  onChanged?: (updated: InspectionDetail, message: string) => void
  /**
   * 하단 버튼 영역에 덧붙일 추가 버튼 슬롯 (예: BM 'AI 리포트 생성').
   * inspection 도메인이 aireport 를 직접 import 하면 순환 의존이 되므로 호출부에서 주입한다.
   */
  renderExtraActions?: (inspection: InspectionDetail) => ReactNode
}

/**
 * 점검 이력 상세 모달 (S-4 행 클릭 / S-3 점검 탭 공용).
 * - 체크리스트 결과 표 포함
 * - [승인]: ENGINEER+ (이미 승인된 건은 숨김)
 * - [수정]: 작성자 본인 또는 ENGINEER+ (서버도 동일하게 403 FORBIDDEN 으로 막는다)
 */
export function InspectionDetailDialog({
  inspectionId,
  onClose,
  onChanged,
  renderExtraActions,
}: InspectionDetailDialogProps) {
  const { user } = useAuth()
  const { inspection, loading, error, refetch } = useInspectionDetail(inspectionId)
  // 승인/수정 직후에는 응답 본문으로 바로 갱신한다
  const [updated, setUpdated] = useState<InspectionDetail | null>(null)
  const [editing, setEditing] = useState(false)
  const [reviewing, setReviewing] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)

  const current = updated ?? inspection
  const isEngineerPlus = user?.role === 'ADMIN' || user?.role === 'ENGINEER'
  const isReviewed = current ? current.reviewedBy != null || !!current.reviewedAt : false
  const canReview = isEngineerPlus && !isReviewed
  const canEdit = current != null && (isEngineerPlus || current.workerId === user?.id)

  const handleReview = async () => {
    if (!current || reviewing) return
    setReviewing(true)
    setActionError(null)
    try {
      const result = await reviewInspection(current.id)
      setUpdated(result)
      onChanged?.(result, `점검 이력 #${result.id} 을(를) 승인했습니다.`)
    } catch (cause) {
      setActionError(toUserMessage(toApiError(cause)))
    } finally {
      setReviewing(false)
    }
  }

  const title = current ? `점검 이력 #${current.id}` : '점검 이력'

  return (
    <Modal
      title={editing ? `${title} 수정` : title}
      onClose={onClose}
      wide
      footer={
        !editing && current ? (
          <>
            {renderExtraActions?.(current)}
            {canEdit && (
              <button type="button" className="btn" onClick={() => setEditing(true)}>
                수정
              </button>
            )}
            {canReview && (
              <button
                type="button"
                className="btn btn-primary"
                disabled={reviewing}
                onClick={() => void handleReview()}
              >
                {reviewing && <Spinner />}
                승인
              </button>
            )}
            <button type="button" className="btn btn-ghost" onClick={onClose}>
              닫기
            </button>
          </>
        ) : undefined
      }
    >
      <div className="inspection-modal-body">
        {loading && !current && <LoadingBlock label="점검 이력을 불러오는 중…" />}
        {error && !current && <ErrorState error={error} onRetry={refetch} />}

        {current && editing && (
          <InspectionForm
            mode="edit"
            initial={current}
            onCancel={() => setEditing(false)}
            onSaved={(saved) => {
              setUpdated(saved)
              setEditing(false)
              onChanged?.(saved, `점검 이력 #${saved.id} 을(를) 수정했습니다.`)
            }}
          />
        )}

        {current && !editing && (
          <div className="inspection-detail">
            <dl className="detail-info">
              <div>
                <dt>설비</dt>
                <dd>
                  {current.equipmentCode} {current.equipmentName}
                </dd>
              </div>
              <div>
                <dt>유형 / 교대</dt>
                <dd>
                  <span className="type-badge" data-type={current.type}>
                    {current.type}
                  </span>{' '}
                  {current.shift}
                </dd>
              </div>
              <div>
                <dt>작업자</dt>
                <dd>{current.workerName}</dd>
              </div>
              <div>
                <dt>시작 (KST)</dt>
                <dd>{formatKst(current.startedAt)}</dd>
              </div>
              <div>
                <dt>종료 (KST)</dt>
                <dd>{formatKst(current.endedAt)}</dd>
              </div>
              <div>
                <dt>소요시간</dt>
                <dd>{formatDuration(current.durationMin)}</dd>
              </div>
              {current.type === 'BM' && (
                <>
                  <div>
                    <dt>4M 분류</dt>
                    <dd>{current.cause4m ? `${current.cause4m} (${CAUSE_4M_LABEL[current.cause4m]})` : '-'}</dd>
                  </div>
                  <div>
                    <dt>연계 알람</dt>
                    <dd>{current.alarmId != null ? `#${current.alarmId}` : '-'}</dd>
                  </div>
                </>
              )}
              <div>
                <dt>승인</dt>
                <dd>
                  {isReviewed
                    ? `${current.reviewedByName ?? ''} · ${formatKst(current.reviewedAt)}`
                    : current.hasNg
                      ? '엔지니어 확인 대기'
                      : '미승인'}
                </dd>
              </div>
            </dl>

            {current.causeDetail && (
              <div>
                <h3 className="detail-section-title">상세 원인</h3>
                <p className="detail-text">{current.causeDetail}</p>
              </div>
            )}
            <div>
              <h3 className="detail-section-title">점검 내용</h3>
              <p className="detail-text">{current.content}</p>
            </div>
            {current.actionTaken && (
              <div>
                <h3 className="detail-section-title">조치 사항</h3>
                <p className="detail-text">{current.actionTaken}</p>
              </div>
            )}

            {current.checkResults.length > 0 && (
              <div>
                <h3 className="detail-section-title">
                  체크리스트 결과 {current.hasNg && <span className="ng-flag">· NG 포함</span>}
                </h3>
                <div className="table-scroll">
                  <table className="data-table check-table">
                    <thead>
                      <tr>
                        <th>항목</th>
                        <th>판정 기준</th>
                        <th>결과</th>
                        <th>메모</th>
                      </tr>
                    </thead>
                    <tbody>
                      {current.checkResults.map((row) => (
                        <tr key={row.checklistItemId}>
                          <td>{row.itemName}</td>
                          <td>{row.criteria ?? '-'}</td>
                          <td>
                            <span className="check-result" data-result={row.result}>
                              {CHECK_RESULT_LABEL[row.result] ?? row.result}
                            </span>
                          </td>
                          <td>{row.note ?? '-'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            )}

            {actionError && (
              <p className="form-error" role="alert">
                {actionError}
              </p>
            )}
          </div>
        )}
      </div>
    </Modal>
  )
}
