import { useEffect } from 'react'
import { useRouteError } from 'react-router-dom'
import '@/shared/ui/ui.css'
import './layouts/layout.css'

/**
 * 라우터 최상위 errorElement — 레이아웃 밖(프로바이더/라우터 단계)에서 난 예외용 브랜드 화면.
 * 스택트레이스·에러 메시지는 노출하지 않고 콘솔에만 남긴다.
 * 라우터 컨텍스트가 깨졌을 수 있어 Link 대신 일반 앵커/reload 를 쓴다.
 */
export function RouteErrorPage() {
  const error = useRouteError()

  useEffect(() => {
    console.error('[RouteError] 라우트 처리 중 예외', error)
  }, [error])

  return (
    <div className="login-shell">
      <div className="login-card route-error-card" role="alert">
        <div className="login-brand">
          <h1>
            <span>◉</span> FabWatch
          </h1>
          <p>예상하지 못한 문제가 발생했습니다</p>
        </div>
        <p className="route-error-text">
          화면을 불러오는 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요. 계속되면 라인 현황으로 이동해 주세요.
        </p>
        <div className="error-actions">
          <button type="button" className="btn btn-primary" onClick={() => window.location.reload()}>
            다시 시도
          </button>
          <a className="btn btn-ghost" href="/">
            라인 현황으로
          </a>
        </div>
      </div>
    </div>
  )
}
