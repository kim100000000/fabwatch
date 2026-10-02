import { StubPage } from './StubPage'

/**
 * docs/04 §2 화면 목록 중 이번 라운드 미구현 화면의 라우팅 스텁.
 * 각 화면 구현 시 이 파일에서 꺼내 전용 페이지 파일로 승격한다.
 */

/** S-8 관리 (/admin) */
export function AdminPage() {
  return (
    <StubPage
      screen="S-8"
      title="관리"
      plan="2~4주차: 사용자 관리 · 임계치 설정 · PM 스케줄 · 시뮬레이터 시나리오 제어 (ADMIN / 일부 ENGINEER)"
    />
  )
}
