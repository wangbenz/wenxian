export default defineNuxtConfig({
  srcDir: "app/",
  dir: { public: "../public" },
  app: {
    pageTransition: { name: "fade", mode: "out-in" },
    head: {
      title: "开物 · Loreforge",
      htmlAttrs: { lang: "zh-CN" },
      meta: [
        { charset: "utf-8" },
        { name: "viewport", content: "width=device-width, initial-scale=1" },
        {
          name: "description",
          content: "开物修仙资料库：法术、物品、规则和状态。",
        },
        { name: "theme-color", content: "#edf5f2" },
      ],
    },
  },
  vue: { runtimeCompiler: false },
  nitro: { preset: "node-server" },
  modules: [
    "@nuxt/eslint",
    [
      "@nuxtjs/tailwindcss",
      { configPath: "tailwind.config.ts", cssPath: "~/styles/tailwind.css" },
    ],
    "@nuxt/test-utils/module",
  ],
  runtimeConfig: {
    public: {
      apiUrl: (
        process.env.NUXT_PUBLIC_API_URL ||
        process.env.API_URL ||
        "http://127.0.0.1:18087"
      ).replace(/\/+$/, ""),
    },
  },
  telemetry: false,
  devtools: { enabled: false },
  typescript: { strict: true },
  imports: { autoImport: true, global: true },
  compatibilityDate: "2024-11-16",
});
