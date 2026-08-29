"""Behavior tests for the Android current-APK visual evidence runner."""

from __future__ import annotations

import json
import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT_PATH = Path(__file__).with_name("android-current-ui-gate.sh")
GESTURAL = "com.android.internal.systemui.navbar.gestural"
THREE_BUTTON = "com.android.internal.systemui.navbar.threebutton"
CORNER_CUTOUT = "com.android.internal.display.cutout.emulation.corner"
TALL_CUTOUT = "com.android.internal.display.cutout.emulation.tall"


FAKE_ADB = r'''#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import os
import shutil
import sys
from pathlib import Path


state_path = Path(os.environ["FAKE_ANDROID_STATE"])
device_root = Path(os.environ["FAKE_DEVICE_FILES"])


def load_state() -> dict[str, object]:
    return json.loads(state_path.read_text(encoding="utf-8"))


def save_state(state: dict[str, object]) -> None:
    state_path.write_text(json.dumps(state, sort_keys=True), encoding="utf-8")


def device_path(remote_path: str) -> Path:
    if not remote_path.startswith("/data/local/tmp/openaria-current-ui"):
        raise ValueError(f"unexpected fake device path: {remote_path}")
    return device_root / remote_path.removeprefix("/")


def overlay_list(state: dict[str, object]) -> None:
    packages = (
        "com.android.internal.systemui.navbar.gestural",
        "com.android.internal.systemui.navbar.threebutton",
        "com.android.internal.display.cutout.emulation.corner",
        "com.android.internal.display.cutout.emulation.tall",
    )
    enabled = {state.get("navigation_overlay"), state.get("cutout_overlay")}
    for package in packages:
        marker = "[X]" if package in enabled else "[ ]"
        print(f"{marker} {package}")


def shell(args: list[str]) -> int:
    state = load_state()
    if args == ["wm", "size"]:
        print(f"Physical size: {state['physical_size']}")
        if state.get("size_override"):
            print(f"Override size: {state['size_override']}")
        return 0
    if args[:2] == ["wm", "size"]:
        state["size_override"] = None if args[2] == "reset" else args[2]
        save_state(state)
        return 0
    if args == ["wm", "density"]:
        print(f"Physical density: {state['physical_density']}")
        if state.get("density_override") is not None:
            print(f"Override density: {state['density_override']}")
        return 0
    if args[:2] == ["wm", "density"]:
        if args[2] == "reset":
            state["density_override"] = None
        elif not (os.environ.get("FAKE_IGNORE_320_DENSITY") == "1" and args[2] == "320"):
            state["density_override"] = int(args[2])
        save_state(state)
        return 0
    if args[:3] == ["wm", "user-rotation", "lock"]:
        value = int(args[3])
        state["accelerometer_rotation"] = 0
        state["user_rotation"] = value
        if (
            os.environ.get("FAKE_REQUIRE_FIXED_ROTATION") != "1"
            or state["fixed_to_user_rotation"] == "enabled"
        ):
            state["surface_rotation"] = value
        save_state(state)
        return 0
    if args == ["wm", "user-rotation", "free"]:
        state["accelerometer_rotation"] = 1
        save_state(state)
        return 0
    if args == ["wm", "fixed-to-user-rotation"]:
        print(state["fixed_to_user_rotation"])
        return 0
    if args[:2] == ["wm", "fixed-to-user-rotation"]:
        state["fixed_to_user_rotation"] = args[2]
        save_state(state)
        return 0
    if args[:4] == ["settings", "get", "system", "accelerometer_rotation"]:
        print(state["accelerometer_rotation"])
        return 0
    if args[:4] == ["settings", "get", "system", "user_rotation"]:
        print(state["user_rotation"])
        return 0
    if args[:4] == ["settings", "put", "system", "accelerometer_rotation"]:
        value = int(args[4])
        if value == 1 and os.environ.get("FAKE_FAIL_RESTORE_ACCELEROMETER") == "1":
            return 41
        state["accelerometer_rotation"] = value
        save_state(state)
        return 0
    if args[:4] == ["settings", "put", "system", "user_rotation"]:
        value = int(args[4])
        state["user_rotation"] = value
        if (
            state["accelerometer_rotation"] == 0
            and os.environ.get("FAKE_SETTINGS_ROTATION_STALE") != "1"
            and (
                os.environ.get("FAKE_REQUIRE_FIXED_ROTATION") != "1"
                or state["fixed_to_user_rotation"] == "enabled"
            )
        ):
            state["surface_rotation"] = value
        save_state(state)
        return 0
    if args == ["dumpsys", "input"]:
        print("Input Reader State:")
        if os.environ.get("FAKE_API35_ROTATION_OUTPUT") == "1":
            print("  InputDeviceOrientation: Rotation0")
        else:
            print(f"  SurfaceOrientation: {state['surface_rotation']}")
        return 0
    if args == ["dumpsys", "window", "displays"]:
        if os.environ.get("FAKE_API35_ROTATION_OUTPUT") == "1":
            print(
                f"  mRotation={state['surface_rotation']} "
                "mDeferredRotationPauseCount=0"
            )
        else:
            print(f"Display rotation={state['surface_rotation']} cutout={state.get('cutout_overlay')}")
        return 0
    if args == ["dumpsys", "window", "windows"]:
        print("mCurrentFocus=Window{42 u0 com.openaria.openaria_echo_mobile/.MainActivity}")
        return 0
    if args == ["cmd", "overlay", "list"]:
        overlay_list(state)
        return 0
    if args == ["cmd", "overlay", "lookup", "android", "android:integer/config_navBarInteractionMode"]:
        print(2 if state.get("navigation_overlay", "").endswith("gestural") else 0)
        return 0
    if args == ["cmd", "overlay", "lookup", "android", "android:string/config_mainBuiltInDisplayCutout"]:
        print("M 0,0 L 10,0 L 10,10 Z" if state.get("cutout_overlay") else '""')
        return 0
    if args[:4] == ["cmd", "overlay", "enable-exclusive", "--category"]:
        package = args[4]
        if ".navbar." in package:
            state["navigation_overlay"] = package
        elif ".cutout.emulation." in package:
            state["cutout_overlay"] = package
        save_state(state)
        return 0
    if args[:3] == ["cmd", "overlay", "enable"]:
        package = args[3]
        if ".navbar." in package:
            state["navigation_overlay"] = package
        elif ".cutout.emulation." in package:
            state["cutout_overlay"] = package
        save_state(state)
        return 0
    if args[:3] == ["cmd", "overlay", "disable"]:
        package = args[3]
        if state.get("navigation_overlay") == package:
            state["navigation_overlay"] = None
        if state.get("cutout_overlay") == package:
            state["cutout_overlay"] = None
        save_state(state)
        return 0
    if args[:2] == ["am", "force-stop"]:
        return 0
    if args[:2] in (["rm", "-rf"], ["rm", "-f"]):
        target = device_path(args[2])
        if target.is_dir():
            shutil.rmtree(target)
        else:
            target.unlink(missing_ok=True)
        return 0
    if args[:2] == ["mkdir", "-p"]:
        device_path(args[2]).mkdir(parents=True, exist_ok=True)
        return 0
    if args[:2] == ["test", "!"] and args[2] == "-e":
        return 1 if device_path(args[3]).exists() else 0
    print(f"unexpected fake adb shell command: {args!r}", file=sys.stderr)
    return 98


def pull(args: list[str]) -> int:
    source, destination = args
    filename = Path(source).name
    profile = next(
        candidate
        for candidate in (
            "small_gesture",
            "small_three_button",
            "landscape_gesture",
            "cutout_three_button",
        )
        if filename.startswith(candidate + "-")
    )
    if (
        os.environ.get("FAKE_PULL_FAIL_PROFILE") == profile
        and source.endswith(".png")
    ):
        return 23
    source_path = device_path(source)
    if not source_path.is_file():
        return 29
    destination_path = Path(destination)
    destination_path.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source_path, destination_path)
    return 0


def exec_out(args: list[str]) -> int:
    print("run-as: unknown package: com.openaria.openaria_echo_mobile")
    return 29


arguments = sys.argv[1:]
with Path(os.environ["FAKE_ADB_CALLS"]).open("a", encoding="utf-8") as calls:
    calls.write(json.dumps(arguments) + "\n")
if arguments == ["wait-for-device"]:
    raise SystemExit(0)
if arguments and arguments[0] == "shell":
    raise SystemExit(shell(arguments[1:]))
if arguments and arguments[0] == "exec-out":
    raise SystemExit(exec_out(arguments[1:]))
if arguments and arguments[0] == "pull":
    raise SystemExit(pull(arguments[1:]))
print(f"unexpected fake adb command: {arguments!r}", file=sys.stderr)
raise SystemExit(99)
'''


