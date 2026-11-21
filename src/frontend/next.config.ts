import type { NextConfig } from "next";

const backendUrl: string = process.env.BACKEND_URL || "http://localhost:8080";

// The Docker build and the disposable acceptance test use the same static export
// mode. Normal development/CI builds retain standalone output and the API rewrite.
const staticExport = process.env.SHAREWISE_STATIC_EXPORT === "1";

const nextConfig: NextConfig = {
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
