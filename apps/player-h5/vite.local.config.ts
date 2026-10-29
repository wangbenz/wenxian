import { defineConfig } from "vite";
import account from "./vite.account.config";

// 本地日常入口使用账号运行时；正式构建仍读取原 vite.config.ts。
export default defineConfig({
  ...account,
  define: { "import.meta.env.VITE_LOCAL_PLAYER_RUNTIME": JSON.stringify("python-account") },
});
