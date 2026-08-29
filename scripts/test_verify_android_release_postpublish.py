from __future__ import annotations

import copy
import hashlib
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import warnings
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import verify_android_release_postpublish as verifier
from verify_android_release_postpublish import (
    AAB_EVIDENCE_SCHEMA,
    PACKAGE_NAME,
    VerificationFailure,
    download_anonymous_asset,
    validate_final_recheck,
    validate_release_state,
    validate_source_evidence,
    verify_aab_signature,
    verify_downloaded_release,
)

REPOSITORY = "Alpenl/openaria-echo-mobile"
RUN_ID = "33253867763"
RUN_ATTEMPT = "1"
TAG = "v0.1.7"
COMMIT = "83147d60a6c41395a7cec2d5b5586a9694090c37"
RELEASE_ID = 378992098
CERTIFICATE = "a" * 64
FIXTURE = (
    Path(__file__).resolve().parent
    / "fixtures"
    / "android-release-v0.1.7-postpublish.json"
)


def sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def mismatch_fields(error: VerificationFailure) -> set[str]:
    return {str(item["field"]) for item in error.mismatches}


class AnonymousDownloadHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    exact_body = b"abcd"

    def log_message(self, format: str, *args: object) -> None:
        pass

    def do_GET(self) -> None:
        self.close_connection = True
        if self.path == "/redirect":
            self.send_response(302)
            self.send_header("Location", "/exact?sig=temporary-secret")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if self.path.startswith("/exact"):
            self.send_response(200)
            self.send_header("Content-Length", str(len(self.exact_body)))
            self.end_headers()
            self.wfile.write(self.exact_body)
            return
        if self.path == "/oversized-content-length":
            body = self.exact_body + b"e"
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            try:
                self.wfile.write(body)
            except BrokenPipeError:
                pass
            return
        if self.path == "/slow-declared":
            self.send_response(200)
            self.send_header("Content-Length", str(len(self.exact_body)))
            self.end_headers()
            try:
                for value in self.exact_body:
                    time.sleep(0.02)
                    self.wfile.write(bytes([value]))
                    self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                pass
            return
        if self.path == "/slow-headers":
            wire_response = (
                b"HTTP/1.1 200 OK\r\nContent-Length: 4\r\n"
                b"Content-Type: application/octet-stream\r\n\r\nabcd"
            )
            try:
                for value in wire_response:
                    time.sleep(0.02)
                    self.wfile.write(bytes([value]))
                    self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                pass
            return
        if self.path in (
            "/oversized-chunked",
            "/stalled-chunked",
            "/slow-chunked",
        ):
            self.send_response(200)
            self.send_header("Transfer-Encoding", "chunked")
            self.end_headers()
            chunks = [self.exact_body]
            if self.path == "/oversized-chunked":
                chunks.append(b"e")
            if self.path == "/slow-chunked":
                chunks = [bytes([value]) for value in self.exact_body]
            try:
                for chunk in chunks:
                    if self.path == "/slow-chunked":
                        time.sleep(0.02)
                    self.wfile.write(f"{len(chunk):X}\r\n".encode())
                    self.wfile.write(chunk + b"\r\n")
                    self.wfile.flush()
                if self.path == "/stalled-chunked":
                    time.sleep(0.2)
                self.wfile.write(b"0\r\n\r\n")
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                pass
            return
        self.send_error(404)


class BoundedAnonymousDownloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), AnonymousDownloadHandler)
        cls.server.daemon_threads = True
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base_url = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls) -> None:
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=2)

    def download(
        self,
        root: Path,
        route: str,
        *,
        connect_timeout: float = 0.5,
        body_timeout: float = 0.5,
        total_timeout: float = 2,
    ) -> dict:
        return download_anonymous_asset(
            asset_name="asset.bin",
            url=f"{self.base_url}{route}",
            partial_output=root / "asset.bin.partial",
            expected_size=len(AnonymousDownloadHandler.exact_body),
            expected_digest=f"sha256:{sha256(AnonymousDownloadHandler.exact_body)}",
            connect_timeout_seconds=connect_timeout,
            body_timeout_seconds=body_timeout,
            total_timeout_seconds=total_timeout,
            allow_http_for_tests=True,
        )

    def test_exact_anonymous_stream_is_bounded_and_kept_as_partial(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            report = self.download(root, "/exact")

            self.assertEqual(
                AnonymousDownloadHandler.exact_body,
                (root / "asset.bin.partial").read_bytes(),
            )
            self.assertEqual(4, report["limits"]["hard_max_bytes"])
            self.assertTrue(report["partial_verified_before_publish"])
            self.assertNotIn("Authorization", report["request_headers"])
            self.assertTrue(report["worker"]["isolated_process"])
            self.assertTrue(report["worker"]["reaped"])
            self.assertIn("DNS", report["limits"]["total_timeout_scope"])

    def test_oversized_content_length_is_rejected_before_body_write(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            with self.assertRaises(VerificationFailure) as raised:
                self.download(root, "/oversized-content-length")

            self.assertFalse((root / "asset.bin.partial").exists())
        self.assertIn(
            "anonymous_downloads[asset.bin].content_length",
            mismatch_fields(raised.exception),
        )

    def test_redirect_audit_redacts_temporary_signed_query(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            report = self.download(Path(temporary_directory), "/redirect")

        serialized = json.dumps(report, sort_keys=True)
        self.assertNotIn("temporary-secret", serialized)
        self.assertNotIn("?", report["response"]["final_url"])
        self.assertTrue(report["response"]["signed_query_parameters_redacted"])

    def test_chunked_response_cannot_cross_expected_byte_ceiling(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            with self.assertRaises(VerificationFailure) as raised:
                self.download(root, "/oversized-chunked")

            self.assertFalse((root / "asset.bin.partial").exists())
        self.assertIn(
            "anonymous_downloads[asset.bin].max_bytes",
            mismatch_fields(raised.exception),
        )

    def test_stalled_chunked_body_has_independent_deadline(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            with self.assertRaises(VerificationFailure) as raised:
                self.download(root, "/stalled-chunked", body_timeout=0.05)

            self.assertFalse((root / "asset.bin.partial").exists())
        self.assertIn(
            "anonymous_downloads[asset.bin].body_timeout_seconds",
            mismatch_fields(raised.exception),
        )

    def test_slow_declared_body_cannot_cross_total_wall_clock(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            started = time.monotonic()
            with self.assertRaises(VerificationFailure) as raised:
                self.download(
                    root,
                    "/slow-declared",
                    connect_timeout=0.04,
                    body_timeout=0.04,
                    total_timeout=0.06,
                )
            elapsed = time.monotonic() - started

            self.assertFalse((root / "asset.bin.partial").exists())
        self.assertLess(elapsed, 0.16)
        self.assertIn(
            "anonymous_downloads[asset.bin].total_timeout_seconds",
            mismatch_fields(raised.exception),
        )

    def test_slow_chunked_body_cannot_cross_total_wall_clock(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            started = time.monotonic()
            with self.assertRaises(VerificationFailure) as raised:
                self.download(
                    root,
                    "/slow-chunked",
                    connect_timeout=0.05,
                    body_timeout=0.05,
                    total_timeout=0.07,
                )
            elapsed = time.monotonic() - started

            self.assertFalse((root / "asset.bin.partial").exists())
        self.assertLess(elapsed, 0.18)
        self.assertIn(
            "anonymous_downloads[asset.bin].total_timeout_seconds",
            mismatch_fields(raised.exception),
        )

    def test_slow_headers_are_killed_and_worker_is_reaped(self) -> None:
        created_processes: list[subprocess.Popen[str]] = []
        real_popen = subprocess.Popen

        def capture_process(*args: object, **kwargs: object) -> subprocess.Popen[str]:
            process = real_popen(*args, **kwargs)
            created_processes.append(process)
            return process

        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            started = time.monotonic()
            with (
                mock.patch.object(
                    verifier.subprocess, "Popen", side_effect=capture_process
                ),
                self.assertRaises(VerificationFailure) as raised,
            ):
                self.download(
                    root,
                    "/slow-headers",
                    connect_timeout=0.05,
                    body_timeout=0.05,
                    total_timeout=0.07,
                )
            elapsed = time.monotonic() - started

            self.assertFalse((root / "asset.bin.partial").exists())
        self.assertLess(elapsed, 0.18)
        self.assertEqual(1, len(created_processes))
        self.assertIsNotNone(created_processes[0].poll())
        self.assertIn(
            "anonymous_downloads[asset.bin].total_timeout_seconds",
            mismatch_fields(raised.exception),
        )


class AndroidReleasePostPublishVerifierTest(unittest.TestCase):
    def setUp(self) -> None:
        self.asset_values = {
            "SHA256SUMS.txt": (312, "1" * 64),
            "android-update.json": (875, "2" * 64),
            f"openaria-echo-mobile-{TAG}-android-signed.aab": (6913203, "3" * 64),
            f"openaria-echo-mobile-{TAG}-android-signed.apk": (7279144, "4" * 64),
        }
        self.ownership = self._ownership(self.asset_values)
        self.published = self._release(self.asset_values)
        self.latest = copy.deepcopy(self.published)
        self.by_tag = copy.deepcopy(self.published)
        self.tag_ref = {"object": {"type": "commit", "sha": COMMIT}}
        self.fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))

    @staticmethod
    def _ownership(asset_values: dict[str, tuple[int, str]]) -> dict[str, object]:
        return {
            "schema": "openaria.mobile.release-ownership.v1",
            "repository": REPOSITORY,
            "run_id": RUN_ID,
            "run_attempt": RUN_ATTEMPT,
            "draft_never_public": True,
            "draft_created_by_run": True,
            "target": {
                "tag": TAG,
                "source_commit": COMMIT,
                "release_id": RELEASE_ID,
                "assets": [
                    {
                        "name": name,
                        "size": size,
                        "digest": f"sha256:{digest}",
                        "browser_download_url": (
                            f"https://github.com/{REPOSITORY}/releases/download/"
                            f"untagged-34bcda81f176314000da/{name}"
                        ),
                    }
                    for name, (size, digest) in sorted(asset_values.items())
                ],
            },
        }

    @staticmethod
    def _release(asset_values: dict[str, tuple[int, str]]) -> dict[str, object]:
        return {
            "id": RELEASE_ID,
            "tag_name": TAG,
            "draft": False,
            "prerelease": False,
            "immutable": True,
            "published_at": "2026-08-29T13:17:54Z",
            "target_commitish": COMMIT,
            "assets": [
                {
                    "name": name,
                    "size": size,
                    "digest": f"sha256:{digest}",
                    "browser_download_url": (
                        f"https://github.com/{REPOSITORY}/releases/download/{TAG}/{name}"
                    ),
                }
                for name, (size, digest) in sorted(asset_values.items())
            ],
        }

    def validate(self, **overrides: object) -> dict[str, object]:
        arguments: dict[str, object] = {
            "ownership": self.ownership,
            "latest_release": self.latest,
            "published_release": self.published,
            "published_by_tag": self.by_tag,
            "tag_ref": self.tag_ref,
            "repository": REPOSITORY,
            "source_run_id": RUN_ID,
            "source_run_attempt": RUN_ATTEMPT,
            "tag": TAG,
            "commit": COMMIT,
        }
        arguments.update(overrides)
        return validate_release_state(**arguments)  # type: ignore[arg-type]

    def fixture_state(self) -> dict[str, object]:
        release = self.fixture["published_release"]
        return validate_release_state(
            ownership=self.fixture["ownership"],
            latest_release=release,
            published_release=release,
            published_by_tag=release,
            tag_ref=self.fixture["tag_ref"],
            repository=REPOSITORY,
            source_run_id=RUN_ID,
            source_run_attempt=RUN_ATTEMPT,
            tag=TAG,
            commit=COMMIT,
        )

    def validate_fixture_source(self, **overrides: object) -> None:
        arguments: dict[str, object] = {
            "state": self.fixture_state(),
            "ownership": self.fixture["ownership"],
            "repository_metadata": self.fixture["repository"],
            "source_run": self.fixture["source_run"],
            "source_jobs": self.fixture["source_jobs"],
            "ownership_artifact": self.fixture["ownership_artifact"],
            "default_branch": "main",
        }
        arguments.update(overrides)
        validate_source_evidence(**arguments)  # type: ignore[arg-type]

    def test_draft_urls_change_but_content_identity_and_public_urls_pass(self) -> None:
        state = self.validate()

        self.assertEqual(RELEASE_ID, state["target"]["release_id"])
        self.assertEqual(4, len(state["target"]["assets"]))
        self.assertTrue(
            all(
                f"/releases/download/{TAG}/" in asset["browser_download_url"]
                for asset in state["target"]["assets"]
            )
        )

    def test_wrong_source_run_is_rejected(self) -> None:
        with self.assertRaises(VerificationFailure) as raised:
            self.validate(source_run_id="33253867764")

        self.assertIn("ownership.run_id", mismatch_fields(raised.exception))

    def test_wrong_tag_is_rejected(self) -> None:
        with self.assertRaises(VerificationFailure) as raised:
            self.validate(tag="v0.1.8")

        fields = mismatch_fields(raised.exception)
        self.assertIn("ownership.target.tag", fields)
        self.assertIn("published.tag_name", fields)

    def test_tampered_receipt_release_id_is_rejected(self) -> None:
        tampered = copy.deepcopy(self.ownership)
        tampered["target"]["release_id"] = RELEASE_ID + 1

        with self.assertRaises(VerificationFailure) as raised:
            self.validate(ownership=tampered)

        self.assertIn("published.id", mismatch_fields(raised.exception))

    def test_tampered_receipt_digest_is_rejected(self) -> None:
        tampered = copy.deepcopy(self.ownership)
        tampered["target"]["assets"][0]["digest"] = "sha256:" + "f" * 64

        with self.assertRaises(VerificationFailure) as raised:
            self.validate(ownership=tampered)

        self.assertTrue(
            any(
                field.endswith(".digest") and field.startswith("published.assets")
                for field in mismatch_fields(raised.exception)
            )
        )

    def test_wrong_public_url_has_field_level_diagnostic(self) -> None:
        published = copy.deepcopy(self.published)
        published["assets"][0]["browser_download_url"] = (
            f"https://github.com/{REPOSITORY}/releases/download/"
            "untagged-stale/SHA256SUMS.txt"
        )

        with self.assertRaises(VerificationFailure) as raised:
            self.validate(published_release=published)

        self.assertIn(
            "published.assets[SHA256SUMS.txt].browser_download_url",
            mismatch_fields(raised.exception),
        )

    def test_real_sanitized_v017_api_receipt_and_job_chain_replays(self) -> None:
        state = self.fixture_state()
        self.validate_fixture_source(state=state)

        self.assertEqual(
            "openaria.mobile.release-post-publish-state.v2", state["schema"]
        )
        self.assertEqual("2026-08-29T13:17:54Z", state["target"]["published_at"])
        self.assertEqual(378992098, state["target"]["release_id"])

    def test_source_chain_rejects_missing_or_failed_success_step(self) -> None:
        jobs = copy.deepcopy(self.fixture["source_jobs"])
        publication = next(
            job for job in jobs["jobs"] if job["name"] == "Assemble release"
        )
        publish = next(
            step
            for step in publication["steps"]
            if step["name"] == "Publish the receipt-owned GitHub Release"
        )
        publish["conclusion"] = "failure"

        with self.assertRaises(VerificationFailure) as raised:
            self.validate_fixture_source(source_jobs=jobs)

        self.assertIn(
            "source_jobs[Assemble release].steps[Publish the receipt-owned GitHub Release].conclusion",
            mismatch_fields(raised.exception),
        )

    def test_source_chain_rejects_duplicate_jobs_and_wrong_step_order(self) -> None:
        jobs = copy.deepcopy(self.fixture["source_jobs"])
        jobs["jobs"].append(copy.deepcopy(jobs["jobs"][0]))
        publication = next(
            job for job in jobs["jobs"] if job["name"] == "Assemble release"
        )
        publication["steps"][-1]["number"] = 10

        with self.assertRaises(VerificationFailure) as raised:
            self.validate_fixture_source(source_jobs=jobs)

        fields = mismatch_fields(raised.exception)
        self.assertIn("source_jobs.duplicate_names", fields)
        self.assertIn("source_jobs[Assemble release].publication_step_order", fields)

    def test_source_chain_rejects_wrong_owner_actor_and_default_branch(self) -> None:
        source_run = copy.deepcopy(self.fixture["source_run"])
        source_run["triggering_actor"]["login"] = "not-the-owner"
        repository = copy.deepcopy(self.fixture["repository"])
        repository["default_branch"] = "release"

        with self.assertRaises(VerificationFailure) as raised:
            self.validate_fixture_source(
                source_run=source_run, repository_metadata=repository
            )

        fields = mismatch_fields(raised.exception)
        self.assertIn("source_run.triggering_actor.login", fields)
        self.assertIn("repository", fields)

    def test_receipt_preflight_rejects_wrong_head_tag_raw_hash_and_time(self) -> None:
        ownership = copy.deepcopy(self.fixture["ownership"])
        preflight = ownership["immutable_releases_preflight"]
        preflight["default_branch_head"] = "0" * 40
        preflight["release_tag"] = "v0.1.8"
        preflight["response_sha256"] = "0" * 64
        preflight["checked_at"] = "2026-08-29T12:00:00Z"

        with self.assertRaises(VerificationFailure) as raised:
            self.validate_fixture_source(ownership=ownership)

        fields = mismatch_fields(raised.exception)
        self.assertIn(
            "ownership.immutable_releases_preflight.default_branch_head", fields
        )
        self.assertIn("ownership.immutable_releases_preflight.release_tag", fields)
        self.assertIn("ownership.immutable_releases_preflight.response_sha256", fields)
        self.assertIn(
            "ownership.immutable_releases_preflight.dispatch_delay_seconds", fields
        )

    def test_publish_and_artifact_timestamps_must_be_inside_success_steps(self) -> None:
        state = copy.deepcopy(self.fixture_state())
        state["target"]["published_at"] = "2026-08-29T13:18:00Z"
        artifact = copy.deepcopy(self.fixture["ownership_artifact"])
        artifact["created_at"] = "2026-08-29T13:15:00Z"

        with self.assertRaises(VerificationFailure) as raised:
            self.validate_fixture_source(state=state, ownership_artifact=artifact)

        fields = mismatch_fields(raised.exception)
        self.assertIn("state.target.published_at", fields)
        self.assertIn("ownership_artifact.created_at", fields)

    def test_source_chain_rejects_expired_or_wrong_numeric_artifact(self) -> None:
        artifact = copy.deepcopy(self.fixture["ownership_artifact"])
        artifact["expired"] = True
        artifact["workflow_run"]["id"] += 1

        with self.assertRaises(VerificationFailure) as raised:
            self.validate_fixture_source(ownership_artifact=artifact)

        fields = mismatch_fields(raised.exception)
        self.assertIn("ownership_artifact.expired", fields)
        self.assertIn("ownership_artifact.workflow_run.id", fields)

    def test_final_recheck_rejects_latest_or_artifact_digest_drift(self) -> None:
        state = self.fixture_state()
        final_state = copy.deepcopy(state)
        final_state["target"]["latest"] = False
        final_artifact = copy.deepcopy(self.fixture["ownership_artifact"])
        final_artifact["digest"] = "sha256:" + "0" * 64

        with self.assertRaises(VerificationFailure) as raised:
            validate_final_recheck(
                initial_state=state,
                final_state=final_state,
                initial_repository=self.fixture["repository"],
                final_repository=self.fixture["repository"],
                initial_source_run=self.fixture["source_run"],
                final_source_run=self.fixture["source_run"],
                initial_source_jobs=self.fixture["source_jobs"],
                final_source_jobs=self.fixture["source_jobs"],
                initial_ownership_artifact=self.fixture["ownership_artifact"],
                final_ownership_artifact=final_artifact,
            )

        fields = mismatch_fields(raised.exception)
        self.assertIn("final_recheck.release_state", fields)
        self.assertIn("final_recheck.ownership_artifact", fields)

    def test_anonymous_assets_manifest_checksums_package_and_signers_are_exact(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            apk_name = f"openaria-echo-mobile-{TAG}-android-signed.apk"
            aab_name = f"openaria-echo-mobile-{TAG}-android-signed.aab"
            apk = b"signed apk fixture"
            aab = b"signed aab fixture"
            (root / apk_name).write_bytes(apk)
            (root / aab_name).write_bytes(aab)
            manifest = {
                "schema": "openaria.echo.mobile.android-update.v1",
                "version": "0.1.7",
                "versionCode": 10,
                "packageName": PACKAGE_NAME,
                "signingCertificateSha256": CERTIFICATE,
                "pubDate": "2026-08-29T12:46:40Z",
                "notes": "fixture",
                "android": {
                    "apk": {
                        "url": f"https://github.com/{REPOSITORY}/releases/download/{TAG}/{apk_name}",
                        "sha256": sha256(apk),
                        "bytes": len(apk),
                    },
                    "aab": {
                        "url": f"https://github.com/{REPOSITORY}/releases/download/{TAG}/{aab_name}",
                        "sha256": sha256(aab),
                        "bytes": len(aab),
                    },
                },
            }
            manifest_bytes = (
                json.dumps(manifest, separators=(",", ":")) + "\n"
            ).encode()
            (root / "android-update.json").write_bytes(manifest_bytes)
            checksums = (
                f"{sha256(manifest_bytes)}  android-update.json\n"
                f"{sha256(aab)}  {aab_name}\n"
                f"{sha256(apk)}  {apk_name}\n"
            ).encode()
            (root / "SHA256SUMS.txt").write_bytes(checksums)
            values = {
                "SHA256SUMS.txt": (len(checksums), sha256(checksums)),
                "android-update.json": (len(manifest_bytes), sha256(manifest_bytes)),
                aab_name: (len(aab), sha256(aab)),
                apk_name: (len(apk), sha256(apk)),
            }
            ownership = self._ownership(values)
            published = self._release(values)
            state = self.validate(
                ownership=ownership,
                latest_release=published,
                published_release=published,
                published_by_tag=published,
            )

            reports = root / "_reports"
            reports.mkdir()
            apk_signature = reports / "apksigner.txt"
            apk_package = reports / "apk-package.txt"
            apk_version_name = reports / "apk-version-name.txt"
            apk_version_code = reports / "apk-version-code.txt"
            apk_signature.write_text(
                f"Signer #1 certificate SHA-256 digest: {CERTIFICATE}\n",
                encoding="utf-8",
            )
            apk_package.write_text(f"{PACKAGE_NAME}\n", encoding="utf-8")
            apk_version_name.write_text("0.1.7\n", encoding="utf-8")
            apk_version_code.write_text("10\n", encoding="utf-8")
            aab_verification = {
                "schema": AAB_EVIDENCE_SCHEMA,
                "aab": {
                    "name": aab_name,
                    "size": len(aab),
                    "digest": f"sha256:{sha256(aab)}",
                },
                "archive": {
                    "container_preflight": {
                        "archive_bytes": len(aab),
                        "central_directory_bytes": 1,
                        "central_directory_offset": 0,
                        "declared_entry_count": 5,
                        "actual_entry_count": 5,
                        "eocd_offset": 1,
                        "eocd_comment_bytes": 0,
                        "zip64": False,
                        "zip64_eocd_bytes": 0,
                        "single_disk": True,
                        "unambiguous": True,
                        "checked_before_zipfile": True,
                        "limits": {
                            "archive_bytes": verifier.AAB_MAX_ARCHIVE_BYTES,
                            "central_directory_bytes": verifier.AAB_MAX_CENTRAL_DIRECTORY_BYTES,
                            "entry_count": verifier.AAB_MAX_ENTRY_COUNT,
                            "zip64_eocd_bytes": verifier.AAB_MAX_ZIP64_EOCD_BYTES,
                        },
                    },
                    "entry_count": 5,
                    "payload_entry_count": 2,
                    "signature_control_entries": [
                        "META-INF/FIXTURE.RSA",
                        "META-INF/FIXTURE.SF",
                        "META-INF/MANIFEST.MF",
                    ],
                    "jarsigner_ignored_meta_inf_entries": [
                        "META-INF/FIXTURE.RSA",
                        "META-INF/FIXTURE.SF",
                        "META-INF/MANIFEST.MF",
                    ],
                    "duplicate_entries": False,
                    "canonical_paths": True,
                    "crc": "exact",
                    "resources": {
                        "total_uncompressed_bytes": 100,
                        "max_entry_uncompressed_bytes": 50,
                        "max_compression_ratio": 2.0,
                        "limits": {
                            "entry_count": verifier.AAB_MAX_ENTRY_COUNT,
                            "entry_uncompressed_bytes": verifier.AAB_MAX_ENTRY_UNCOMPRESSED_BYTES,
                            "total_uncompressed_bytes": verifier.AAB_MAX_TOTAL_UNCOMPRESSED_BYTES,
                            "compression_ratio": verifier.AAB_MAX_COMPRESSION_RATIO,
                        },
                        "checked_before_crc_decompression": True,
                        "container_checked_before_zipfile": True,
                    },
                },
                "certificate_sha256": CERTIFICATE,
                "certificate_count": 1,
                "strict": True,
                "jarsigner_exit_code": 0,
                "trust_anchor_count": 1,
                "trust_anchor": "ephemeral-extracted-pinned-certificate",
                "alias_bound": True,
                "ephemeral_truststore_cleaned": True,
                "all_payload_entries_signed": True,
            }

            evidence = verify_downloaded_release(
                state=state,
                asset_dir=root,
                expected_certificate_sha256=CERTIFICATE,
                apk_signature_report=apk_signature,
                apk_package_report=apk_package,
                apk_version_name_report=apk_version_name,
                apk_version_code_report=apk_version_code,
                aab_verification=aab_verification,
            )

            self.assertEqual(4, len(evidence["assets"]))
            self.assertEqual(10, evidence["application"]["version_code"])
            self.assertEqual("exact", evidence["signature_identity"]["apk"])
            self.assertEqual(
                "strict-all-entries-exact", evidence["signature_identity"]["aab"]
            )

            aab_verification["aab"]["digest"] = "sha256:" + "0" * 64
            with self.assertRaises(VerificationFailure) as raised:
                verify_downloaded_release(
                    state=state,
                    asset_dir=root,
                    expected_certificate_sha256=CERTIFICATE,
                    apk_signature_report=apk_signature,
                    apk_package_report=apk_package,
                    apk_version_name_report=apk_version_name,
                    apk_version_code_report=apk_version_code,
                    aab_verification=aab_verification,
                )
            self.assertIn(
                "aab.strict_verification.aab.digest",
                mismatch_fields(raised.exception),
            )


class StrictAabVerifierTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        for tool in ("keytool", "jarsigner"):
            if shutil.which(tool) is None:
                raise RuntimeError(f"{tool} is required for strict AAB verifier tests")
        cls._temporary = tempfile.TemporaryDirectory()
        cls.root = Path(cls._temporary.name)
        cls.keystore = cls.root / "fixture-signers.p12"
        cls.password = "fixture-password"
        cls._run(
            "keytool",
            "-genkeypair",
            "-noprompt",
            "-alias",
            "fixture",
            "-keyalg",
            "RSA",
            "-keysize",
            "2048",
            "-validity",
            "3650",
            "-dname",
            "CN=OpenAria AAB Test Fixture",
            "-keystore",
            str(cls.keystore),
            "-storetype",
            "PKCS12",
            "-storepass",
            cls.password,
            "-keypass",
            cls.password,
        )
        cls.base_aab = cls.root / "base-signed.aab"
        with zipfile.ZipFile(cls.base_aab, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("BundleConfig.pb", b"bundle configuration")
            archive.writestr(
                "base/manifest/AndroidManifest.xml", b"compiled manifest fixture"
            )
        cls._run(
            "jarsigner",
            "-keystore",
            str(cls.keystore),
            "-storetype",
            "PKCS12",
            "-storepass",
            cls.password,
            "-keypass",
            cls.password,
            str(cls.base_aab),
            "fixture",
        )
        certificate_der = cls.root / "fixture.der"
        cls._run(
            "keytool",
            "-exportcert",
            "-alias",
            "fixture",
            "-keystore",
            str(cls.keystore),
            "-storetype",
            "PKCS12",
            "-storepass",
            cls.password,
            "-file",
            str(certificate_der),
        )
        cls.certificate = hashlib.sha256(certificate_der.read_bytes()).hexdigest()
        cls._run(
            "keytool",
            "-genkeypair",
            "-noprompt",
            "-alias",
            "second",
            "-keyalg",
            "RSA",
            "-keysize",
            "2048",
            "-validity",
            "3650",
            "-dname",
            "CN=Second OpenAria AAB Test Fixture",
            "-keystore",
            str(cls.keystore),
            "-storetype",
            "PKCS12",
            "-storepass",
            cls.password,
            "-keypass",
            cls.password,
        )

    @classmethod
    def tearDownClass(cls) -> None:
        cls._temporary.cleanup()

    @staticmethod
    def _run(*command: str) -> None:
        environment = dict(os.environ)
        environment["LC_ALL"] = "C"
        subprocess.run(
            command,
            check=True,
            capture_output=True,
            text=True,
            env=environment,
        )

    def copy_fixture(self, root: Path) -> Path:
        destination = root / "fixture.aab"
        shutil.copyfile(self.base_aab, destination)
        return destination

    def verify(self, aab: Path, root: Path, certificate: str | None = None) -> dict:
        trust_parent = root / "temporary-truststores"
        trust_parent.mkdir()
        evidence = verify_aab_signature(
            aab_path=aab,
            expected_certificate_sha256=certificate or self.certificate,
            report_dir=root / "reports",
            temporary_parent=trust_parent,
        )
        self.assertEqual([], list(trust_parent.iterdir()))
        return evidence

    def test_valid_self_signed_aab_passes_strict_with_cleaned_ephemeral_anchor(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            evidence = self.verify(self.copy_fixture(root), root)
            strict_report = (root / "reports" / "jarsigner-strict.txt").read_text(
                encoding="utf-8"
            )
            self.assertNotIn(str(root), strict_report)
            self.assertNotIn(".p12", strict_report)

        self.assertTrue(evidence["all_payload_entries_signed"])
        self.assertEqual(0, evidence["jarsigner_exit_code"])
        self.assertEqual(1, evidence["trust_anchor_count"])
        self.assertEqual(2, evidence["archive"]["payload_entry_count"])
        self.assertTrue(
            evidence["archive"]["resources"]["checked_before_crc_decompression"]
        )
        self.assertTrue(
            evidence["archive"]["container_preflight"]["checked_before_zipfile"]
        )
        self.assertEqual(
            evidence["archive"]["entry_count"],
            evidence["archive"]["container_preflight"]["actual_entry_count"],
        )
        self.assertLessEqual(
            evidence["archive"]["resources"]["max_compression_ratio"],
            verifier.AAB_MAX_COMPRESSION_RATIO,
        )

    def test_appended_unsigned_payload_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            aab = self.copy_fixture(root)
            with zipfile.ZipFile(aab, "a", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("base/assets/unsigned.pb", b"unsigned")

            with self.assertRaises(VerificationFailure) as raised:
                self.verify(aab, root)

        fields = mismatch_fields(raised.exception)
        self.assertTrue(
            "aab.jarsigner.strict_exit_code" in fields
            or "aab.keytool.printcert_rfc.exit_code" in fields
        )

    def test_tampered_signed_payload_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            aab = self.copy_fixture(root)
            replacement = root / "tampered.aab"
            with (
                zipfile.ZipFile(aab) as source,
                zipfile.ZipFile(replacement, "w") as target,
            ):
                for entry in source.infolist():
                    value = source.read(entry.filename)
                    if entry.filename == "BundleConfig.pb":
                        value += b" tampered"
                    target.writestr(entry, value)
            replacement.replace(aab)

            with self.assertRaises(VerificationFailure) as raised:
                self.verify(aab, root)

        fields = mismatch_fields(raised.exception)
        self.assertTrue(
            "aab.jarsigner.strict_exit_code" in fields
            or "aab.keytool.printcert_rfc.exit_code" in fields
        )

    def test_wrong_certificate_pin_is_rejected_before_trust(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            aab = self.copy_fixture(root)
            trust_parent = root / "temporary-truststores"
            trust_parent.mkdir()

            with self.assertRaises(VerificationFailure) as raised:
                verify_aab_signature(
                    aab_path=aab,
                    expected_certificate_sha256="0" * 64,
                    report_dir=root / "reports",
                    temporary_parent=trust_parent,
                )

            self.assertEqual([], list(trust_parent.iterdir()))
        self.assertIn("aab.signingCertificateSha256", mismatch_fields(raised.exception))

    def test_second_signer_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            aab = self.copy_fixture(root)
            self._run(
                "jarsigner",
                "-keystore",
                str(self.keystore),
                "-storetype",
                "PKCS12",
                "-storepass",
                self.password,
                "-keypass",
                self.password,
                str(aab),
                "second",
            )

            with self.assertRaises(VerificationFailure) as raised:
                self.verify(aab, root)

        fields = mismatch_fields(raised.exception)
        self.assertTrue(
            "aab.archive.signature_file_count" in fields
            or "aab.certificate.pem_count" in fields
        )

    def test_duplicate_payload_name_is_rejected_even_when_bytes_match(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            aab = self.copy_fixture(root)
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                with zipfile.ZipFile(aab, "a", zipfile.ZIP_DEFLATED) as archive:
                    archive.writestr("BundleConfig.pb", b"bundle configuration")

            with self.assertRaises(VerificationFailure) as raised:
                self.verify(aab, root)

        self.assertIn(
            "aab.archive.duplicate_entries", mismatch_fields(raised.exception)
        )

    def test_extra_meta_inf_signature_control_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            aab = self.copy_fixture(root)
            with zipfile.ZipFile(aab, "a", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("META-INF/EVIL.SF", b"ignored by jarsigner")

            with self.assertRaises(VerificationFailure) as raised:
                self.verify(aab, root)

        fields = mismatch_fields(raised.exception)
        self.assertIn("aab.archive.signature_file_count", fields)
        self.assertIn("aab.archive.signature_control_count", fields)

    def test_jar_ignored_signature_controls_are_case_insensitive(self) -> None:
        ignored_controls = (
            "META-INF/EVIL.sf",
            "META-INF/EVIL.rSa",
            "meta-inf/EVIL.SF",
            "MeTa-InF/SiG-EvIl",
        )
        for ignored_control in ignored_controls:
            with self.subTest(ignored_control=ignored_control):
                with tempfile.TemporaryDirectory() as temporary_directory:
                    root = Path(temporary_directory)
                    aab = self.copy_fixture(root)
                    with zipfile.ZipFile(aab, "a", zipfile.ZIP_DEFLATED) as archive:
                        archive.writestr(ignored_control, b"ignored by jarsigner")

                    with self.assertRaises(VerificationFailure) as raised:
                        self.verify(aab, root)

                fields = mismatch_fields(raised.exception)
                self.assertTrue(
                    "aab.archive.signature_control_count" in fields
                    or "aab.archive.signature_controls" in fields
                    or any(
                        field.endswith(".signature_control_case") for field in fields
                    )
                )

    def test_empty_basename_jar_signature_controls_are_rejected(self) -> None:
        ignored_controls = (
            "META-INF/.SF",
            "META-INF/.RSA",
            "META-INF/.DSA",
            "META-INF/.EC",
            "META-INF/SIG-",
        )
        for ignored_control in ignored_controls:
            with self.subTest(ignored_control=ignored_control):
                with tempfile.TemporaryDirectory() as temporary_directory:
                    root = Path(temporary_directory)
                    aab = self.copy_fixture(root)
                    with zipfile.ZipFile(aab, "a", zipfile.ZIP_DEFLATED) as archive:
                        archive.writestr(ignored_control, b"ignored by jarsigner")

                    with self.assertRaises(VerificationFailure) as raised:
                        self.verify(aab, root)

                fields = mismatch_fields(raised.exception)
                self.assertTrue(
                    "aab.archive.signature_control_count" in fields
                    or "aab.archive.signature_controls" in fields
                    or "aab.archive.signature_file_count" in fields
                    or "aab.archive.signature_block_count" in fields
                )

    def test_declared_entry_limit_is_rejected_before_zipfile_construction(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            archive_path = Path(temporary_directory) / "too-many-empty-entries.aab"
            with zipfile.ZipFile(archive_path, "w", zipfile.ZIP_STORED) as archive:
                for index in range(verifier.AAB_MAX_ENTRY_COUNT + 1):
                    archive.writestr(f"empty/{index}", b"")

            with (
                mock.patch.object(
                    verifier.zipfile,
                    "ZipFile",
                    side_effect=AssertionError("ZipFile must not be constructed"),
                ),
                self.assertRaises(VerificationFailure) as raised,
            ):
                verifier._audit_aab_archive(archive_path)

        self.assertIn(
            "aab.archive.container_preflight.entry_count",
            mismatch_fields(raised.exception),
        )

    def test_underdeclared_central_directory_is_rejected_before_zipfile(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            archive_path = Path(temporary_directory) / "underdeclared-entries.aab"
            with zipfile.ZipFile(archive_path, "w", zipfile.ZIP_STORED) as archive:
                for index in range(32):
                    archive.writestr(f"empty/{index}", b"")

            archive_bytes = bytearray(archive_path.read_bytes())
            eocd_offset = archive_bytes.rfind(b"PK\x05\x06")
            self.assertGreaterEqual(eocd_offset, 0)
            struct.pack_into("<H", archive_bytes, eocd_offset + 8, 1)
            struct.pack_into("<H", archive_bytes, eocd_offset + 10, 1)
            archive_path.write_bytes(archive_bytes)

            with (
                mock.patch.object(
                    verifier.zipfile,
                    "ZipFile",
                    side_effect=AssertionError("ZipFile must not be constructed"),
                ),
                self.assertRaises(VerificationFailure) as raised,
            ):
                verifier._audit_aab_archive(archive_path)

        self.assertIn(
            "aab.archive.container_preflight.unparsed_central_directory_bytes",
            mismatch_fields(raised.exception),
        )

    def test_archive_and_central_directory_byte_limits_precede_zipfile(self) -> None:
        cases = (
            (
                "AAB_MAX_ARCHIVE_BYTES",
                self.base_aab.stat().st_size - 1,
                "aab.archive.container_preflight.archive_bytes",
            ),
            (
                "AAB_MAX_CENTRAL_DIRECTORY_BYTES",
                1,
                "aab.archive.container_preflight.central_directory_bytes",
            ),
        )
        for constant, limit, expected_field in cases:
            with self.subTest(constant=constant):
                with (
                    mock.patch.object(verifier, constant, limit),
                    mock.patch.object(
                        verifier.zipfile,
                        "ZipFile",
                        side_effect=AssertionError("ZipFile must not be constructed"),
                    ),
                    self.assertRaises(VerificationFailure) as raised,
                ):
                    verifier._audit_aab_archive(self.base_aab)
                self.assertIn(expected_field, mismatch_fields(raised.exception))

    def test_forged_zip64_entry_count_is_rejected_before_zipfile_construction(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            archive_path = Path(temporary_directory) / "forged-zip64-count.aab"
            with zipfile.ZipFile(archive_path, "w", zipfile.ZIP_STORED) as archive:
                archive.writestr("payload", b"payload")

            archive_bytes = archive_path.read_bytes()
            eocd_offset = archive_bytes.rfind(b"PK\x05\x06")
            self.assertGreaterEqual(eocd_offset, 0)
            eocd = struct.unpack_from("<4s4H2IH", archive_bytes, eocd_offset)
            central_directory_bytes = eocd[5]
            central_directory_offset = eocd[6]
            forged_entries = verifier.AAB_MAX_ENTRY_COUNT + 1
            zip64_eocd = struct.pack(
                "<4sQ2H2I4Q",
                b"PK\x06\x06",
                44,
                45,
                45,
                0,
                0,
                forged_entries,
                forged_entries,
                central_directory_bytes,
                central_directory_offset,
            )
            zip64_locator = struct.pack("<4sIQI", b"PK\x06\x07", 0, eocd_offset, 1)
            classic_eocd = struct.pack(
                "<4s4H2IH",
                b"PK\x05\x06",
                0,
                0,
                0xFFFF,
                0xFFFF,
                0xFFFFFFFF,
                0xFFFFFFFF,
                0,
            )
            archive_path.write_bytes(
                archive_bytes[:eocd_offset] + zip64_eocd + zip64_locator + classic_eocd
            )

            with (
                mock.patch.object(
                    verifier.zipfile,
                    "ZipFile",
                    side_effect=AssertionError("ZipFile must not be constructed"),
                ),
                self.assertRaises(VerificationFailure) as raised,
            ):
                verifier._audit_aab_archive(archive_path)

        self.assertIn(
            "aab.archive.container_preflight.entry_count",
            mismatch_fields(raised.exception),
        )

    def test_multidisk_and_ambiguous_eocd_are_rejected_before_zipfile(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            original = root / "original.zip"
            with zipfile.ZipFile(original, "w", zipfile.ZIP_STORED) as archive:
                archive.writestr("payload", b"payload")
            original_bytes = original.read_bytes()
            eocd_offset = original_bytes.rfind(b"PK\x05\x06")

            multidisk = root / "multidisk.aab"
            multidisk_bytes = bytearray(original_bytes)
            struct.pack_into("<H", multidisk_bytes, eocd_offset + 4, 1)
            multidisk.write_bytes(multidisk_bytes)

            ambiguous = root / "ambiguous.aab"
            fake_eocd = struct.pack("<4s4H2IH", b"PK\x05\x06", 0, 0, 1, 1, 0, 0, 22)
            ambiguous.write_bytes(
                original_bytes[:eocd_offset] + fake_eocd + original_bytes[eocd_offset:]
            )

            expected_fields = (
                (multidisk, "aab.archive.container_preflight.disk_number"),
                (
                    ambiguous,
                    "aab.archive.container_preflight.eocd_candidate_count",
                ),
            )
            for archive_path, expected_field in expected_fields:
                with self.subTest(archive_path=archive_path.name):
                    with (
                        mock.patch.object(
                            verifier.zipfile,
                            "ZipFile",
                            side_effect=AssertionError(
                                "ZipFile must not be constructed"
                            ),
                        ),
                        self.assertRaises(VerificationFailure) as raised,
                    ):
                        verifier._audit_aab_archive(archive_path)
                    self.assertIn(expected_field, mismatch_fields(raised.exception))

    def test_zip_bomb_ratio_is_rejected_before_crc_decompression(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            aab = root / "compression-bomb.aab"
            with zipfile.ZipFile(aab, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("base/assets/compression-bomb.bin", b"\0" * 1048576)

            with (
                mock.patch.object(
                    zipfile.ZipFile,
                    "testzip",
                    side_effect=AssertionError("CRC decompression must not run"),
                ),
                self.assertRaises(VerificationFailure) as raised,
            ):
                verifier._audit_aab_archive(aab)

        self.assertTrue(
            any(
                field.endswith(".compression_ratio")
                for field in mismatch_fields(raised.exception)
            )
        )

    def test_entry_and_uncompressed_byte_limits_precede_crc(self) -> None:
        cases = (
            (
                "AAB_MAX_ENTRY_COUNT",
                1,
                "aab.archive.container_preflight.entry_count",
            ),
            (
                "AAB_MAX_ENTRY_UNCOMPRESSED_BYTES",
                1,
                "uncompressed_bytes",
            ),
            (
                "AAB_MAX_TOTAL_UNCOMPRESSED_BYTES",
                1,
                "aab.archive.total_uncompressed_bytes",
            ),
        )
        for constant, limit, expected_field in cases:
            with self.subTest(constant=constant):
                with (
                    mock.patch.object(verifier, constant, limit),
                    mock.patch.object(
                        zipfile.ZipFile,
                        "testzip",
                        side_effect=AssertionError("CRC decompression must not run"),
                    ),
                    self.assertRaises(VerificationFailure) as raised,
                ):
                    verifier._audit_aab_archive(self.base_aab)

                fields = mismatch_fields(raised.exception)
                if expected_field == "uncompressed_bytes":
                    self.assertTrue(
                        any(field.endswith(".uncompressed_bytes") for field in fields)
                    )
                else:
                    self.assertIn(expected_field, fields)

    def test_jarsigner_timeout_is_classified_and_truststore_is_cleaned(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            aab = self.copy_fixture(root)
            trust_parent = root / "temporary-truststores"
            trust_parent.mkdir()
            real_subprocess_run = subprocess.run

            def run_with_jarsigner_timeout(
                command: list[str], *args: object, **kwargs: object
            ) -> subprocess.CompletedProcess[str]:
                if command[0] == "jarsigner" and "-verify" in command:
                    raise subprocess.TimeoutExpired(command, kwargs.get("timeout"))
                return real_subprocess_run(command, *args, **kwargs)

            with (
                mock.patch.object(
                    verifier.subprocess,
                    "run",
                    side_effect=run_with_jarsigner_timeout,
                ),
                self.assertRaises(VerificationFailure) as raised,
            ):
                verify_aab_signature(
                    aab_path=aab,
                    expected_certificate_sha256=self.certificate,
                    report_dir=root / "reports",
                    temporary_parent=trust_parent,
                )

            self.assertEqual([], list(trust_parent.iterdir()))
        self.assertIn(
            "aab.jarsigner.strict.timeout_seconds",
            mismatch_fields(raised.exception),
        )

    def test_keytool_import_and_list_failures_clean_ephemeral_truststore(
        self,
    ) -> None:
        cases = (
            (
                "-importcert",
                "timeout",
                "aab.keytool.import_pinned_trust_anchor.timeout_seconds",
            ),
            (
                "-importcert",
                "os_error",
                "aab.keytool.import_pinned_trust_anchor",
            ),
            (
                "-list",
                "timeout",
                "aab.keytool.list_pinned_trust_anchor.timeout_seconds",
            ),
            (
                "-list",
                "os_error",
                "aab.keytool.list_pinned_trust_anchor",
            ),
        )
        real_subprocess_run = subprocess.run
        for marker, failure_kind, expected_field in cases:
            with self.subTest(marker=marker, failure_kind=failure_kind):
                with tempfile.TemporaryDirectory() as temporary_directory:
                    root = Path(temporary_directory)
                    aab = self.copy_fixture(root)
                    trust_parent = root / "temporary-truststores"
                    trust_parent.mkdir()

                    def run_with_keytool_failure(
                        command: list[str],
                        *args: object,
                        marker: str = marker,
                        failure_kind: str = failure_kind,
                        **kwargs: object,
                    ) -> subprocess.CompletedProcess[str]:
                        if command[0] == "keytool" and marker in command:
                            if failure_kind == "timeout":
                                raise subprocess.TimeoutExpired(
                                    command, kwargs.get("timeout")
                                )
                            raise OSError("synthetic keytool failure")
                        return real_subprocess_run(command, *args, **kwargs)

                    with (
                        mock.patch.object(
                            verifier.subprocess,
                            "run",
                            side_effect=run_with_keytool_failure,
                        ),
                        self.assertRaises(VerificationFailure) as raised,
                    ):
                        verify_aab_signature(
                            aab_path=aab,
                            expected_certificate_sha256=self.certificate,
                            report_dir=root / "reports",
                            temporary_parent=trust_parent,
                        )

                    self.assertEqual([], list(trust_parent.iterdir()))
                self.assertIn(expected_field, mismatch_fields(raised.exception))


if __name__ == "__main__":
    unittest.main()
