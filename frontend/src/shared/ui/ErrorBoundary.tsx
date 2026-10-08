import { Component } from 'react'
import type { ErrorInfo, ReactNode } from 'react'
import { Link } from 'react-router-dom'
import './ui.css'

interface ErrorBoundaryProps {
  children: ReactNode
  /** 이 값이 바뀌면(예: 라우트 변경) 에러 상태를 해제한다 */
  resetKey?: string
}

interface ErrorBoundaryState {
  hasError: boolean
  /** 마지막으로 반영한 resetKey — 바뀌면 에러 상태를 해제하기 위한 비교용 */
  resetKey?: string
}

/**
 * 렌더 중 예외를 잡아 사이드바·헤더는 유지한 채 본문만 안내 화면으로 대체한다.
 * 스택트레이스/에러 메시지는 사용자에게 노출하지 않고 콘솔에만 남긴다.
 */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { hasError: false, resetKey: this.props.resetKey }

  static getDerivedStateFromError(): Partial<ErrorBoundaryState> {
    return { hasError: true }
  }

  // resetKey(예: 라우트 경로)가 바뀌면 에러 상태를 풀어 새 화면을 다시 그린다
  static getDerivedStateFromProps(
    props: ErrorBoundaryProps,
    state: ErrorBoundaryState,
  ): Partial<ErrorBoundaryState> | null {
    if (props.resetKey !== state.resetKey) return { hasError: false, resetKey: props.resetKey }
    return null
  }

  componentDidCatch(error: unknown, info: ErrorInfo): void {
    console.error('[ErrorBoundary] 화면 렌더링 오류', error, info.componentStack)
  }

  render(): ReactNode {
    if (!this.state.hasError) return this.props.children
    return (
      <div className="page">
        <div className="error-state" role="alert">
          <strong>화면을 표시하는 중 문제가 발생했습니다</strong>
          <span>일시적인 오류일 수 있습니다. 다시 시도해도 해결되지 않으면 라인 현황으로 이동해 주세요.</span>
          <div className="error-actions">
            <button type="button" className="btn" onClick={() => this.setState({ hasError: false })}>
              다시 시도
            </button>
            <Link to="/" className="btn btn-ghost">
              라인 현황으로
            </Link>
          </div>
        </div>
      </div>
    )
  }
}
