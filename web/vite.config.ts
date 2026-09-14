import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    host: '0.0.0.0',
    allowedHosts: ['epr.test', 'support.epr.test', 'localhost'],
    proxy: {
      '/api': 'http://127.0.0.1:8080',
    },
  },
})
