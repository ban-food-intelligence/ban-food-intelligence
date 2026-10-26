import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Dashboard is served on 5173 both in `npm run dev` and in the Docker `vite preview` container.
export default defineConfig({
  plugins: [react()],
  server: { host: '0.0.0.0', port: 5173 },
  preview: { host: '0.0.0.0', port: 5173 },
});
