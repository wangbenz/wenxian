import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    host: "0.0.0.0",
    port: 4274,
    proxy: { "/api/v2": { target: process.env.VITE_V2_API_PROXY ?? "http://127.0.0.1:18180", changeOrigin: true } },
  },
});
