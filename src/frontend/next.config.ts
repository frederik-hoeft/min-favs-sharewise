import type { NextConfig } from "next";

const backendUrl: string = process.env.BACKEND_URL || "http://localhost:8080";

// The acceptance probe compiles the same routes as a static site, without
// altering the production standalone output or same-origin development proxy.
const staticExportProbe = process.env.SHAREWISE_STATIC_EXPORT_PROBE === "1";

const nextConfig: NextConfig = {
  output: staticExportProbe ? "export" : "standalone",
  ...(staticExportProbe && { distDir: ".next-export-probe" }),
  ...(!staticExportProbe && {
    async rewrites() {
      return [{ source: "/api/:path*", destination: `${backendUrl}/api/:path*` }];
    },
  }),
  experimental: {
    agentFeedback: true,
  },
};

export default nextConfig;
