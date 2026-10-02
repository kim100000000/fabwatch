import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '@/app/providers/useAuth'
import { useUnsavedGuard } from '@/shared/hooks/useUnsavedGuard'
import { toApiError } from '@/shared/api'
import { formatKst } from '@/shared/lib/datetime'
import { errorCodeOf, toUserMessage } from '@/shared/lib/errorMessage'
import { ConfirmDialog, ErrorState, LoadingBlock, Spinner } from '@/shared/ui'
import { confirmAiReport, retryAiReport, updateAiReport } from '../api/aiReportApi'
import { useAiReportDetail } from '../api/useAiReportDetail'
import { canManageReport, orDash } from '../types'
import { ReportEditor } from './ReportEditor'
import { ReportMarkdown } from './ReportMarkdown'
import { ReportStatusBadge } from './ReportStatusBadge'
import './report.css'

interface ReportDetailPanelProps {
  reportId: number
}

type Busy = 'save' | 'confirm' | 'retry' | null

/**
 * S-7 리포트 상세/편집 (/reports/:id) — docs/04 S-7, docs/03 F-6.
 * - GENERATING: 스켈레톤 + 2초 폴링(useAiReportDetail), FAILED: 사유 + [재시도][수동 작성]
 * - DRAFT: [원본 초안 보기][편집][확정], 편집은 finalContent 에만 (draftContent 는 읽기 전용)
 * - CONFIRMED: 읽기 전용 + 확정자·확정일시
 * - 생성/편집/확정/재시도는 ENGINEER+ 에게만 노출
 */
