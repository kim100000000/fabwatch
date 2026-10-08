import { useId, useState } from 'react'
import type { ReactNode } from 'react'
import { GLOSSARY } from '@/shared/lib/glossary'
import type { GlossaryKey } from '@/shared/lib/glossary'
import './ui.css'

interface TermProps {
  term: GlossaryKey
  /** 기본은 용어 사전의 label */
  children?: ReactNode
}

/**
 * 용어 풀이 툴팁. 마우스 hover 와 키보드 포커스 모두에서 열리고, 풀이는 aria-describedby 로 연결된다.
 * 툴팁은 position: fixed 로 그려 표/스크롤 영역에 잘리지 않는다.
 */
export function Term({ term, children }: TermProps) {
  const entry = GLOSSARY[term]
  const tipId = useId()
  const [pos, setPos] = useState<{ top: number; left: number } | null>(null)

  const open = (element: HTMLElement) => {
    const rect = element.getBoundingClientRect()
    const width = Math.min(260, window.innerWidth - 16)
    const left = Math.max(8, Math.min(rect.left, window.innerWidth - width - 8))
    setPos({ top: rect.bottom + 6, left })
  }

  return (
    <span
      className="term"
      tabIndex={0}
      aria-describedby={tipId}
      onMouseEnter={(event) => open(event.currentTarget)}
      onFocus={(event) => open(event.currentTarget)}
      onMouseLeave={() => setPos(null)}
      onBlur={() => setPos(null)}
      onKeyDown={(event) => {
        if (event.key === 'Escape') setPos(null)
      }}
    >
      {children ?? entry.label}
      <span
        id={tipId}
        role="tooltip"
        className="term-tip"
        data-open={pos ? 'true' : undefined}
        style={pos ? { top: pos.top, left: pos.left } : undefined}
      >
        {entry.description}
      </span>
    </span>
  )
}
