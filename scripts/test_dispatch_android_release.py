"""Executable contract tests for the local Android release dispatcher."""

from __future__ import annotations

import base64
import hashlib
import json
import os
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("dispatch-android-release.sh")
SOURCE_COMMIT = "1" * 40
RAW_RESPONSE = b'{"enabled":true,"enforced_by_owner":false}\n'


class DispatchAndroidReleaseTest(unittest.TestCase):
    def run_dispatcher(
        self,
        *extra: str,
        release_tag: str = "v0.1.7",
        default_branch_head: str = SOURCE_COMMIT,
    ) -> tuple[subprocess.CompletedProcess[str], list[str]]:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            capture = root / "capture.txt"
            mock_gh = root / "gh"
            mock_gh.write_text(
                textwrap.dedent(
                    f"""\
                    #!/usr/bin/env bash
                    set -euo pipefail
                    if [[ "$1" == "api" && "$2" == "user" ]]; then
                      printf '%s\n' 'Alpenl'
                    elif [[ "$1" == "api" && "$2" == "repos/Alpenl/openaria-echo-mobile/commits/{SOURCE_COMMIT}" ]]; then
                      printf '%s\n' '{SOURCE_COMMIT}'
                    elif [[ "$1" == "api" && "$2" == "repos/Alpenl/openaria-echo-mobile" ]]; then
                      printf '%s\n' 'main'
                    elif [[ "$1" == "api" && "$2" == "repos/Alpenl/openaria-echo-mobile/commits/main" ]]; then
                      printf '%s\n' '{default_branch_head}'
                    elif [[ "$1" == "api" && "${{@: -1}}" == "repos/Alpenl/openaria-echo-mobile/immutable-releases" ]]; then
                      printf '%s\n' '{{"enabled":true,"enforced_by_owner":false}}'
                    elif [[ "$1" == "workflow" && "$2" == "run" ]]; then
                      printf '%s\n' "$@" > "${{GH_CAPTURE}}"
                    else
                      printf 'unexpected gh call: %s\n' "$*" >&2
                      exit 99
                    fi
                    """
                ),
                encoding="utf-8",
            )
            mock_gh.chmod(0o755)
            environment = os.environ.copy()
            environment["PATH"] = f"{root}:{environment['PATH']}"
            environment["GH_CAPTURE"] = str(capture)
            result = subprocess.run(
                [str(SCRIPT), SOURCE_COMMIT, release_tag, *extra],
                check=False,
                capture_output=True,
                text=True,
                env=environment,
                timeout=15,
            )
            captured = capture.read_text(encoding="utf-8").splitlines() if capture.exists() else []
            return result, captured

    def evidence_from(self, captured: list[str]) -> dict[str, object]:
        field = next(value for value in captured if value.startswith("immutable_releases_preflight="))
        return json.loads(field.removeprefix("immutable_releases_preflight="))

    def test_default_dispatch_binds_exact_raw_admin_response_with_bootstrap_disabled(self) -> None:
        result, captured = self.run_dispatcher()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(f"ref={SOURCE_COMMIT}", captured)
        self.assertIn("release_tag=v0.1.7", captured)
        self.assertIn("allow_legacy_baseline_bootstrap=false", captured)
        evidence = self.evidence_from(captured)
        self.assertEqual("Alpenl/openaria-echo-mobile", evidence["repository"])
        self.assertEqual("Alpenl", evidence["actor"])
        self.assertEqual(SOURCE_COMMIT, evidence["source_commit"])
        self.assertEqual("main", evidence["default_branch"])
        self.assertEqual(SOURCE_COMMIT, evidence["default_branch_head"])
        self.assertEqual("v0.1.7", evidence["release_tag"])
        self.assertFalse(evidence["allow_legacy_baseline_bootstrap"])
        self.assertEqual(RAW_RESPONSE, base64.b64decode(str(evidence["response_raw_base64"])))
        self.assertEqual(hashlib.sha256(RAW_RESPONSE).hexdigest(), evidence["response_sha256"])
        self.assertEqual({"enabled": True, "enforced_by_owner": False}, evidence["response"])

    def test_bootstrap_flag_is_explicitly_bound_into_dispatch_and_preflight(self) -> None:
        result, captured = self.run_dispatcher("--allow-legacy-baseline-bootstrap")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("allow_legacy_baseline_bootstrap=true", captured)
        self.assertTrue(self.evidence_from(captured)["allow_legacy_baseline_bootstrap"])

    def test_unknown_third_argument_fails_before_any_dispatch(self) -> None:
        result, captured = self.run_dispatcher("--unsafe")

        self.assertEqual(2, result.returncode)
        self.assertEqual([], captured)

    def test_bootstrap_flag_rejects_any_other_release_tag_before_admin_read(self) -> None:
        result, captured = self.run_dispatcher(
            "--allow-legacy-baseline-bootstrap",
            release_tag="v0.1.8",
        )

        self.assertEqual(1, result.returncode)
        self.assertIn("only for v0.1.7", result.stderr)
        self.assertEqual([], captured)

    def test_non_default_branch_head_fails_before_admin_read_or_dispatch(self) -> None:
        other_head = "2" * 40
        result, captured = self.run_dispatcher(default_branch_head=other_head)

        self.assertEqual(1, result.returncode)
        self.assertIn(f"current main head {other_head}", result.stderr)
        self.assertEqual([], captured)


if __name__ == "__main__":
    unittest.main()
