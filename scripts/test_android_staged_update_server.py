"""Tests for the closed staged Android update TLS server."""

from __future__ import annotations

import hashlib
import http.client
import importlib.util
import json
import ssl
import subprocess
import sys
import tempfile
import threading
import unittest
from pathlib import Path


SCRIPT_PATH = Path(__file__).with_name("android-staged-update-server.py")
SPEC = importlib.util.spec_from_file_location("android_staged_update_server", SCRIPT_PATH)
assert SPEC is not None and SPEC.loader is not None
server_module = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = server_module
SPEC.loader.exec_module(server_module)


class StagedUpdateServerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.apk = self.root / "openaria-echo-mobile-v0.1.7-android-signed.apk"
        self.apk.write_bytes(b"exact signed candidate bytes")
        self.apk_sha256 = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        self.repository = "Alpenl/openaria-echo-mobile"
        self.release_tag = "v0.1.7"
        self.apk_path = f"/{self.repository}/releases/download/{self.release_tag}/{self.apk.name}"
        self.manifest_path = f"/{self.repository}/releases/latest/download/android-update.json"
        self.manifest = self.root / "android-update.json"
        self.manifest.write_text(
            json.dumps(
                {
                    "android": {
                        "apk": {
                            "url": f"https://github.com{self.apk_path}",
                            "sha256": self.apk_sha256,
                            "bytes": self.apk.stat().st_size,
                        }
                    }
                }
            ),
            encoding="utf-8",
        )
        self.cert = self.root / "server.crt"
        self.key = self.root / "server.key"
        subprocess.run(
            [
                "openssl",
                "req",
                "-x509",
                "-newkey",
                "rsa:2048",
                "-nodes",
                "-days",
                "1",
                "-subj",
                "/CN=localhost",
                "-keyout",
                str(self.key),
                "-out",
                str(self.cert),
            ],
            check=True,
            capture_output=True,
        )
        self.request_log = self.root / "requests.jsonl"
        assets = server_module.load_assets(
            self.manifest,
            self.apk,
            self.repository,
            self.release_tag,
        )
        self.server = server_module.create_server(
            "127.0.0.1",
            0,
            self.cert,
            self.key,
            assets,
            self.request_log,
        )
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        context = ssl._create_unverified_context()  # noqa: SLF001 - loopback test certificate.
        self.connection = http.client.HTTPSConnection(
            "127.0.0.1",
            self.server.server_port,
            context=context,
            timeout=5,
        )

    def request(self, method: str, path: str, *, host: str = "github.com") -> http.client.HTTPResponse:
        self.connection.request(method, path, headers={"Host": host})
        return self.connection.getresponse()

    def tearDown(self) -> None:
        self.connection.close()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
        self.temporary.cleanup()

    def test_serves_only_exact_manifest_and_candidate_paths_with_request_evidence(self) -> None:
        manifest_response = self.request("GET", self.manifest_path)
        self.assertEqual(200, manifest_response.status)
        self.assertEqual(self.manifest.read_bytes(), manifest_response.read())

        head_response = self.request("HEAD", self.apk_path)
        self.assertEqual(200, head_response.status)
        self.assertEqual(str(self.apk.stat().st_size), head_response.getheader("Content-Length"))
        self.assertEqual(b"", head_response.read())

        apk_response = self.request("GET", self.apk_path)
        self.assertEqual(200, apk_response.status)
        self.assertEqual(self.apk.read_bytes(), apk_response.read())

        rejected = self.request("GET", f"{self.apk_path}?untrusted=1")
        self.assertEqual(404, rejected.status)
        rejected.read()

        wrong_host = self.request("GET", self.manifest_path, host="example.invalid")
        self.assertEqual(421, wrong_host.status)
        wrong_host.read()

        events = [json.loads(line) for line in self.request_log.read_text().splitlines()]
        self.assertEqual(["GET", "HEAD", "GET", "GET", "GET"], [event["method"] for event in events])
        self.assertEqual([200, 200, 200, 404, 421], [event["status"] for event in events])
        self.assertEqual("github.com", events[0]["host"])
        self.assertEqual("example.invalid", events[-1]["host"])
        self.assertEqual(self.apk_sha256, events[1]["sha256"])
        self.assertEqual(self.apk_sha256, events[2]["sha256"])

    def test_rejects_manifest_that_does_not_bind_exact_candidate_bytes(self) -> None:
        manifest = json.loads(self.manifest.read_text(encoding="utf-8"))
        manifest["android"]["apk"]["sha256"] = "0" * 64
        self.manifest.write_text(json.dumps(manifest), encoding="utf-8")

        with self.assertRaisesRegex(ValueError, "bytes do not match"):
            server_module.load_assets(
                self.manifest,
                self.apk,
                self.repository,
                self.release_tag,
            )


if __name__ == "__main__":
    unittest.main()
