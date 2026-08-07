import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// FSD 라이트 구조 기준 절대 경로 별칭(@ → src) 사용
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173, // docs/11 §4 CORS 허용 Origin과 일치
  },
})
