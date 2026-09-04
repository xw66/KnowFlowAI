import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: { port: 5173, proxy: { '/api': process.env.KNOWFLOW_API_TARGET || 'http://localhost:8080' } }
})
