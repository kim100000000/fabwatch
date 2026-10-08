import { Link } from 'react-router-dom'
import { usePageTitle } from '@/shared/hooks/usePageTitle'
import { EmptyState } from '@/shared/ui'

/** 정의되지 않은 경로 */
export function NotFoundPage() {
  usePageTitle('페이지를 찾을 수 없음')
  return (
    <div className="page">
      <EmptyState
        title="페이지를 찾을 수 없습니다"
        description="주소를 확인해 주세요."
        icon="✕"
        action={<Link to="/">라인 현황으로 이동</Link>}
      />
    </div>
  )
}
