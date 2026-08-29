from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

REPOSITORY = "Alpenl/openaria-echo-mobile"
PACKAGE_NAME = "com.openaria.openaria_echo_mobile"
OWNERSHIP_SCHEMA = "openaria.mobile.release-ownership.v1"
STATE_SCHEMA = "openaria.mobile.release-post-publish-state.v1"
EVIDENCE_SCHEMA = "openaria.mobile.read-only-post-publish-verification.v1"
HEX_40 = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
SHA256_DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")
RELEASE_TAG = re.compile(r"^v([0-9]+\.[0-9]+\.[0-9]+)$")


class VerificationFailure(Exception):
    def __init__(self, mismatches: list[dict[str, Any]]) -> None:
        super().__init__("Android post-publish verification failed")
        self.mismatches = mismatches


def _load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise VerificationFailure(
            [
                {
                    "field": str(path),
                    "expected": "valid JSON object",
                    "actual": str(error),
                }
            ]
        ) from error
    if not isinstance(value, dict):
        raise VerificationFailure(
            [
                {
                    "field": str(path),
                    "expected": "JSON object",
                    "actual": type(value).__name__,
                }
            ]
        )
    return value


def _write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )


def _mismatch(
    mismatches: list[dict[str, Any]], field: str, expected: Any, actual: Any
) -> None:
    if actual != expected:
        mismatches.append({"field": field, "expected": expected, "actual": actual})


def _require(
    mismatches: list[dict[str, Any]],
    condition: bool,
    field: str,
    expected: Any,
    actual: Any,
) -> None:
    if not condition:
        mismatches.append({"field": field, "expected": expected, "actual": actual})


def _at(value: Any, *path: str) -> Any:
    current = value
    for key in path:
        if not isinstance(current, dict) or key not in current:
            return None
        current = current[key]
    return current


def _release_names(tag: str) -> set[str]:
    return {
        "SHA256SUMS.txt",
        "android-update.json",
        f"openaria-echo-mobile-{tag}-android-signed.aab",
        f"openaria-echo-mobile-{tag}-android-signed.apk",
    }


def _asset_projection(release: dict[str, Any]) -> list[dict[str, Any]]:
    assets = release.get("assets")
    if not isinstance(assets, list):
        return []
    return [
        {
            "name": asset.get("name"),
            "size": asset.get("size"),
            "digest": asset.get("digest"),
            "browser_download_url": asset.get("browser_download_url"),
        }
        for asset in assets
        if isinstance(asset, dict)
    ]


def _assets_by_name(
    assets: Any, field: str, expected_names: set[str], mismatches: list[dict[str, Any]]
) -> dict[str, dict[str, Any]]:
    if not isinstance(assets, list):
        mismatches.append(
            {"field": field, "expected": "array", "actual": type(assets).__name__}
        )
        return {}

    by_name: dict[str, dict[str, Any]] = {}
    duplicate_names: list[str] = []
    invalid_entries = 0
    for asset in assets:
        if not isinstance(asset, dict) or not isinstance(asset.get("name"), str):
            invalid_entries += 1
            continue
        name = asset["name"]
        if name in by_name:
            duplicate_names.append(name)
        by_name[name] = asset

    _require(
        mismatches,
        invalid_entries == 0,
        f"{field}.entries",
        "objects with string names",
        invalid_entries,
    )
    _mismatch(mismatches, f"{field}.names", sorted(expected_names), sorted(by_name))
    _mismatch(mismatches, f"{field}.duplicate_names", [], sorted(set(duplicate_names)))
    return by_name


def _validate_asset_fields(
    assets: dict[str, dict[str, Any]], field: str, mismatches: list[dict[str, Any]]
) -> None:
    for name, asset in sorted(assets.items()):
        size = asset.get("size")
        digest = asset.get("digest")
        _require(
            mismatches,
            isinstance(size, int) and not isinstance(size, bool) and size > 0,
            f"{field}[{name}].size",
            "positive integer",
            size,
        )
        _require(
            mismatches,
            isinstance(digest, str) and SHA256_DIGEST.fullmatch(digest) is not None,
            f"{field}[{name}].digest",
            "sha256:<64 lowercase hex>",
            digest,
        )


