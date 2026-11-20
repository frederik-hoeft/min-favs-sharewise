"""Integration checks for the Phase C Nginx edge using a disposable HTTP backend.

Requires local nginx, openssl and envsubst; no Docker, PostgreSQL or Node needed.
"""

import http.client
import json
import os
import shutil
import socket
import ssl
import subprocess
import tempfile
import threading
import time
import unittest
from contextlib import ExitStack
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
NGINX_TEMPLATE = ROOT / "src/frontend/nginx/production.conf.template"
REQUIRED_TOOLS = ("nginx", "openssl", "envsubst")


class EchoBackend(BaseHTTPRequestHandler):
    def handle_request(self):
        size = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(size).decode() if size else ""
        response = json.dumps({
            "method": self.command,
            "path": self.path,
            "headers": dict(self.headers),
            "body": body,
        }).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(response)))
        self.send_header("Cache-Control", "public, max-age=600")
        self.send_header("Set-Cookie", "sharewise_test=alive; HttpOnly; Secure; SameSite=Lax")
        self.end_headers()
        self.wfile.write(response)

    do_GET = do_POST = handle_request

    def log_message(self, format, *args):
        pass


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


@unittest.skipUnless(all(shutil.which(tool) for tool in REQUIRED_TOOLS),
                     "Nginx/OpenSSL/envsubst not available locally")
