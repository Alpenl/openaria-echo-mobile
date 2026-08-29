"""Prove that the published production APK upgrades through the old app UI."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from collections.abc import Iterable
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse

PACKAGE_NAME = "com.openaria.openaria_echo_mobile"
SETTINGS_PACKAGE = "com.android.settings"
UNKNOWN_SOURCES_TITLE = "Install unknown apps"
UNKNOWN_SOURCES_ALLOW_LABEL = "Allow from this source"
APPLICATION_LABEL = "Open Aria Echo"
MANIFEST_SCHEMA = "openaria.echo.mobile.android-update.v1"
VERIFIED_RETRY_MIN_VERSION_CODE = 6
DEVICE_UI_XML = "/sdcard/openaria-update-window.xml"
UI_DUMP_ATTEMPTS = 3
UI_DUMP_RETRY_DELAY_SECONDS = 0.5
UNKNOWN_SOURCES_PAGE_TIMEOUT_SECONDS = 90
UNKNOWN_SOURCES_CONFIRM_TIMEOUT_SECONDS = 10
UNKNOWN_SOURCES_POLL_INTERVAL_SECONDS = 1
HEX_64 = re.compile(r"^[0-9a-f]{64}$")
BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
SIGNER_DIGEST = re.compile(
    r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$",
    re.MULTILINE,
)


class AcceptanceError(RuntimeError):
    pass


def now_utc() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def sanitize(message: str) -> str:
    # A failed redirect must never copy a temporary signed URL into Actions logs.
    return re.sub(r"(https://[^\s?]+)\?[^\s]+", r"\1?<redacted>", message).strip()


def command(
    args: list[str],
    *,
    timeout: int = 60,
    sensitive_output: bool = False,
) -> str:
    try:
        result = subprocess.run(
            args,
            check=False,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except (OSError, subprocess.TimeoutExpired) as exception:
        raise AcceptanceError(f"Command {args[0]!r} could not complete: {sanitize(str(exception))}") from exception
    if result.returncode != 0:
        if sensitive_output:
            raise AcceptanceError(f"Command {args[0]!r} failed while checking a protected identity")
        detail = sanitize((result.stderr or result.stdout)[-2000:])
        raise AcceptanceError(f"Command {args[0]!r} failed: {detail}")
    return result.stdout.strip()


def require_tools(names: Iterable[str]) -> None:
    missing = [name for name in names if shutil.which(name) is None]
    if missing:
        raise AcceptanceError("Missing acceptance tools: " + ", ".join(missing))


def download(url: str, destination: Path) -> None:
    request = urllib.request.Request(url, headers={"User-Agent": "OpenAria-Android-Upgrade-Acceptance/1"})
    last_error: Exception | None = None
    for attempt in range(1, 6):
        try:
            with urllib.request.urlopen(request, timeout=45) as response, destination.open("wb") as output:
                if response.status < 200 or response.status >= 300:
                    raise AcceptanceError(f"Download failed with HTTP {response.status}")
                shutil.copyfileobj(response, output)
            return
        except (OSError, urllib.error.URLError, AcceptanceError) as exception:
            last_error = exception
            if attempt < 5:
                time.sleep(attempt)
    raise AcceptanceError(f"Could not download a required published asset: {sanitize(str(last_error))}")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def apk_identity(path: Path) -> tuple[str, str, int]:
    package_name = command(["apkanalyzer", "manifest", "application-id", str(path)])
    version_name = command(["apkanalyzer", "manifest", "version-name", str(path)])
    version_code_text = command(["apkanalyzer", "manifest", "version-code", str(path)])
    try:
        version_code = int(version_code_text)
    except ValueError as exception:
        raise AcceptanceError("apkanalyzer returned a non-integer versionCode") from exception
    return package_name, version_name, version_code


def apk_signer(path: Path) -> str:
    output = command(
        ["apksigner", "verify", "--verbose", "--print-certs", str(path)],
        sensitive_output=True,
    )
    digests = [match.lower() for match in SIGNER_DIGEST.findall(output)]
    if len(digests) != 1:
        raise AcceptanceError(f"APK must have exactly one signer; found {len(digests)}")
    return digests[0]


def device_package_identity() -> tuple[str, int]:
    package_dump = command(["adb", "shell", "dumpsys", "package", PACKAGE_NAME], timeout=30)
    version_name_match = re.search(r"^\s*versionName=([^\s]+)\s*$", package_dump, re.MULTILINE)
    version_code_match = re.search(r"^\s*versionCode=(\d+)\b", package_dump, re.MULTILINE)
    if version_name_match is None or version_code_match is None:
        raise AcceptanceError("Could not read the installed package version from Android")
    return version_name_match.group(1), int(version_code_match.group(1))


def screen_size() -> tuple[int, int]:
    output = command(["adb", "shell", "wm", "size"])
    match = re.search(r"(?:Override|Physical) size:\s*(\d+)x(\d+)", output)
    if match is None:
        raise AcceptanceError("Could not read the emulator screen size")
    return int(match.group(1)), int(match.group(2))


def preserve_malformed_ui(evidence_dir: Path, xml: str) -> Path:
    attempt = 1
    while True:
        destination = evidence_dir / f"malformed-ui-attempt-{attempt}.xml"
        try:
            with destination.open("x", encoding="utf-8") as output:
                output.write(xml)
            return destination
        except FileExistsError:
            attempt += 1


def dump_ui(evidence_dir: Path) -> list[dict[str, str]]:
    malformed_evidence: list[str] = []
    for attempt in range(1, UI_DUMP_ATTEMPTS + 1):
        command(["adb", "shell", "uiautomator", "dump", DEVICE_UI_XML], timeout=30)
        xml = command(["adb", "exec-out", "cat", DEVICE_UI_XML], timeout=30)
        (evidence_dir / "last-ui.xml").write_text(xml, encoding="utf-8")
        try:
            root = ET.fromstring(xml)
        except ET.ParseError as exception:
            malformed_evidence.append(preserve_malformed_ui(evidence_dir, xml).name)
            if attempt < UI_DUMP_ATTEMPTS:
                time.sleep(UI_DUMP_RETRY_DELAY_SECONDS)
                continue
            samples = ", ".join(malformed_evidence)
            raise AcceptanceError(
                f"Android returned malformed UI hierarchy XML after {UI_DUMP_ATTEMPTS} attempts; "
                f"last parse error: {exception}; preserved samples: {samples}"
            ) from exception
        return [dict(node.attrib) for node in root.iter("node")]
    raise AssertionError("UI hierarchy retry loop exhausted without a result")


def node_center(node: dict[str, str]) -> tuple[int, int]:
    match = BOUNDS.fullmatch(node.get("bounds", ""))
    if match is None:
        raise AcceptanceError("Android UI node did not expose usable bounds")
    left, top, right, bottom = (int(value) for value in match.groups())
    return (left + right) // 2, (top + bottom) // 2


def tap_node(node: dict[str, str]) -> None:
    x, y = node_center(node)
    command(["adb", "shell", "input", "tap", str(x), str(y)])


def find_text_node(nodes: Iterable[dict[str, str]], text: str) -> dict[str, str] | None:
    matches = [node for node in nodes if node.get("text") == text or node.get("content-desc") == text]
    if not matches:
        return None
    return next((node for node in matches if node.get("clickable") == "true"), matches[0])


def wait_for_text(text: str, evidence_dir: Path, timeout: int) -> dict[str, str]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        node = find_text_node(dump_ui(evidence_dir), text)
        if node is not None:
            return node
        time.sleep(1)
    raise AcceptanceError(f"Timed out waiting for Android UI text {text!r}")


def wait_for_package(package_name: str, evidence_dir: Path, timeout: int) -> list[dict[str, str]]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        nodes = dump_ui(evidence_dir)
        if any(node.get("package") == package_name for node in nodes):
            return nodes
        time.sleep(1)
    raise AcceptanceError(f"Timed out waiting for Android package {package_name!r} to become visible")


def tap_text(text: str, evidence_dir: Path, timeout: int = 20) -> None:
    tap_node(wait_for_text(text, evidence_dir, timeout))
    time.sleep(1)


def scroll_until_tap(text: str, evidence_dir: Path, attempts: int = 10) -> None:
    width, height = screen_size()
    for _ in range(attempts):
        node = find_text_node(dump_ui(evidence_dir), text)
        if node is not None:
            tap_node(node)
            time.sleep(1)
            return
        command(
            [
                "adb",
                "shell",
                "input",
                "swipe",
                str(width // 2),
                str(height * 3 // 4),
                str(width // 2),
                str(height // 3),
                "350",
            ]
        )
        time.sleep(1)
    raise AcceptanceError(f"Could not find Android UI text {text!r} after scrolling")


def capture_screen(destination: Path) -> None:
    try:
        image = subprocess.run(
            ["adb", "exec-out", "screencap", "-p"],
            check=True,
            capture_output=True,
            timeout=30,
        ).stdout
        destination.write_bytes(image)
    except (OSError, subprocess.SubprocessError):
        return


def node_has_label(node: dict[str, str], label: str) -> bool:
    return node.get("text") == label or node.get("content-desc") == label


def require_unknown_sources_control(nodes: Iterable[dict[str, str]]) -> dict[str, str]:
    settings_nodes = [node for node in nodes if node.get("package") == SETTINGS_PACKAGE]
    required_labels = (
        UNKNOWN_SOURCES_TITLE,
        UNKNOWN_SOURCES_ALLOW_LABEL,
        APPLICATION_LABEL,
    )
    if not all(any(node_has_label(node, label) for node in settings_nodes) for label in required_labels):
        raise AcceptanceError(
            "Android Settings did not identify the Open Aria Echo Unknown Sources page"
        )

    checkables = [node for node in settings_nodes if node.get("checkable") == "true"]
    if not checkables:
        raise AcceptanceError("Unknown Sources page did not expose a checkable permission control")
    if len(checkables) != 1:
        raise AcceptanceError(
            f"Unknown Sources page is ambiguous: found {len(checkables)} checkable controls"
        )

    control = checkables[0]
    classic_switch = "Switch" in control.get("class", "") or control.get(
        "resource-id",
        "",
    ).endswith("switch_widget")
    compose_switch = (
        control.get("class") == "android.view.View" and control.get("clickable") == "true"
    )
    if not classic_switch and not compose_switch:
        raise AcceptanceError("Unknown Sources page exposed an unsupported checkable control")
    return control


def unknown_sources_control_identity(control: dict[str, str]) -> tuple[str, ...]:
    return tuple(
        control.get(attribute, "")
        for attribute in ("package", "resource-id", "class", "text", "content-desc", "bounds")
    )


def wait_for_unknown_sources_confirmation(
    evidence_dir: Path,
    expected_identity: tuple[str, ...],
) -> dict[str, str]:
    deadline = time.monotonic() + UNKNOWN_SOURCES_CONFIRM_TIMEOUT_SECONDS
    last_error: AcceptanceError | None = None
    while time.monotonic() < deadline:
        try:
            control = require_unknown_sources_control(dump_ui(evidence_dir))
        except AcceptanceError as exception:
            last_error = exception
            time.sleep(UNKNOWN_SOURCES_POLL_INTERVAL_SECONDS)
            continue
        if unknown_sources_control_identity(control) != expected_identity:
            raise AcceptanceError("Unknown Sources permission control identity changed after confirmation")
        if control.get("checked") == "true":
            return control
        last_error = AcceptanceError("Unknown Sources permission control did not become checked")
        time.sleep(UNKNOWN_SOURCES_POLL_INTERVAL_SECONDS)

    detail = str(last_error) if last_error is not None else "no confirmation hierarchy was available"
    raise AcceptanceError(
        f"Timed out waiting for Unknown Sources confirmation; last reason: {detail}"
    )


def enable_unknown_sources_after_app_handoff(evidence_dir: Path) -> str:
    navigation = ""
    deadline = time.monotonic() + UNKNOWN_SOURCES_PAGE_TIMEOUT_SECONDS
    last_page_error: AcceptanceError | None = None
    while time.monotonic() < deadline:
        nodes = dump_ui(evidence_dir)
        packages = {node.get("package", "") for node in nodes}
        if SETTINGS_PACKAGE not in packages:
            installer_visible = any("packageinstaller" in package for package in packages)
            if installer_visible:
                settings_button = next(
                    (
                        node
                        for node in nodes
                        if node.get("clickable") == "true"
                        and (
                            node.get("text", "").casefold() == "settings"
                            or "settings" in node.get("resource-id", "").casefold()
                        )
                    ),
                    None,
                )
                if settings_button is not None:
                    capture_screen(evidence_dir / "installer-security-prompt.png")
                    tap_node(settings_button)
                    navigation = "installer_security_prompt_after_baseline_app_handoff"
                    time.sleep(1)
                    continue
            time.sleep(1)
            continue

        try:
            control = require_unknown_sources_control(nodes)
        except AcceptanceError as exception:
            last_page_error = exception
            time.sleep(UNKNOWN_SOURCES_POLL_INTERVAL_SECONDS)
            continue
        if not navigation:
            navigation = "direct_baseline_app_permission_guidance"
        if control.get("checked") == "true":
            raise AcceptanceError(
                "Unknown Sources was already enabled; the system confirmation was not exercised"
            )
        control_identity = unknown_sources_control_identity(control)
        tap_node(control)
        wait_for_unknown_sources_confirmation(evidence_dir, control_identity)
        capture_screen(evidence_dir / "unknown-sources-enabled.png")
        command(["adb", "shell", "input", "keyevent", "4"])
        return navigation
    detail = str(last_page_error) if last_page_error is not None else "Android Settings was not visible"
    raise AcceptanceError(
        f"Timed out waiting for the Open Aria Echo Unknown Sources page; last reason: {detail}"
    )


def wait_for_installer_and_confirm(evidence_dir: Path, timeout: int = 150) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        nodes = dump_ui(evidence_dir)
        packages = {node.get("package", "") for node in nodes}
        installer_visible = any("packageinstaller" in package for package in packages)
        if installer_visible:
            for label in ("Update", "Install"):
                node = find_text_node(nodes, label)
                if node is not None:
                    capture_screen(evidence_dir / "installer-handoff.png")
                    tap_node(node)
                    return
        time.sleep(1)
    raise AcceptanceError("The baseline app never handed the verified APK to Android's package installer")


def wait_for_installed_version(expected_name: str, expected_code: int, timeout: int = 150) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            actual_name, actual_code = device_package_identity()
            if actual_name == expected_name and actual_code == expected_code:
                return
        except AcceptanceError:
            pass
        time.sleep(2)
    raise AcceptanceError(
        f"Android did not finish installing version {expected_name}+{expected_code} after confirmation"
    )


def validate_manifest(
    manifest: object,
    repository: str,
    release_tag: str,
    expected_version_name: str,
    expected_version_code: int,
) -> dict[str, object]:
    if not isinstance(manifest, dict):
        raise AcceptanceError("Published update manifest must be a JSON object")
    if manifest.get("schema") != MANIFEST_SCHEMA:
        raise AcceptanceError("Published update manifest has the wrong schema")
    if manifest.get("version") != expected_version_name or manifest.get("versionCode") != expected_version_code:
        raise AcceptanceError("Published update manifest does not identify the release under test")
    if manifest.get("packageName") != PACKAGE_NAME:
        raise AcceptanceError("Published update manifest has the wrong package name")
    certificate = str(manifest.get("signingCertificateSha256", "")).lower()
    if HEX_64.fullmatch(certificate) is None:
        raise AcceptanceError("Published update manifest has no valid signing certificate identity")
    try:
        apk = manifest["android"]["apk"]  # type: ignore[index]
        apk_url = str(apk["url"])
        apk_sha256 = str(apk["sha256"]).lower()
        apk_bytes = int(apk["bytes"])
    except (KeyError, TypeError, ValueError) as exception:
        raise AcceptanceError("Published update manifest has incomplete APK metadata") from exception
    parsed = urlparse(apk_url)
    expected_prefix = f"/Alpenl/openaria-echo-mobile/releases/download/{release_tag}/"
    asset_name = parsed.path.removeprefix(expected_prefix)
    if (
        parsed.scheme != "https"
        or parsed.hostname != "github.com"
        or not parsed.path.startswith(expected_prefix)
        or not parsed.path.endswith(".apk")
        or not asset_name
        or "/" in asset_name
        or parsed.query
        or parsed.fragment
        or parsed.username
        or repository != "Alpenl/openaria-echo-mobile"
    ):
        raise AcceptanceError("Published update manifest APK URL is not bound to this release")
    if HEX_64.fullmatch(apk_sha256) is None or apk_bytes <= 0:
        raise AcceptanceError("Published update manifest has invalid APK hash or size")
    return {
        "certificate": certificate,
        "apkUrl": apk_url,
        "apkSha256": apk_sha256,
        "apkBytes": apk_bytes,
    }


def run_acceptance(args: argparse.Namespace) -> dict[str, object]:
    evidence_dir = Path(args.evidence_dir).resolve()
    evidence_dir.mkdir(parents=True, exist_ok=True)
    require_tools(("adb", "apkanalyzer", "apksigner"))
    command(["adb", "wait-for-device"], timeout=120)

    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", args.baseline_tag):
        raise AcceptanceError("Baseline tag must use vX.Y.Z form")
    if args.baseline_tag != f"v{args.baseline_version_name}" or args.baseline_version_code <= 0:
        raise AcceptanceError("Baseline tag, versionName, and versionCode are inconsistent")
    if (
        not args.baseline_apk_name.endswith(".apk")
        or Path(args.baseline_apk_name).name != args.baseline_apk_name
    ):
        raise AcceptanceError("Baseline APK name must identify one Release APK asset")
    if args.baseline_tag == args.release_tag or args.baseline_version_code >= args.expected_version_code:
        raise AcceptanceError("Baseline must be an older production Release than the candidate")
    if not re.fullmatch(r"[0-9]+", args.baseline_release_id):
        raise AcceptanceError("Baseline Release ID must be a positive decimal identity")
    if int(args.baseline_release_id) <= 0 or HEX_64.fullmatch(args.baseline_apk_sha256) is None:
        raise AcceptanceError("Baseline Release identity or APK digest is invalid")
    if HEX_64.fullmatch(args.candidate_apk_sha256) is None:
        raise AcceptanceError("Candidate APK digest is invalid")
    if re.fullmatch(r"[0-9a-f]{40}", args.source_commit) is None:
        raise AcceptanceError("Source commit must be an exact Git commit identity")
    if not args.run_id.isdecimal() or not args.run_attempt.isdecimal():
        raise AcceptanceError("Actions run identity is invalid")
    if args.legacy_bootstrap_authorized not in {"true", "false"}:
        raise AcceptanceError("Legacy bootstrap authorization must be a boolean identity")
    legacy_bootstrap_authorized = args.legacy_bootstrap_authorized == "true"

    candidate_manifest_path = Path(args.candidate_manifest_path).resolve()
    if not candidate_manifest_path.is_file():
        raise AcceptanceError("The exact staged candidate manifest is unavailable")
    baseline_apk_url = (
        f"https://github.com/{args.repository}/releases/download/"
        f"{args.baseline_tag}/{args.baseline_apk_name}"
    )
    with tempfile.TemporaryDirectory(prefix="openaria-android-upgrade-") as temporary:
        temp_dir = Path(temporary)
        baseline_apk = temp_dir / args.baseline_apk_name
        installed_apk = temp_dir / "installed-base.apk"

        try:
            manifest_json = json.loads(candidate_manifest_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exception:
            raise AcceptanceError("Staged update manifest is not valid JSON") from exception
        target = validate_manifest(
            manifest_json,
            args.repository,
            args.release_tag,
            args.expected_version_name,
            args.expected_version_code,
        )
        if target["apkSha256"] != args.candidate_apk_sha256:
            raise AcceptanceError("Staged manifest is not bound to the exact candidate APK digest")

        download(baseline_apk_url, baseline_apk)
        baseline_package, baseline_name, baseline_code = apk_identity(baseline_apk)
        if (baseline_package, baseline_name, baseline_code) != (
            PACKAGE_NAME,
            args.baseline_version_name,
            args.baseline_version_code,
        ):
            raise AcceptanceError("The published baseline APK does not have the expected production identity")
        baseline_signer = apk_signer(baseline_apk)
        baseline_sha256 = sha256(baseline_apk)
        if baseline_sha256 != args.baseline_apk_sha256:
            raise AcceptanceError("Downloaded baseline APK does not match its frozen Release digest")
        if baseline_signer != target["certificate"]:
            raise AcceptanceError("The baseline and candidate release signing certificates do not match")

        command(["adb", "install", "--no-streaming", "-r", str(baseline_apk)], timeout=180)
        old_name, old_code = device_package_identity()
        if (old_name, old_code) != (args.baseline_version_name, args.baseline_version_code):
            raise AcceptanceError("Android did not install the previous production baseline")

        command(
            ["adb", "shell", "pm", "grant", PACKAGE_NAME, "android.permission.POST_NOTIFICATIONS"],
            timeout=30,
        )
        command(["adb", "shell", "am", "force-stop", PACKAGE_NAME])
        command(
            [
                "adb",
                "shell",
                "monkey",
                "-p",
                PACKAGE_NAME,
                "-c",
                "android.intent.category.LAUNCHER",
                "1",
            ],
            timeout=30,
        )

        # Production baselines default their own in-app locale to Chinese. Switch it
        # through the app UI so this contract is independent of the host locale.
        tap_text("\u673a\u8eab", evidence_dir, timeout=30)
        scroll_until_tap("English", evidence_dir)
        scroll_until_tap("Check for updates", evidence_dir)
        wait_for_text(
            f"{args.expected_version_name}+{args.expected_version_code}",
            evidence_dir,
            timeout=90,
        )
        capture_screen(evidence_dir / "candidate-available.png")
        tap_text("Install update", evidence_dir, timeout=20)
        unknown_sources_navigation = enable_unknown_sources_after_app_handoff(evidence_dir)
        baseline_has_verified_retry = args.baseline_version_code >= VERIFIED_RETRY_MIN_VERSION_CODE
        if (
            baseline_has_verified_retry
            and unknown_sources_navigation != "direct_baseline_app_permission_guidance"
        ):
            raise AcceptanceError(
                "The verified-retry baseline did not directly guide the user to Unknown Sources"
            )
        verified_retry_observed = False
        second_install_tap = False
        if baseline_has_verified_retry:
            wait_for_package(PACKAGE_NAME, evidence_dir, timeout=30)
            wait_for_text("The update is verified and ready to install.", evidence_dir, timeout=30)
            verified_retry_observed = True
            scroll_until_tap("Install update", evidence_dir)
            second_install_tap = True
        wait_for_installer_and_confirm(evidence_dir)
        wait_for_installed_version(args.expected_version_name, args.expected_version_code)
        capture_screen(evidence_dir / "candidate-installed.png")

        package_paths = command(["adb", "shell", "pm", "path", PACKAGE_NAME]).splitlines()
        base_paths = [line.removeprefix("package:").strip() for line in package_paths if line.endswith("base.apk")]
        if len(base_paths) != 1:
            raise AcceptanceError(f"Expected one installed base APK; found {len(base_paths)}")
        command(["adb", "pull", base_paths[0], str(installed_apk)], timeout=120)

        installed_sha256 = sha256(installed_apk)
        installed_bytes = installed_apk.stat().st_size
        installed_package, installed_name, installed_code = apk_identity(installed_apk)
        installed_signer = apk_signer(installed_apk)
        if installed_sha256 != target["apkSha256"] or installed_bytes != target["apkBytes"]:
            raise AcceptanceError("The installed base APK bytes do not match the published manifest")
        if (installed_package, installed_name, installed_code) != (
            PACKAGE_NAME,
            args.expected_version_name,
            args.expected_version_code,
        ):
            raise AcceptanceError("The installed base APK identity does not match the published release")
        if installed_signer != target["certificate"] or installed_signer != baseline_signer:
            raise AcceptanceError("The installed APK certificate does not match the release chain")

        actions_run_id = os.environ.get("GITHUB_RUN_ID", "")
        actions_run_attempt = os.environ.get("GITHUB_RUN_ATTEMPT", "")
        if actions_run_id and actions_run_id != args.run_id:
            raise AcceptanceError("Actions run ID differs from the staged acceptance authority")
        if actions_run_attempt and actions_run_attempt != args.run_attempt:
            raise AcceptanceError("Actions run attempt differs from the staged acceptance authority")
        actions_server = os.environ.get("GITHUB_SERVER_URL", "https://github.com").rstrip("/")
        actions_run_url = (
            f"{actions_server}/{args.repository}/actions/runs/{actions_run_id}"
            if actions_run_id
            else None
        )
        evidence: dict[str, object] = {
            "schema": "openaria.echo.mobile.android-in-app-update-acceptance.v1",
            "completedAt": now_utc(),
            "repository": args.repository,
            "releaseTag": args.release_tag,
            "actionsRunId": args.run_id,
            "actionsRunAttempt": args.run_attempt,
            "actionsRunUrl": actions_run_url,
            "sourceCommit": args.source_commit,
            "publicationState": "prepublish_staged_candidate",
            "emulatorApiLevel": command(["adb", "shell", "getprop", "ro.build.version.sdk"]),
            "baseline": {
                "releaseId": int(args.baseline_release_id),
                "releaseTag": args.baseline_tag,
                "versionName": old_name,
                "versionCode": old_code,
                "apkUrl": baseline_apk_url,
                "apkSha256": baseline_sha256,
                "apkBytes": baseline_apk.stat().st_size,
                "certificateMatchesManifest": baseline_signer == target["certificate"],
                "legacyBootstrapAuthorized": legacy_bootstrap_authorized,
            },
            "candidate": {
                "releaseTag": args.release_tag,
                "versionName": installed_name,
                "versionCode": installed_code,
                "apkUrl": target["apkUrl"],
                "apkSha256": installed_sha256,
                "apkBytes": installed_bytes,
                "certificateMatchesManifest": installed_signer == target["certificate"],
                "certificateMatchesBaseline": installed_signer == baseline_signer,
            },
            "flow": {
                "publishedManifestAccessed": False,
                "stagedCandidateManifestAccessed": True,
                "baselineReleaseApkInstalled": True,
                "targetVersionDisplayedByOldApp": True,
                "targetVersionDisplayedByBaselineApp": True,
                "candidateDownloadedByOldApp": True,
                "candidateDownloadedByBaselineApp": True,
                "unknownSourcesPromptOpenedByBaselineApp": True,
                "unknownSourcesNavigation": unknown_sources_navigation,
                "baselineContainsVerifiedRetryUpdater": baseline_has_verified_retry,
                "baselineSupportsVerifiedRetry": verified_retry_observed,
                "secondInstallTapAfterPermission": second_install_tap,
                "installerHandoffObserved": True,
                "systemInstallerConfirmationClicked": True,
                "unknownSourcesEnabledThroughSystemUi": True,
                "unknownSourcesSwitchInitiallyOff": True,
                "unknownSourcesSwitchClicked": True,
                "installedPackageUpgraded": True,
                "manualCandidateDownload": False,
                "candidateUpdaterExercised": verified_retry_observed,
                "eligibleForIssue39Closure": verified_retry_observed and second_install_tap,
            },
        }
        (evidence_dir / "android-in-app-update-evidence.json").write_text(
            json.dumps(evidence, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        return evidence


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", required=True)
    parser.add_argument("--release-tag", required=True)
    parser.add_argument("--expected-version-name", required=True)
    parser.add_argument("--expected-version-code", required=True, type=int)
    parser.add_argument("--baseline-tag", required=True)
    parser.add_argument("--baseline-version-name", required=True)
    parser.add_argument("--baseline-version-code", required=True, type=int)
    parser.add_argument("--baseline-apk-name", required=True)
    parser.add_argument("--baseline-release-id", required=True)
    parser.add_argument("--baseline-apk-sha256", required=True)
    parser.add_argument("--legacy-bootstrap-authorized", required=True)
    parser.add_argument("--candidate-manifest-path", required=True)
    parser.add_argument("--candidate-apk-sha256", required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--run-attempt", required=True)
    parser.add_argument("--evidence-dir", default="android-upgrade-evidence")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    evidence_dir = Path(args.evidence_dir).resolve()
    evidence_dir.mkdir(parents=True, exist_ok=True)
    try:
        evidence = run_acceptance(args)
    except Exception as exception:  # noqa: BLE001 - persist diagnostics for CI triage.
        message = sanitize(str(exception))
        capture_screen(evidence_dir / "failure.png")
        diagnostics_error: str | None = None
        try:
            dump_ui(evidence_dir)
        except Exception as diagnostics_exception:  # noqa: BLE001 - keep the original acceptance error.
            diagnostics_error = sanitize(str(diagnostics_exception))
        failure = {"failedAt": now_utc(), "error": message}
        if diagnostics_error is not None:
            failure["diagnosticsError"] = diagnostics_error
        (evidence_dir / "failure.json").write_text(
            json.dumps(failure, indent=2) + "\n",
            encoding="utf-8",
        )
        print(f"::error::{message}", file=sys.stderr)
        return 1
    safe_summary = {
        "releaseTag": evidence["releaseTag"],
        "baseline": evidence["baseline"],
        "candidate": evidence["candidate"],
        "flow": evidence["flow"],
    }
    print(json.dumps(safe_summary, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
