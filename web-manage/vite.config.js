import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'

// 构建产物直接放进网关的静态目录，随后台一起部署，访问地址 /admin/
export default defineConfig({
  base: '/admin/',
  plugins: [vue()],
  build: {
    outDir: fileURLToPath(new URL('../gateway/src/main/resources/static/admin', import.meta.url)),
    emptyOutDir: true,
    chunkSizeWarningLimit: 2000,
  },
  server: {
    // 本地开发：npm run dev，接口转发到本机后台（gateway 默认端口 80，可用 PORT 改）
    proxy: { '/admin-api': 'http://localhost:80', '/api': 'http://localhost:80' },
  },
})
