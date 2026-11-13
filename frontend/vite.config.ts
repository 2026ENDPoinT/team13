import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// `public/` is copied verbatim (fonts, images, vendored PDF.js/hls.js, lecture media, sw.js).
// Built chunks go under /app/build so server.mjs serves them with the page CSP like the other /app scripts.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: "dist",
    assetsDir: "app/build",
    emptyOutDir: true,
  },
});
