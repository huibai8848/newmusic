import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react-swc';
import { fileURLToPath, URL } from 'node:url';

// 后台独立端口 5174：后端 AdminJwtProperties.allowedOrigins 的默认白名单包含它
const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8081';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5174,
    strictPort: true,
    open: false,
    proxy: {
      // 同源代理：后台 Cookie 是 SameSite=Strict，必须经同源请求才能带上
      '/api': { target: BACKEND, changeOrigin: true },
      '/media': { target: BACKEND, changeOrigin: true },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 1200,
  },
});
