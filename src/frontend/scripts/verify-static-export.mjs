/**
 * P2.0e acceptance probe: compile the existing routes as static files and
 * serve them without a Next.js runtime. The ordinary build configuration is
 * unchanged; this script selects an isolated output mode with an environment
 * variable and removes only its own generated artifacts.
 */
import { spawn } from "node:child_process";
import { createServer } from "node:http";
import { existsSync } from "node:fs";
import { readFile, readdir, rm } from "node:fs/promises";
import { dirname, extname, isAbsolute, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
// With a custom distDir, Next.js writes the export directly into that directory.
const output = join(root, ".next-export-probe");
const routes = [
  { path: "/", text: "Keep the plans." },
  { path: "/app", text: "Your workspace." },
  { path: "/app/dummy1", text: "This placeholder demonstrates a standard feature page" },
  { path: "/app/dummy2", text: "A second destination for validating links" },
  { path: "/app/dummy3", text: "This is an illustrative route for checking long breadcrumbs" },
  { path: "/app/dummy3/international-collaboration-project", text: "International Collaboration Project" },
  { path: "/app/dummy3/international-collaboration-project/shared-expenses-and-settlement-revision-history", text: "Shared Expenses and Settlement Revision History" },
  { path: "/app/dummy3/international-collaboration-project/shared-expenses-and-settlement-revision-history/overview", text: "Nested navigation" },
];

function runBuild() {
  return new Promise((resolveRun, reject) => {
    const child = spawn(process.execPath, [join(root, "node_modules/next/dist/bin/next"), "build"], {
      cwd: root,
      env: { ...process.env, SHAREWISE_STATIC_EXPORT_PROBE: "1", NEXT_TELEMETRY_DISABLED: "1" },
      stdio: "inherit",
    });
    child.once("error", reject);
    child.once("exit", (code) => code === 0 ? resolveRun() : reject(new Error(`Static export build exited with ${code}`)));
  });
}

async function firstAsset(path, extension) {
  for (const entry of await readdir(path, { withFileTypes: true })) {
    const item = join(path, entry.name);
    if (entry.isDirectory()) {
      const found = await firstAsset(item, extension);
      if (found) return found;
    } else if (entry.name.endsWith(extension)) {
      return `/${relative(output, item).split("\\").join("/")}`;
    }
  }
  return null;
}

function serveStaticFiles() {
  return createServer(async (request, response) => {
    try {
      const url = new URL(request.url ?? "/", "http://localhost");
      const path = decodeURIComponent(url.pathname);
      const filename = path === "/" ? "index.html" : path.endsWith("/")
        ? `${path.slice(1)}index.html` : extname(path) ? path.slice(1) : `${path.slice(1)}.html`;
      const target = resolve(output, filename);
      const rel = relative(output, target);
      if (isAbsolute(rel) || rel.startsWith("..")) {
        response.writeHead(403).end();
        return;
      }
      const bytes = await readFile(target);
      const contentType = target.endsWith(".html") ? "text/html" : target.endsWith(".css")
        ? "text/css" : target.endsWith(".js") ? "text/javascript" : "application/octet-stream";
      response.writeHead(200, { "content-type": contentType }).end(bytes);
    } catch {
      response.writeHead(404).end();
    }
  });
}

async function main() {
  // Never remove pre-existing artifacts belonging to another build.
  if (existsSync(output)) {
    throw new Error("Move the existing .next-export-probe/ before running the disposable export probe");
  }

  try {
    await runBuild();
    const server = serveStaticFiles();
    await new Promise((done) => server.listen(0, "127.0.0.1", done));
    try {
      const address = server.address();
      if (!address || typeof address === "string") throw new Error("No local static HTTP port");
      const base = `http://127.0.0.1:${address.port}`;
      for (const route of routes) {
        const response = await fetch(`${base}${route.path}`);
        const body = await response.text();
        if (!response.ok || !body.includes(route.text)) {
          throw new Error(`Static route ${route.path}: HTTP ${response.status} or missing expected page content`);
        }
        console.log(`Static page OK: ${route.path}`);
      }
      for (const extension of [".css", ".js"]) {
        const path = await firstAsset(join(output, "_next/static"), extension);
        if (!path) throw new Error(`Missing exported ${extension} asset`);
        const response = await fetch(`${base}${path}`);
        if (!response.ok || !(await response.arrayBuffer()).byteLength) {
          throw new Error(`Static asset ${path}: HTTP ${response.status}`);
        }
        console.log(`Static asset OK: ${path}`);
      }
    } finally {
      await new Promise((done, reject) => server.close((error) => error ? reject(error) : done()));
    }
  } finally {
    await rm(output, { recursive: true, force: true });
  }
}

await main();
