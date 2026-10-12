import type { UserRole } from './types'

/**
 * 데모 로그인 계정.
 * 전부 가상 계정이며, 노출 여부·계정 목록·비밀번호는 빌드 환경에 따라 아래 두 함수가 결정한다.
 *
 * - 개발 모드(DEV): 기존대로 3계정(ADMIN 포함) + 시드 비밀번호. VITE_DEMO_LOGIN=false 로만 끈다.
 * - 운영 빌드(PROD): VITE_DEMO_LOGIN=true 일 때만 노출하며 **ADMIN 버튼은 절대 포함하지 않는다**
 *   (ADMIN 은 서버의 부트스트랩 계정만 비공개로 보유). 비밀번호는 빌드 시 주입되는 VITE_DEMO_PASSWORD 이고,
 *   비어 있으면 데모 로그인을 켜지 않는다.
 *
 * ★ VITE_DEMO_PASSWORD 는 시크릿이 아니다 — VITE_ 값은 번들(공개 JS)에 그대로 박히므로 누구나 볼 수 있다.
 *   공개 데모용 "권한이 낮은 가상 계정(ENGINEER/TECHNICIAN)의 공개 비밀번호"로만 쓰고, 운영 ADMIN 비밀번호와 절대 같게 하지 않는다.
 *
 * 데모가 꺼진 운영 빌드에서는 이 파일의 계정·비밀번호가 번들에서 제거된다(tree-shaking, 빌드 후 grep 으로 검증 — docs/13 §3.3).
 * 그래서 아래 함수들은 모듈 최상단의 DEV/PROD 분기에서만 호출하고, 계정 상수는 이 파일 밖에서 참조하지 않는다.
 */
export interface DemoAccount {
  role: UserRole
  label: string
  email: string
}

export interface DemoLoginConfig {
  enabled: boolean
  accounts: DemoAccount[]
  password: string
}

const DISABLED: DemoLoginConfig = { enabled: false, accounts: [], password: '' }

/** ADMIN 이 아닌 데모 계정 — 운영 데모 빌드에서도 쓰는 목록 */
const DEMO_USER_ACCOUNTS: DemoAccount[] = [
  { role: 'ENGINEER', label: '엔지니어', email: 'engineer@fabwatch.dev' },
  { role: 'TECHNICIAN', label: '테크니션', email: 'tech@fabwatch.dev' },
]

/** 개발 모드 전용 — 로컬 시드(fabwatch123)와 ADMIN 데모 계정 포함 */
export function resolveDevDemoLogin(flag: string | undefined): DemoLoginConfig {
  // 개발 모드는 플래그가 없으면 켠다. 'false' 등 명시하면 그 값을 따른다.
  if (flag !== undefined && flag !== 'true') return DISABLED
  return {
    enabled: true,
    accounts: [{ role: 'ADMIN', label: '관리자', email: 'admin@fabwatch.dev' }, ...DEMO_USER_ACCOUNTS],
    password: 'fabwatch123',
  }
}

/** 운영 빌드 — 명시적으로 'true' 이고 공개 데모 비밀번호가 있을 때만. ADMIN 제외. */
export function resolveProdDemoLogin(flag: string | undefined, publicPassword: string | undefined): DemoLoginConfig {
  const password = (publicPassword ?? '').trim()
  if (flag !== 'true' || password === '') return DISABLED
  return { enabled: true, accounts: DEMO_USER_ACCOUNTS.map((account) => ({ ...account })), password }
}

/**
 * 현재 빌드의 데모 로그인 설정.
 * DEV/PROD 와 'VITE_DEMO_LOGIN === "true"' 는 빌드 시 상수로 접혀, 운영 빌드에서 데모가 꺼져 있으면
 * 계정·비밀번호 코드가 번들에서 통째로 빠진다(함수 호출 안에서 플래그를 보면 접히지 않으므로 여기서 분기한다).
 */
export const DEMO_LOGIN: DemoLoginConfig = import.meta.env.DEV
  ? resolveDevDemoLogin(import.meta.env.VITE_DEMO_LOGIN)
  : import.meta.env.VITE_DEMO_LOGIN === 'true'
    ? resolveProdDemoLogin('true', import.meta.env.VITE_DEMO_PASSWORD)
    : DISABLED

/** 데모 계정 버튼 노출 여부 */
export const DEMO_LOGIN_ENABLED: boolean = DEMO_LOGIN.enabled
