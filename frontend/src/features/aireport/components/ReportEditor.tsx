import { useDeferredValue, useState } from 'react'
import type { KeyboardEvent } from 'react'
import { FINAL_CONTENT_MAX_LENGTH } from '../types'
import { ReportMarkdown } from './ReportMarkdown'
import './report.css'

interface ReportEditorProps {
  value: string
  onChange: (value: string) => void
  disabled?: boolean
}

type MobileTab = 'edit' | 'preview'

const TAB_ORDER: MobileTab[] = ['edit', 'preview']

/**
 * 마크다운 편집기 — 데스크톱: 좌 에디터 / 우 실시간 미리보기 분할, 모바일 폭: 탭 전환 (docs/04 S-7).
 * 미리보기도 안전 렌더러(ReportMarkdown)를 쓴다 — 편집 중 입력한 HTML 도 렌더링되지 않는다.
 */
export function ReportEditor({ value, onChange, disabled = false }: ReportEditorProps) {
  const [tab, setTab] = useState<MobileTab>('edit')
  // 긴 본문 입력 중 미리보기 재렌더가 입력을 막지 않도록 지연 값 사용
  const previewValue = useDeferredValue(value)

  // WAI-ARIA 탭 패턴: 좌우 방향키로 탭 이동
  const handleTabKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return
    event.preventDefault()
    const next = TAB_ORDER[(TAB_ORDER.indexOf(tab) + 1) % TAB_ORDER.length]
    setTab(next)
    document.getElementById(`report-editor-tab-${next}`)?.focus()
  }

  return (
    <div className="report-editor" data-tab={tab}>
      <div className="report-editor-tabs" role="tablist" aria-label="편집기 보기 전환">
        <button
          type="button"
          role="tab"
          id="report-editor-tab-edit"
          aria-selected={tab === 'edit'}
          aria-controls="report-editor-panel-edit"
          tabIndex={tab === 'edit' ? 0 : -1}
          className={tab === 'edit' ? 'active' : undefined}
          aria-pressed={tab === 'edit'}
          onClick={() => setTab('edit')}
          onKeyDown={handleTabKeyDown}
        >
          작성
        </button>
        <button
          type="button"
          role="tab"
          id="report-editor-tab-preview"
          aria-selected={tab === 'preview'}
          aria-controls="report-editor-panel-preview"
          tabIndex={tab === 'preview' ? 0 : -1}
          className={tab === 'preview' ? 'active' : undefined}
          aria-pressed={tab === 'preview'}
          onClick={() => setTab('preview')}
          onKeyDown={handleTabKeyDown}
        >
          미리보기
        </button>
      </div>

      <div
        id="report-editor-panel-edit"
        role="tabpanel"
        aria-labelledby="report-editor-tab-edit"
        className="report-editor-pane report-editor-input field"
      >
        <label htmlFor="report-editor-textarea">리포트 본문 (마크다운)</label>
        <textarea
          id="report-editor-textarea"
          value={value}
          disabled={disabled}
          spellCheck={false}
          maxLength={FINAL_CONTENT_MAX_LENGTH}
          aria-describedby="report-editor-count"
          onChange={(event) => onChange(event.target.value)}
        />
        <span
          id="report-editor-count"
          className="field-hint report-editor-count"
          data-near-limit={value.length >= FINAL_CONTENT_MAX_LENGTH * 0.9}
        >
          {value.length.toLocaleString('ko-KR')} / {FINAL_CONTENT_MAX_LENGTH.toLocaleString('ko-KR')}자
        </span>
      </div>

      <div
        id="report-editor-panel-preview"
        role="tabpanel"
        aria-labelledby="report-editor-tab-preview"
        className="report-editor-pane report-editor-preview"
      >
        <div className="report-pane-label">미리보기</div>
        {previewValue.trim() ? (
          <ReportMarkdown content={previewValue} />
        ) : (
          <p className="field-hint">내용을 입력하면 미리보기가 표시됩니다.</p>
        )}
      </div>
    </div>
  )
}
