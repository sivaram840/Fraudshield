import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The dev server proxies /api to the Spring Boot backend, so no CORS setup is needed in development.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // Listen on IPv4 so both http://localhost:5173 and http://127.0.0.1:5173 work (Windows may resolve localhost to IPv6 only).
    host: '127.0.0.1',
    proxy: { '/api': process.env.BACKEND_URL || 'http://localhost:8080' },
  },
});
