import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The browser only ever talks to the console. Live execution updates arrive as
// SSE that the console re-publishes from the runtime's gRPC stream — browsers
// cannot speak gRPC server streaming, and putting a proxy in front of the
// runtime would mean a second identity path for the same person.
export default defineConfig({
  server: {
    proxy: {
      "/api": "http://localhost:8090",
    },
  },
});
