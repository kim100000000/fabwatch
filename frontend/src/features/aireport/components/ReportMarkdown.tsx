import { isValidElement } from 'react'
import type { ReactNode } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import type { Components } from 'react-markdown'
import './report.css'

/**
 * LLM 출력은 신뢰하지 않는다 (docs/11 §7).
 * - raw HTML 은 렌더링하지 않는다: react-markdown 은 기본적으로 HTML 을 해석하지 않고(rehype-raw 미사용),
 *   skipHtml 로 HTML 노드를 아예 버린다 → <script>/<iframe>/onerror 등은 화면에 나오지 않는다.
 * - 링크 URL 은 react-markdown 기본 urlTransform 이 http/https/mailto 등 안전한 프로토콜만 통과시킨다
 *   (javascript: 등은 제거). 링크는 새 탭 + rel=noopener noreferrer.
 * - 링크 텍스트에 실제 URL 도메인이 보이지 않으면(텍스트≠URL 피싱 가능) 도메인을 옆에 표시한다.
 * - 이미지는 렌더링하지 않는다 (외부 이미지 요청으로 인한 추적·유출 방지) — 대체 텍스트만 표시.
 */
/** 링크 자식 노드에서 보이는 글자만 모은다 */
function textOf(node: ReactNode): string {
  if (typeof node === 'string' || typeof node === 'number') return String(node)
  if (Array.isArray(node)) return node.map(textOf).join('')
  if (isValidElement<{ children?: ReactNode }>(node)) return textOf(node.props.children)
  return ''
}

/** http(s) 링크의 호스트 — 파싱 실패/그 외 프로토콜이면 null */
function externalHost(href: string): string | null {
  try {
    const url = new URL(href)
    return url.protocol === 'http:' || url.protocol === 'https:' ? url.hostname : null
  } catch {
    return null
  }
}

const COMPONENTS: Components = {
  a: ({ href, children }) => {
    // 안전하지 않은 프로토콜(javascript: 등)은 urlTransform 이 href 를 비운다 — 링크 없이 글자만 보여준다
    if (!href) return <span>{children}</span>
    const host = externalHost(href)
    // 보이는 글자에 실제 도메인이 없으면 도메인을 함께 보여 준다
    const showHost = host !== null && !textOf(children).toLowerCase().includes(host.toLowerCase())
    return (
      <>
        <a href={href} target="_blank" rel="noopener noreferrer nofollow">
          {children}
        </a>
        {showHost && <span className="report-md-link-host"> ({host})</span>}
      </>
    )
  },
  img: ({ alt }) => <span className="report-md-image">[이미지 생략{alt ? `: ${alt}` : ''}]</span>,
}

interface ReportMarkdownProps {
  content: string
}

/** 리포트 마크다운 안전 렌더러 (GFM 표/체크리스트 지원) */
export function ReportMarkdown({ content }: ReportMarkdownProps) {
  return (
    <div className="report-md">
      <ReactMarkdown remarkPlugins={[remarkGfm]} skipHtml components={COMPONENTS}>
        {content}
      </ReactMarkdown>
    </div>
  )
}
