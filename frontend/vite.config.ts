import { fileURLToPath, URL } from 'node:url'
import { loadEnv } from 'vite'
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// FSD 라이트 구조 기준 절대 경로 별칭(@ → src) 사용
export default defineConfig(({ mode, command }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_')

  // 프로덕션 빌드에서 API 주소가 없으면 localhost 로 폴백하지 않고 상대경로(/api/v1)를 쓴다.
  // 리버스 프록시 없이 배포하면 API 호출이 실패하므로 빌드 시점에 눈에 띄게 경고한다.
  if (command === 'build' && mode === 'production' && !env.VITE_API_BASE_URL) {
    console.warn(
      '\n[fabwatch] 경고: VITE_API_BASE_URL 이 설정되지 않았습니다. ' +
        '프로덕션 번들은 상대경로 "/api/v1" 로 API 를 호출합니다. ' +
        '백엔드가 다른 도메인이면 빌드 환경변수에 VITE_API_BASE_URL 을 지정하세요.\n',
    )
  }

  return {
    plugins: [react()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      port: 5173, // docs/11 §4 CORS 허용 Origin과 일치
    },
    // 단위 테스트: 도메인 로직(인터셉터/임계치 검증/시각/상태 전이/KPI 포맷) 위주
    test: {
      environment: 'jsdom',
      include: ['src/**/*.test.ts'],
    },
  }
})
