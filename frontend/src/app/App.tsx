import { BrowserRouter } from 'react-router-dom'
import { AuthProvider } from './providers'
import { AppRoutes } from './router'

/** 전역 프로바이더 + 라우터 조립 */
export function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <AppRoutes />
      </AuthProvider>
    </BrowserRouter>
  )
}
