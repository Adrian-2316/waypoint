import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The build goes straight into Quarkus's static folder. In dev, API and live feed calls go to Quarkus on :8080.
export default defineConfig({
  plugins: [react()],
  build: { outDir: "../src/main/resources/META-INF/resources", emptyOutDir: true },
  server: {
    proxy: {
      "/api": "http://localhost:8080",
      "/j_security_check": "http://localhost:8080",
      "/ws": { target: "ws://localhost:8080", ws: true },
    },
  },
});
