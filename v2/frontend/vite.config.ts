import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  publicDir: "../../frontend/public",
  build: {
    outDir: "dist",
    emptyOutDir: true,
  },
  server: {
    host: "0.0.0.0",
    port: 4273,
    proxy: {
      "/api/v1": {
        target: process.env.VITE_V1_API_PROXY ?? "http://127.0.0.1:18080",
        changeOrigin: true,
      },
      "/api/v2": {
        target: process.env.VITE_V2_API_PROXY ?? "http://127.0.0.1:18180",
        changeOrigin: true,
      },
    },
  },
});
