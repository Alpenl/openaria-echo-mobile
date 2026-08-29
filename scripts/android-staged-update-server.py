"""Serve one staged Android update manifest and APK over a closed TLS endpoint."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import ssl
import threading
from dataclasses import dataclass
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


@dataclass(frozen=True)
class StagedAsset:
    path: str
    source: Path
    content_type: str
    sha256: str
    bytes: int


def load_assets(
    manifest_path: Path,
    apk_path: Path,
    repository: str,
    release_tag: str,
) -> dict[str, StagedAsset]:
    manifest_bytes = manifest_path.read_bytes()
    manifest = json.loads(manifest_bytes)
    apk = manifest["android"]["apk"]
    apk_url = urlparse(apk["url"])
    expected_apk_path = f"/{repository}/releases/download/{release_tag}/{apk_path.name}"
    if (
        apk_url.scheme != "https"
        or apk_url.hostname != "github.com"
        or apk_url.path != expected_apk_path
        or apk_url.query
        or apk_url.fragment
    ):
        raise ValueError("candidate APK URL is not the exact staged GitHub Release path")
    actual_apk_sha256 = sha256(apk_path)
    actual_apk_bytes = apk_path.stat().st_size
    if apk["sha256"] != actual_apk_sha256 or apk["bytes"] != actual_apk_bytes:
        raise ValueError("candidate APK bytes do not match the staged update manifest")

    manifest_url_path = f"/{repository}/releases/latest/download/android-update.json"
    return {
        manifest_url_path: StagedAsset(
            path=manifest_url_path,
            source=manifest_path,
            content_type="application/json; charset=utf-8",
            sha256=hashlib.sha256(manifest_bytes).hexdigest(),
            bytes=len(manifest_bytes),
        ),
        expected_apk_path: StagedAsset(
            path=expected_apk_path,
            source=apk_path,
            content_type="application/vnd.android.package-archive",
            sha256=actual_apk_sha256,
            bytes=actual_apk_bytes,
        ),
    }


class StagedUpdateServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(
        self,
        server_address: tuple[str, int],
        assets: dict[str, StagedAsset],
        request_log: Path,
    ) -> None:
        super().__init__(server_address, StagedUpdateHandler)
        self.assets = assets
        self.request_log = request_log
        self.log_lock = threading.Lock()

    def record(
        self,
        method: str,
        host: str,
        path: str,
        status: int,
        asset: StagedAsset | None,
    ) -> None:
        event = {
            "at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "method": method,
            "host": host,
            "path": path,
            "status": status,
            "sha256": asset.sha256 if asset is not None else None,
            "bytes": asset.bytes if asset is not None else None,
        }
        encoded = json.dumps(event, sort_keys=True) + "\n"
        with self.log_lock, self.request_log.open("a", encoding="utf-8") as output:
            output.write(encoded)
            output.flush()
            os.fsync(output.fileno())


class StagedUpdateHandler(BaseHTTPRequestHandler):
    server: StagedUpdateServer
    protocol_version = "HTTP/1.1"
    server_version = "OpenAriaStagedUpdate/1"

    def do_HEAD(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API.
        self._serve(include_body=False)

    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API.
        self._serve(include_body=True)

    def _serve(self, *, include_body: bool) -> None:
        host = self.headers.get("Host", "")
        if host not in {"github.com", "github.com:443"}:
            self.server.record(self.command, host, self.path, 421, None)
            self.send_error(421)
            return
        asset = self.server.assets.get(self.path)
        if asset is None:
            self.server.record(self.command, host, self.path, 404, None)
            self.send_error(404)
            return
        self.server.record(self.command, host, self.path, 200, asset)
        self.send_response(200)
        self.send_header("Content-Type", asset.content_type)
        self.send_header("Content-Length", str(asset.bytes))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        if include_body:
            with asset.source.open("rb") as stream:
                while chunk := stream.read(64 * 1024):
                    self.wfile.write(chunk)

    def log_message(self, _format: str, *_args: object) -> None:
        return


def create_server(
    bind: str,
    port: int,
    cert: Path,
    key: Path,
    assets: dict[str, StagedAsset],
    request_log: Path,
) -> StagedUpdateServer:
    server = StagedUpdateServer((bind, port), assets, request_log)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(certfile=cert, keyfile=key)
    server.socket = context.wrap_socket(server.socket, server_side=True)
    return server


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bind", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=443)
    parser.add_argument("--cert", type=Path, required=True)
    parser.add_argument("--key", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--release-tag", required=True)
    parser.add_argument("--request-log", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    assets = load_assets(args.manifest, args.apk, args.repository, args.release_tag)
    args.request_log.parent.mkdir(parents=True, exist_ok=True)
    args.request_log.touch(mode=0o644, exist_ok=True)
    server = create_server(args.bind, args.port, args.cert, args.key, assets, args.request_log)
    server.serve_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
