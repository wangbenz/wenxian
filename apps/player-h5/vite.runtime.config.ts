import { defineConfig } from "vite";
import original from "./vite.config";

// 独立试玩启动配置，复用原 Vite 配置；保护原默认运行入口。
export default defineConfig({
  ...original,
  server: {
    ...original.server,
    host: "127.0.0.1",
    proxy: {
      "/api/runtime": {
        target: process.env.VITE_RUNTIME_API_PROXY ?? "http://127.0.0.1:18090",
        changeOrigin: true,
      },
      ...original.server?.proxy,
    },
  },
});
