import './ui.css'

interface PaginationProps {
  /** 0 기반 현재 페이지 (서버 number) */
  page: number
  totalPages: number
  totalElements?: number
  onChange: (page: number) => void
}

/** 공통 페이저 — 이전/다음 + 현재 위치. 페이지가 1개 이하면 건수만 보여준다. */
export function Pagination({ page, totalPages, totalElements, onChange }: PaginationProps) {
  const lastPage = Math.max(totalPages - 1, 0)
  return (
    <div className="pagination">
      {totalElements !== undefined && <span className="pagination-total">총 {totalElements}건</span>}
      {totalPages > 1 && (
        <div className="pagination-controls">
          <button type="button" className="btn btn-sm" disabled={page <= 0} onClick={() => onChange(page - 1)}>
            이전
          </button>
          <span className="mono pagination-pos">
            {page + 1} / {totalPages}
          </span>
          <button
            type="button"
            className="btn btn-sm"
            disabled={page >= lastPage}
            onClick={() => onChange(page + 1)}
          >
            다음
          </button>
        </div>
      )}
    </div>
  )
}
