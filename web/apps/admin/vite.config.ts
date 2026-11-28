import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// In dev, set VITE_API_URL="" to call the API through this proxy (same origin, so no CORS entry is needed).
const api = process.env.API_PROXY ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  server: { port: Number(process.env.PORT ?? 5173), strictPort: true, host: true, proxy: { '/v1': { target: api, changeOrigin: true, headers: { origin: 'http://localhost:5173' } } } },
  // Third-party code (React, the router) changes rarely: a separate file stays cached across our releases.
  build: { rollupOptions: { output: { manualChunks: (id) => (id.includes('node_modules') && !id.includes('@fontsource') ? 'vendor' : undefined) } } },
  preview: { port: 5173, host: true },
})
