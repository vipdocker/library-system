import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// 端口与代理目标契约见 docs/design/solution-design.md 第 5 节（D5）。
export default defineConfig({
  plugins: [react()],
  server: {
    port: Number(process.env.FRONTEND_PORT ?? 15195),
    strictPort: true,
    proxy: {
      '/api': {
        target: process.env.VITE_API_PROXY_TARGET ?? 'http://localhost:18095',
        changeOrigin: false,
      },
    },
  },
  preview: {
    port: Number(process.env.FRONTEND_PORT ?? 15195),
    strictPort: true,
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./vitest.setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    css: false,
    clearMocks: true,
    restoreMocks: true,
  },
});