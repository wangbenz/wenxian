import { defineConfig } from "vite";
import original from "./vite.config";

// 仅供独立账号后端联调，原 Java 与试玩代理配置保持各自入口。
export default defineConfig({
  ...original,
  server: { ...original.server, host: "127.0.0.1", proxy: {
    "/api": { target: process.env.VITE_RUNTIME_API_PROXY ?? "http://127.0.0.1:18094", changeOrigin: true },
  } },
});
