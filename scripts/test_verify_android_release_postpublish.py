from __future__ import annotations

import copy
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from verify_android_release_postpublish import (
    AAB_EVIDENCE_SCHEMA,
    PACKAGE_NAME,
    VerificationFailure,
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
                    "payload_entry_count": 2,
                    "signature_control_entries": [
                        "META-INF/FIXTURE.RSA",
                        "META-INF/FIXTURE.SF",
                        "META-INF/MANIFEST.MF",
                    ],
                    "duplicate_entries": False,
                    "canonical_paths": True,
                    "crc": "exact",
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


if __name__ == "__main__":
    unittest.main()
