/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** API 베이스 URL (프로덕션 빌드 필수, 개발 모드는 localhost 폴백) */
  readonly VITE_API_BASE_URL?: string
  /** 로그인 화면 '데모 계정' 버튼 노출 ('true' | 'false') */
  readonly VITE_DEMO_LOGIN?: string
  /** 운영 데모 빌드의 공개 비밀번호(시크릿 아님 — 번들에 노출됨). 비어 있으면 운영 빌드에서 데모 로그인이 꺼진다 */
  readonly VITE_DEMO_PASSWORD?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