def validate_release_state(
    *,
    ownership: dict[str, Any],
    latest_release: dict[str, Any],
    published_release: dict[str, Any],
    published_by_tag: dict[str, Any],
    tag_ref: dict[str, Any],
    repository: str,
    source_run_id: str,
    source_run_attempt: str,
    tag: str,
    commit: str,
) -> dict[str, Any]:
    mismatches: list[dict[str, Any]] = []
    tag_match = RELEASE_TAG.fullmatch(tag)
    _require(mismatches, repository == REPOSITORY, "repository", REPOSITORY, repository)
    _require(
        mismatches,
        source_run_id.isdigit() and int(source_run_id) > 0,
        "source_run_id",
        "positive integer string",
        source_run_id,
    )
    _require(
        mismatches,
        source_run_attempt.isdigit() and int(source_run_attempt) > 0,
        "source_run_attempt",
        "positive integer string",
        source_run_attempt,
    )
    _require(mismatches, tag_match is not None, "tag", "vX.Y.Z", tag)
    _require(
        mismatches,
        HEX_40.fullmatch(commit) is not None,
        "commit",
        "40 lowercase hex",
        commit,
    )

    _mismatch(mismatches, "ownership.schema", OWNERSHIP_SCHEMA, ownership.get("schema"))
    _mismatch(
        mismatches, "ownership.repository", repository, ownership.get("repository")
    )
    _mismatch(mismatches, "ownership.run_id", source_run_id, ownership.get("run_id"))
    _mismatch(
        mismatches,
        "ownership.run_attempt",
        source_run_attempt,
        ownership.get("run_attempt"),
    )
    _mismatch(
        mismatches,
        "ownership.draft_never_public",
        True,
        ownership.get("draft_never_public"),
    )
    _mismatch(
        mismatches,
        "ownership.draft_created_by_run",
        True,
        ownership.get("draft_created_by_run"),
    )
    _mismatch(mismatches, "ownership.target.tag", tag, _at(ownership, "target", "tag"))
    _mismatch(
        mismatches,
        "ownership.target.source_commit",
        commit,
        _at(ownership, "target", "source_commit"),
    )

    release_id = _at(ownership, "target", "release_id")
    _require(
        mismatches,
        isinstance(release_id, int)
        and not isinstance(release_id, bool)
        and release_id > 0,
        "ownership.target.release_id",
        "positive integer",
        release_id,
    )

    expected_names = _release_names(tag)
    owned_assets = _assets_by_name(
        _at(ownership, "target", "assets"),
        "ownership.target.assets",
        expected_names,
        mismatches,
    )
    published_assets = _assets_by_name(
        _asset_projection(published_release),
        "published.assets",
        expected_names,
        mismatches,
    )
    by_tag_assets = _assets_by_name(
        _asset_projection(published_by_tag),
        "published_by_tag.assets",
        expected_names,
        mismatches,
    )
    latest_assets = _assets_by_name(
        _asset_projection(latest_release), "latest.assets", expected_names, mismatches
    )
    _validate_asset_fields(owned_assets, "ownership.target.assets", mismatches)
    _validate_asset_fields(published_assets, "published.assets", mismatches)

    owned_asset_identity = {
        name: {field: asset.get(field) for field in ("name", "size", "digest")}
        for name, asset in owned_assets.items()
    }
    published_asset_identity = {
        name: {field: asset.get(field) for field in ("name", "size", "digest")}
        for name, asset in published_assets.items()
    }

    for name in sorted(expected_names):
        owned = owned_asset_identity.get(name, {})
        public = published_asset_identity.get(name, {})
        owned_asset = owned_assets.get(name, {})
        public_asset = published_assets.get(name, {})
        for identity_field in ("name", "size", "digest"):
            _mismatch(
                mismatches,
                f"published.assets[{name}].{identity_field}",
                owned.get(identity_field),
                public.get(identity_field),
            )
        expected_public_url = (
            f"https://github.com/{repository}/releases/download/{tag}/{name}"
        )
        _mismatch(
            mismatches,
            f"published.assets[{name}].browser_download_url",
            expected_public_url,
            public_asset.get("browser_download_url"),
        )
        owned_url = owned_asset.get("browser_download_url")
        owned_url_pattern = re.compile(
            rf"^https://github\.com/{re.escape(repository)}/releases/download/untagged-[0-9a-f]+/{re.escape(name)}$"
        )
        _require(
            mismatches,
            isinstance(owned_url, str)
            and owned_url_pattern.fullmatch(owned_url) is not None,
            f"ownership.target.assets[{name}].browser_download_url",
            f"https://github.com/{repository}/releases/download/untagged-<id>/{name}",
            owned_url,
        )

        for snapshot_name, snapshot_assets in (
            ("latest", latest_assets),
            ("published_by_tag", by_tag_assets),
        ):
            snapshot = snapshot_assets.get(name, {})
            for identity_field in ("name", "size", "digest", "browser_download_url"):
                _mismatch(
                    mismatches,
                    f"{snapshot_name}.assets[{name}].{identity_field}",
                    public_asset.get(identity_field),
                    snapshot.get(identity_field),
                )

    for field, expected in (
        ("id", release_id),
        ("tag_name", tag),
        ("draft", False),
        ("prerelease", False),
        ("immutable", True),
    ):
        _mismatch(
            mismatches, f"published.{field}", expected, published_release.get(field)
        )
        _mismatch(
            mismatches,
            f"published_by_tag.{field}",
            expected,
            published_by_tag.get(field),
        )
        _mismatch(mismatches, f"latest.{field}", expected, latest_release.get(field))
    _require(
        mismatches,
        isinstance(published_release.get("published_at"), str)
        and bool(published_release.get("published_at")),
        "published.published_at",
        "non-empty timestamp",
        published_release.get("published_at"),
    )
    _mismatch(
        mismatches, "tag_ref.object.type", "commit", _at(tag_ref, "object", "type")
    )
    _mismatch(mismatches, "tag_ref.object.sha", commit, _at(tag_ref, "object", "sha"))

    if mismatches:
        raise VerificationFailure(mismatches)

    assets = [
        {
            "name": name,
            "size": published_assets[name]["size"],
            "digest": published_assets[name]["digest"],
            "browser_download_url": published_assets[name]["browser_download_url"],
        }
        for name in sorted(expected_names)
    ]
    return {
        "schema": STATE_SCHEMA,
        "repository": repository,
        "source_run_id": source_run_id,
        "source_run_attempt": source_run_attempt,
        "source_commit": commit,
        "target": {
            "tag": tag,
            "release_id": release_id,
            "tag_commit": commit,
            "immutable": True,
            "latest": True,
            "assets": assets,
        },
    }


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _read_single_line(path: Path) -> str:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise VerificationFailure(
            [
                {
                    "field": str(path),
                    "expected": "readable text file",
                    "actual": str(error),
                }
            ]
        ) from error
    if len(lines) != 1 or not lines[0]:
        raise VerificationFailure(
            [{"field": str(path), "expected": "one non-empty line", "actual": lines}]
        )
    return lines[0]


