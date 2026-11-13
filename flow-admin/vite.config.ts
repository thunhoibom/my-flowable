import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Flow Admin talks to two backends (see the architecture spec):
//   /api/engine/**   -> Flow Engine    (Work area, and everything until phase 2 splits the clients)
//   /api/admin-be/** -> Flow Admin BE  (Design and Hub areas)
// The prefix is stripped before forwarding, like the nginx config.
function backend(prefix: string, target: string) {
  return {
    target,
    changeOrigin: true,
    rewrite: (path: string) => path.replace(new RegExp(`^${prefix}`), ''),
  }
}

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: '127.0.0.1',
    port: 5174,
    proxy: {
      '/api/engine': backend(
        '/api/engine',
        process.env.VITE_ENGINE_PROXY_TARGET || 'http://localhost:8080',
      ),
      '/api/admin-be': backend(
        '/api/admin-be',
        process.env.VITE_ADMIN_BE_PROXY_TARGET || 'http://localhost:8081',
      ),
    },
  },
})
