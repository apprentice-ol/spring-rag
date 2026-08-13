import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// /api 代理到后端 9081，避免 CORS；SSE 流式走 HTTP，proxy 正常转发。
//
// base：仅生产构建（vite build）设为 /api/rag/——因为产物要塞进 Spring Boot 的 static 目录、
//       后端 context-path=/api/rag，base 不同步会导致 index.html 引用 /assets/... 而 404。
//       dev 模式保持 '/'，不影响本地访问 localhost:5173。
export default defineConfig(({ mode }) => ({
  base: mode === 'production' ? '/api/rag/' : '/',
  plugins: [vue()],
  server: {
    port: 5173,
    allowedHosts: ['host.docker.internal', 'localhost'], // 允许容器浏览器（MCP Docker web-test）访问
    proxy: {
      '/api': {
        target: 'http://localhost:9081',
        changeOrigin: true,
      },
    },
  },
}))
