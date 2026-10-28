import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: { environment: 'node', env: { VITE_API_BASE: 'http://api.test:8080' } },
});