def _parse_checksums(path: Path) -> dict[str, str]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise VerificationFailure(
            [
                {
                    "field": str(path),
                    "expected": "readable checksum file",
                    "actual": str(error),
                }
            ]
        ) from error
    checksums: dict[str, str] = {}
    mismatches: list[dict[str, Any]] = []
    pattern = re.compile(r"^([0-9a-f]{64})  ([A-Za-z0-9._-]+)$")
    for index, line in enumerate(lines, start=1):
        match = pattern.fullmatch(line)
        if match is None:
            mismatches.append(
                {
                    "field": f"SHA256SUMS.txt:{index}",
                    "expected": "<sha256>  <basename>",
                    "actual": line,
                }
            )
            continue
        digest, name = match.groups()
        if name in checksums:
            mismatches.append(
                {
                    "field": f"SHA256SUMS.txt:{index}",
                    "expected": "unique filename",
                    "actual": name,
                }
            )
        checksums[name] = digest
    if mismatches:
        raise VerificationFailure(mismatches)
    return checksums


def _certificate_digests(report: str, pattern: re.Pattern[str]) -> list[str]:
    return [match.replace(":", "").lower() for match in pattern.findall(report)]


def validate_source_evidence(
    *,
    state: dict[str, Any],
    source_run: dict[str, Any],
    ownership_artifact: dict[str, Any],
) -> None:
    mismatches: list[dict[str, Any]] = []
    run_id = int(state["source_run_id"])
    run_attempt = int(state["source_run_attempt"])
    commit = state["source_commit"]
    repository = state["repository"]
    tag = state["target"]["tag"]
    expected_artifact_name = f"android-release-ownership-{tag}-{run_id}-{run_attempt}"

    for field, expected, actual in (
        ("source_run.id", run_id, source_run.get("id")),
        ("source_run.run_attempt", run_attempt, source_run.get("run_attempt")),
        ("source_run.head_sha", commit, source_run.get("head_sha")),
        (
            "source_run.path",
            ".github/workflows/mobile-release.yml",
            source_run.get("path"),
        ),
        ("source_run.event", "workflow_dispatch", source_run.get("event")),
        ("source_run.status", "completed", source_run.get("status")),
        ("source_run.conclusion", "failure", source_run.get("conclusion")),
        (
            "source_run.repository.full_name",
            repository,
            _at(source_run, "repository", "full_name"),
        ),
        (
            "source_run.head_repository.full_name",
            repository,
            _at(source_run, "head_repository", "full_name"),
        ),
        (
            "ownership_artifact.name",
            expected_artifact_name,
            ownership_artifact.get("name"),
        ),
        ("ownership_artifact.expired", False, ownership_artifact.get("expired")),
        (
            "ownership_artifact.workflow_run.id",
            run_id,
            _at(ownership_artifact, "workflow_run", "id"),
        ),
        (
            "ownership_artifact.workflow_run.head_sha",
            commit,
            _at(ownership_artifact, "workflow_run", "head_sha"),
        ),
    ):
        _mismatch(mismatches, field, expected, actual)

    artifact_id = ownership_artifact.get("id")
    artifact_size = ownership_artifact.get("size_in_bytes")
    artifact_digest = ownership_artifact.get("digest")
    _require(
        mismatches,
        isinstance(artifact_id, int) and artifact_id > 0,
        "ownership_artifact.id",
        "positive integer",
        artifact_id,
    )
    _require(
        mismatches,
        isinstance(artifact_size, int) and artifact_size > 0,
        "ownership_artifact.size_in_bytes",
        "positive integer",
        artifact_size,
    )
    _require(
        mismatches,
        isinstance(artifact_digest, str)
        and SHA256_DIGEST.fullmatch(artifact_digest) is not None,
        "ownership_artifact.digest",
        "sha256:<64 lowercase hex>",
        artifact_digest,
    )
    repository_id = _at(source_run, "repository", "id")
    head_repository_id = _at(source_run, "head_repository", "id")
    _mismatch(
        mismatches,
        "ownership_artifact.workflow_run.repository_id",
        repository_id,
        _at(ownership_artifact, "workflow_run", "repository_id"),
    )
    _mismatch(
        mismatches,
        "ownership_artifact.workflow_run.head_repository_id",
        head_repository_id,
        _at(ownership_artifact, "workflow_run", "head_repository_id"),
    )
    if mismatches:
        raise VerificationFailure(mismatches)


