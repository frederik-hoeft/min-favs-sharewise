import { afterEach, describe, expect, it, vi } from "vitest";

async function loadConfiguration(staticExport: boolean) {
  vi.stubEnv("SHAREWISE_STATIC_EXPORT", staticExport ? "1" : "0");
  vi.stubEnv("SHAREWISE_AUTHORIZATION_DEBUG", "0");
  vi.stubEnv("BACKEND_URL", "http://backend.internal:8080");
  vi.resetModules();
  return (await import("../next.config")).default;
}

afterEach(() => {
  vi.unstubAllEnvs();
  vi.resetModules();
});

describe("Next.js build output", () => {
  it("keeps standalone output and the same-origin API rewrite by default", async () => {
    const config = await loadConfiguration(false);
    expect(config.output).toBe("standalone");
    expect(config.env?.SHAREWISE_AUTHORIZATION_DEBUG).toBe("0");
    expect(config.distDir).toBeUndefined();
    expect(config.rewrites).toBeTypeOf("function");
    expect(await config.rewrites!()).toEqual([
      { source: "/api/:path*", destination: "http://backend.internal:8080/api/:path*" },
    ]);
  });

  it("inlines an explicit diagnostic opt-in for the disposable browser build", async () => {
    vi.stubEnv("SHAREWISE_STATIC_EXPORT", "1");
    vi.stubEnv("SHAREWISE_AUTHORIZATION_DEBUG", "1");
    vi.resetModules();
    const config = (await import("../next.config")).default;
    expect(config.env?.SHAREWISE_AUTHORIZATION_DEBUG).toBe("1");
  });

  it("exports static files without embedding an API rewrite", async () => {
    const config = await loadConfiguration(true);
    expect(config.output).toBe("export");
    expect(config.env?.SHAREWISE_AUTHORIZATION_DEBUG).toBe("0");
    expect(config.distDir).toBe(".next-export");
    expect(config.rewrites).toBeUndefined();
  });
});