export function ReportDetailPanel({ reportId }: ReportDetailPanelProps) {
  const { user } = useAuth()
  const canManage = canManageReport(user?.role)
  const { report, loading, error, pollTimedOut, setReport, refetch } = useAiReportDetail(reportId)

  const [editing, setEditing] = useState(false)
  const [editText, setEditText] = useState('')
  // 편집 시작/저장 시점의 본문 — 이것과 달라지면 '저장 안 한 변경'
  const [editBaseline, setEditBaseline] = useState('')
  const [showDraft, setShowDraft] = useState(false)
  const [busy, setBusy] = useState<Busy>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [discardOpen, setDiscardOpen] = useState(false)

  const dirty = editing && editText !== editBaseline
  const blocker = useUnsavedGuard(dirty)

  if (loading && !report) return <LoadingBlock label="리포트를 불러오는 중…" />
  if (error && !report) return <ErrorState error={error} onRetry={refetch} />
  if (!report) return null

  const hasFinal = !!report.finalContent?.trim()
  const hasDraft = !!report.draftContent?.trim()
  const status = report.status
  const isConfirmed = status === 'CONFIRMED'
  const canEditStatus = status === 'DRAFT' || status === 'FAILED'

  /** 읽기 모드에서 보여줄 본문: 수정본/확정본이 있으면 그것, 없으면 AI 초안 */
  const displayContent = hasFinal ? report.finalContent! : (report.draftContent ?? '')
  const displayLabel = isConfirmed ? '확정본' : hasFinal ? '수정본 (미확정)' : 'AI 초안 (미수정)'

  /** 확정 가능 여부 — FAILED 는 수동 작성한 본문이 있어야 한다 (서버도 400) */
  const confirmable =
    canEditStatus && (status === 'DRAFT' ? true : (editing ? editText.trim() : (report.finalContent ?? '').trim()) !== '')

  const startEdit = () => {
    // 편집 시작 시 finalContent 가 없으면 AI 초안으로 초기화 (FAILED 수동 작성은 초안이 없으므로 빈 상태)
    const initial = hasFinal ? report.finalContent! : (report.draftContent ?? '')
    setEditText(initial)
    setEditBaseline(initial)
    setEditing(true)
    setActionError(null)
    setNotice(null)
  }

  const closeEditor = () => {
    setEditing(false)
    setEditText('')
    setEditBaseline('')
  }

  /** 실패 공통 처리 — 상태 충돌(409)이면 서버 상태를 다시 읽어 화면을 맞춘다 */
  const handleFailure = (cause: unknown) => {
    const apiError = toApiError(cause)
    setActionError(toUserMessage(apiError))
    if (errorCodeOf(apiError) === 'INVALID_REPORT_STATE') {
      closeEditor()
      refetch()
    }
  }

  const save = async (): Promise<boolean> => {
    if (busy) return false
    setBusy('save')
    setActionError(null)
    try {
      const saved = await updateAiReport(report.id, editText)
      setReport(saved)
      return true
    } catch (cause) {
      handleFailure(cause)
      return false
    } finally {
      setBusy(null)
    }
  }

  const handleSave = async () => {
    if (await save()) {
      closeEditor()
      setNotice('저장했습니다. 내용을 확인한 뒤 확정해 주세요.')
    }
  }

  /** 확정 — 저장 안 한 변경이 있으면 먼저 저장하고 확정한다 */
  const handleConfirm = async () => {
    if (busy) return
    if (dirty && !(await save())) {
      setConfirmOpen(false)
      return
    }
    setBusy('confirm')
    setActionError(null)
    try {
      const confirmed = await confirmAiReport(report.id)
      setReport(confirmed)
      closeEditor()
      setNotice('리포트를 확정했습니다.')
    } catch (cause) {
      handleFailure(cause)
    } finally {
      setBusy(null)
      setConfirmOpen(false)
    }
  }

  const handleRetry = async () => {
    if (busy) return
    setBusy('retry')
    setActionError(null)
    setNotice(null)
    try {
      await retryAiReport(report.id)
      refetch() // GENERATING 으로 돌아오면 폴링이 재개된다
    } catch (cause) {
      handleFailure(cause)
    } finally {
      setBusy(null)
    }
  }

  const requestCancelEdit = () => {
    if (dirty) setDiscardOpen(true)
    else closeEditor()
  }

  return (
    <div className="report-detail">
      {/* ---------- 헤더 ---------- */}
      <header className="report-header">
        <div className="report-header-top">
          <h1 className="page-title report-title">{report.title}</h1>
          <ReportStatusBadge status={status} />
        </div>
        <dl className="report-meta">
          <div>
            <dt>설비</dt>
            <dd>
              <Link to={`/equipment/${report.equipmentId}`} className="mono">
                {orDash(report.equipmentCode)}
              </Link>{' '}
              {report.equipmentName ?? ''}
            </dd>
          </div>
          {report.alarmId != null && (
            <div>
              <dt>연계 알람</dt>
              <dd>
                <Link to="/alarms" className="mono">
                  알람 #{report.alarmId}
                </Link>
              </dd>
            </div>
          )}
          {report.inspectionId != null && (
            <div>
              <dt>연계 점검</dt>
              <dd>
                <Link to={`/inspections?id=${report.inspectionId}`} className="mono">
                  점검 이력 #{report.inspectionId}
                </Link>
              </dd>
            </div>
          )}
          <div>
            <dt>생성</dt>
            <dd>
              {orDash(report.createdByName)} · <span className="mono">{formatKst(report.createdAt)}</span>
            </dd>
          </div>
          {isConfirmed && (
            <div>
              <dt>확정</dt>
              <dd>
                {report.confirmedByName ?? '-'} · <span className="mono">{formatKst(report.confirmedAt)}</span>
              </dd>
            </div>
          )}
        </dl>
        {(report.model || report.promptTokens != null || report.completionTokens != null) && (
          <p className="report-model-info mono">
            {report.model ?? '-'} · 입력 {report.promptTokens ?? '-'} / 출력 {report.completionTokens ?? '-'} 토큰
          </p>
        )}
      </header>

      {notice && (
        <p className="form-notice" role="status">
          {notice}
          <button type="button" onClick={() => setNotice(null)} aria-label="알림 닫기">
            ✕
          </button>
        </p>
      )}
      {actionError && (
        <p className="form-error" role="alert">
          {actionError}
        </p>
      )}
      {error && (
        <p className="form-error" role="alert">
          {toUserMessage(error)}{' '}
          <button type="button" className="report-link-btn" onClick={refetch}>
            다시 조회
          </button>
        </p>
      )}

      {/* ---------- GENERATING ---------- */}
      {status === 'GENERATING' && (
        <section className="report-generating" aria-live="polite">
          <div className="report-generating-head">
            <Spinner />
            <span>
              {pollTimedOut ? '시간이 오래 걸립니다. 새로고침해 주세요' : '리포트 생성 중 (최대 1분)'}
            </span>
            {pollTimedOut && (
              <button type="button" className="btn btn-sm" onClick={refetch}>
                새로고침
              </button>
            )}
          </div>
          {!pollTimedOut && <div className="report-progress" aria-hidden="true" />}
          <div className="report-skeleton" aria-hidden="true">
            <span style={{ width: '40%' }} />
            <span style={{ width: '95%' }} />
            <span style={{ width: '88%' }} />
            <span style={{ width: '92%' }} />
            <span style={{ width: '60%' }} />
          </div>
        </section>
      )}

      {/* ---------- FAILED 안내 ---------- */}
      {status === 'FAILED' && (
        <section className="report-failed" role="alert">
          <strong>AI 리포트 생성에 실패했습니다.</strong>
          <p>{report.failReason || '실패 사유가 기록되지 않았습니다.'}</p>
          {canManage && (
            <p className="field-hint">[재시도]로 다시 생성하거나, [수동 작성]으로 직접 작성해 확정할 수 있습니다.</p>
          )}
        </section>
      )}

      {/* ---------- 액션 바 ---------- */}
      {status !== 'GENERATING' && (
        <div className="report-actions">
          {(hasDraft && (hasFinal || editing)) && (
            <button
              type="button"
              className="btn"
              aria-pressed={showDraft}
              onClick={() => setShowDraft((value) => !value)}
            >
              {showDraft ? '원본 초안 닫기' : '원본 초안 보기'}
            </button>
          )}

          {canManage && status === 'FAILED' && !editing && (
            <>
              <button type="button" className="btn" disabled={busy !== null} onClick={() => void handleRetry()}>
                {busy === 'retry' && <Spinner />}
                재시도
              </button>
              <button type="button" className="btn" disabled={busy !== null} onClick={startEdit}>
                {hasFinal ? '수동 작성 이어서 편집' : '수동 작성'}
              </button>
            </>
          )}

          {canManage && status === 'DRAFT' && !editing && (
            <button type="button" className="btn" disabled={busy !== null} onClick={startEdit}>
              편집
            </button>
          )}

          {canManage && editing && (
            <>
              <button
                type="button"
                className="btn btn-primary"
                disabled={busy !== null || !dirty || editText.trim() === ''}
                onClick={() => void handleSave()}
              >
                {busy === 'save' && <Spinner />}
                저장
              </button>
              <button type="button" className="btn btn-ghost" disabled={busy !== null} onClick={requestCancelEdit}>
                편집 취소
              </button>
            </>
          )}

          {canManage && canEditStatus && (
            <button
              type="button"
              className="btn report-confirm-btn"
              disabled={busy !== null || !confirmable}
              title={!confirmable ? '수동 작성한 본문이 있어야 확정할 수 있습니다' : undefined}
              onClick={() => setConfirmOpen(true)}
            >
              확정
            </button>
          )}
        </div>
      )}

      {/* ---------- 본문 ---------- */}
      {status !== 'GENERATING' && editing && canManage && (
        <ReportEditor value={editText} onChange={setEditText} disabled={busy !== null} />
      )}

      {status !== 'GENERATING' && !editing && (hasFinal || hasDraft) && (
        <section className="report-body card" aria-label="리포트 본문">
          <div className="report-pane-label">{displayLabel}</div>
          <ReportMarkdown content={displayContent} />
        </section>
      )}

      {status !== 'GENERATING' && !editing && !hasFinal && !hasDraft && status !== 'FAILED' && (
        <p className="field-hint">표시할 본문이 없습니다.</p>
      )}

      {showDraft && hasDraft && (
        <section className="report-body report-draft card" aria-label="AI 원본 초안">
          <div className="report-pane-label">AI 원본 초안 (읽기 전용 — 수정되지 않습니다)</div>
          <ReportMarkdown content={report.draftContent!} />
        </section>
      )}

      {/* ---------- 확인 다이얼로그 ---------- */}
      {confirmOpen && (
        <ConfirmDialog
          title="리포트 확정"
          message={
            dirty
              ? '저장하지 않은 변경 사항이 있습니다. 저장한 뒤 확정합니다.\n확정하면 수정할 수 없습니다.'
              : '확정하면 수정할 수 없습니다.\n이 내용으로 리포트를 확정하시겠습니까?'
          }
          confirmLabel={dirty ? '저장 후 확정' : '확정'}
          busy={busy !== null}
          onConfirm={() => void handleConfirm()}
          onCancel={() => setConfirmOpen(false)}
        />
      )}

      {discardOpen && (
        <ConfirmDialog
          title="편집 취소"
          message="저장하지 않은 변경 사항이 사라집니다. 편집을 취소하시겠습니까?"
          confirmLabel="변경 버리기"
          cancelLabel="계속 편집"
          onConfirm={() => {
            setDiscardOpen(false)
            closeEditor()
          }}
          onCancel={() => setDiscardOpen(false)}
        />
      )}

      {blocker.state === 'blocked' && (
        <ConfirmDialog
          title="페이지를 떠나시겠습니까?"
          message="저장하지 않은 변경 사항이 있습니다. 떠나면 편집 내용이 사라집니다."
          confirmLabel="떠나기"
          cancelLabel="머무르기"
          onConfirm={() => blocker.proceed()}
          onCancel={() => blocker.reset()}
        />
      )}
    </div>
  )
}
