import type { UserRole } from './types'

/**
 * 데모 로그인 계정.
 * 시드 계정은 전부 가상 계정이며, 플래그(VITE_DEMO_LOGIN)가 켜진 빌드에서만 화면에 노출한다.
 * 플래그가 꺼져 있으면 이 파일의 값이 UI 어디에도 렌더링되지 않는다.
 */
export interface DemoAccount {
  role: UserRole
  label: string
  email: string
}

export const DEMO_PASSWORD = 'fabwatch123'

export const DEMO_ACCOUNTS: DemoAccount[] = [
  { role: 'ADMIN', label: '관리자', email: 'admin@fabwatch.dev' },
  { role: 'ENGINEER', label: '엔지니어', email: 'engineer@fabwatch.dev' },
  { role: 'TECHNICIAN', label: '테크니션', email: 'tech@fabwatch.dev' },
]

/**
 * 데모 계정 버튼 노출 여부.
 * 기본값: 개발 모드 true / 프로덕션 빌드 false. VITE_DEMO_LOGIN 으로 명시하면 그 값을 따른다.
 */
export const DEMO_LOGIN_ENABLED: boolean =
  import.meta.env.VITE_DEMO_LOGIN !== undefined
    ? import.meta.env.VITE_DEMO_LOGIN === 'true'
    : import.meta.env.DEV