def verify_downloaded_release(
    *,
    state: dict[str, Any],
    asset_dir: Path,
    expected_certificate_sha256: str,
    apk_signature_report: Path,
    apk_package_report: Path,
    apk_version_name_report: Path,
    apk_version_code_report: Path,
    aab_signature_report: Path,
    aab_certificate_report: Path,
) -> dict[str, Any]:
    mismatches: list[dict[str, Any]] = []
    if state.get("schema") != STATE_SCHEMA:
        raise VerificationFailure(
            [
                {
                    "field": "state.schema",
                    "expected": STATE_SCHEMA,
                    "actual": state.get("schema"),
                }
            ]
        )
    tag = _at(state, "target", "tag")
    tag_match = RELEASE_TAG.fullmatch(tag) if isinstance(tag, str) else None
    if tag_match is None:
        raise VerificationFailure(
            [{"field": "state.target.tag", "expected": "vX.Y.Z", "actual": tag}]
        )
    version = tag_match.group(1)
    expected_names = _release_names(tag)
    assets = _assets_by_name(
        _at(state, "target", "assets"),
        "state.target.assets",
        expected_names,
        mismatches,
    )

    try:
        actual_names = {path.name for path in asset_dir.iterdir() if path.is_file()}
    except OSError as error:
        raise VerificationFailure(
            [
                {
                    "field": str(asset_dir),
                    "expected": "readable asset directory",
                    "actual": str(error),
                }
            ]
        ) from error
    _mismatch(
        mismatches,
        "anonymous_downloads.names",
        sorted(expected_names),
        sorted(actual_names),
    )

    observed_assets: list[dict[str, Any]] = []
    for name in sorted(expected_names):
        path = asset_dir / name
        asset = assets.get(name, {})
        if not path.is_file():
            continue
        observed_size = path.stat().st_size
        observed_sha256 = _sha256(path)
        _mismatch(
            mismatches,
            f"anonymous_downloads[{name}].size",
            asset.get("size"),
            observed_size,
        )
        _mismatch(
            mismatches,
            f"anonymous_downloads[{name}].digest",
            asset.get("digest"),
            f"sha256:{observed_sha256}",
        )
        observed_assets.append(
            {
                "name": name,
                "size": observed_size,
                "digest": f"sha256:{observed_sha256}",
                "browser_download_url": asset.get("browser_download_url"),
            }
        )

    if mismatches:
        raise VerificationFailure(mismatches)

    checksums = _parse_checksums(asset_dir / "SHA256SUMS.txt")
    checksum_names = expected_names - {"SHA256SUMS.txt"}
    _mismatch(
        mismatches, "SHA256SUMS.txt.names", sorted(checksum_names), sorted(checksums)
    )
    for name in sorted(checksum_names):
        if name in assets:
            _mismatch(
                mismatches,
                f"SHA256SUMS.txt[{name}]",
                str(assets[name]["digest"]).removeprefix("sha256:"),
                checksums.get(name),
            )

    manifest = _load_json(asset_dir / "android-update.json")
    _mismatch(
        mismatches,
        "manifest.schema",
        "openaria.echo.mobile.android-update.v1",
        manifest.get("schema"),
    )
    _mismatch(
        mismatches, "manifest.packageName", PACKAGE_NAME, manifest.get("packageName")
    )
    _mismatch(mismatches, "manifest.version", version, manifest.get("version"))
    version_code = manifest.get("versionCode")
    _require(
        mismatches,
        isinstance(version_code, int)
        and not isinstance(version_code, bool)
        and version_code > 0,
        "manifest.versionCode",
        "positive integer",
        version_code,
    )
    manifest_certificate = manifest.get("signingCertificateSha256")
    _require(
        mismatches,
        isinstance(manifest_certificate, str)
        and SHA256.fullmatch(manifest_certificate) is not None,
        "manifest.signingCertificateSha256",
        "64 lowercase hex",
        manifest_certificate,
    )

    apk_name = f"openaria-echo-mobile-{tag}-android-signed.apk"
    aab_name = f"openaria-echo-mobile-{tag}-android-signed.aab"
    for kind, name in (("apk", apk_name), ("aab", aab_name)):
        manifest_asset = _at(manifest, "android", kind)
        if not isinstance(manifest_asset, dict):
            mismatches.append(
                {
                    "field": f"manifest.android.{kind}",
                    "expected": "object",
                    "actual": manifest_asset,
                }
            )
            continue
        asset = assets[name]
        _mismatch(
            mismatches,
            f"manifest.android.{kind}.url",
            asset["browser_download_url"],
            manifest_asset.get("url"),
        )
        _mismatch(
            mismatches,
            f"manifest.android.{kind}.sha256",
            str(asset["digest"]).removeprefix("sha256:"),
            manifest_asset.get("sha256"),
        )
        _mismatch(
            mismatches,
            f"manifest.android.{kind}.bytes",
            asset["size"],
            manifest_asset.get("bytes"),
        )

    normalized_expected_certificate = expected_certificate_sha256.lower()
    _require(
        mismatches,
        SHA256.fullmatch(normalized_expected_certificate) is not None,
        "expected_certificate_sha256",
        "64 hex characters",
        "invalid" if not SHA256.fullmatch(normalized_expected_certificate) else "valid",
    )
    if isinstance(manifest_certificate, str):
        _require(
            mismatches,
            manifest_certificate == normalized_expected_certificate,
            "manifest.signingCertificateSha256",
            "protected release certificate",
            "different certificate"
            if manifest_certificate != normalized_expected_certificate
            else "protected release certificate",
        )

    apk_report = apk_signature_report.read_text(encoding="utf-8")
    apk_digests = _certificate_digests(
        apk_report,
        re.compile(
            r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9A-Fa-f:]{64,95})$",
            re.MULTILINE,
        ),
    )
    _mismatch(mismatches, "apk.signer_count", 1, len(apk_digests))
    if len(apk_digests) == 1:
        _require(
            mismatches,
            apk_digests[0] == normalized_expected_certificate,
            "apk.signingCertificateSha256",
            "protected release certificate",
            "different certificate"
            if apk_digests[0] != normalized_expected_certificate
            else "protected release certificate",
        )

    apk_package = _read_single_line(apk_package_report)
    apk_version_name = _read_single_line(apk_version_name_report)
    apk_version_code = _read_single_line(apk_version_code_report)
    _mismatch(mismatches, "apk.packageName", PACKAGE_NAME, apk_package)
    _mismatch(mismatches, "apk.version", version, apk_version_name)
    _mismatch(mismatches, "apk.versionCode", str(version_code), apk_version_code)

    aab_signature = aab_signature_report.read_text(encoding="utf-8")
    _require(
        mismatches,
        "jar verified." in aab_signature.splitlines(),
        "aab.jarsigner",
        "jar verified.",
        "verification marker absent",
    )
    aab_certificate = aab_certificate_report.read_text(encoding="utf-8")
    aab_digests = _certificate_digests(
        aab_certificate,
        re.compile(r"^[ \t]*SHA256: ([0-9A-Fa-f:]{64,95})$", re.MULTILINE),
    )
    _mismatch(mismatches, "aab.signer_count", 1, len(aab_digests))
    if len(aab_digests) == 1:
        _require(
            mismatches,
            aab_digests[0] == normalized_expected_certificate,
            "aab.signingCertificateSha256",
            "protected release certificate",
            "different certificate"
            if aab_digests[0] != normalized_expected_certificate
            else "protected release certificate",
        )
    if mismatches:
        raise VerificationFailure(mismatches)

    return {
        "assets": observed_assets,
        "application": {
            "package_name": PACKAGE_NAME,
            "version": version,
            "version_code": version_code,
        },
        "signature_identity": {
            "certificate_sha256": normalized_expected_certificate,
            "manifest": "exact",
            "apk": "exact",
            "aab": "exact",
        },
    }


