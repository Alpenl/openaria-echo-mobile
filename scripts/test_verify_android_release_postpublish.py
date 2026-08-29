from __future__ import annotations

import copy
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from verify_android_release_postpublish import (
    PACKAGE_NAME,
    VerificationFailure,
    validate_release_state,
    validate_source_evidence,
    verify_downloaded_release,
)

REPOSITORY = "Alpenl/openaria-echo-mobile"
RUN_ID = "33253867763"
RUN_ATTEMPT = "1"
TAG = "v0.1.7"
COMMIT = "83147d60a6c41395a7cec2d5b5586a9694090c37"
RELEASE_ID = 378992098
CERTIFICATE = "a" * 64


def sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


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

        fields = {item["field"] for item in raised.exception.mismatches}
        self.assertIn("ownership.run_id", fields)

    def test_wrong_tag_is_rejected(self) -> None:
        with self.assertRaises(VerificationFailure) as raised:
            self.validate(tag="v0.1.8")

        fields = {item["field"] for item in raised.exception.mismatches}
        self.assertIn("ownership.target.tag", fields)
        self.assertIn("published.tag_name", fields)

    def test_tampered_receipt_release_id_is_rejected(self) -> None:
        tampered = copy.deepcopy(self.ownership)
        tampered["target"]["release_id"] = RELEASE_ID + 1

        with self.assertRaises(VerificationFailure) as raised:
            self.validate(ownership=tampered)

        fields = {item["field"] for item in raised.exception.mismatches}
        self.assertIn("published.id", fields)

    def test_tampered_receipt_digest_is_rejected(self) -> None:
        tampered = copy.deepcopy(self.ownership)
        tampered["target"]["assets"][0]["digest"] = "sha256:" + "f" * 64

        with self.assertRaises(VerificationFailure) as raised:
            self.validate(ownership=tampered)

        fields = {item["field"] for item in raised.exception.mismatches}
        self.assertTrue(
            any(
                field.endswith(".digest") and field.startswith("published.assets")
                for field in fields
            )
        )

    def test_wrong_public_url_has_field_level_diagnostic(self) -> None:
        published = copy.deepcopy(self.published)
        published["assets"][0]["browser_download_url"] = (
            f"https://github.com/{REPOSITORY}/releases/download/untagged-stale/SHA256SUMS.txt"
        )

        with self.assertRaises(VerificationFailure) as raised:
            self.validate(published_release=published)

        fields = {item["field"] for item in raised.exception.mismatches}
        self.assertIn("published.assets[SHA256SUMS.txt].browser_download_url", fields)

    def test_source_run_and_ownership_artifact_are_strictly_bound(self) -> None:
        state = self.validate()
        source_run = {
            "id": int(RUN_ID),
            "run_attempt": int(RUN_ATTEMPT),
            "head_sha": COMMIT,
            "path": ".github/workflows/mobile-release.yml",
            "event": "workflow_dispatch",
            "status": "completed",
            "conclusion": "failure",
            "repository": {"id": 10, "full_name": REPOSITORY},
            "head_repository": {"id": 10, "full_name": REPOSITORY},
        }
        artifact = {
            "id": 9715378093,
            "name": f"android-release-ownership-{TAG}-{RUN_ID}-{RUN_ATTEMPT}",
            "size_in_bytes": 1383,
            "digest": "sha256:" + "b" * 64,
            "expired": False,
            "workflow_run": {
                "id": int(RUN_ID),
                "repository_id": 10,
                "head_repository_id": 10,
                "head_sha": COMMIT,
            },
        }

        validate_source_evidence(
            state=state, source_run=source_run, ownership_artifact=artifact
        )
        artifact["workflow_run"]["head_sha"] = "0" * 40
        with self.assertRaises(VerificationFailure) as raised:
            validate_source_evidence(
                state=state, source_run=source_run, ownership_artifact=artifact
            )
        self.assertEqual(
            "ownership_artifact.workflow_run.head_sha",
            raised.exception.mismatches[0]["field"],
        )

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
            aab_signature = reports / "jarsigner.txt"
            aab_certificate = reports / "aab-certificate.txt"
            apk_signature.write_text(
                f"Signer #1 certificate SHA-256 digest: {CERTIFICATE}\n",
                encoding="utf-8",
            )
            apk_package.write_text(f"{PACKAGE_NAME}\n", encoding="utf-8")
            apk_version_name.write_text("0.1.7\n", encoding="utf-8")
            apk_version_code.write_text("10\n", encoding="utf-8")
            aab_signature.write_text("jar verified.\n", encoding="utf-8")
            colon_digest = ":".join(
                CERTIFICATE[index : index + 2].upper()
                for index in range(0, len(CERTIFICATE), 2)
            )
            aab_certificate.write_text(f"SHA256: {colon_digest}\n", encoding="utf-8")

            evidence = verify_downloaded_release(
                state=state,
                asset_dir=root,
                expected_certificate_sha256=CERTIFICATE,
                apk_signature_report=apk_signature,
                apk_package_report=apk_package,
                apk_version_name_report=apk_version_name,
                apk_version_code_report=apk_version_code,
                aab_signature_report=aab_signature,
                aab_certificate_report=aab_certificate,
            )

            self.assertEqual(4, len(evidence["assets"]))
            self.assertEqual(10, evidence["application"]["version_code"])
            self.assertEqual("exact", evidence["signature_identity"]["apk"])
            self.assertEqual("exact", evidence["signature_identity"]["aab"])


if __name__ == "__main__":
    unittest.main()
