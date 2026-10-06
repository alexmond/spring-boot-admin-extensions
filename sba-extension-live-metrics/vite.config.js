import vue from "@vitejs/plugin-vue";
import path from "path";
import { defineConfig } from "vite";

// Build the extension as a UMD bundle. `vue` is externalized — the SBA server provides the
// Vue runtime at load time (as the global `Vue`), same as the environments extension.
export default defineConfig({
  plugins: [vue()],
  build: {
    target: "es2015",
    sourcemap: true,
    minify: false,
    outDir: "target/dist",
    lib: {
      entry: path.resolve(import.meta.dirname, "src/index.js"),
      name: "LiveMetricsUi",
      formats: ["umd"],
      fileName: () => "live-metrics-ui.js",
    },
    rollupOptions: {
      external: ["vue"],
      output: {
        globals: { vue: "Vue" },
      },
    },
  },
});
