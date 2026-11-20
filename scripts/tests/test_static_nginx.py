"""Run the Phase B static Nginx route rules against disposable exported files."""

import shutil
import socket
import subprocess
import tempfile
import time
import unittest
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import urlopen


ROOT = Path(__file__).resolve().parents[2]
NGINX_CONFIG = ROOT / "src/frontend/nginx/static.conf"


@unittest.skipUnless(shutil.which("nginx"), "Nginx not available locally")
class StaticNginxTests(unittest.TestCase):
    def test_routes_assets_and_missing_page(self):
        with tempfile.TemporaryDirectory(prefix="sharewise-nginx-") as directory:
            temporary = Path(directory)
            # The local nginx master can use an unprivileged worker account.
            temporary.chmod(0o755)
            site = temporary / "site"
            (site / "app").mkdir(parents=True)
            (site / "_next/static").mkdir(parents=True)
            (site / "index.html").write_text("Landing")
            (site / "app.html").write_text("Workspace")
            (site / "app/nested.html").write_text("Nested")
            (site / "_next/static/example.js").write_text("const page = 1;")

            with socket.socket() as listener:
                listener.bind(("127.0.0.1", 0))
                port = listener.getsockname()[1]

            config = NGINX_CONFIG.read_text().replace(
                "listen 80;", f"listen 127.0.0.1:{port};"
            ).replace("/usr/share/nginx/html", str(site))
            (temporary / "static.conf").write_text(config)
            (temporary / "nginx.conf").write_text(
                f"worker_processes 1;\npid {temporary / 'nginx.pid'};\n"
                f"error_log {temporary / 'error.log'} info;\n"
                "events { worker_connections 128; }\n"
                f"http {{ include /etc/nginx/mime.types; include {temporary / 'static.conf'}; }}\n"
            )
            command = ["nginx", "-c", str(temporary / "nginx.conf")]
            subprocess.run(command + ["-t"], check=True, capture_output=True)
            process = subprocess.Popen(command + ["-g", "daemon off;"],
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            try:
                base = f"http://127.0.0.1:{port}"
                for _ in range(30):
                    if process.poll() is not None:
                        self.fail(f"Nginx exited with status {process.returncode}")
                    try:
                        with urlopen(base + "/", timeout=1) as response:
                            self.assertEqual(response.read(), b"Landing")
                        break
                    except (URLError, TimeoutError):
                        time.sleep(0.1)
                else:
                    self.fail("Nginx did not start")

                for route, body in [
                    ("/app", b"Workspace"),
                    ("/app/nested", b"Nested"),
                    ("/_next/static/example.js", b"const page = 1;"),
                ]:
                    with self.subTest(route=route):
                        with urlopen(base + route, timeout=2) as response:
                            self.assertEqual(response.status, 200)
                            self.assertEqual(response.read(), body)
                with self.assertRaises(HTTPError) as missing:
                    urlopen(base + "/unknown", timeout=2)
                self.assertEqual(missing.exception.code, 404)
            finally:
                process.terminate()
                process.wait(timeout=5)


if __name__ == "__main__":
    unittest.main()
