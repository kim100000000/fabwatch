import { Link } from 'react-router-dom'
import { useAuth } from '@/app/providers/useAuth'
import { usePageTitle } from '@/shared/hooks/usePageTitle'
import { EmptyState } from '@/shared/ui'

/** 관리 (/admin) — ADMIN 전용. 현재는 준비 중 안내만 제공한다. */
export function AdminPage() {
  const { user } = useAuth()
  usePageTitle('관리')

  // 메뉴는 ADMIN 에게만 보이지만 주소로 직접 들어오는 경우도 막는다
  if (user?.role !== 'ADMIN') {
    return (
      <div className="page">
        <EmptyState
          title="접근 권한이 없습니다"
          description="관리 화면은 관리자만 사용할 수 있습니다."
          icon="⚒"
          action={<Link to="/">라인 현황으로 이동</Link>}
        />
      </div>
    )
  }

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">관리</h1>
        </div>
      </div>
      <EmptyState
        title="준비 중입니다"
        description="사용자 관리 등 관리 기능은 준비 중입니다. 임계치 편집은 설비 상세의 센서 탭에서 할 수 있습니다."
        icon="◷"
      />
    </div>
  )
}
