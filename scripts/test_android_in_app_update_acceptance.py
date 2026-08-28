"""Regression tests for the Android in-app upgrade acceptance driver."""

from __future__ import annotations

import importlib.util
import tempfile
import unittest
import xml.etree.ElementTree as ET
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

ANDROID_35_UNKNOWN_SOURCES_XML = """\
<hierarchy rotation="0">
  <node index="0" text="Open Aria Echo" resource-id="" class="android.widget.TextView" package="com.android.settings" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[358,740][722,802]" />
  <node index="1" text="" resource-id="" class="android.view.View" package="com.android.settings" content-desc="" checkable="true" checked="false" clickable="true" enabled="true" focusable="true" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[0,903][1080,1059]" />
  <node index="0" text="Allow from this source" resource-id="" class="android.widget.TextView" package="com.android.settings" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[63,950][610,1012]" />
  <node index="2" text="Install unknown apps" resource-id="" class="android.widget.TextView" package="com.android.settings" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[64,361][952,477]" />
</hierarchy>
"""


def android_35_unknown_sources_nodes(*, checked: bool = False) -> list[dict[str, str]]:
    nodes = [dict(node.attrib) for node in ET.fromstring(ANDROID_35_UNKNOWN_SOURCES_XML).iter("node")]
    next(node for node in nodes if node["checkable"] == "true")["checked"] = str(checked).lower()
    return nodes


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


