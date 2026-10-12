import { describe, expect, it } from 'vitest'
import { resolveDevDemoLogin, resolveProdDemoLogin } from './demoAccounts'

describe('resolveDevDemoLogin (개발 모드)', () => {
  it('플래그가 없으면 3계정(ADMIN 포함)과 시드 비밀번호로 켜진다', () => {
    const config = resolveDevDemoLogin(undefined)
    expect(config.enabled).toBe(true)
    expect(config.accounts.map((a) => a.role)).toEqual(['ADMIN', 'ENGINEER', 'TECHNICIAN'])
    expect(config.password).toBe('fabwatch123')
  })

  it("'true' 도 켜지고 'false' 는 끈다", () => {
    expect(resolveDevDemoLogin('true').enabled).toBe(true)
    expect(resolveDevDemoLogin('false')).toEqual({ enabled: false, accounts: [], password: '' })
  })
})

describe('resolveProdDemoLogin (운영 빌드)', () => {
  it("플래그가 'true' 가 아니면 비밀번호가 있어도 꺼진다", () => {
    for (const flag of [undefined, 'false', '', 'TRUE', '1']) {
      expect(resolveProdDemoLogin(flag, 'pw-demo').enabled).toBe(false)
    }
  })

  it('비밀번호가 비었거나 공백이면 켜지지 않는다', () => {
    expect(resolveProdDemoLogin('true', undefined).enabled).toBe(false)
    expect(resolveProdDemoLogin('true', '').enabled).toBe(false)
    expect(resolveProdDemoLogin('true', '   ').enabled).toBe(false)
  })

  it('켜지면 ENGINEER/TECHNICIAN 만 노출하고 ADMIN 은 절대 포함하지 않는다', () => {
    const config = resolveProdDemoLogin('true', ' pw-demo ')
    expect(config.enabled).toBe(true)
    expect(config.accounts.map((a) => a.role)).toEqual(['ENGINEER', 'TECHNICIAN'])
    expect(config.accounts.some((a) => a.role === 'ADMIN' || a.email.startsWith('admin@'))).toBe(false)
    expect(config.password).toBe('pw-demo')
  })

  it('시드 비밀번호(fabwatch123)를 쓰지 않는다', () => {
    expect(resolveProdDemoLogin('true', 'pw-demo').password).not.toBe('fabwatch123')
  })
})
