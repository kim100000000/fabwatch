import { useEffect } from 'react'

const BASE_TITLE = 'FabWatch'

/** 화면별 document.title 갱신 — 스크린리더/탭 구분용. title 이 null 이면 기본 제목만 쓴다 */
export function usePageTitle(title: string | null | undefined): void {
  useEffect(() => {
    document.title = title ? `${title} · ${BASE_TITLE}` : `${BASE_TITLE} — 설비관리 시스템`
  }, [title])
}
