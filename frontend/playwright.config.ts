import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import { defineConfig, devices } from '@playwright/test';

// E2E 配置（T02 骨架，T12 完善）：7 步冒烟用例见 e2e/smoke.spec.ts。
// 端口契约（D5）：后端 18096、前端 15196；后端使用临时数据库，绝不触碰 backend/data/library.db。
const frontendDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(frontendDir, '..');

const javaHome =
  process.env.LIB_JAVA_HOME ?? '/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home';
const backendPort = Number(process.env.E2E_BACKEND_PORT ?? 18096);
const frontendPort = Number(process.env.E2E_FRONTEND_PORT ?? 15196);
const e2eDbPath = path.join(os.tmpdir(), `library-e2e-${Date.now()}.db`);
const brewPath = `/opt/homebrew/bin:${process.env.PATH ?? ''}`;

export default defineConfig({
  testDir: './e2e',
  // Playwright 运行前会清空 outputDir；test-results/ 根目录下还有其他任务的自检产物
  // （如 candidate-snapshot-files.txt，不得删除），所以产物写入独立子目录。
  outputDir: './test-results/e2e',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 60_000,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: `http://localhost:${frontendPort}`,
    headless: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: [
    {
      // 与 scripts/env.sh 同等逻辑：显式 JAVA_HOME(21) + /opt/homebrew/bin，不加载 nvm。
      command: 'mvn -f backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=dev',
      cwd: repoRoot,
      env: {
        ...process.env,
        JAVA_HOME: javaHome,
        PATH: `${javaHome}/bin:${brewPath}`,
        SERVER_PORT: String(backendPort),
        LIB_DB_PATH: e2eDbPath,
        LIB_ADMIN_PASSWORD: process.env.LIB_ADMIN_PASSWORD ?? 'Admin@12345',
      },
      url: `http://localhost:${backendPort}/api/auth/csrf`,
      timeout: 180_000,
      reuseExistingServer: false,
      stdout: 'pipe',
      stderr: 'pipe',
    },
    {
      command: '/opt/homebrew/bin/npm run dev',
      cwd: frontendDir,
      env: {
        ...process.env,
        PATH: brewPath,
        FRONTEND_PORT: String(frontendPort),
        VITE_API_PROXY_TARGET: `http://localhost:${backendPort}`,
      },
      url: `http://localhost:${frontendPort}`,
      timeout: 120_000,
      reuseExistingServer: false,
      stdout: 'pipe',
      stderr: 'pipe',
    },
  ],
});