FAKE_GRADLEW = r'''#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import os
import sys
from pathlib import Path


profile = next(
    argument.rsplit("=", 1)[1]
    for argument in sys.argv[1:]
    if "visualProfile=" in argument
)
nonce = next(
    argument.rsplit("=", 1)[1]
    for argument in sys.argv[1:]
    if "evidenceNonce=" in argument
)
result = Path("app/build/outputs/androidTest-results/connected/debug")
result.mkdir(parents=True, exist_ok=True)
(tests, failures, errors, skipped) = (1, 0, 0, 0)
if os.environ.get("FAKE_ZERO_TEST_PROFILE") == profile:
    tests = 0
if os.environ.get("FAKE_SKIPPED_TEST_PROFILE") == profile:
    skipped = 1
(result / f"TEST-{profile}.xml").write_text(
    (
        '<testsuite '
        'name="com.openaria.openaria_echo_mobile.CurrentUiVisualGateTest" '
        f'tests="{tests}" failures="{failures}" errors="{errors}" skipped="{skipped}">'
        '<testcase '
        'name="currentApkKeepsSemanticsAndGeometryInsideTheRealSystemSafeArea" '
        'classname="com.openaria.openaria_echo_mobile.CurrentUiVisualGateTest" />'
        '</testsuite>'
    ),
    encoding="utf-8",
)
if os.environ.get("FAKE_EXTRA_XML_PROFILE") == profile:
    (result / f"TEST-{profile}-extra.xml").write_text(
        (
            '<testsuite name="com.openaria.openaria_echo_mobile.CurrentUiVisualGateTest" '
            'tests="1" failures="0" errors="0" skipped="0">'
            '<testcase '
            'name="currentApkKeepsSemanticsAndGeometryInsideTheRealSystemSafeArea" '
            'classname="com.openaria.openaria_echo_mobile.CurrentUiVisualGateTest" />'
            '</testsuite>'
        ),
        encoding="utf-8",
    )
with Path(os.environ["FAKE_GRADLE_CALLS"]).open("a", encoding="utf-8") as calls:
    calls.write(profile + "\n")
print(f"fake instrumentation profile={profile}")
device_root = Path(os.environ["FAKE_DEVICE_FILES"])
remote_root = device_root / "data/local/tmp/openaria-current-ui"
remote_root.mkdir(parents=True, exist_ok=True)
if os.environ.get("FAKE_SKIP_EVIDENCE_PROFILE") != profile:
    screenshot_bytes = b"fake-current-apk-png:" + profile.encode("ascii")
    screenshot_sha256 = hashlib.sha256(screenshot_bytes).hexdigest()
    if os.environ.get("FAKE_BAD_HASH_PROFILE") == profile:
        screenshot_sha256 = "0" * 64
    evidence_nonce = "0" * 32 if os.environ.get("FAKE_STALE_NONCE_PROFILE") == profile else nonce
    png_path = remote_root / f"{profile}-{nonce}.png"
    json_path = remote_root / f"{profile}-{nonce}.json"
    if os.environ.get("FAKE_EMPTY_PNG_PROFILE") == profile:
        png_path.write_bytes(b"")
    else:
        png_path.write_bytes(screenshot_bytes)
    if os.environ.get("FAKE_PARTIAL_EVIDENCE_PROFILE") != profile:
        json_path.write_text(
            json.dumps(
                {
                    "schema": "openaria.echo.mobile.current-ui-evidence.v1",
                    "profile": profile,
                    "evidenceNonce": evidence_nonce,
                    "targetPackage": "com.openaria.openaria_echo_mobile",
                    "targetWindowFocused": True,
                    "screenshotPngSha256": screenshot_sha256,
                }
            ),
            encoding="utf-8",
        )
if os.environ.get("FAKE_GRADLE_FAIL_PROFILE") == profile:
    raise SystemExit(17)
raise SystemExit(0)
'''


class AndroidCurrentUiGateBehaviorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.state_path = self.root / "state.json"
        self.calls_path = self.root / "gradle-calls.txt"
        self.adb_calls_path = self.root / "adb-calls.jsonl"
        self.device_files = self.root / "device-files"
        self.evidence = self.root / "evidence"
        self.initial_state = {
            "physical_size": "1080x1920",
            "size_override": "900x1600",
            "physical_density": 420,
            "density_override": 360,
            "accelerometer_rotation": 1,
            "user_rotation": 2,
            "surface_rotation": 0,
            "fixed_to_user_rotation": "default",
            "navigation_overlay": THREE_BUTTON,
            "cutout_overlay": CORNER_CUTOUT,
        }
        self.state_path.write_text(json.dumps(self.initial_state), encoding="utf-8")
        self.fake_adb = self.write_executable("fake-adb", FAKE_ADB)
        self.fake_gradle = self.write_executable("fake-gradlew", FAKE_GRADLEW)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_executable(self, name: str, body: str) -> Path:
        path = self.root / name
        path.write_text(body, encoding="utf-8")
        path.chmod(path.stat().st_mode | stat.S_IXUSR)
        return path

    def run_gate(self, **overrides: str) -> subprocess.CompletedProcess[str]:
        environment = os.environ.copy()
        environment.update(
            {
                "OPENARIA_UI_GATE_ADB": str(self.fake_adb),
                "OPENARIA_UI_GATE_GRADLEW": str(self.fake_gradle),
                "OPENARIA_UI_GATE_MAX_ATTEMPTS": "2",
                "OPENARIA_UI_GATE_POLL_INTERVAL_SECONDS": "0",
                "FAKE_ANDROID_STATE": str(self.state_path),
                "FAKE_GRADLE_CALLS": str(self.calls_path),
                "FAKE_ADB_CALLS": str(self.adb_calls_path),
                "FAKE_DEVICE_FILES": str(self.device_files),
            }
        )
        environment.update(overrides)
        return subprocess.run(
            ["bash", str(SCRIPT_PATH), str(self.evidence)],
            cwd=self.root,
            env=environment,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )

    def read_result(self, profile: str) -> str:
        return (self.evidence / f"{profile}-result.env").read_text(encoding="utf-8")

    def adb_calls(self) -> list[list[str]]:
        return [
            json.loads(line)
            for line in self.adb_calls_path.read_text(encoding="utf-8").splitlines()
        ]

    def assert_initial_state_restored(self) -> None:
        self.assertEqual(self.initial_state, json.loads(self.state_path.read_text(encoding="utf-8")))
        self.assertIn("gate=PASS", (self.evidence / "restored-state-gate.txt").read_text(encoding="utf-8"))

    def assert_failure_bundle(self, profile: str) -> None:
        self.assertTrue((self.evidence / f"{profile}-run.log").is_file())
        self.assertTrue((self.evidence / f"{profile}-final-device-state.txt").is_file())
        result_root = self.evidence / f"{profile}-androidTest-results"
        self.assertTrue(any(result_root.rglob("*.xml")), f"missing XML under {result_root}")

    def test_success_collects_all_profiles_and_restores_exact_initial_state(self) -> None:
        result = self.run_gate()

        self.assertEqual(0, result.returncode, result.stdout)
        for profile in ("small_gesture", "small_three_button", "landscape_gesture", "cutout_three_button"):
            self.assertIn("profile_exit_status=0", self.read_result(profile))
            self.assertTrue((self.evidence / f"{profile}.png").is_file())
            self.assertTrue((self.evidence / f"{profile}.json").is_file())
            evidence = json.loads((self.evidence / f"{profile}.json").read_text(encoding="utf-8"))
            self.assertRegex(evidence["evidenceNonce"], r"^[0-9a-f]{32}$")
            self.assertIn("gate=PASS", (self.evidence / f"{profile}-preflight-state.txt").read_text(encoding="utf-8"))
        self.assertTrue((self.evidence / "SHA256SUMS.txt").is_file())
        self.assert_initial_state_restored()

    def test_api35_window_rotation_fallback_runs_all_profiles_and_restores_state(self) -> None:
        result = self.run_gate(FAKE_API35_ROTATION_OUTPUT="1")

        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn("surface_rotation=0", (self.evidence / "initial-state-snapshot.env").read_text(encoding="utf-8"))
        self.assertEqual(
            ["small_gesture", "small_three_button", "landscape_gesture", "cutout_three_button"],
            self.calls_path.read_text(encoding="utf-8").splitlines(),
        )
        self.assert_initial_state_restored()

    def test_api35_uses_window_manager_rotation_when_settings_do_not_rotate_surface(self) -> None:
        result = self.run_gate(
            FAKE_API35_ROTATION_OUTPUT="1",
            FAKE_SETTINGS_ROTATION_STALE="1",
            FAKE_REQUIRE_FIXED_ROTATION="1",
        )

        self.assertEqual(0, result.returncode, result.stdout)
        self.assertEqual(
            ["small_gesture", "small_three_button", "landscape_gesture", "cutout_three_button"],
            self.calls_path.read_text(encoding="utf-8").splitlines(),
        )
        self.assertIn(["shell", "wm", "fixed-to-user-rotation", "enabled"], self.adb_calls())
        self.assert_initial_state_restored()

    def test_instrumentation_exports_to_shell_storage_before_agp_uninstalls_app(self) -> None:
        result = self.run_gate()

        self.assertEqual(0, result.returncode, result.stdout)
        for profile in ("small_gesture", "small_three_button", "landscape_gesture", "cutout_three_button"):
            self.assertTrue((self.evidence / f"{profile}.png").is_file())
            self.assertTrue((self.evidence / f"{profile}.json").is_file())
        self.assertFalse(any(call and call[0] == "exec-out" for call in self.adb_calls()))
        self.assertEqual(8, sum(1 for call in self.adb_calls() if call and call[0] == "pull"))
        self.assert_initial_state_restored()

    def test_instrumentation_failure_preserves_code_and_finally_collects_evidence(self) -> None:
        result = self.run_gate(FAKE_GRADLE_FAIL_PROFILE="small_three_button")

        self.assertEqual(17, result.returncode, result.stdout)
        profile_result = self.read_result("small_three_button")
        self.assertIn("instrumentation_status=17", profile_result)
        self.assertIn("profile_exit_status=17", profile_result)
        self.assert_failure_bundle("small_three_button")
        self.assertIn("cutout_three_button", self.calls_path.read_text(encoding="utf-8"))
        self.assertIn("final_exit_status=17", (self.evidence / "exit-summary.env").read_text(encoding="utf-8"))
        self.assert_initial_state_restored()

    def test_shell_storage_export_failure_preserves_code_and_keeps_xml_log_and_device_state(self) -> None:
        result = self.run_gate(FAKE_PULL_FAIL_PROFILE="landscape_gesture")

        self.assertEqual(23, result.returncode, result.stdout)
        profile_result = self.read_result("landscape_gesture")
        self.assertIn("png_pull_status=23", profile_result)
        self.assertIn("profile_exit_status=23", profile_result)
        self.assert_failure_bundle("landscape_gesture")
        self.assert_initial_state_restored()

    def test_missing_current_nonce_evidence_rejects_stale_files(self) -> None:
        stale_root = self.device_files / "data/local/tmp/openaria-current-ui"
        stale_root.mkdir(parents=True)
        (stale_root / "small_gesture-old.png").write_bytes(b"stale")
        (stale_root / "small_gesture-old.json").write_text("{}", encoding="utf-8")

        result = self.run_gate(FAKE_SKIP_EVIDENCE_PROFILE="small_gesture")

        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("profile_exit_status=29", self.read_result("small_gesture"))
        self.assert_initial_state_restored()

    def test_empty_current_png_is_not_accepted(self) -> None:
        result = self.run_gate(FAKE_EMPTY_PNG_PROFILE="small_three_button")

        self.assertEqual(72, result.returncode, result.stdout)
        self.assertIn("png_pull_status=72", self.read_result("small_three_button"))
        self.assert_initial_state_restored()

    def test_partial_current_evidence_is_not_accepted(self) -> None:
        result = self.run_gate(FAKE_PARTIAL_EVIDENCE_PROFILE="landscape_gesture")

        self.assertEqual(29, result.returncode, result.stdout)
        self.assertIn("json_pull_status=29", self.read_result("landscape_gesture"))
        self.assert_initial_state_restored()

    def test_mismatched_evidence_nonce_is_a_host_gate_failure(self) -> None:
        result = self.run_gate(FAKE_STALE_NONCE_PROFILE="cutout_three_button")

        self.assertEqual(75, result.returncode, result.stdout)
        self.assertIn("evidence_identity_status=75", self.read_result("cutout_three_button"))
        self.assert_initial_state_restored()

    def test_zero_test_xml_cannot_accept_otherwise_valid_evidence(self) -> None:
        result = self.run_gate(FAKE_ZERO_TEST_PROFILE="small_gesture")

        self.assertEqual(71, result.returncode, result.stdout)
        self.assertIn("android_test_results_status=71", self.read_result("small_gesture"))
        self.assert_initial_state_restored()

    def test_skipped_test_xml_cannot_accept_otherwise_valid_evidence(self) -> None:
        result = self.run_gate(FAKE_SKIPPED_TEST_PROFILE="small_three_button")

        self.assertEqual(71, result.returncode, result.stdout)
        self.assertIn("android_test_results_status=71", self.read_result("small_three_button"))
        self.assert_initial_state_restored()

    def test_multiple_test_xml_files_cannot_bind_one_profile_run(self) -> None:
        result = self.run_gate(FAKE_EXTRA_XML_PROFILE="landscape_gesture")

        self.assertEqual(71, result.returncode, result.stdout)
        self.assertIn("android_test_results_status=71", self.read_result("landscape_gesture"))
        self.assert_initial_state_restored()

    def test_mismatched_uploaded_bitmap_hash_is_a_host_gate_failure(self) -> None:
        result = self.run_gate(FAKE_BAD_HASH_PROFILE="small_gesture")

        self.assertEqual(75, result.returncode, result.stdout)
        profile_result = self.read_result("small_gesture")
        self.assertIn("evidence_identity_status=75", profile_result)
        self.assertIn("profile_exit_status=75", profile_result)
        self.assertIn(
            "pulled PNG does not match the Bitmap hash recorded by instrumentation",
            (self.evidence / "small_gesture-run.log").read_text(encoding="utf-8"),
        )
        self.assert_failure_bundle("small_gesture")
        self.assert_initial_state_restored()

    def test_cleanup_failure_does_not_mask_original_instrumentation_code(self) -> None:
        result = self.run_gate(
            FAKE_GRADLE_FAIL_PROFILE="small_gesture",
            FAKE_FAIL_RESTORE_ACCELEROMETER="1",
        )

        self.assertEqual(17, result.returncode, result.stdout)
        summary = (self.evidence / "exit-summary.env").read_text(encoding="utf-8")
        self.assertIn("original_exit_status=17", summary)
        self.assertIn("cleanup_exit_status=41", summary)
        self.assertIn("final_exit_status=17", summary)
        self.assertIn("restore saved user rotation while sensor mode is active", (self.evidence / "restore.log").read_text(encoding="utf-8"))

    def test_cleanup_failure_becomes_gate_failure_after_successful_profiles(self) -> None:
        result = self.run_gate(FAKE_FAIL_RESTORE_ACCELEROMETER="1")

        self.assertEqual(41, result.returncode, result.stdout)
        summary = (self.evidence / "exit-summary.env").read_text(encoding="utf-8")
        self.assertIn("original_exit_status=0", summary)
        self.assertIn("cleanup_exit_status=41", summary)
        self.assertIn("final_exit_status=41", summary)

    def test_non_converged_density_blocks_instrumentation_and_records_failed_state(self) -> None:
        result = self.run_gate(FAKE_IGNORE_320_DENSITY="1")

        self.assertEqual(70, result.returncode, result.stdout)
        profile_result = self.read_result("small_gesture")
        self.assertIn("configuration_status=70", profile_result)
        self.assertIn("instrumentation_status=125", profile_result)
        self.assertIn("profile_exit_status=70", profile_result)
        self.assertIn("gate=FAIL", (self.evidence / "small_gesture-preflight-state.txt").read_text(encoding="utf-8"))
        calls = self.calls_path.read_text(encoding="utf-8")
        self.assertNotIn("small_gesture", calls)
        self.assertIn("cutout_three_button", calls)
        self.assert_initial_state_restored()


if __name__ == "__main__":
    unittest.main()