class UnknownSourcesPageTest(unittest.TestCase):
    def run_enable(
        self,
        *snapshots: list[dict[str, str]],
        monotonic_values: list[int] | None = None,
    ):
        evidence_dir = Path("/tmp/android-upgrade-evidence-test")
        self.tap = mock.Mock()
        self.command = mock.Mock()
        clock = monotonic_values if monotonic_values is not None else list(range(20))
        with mock.patch.object(acceptance, "dump_ui", side_effect=snapshots), mock.patch.object(
            acceptance,
            "tap_node",
            self.tap,
        ), mock.patch.object(acceptance, "capture_screen"), mock.patch.object(
            acceptance,
            "command",
            self.command,
        ), mock.patch.object(acceptance.time, "sleep"), mock.patch.object(
            acceptance.time,
            "monotonic",
            side_effect=clock,
        ):
            navigation = acceptance.enable_unknown_sources_after_app_handoff(evidence_dir)
        return navigation, self.tap, self.command

    def test_accepts_real_android_35_compose_checkable_on_strict_target_page(self) -> None:
        navigation, tap, command = self.run_enable(
            android_35_unknown_sources_nodes(),
            android_35_unknown_sources_nodes(checked=True),
        )

        self.assertEqual("direct_baseline_app_permission_guidance", navigation)
        tapped = tap.call_args.args[0]
        self.assertEqual("android.view.View", tapped["class"])
        self.assertEqual("true", tapped["checkable"])
        self.assertEqual("true", tapped["clickable"])
        command.assert_called_once_with(["adb", "shell", "input", "keyevent", "4"])

    def test_keeps_classic_switch_path_on_strict_target_page(self) -> None:
        before = android_35_unknown_sources_nodes()
        after = android_35_unknown_sources_nodes(checked=True)
        for nodes in (before, after):
            switch = next(node for node in nodes if node["checkable"] == "true")
            switch["class"] = "android.widget.Switch"
            switch["resource-id"] = "android:id/switch_widget"

        navigation, tap, _command = self.run_enable(before, after)

        self.assertEqual("direct_baseline_app_permission_guidance", navigation)
        self.assertEqual("android.widget.Switch", tap.call_args.args[0]["class"])

    def test_rejects_target_page_when_any_required_marker_is_missing(self) -> None:
        for missing in ("Install unknown apps", "Allow from this source", "Open Aria Echo"):
            with self.subTest(missing=missing):
                nodes = [node for node in android_35_unknown_sources_nodes() if node["text"] != missing]
                with self.assertRaisesRegex(
                    acceptance.AcceptanceError,
                    "did not identify the Open Aria Echo Unknown Sources page",
                ):
                    acceptance.require_unknown_sources_control(nodes)

    def test_waits_for_incomplete_settings_shell_to_render_target_page(self) -> None:
        settings_shell = [
            {
                "text": "",
                "package": "com.android.settings",
                "class": "android.widget.FrameLayout",
                "checkable": "false",
            }
        ]

        navigation, tap, _command = self.run_enable(
            settings_shell,
            android_35_unknown_sources_nodes(),
            android_35_unknown_sources_nodes(checked=True),
        )

        self.assertEqual("direct_baseline_app_permission_guidance", navigation)
        tap.assert_called_once()

    def test_waits_for_checked_state_after_first_post_tap_snapshot(self) -> None:
        navigation, tap, _command = self.run_enable(
            android_35_unknown_sources_nodes(),
            android_35_unknown_sources_nodes(),
            android_35_unknown_sources_nodes(checked=True),
        )

        self.assertEqual("direct_baseline_app_permission_guidance", navigation)
        tap.assert_called_once()

    def test_rejects_unrelated_settings_toggle(self) -> None:
        unrelated = [
            {
                "text": "Notifications",
                "package": "com.android.settings",
                "class": "android.widget.TextView",
                "checkable": "false",
            },
            {
                "text": "",
                "package": "com.android.settings",
                "class": "android.widget.Switch",
                "resource-id": "android:id/switch_widget",
                "checkable": "true",
                "checked": "false",
                "clickable": "true",
                "bounds": "[0,0][100,100]",
            },
        ]
        with self.assertRaisesRegex(
            acceptance.AcceptanceError,
            r"Timed out.*last reason:.*did not identify the Open Aria Echo Unknown Sources page",
        ):
            self.run_enable(unrelated, unrelated, monotonic_values=[0, 1, 2, 91])
        self.tap.assert_not_called()
        self.command.assert_not_called()

    def test_rejects_ambiguous_checkable_controls(self) -> None:
        before = android_35_unknown_sources_nodes()
        second = dict(next(node for node in before if node["checkable"] == "true"))
        second["class"] = "android.widget.Switch"
        second["resource-id"] = "android:id/switch_widget"
        second["bounds"] = "[900,903][1080,1059]"
        before.append(second)

        with self.assertRaisesRegex(
            acceptance.AcceptanceError,
            r"Timed out.*last reason:.*ambiguous.*2 checkable",
        ):
            self.run_enable(before, before, monotonic_values=[0, 1, 2, 91])
        self.tap.assert_not_called()
        self.command.assert_not_called()

    def test_retries_unsupported_control_then_reports_last_reason(self) -> None:
        unsupported = android_35_unknown_sources_nodes()
        control = next(node for node in unsupported if node["checkable"] == "true")
        control["class"] = "android.widget.CheckBox"

        with self.assertRaisesRegex(
            acceptance.AcceptanceError,
            r"Timed out.*last reason:.*unsupported checkable control",
        ):
            self.run_enable(unsupported, unsupported, monotonic_values=[0, 1, 2, 91])
        self.tap.assert_not_called()
        self.command.assert_not_called()

    def test_revalidates_strict_page_after_click(self) -> None:
        checked_without_markers = [
            node for node in android_35_unknown_sources_nodes(checked=True) if node["checkable"] == "true"
        ]

        with self.assertRaisesRegex(
            acceptance.AcceptanceError,
            r"Timed out.*confirmation.*last reason:.*did not identify.*Unknown Sources page",
        ):
            self.run_enable(
                android_35_unknown_sources_nodes(),
                checked_without_markers,
                monotonic_values=[0, 1, 2, 3, 13],
            )

    def test_post_tap_unchecked_state_times_out_with_last_reason(self) -> None:
        with self.assertRaisesRegex(
            acceptance.AcceptanceError,
            r"Timed out.*confirmation.*last reason:.*did not become checked",
        ):
            self.run_enable(
                android_35_unknown_sources_nodes(),
                android_35_unknown_sources_nodes(),
                monotonic_values=[0, 1, 2, 3, 13],
            )
        self.tap.assert_called_once()
        self.command.assert_not_called()

    def test_control_identity_change_after_tap_fails_immediately(self) -> None:
        changed = android_35_unknown_sources_nodes(checked=True)
        next(node for node in changed if node["checkable"] == "true")["bounds"] = "[1,903][1080,1059]"

        with self.assertRaisesRegex(
            acceptance.AcceptanceError,
            "control identity changed after confirmation",
        ):
            self.run_enable(android_35_unknown_sources_nodes(), changed)
        self.tap.assert_called_once()
        self.command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
