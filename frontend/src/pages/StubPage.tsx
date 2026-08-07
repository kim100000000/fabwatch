import { EmptyState } from '@/shared/ui'

interface StubPageProps {
  /** 화면 번호 (docs/04 §2) */
  screen: string
  title: string
  /** 구현 예정 내용 */
  plan: string
}

/** 아직 구현 전인 화면의 라우팅 스텁 — 제목 + 구현 예정 안내만 표시 */
export function StubPage({ screen, title, plan }: StubPageProps) {
  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">
            <span className="mono" style={{ color: 'var(--text-secondary)', marginRight: 8 }}>
              {screen}
            </span>
            {title}
          </h1>
        </div>
      </div>
      <EmptyState title="다음 라운드 구현" description={plan} icon="◷" />
    </div>
  )
}