def _print_failure(error: VerificationFailure) -> None:
    for mismatch in error.mismatches:
        field = mismatch.get("field")
        expected = json.dumps(
            mismatch.get("expected"), sort_keys=True, ensure_ascii=True
        )
        actual = json.dumps(mismatch.get("actual"), sort_keys=True, ensure_ascii=True)
        print(
            f"::error title=Android post-publish mismatch::field={field} expected={expected} actual={actual}",
            file=sys.stderr,
        )


def _state_command(args: argparse.Namespace) -> None:
    state = validate_release_state(
        ownership=_load_json(args.ownership),
        latest_release=_load_json(args.latest_release),
        published_release=_load_json(args.published_release),
        published_by_tag=_load_json(args.published_by_tag),
        tag_ref=_load_json(args.tag_ref),
        repository=args.repository,
        source_run_id=args.source_run_id,
        source_run_attempt=args.source_run_attempt,
        tag=args.tag,
        commit=args.commit,
    )
    _write_json(args.output, state)


def _complete_command(args: argparse.Namespace) -> None:
    state = _load_json(args.state)
    source_run = _load_json(args.source_run_metadata)
    ownership_artifact = _load_json(args.ownership_artifact_metadata)
    validate_source_evidence(
        state=state,
        source_run=source_run,
        ownership_artifact=ownership_artifact,
    )
    byte_evidence = verify_downloaded_release(
        state=state,
        asset_dir=args.asset_dir,
        expected_certificate_sha256=args.expected_certificate_sha256,
        apk_signature_report=args.apk_signature_report,
        apk_package_report=args.apk_package_report,
        apk_version_name_report=args.apk_version_name_report,
        apk_version_code_report=args.apk_version_code_report,
        aab_signature_report=args.aab_signature_report,
        aab_certificate_report=args.aab_certificate_report,
    )
    run_id = args.verification_run_id
    run_attempt = args.verification_run_attempt
    commit = args.verification_commit
    mismatches: list[dict[str, Any]] = []
    _require(
        mismatches,
        run_id.isdigit() and int(run_id) > 0,
        "verification_run_id",
        "positive integer string",
        run_id,
    )
    _require(
        mismatches,
        run_attempt.isdigit() and int(run_attempt) > 0,
        "verification_run_attempt",
        "positive integer string",
        run_attempt,
    )
    _require(
        mismatches,
        HEX_40.fullmatch(commit) is not None,
        "verification_commit",
        "40 lowercase hex",
        commit,
    )
    if mismatches:
        raise VerificationFailure(mismatches)

    evidence = {
        "schema": EVIDENCE_SCHEMA,
        "repository": state["repository"],
        "verified_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "read_only": True,
        "verification_run": {
            "run_id": run_id,
            "run_attempt": run_attempt,
            "commit": commit,
        },
        "source_run": {
            "run_id": state["source_run_id"],
            "run_attempt": state["source_run_attempt"],
            "commit": state["source_commit"],
            "workflow": ".github/workflows/mobile-release.yml",
            "conclusion": source_run["conclusion"],
            "ownership_artifact": {
                "id": ownership_artifact["id"],
                "name": ownership_artifact["name"],
                "size_in_bytes": ownership_artifact["size_in_bytes"],
                "digest": ownership_artifact["digest"],
            },
        },
        "target": {
            **state["target"],
            "assets": byte_evidence["assets"],
        },
        "application": byte_evidence["application"],
        "signature_identity": byte_evidence["signature_identity"],
        "verification": {
            "source_run_and_ownership_receipt": "exact",
            "release_id_latest_immutable_and_tag_commit": "exact",
            "public_asset_urls": "exact",
            "anonymous_asset_bytes_and_digests": "exact",
            "checksum_closure": "exact",
            "manifest_identity": "exact",
            "apk_identity_and_signer": "exact",
            "aab_signer": "exact",
        },
    }
    _write_json(args.output, evidence)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Verify an immutable Android Release without mutation."
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    state = subparsers.add_parser(
        "state", help="Validate receipt-owned public API state and URLs."
    )
    state.add_argument("--ownership", type=Path, required=True)
    state.add_argument("--latest-release", type=Path, required=True)
    state.add_argument("--published-release", type=Path, required=True)
    state.add_argument("--published-by-tag", type=Path, required=True)
    state.add_argument("--tag-ref", type=Path, required=True)
    state.add_argument("--repository", required=True)
    state.add_argument("--source-run-id", required=True)
    state.add_argument("--source-run-attempt", required=True)
    state.add_argument("--tag", required=True)
    state.add_argument("--commit", required=True)
    state.add_argument("--output", type=Path, required=True)
    state.set_defaults(handler=_state_command)

    complete = subparsers.add_parser(
        "complete", help="Verify anonymous bytes, identities, and source evidence."
    )
    complete.add_argument("--state", type=Path, required=True)
    complete.add_argument("--source-run-metadata", type=Path, required=True)
    complete.add_argument("--ownership-artifact-metadata", type=Path, required=True)
    complete.add_argument("--asset-dir", type=Path, required=True)
    complete.add_argument("--expected-certificate-sha256", required=True)
    complete.add_argument("--apk-signature-report", type=Path, required=True)
    complete.add_argument("--apk-package-report", type=Path, required=True)
    complete.add_argument("--apk-version-name-report", type=Path, required=True)
    complete.add_argument("--apk-version-code-report", type=Path, required=True)
    complete.add_argument("--aab-signature-report", type=Path, required=True)
    complete.add_argument("--aab-certificate-report", type=Path, required=True)
    complete.add_argument("--verification-run-id", required=True)
    complete.add_argument("--verification-run-attempt", required=True)
    complete.add_argument("--verification-commit", required=True)
    complete.add_argument("--output", type=Path, required=True)
    complete.set_defaults(handler=_complete_command)
    return parser


def main() -> int:
    args = _parser().parse_args()
    try:
        args.handler(args)
    except VerificationFailure as error:
        _print_failure(error)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
