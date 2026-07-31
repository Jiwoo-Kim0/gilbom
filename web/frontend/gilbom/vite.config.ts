import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  build: {
    // 운영 image에는 원본 TypeScript 경로와 코드를 복원할 source map을 싣지 않는다.
    sourcemap: false,
  },
  server: {
    // 개발 서버에서도 브라우저는 same-origin 상대 경로만 사용한다.
    proxy: {
      '/api': 'http://127.0.0.1:8080',
    },
  },
})
