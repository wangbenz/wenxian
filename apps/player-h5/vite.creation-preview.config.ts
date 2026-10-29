import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// 使用项目内的同一创角组件，只提供独立前端预览，不配置账号或游戏 API 代理。
export default defineConfig({
  root: fileURLToPath(new URL("./preview/creation/", import.meta.url)),
  publicDir: fileURLToPath(new URL("./public/", import.meta.url)),
  cacheDir: fileURLToPath(new URL("./node_modules/.vite-creation-preview/", import.meta.url)),
  plugins: [react()],
  server: {
    host: "127.0.0.1", port: 4192, strictPort: true,
    fs: { allow: [fileURLToPath(new URL("./", import.meta.url))] },
  },
  build: {
    outDir: fileURLToPath(new URL("./dist/creation-preview/", import.meta.url)),
    emptyOutDir: true,
  },
});