class ProductionNginxTests(unittest.TestCase):
    def setUp(self):
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.directory = Path(self.stack.enter_context(tempfile.TemporaryDirectory(prefix="sharewise-nginx-")))
        # The local Nginx worker may drop from root to www-data.
        self.directory.chmod(0o755)
        self.http_port = free_port()
        self.https_port = free_port()
        self.health_port = free_port()
        self.backend = ThreadingHTTPServer(("127.0.0.1", 0), EchoBackend)
        self.stack.callback(self.backend.server_close)
        self.backend_thread = threading.Thread(target=self.backend.serve_forever, daemon=True)
        self.backend_thread.start()
        self.stack.callback(self.backend.shutdown)
        self._prepare_static_site()
        self._prepare_tls()
        self._prepare_nginx()
        self.process = subprocess.Popen(
            ["nginx", "-c", str(self.directory / "nginx.conf"), "-g", "daemon off;"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
        )
        self.stack.callback(self._stop_nginx)
        for _ in range(50):
            if self.process.poll() is not None:
                self.fail(f"Nginx exited: {self.process.stderr.read().decode()}")
            try:
                status, _, _ = self.request("GET", "/", tls=False)
                if status == 308:
                    break
            except OSError:
                time.sleep(0.1)
        else:
            self.fail("Nginx did not start")

    def _prepare_static_site(self):
        self.site = self.directory / "site"
        (self.site / "app").mkdir(parents=True)
        (self.site / "_next/static").mkdir(parents=True)
        (self.site / "index.html").write_text("Landing")
        (self.site / "app.html").write_text("Workspace")
        (self.site / "app/nested.html").write_text("Nested")
        (self.site / "_next/static/example.js").write_text("const page = 1;")
        (self.site / ".env").write_text("hidden")
        (self.site / "app/.secret").write_text("hidden")

    def _prepare_tls(self):
        self.cert = self.directory / "fullchain.pem"
        self.key = self.directory / "privkey.pem"
        subprocess.run([
            "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
            "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost",
            "-keyout", str(self.key), "-out", str(self.cert),
        ], capture_output=True, check=True)
        self.key.chmod(0o600)

    def _prepare_nginx(self):
        env = {**os.environ,
               "SHAREWISE_PUBLIC_HOST": "localhost",
               "SHAREWISE_HTTPS_PORT": str(self.https_port),
               "SHAREWISE_TLS_PORT_SUFFIX": f":{self.https_port}"}
        config = subprocess.run(
            ["envsubst", "${SHAREWISE_PUBLIC_HOST} ${SHAREWISE_HTTPS_PORT} ${SHAREWISE_TLS_PORT_SUFFIX}"],
            input=NGINX_TEMPLATE.read_text(), text=True, capture_output=True, env=env, check=True,
        ).stdout
        config = (config
            .replace("listen 80 default_server;", f"listen 127.0.0.1:{self.http_port} default_server;")
            .replace("listen 80;", f"listen 127.0.0.1:{self.http_port};")
            .replace("listen 443 ssl default_server;", f"listen 127.0.0.1:{self.https_port} ssl default_server;")
            .replace("listen 443 ssl;", f"listen 127.0.0.1:{self.https_port} ssl;")
            .replace("listen 127.0.0.1:8081;", f"listen 127.0.0.1:{self.health_port};")
            .replace("backend:8080", f"127.0.0.1:{self.backend.server_port}")
            .replace("/usr/share/nginx/html", str(self.site))
            .replace("/etc/nginx/tls/fullchain.pem", str(self.cert))
            .replace("/etc/nginx/tls/privkey.pem", str(self.key)))
        (self.directory / "production.conf").write_text(config)
        (self.directory / "nginx.conf").write_text(
            f"worker_processes 1;\npid {self.directory / 'nginx.pid'};\n"
            f"error_log {self.directory / 'error.log'} info;\n"
            "events { worker_connections 128; }\n"
            f"http {{ include /etc/nginx/mime.types; include {self.directory / 'production.conf'}; }}\n"
        )
        subprocess.run(["nginx", "-t", "-c", str(self.directory / "nginx.conf")],
                       capture_output=True, check=True)

    def _stop_nginx(self):
        if self.process.poll() is None:
            self.process.terminate()
        try:
            self.process.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.communicate()

    def request(self, method, path, *, tls=True, headers=None, body=None, sni_host="localhost"):
        context = ssl._create_unverified_context()
        if tls:
            connection = http.client.HTTPSConnection(sni_host, self.https_port, timeout=3, context=context)
        else:
            connection = http.client.HTTPConnection(sni_host, self.http_port, timeout=3)
        try:
            connection.request(method, path, headers=headers or {}, body=body)
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            connection.close()

    def test_static_routes_cache_and_dotfiles(self):
        for route, expected in [("/", b"Landing"), ("/app", b"Workspace"),
                                ("/app/nested", b"Nested")]:
            with self.subTest(route=route):
                status, headers, body = self.request("GET", route)
                self.assertEqual((status, body), (200, expected))
                self.assertEqual(headers["Cache-Control"], "no-store")
        status, headers, body = self.request("GET", "/_next/static/example.js")
        self.assertEqual((status, body), (200, b"const page = 1;"))
        self.assertEqual(headers["Cache-Control"], "public, max-age=31536000, immutable")
        for path in ("/unknown", "/app/not-present", "/.env", "/app/.secret",
                     "/_next/static/.hidden"):
            with self.subTest(path=path):
                self.assertEqual(self.request("GET", path)[0], 404)

    def test_tls_host_validation_and_http_redirect(self):
        status, headers, _ = self.request("GET", "/app/nested?x=1", tls=False)
        self.assertEqual(status, 308)
        self.assertEqual(headers["Location"], f"https://localhost:{self.https_port}/app/nested?x=1")
        self.assertEqual(self.request("GET", "/", tls=False,
                                      headers={"Host": "attacker.invalid"})[0], 421)
        self.assertEqual(self.request("GET", "/", headers={"Host": "attacker.invalid"})[0], 421)
        self.assertEqual(self.request("GET", "/", headers={"Host": "LOCALHOST"})[0], 200)
        with self.assertRaises((ssl.SSLError, OSError, http.client.HTTPException)):
            self.request("GET", "/", sni_host="127.0.0.1", headers={"Host": "localhost"})

    def test_api_proxy_forwards_request_and_sanitizes_client_metadata(self):
        forged = {
            "Authorization": "Bearer example-token", "Cookie": "session=example",
            "Forwarded": "for=203.0.113.99;proto=http;host=attacker.invalid",
            "X-Forwarded-For": "203.0.113.99", "X-Forwarded-Proto": "http",
            "X-Forwarded-Host": "attacker.invalid", "X-Forwarded-Port": "81",
            "X-Forwarded-Prefix": "/wrong", "X-Forwarded-Ssl": "on",
            "X-Original-URL": "/admin", "X-Rewrite-URL": "/admin",
            "X-Forwarded-Protocol": "http", "X-Forwarded-By": "attacker.invalid",
            "Content-Type": "application/json",
        }
        status, headers, body = self.request(
            "POST", "/api/system/v1/health?from=%2Fapi&n=2", headers=forged, body=b'{"ok":true}'
        )
        self.assertEqual(status, 200)
        self.assertEqual(headers["Cache-Control"], "no-store")
        self.assertEqual(headers["Set-Cookie"], "sharewise_test=alive; HttpOnly; Secure; SameSite=Lax")
        payload = json.loads(body)
        self.assertEqual(payload["method"], "POST")
        self.assertEqual(payload["path"], "/api/system/v1/health?from=%2Fapi&n=2")
        self.assertEqual(payload["body"], '{"ok":true}')
        observed = {name.lower(): value for name, value in payload["headers"].items()}
        self.assertEqual(observed["authorization"], "Bearer example-token")
        self.assertEqual(observed["cookie"], "session=example")
        self.assertEqual(observed["host"], f"localhost:{self.https_port}")
        self.assertEqual(observed["x-forwarded-host"], f"localhost:{self.https_port}")
        self.assertEqual(observed["x-forwarded-port"], str(self.https_port))
        self.assertEqual(observed["x-forwarded-proto"], "https")
        self.assertEqual(observed["x-forwarded-for"], "127.0.0.1")
        for removed in ("forwarded", "x-forwarded-prefix", "x-forwarded-ssl", "x-original-url",
                        "x-rewrite-url", "x-forwarded-scheme", "x-forwarded-server",
                        "x-forwarded-by", "x-forwarded-protocol"):
            self.assertNotIn(removed, observed)

    def test_api_unavailable_returns_gateway_error_not_static_fallback(self):
        self.backend.shutdown()
        self.backend.server_close()
        status, headers, _ = self.request("GET", "/api/system/v1/health")
        self.assertEqual(status, 502)
        self.assertEqual(headers["Cache-Control"], "no-store")
        self.assertEqual(self.request("GET", "/")[0], 200)

    def test_local_health_endpoint_is_not_public(self):
        status, _, body = self.request("GET", "/health")
        self.assertEqual(status, 404)
        connection = http.client.HTTPConnection("127.0.0.1", self.health_port, timeout=3)
        try:
            connection.request("GET", "/health")
            response = connection.getresponse()
            self.assertEqual(response.status, 200)
            self.assertEqual(response.read(), b"ok\n")
        finally:
            connection.close()



class NginxStartupConfigurationTests(unittest.TestCase):
    """Fail-closed startup inputs must not enter the template-rendering step."""

    SCRIPT = ROOT / "src/frontend/nginx/15-configure-sharewise.sh"

    def test_invalid_host_and_ports_are_rejected(self):
        for host, port in [("bad;host", "443"), ("foo..example", "443"),
                           ("example.test", "0"), ("example.test", "65536"),
                           ("example.test", "not-a-port")]:
            with self.subTest(host=host, port=port):
                env = {**os.environ, "SHAREWISE_PUBLIC_HOST": host,
                       "SHAREWISE_HTTPS_PORT": port}
                completed = subprocess.run(["sh", str(self.SCRIPT)], env=env,
                                           text=True, capture_output=True)
                self.assertNotEqual(completed.returncode, 0)
                self.assertIn("ShareWise Nginx configuration:", completed.stderr)

    def test_missing_public_host_is_rejected(self):
        env = {key: value for key, value in os.environ.items()
               if key != "SHAREWISE_PUBLIC_HOST"}
        completed = subprocess.run(["sh", str(self.SCRIPT)], env=env,
                                   text=True, capture_output=True)
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("SHAREWISE_PUBLIC_HOST", completed.stderr)


if __name__ == "__main__":
    unittest.main()
