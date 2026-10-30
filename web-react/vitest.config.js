import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: { include: ['src/__tests__/**/*.test.{js,jsx}'], environment: 'node', env: { VITE_API_BASE: 'http://api.test:8080' } },
});
