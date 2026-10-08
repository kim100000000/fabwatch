/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** API 베이스 URL (프로덕션 빌드 필수, 개발 모드는 localhost 폴백) */
  readonly VITE_API_BASE_URL?: string
  /** 로그인 화면 '데모 계정' 버튼 노출 ('true' | 'false') */
  readonly VITE_DEMO_LOGIN?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
