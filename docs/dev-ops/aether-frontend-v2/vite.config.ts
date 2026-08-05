import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8091',
        changeOrigin: true,
      },
      '/fs': {
        target: 'http://localhost:8003',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/fs/, ''),
        timeout: 30_000,
      },
      '/doc': {
        target: 'http://localhost:8001',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/doc/, ''),
        timeout: 30_000,
      },
      '/sandbox': {
        target: 'http://localhost:8002',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/sandbox/, ''),
        timeout: 300_000,
      },
    },
  },
  build: {
    target: 'ES2020',
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 800,
  },
})
