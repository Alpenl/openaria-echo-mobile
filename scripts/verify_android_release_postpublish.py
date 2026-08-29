from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import http.client
import json
import os
import re
import secrets
import stat
import struct
import subprocess
import sys
import tempfile
import time
import zipfile
from contextlib import nullcontext
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Any, BinaryIO
from urllib.parse import urljoin, urlsplit, urlunsplit

REPOSITORY = "Alpenl/openaria-echo-mobile"
PACKAGE_NAME = "com.openaria.openaria_echo_mobile"
OWNERSHIP_SCHEMA = "openaria.mobile.release-ownership.v1"
STATE_SCHEMA = "openaria.mobile.release-post-publish-state.v2"
EVIDENCE_SCHEMA = "openaria.mobile.read-only-post-publish-verification.v2"
AAB_EVIDENCE_SCHEMA = "openaria.mobile.aab-strict-verification.v1"
SOURCE_WORKFLOW = ".github/workflows/mobile-release.yml"
SOURCE_WORKFLOW_NAME = "Mobile Release"
BUILD_JOB = "Build Android release"
ASSEMBLE_JOB = "Assemble release"
HEX_40 = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
SHA256_DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")
RELEASE_TAG = re.compile(r"^v([0-9]+\.[0-9]+\.[0-9]+)$")
DOWNLOAD_CHUNK_BYTES = 64 * 1024
DOWNLOAD_MAX_REDIRECTS = 5
DOWNLOAD_WORKER_FLAG = "--internal-anonymous-download-worker"
AAB_MAX_ENTRY_COUNT = 4096
AAB_MAX_ARCHIVE_BYTES = 256 * 1024 * 1024
AAB_MAX_CENTRAL_DIRECTORY_BYTES = 16 * 1024 * 1024
AAB_MAX_ZIP64_EOCD_BYTES = 4096
AAB_MAX_ENTRY_UNCOMPRESSED_BYTES = 64 * 1024 * 1024
AAB_MAX_TOTAL_UNCOMPRESSED_BYTES = 256 * 1024 * 1024
AAB_MAX_COMPRESSION_RATIO = 100.0
KEYTOOL_TIMEOUT_SECONDS = 30
JARSIGNER_TIMEOUT_SECONDS = 120


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
        ("target_commitish", commit),
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
    published_at = published_release.get("published_at")
    _require(
        mismatches,
        isinstance(published_at, str) and bool(published_at),
        "published.published_at",
        "non-empty timestamp",
        published_at,
    )
    _mismatch(
        mismatches,
        "published_by_tag.published_at",
        published_at,
        published_by_tag.get("published_at"),
    )
    _mismatch(
        mismatches,
        "latest.published_at",
        published_at,
        latest_release.get("published_at"),
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
            "published_at": published_at,
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


def _source_run_projection(source_run: dict[str, Any]) -> dict[str, Any]:
    def identity(value: Any) -> dict[str, Any]:
        if not isinstance(value, dict):
            return {"id": None, "login": None, "type": None}
        return {key: value.get(key) for key in ("id", "login", "type")}

    return {
        "id": source_run.get("id"),
        "run_attempt": source_run.get("run_attempt"),
        "head_sha": source_run.get("head_sha"),
        "head_branch": source_run.get("head_branch"),
        "path": source_run.get("path"),
        "event": source_run.get("event"),
        "status": source_run.get("status"),
        "conclusion": source_run.get("conclusion"),
        "created_at": source_run.get("created_at"),
        "actor": identity(source_run.get("actor")),
        "triggering_actor": identity(source_run.get("triggering_actor")),
        "repository": {
            "id": _at(source_run, "repository", "id"),
            "full_name": _at(source_run, "repository", "full_name"),
            "owner": identity(_at(source_run, "repository", "owner")),
        },
        "head_repository": {
            "id": _at(source_run, "head_repository", "id"),
            "full_name": _at(source_run, "head_repository", "full_name"),
        },
    }


def _repository_projection(repository_metadata: dict[str, Any]) -> dict[str, Any]:
    owner = repository_metadata.get("owner")
    return {
        "id": repository_metadata.get("id"),
        "full_name": repository_metadata.get("full_name"),
        "default_branch": repository_metadata.get("default_branch"),
        "owner": {
            key: owner.get(key) if isinstance(owner, dict) else None
            for key in ("id", "login", "type")
        },
    }


def _source_jobs_projection(source_jobs: dict[str, Any]) -> list[dict[str, Any]]:
    jobs = source_jobs.get("jobs")
    if not isinstance(jobs, list):
        return []
    projected: list[dict[str, Any]] = []
    for job in jobs:
        if not isinstance(job, dict):
            continue
        steps = job.get("steps")
        projected.append(
            {
                "id": job.get("id"),
                "name": job.get("name"),
                "run_id": job.get("run_id"),
                "run_attempt": job.get("run_attempt"),
                "run_url": job.get("run_url"),
                "workflow_name": job.get("workflow_name"),
                "head_branch": job.get("head_branch"),
                "head_sha": job.get("head_sha"),
                "status": job.get("status"),
                "conclusion": job.get("conclusion"),
                "steps": [
                    {
                        "number": step.get("number"),
                        "name": step.get("name"),
                        "status": step.get("status"),
                        "conclusion": step.get("conclusion"),
                        "started_at": step.get("started_at"),
                        "completed_at": step.get("completed_at"),
                    }
                    for step in steps
                    if isinstance(step, dict)
                ]
                if isinstance(steps, list)
                else steps,
            }
        )
    return sorted(projected, key=lambda job: str(job.get("name")))


def _ownership_artifact_projection(
    ownership_artifact: dict[str, Any],
) -> dict[str, Any]:
    return {
        "id": ownership_artifact.get("id"),
        "name": ownership_artifact.get("name"),
        "size_in_bytes": ownership_artifact.get("size_in_bytes"),
        "digest": ownership_artifact.get("digest"),
        "expired": ownership_artifact.get("expired"),
        "created_at": ownership_artifact.get("created_at"),
        "expires_at": ownership_artifact.get("expires_at"),
        "workflow_run": {
            "id": _at(ownership_artifact, "workflow_run", "id"),
            "repository_id": _at(ownership_artifact, "workflow_run", "repository_id"),
            "head_repository_id": _at(
                ownership_artifact, "workflow_run", "head_repository_id"
            ),
            "head_branch": _at(ownership_artifact, "workflow_run", "head_branch"),
            "head_sha": _at(ownership_artifact, "workflow_run", "head_sha"),
        },
    }


def _jobs_by_name(
    source_jobs: dict[str, Any], mismatches: list[dict[str, Any]]
) -> dict[str, dict[str, Any]]:
    jobs = source_jobs.get("jobs")
    if not isinstance(jobs, list):
        mismatches.append(
            {
                "field": "source_jobs.jobs",
                "expected": "array",
                "actual": type(jobs).__name__,
            }
        )
        return {}
    by_name: dict[str, dict[str, Any]] = {}
    duplicates: list[str] = []
    invalid_entries = 0
    for job in jobs:
        if not isinstance(job, dict) or not isinstance(job.get("name"), str):
            invalid_entries += 1
            continue
        name = job["name"]
        if name in by_name:
            duplicates.append(name)
        by_name[name] = job
    _mismatch(mismatches, "source_jobs.invalid_entries", 0, invalid_entries)
    _mismatch(mismatches, "source_jobs.duplicate_names", [], sorted(set(duplicates)))
    _mismatch(
        mismatches,
        "source_jobs.names",
        sorted((BUILD_JOB, ASSEMBLE_JOB)),
        sorted(by_name),
    )
    return by_name


def _timestamp(
    value: Any, field: str, mismatches: list[dict[str, Any]]
) -> datetime | None:
    if not isinstance(value, str):
        mismatches.append(
            {"field": field, "expected": "RFC3339 timestamp", "actual": value}
        )
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        parsed = None
    if parsed is None or parsed.tzinfo is None:
        mismatches.append(
            {"field": field, "expected": "RFC3339 timestamp", "actual": value}
        )
        return None
    return parsed.astimezone(timezone.utc)


def _validate_required_steps(
    *,
    job: dict[str, Any],
    job_field: str,
    required: dict[str, str],
    mismatches: list[dict[str, Any]],
) -> dict[str, dict[str, Any]]:
    steps = job.get("steps")
    if not isinstance(steps, list):
        mismatches.append(
            {
                "field": f"{job_field}.steps",
                "expected": "array",
                "actual": type(steps).__name__,
            }
        )
        return {}
    by_name: dict[str, list[dict[str, Any]]] = {}
    for step in steps:
        if isinstance(step, dict) and isinstance(step.get("name"), str):
            by_name.setdefault(step["name"], []).append(step)
    for name, expected_conclusion in required.items():
        matches = by_name.get(name, [])
        _mismatch(mismatches, f"{job_field}.steps[{name}].count", 1, len(matches))
        if len(matches) != 1:
            continue
        step = matches[0]
        _mismatch(
            mismatches,
            f"{job_field}.steps[{name}].status",
            "completed",
            step.get("status"),
        )
        _mismatch(
            mismatches,
            f"{job_field}.steps[{name}].conclusion",
            expected_conclusion,
            step.get("conclusion"),
        )
        _require(
            mismatches,
            isinstance(step.get("number"), int) and step["number"] > 0,
            f"{job_field}.steps[{name}].number",
            "positive integer",
            step.get("number"),
        )
        _timestamp(
            step.get("started_at"),
            f"{job_field}.steps[{name}].started_at",
            mismatches,
        )
        _timestamp(
            step.get("completed_at"),
            f"{job_field}.steps[{name}].completed_at",
            mismatches,
        )
    return {
        name: matches[0]
        for name in required
        if len(matches := by_name.get(name, [])) == 1
    }


def validate_source_evidence(
    *,
    state: dict[str, Any],
    ownership: dict[str, Any],
    repository_metadata: dict[str, Any],
    source_run: dict[str, Any],
    source_jobs: dict[str, Any],
    ownership_artifact: dict[str, Any],
    default_branch: str,
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
        ("source_run.head_branch", default_branch, source_run.get("head_branch")),
        ("source_run.path", SOURCE_WORKFLOW, source_run.get("path")),
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
            "ownership_artifact.workflow_run.head_branch",
            default_branch,
            _at(ownership_artifact, "workflow_run", "head_branch"),
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
    artifact_created_at = _timestamp(
        ownership_artifact.get("created_at"),
        "ownership_artifact.created_at",
        mismatches,
    )
    _timestamp(
        ownership_artifact.get("expires_at"),
        "ownership_artifact.expires_at",
        mismatches,
    )
    repository_id = _at(source_run, "repository", "id")
    head_repository_id = _at(source_run, "head_repository", "id")
    _mismatch(
        mismatches,
        "source_run.head_repository.id",
        repository_id,
        head_repository_id,
    )
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

    repository_owner = _at(source_run, "repository", "owner")
    actor = source_run.get("actor")
    triggering_actor = source_run.get("triggering_actor")
    _mismatch(
        mismatches,
        "repository",
        {
            "id": _at(source_run, "repository", "id"),
            "full_name": repository,
            "default_branch": default_branch,
            "owner": {
                key: repository_owner.get(key)
                if isinstance(repository_owner, dict)
                else None
                for key in ("id", "login", "type")
            },
        },
        _repository_projection(repository_metadata),
    )
    for identity_field in ("id", "login", "type"):
        expected_owner_identity = (
            repository_owner.get(identity_field)
            if isinstance(repository_owner, dict)
            else None
        )
        actual_actor_identity = (
            actor.get(identity_field) if isinstance(actor, dict) else None
        )
        actual_triggering_identity = (
            triggering_actor.get(identity_field)
            if isinstance(triggering_actor, dict)
            else None
        )
        _mismatch(
            mismatches,
            f"source_run.actor.{identity_field}",
            expected_owner_identity,
            actual_actor_identity,
        )
        _mismatch(
            mismatches,
            f"source_run.triggering_actor.{identity_field}",
            actual_actor_identity,
            actual_triggering_identity,
        )

    preflight = ownership.get("immutable_releases_preflight")
    if not isinstance(preflight, dict):
        mismatches.append(
            {
                "field": "ownership.immutable_releases_preflight",
                "expected": "object",
                "actual": type(preflight).__name__,
            }
        )
        preflight = {}
    actor_login = actor.get("login") if isinstance(actor, dict) else None
    for field, expected in (
        ("schema", "openaria.github.immutable-releases-preflight.v1"),
        ("repository", repository),
        ("actor", actor_login),
        ("source_commit", commit),
        ("default_branch", default_branch),
        ("default_branch_head", commit),
        ("release_tag", tag),
        ("run_created_at", source_run.get("created_at")),
        ("endpoint", f"GET /repos/{repository}/immutable-releases"),
        ("api_version", "2026-03-10"),
        ("enabled", True),
    ):
        _mismatch(
            mismatches,
            f"ownership.immutable_releases_preflight.{field}",
            expected,
            preflight.get(field),
        )
    _mismatch(
        mismatches,
        "ownership.immutable_releases_preflight.response.enabled",
        True,
        _at(preflight, "response", "enabled"),
    )
    response = preflight.get("response")
    _require(
        mismatches,
        isinstance(response, dict)
        and sorted(response) == ["enabled", "enforced_by_owner"]
        and response.get("enabled") is True
        and isinstance(response.get("enforced_by_owner"), bool),
        "ownership.immutable_releases_preflight.response",
        "exact enabled/enforced_by_owner object",
        response,
    )
    legacy_authorization = preflight.get("allow_legacy_baseline_bootstrap")
    _require(
        mismatches,
        isinstance(legacy_authorization, bool),
        "ownership.immutable_releases_preflight.allow_legacy_baseline_bootstrap",
        "boolean",
        legacy_authorization,
    )
    _mismatch(
        mismatches,
        "ownership.immutable_releases_preflight.allow_legacy_baseline_bootstrap",
        _at(ownership, "baseline", "legacy_bootstrap_authorized"),
        legacy_authorization,
    )

    raw_base64 = preflight.get("response_raw_base64")
    raw_response: bytes | None = None
    if isinstance(raw_base64, str):
        try:
            raw_response = base64.b64decode(raw_base64, validate=True)
        except (binascii.Error, ValueError):
            raw_response = None
    _require(
        mismatches,
        raw_response is not None,
        "ownership.immutable_releases_preflight.response_raw_base64",
        "valid canonical base64",
        "invalid" if raw_response is None else "valid",
    )
    response_sha256 = preflight.get("response_sha256")
    _require(
        mismatches,
        isinstance(response_sha256, str)
        and SHA256.fullmatch(response_sha256) is not None,
        "ownership.immutable_releases_preflight.response_sha256",
        "64 lowercase hex",
        response_sha256,
    )
    if raw_response is not None:
        _mismatch(
            mismatches,
            "ownership.immutable_releases_preflight.response_sha256",
            hashlib.sha256(raw_response).hexdigest(),
            response_sha256,
        )
        try:
            decoded_response = json.loads(raw_response)
        except json.JSONDecodeError:
            decoded_response = None
        _mismatch(
            mismatches,
            "ownership.immutable_releases_preflight.response_raw",
            response,
            decoded_response,
        )

    checked_at = _timestamp(
        preflight.get("checked_at"),
        "ownership.immutable_releases_preflight.checked_at",
        mismatches,
    )
    run_created_at = _timestamp(
        source_run.get("created_at"), "source_run.created_at", mismatches
    )
    if checked_at is not None and run_created_at is not None:
        dispatch_delay = (run_created_at - checked_at).total_seconds()
        _require(
            mismatches,
            -60 <= dispatch_delay <= 300,
            "ownership.immutable_releases_preflight.dispatch_delay_seconds",
            "between -60 and 300 inclusive",
            dispatch_delay,
        )

    jobs = _jobs_by_name(source_jobs, mismatches)
    run_url = f"https://api.github.com/repos/{repository}/actions/runs/{run_id}"
    for job_name, expected_conclusion in (
        (BUILD_JOB, "success"),
        (ASSEMBLE_JOB, "failure"),
    ):
        job = jobs.get(job_name, {})
        job_field = f"source_jobs[{job_name}]"
        for field, expected in (
            ("run_id", run_id),
            ("run_attempt", run_attempt),
            ("run_url", run_url),
            ("workflow_name", SOURCE_WORKFLOW_NAME),
            ("head_branch", default_branch),
            ("head_sha", commit),
            ("status", "completed"),
            ("conclusion", expected_conclusion),
        ):
            _mismatch(mismatches, f"{job_field}.{field}", expected, job.get(field))

    _validate_required_steps(
        job=jobs.get(BUILD_JOB, {}),
        job_field=f"source_jobs[{BUILD_JOB}]",
        required={
            "Validate release metadata": "success",
            "Upload immutable-release preflight evidence": "success",
            "Build Android release artifacts": "success",
            "Verify APK identity and generate update manifest": "success",
            "Upload Android artifacts": "success",
        },
        mismatches=mismatches,
    )
    assemble_steps = _validate_required_steps(
        job=jobs.get(ASSEMBLE_JOB, {}),
        job_field=f"source_jobs[{ASSEMBLE_JOB}]",
        required={
            "Reject stale release rerun preflight": "success",
            "Upload exact-run pre-publish ownership receipt": "success",
            "Upgrade previous production through the staged production updater": "success",
            "Upload pre-publish in-app upgrade evidence": "success",
            "Publish the receipt-owned GitHub Release": "success",
            "Post-publish verification": "failure",
        },
        mismatches=mismatches,
    )
    ordered_step_names = (
        "Upload exact-run pre-publish ownership receipt",
        "Upgrade previous production through the staged production updater",
        "Upload pre-publish in-app upgrade evidence",
        "Publish the receipt-owned GitHub Release",
        "Post-publish verification",
    )
    step_numbers = [
        assemble_steps[name].get("number")
        for name in ordered_step_names
        if name in assemble_steps
    ]
    _require(
        mismatches,
        len(step_numbers) == len(ordered_step_names)
        and all(isinstance(number, int) for number in step_numbers)
        and step_numbers == sorted(step_numbers)
        and len(set(step_numbers)) == len(step_numbers),
        f"source_jobs[{ASSEMBLE_JOB}].publication_step_order",
        list(ordered_step_names),
        step_numbers,
    )

    publish_step = assemble_steps.get("Publish the receipt-owned GitHub Release")
    if publish_step is not None:
        publish_started_at = _timestamp(
            publish_step.get("started_at"),
            f"source_jobs[{ASSEMBLE_JOB}].steps[Publish the receipt-owned GitHub Release].started_at",
            mismatches,
        )
        publish_completed_at = _timestamp(
            publish_step.get("completed_at"),
            f"source_jobs[{ASSEMBLE_JOB}].steps[Publish the receipt-owned GitHub Release].completed_at",
            mismatches,
        )
        published_at = _timestamp(
            _at(state, "target", "published_at"),
            "state.target.published_at",
            mismatches,
        )
        if (
            publish_started_at is not None
            and publish_completed_at is not None
            and published_at is not None
        ):
            _require(
                mismatches,
                publish_started_at <= published_at <= publish_completed_at,
                "state.target.published_at",
                "inside successful receipt-owned publication step",
                _at(state, "target", "published_at"),
            )

    receipt_step = assemble_steps.get("Upload exact-run pre-publish ownership receipt")
    if receipt_step is not None and artifact_created_at is not None:
        receipt_started_at = _timestamp(
            receipt_step.get("started_at"),
            f"source_jobs[{ASSEMBLE_JOB}].steps[Upload exact-run pre-publish ownership receipt].started_at",
            mismatches,
        )
        receipt_completed_at = _timestamp(
            receipt_step.get("completed_at"),
            f"source_jobs[{ASSEMBLE_JOB}].steps[Upload exact-run pre-publish ownership receipt].completed_at",
            mismatches,
        )
        if receipt_started_at is not None and receipt_completed_at is not None:
            _require(
                mismatches,
                receipt_started_at <= artifact_created_at <= receipt_completed_at,
                "ownership_artifact.created_at",
                "inside successful ownership receipt upload step",
                ownership_artifact.get("created_at"),
            )
    if mismatches:
        raise VerificationFailure(mismatches)


def validate_final_recheck(
    *,
    initial_state: dict[str, Any],
    final_state: dict[str, Any],
    initial_repository: dict[str, Any],
    final_repository: dict[str, Any],
    initial_source_run: dict[str, Any],
    final_source_run: dict[str, Any],
    initial_source_jobs: dict[str, Any],
    final_source_jobs: dict[str, Any],
    initial_ownership_artifact: dict[str, Any],
    final_ownership_artifact: dict[str, Any],
) -> None:
    mismatches: list[dict[str, Any]] = []
    _mismatch(mismatches, "final_recheck.release_state", initial_state, final_state)
    _mismatch(
        mismatches,
        "final_recheck.repository",
        _repository_projection(initial_repository),
        _repository_projection(final_repository),
    )
    _mismatch(
        mismatches,
        "final_recheck.source_run",
        _source_run_projection(initial_source_run),
        _source_run_projection(final_source_run),
    )
    _mismatch(
        mismatches,
        "final_recheck.source_jobs",
        _source_jobs_projection(initial_source_jobs),
        _source_jobs_projection(final_source_jobs),
    )
    _mismatch(
        mismatches,
        "final_recheck.ownership_artifact",
        _ownership_artifact_projection(initial_ownership_artifact),
        _ownership_artifact_projection(final_ownership_artifact),
    )
    if mismatches:
        raise VerificationFailure(mismatches)


def _download_failure(field: str, expected: Any, actual: Any) -> VerificationFailure:
    return VerificationFailure(
        [{"field": field, "expected": expected, "actual": actual}]
    )


def _redacted_download_url(url: str) -> str:
    parsed = urlsplit(url)
    return urlunsplit((parsed.scheme, parsed.netloc, parsed.path, "", ""))


def _remaining_timeout(
    *, deadline: float, phase_timeout: float, asset_name: str, phase: str
) -> float:
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise _download_failure(
            f"anonymous_downloads[{asset_name}].total_timeout_seconds",
            "download completed before the total deadline",
            "expired",
        )
    return max(0.001, min(phase_timeout, remaining))


def _raise_download_timeout(
    *, asset_name: str, phase: str, phase_timeout: float, deadline: float
) -> None:
    if time.monotonic() >= deadline:
        raise _download_failure(
            f"anonymous_downloads[{asset_name}].total_timeout_seconds",
            "download completed before the total deadline",
            "expired",
        )
    raise _download_failure(
        f"anonymous_downloads[{asset_name}].{phase}_timeout_seconds",
        f"each {phase} operation completed within {phase_timeout:g} seconds",
        "expired",
    )


def _validate_anonymous_download_arguments(
    *,
    asset_name: str,
    expected_size: int,
    expected_digest: str,
    connect_timeout_seconds: float,
    body_timeout_seconds: float,
    total_timeout_seconds: float,
) -> None:
    mismatches: list[dict[str, Any]] = []
    _require(
        mismatches,
        Path(asset_name).name == asset_name and asset_name not in ("", ".", ".."),
        "anonymous_download.asset_name",
        "single safe filename",
        asset_name,
    )
    _require(
        mismatches,
        isinstance(expected_size, int)
        and not isinstance(expected_size, bool)
        and expected_size > 0,
        f"anonymous_downloads[{asset_name}].expected_size",
        "positive integer",
        expected_size,
    )
    _require(
        mismatches,
        isinstance(expected_digest, str)
        and SHA256_DIGEST.fullmatch(expected_digest) is not None,
        f"anonymous_downloads[{asset_name}].expected_digest",
        "sha256:<64 lowercase hex>",
        expected_digest,
    )
    for field, value in (
        ("connect_timeout_seconds", connect_timeout_seconds),
        ("body_timeout_seconds", body_timeout_seconds),
        ("total_timeout_seconds", total_timeout_seconds),
    ):
        _require(
            mismatches,
            isinstance(value, (int, float))
            and not isinstance(value, bool)
            and value > 0,
            f"anonymous_downloads[{asset_name}].{field}",
            "positive number",
            value,
        )
    if all(
        isinstance(value, (int, float)) and not isinstance(value, bool) and value > 0
        for value in (
            connect_timeout_seconds,
            body_timeout_seconds,
            total_timeout_seconds,
        )
    ):
        _require(
            mismatches,
            connect_timeout_seconds <= total_timeout_seconds,
            f"anonymous_downloads[{asset_name}].connect_timeout_seconds",
            f"<= total timeout {total_timeout_seconds:g}",
            connect_timeout_seconds,
        )
        _require(
            mismatches,
            body_timeout_seconds <= total_timeout_seconds,
            f"anonymous_downloads[{asset_name}].body_timeout_seconds",
            f"<= total timeout {total_timeout_seconds:g}",
            body_timeout_seconds,
        )
    if mismatches:
        raise VerificationFailure(mismatches)


def _download_anonymous_asset_in_worker(
    *,
    asset_name: str,
    url: str,
    partial_output: Path,
    expected_size: int,
    expected_digest: str,
    connect_timeout_seconds: float,
    body_timeout_seconds: float,
    total_timeout_seconds: float,
    allow_http_for_tests: bool = False,
) -> dict[str, Any]:
    _validate_anonymous_download_arguments(
        asset_name=asset_name,
        expected_size=expected_size,
        expected_digest=expected_digest,
        connect_timeout_seconds=connect_timeout_seconds,
        body_timeout_seconds=body_timeout_seconds,
        total_timeout_seconds=total_timeout_seconds,
    )

    partial_output.parent.mkdir(parents=True, exist_ok=True)
    try:
        partial_output.unlink(missing_ok=True)
    except OSError as error:
        raise _download_failure(
            f"anonymous_downloads[{asset_name}].partial_output",
            "replaceable partial path",
            type(error).__name__,
        ) from error

    started = time.monotonic()
    deadline = started + total_timeout_seconds
    current_url = url
    redirects: list[str] = []
    connection: http.client.HTTPConnection | None = None
    response: http.client.HTTPResponse | None = None
    phase = "connect"
    observed_size = 0
    observed_digest = ""
    content_length: int | None = None
    request_headers = {
        "Accept": "application/octet-stream",
        "Accept-Encoding": "identity",
        "User-Agent": "OpenAria-read-only-release-verifier/1",
    }

    def remove_partial() -> None:
        try:
            partial_output.unlink(missing_ok=True)
        except OSError:
            pass

    try:
        while True:
            try:
                parsed = urlsplit(current_url)
                parsed_port = parsed.port
            except ValueError as error:
                raise _download_failure(
                    f"anonymous_downloads[{asset_name}].url",
                    "valid URL without an invalid port",
                    "invalid",
                ) from error
            allowed_schemes = {"https"}
            if allow_http_for_tests:
                allowed_schemes.add("http")
            if (
                parsed.scheme not in allowed_schemes
                or not parsed.hostname
                or parsed.username is not None
                or parsed.password is not None
                or parsed.fragment
            ):
                raise _download_failure(
                    f"anonymous_downloads[{asset_name}].url",
                    "anonymous HTTPS URL without credentials or fragment",
                    current_url,
                )

            phase = "connect"
            connect_timeout = _remaining_timeout(
                deadline=deadline,
                phase_timeout=connect_timeout_seconds,
                asset_name=asset_name,
                phase=phase,
            )
            connection_type: type[http.client.HTTPConnection]
            if parsed.scheme == "https":
                connection_type = http.client.HTTPSConnection
            else:
                connection_type = http.client.HTTPConnection
            connection = connection_type(
                parsed.hostname, parsed_port, timeout=connect_timeout
            )
            request_target = urlunsplit(("", "", parsed.path or "/", parsed.query, ""))
            try:
                connection.request("GET", request_target, headers=request_headers)
                if connection.sock is not None:
                    connection.sock.settimeout(
                        _remaining_timeout(
                            deadline=deadline,
                            phase_timeout=connect_timeout_seconds,
                            asset_name=asset_name,
                            phase=phase,
                        )
                    )
                response = connection.getresponse()
            except TimeoutError:
                _raise_download_timeout(
                    asset_name=asset_name,
                    phase=phase,
                    phase_timeout=connect_timeout_seconds,
                    deadline=deadline,
                )

            if response.status in (301, 302, 303, 307, 308):
                location = response.getheader("Location")
                if not location:
                    raise _download_failure(
                        f"anonymous_downloads[{asset_name}].redirect_location",
                        "non-empty Location header",
                        location,
                    )
                if len(redirects) >= DOWNLOAD_MAX_REDIRECTS:
                    raise _download_failure(
                        f"anonymous_downloads[{asset_name}].redirect_count",
                        f"<= {DOWNLOAD_MAX_REDIRECTS}",
                        len(redirects) + 1,
                    )
                next_url = urljoin(current_url, location)
                redirects.append(next_url)
                response.close()
                connection.close()
                response = None
                connection = None
                current_url = next_url
                continue
            if response.status != 200:
                raise _download_failure(
                    f"anonymous_downloads[{asset_name}].http_status",
                    200,
                    response.status,
                )
            break

        content_encoding = response.getheader("Content-Encoding")
        if content_encoding is not None and content_encoding.lower() != "identity":
            raise _download_failure(
                f"anonymous_downloads[{asset_name}].content_encoding",
                "identity or absent",
                content_encoding,
            )
        content_length_header = response.getheader("Content-Length")
        if content_length_header is not None:
            try:
                content_length = int(content_length_header)
            except ValueError as error:
                raise _download_failure(
                    f"anonymous_downloads[{asset_name}].content_length",
                    expected_size,
                    content_length_header,
                ) from error
            if content_length != expected_size:
                raise _download_failure(
                    f"anonymous_downloads[{asset_name}].content_length",
                    expected_size,
                    content_length,
                )

        phase = "body"
        digest = hashlib.sha256()
        with partial_output.open("xb") as output:
            while True:
                if connection.sock is not None:
                    connection.sock.settimeout(
                        _remaining_timeout(
                            deadline=deadline,
                            phase_timeout=body_timeout_seconds,
                            asset_name=asset_name,
                            phase=phase,
                        )
                    )
                read_limit = min(
                    DOWNLOAD_CHUNK_BYTES, expected_size - observed_size + 1
                )
                try:
                    # read1 performs at most one payload recv. The supervising
                    # process still enforces the absolute attempt deadline,
                    # including chunk framing and all pre-body phases.
                    chunk = response.read1(read_limit)
                except TimeoutError:
                    _raise_download_timeout(
                        asset_name=asset_name,
                        phase=phase,
                        phase_timeout=body_timeout_seconds,
                        deadline=deadline,
                    )
                if not chunk:
                    break
                next_size = observed_size + len(chunk)
                if next_size > expected_size:
                    raise _download_failure(
                        f"anonymous_downloads[{asset_name}].max_bytes",
                        expected_size,
                        f">={next_size}",
                    )
                output.write(chunk)
                digest.update(chunk)
                observed_size = next_size

        observed_digest = f"sha256:{digest.hexdigest()}"
        if observed_size != expected_size:
            raise _download_failure(
                f"anonymous_downloads[{asset_name}].size",
                expected_size,
                observed_size,
            )
        if observed_digest != expected_digest:
            raise _download_failure(
                f"anonymous_downloads[{asset_name}].digest",
                expected_digest,
                observed_digest,
            )
        if partial_output.stat().st_size != expected_size:
            raise _download_failure(
                f"anonymous_downloads[{asset_name}].partial_size",
                expected_size,
                partial_output.stat().st_size,
            )
    except VerificationFailure:
        remove_partial()
        raise
    except TimeoutError:
        remove_partial()
        _raise_download_timeout(
            asset_name=asset_name,
            phase=phase,
            phase_timeout=(
                connect_timeout_seconds if phase == "connect" else body_timeout_seconds
            ),
            deadline=deadline,
        )
    except (OSError, http.client.HTTPException) as error:
        remove_partial()
        raise _download_failure(
            f"anonymous_downloads[{asset_name}].{phase}_network_error",
            "successful anonymous transfer",
            type(error).__name__,
        ) from error
    finally:
        if response is not None:
            response.close()
        if connection is not None:
            connection.close()

    return {
        "schema": "openaria.mobile.bounded-anonymous-download.v1",
        "asset": {
            "name": asset_name,
            "url": url,
            "size": observed_size,
            "digest": observed_digest,
        },
        "anonymous": True,
        "partial_verified_before_publish": True,
        "limits": {
            "hard_max_bytes": expected_size,
            "chunk_bytes": DOWNLOAD_CHUNK_BYTES,
            "connect_timeout_seconds": connect_timeout_seconds,
            "body_timeout_seconds": body_timeout_seconds,
            "total_timeout_seconds": total_timeout_seconds,
            "max_redirects": DOWNLOAD_MAX_REDIRECTS,
        },
        "response": {
            "content_length": content_length,
            "redirect_count": len(redirects),
            "redirects": [_redacted_download_url(value) for value in redirects],
            "final_url": _redacted_download_url(current_url),
            "signed_query_parameters_redacted": any(
                urlsplit(value).query for value in (*redirects, current_url)
            ),
        },
        "request_headers": sorted(request_headers),
        "elapsed_seconds": round(time.monotonic() - started, 6),
    }


def _anonymous_download_worker_main() -> int:
    try:
        request = json.load(sys.stdin)
        if not isinstance(request, dict):
            raise TypeError("worker request must be an object")
        report = _download_anonymous_asset_in_worker(
            asset_name=request["asset_name"],
            url=request["url"],
            partial_output=Path(request["partial_output"]),
            expected_size=request["expected_size"],
            expected_digest=request["expected_digest"],
            connect_timeout_seconds=request["connect_timeout_seconds"],
            body_timeout_seconds=request["body_timeout_seconds"],
            total_timeout_seconds=request["total_timeout_seconds"],
            allow_http_for_tests=request["allow_http_for_tests"],
        )
        result: dict[str, Any] = {
            "schema": "openaria.mobile.anonymous-download-worker.v1",
            "status": "success",
            "report": report,
        }
    except VerificationFailure as error:
        result = {
            "schema": "openaria.mobile.anonymous-download-worker.v1",
            "status": "verification_failure",
            "mismatches": error.mismatches,
        }
    except (KeyError, TypeError, ValueError, OSError, json.JSONDecodeError) as error:
        result = {
            "schema": "openaria.mobile.anonymous-download-worker.v1",
            "status": "internal_error",
            "error_type": type(error).__name__,
        }
    json.dump(result, sys.stdout, sort_keys=True)
    sys.stdout.write("\n")
    sys.stdout.flush()
    return 0


def _kill_and_reap_download_worker(
    process: subprocess.Popen[str],
) -> tuple[str, str]:
    if process.poll() is None:
        process.kill()
    # SIGKILL closes the worker's only network connection. communicate() waits
    # for and reaps the direct child, so no timed-out resolver or socket keeps
    # running after this function returns.
    stdout, stderr = process.communicate()
    return stdout, stderr


def download_anonymous_asset(
    *,
    asset_name: str,
    url: str,
    partial_output: Path,
    expected_size: int,
    expected_digest: str,
    connect_timeout_seconds: float,
    body_timeout_seconds: float,
    total_timeout_seconds: float,
    allow_http_for_tests: bool = False,
) -> dict[str, Any]:
    _validate_anonymous_download_arguments(
        asset_name=asset_name,
        expected_size=expected_size,
        expected_digest=expected_digest,
        connect_timeout_seconds=connect_timeout_seconds,
        body_timeout_seconds=body_timeout_seconds,
        total_timeout_seconds=total_timeout_seconds,
    )

    def remove_partial() -> None:
        try:
            partial_output.unlink(missing_ok=True)
        except OSError:
            pass

    remove_partial()
    request = {
        "asset_name": asset_name,
        "url": url,
        "partial_output": str(partial_output),
        "expected_size": expected_size,
        "expected_digest": expected_digest,
        "connect_timeout_seconds": connect_timeout_seconds,
        "body_timeout_seconds": body_timeout_seconds,
        "total_timeout_seconds": total_timeout_seconds,
        "allow_http_for_tests": allow_http_for_tests,
    }
    environment = {
        "LANG": "C",
        "LC_ALL": "C",
        "PYTHONUTF8": "1",
    }
    python_executable = str(Path(sys.executable).resolve(strict=True))

    started = time.monotonic()
    deadline = started + total_timeout_seconds
    process: subprocess.Popen[str] | None = None
    try:
        process = subprocess.Popen(
            [python_executable, str(Path(__file__).resolve()), DOWNLOAD_WORKER_FLAG],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            env=environment,
            start_new_session=True,
        )
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            _kill_and_reap_download_worker(process)
            remove_partial()
            raise _download_failure(
                f"anonymous_downloads[{asset_name}].total_timeout_seconds",
                "complete attempt finished before the total deadline",
                "expired",
            )
        try:
            stdout, _stderr = process.communicate(
                json.dumps(request, sort_keys=True), timeout=remaining
            )
        except subprocess.TimeoutExpired:
            _kill_and_reap_download_worker(process)
            remove_partial()
            raise _download_failure(
                f"anonymous_downloads[{asset_name}].total_timeout_seconds",
                "complete attempt finished before the total deadline",
                "expired",
            ) from None
    except VerificationFailure:
        raise
    except OSError as error:
        if process is not None and process.poll() is None:
            _kill_and_reap_download_worker(process)
        remove_partial()
        raise _download_failure(
            f"anonymous_downloads[{asset_name}].worker_start",
            "isolated anonymous download worker started and was reaped",
            type(error).__name__,
        ) from error

    finished = time.monotonic()
    if finished > deadline:
        remove_partial()
        raise _download_failure(
            f"anonymous_downloads[{asset_name}].total_timeout_seconds",
            "complete attempt finished before the total deadline",
            "expired",
        )
    if process.returncode != 0:
        remove_partial()
        raise _download_failure(
            f"anonymous_downloads[{asset_name}].worker_exit",
            0,
            process.returncode,
        )
    try:
        result = json.loads(stdout)
    except json.JSONDecodeError as error:
        remove_partial()
        raise _download_failure(
            f"anonymous_downloads[{asset_name}].worker_protocol",
            "single valid JSON result",
            "invalid",
        ) from error
    if (
        not isinstance(result, dict)
        or result.get("schema") != "openaria.mobile.anonymous-download-worker.v1"
    ):
        remove_partial()
        raise _download_failure(
            f"anonymous_downloads[{asset_name}].worker_protocol",
            "openaria.mobile.anonymous-download-worker.v1 object",
            "invalid",
        )
    if result.get("status") == "verification_failure":
        mismatches = result.get("mismatches")
        remove_partial()
        if isinstance(mismatches, list) and all(
            isinstance(mismatch, dict) for mismatch in mismatches
        ):
            raise VerificationFailure(mismatches)
        raise _download_failure(
            f"anonymous_downloads[{asset_name}].worker_protocol",
            "field-level verification mismatches",
            "invalid",
        )
    if result.get("status") != "success" or not isinstance(result.get("report"), dict):
        remove_partial()
        raise _download_failure(
            f"anonymous_downloads[{asset_name}].worker_protocol",
            "successful bounded download result",
            result.get("status"),
        )

    report = result["report"]
    worker_elapsed = report.get("elapsed_seconds")
    report["elapsed_seconds"] = round(finished - started, 6)
    report["worker"] = {
        "isolated_process": True,
        "reaped": process.poll() is not None,
        "network_elapsed_seconds": worker_elapsed,
        "environment_policy": "fixed-minimal-allowlist",
        "environment_names": sorted(environment),
    }
    report["limits"]["total_timeout_scope"] = (
        "worker start, DNS, TCP/TLS, headers, redirects, body, and worker reap"
    )
    return report


def _run_tool(
    command: list[str], field: str, *, timeout_seconds: int
) -> subprocess.CompletedProcess[str]:
    environment = dict(os.environ)
    environment["LC_ALL"] = "C"
    try:
        return subprocess.run(
            command,
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            env=environment,
            timeout=timeout_seconds,
        )
    except subprocess.TimeoutExpired as error:
        raise VerificationFailure(
            [
                {
                    "field": f"{field}.timeout_seconds",
                    "expected": f"completed within {timeout_seconds} seconds",
                    "actual": "expired",
                }
            ]
        ) from error
    except OSError as error:
        raise VerificationFailure(
            [{"field": field, "expected": "executable tool", "actual": str(error)}]
        ) from error


def _safe_tool_output(
    result: subprocess.CompletedProcess[str], replacements: tuple[str, ...] = ()
) -> str:
    output = result.stdout
    if result.stderr:
        output += ("\n" if output and not output.endswith("\n") else "") + result.stderr
    for replacement in replacements:
        if replacement:
            output = output.replace(replacement, "<ephemeral>")
    return output


def _preflight_aab_zip_container(
    archive_file: BinaryIO, archive_bytes: int
) -> dict[str, Any]:
    eocd_struct = struct.Struct("<4s4H2IH")
    zip64_locator_struct = struct.Struct("<4sIQI")
    zip64_eocd_struct = struct.Struct("<4sQ2H2I4Q")
    central_header_struct = struct.Struct("<4s6H3I5H2I")
    eocd_signature = b"PK\x05\x06"
    zip64_locator_signature = b"PK\x06\x07"
    zip64_eocd_signature = b"PK\x06\x06"
    central_signature = b"PK\x01\x02"

    mismatches: list[dict[str, Any]] = []
    _require(
        mismatches,
        0 < archive_bytes <= AAB_MAX_ARCHIVE_BYTES,
        "aab.archive.container_preflight.archive_bytes",
        f"1..{AAB_MAX_ARCHIVE_BYTES}",
        archive_bytes,
    )
    _require(
        mismatches,
        archive_bytes >= eocd_struct.size,
        "aab.archive.container_preflight.minimum_bytes",
        f">= {eocd_struct.size}",
        archive_bytes,
    )
    if mismatches:
        raise VerificationFailure(mismatches)

    try:
        with nullcontext(archive_file):
            tail_bytes = min(archive_bytes, eocd_struct.size + 65535)
            archive_file.seek(archive_bytes - tail_bytes)
            tail = archive_file.read(tail_bytes)
            candidates: list[tuple[int, tuple[Any, ...]]] = []
            search_from = 0
            while True:
                relative_offset = tail.find(eocd_signature, search_from)
                if relative_offset < 0:
                    break
                if relative_offset + eocd_struct.size <= len(tail):
                    values = eocd_struct.unpack_from(tail, relative_offset)
                    comment_bytes = values[-1]
                    if relative_offset + eocd_struct.size + comment_bytes == len(tail):
                        candidates.append((relative_offset, values))
                search_from = relative_offset + 1
            _require(
                mismatches,
                len(candidates) == 1,
                "aab.archive.container_preflight.eocd_candidate_count",
                1,
                len(candidates),
            )
            if mismatches:
                raise VerificationFailure(mismatches)

            relative_eocd_offset, eocd = candidates[0]
            eocd_offset = archive_bytes - tail_bytes + relative_eocd_offset
            (
                _signature,
                disk_number,
                central_directory_disk,
                entries_on_disk_16,
                total_entries_16,
                central_directory_bytes_32,
                central_directory_offset_32,
                comment_bytes,
            ) = eocd
            _mismatch(
                mismatches,
                "aab.archive.container_preflight.disk_number",
                0,
                disk_number,
            )
            _mismatch(
                mismatches,
                "aab.archive.container_preflight.central_directory_disk",
                0,
                central_directory_disk,
            )

            sentinel_used = (
                entries_on_disk_16 == 0xFFFF
                or total_entries_16 == 0xFFFF
                or central_directory_bytes_32 == 0xFFFFFFFF
                or central_directory_offset_32 == 0xFFFFFFFF
            )
            locator_offset = eocd_offset - zip64_locator_struct.size
            locator = b""
            if locator_offset >= 0:
                archive_file.seek(locator_offset)
                locator = archive_file.read(zip64_locator_struct.size)
            has_zip64_locator = locator.startswith(zip64_locator_signature)
            _mismatch(
                mismatches,
                "aab.archive.container_preflight.zip64_required",
                sentinel_used,
                has_zip64_locator,
            )
            if mismatches:
                raise VerificationFailure(mismatches)

            zip64 = False
            zip64_eocd_bytes = 0
            directory_end = eocd_offset
            if sentinel_used and has_zip64_locator:
                zip64 = True
                (
                    _locator_signature,
                    zip64_eocd_disk,
                    zip64_eocd_offset,
                    total_disks,
                ) = zip64_locator_struct.unpack(locator)
                _mismatch(
                    mismatches,
                    "aab.archive.container_preflight.zip64_eocd_disk",
                    0,
                    zip64_eocd_disk,
                )
                _mismatch(
                    mismatches,
                    "aab.archive.container_preflight.total_disks",
                    1,
                    total_disks,
                )
                _require(
                    mismatches,
                    0 <= zip64_eocd_offset <= locator_offset - zip64_eocd_struct.size,
                    "aab.archive.container_preflight.zip64_eocd_offset",
                    "bounded offset before locator",
                    zip64_eocd_offset,
                )
                if mismatches:
                    raise VerificationFailure(mismatches)
                archive_file.seek(zip64_eocd_offset)
                zip64_header = archive_file.read(zip64_eocd_struct.size)
                _require(
                    mismatches,
                    len(zip64_header) == zip64_eocd_struct.size,
                    "aab.archive.container_preflight.zip64_eocd_header_bytes",
                    zip64_eocd_struct.size,
                    len(zip64_header),
                )
                if mismatches:
                    raise VerificationFailure(mismatches)
                zip64_values = zip64_eocd_struct.unpack(zip64_header)
                (
                    zip64_signature,
                    zip64_record_payload_bytes,
                    _version_made_by,
                    _version_needed,
                    zip64_disk_number,
                    zip64_central_directory_disk,
                    entries_on_disk,
                    total_entries,
                    central_directory_bytes,
                    central_directory_offset,
                ) = zip64_values
                zip64_eocd_bytes = 12 + zip64_record_payload_bytes
                _mismatch(
                    mismatches,
                    "aab.archive.container_preflight.zip64_eocd_signature",
                    zip64_eocd_signature.hex(),
                    zip64_signature.hex(),
                )
                _require(
                    mismatches,
                    44 <= zip64_record_payload_bytes <= AAB_MAX_ZIP64_EOCD_BYTES - 12,
                    "aab.archive.container_preflight.zip64_eocd_bytes",
                    f"56..{AAB_MAX_ZIP64_EOCD_BYTES}",
                    zip64_eocd_bytes,
                )
                _mismatch(
                    mismatches,
                    "aab.archive.container_preflight.zip64_eocd_end",
                    locator_offset,
                    zip64_eocd_offset + zip64_eocd_bytes,
                )
                _mismatch(
                    mismatches,
                    "aab.archive.container_preflight.zip64_disk_number",
                    0,
                    zip64_disk_number,
                )
                _mismatch(
                    mismatches,
                    "aab.archive.container_preflight.zip64_central_directory_disk",
                    0,
                    zip64_central_directory_disk,
                )
                for field, classic_value, sentinel, zip64_value in (
                    (
                        "entries_on_disk",
                        entries_on_disk_16,
                        0xFFFF,
                        entries_on_disk,
                    ),
                    (
                        "total_entries",
                        total_entries_16,
                        0xFFFF,
                        total_entries,
                    ),
                    (
                        "central_directory_bytes",
                        central_directory_bytes_32,
                        0xFFFFFFFF,
                        central_directory_bytes,
                    ),
                    (
                        "central_directory_offset",
                        central_directory_offset_32,
                        0xFFFFFFFF,
                        central_directory_offset,
                    ),
                ):
                    if classic_value != sentinel:
                        _mismatch(
                            mismatches,
                            f"aab.archive.container_preflight.zip64_classic_{field}",
                            zip64_value,
                            classic_value,
                        )
                directory_end = zip64_eocd_offset
            else:
                entries_on_disk = entries_on_disk_16
                total_entries = total_entries_16
                central_directory_bytes = central_directory_bytes_32
                central_directory_offset = central_directory_offset_32

            _mismatch(
                mismatches,
                "aab.archive.container_preflight.entries_on_disk",
                total_entries,
                entries_on_disk,
            )
            _require(
                mismatches,
                0 < total_entries <= AAB_MAX_ENTRY_COUNT,
                "aab.archive.container_preflight.entry_count",
                f"1..{AAB_MAX_ENTRY_COUNT}",
                total_entries,
            )
            _require(
                mismatches,
                0 < central_directory_bytes <= AAB_MAX_CENTRAL_DIRECTORY_BYTES,
                "aab.archive.container_preflight.central_directory_bytes",
                f"1..{AAB_MAX_CENTRAL_DIRECTORY_BYTES}",
                central_directory_bytes,
            )
            _require(
                mismatches,
                0 <= central_directory_offset < directory_end,
                "aab.archive.container_preflight.central_directory_offset",
                "bounded offset before EOCD records",
                central_directory_offset,
            )
            _mismatch(
                mismatches,
                "aab.archive.container_preflight.central_directory_end",
                directory_end,
                central_directory_offset + central_directory_bytes,
            )
            if mismatches:
                raise VerificationFailure(mismatches)

            archive_file.seek(central_directory_offset)
            remaining_directory_bytes = central_directory_bytes
            actual_entries = 0
            for entry_index in range(total_entries):
                central_header = archive_file.read(central_header_struct.size)
                if len(central_header) != central_header_struct.size:
                    mismatches.append(
                        {
                            "field": "aab.archive.container_preflight.central_header_bytes",
                            "expected": central_header_struct.size,
                            "actual": len(central_header),
                        }
                    )
                    break
                central_values = central_header_struct.unpack(central_header)
                _require(
                    mismatches,
                    central_values[0] == central_signature,
                    f"aab.archive.container_preflight.entries[{entry_index}].signature",
                    central_signature.hex(),
                    central_values[0].hex(),
                )
                filename_bytes = central_values[10]
                extra_bytes = central_values[11]
                entry_comment_bytes = central_values[12]
                entry_disk_number = central_values[13]
                variable_bytes = filename_bytes + extra_bytes + entry_comment_bytes
                record_bytes = central_header_struct.size + variable_bytes
                _require(
                    mismatches,
                    record_bytes <= remaining_directory_bytes,
                    f"aab.archive.container_preflight.entries[{entry_index}].record_bytes",
                    f"<= {remaining_directory_bytes}",
                    record_bytes,
                )
                _mismatch(
                    mismatches,
                    f"aab.archive.container_preflight.entries[{entry_index}].disk_number",
                    0,
                    entry_disk_number,
                )
                if mismatches:
                    break
                archive_file.seek(variable_bytes, os.SEEK_CUR)
                remaining_directory_bytes -= record_bytes
                actual_entries += 1
            _mismatch(
                mismatches,
                "aab.archive.container_preflight.actual_entry_count",
                total_entries,
                actual_entries,
            )
            _mismatch(
                mismatches,
                "aab.archive.container_preflight.unparsed_central_directory_bytes",
                0,
                remaining_directory_bytes,
            )
            if mismatches:
                raise VerificationFailure(mismatches)
    except VerificationFailure:
        raise
    except (OSError, struct.error) as error:
        raise VerificationFailure(
            [
                {
                    "field": "aab.archive.container_preflight",
                    "expected": "bounded unambiguous single-disk ZIP container",
                    "actual": type(error).__name__,
                }
            ]
        ) from error

    return {
        "archive_bytes": archive_bytes,
        "central_directory_bytes": central_directory_bytes,
        "central_directory_offset": central_directory_offset,
        "declared_entry_count": total_entries,
        "actual_entry_count": actual_entries,
        "eocd_offset": eocd_offset,
        "eocd_comment_bytes": comment_bytes,
        "zip64": zip64,
        "zip64_eocd_bytes": zip64_eocd_bytes,
        "single_disk": True,
        "unambiguous": True,
        "checked_before_zipfile": True,
        "limits": {
            "archive_bytes": AAB_MAX_ARCHIVE_BYTES,
            "central_directory_bytes": AAB_MAX_CENTRAL_DIRECTORY_BYTES,
            "entry_count": AAB_MAX_ENTRY_COUNT,
            "zip64_eocd_bytes": AAB_MAX_ZIP64_EOCD_BYTES,
        },
    }


def _audit_aab_archive_file(
    archive_file: BinaryIO, archive_bytes: int
) -> dict[str, Any]:
    container_evidence = _preflight_aab_zip_container(archive_file, archive_bytes)
    archive_file.seek(0)
    entries: list[zipfile.ZipInfo] = []
    payload_entries: list[str] = []
    signature_controls: list[str] = []
    total_uncompressed_bytes = 0
    max_entry_uncompressed_bytes = 0
    max_compression_ratio = 0.0
    try:
        with zipfile.ZipFile(archive_file) as archive:
            entries = archive.infolist()
            resource_mismatches: list[dict[str, Any]] = []
            _require(
                resource_mismatches,
                len(entries) <= AAB_MAX_ENTRY_COUNT,
                "aab.archive.entry_count",
                f"<= {AAB_MAX_ENTRY_COUNT}",
                len(entries),
            )
            _mismatch(
                resource_mismatches,
                "aab.archive.entry_count_vs_container_preflight",
                container_evidence["actual_entry_count"],
                len(entries),
            )
            for entry in entries:
                total_uncompressed_bytes += entry.file_size
                max_entry_uncompressed_bytes = max(
                    max_entry_uncompressed_bytes, entry.file_size
                )
                _require(
                    resource_mismatches,
                    entry.file_size <= AAB_MAX_ENTRY_UNCOMPRESSED_BYTES,
                    f"aab.archive.entries[{entry.filename}].uncompressed_bytes",
                    f"<= {AAB_MAX_ENTRY_UNCOMPRESSED_BYTES}",
                    entry.file_size,
                )
                if entry.is_dir() or entry.file_size == 0:
                    compression_ratio = 0.0
                elif entry.compress_size == 0:
                    compression_ratio = float("inf")
                else:
                    compression_ratio = entry.file_size / entry.compress_size
                max_compression_ratio = max(max_compression_ratio, compression_ratio)
                _require(
                    resource_mismatches,
                    compression_ratio <= AAB_MAX_COMPRESSION_RATIO,
                    f"aab.archive.entries[{entry.filename}].compression_ratio",
                    f"<= {AAB_MAX_COMPRESSION_RATIO:g}",
                    (
                        round(compression_ratio, 6)
                        if compression_ratio != float("inf")
                        else "infinite"
                    ),
                )
            _require(
                resource_mismatches,
                total_uncompressed_bytes <= AAB_MAX_TOTAL_UNCOMPRESSED_BYTES,
                "aab.archive.total_uncompressed_bytes",
                f"<= {AAB_MAX_TOTAL_UNCOMPRESSED_BYTES}",
                total_uncompressed_bytes,
            )
            if resource_mismatches:
                raise VerificationFailure(resource_mismatches)

            mismatches: list[dict[str, Any]] = []
            names = [entry.filename for entry in entries]
            duplicates = sorted(name for name in set(names) if names.count(name) != 1)
            _mismatch(mismatches, "aab.archive.duplicate_entries", [], duplicates)

            manifests: list[str] = []
            signature_files: list[tuple[str, str]] = []
            signature_blocks: list[tuple[str, str]] = []
            for entry in entries:
                name = entry.filename
                stripped_name = name.removesuffix("/")
                path = PurePosixPath(stripped_name) if stripped_name else None
                canonical = (
                    bool(stripped_name)
                    and "\\" not in name
                    and not name.startswith("/")
                    and "//" not in name
                    and path is not None
                    and all(part not in ("", ".", "..") for part in path.parts)
                    and str(path) == stripped_name
                )
                _require(
                    mismatches,
                    canonical,
                    f"aab.archive.entries[{name}].canonical_path",
                    True,
                    False,
                )
                _require(
                    mismatches,
                    not bool(entry.flag_bits & 0x1),
                    f"aab.archive.entries[{name}].encrypted",
                    False,
                    bool(entry.flag_bits & 0x1),
                )
                unix_mode = entry.external_attr >> 16
                _require(
                    mismatches,
                    not stat.S_ISLNK(unix_mode),
                    f"aab.archive.entries[{name}].symlink",
                    False,
                    stat.S_ISLNK(unix_mode),
                )
                if entry.is_dir():
                    continue
                normalized_name = name.upper()
                if normalized_name == "META-INF/MANIFEST.MF":
                    manifests.append(name)
                    signature_controls.append(name)
                    _require(
                        mismatches,
                        name == normalized_name,
                        f"aab.archive.entries[{name}].signature_control_case",
                        "META-INF/MANIFEST.MF",
                        name,
                    )
                    continue
                signature_file = re.fullmatch(r"META-INF/([^/]*)\.SF", normalized_name)
                if signature_file is not None:
                    signature_basename = signature_file.group(1)
                    signature_files.append((signature_basename, name))
                    signature_controls.append(name)
                    _require(
                        mismatches,
                        bool(signature_basename),
                        f"aab.archive.entries[{name}].signature_control_basename",
                        "non-empty JAR signer basename",
                        signature_basename,
                    )
                    _require(
                        mismatches,
                        name == normalized_name,
                        f"aab.archive.entries[{name}].signature_control_case",
                        normalized_name,
                        name,
                    )
                    continue
                signature_block = re.fullmatch(
                    r"META-INF/([^/]*)\.(RSA|DSA|EC)", normalized_name
                )
                if signature_block is not None:
                    signature_basename = signature_block.group(1)
                    signature_blocks.append((signature_basename, name))
                    signature_controls.append(name)
                    _require(
                        mismatches,
                        bool(signature_basename),
                        f"aab.archive.entries[{name}].signature_control_basename",
                        "non-empty JAR signer basename",
                        signature_basename,
                    )
                    _require(
                        mismatches,
                        name == normalized_name,
                        f"aab.archive.entries[{name}].signature_control_case",
                        normalized_name,
                        name,
                    )
                    continue
                if re.fullmatch(r"META-INF/SIG-[^/]*", normalized_name) is not None:
                    signature_controls.append(name)
                    mismatches.append(
                        {
                            "field": "aab.archive.signature_controls",
                            "expected": "no SIG-* controls",
                            "actual": name,
                        }
                    )
                    continue
                payload_entries.append(name)

            _mismatch(
                mismatches,
                "aab.archive.manifest_count",
                1,
                len(manifests),
            )
            _mismatch(
                mismatches,
                "aab.archive.signature_file_count",
                1,
                len(signature_files),
            )
            _mismatch(
                mismatches,
                "aab.archive.signature_block_count",
                1,
                len(signature_blocks),
            )
            if len(signature_files) == 1 and len(signature_blocks) == 1:
                _mismatch(
                    mismatches,
                    "aab.archive.signature_control_basename",
                    signature_files[0][0],
                    signature_blocks[0][0],
                )
            _mismatch(
                mismatches,
                "aab.archive.signature_control_count",
                3,
                len(signature_controls),
            )
            _require(
                mismatches,
                len(payload_entries) > 0,
                "aab.archive.payload_entry_count",
                "positive integer",
                len(payload_entries),
            )
            if mismatches:
                raise VerificationFailure(mismatches)

            bad_crc_entry = archive.testzip()
            _mismatch(mismatches, "aab.archive.bad_crc_entry", None, bad_crc_entry)
            if mismatches:
                raise VerificationFailure(mismatches)
    except VerificationFailure:
        raise
    except (OSError, zipfile.BadZipFile) as error:
        raise VerificationFailure(
            [
                {
                    "field": "aab.archive",
                    "expected": "readable ZIP archive",
                    "actual": str(error),
                }
            ]
        ) from error
    return {
        "container_preflight": container_evidence,
        "entry_count": len(entries),
        "payload_entry_count": len(payload_entries),
        "signature_control_entries": sorted(signature_controls),
        "jarsigner_ignored_meta_inf_entries": sorted(signature_controls),
        "duplicate_entries": False,
        "canonical_paths": True,
        "crc": "exact",
        "resources": {
            "total_uncompressed_bytes": total_uncompressed_bytes,
            "max_entry_uncompressed_bytes": max_entry_uncompressed_bytes,
            "max_compression_ratio": round(max_compression_ratio, 6),
            "limits": {
                "archive_bytes": AAB_MAX_ARCHIVE_BYTES,
                "central_directory_bytes": AAB_MAX_CENTRAL_DIRECTORY_BYTES,
                "entry_count": AAB_MAX_ENTRY_COUNT,
                "entry_uncompressed_bytes": AAB_MAX_ENTRY_UNCOMPRESSED_BYTES,
                "total_uncompressed_bytes": AAB_MAX_TOTAL_UNCOMPRESSED_BYTES,
                "compression_ratio": AAB_MAX_COMPRESSION_RATIO,
            },
            "checked_before_crc_decompression": True,
            "container_checked_before_zipfile": True,
        },
    }


def _stable_file_state(file_stat: os.stat_result) -> dict[str, int]:
    return {
        "device": file_stat.st_dev,
        "inode": file_stat.st_ino,
        "mode": file_stat.st_mode,
        "size": file_stat.st_size,
        "mtime_ns": file_stat.st_mtime_ns,
        "ctime_ns": file_stat.st_ctime_ns,
    }


def _audit_aab_archive(aab_path: Path) -> dict[str, Any]:
    try:
        no_follow_flag = os.O_NOFOLLOW
    except AttributeError as error:
        raise VerificationFailure(
            [
                {
                    "field": "aab.archive.stable_file.no_follow_support",
                    "expected": True,
                    "actual": False,
                }
            ]
        ) from error
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | no_follow_flag
    descriptor: int | None = None
    try:
        descriptor = os.open(aab_path, flags)
        before = os.fstat(descriptor)
        mismatches: list[dict[str, Any]] = []
        _require(
            mismatches,
            stat.S_ISREG(before.st_mode),
            "aab.archive.stable_file.regular_file",
            True,
            False,
        )
        if mismatches:
            raise VerificationFailure(mismatches)

        archive_file = os.fdopen(descriptor, "rb", closefd=True)
        descriptor = None
        with archive_file:
            evidence = _audit_aab_archive_file(archive_file, before.st_size)
            archive_file.seek(0)
            digest = hashlib.sha256()
            remaining_bytes = before.st_size
            while remaining_bytes > 0:
                chunk = archive_file.read(min(DOWNLOAD_CHUNK_BYTES, remaining_bytes))
                if not chunk:
                    break
                digest.update(chunk)
                remaining_bytes -= len(chunk)
            trailing_bytes = archive_file.read(1)
            after = os.fstat(archive_file.fileno())
        before_state = _stable_file_state(before)
        after_state = _stable_file_state(after)
        hashed_bytes = before.st_size - remaining_bytes
        _mismatch(
            mismatches,
            "aab.archive.stable_file.hashed_bytes",
            before.st_size,
            hashed_bytes,
        )
        _mismatch(
            mismatches,
            "aab.archive.stable_file.trailing_bytes",
            0,
            len(trailing_bytes),
        )
        _mismatch(
            mismatches,
            "aab.archive.stable_file.fstat_unchanged",
            before_state,
            after_state,
        )
        if mismatches:
            raise VerificationFailure(mismatches)
    except VerificationFailure:
        raise
    except OSError as error:
        raise VerificationFailure(
            [
                {
                    "field": "aab.archive.stable_file.open",
                    "expected": "O_NOFOLLOW regular file opened once for preflight and ZIP audit",
                    "actual": type(error).__name__,
                }
            ]
        ) from error
    finally:
        if descriptor is not None:
            os.close(descriptor)

    evidence["container_preflight"]["stable_file"] = {
        "opened_with_no_follow": True,
        "regular_file": True,
        "preflight_and_zipfile_same_descriptor": True,
        "fstat_unchanged": True,
        "hashed_bytes": hashed_bytes,
        "digest": f"sha256:{digest.hexdigest()}",
        "state": after_state,
    }
    return evidence


def verify_aab_signature(
    *,
    aab_path: Path,
    expected_certificate_sha256: str,
    report_dir: Path,
    temporary_parent: Path | None = None,
) -> dict[str, Any]:
    mismatches: list[dict[str, Any]] = []
    normalized_expected_certificate = expected_certificate_sha256.lower()
    _require(
        mismatches,
        SHA256.fullmatch(normalized_expected_certificate) is not None,
        "expected_certificate_sha256",
        "64 hex characters",
        "invalid" if not SHA256.fullmatch(normalized_expected_certificate) else "valid",
    )
    _require(
        mismatches,
        aab_path.is_file(),
        "aab.path",
        "existing regular file",
        str(aab_path),
    )
    if mismatches:
        raise VerificationFailure(mismatches)

    report_dir.mkdir(parents=True, exist_ok=True)
    archive_evidence = _audit_aab_archive(aab_path)
    certificate_pem_result = _run_tool(
        ["keytool", "-printcert", "-rfc", "-jarfile", str(aab_path)],
        "aab.keytool.printcert_rfc",
        timeout_seconds=KEYTOOL_TIMEOUT_SECONDS,
    )
    certificate_text_result = _run_tool(
        ["keytool", "-printcert", "-jarfile", str(aab_path)],
        "aab.keytool.printcert",
        timeout_seconds=KEYTOOL_TIMEOUT_SECONDS,
    )
    certificate_pem_output = certificate_pem_result.stdout
    certificate_text = _safe_tool_output(certificate_text_result)
    _mismatch(
        mismatches,
        "aab.keytool.printcert_rfc.exit_code",
        0,
        certificate_pem_result.returncode,
    )
    _mismatch(
        mismatches,
        "aab.keytool.printcert.exit_code",
        0,
        certificate_text_result.returncode,
    )
    pem_blocks = re.findall(
        r"-----BEGIN CERTIFICATE-----\s*([A-Za-z0-9+/=\r\n]+?)\s*-----END CERTIFICATE-----",
        certificate_pem_output,
    )
    _mismatch(mismatches, "aab.certificate.pem_count", 1, len(pem_blocks))
    certificate_pem = ""
    certificate_digest: str | None = None
    if len(pem_blocks) == 1:
        encoded_der = re.sub(r"\s+", "", pem_blocks[0])
        try:
            certificate_der = base64.b64decode(encoded_der, validate=True)
        except (binascii.Error, ValueError) as error:
            mismatches.append(
                {
                    "field": "aab.certificate.pem",
                    "expected": "valid base64 DER certificate",
                    "actual": type(error).__name__,
                }
            )
        else:
            certificate_digest = hashlib.sha256(certificate_der).hexdigest()
            certificate_pem = (
                "-----BEGIN CERTIFICATE-----\n"
                + "\n".join(
                    encoded_der[index : index + 64]
                    for index in range(0, len(encoded_der), 64)
                )
                + "\n-----END CERTIFICATE-----\n"
            )
        _require(
            mismatches,
            certificate_digest == normalized_expected_certificate,
            "aab.signingCertificateSha256",
            "protected release certificate",
            "different certificate"
            if certificate_digest != normalized_expected_certificate
            else "protected release certificate",
        )
    (report_dir / "aab-certificate.pem").write_text(certificate_pem, encoding="utf-8")
    (report_dir / "aab-certificate.txt").write_text(certificate_text, encoding="utf-8")
    if mismatches:
        raise VerificationFailure(mismatches)

    truststore_password = secrets.token_hex(24)
    truststore_path: Path | None = None
    strict_result: subprocess.CompletedProcess[str] | None = None
    import_result: subprocess.CompletedProcess[str] | None = None
    truststore_list_result: subprocess.CompletedProcess[str] | None = None
    trust_anchor_count: int | None = None
    with tempfile.TemporaryDirectory(
        prefix="openaria-aab-trust-",
        dir=str(temporary_parent) if temporary_parent is not None else None,
    ) as temporary_directory:
        temporary_path = Path(temporary_directory)
        temporary_path.chmod(0o700)
        truststore_path = temporary_path / "pinned-release-certificate.p12"
        import_result = _run_tool(
            [
                "keytool",
                "-importcert",
                "-noprompt",
                "-alias",
                "openaria-release-anchor",
                "-file",
                str(report_dir / "aab-certificate.pem"),
                "-keystore",
                str(truststore_path),
                "-storetype",
                "PKCS12",
                "-storepass",
                truststore_password,
            ],
            "aab.keytool.import_pinned_trust_anchor",
            timeout_seconds=KEYTOOL_TIMEOUT_SECONDS,
        )
        if import_result.returncode == 0:
            truststore_list_result = _run_tool(
                [
                    "keytool",
                    "-list",
                    "-keystore",
                    str(truststore_path),
                    "-storetype",
                    "PKCS12",
                    "-storepass",
                    truststore_password,
                ],
                "aab.keytool.list_pinned_trust_anchor",
                timeout_seconds=KEYTOOL_TIMEOUT_SECONDS,
            )
            truststore_output = _safe_tool_output(
                truststore_list_result, (truststore_password, str(truststore_path))
            )
            anchor_count_match = re.search(
                r"Your keystore contains ([0-9]+) entr(?:y|ies)", truststore_output
            )
            if anchor_count_match is not None:
                trust_anchor_count = int(anchor_count_match.group(1))
            (report_dir / "aab-truststore.txt").write_text(
                truststore_output, encoding="utf-8"
            )
            strict_result = _run_tool(
                [
                    "jarsigner",
                    "-verify",
                    "-strict",
                    "-verbose:summary",
                    "-certs",
                    "-keystore",
                    str(truststore_path),
                    "-storetype",
                    "PKCS12",
                    "-storepass",
                    truststore_password,
                    str(aab_path),
                    "openaria-release-anchor",
                ],
                "aab.jarsigner.strict",
                timeout_seconds=JARSIGNER_TIMEOUT_SECONDS,
            )
            strict_output = _safe_tool_output(
                strict_result, (truststore_password, str(truststore_path))
            )
        else:
            strict_output = _safe_tool_output(
                import_result, (truststore_password, str(truststore_path))
            )
        (report_dir / "jarsigner-strict.txt").write_text(
            strict_output, encoding="utf-8"
        )

    _require(
        mismatches,
        truststore_path is not None and not truststore_path.parent.exists(),
        "aab.ephemeral_truststore.cleaned",
        True,
        False,
    )
    _mismatch(
        mismatches,
        "aab.keytool.import_pinned_trust_anchor.exit_code",
        0,
        import_result.returncode if import_result is not None else None,
    )
    _mismatch(
        mismatches,
        "aab.keytool.list_pinned_trust_anchor.exit_code",
        0,
        truststore_list_result.returncode
        if truststore_list_result is not None
        else None,
    )
    _mismatch(mismatches, "aab.trust_anchor_count", 1, trust_anchor_count)
    _mismatch(
        mismatches,
        "aab.jarsigner.strict_exit_code",
        0,
        strict_result.returncode if strict_result is not None else None,
    )
    if mismatches:
        raise VerificationFailure(mismatches)

    return {
        "schema": AAB_EVIDENCE_SCHEMA,
        "aab": {
            "name": aab_path.name,
            "size": archive_evidence["container_preflight"]["stable_file"]["state"][
                "size"
            ],
            "digest": archive_evidence["container_preflight"]["stable_file"]["digest"],
        },
        "archive": archive_evidence,
        "certificate_sha256": normalized_expected_certificate,
        "certificate_count": 1,
        "strict": True,
        "jarsigner_exit_code": 0,
        "trust_anchor_count": 1,
        "trust_anchor": "ephemeral-extracted-pinned-certificate",
        "alias_bound": True,
        "ephemeral_truststore_cleaned": True,
        "all_payload_entries_signed": True,
    }


def verify_downloaded_release(
    *,
    state: dict[str, Any],
    asset_dir: Path,
    expected_certificate_sha256: str,
    apk_signature_report: Path,
    apk_package_report: Path,
    apk_version_name_report: Path,
    apk_version_code_report: Path,
    aab_verification: dict[str, Any],
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

    expected_aab = assets[aab_name]
    for field, expected, actual in (
        ("schema", AAB_EVIDENCE_SCHEMA, aab_verification.get("schema")),
        ("aab.name", aab_name, _at(aab_verification, "aab", "name")),
        ("aab.size", expected_aab["size"], _at(aab_verification, "aab", "size")),
        (
            "aab.digest",
            expected_aab["digest"],
            _at(aab_verification, "aab", "digest"),
        ),
        (
            "certificate_sha256",
            normalized_expected_certificate,
            aab_verification.get("certificate_sha256"),
        ),
        ("strict", True, aab_verification.get("strict")),
        ("jarsigner_exit_code", 0, aab_verification.get("jarsigner_exit_code")),
        (
            "trust_anchor",
            "ephemeral-extracted-pinned-certificate",
            aab_verification.get("trust_anchor"),
        ),
        ("certificate_count", 1, aab_verification.get("certificate_count")),
        ("trust_anchor_count", 1, aab_verification.get("trust_anchor_count")),
        ("alias_bound", True, aab_verification.get("alias_bound")),
        (
            "ephemeral_truststore_cleaned",
            True,
            aab_verification.get("ephemeral_truststore_cleaned"),
        ),
        (
            "all_payload_entries_signed",
            True,
            aab_verification.get("all_payload_entries_signed"),
        ),
        (
            "archive.duplicate_entries",
            False,
            _at(aab_verification, "archive", "duplicate_entries"),
        ),
        (
            "archive.canonical_paths",
            True,
            _at(aab_verification, "archive", "canonical_paths"),
        ),
        ("archive.crc", "exact", _at(aab_verification, "archive", "crc")),
        (
            "archive.resources.checked_before_crc_decompression",
            True,
            _at(
                aab_verification,
                "archive",
                "resources",
                "checked_before_crc_decompression",
            ),
        ),
        (
            "archive.resources.container_checked_before_zipfile",
            True,
            _at(
                aab_verification,
                "archive",
                "resources",
                "container_checked_before_zipfile",
            ),
        ),
        (
            "archive.container_preflight.archive_bytes",
            expected_aab["size"],
            _at(
                aab_verification,
                "archive",
                "container_preflight",
                "archive_bytes",
            ),
        ),
        (
            "archive.container_preflight.single_disk",
            True,
            _at(
                aab_verification,
                "archive",
                "container_preflight",
                "single_disk",
            ),
        ),
        (
            "archive.container_preflight.unambiguous",
            True,
            _at(
                aab_verification,
                "archive",
                "container_preflight",
                "unambiguous",
            ),
        ),
        (
            "archive.container_preflight.checked_before_zipfile",
            True,
            _at(
                aab_verification,
                "archive",
                "container_preflight",
                "checked_before_zipfile",
            ),
        ),
        (
            "archive.container_preflight.stable_file.opened_with_no_follow",
            True,
            _at(
                aab_verification,
                "archive",
                "container_preflight",
                "stable_file",
                "opened_with_no_follow",
            ),
        ),
        (
            "archive.container_preflight.stable_file.regular_file",
            True,
            _at(
                aab_verification,
                "archive",
                "container_preflight",
                "stable_file",
                "regular_file",
            ),
        ),
        (
            "archive.container_preflight.stable_file.preflight_and_zipfile_same_descriptor",
            True,
            _at(
                aab_verification,
                "archive",
                "container_preflight",
                "stable_file",
                "preflight_and_zipfile_same_descriptor",
            ),
        ),
        (
            "archive.container_preflight.stable_file.fstat_unchanged",
            True,
            _at(
                aab_verification,
                "archive",
                "container_preflight",
                "stable_file",
                "fstat_unchanged",
            ),
        ),
        (
            "archive.container_preflight.stable_file.hashed_bytes",
            expected_aab["size"],
            _at(
                aab_verification,
                "archive",
                "container_preflight",
                "stable_file",
                "hashed_bytes",
            ),
        ),
        (
            "archive.container_preflight.stable_file.state.size",
            expected_aab["size"],
            _at(
                aab_verification,
                "archive",
                "container_preflight",
                "stable_file",
                "state",
                "size",
            ),
        ),
        (
            "archive.container_preflight.stable_file.digest",
            expected_aab["digest"],
            _at(
                aab_verification,
                "archive",
                "container_preflight",
                "stable_file",
                "digest",
            ),
        ),
        (
            "archive.container_preflight.limits.archive_bytes",
            AAB_MAX_ARCHIVE_BYTES,
            _at(
                aab_verification,
                "archive",
                "container_preflight",
                "limits",
                "archive_bytes",
            ),
        ),
        (
            "archive.container_preflight.limits.central_directory_bytes",
            AAB_MAX_CENTRAL_DIRECTORY_BYTES,
            _at(
                aab_verification,
                "archive",
                "container_preflight",
                "limits",
                "central_directory_bytes",
            ),
        ),
        (
            "archive.container_preflight.limits.entry_count",
            AAB_MAX_ENTRY_COUNT,
            _at(
                aab_verification,
                "archive",
                "container_preflight",
                "limits",
                "entry_count",
            ),
        ),
        (
            "archive.container_preflight.limits.zip64_eocd_bytes",
            AAB_MAX_ZIP64_EOCD_BYTES,
            _at(
                aab_verification,
                "archive",
                "container_preflight",
                "limits",
                "zip64_eocd_bytes",
            ),
        ),
        (
            "archive.resources.limits.entry_count",
            AAB_MAX_ENTRY_COUNT,
            _at(
                aab_verification,
                "archive",
                "resources",
                "limits",
                "entry_count",
            ),
        ),
        (
            "archive.resources.limits.entry_uncompressed_bytes",
            AAB_MAX_ENTRY_UNCOMPRESSED_BYTES,
            _at(
                aab_verification,
                "archive",
                "resources",
                "limits",
                "entry_uncompressed_bytes",
            ),
        ),
        (
            "archive.resources.limits.total_uncompressed_bytes",
            AAB_MAX_TOTAL_UNCOMPRESSED_BYTES,
            _at(
                aab_verification,
                "archive",
                "resources",
                "limits",
                "total_uncompressed_bytes",
            ),
        ),
        (
            "archive.resources.limits.compression_ratio",
            AAB_MAX_COMPRESSION_RATIO,
            _at(
                aab_verification,
                "archive",
                "resources",
                "limits",
                "compression_ratio",
            ),
        ),
    ):
        _mismatch(mismatches, f"aab.strict_verification.{field}", expected, actual)
    _require(
        mismatches,
        isinstance(_at(aab_verification, "archive", "payload_entry_count"), int)
        and _at(aab_verification, "archive", "payload_entry_count") > 0,
        "aab.strict_verification.archive.payload_entry_count",
        "positive integer",
        _at(aab_verification, "archive", "payload_entry_count"),
    )
    _require(
        mismatches,
        isinstance(_at(aab_verification, "archive", "signature_control_entries"), list)
        and len(_at(aab_verification, "archive", "signature_control_entries")) == 3,
        "aab.strict_verification.archive.signature_control_entries",
        "exact three-entry JAR signature closure",
        _at(aab_verification, "archive", "signature_control_entries"),
    )
    _mismatch(
        mismatches,
        "aab.strict_verification.archive.jarsigner_ignored_meta_inf_entries",
        _at(aab_verification, "archive", "signature_control_entries"),
        _at(aab_verification, "archive", "jarsigner_ignored_meta_inf_entries"),
    )
    for field, limit, actual in (
        (
            "entry_count",
            AAB_MAX_ENTRY_COUNT,
            _at(aab_verification, "archive", "entry_count"),
        ),
        (
            "max_entry_uncompressed_bytes",
            AAB_MAX_ENTRY_UNCOMPRESSED_BYTES,
            _at(
                aab_verification,
                "archive",
                "resources",
                "max_entry_uncompressed_bytes",
            ),
        ),
        (
            "total_uncompressed_bytes",
            AAB_MAX_TOTAL_UNCOMPRESSED_BYTES,
            _at(
                aab_verification,
                "archive",
                "resources",
                "total_uncompressed_bytes",
            ),
        ),
        (
            "max_compression_ratio",
            AAB_MAX_COMPRESSION_RATIO,
            _at(
                aab_verification,
                "archive",
                "resources",
                "max_compression_ratio",
            ),
        ),
    ):
        _require(
            mismatches,
            isinstance(actual, (int, float))
            and not isinstance(actual, bool)
            and 0 <= actual <= limit,
            f"aab.strict_verification.archive.resources.{field}",
            f"number between 0 and {limit}",
            actual,
        )
    archive_entry_count = _at(aab_verification, "archive", "entry_count")
    _mismatch(
        mismatches,
        "aab.strict_verification.archive.container_preflight.declared_entry_count",
        archive_entry_count,
        _at(
            aab_verification,
            "archive",
            "container_preflight",
            "declared_entry_count",
        ),
    )
    _mismatch(
        mismatches,
        "aab.strict_verification.archive.container_preflight.actual_entry_count",
        archive_entry_count,
        _at(
            aab_verification,
            "archive",
            "container_preflight",
            "actual_entry_count",
        ),
    )
    for field, limit, actual in (
        (
            "container_preflight.central_directory_bytes",
            AAB_MAX_CENTRAL_DIRECTORY_BYTES,
            _at(
                aab_verification,
                "archive",
                "container_preflight",
                "central_directory_bytes",
            ),
        ),
        (
            "container_preflight.archive_bytes",
            AAB_MAX_ARCHIVE_BYTES,
            _at(
                aab_verification,
                "archive",
                "container_preflight",
                "archive_bytes",
            ),
        ),
    ):
        _require(
            mismatches,
            isinstance(actual, int)
            and not isinstance(actual, bool)
            and 0 < actual <= limit,
            f"aab.strict_verification.archive.{field}",
            f"integer between 1 and {limit}",
            actual,
        )
    container_archive_bytes = _at(
        aab_verification, "archive", "container_preflight", "archive_bytes"
    )
    container_directory_bytes = _at(
        aab_verification,
        "archive",
        "container_preflight",
        "central_directory_bytes",
    )
    container_directory_offset = _at(
        aab_verification,
        "archive",
        "container_preflight",
        "central_directory_offset",
    )
    container_eocd_offset = _at(
        aab_verification, "archive", "container_preflight", "eocd_offset"
    )
    _require(
        mismatches,
        all(
            isinstance(value, int) and not isinstance(value, bool)
            for value in (
                container_archive_bytes,
                container_directory_bytes,
                container_directory_offset,
                container_eocd_offset,
            )
        )
        and 0 <= container_directory_offset
        and container_directory_offset + container_directory_bytes
        <= container_eocd_offset
        < container_archive_bytes,
        "aab.strict_verification.archive.container_preflight.offset_closure",
        "0 <= central directory <= EOCD < archive bytes",
        {
            "archive_bytes": container_archive_bytes,
            "central_directory_bytes": container_directory_bytes,
            "central_directory_offset": container_directory_offset,
            "eocd_offset": container_eocd_offset,
        },
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
            "aab": "strict-all-entries-exact",
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
    ownership = _load_json(args.ownership)
    state = validate_release_state(
        ownership=ownership,
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
    source_arguments = (
        args.repository_metadata,
        args.source_run_metadata,
        args.source_jobs_metadata,
        args.ownership_artifact_metadata,
        args.default_branch,
    )
    if any(value is not None for value in source_arguments):
        if not all(value is not None for value in source_arguments):
            raise VerificationFailure(
                [
                    {
                        "field": "state.source_evidence_arguments",
                        "expected": "all five source evidence arguments",
                        "actual": "partial source evidence arguments",
                    }
                ]
            )
        validate_source_evidence(
            state=state,
            ownership=ownership,
            repository_metadata=_load_json(args.repository_metadata),
            source_run=_load_json(args.source_run_metadata),
            source_jobs=_load_json(args.source_jobs_metadata),
            ownership_artifact=_load_json(args.ownership_artifact_metadata),
            default_branch=args.default_branch,
        )
    _write_json(args.output, state)


def _download_command(args: argparse.Namespace) -> None:
    state = _load_json(args.state)
    mismatches: list[dict[str, Any]] = []
    _mismatch(mismatches, "state.schema", STATE_SCHEMA, state.get("schema"))
    tag = _at(state, "target", "tag")
    expected_names = _release_names(tag) if isinstance(tag, str) else set()
    assets = _assets_by_name(
        _at(state, "target", "assets"),
        "state.target.assets",
        expected_names,
        mismatches,
    )
    _validate_asset_fields(assets, "state.target.assets", mismatches)
    _require(
        mismatches,
        args.asset_name in assets,
        "download.asset_name",
        sorted(expected_names),
        args.asset_name,
    )
    if args.asset_name in assets:
        _require(
            mismatches,
            isinstance(assets[args.asset_name].get("browser_download_url"), str),
            f"state.target.assets[{args.asset_name}].browser_download_url",
            "HTTPS URL string",
            assets[args.asset_name].get("browser_download_url"),
        )
    _mismatch(
        mismatches,
        "download.partial_output.name",
        f"{args.asset_name}.partial",
        args.partial_output.name,
    )
    if mismatches:
        raise VerificationFailure(mismatches)

    asset = assets[args.asset_name]
    report = download_anonymous_asset(
        asset_name=args.asset_name,
        url=asset["browser_download_url"],
        partial_output=args.partial_output,
        expected_size=asset["size"],
        expected_digest=asset["digest"],
        connect_timeout_seconds=args.connect_timeout_seconds,
        body_timeout_seconds=args.body_timeout_seconds,
        total_timeout_seconds=args.total_timeout_seconds,
    )
    _write_json(args.report, report)


def _aab_command(args: argparse.Namespace) -> None:
    evidence = verify_aab_signature(
        aab_path=args.aab,
        expected_certificate_sha256=args.expected_certificate_sha256,
        report_dir=args.report_dir,
    )
    _write_json(args.output, evidence)


def _complete_command(args: argparse.Namespace) -> None:
    initial_state = _load_json(args.initial_state)
    state = _load_json(args.final_state)
    ownership = _load_json(args.ownership)
    initial_repository = _load_json(args.initial_repository_metadata)
    final_repository = _load_json(args.final_repository_metadata)
    initial_source_run = _load_json(args.initial_source_run_metadata)
    final_source_run = _load_json(args.final_source_run_metadata)
    initial_source_jobs = _load_json(args.initial_source_jobs_metadata)
    final_source_jobs = _load_json(args.final_source_jobs_metadata)
    initial_ownership_artifact = _load_json(args.initial_ownership_artifact_metadata)
    final_ownership_artifact = _load_json(args.final_ownership_artifact_metadata)
    validate_source_evidence(
        state=state,
        ownership=ownership,
        repository_metadata=final_repository,
        source_run=final_source_run,
        source_jobs=final_source_jobs,
        ownership_artifact=final_ownership_artifact,
        default_branch=args.default_branch,
    )
    validate_final_recheck(
        initial_state=initial_state,
        final_state=state,
        initial_repository=initial_repository,
        final_repository=final_repository,
        initial_source_run=initial_source_run,
        final_source_run=final_source_run,
        initial_source_jobs=initial_source_jobs,
        final_source_jobs=final_source_jobs,
        initial_ownership_artifact=initial_ownership_artifact,
        final_ownership_artifact=final_ownership_artifact,
    )
    byte_evidence = verify_downloaded_release(
        state=state,
        asset_dir=args.asset_dir,
        expected_certificate_sha256=args.expected_certificate_sha256,
        apk_signature_report=args.apk_signature_report,
        apk_package_report=args.apk_package_report,
        apk_version_name_report=args.apk_version_name_report,
        apk_version_code_report=args.apk_version_code_report,
        aab_verification=_load_json(args.aab_verification),
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
    _mismatch(
        mismatches,
        "ownership_receipt.relative_path",
        "release-ownership.json",
        args.ownership.name,
    )
    _mismatch(
        mismatches,
        "ownership_receipt.root_directory",
        "ownership",
        args.ownership.parent.name,
    )
    _require(
        mismatches,
        args.ownership.is_file() and not args.ownership.is_symlink(),
        "ownership_receipt.file_type",
        "regular non-symlink file",
        "invalid",
    )
    if mismatches:
        raise VerificationFailure(mismatches)

    ownership_receipt_evidence = {
        "relative_path": "release-ownership.json",
        "size": args.ownership.stat().st_size,
        "digest": f"sha256:{_sha256(args.ownership)}",
    }
    verified_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    jobs_by_name = {
        job["name"]: job
        for job in final_source_jobs["jobs"]
        if isinstance(job, dict) and isinstance(job.get("name"), str)
    }
    evidence = {
        "schema": EVIDENCE_SCHEMA,
        "repository": state["repository"],
        "verified_at": verified_at,
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
            "branch": args.default_branch,
            "workflow": SOURCE_WORKFLOW,
            "conclusion": final_source_run["conclusion"],
            "actor": final_source_run["actor"]["login"],
            "triggering_actor": final_source_run["triggering_actor"]["login"],
            "jobs": {
                "build": {
                    "id": jobs_by_name[BUILD_JOB]["id"],
                    "name": BUILD_JOB,
                    "conclusion": "success",
                },
                "publication": {
                    "id": jobs_by_name[ASSEMBLE_JOB]["id"],
                    "name": ASSEMBLE_JOB,
                    "conclusion": "failure",
                    "ownership_staged_upgrade_and_publish": "success",
                    "post_publish_verification": "failure",
                },
            },
            "ownership_artifact": {
                "id": final_ownership_artifact["id"],
                "name": final_ownership_artifact["name"],
                "size_in_bytes": final_ownership_artifact["size_in_bytes"],
                "digest": final_ownership_artifact["digest"],
                "receipt": ownership_receipt_evidence,
            },
        },
        "target": {
            **state["target"],
            "assets": byte_evidence["assets"],
        },
        "application": byte_evidence["application"],
        "signature_identity": byte_evidence["signature_identity"],
        "final_recheck": {
            "verified_at": verified_at,
            "after_anonymous_download_and_signature_verification": True,
            "source_run": "exact_and_unchanged",
            "repository_default_branch_and_owner": "exact_and_unchanged",
            "source_jobs": "exact_and_unchanged",
            "ownership_artifact_id_size_digest": "exact_and_unchanged",
            "latest_release_id_tag_and_assets": "exact_and_unchanged",
            "release_by_id": "exact_and_unchanged",
            "release_by_tag": "exact_and_unchanged",
            "tag_ref": "exact_and_unchanged",
        },
        "verification": {
            "source_run_jobs_actor_and_ownership_receipt": "exact",
            "release_id_latest_immutable_and_tag_commit": "exact",
            "public_asset_urls": "exact",
            "anonymous_asset_bytes_and_digests": "exact",
            "checksum_closure": "exact",
            "manifest_identity": "exact",
            "apk_identity_and_signer": "exact",
            "aab_all_payload_entries_and_signer": "strict-exact",
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
    state.add_argument("--source-run-metadata", type=Path)
    state.add_argument("--source-jobs-metadata", type=Path)
    state.add_argument("--ownership-artifact-metadata", type=Path)
    state.add_argument("--default-branch")
    state.add_argument("--repository-metadata", type=Path)
    state.add_argument("--output", type=Path, required=True)
    state.set_defaults(handler=_state_command)

    download = subparsers.add_parser(
        "download",
        help="Anonymously stream one state-owned asset into a byte-bounded partial file.",
    )
    download.add_argument("--state", type=Path, required=True)
    download.add_argument("--asset-name", required=True)
    download.add_argument("--partial-output", type=Path, required=True)
    download.add_argument("--report", type=Path, required=True)
    download.add_argument("--connect-timeout-seconds", type=float, required=True)
    download.add_argument("--body-timeout-seconds", type=float, required=True)
    download.add_argument("--total-timeout-seconds", type=float, required=True)
    download.set_defaults(handler=_download_command)

    aab = subparsers.add_parser(
        "aab", help="Strictly verify every AAB payload entry using its pinned signer."
    )
    aab.add_argument("--aab", type=Path, required=True)
    aab.add_argument("--expected-certificate-sha256", required=True)
    aab.add_argument("--report-dir", type=Path, required=True)
    aab.add_argument("--output", type=Path, required=True)
    aab.set_defaults(handler=_aab_command)

    complete = subparsers.add_parser(
        "complete", help="Verify anonymous bytes, identities, and source evidence."
    )
    complete.add_argument("--initial-state", type=Path, required=True)
    complete.add_argument("--final-state", type=Path, required=True)
    complete.add_argument("--ownership", type=Path, required=True)
    complete.add_argument("--initial-repository-metadata", type=Path, required=True)
    complete.add_argument("--final-repository-metadata", type=Path, required=True)
    complete.add_argument("--initial-source-run-metadata", type=Path, required=True)
    complete.add_argument("--final-source-run-metadata", type=Path, required=True)
    complete.add_argument("--initial-source-jobs-metadata", type=Path, required=True)
    complete.add_argument("--final-source-jobs-metadata", type=Path, required=True)
    complete.add_argument(
        "--initial-ownership-artifact-metadata", type=Path, required=True
    )
    complete.add_argument(
        "--final-ownership-artifact-metadata", type=Path, required=True
    )
    complete.add_argument("--default-branch", required=True)
    complete.add_argument("--asset-dir", type=Path, required=True)
    complete.add_argument("--expected-certificate-sha256", required=True)
    complete.add_argument("--apk-signature-report", type=Path, required=True)
    complete.add_argument("--apk-package-report", type=Path, required=True)
    complete.add_argument("--apk-version-name-report", type=Path, required=True)
    complete.add_argument("--apk-version-code-report", type=Path, required=True)
    complete.add_argument("--aab-verification", type=Path, required=True)
    complete.add_argument("--verification-run-id", required=True)
    complete.add_argument("--verification-run-attempt", required=True)
    complete.add_argument("--verification-commit", required=True)
    complete.add_argument("--output", type=Path, required=True)
    complete.set_defaults(handler=_complete_command)
    return parser


def main() -> int:
    if sys.argv[1:] == [DOWNLOAD_WORKER_FLAG]:
        return _anonymous_download_worker_main()
    args = _parser().parse_args()
    try:
        args.handler(args)
    except VerificationFailure as error:
        _print_failure(error)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
