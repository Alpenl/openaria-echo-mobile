"""Regression tests for the Android in-app upgrade acceptance driver."""

from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SCRIPT_PATH = Path(__file__).with_name("android-in-app-update-acceptance.py")
SPEC = importlib.util.spec_from_file_location("android_in_app_update_acceptance", SCRIPT_PATH)
assert SPEC is not None and SPEC.loader is not None
acceptance = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(acceptance)


VALID_HIERARCHY = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<hierarchy rotation="0">'
    '<node text="Settings" package="com.android.settings" bounds="[0,0][100,100]" />'
    "</hierarchy>"
)


class DumpUiTest(unittest.TestCase):
    def command_for(self, xml_samples: list[str]):
        samples = iter(xml_samples)

        def fake_command(args: list[str], **_kwargs: object) -> str:
            if args[:4] == ["adb", "shell", "uiautomator", "dump"]:
                return "UI hierarchy dumped"
            if args == ["adb", "exec-out", "cat", acceptance.DEVICE_UI_XML]:
                return next(samples)
            self.fail(f"Unexpected command: {args!r}")

        return mock.Mock(side_effect=fake_command)

    def test_retries_transient_malformed_hierarchy_and_preserves_first_sample(self) -> None:
        malformed_samples = ["", "<hierarchy>", "<hierarchy><node></hierarchy>"]

        for malformed in malformed_samples:
            with self.subTest(malformed=malformed), tempfile.TemporaryDirectory() as temporary:
                evidence_dir = Path(temporary)
                command = self.command_for([malformed, VALID_HIERARCHY])

                with mock.patch.object(acceptance, "command", command), mock.patch.object(
                    acceptance.time,
                    "sleep",
                ):
                    nodes = acceptance.dump_ui(evidence_dir)

                self.assertEqual("Settings", nodes[0]["text"])
                self.assertEqual(4, command.call_count)
                self.assertEqual(
                    malformed,
                    (evidence_dir / "malformed-ui-attempt-1.xml").read_text(encoding="utf-8"),
                )
                self.assertEqual(
                    VALID_HIERARCHY,
                    (evidence_dir / "last-ui.xml").read_text(encoding="utf-8"),
                )

    def test_fails_after_bounded_retries_with_parse_location_and_all_samples(self) -> None:
        malformed_samples = ["<hierarchy>", "<hierarchy><node>", "<hierarchy><node></hierarchy>"]
        command = self.command_for(malformed_samples)

        with tempfile.TemporaryDirectory() as temporary:
            evidence_dir = Path(temporary)
            with mock.patch.object(acceptance, "command", command), mock.patch.object(
                acceptance.time,
                "sleep",
            ):
                with self.assertRaisesRegex(
                    acceptance.AcceptanceError,
                    r"after 3 attempts.*line 1, column",
                ):
                    acceptance.dump_ui(evidence_dir)

            self.assertEqual(6, command.call_count)
            for attempt, malformed in enumerate(malformed_samples, start=1):
                self.assertEqual(
                    malformed,
                    (evidence_dir / f"malformed-ui-attempt-{attempt}.xml").read_text(encoding="utf-8"),
                )
            self.assertEqual(
                malformed_samples[-1],
                (evidence_dir / "last-ui.xml").read_text(encoding="utf-8"),
            )

            diagnostic_command = self.command_for([VALID_HIERARCHY])
            with mock.patch.object(acceptance, "command", diagnostic_command):
                acceptance.dump_ui(evidence_dir)

            self.assertEqual(
                VALID_HIERARCHY,
                (evidence_dir / "last-ui.xml").read_text(encoding="utf-8"),
            )
            for attempt, malformed in enumerate(malformed_samples, start=1):
                self.assertEqual(
                    malformed,
                    (evidence_dir / f"malformed-ui-attempt-{attempt}.xml").read_text(encoding="utf-8"),
                )


if __name__ == "__main__":
    unittest.main()
