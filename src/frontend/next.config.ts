import type { NextConfig } from "next";

const backendUrl: string = process.env.BACKEND_URL || "http://localhost:8080";

// The Docker build and the disposable acceptance test use the same static export
// mode. Normal development/CI builds retain standalone output and the API rewrite.
const staticExport = process.env.SHAREWISE_STATIC_EXPORT === "1";

const nextConfig: NextConfig = {
  // Always inline an explicit build-time value, including the disabled default.
  env: { SHAREWISE_AUTHORIZATION_DEBUG: process.env.SHAREWISE_AUTHORIZATION_DEBUG === "1" ? "1" : "0" },
  output: staticExport ? "export" : "standalone",
  ...(staticExport && { distDir: ".next-export" }),
  ...(!staticExport && {
    async rewrites() {
      return [{ source: "/api/:path*", destination: `${backendUrl}/api/:path*` }];
    },
  }),
  experimental: {
    agentFeedback: true,
  },
};

export default nextConfig;
