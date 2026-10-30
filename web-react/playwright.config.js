import { defineConfig } from '@playwright/test';

const api = process.env.E2E_API_BASE || 'http://127.0.0.1:8080';
export default defineConfig({
  testDir: './e2e', fullyParallel: false, workers: 1, timeout: 60_000,
  use: { baseURL: 'http://127.0.0.1:4173', locale: 'tr-TR',
    ...(process.env.E2E_BROWSER_CHANNEL ? { channel: process.env.E2E_BROWSER_CHANNEL } : {}),
    screenshot: 'only-on-failure', trace: 'retain-on-failure' },
  webServer: { command: 'npm run dev -- --port 4173 --strictPort', url: 'http://127.0.0.1:4173',
    reuseExistingServer: !process.env.CI, env: { VITE_API_BASE: api } },
});
