import { useState } from 'react'
import type { FormEvent } from 'react'
import { useAuth } from '@/app/providers/useAuth'
import { toUserMessage } from '@/shared/lib/errorMessage'
import { Spinner } from '@/shared/ui'
import './LoginForm.css'

interface LoginFormProps {
  /** 로그인 성공 후 이동 처리 */
  onSuccess: () => void
}

/** S-0 로그인 폼 — POST /auth/login */
export function LoginForm({ onSuccess }: LoginFormProps) {
  const { login } = useAuth()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (submitting) return

    setSubmitting(true)
    setError(null)
    try {
      await login({ email: email.trim(), password })
      onSuccess()
    } catch (cause) {
      // 에러 문구는 code 기준 매핑에서 가져온다 (message 파싱 금지)
      setError(toUserMessage(cause))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form className="login-form" onSubmit={handleSubmit} noValidate>
      <div className="field">
        <label htmlFor="login-email">이메일</label>
        <input
          id="login-email"
          type="email"
          value={email}
          autoComplete="username"
          placeholder="engineer@fabwatch.dev"
          onChange={(event) => setEmail(event.target.value)}
          required
        />
      </div>

      <div className="field">
        <label htmlFor="login-password">비밀번호</label>
        <input
          id="login-password"
          type="password"
          value={password}
          autoComplete="current-password"
          onChange={(event) => setPassword(event.target.value)}
          required
        />
      </div>

      {error && (
        <p className="login-error" role="alert">
          {error}
        </p>
      )}

      <button
        type="submit"
        className="btn btn-primary login-submit"
        disabled={submitting || !email || !password}
      >
        {submitting && <Spinner />}
        {submitting ? '로그인 중…' : '로그인'}
      </button>
    </form>
  )
}
