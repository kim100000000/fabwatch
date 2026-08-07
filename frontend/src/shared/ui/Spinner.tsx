import './ui.css'

interface LoadingBlockProps {
  label?: string
}

/** 인라인 스피너 */
export function Spinner() {
  return <span className="spinner" aria-hidden="true" />
}

/** 영역 단위 로딩 표시 */
export function LoadingBlock({ label = '불러오는 중…' }: LoadingBlockProps) {
  return (
    <div className="spinner-block" role="status">
      <Spinner />
      <span>{label}</span>
    </div>
  )
}
