/**
 * P2.0e acceptance probe: compile the existing routes as static files and
 * serve them without a Next.js runtime. The ordinary build configuration is
 * unchanged. Next.js uses .next/ for intermediate files even when distDir
 * selects an export destination, so the probe builds in a disposable copy.
 */
import { spawn } from "node:child_process";
import { createServer } from "node:http";
import { cp, mkdtemp, readFile, readdir, rm } from "node:fs/promises";
import { dirname, extname, isAbsolute, join, relative, resolve, sep } from "node:path";
import { tmpdir } from "node:os";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const excluded = new Set(["node_modules", ".next", ".next-export", "out", "coverage", ".git"]);
const routes = [
  { path: "/", text: "Keep the plans." },
  { path: "/auth/login", text: "Welcome back." },
  { path: "/auth/register", text: "Start sharing clearly." },
  { path: "/app", text: "Your workspace." },
  { path: "/app/account", text: "Your account" },
  // Group pages render their content client-side behind the workspace guard; their titles are exported.
  { path: "/app/groups", text: "<title>Groups | ShareWise</title>" },
  { path: "/app/groups/new", text: "<title>Create a group | ShareWise</title>" },
  { path: "/app/groups/view", text: "<title>Group | ShareWise</title>" },
  { path: "/app/dummy1", text: "This placeholder demonstrates a standard feature page" },
  { path: "/app/dummy2", text: "A second destination for validating links" },
  { path: "/app/dummy3", text: "This is an illustrative route for checking long breadcrumbs" },
  { path: "/app/dummy3/international-collaboration-project", text: "International Collaboration Project" },
  { path: "/app/dummy3/international-collaboration-project/shared-expenses-and-settlement-revision-history", text: "Shared Expenses and Settlement Revision History" },
  { path: "/app/dummy3/international-collaboration-project/shared-expenses-and-settlement-revision-history/overview", text: "Nested navigation" },
];

function runBuild(workspace) {
  return new Promise((resolveRun, reject) => {
    const child = spawn(process.execPath, [join(workspace, "node_modules/next/dist/bin/next"), "build"], {
      cwd: workspace,
      env: { ...process.env, SHAREWISE_STATIC_EXPORT: "1", NEXT_TELEMETRY_DISABLED: "1" },
      stdio: "inherit",
    });
    child.once("error", reject);
    child.once("exit", (code) => code === 0 ? resolveRun() : reject(new Error(`Static export build exited with ${code}`)));
  });
}

function runCopy(source, destination) {
  return new Promise((resolveCopy, reject) => {
    const child = spawn("cp", ["-a", source, destination], { stdio: "inherit" });
    child.once("error", reject);
    child.once("exit", (code) => code === 0 ? resolveCopy() : reject(new Error(`Dependency copy exited with ${code}`)));
  });
}

async function firstAsset(output, path, extension) {
  for (const entry of await readdir(path, { withFileTypes: true })) {
    const item = join(path, entry.name);
    if (entry.isDirectory()) {
      const found = await firstAsset(output, item, extension);
      if (found) return found;
    } else if (entry.name.endsWith(extension)) {
      return `/${relative(output, item).split("\\").join("/")}`;
    }
  }
  return null;
}

function serveStaticFiles(output) {
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
  const temporaryRoot = await mkdtemp(join(tmpdir(), "sharewise-static-export-"));
  const workspace = join(temporaryRoot, "frontend");
  const output = join(workspace, ".next-export");

  try {
    // Copy source without generated outputs, then copy installed dependencies.
    // A physical dependency copy is necessary: Turbopack does not consistently
    // resolve Next.js through a node_modules symlink outside the build root.
    await cp(root, workspace, {
      recursive: true,
      filter: (source) => {
        const path = relative(root, source);
        const first = path.split(sep)[0];
        return !excluded.has(first) && !path.endsWith(".tsbuildinfo");
      },
    });
    // The CI runner and offline tooling target Linux. Its cp implementation
    // preserves relative npm symlinks and copies large dependency trees much
    // faster than issuing a Node filesystem operation for every package file.
    await runCopy(join(root, "node_modules"), join(workspace, "node_modules"));

    await runBuild(workspace);
    const server = serveStaticFiles(output);
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
      // Old root-level authentication URLs must not remain exported as aliases.
      for (const retired of ["/login", "/register"]) {
        const response = await fetch(`${base}${retired}`);
        if (response.status !== 404) {
          throw new Error(`Retired route ${retired} should return 404 (got ${response.status})`);
        }
      }
      for (const extension of [".css", ".js"]) {
        const path = await firstAsset(output, join(output, "_next/static"), extension);
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
    await rm(temporaryRoot, { recursive: true, force: true });
  }
}

await main();
