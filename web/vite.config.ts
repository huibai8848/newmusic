import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react-swc';
import { VitePWA } from 'vite-plugin-pwa';
import { fileURLToPath, URL } from 'node:url';

// 后端服务地址（模块 01：本机 8080 被占用，服务端口 8081）
const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8081';

export default defineConfig({
  plugins: [
    react(),
    /**
     * PWA（模块 12 FR-21-005）。
     *
     * - `registerType: 'prompt'`：新版本**不自动刷新**，避免正在播放时被 Service Worker 打断；
     * - 只预缓存应用外壳与图片：音频与 `/api` 一律不进 SW 缓存（版权签名 URL 会过期，
     *   离线下载属于 P2 FR-21-006，不能靠 HTTP 缓存冒充）；
     * - `injectRegister: false`：由 `PwaUpdatePrompt` 手动注册，才能拿到「有新版本」回调。
     */
    VitePWA({
      registerType: 'prompt',
      injectRegister: false,
      manifest: {
        id: '/',
        name: 'MusicLive 在线音乐',
        short_name: 'MusicLive',
        description: '在线听歌、歌词、歌单与一起听',
        lang: 'zh-CN',
        start_url: '/',
        scope: '/',
        display: 'standalone',
        background_color: '#ffffff',
        theme_color: '#1677ff',
        categories: ['music', 'entertainment'],
        icons: [
          { src: '/icons/icon-192.png', sizes: '192x192', type: 'image/png', purpose: 'any' },
          { src: '/icons/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
          { src: '/icons/icon-maskable-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
      },
      workbox: {
        globPatterns: ['**/*.{js,css,html,svg,png,woff2}'],
        navigateFallback: '/index.html',
        navigateFallbackDenylist: [/^\/api\//, /^\/ws/, /^\/media\//],
        cleanupOutdatedCaches: true,
        runtimeCaching: [
          {
            // 封面/头像等图片可以缓存；音频（mp3/flac/wav）刻意不匹配
            urlPattern: /\/media\/.*\.(?:png|jpe?g|webp|gif|svg)$/i,
            handler: 'CacheFirst',
            options: {
              cacheName: 'ml-media-images',
              expiration: { maxEntries: 200, maxAgeSeconds: 30 * 24 * 60 * 60 },
              cacheableResponse: { statuses: [0, 200] },
            },
          },
        ],
      },
      devOptions: { enabled: false },
    }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  css: {
    preprocessorOptions: {
      less: {
        javascriptEnabled: true,
      },
    },
  },
  server: {
    port: 5173,
    open: false,
    proxy: {
      // 前端不直连数据库，/api 与 /media 由 dev server 代理到后端
      '/api': { target: BACKEND, changeOrigin: true },
      '/media': { target: BACKEND, changeOrigin: true },
      // 模块 04：原生 WebSocket 走同一域名，dev 下由 Vite 升级转发（ws: true）
      '/ws': { target: BACKEND, changeOrigin: true, ws: true },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 1200,
  },
});
