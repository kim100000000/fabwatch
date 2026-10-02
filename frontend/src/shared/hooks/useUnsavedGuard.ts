import { useEffect } from 'react'
import { useBlocker } from 'react-router-dom'

/**
 * 저장하지 않은 변경이 있을 때 페이지 이탈을 막는다.
 * - 앱 내 이동: 데이터 라우터의 useBlocker (반환값이 'blocked' 면 호출부가 확인 다이얼로그를 띄운다)
 * - 새로고침/탭 닫기: beforeunload 브라우저 기본 경고
 */
export function useUnsavedGuard(dirty: boolean) {
  const blocker = useBlocker(dirty)

  useEffect(() => {
    if (!dirty) return
    const handler = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', handler)
    return () => window.removeEventListener('beforeunload', handler)
  }, [dirty])

  return blocker
}
