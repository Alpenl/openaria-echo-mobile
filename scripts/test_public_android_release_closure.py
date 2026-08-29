"""Execute the paginated public Android Release closure jq contract."""

from __future__ import annotations

import json
import subprocess
import unittest
from pathlib import Path


FILTER = Path(__file__).with_name("public-android-release-closure.jq")


def release(
    release_id: int,
    tag: str,
    *,
    draft: bool = False,
    prerelease: bool = False,
    manifests: int = 1,
) -> dict[str, object]:
    assets: list[dict[str, object]] = [
        {"id": release_id * 10, "name": "other.txt", "size": 1, "digest": "sha256:" + "1" * 64}
    ]
    assets.extend(
        {
            "id": release_id * 10 + index + 1,
            "name": "android-update.json",
            "size": 100 + index,
            "digest": "sha256:" + str((release_id + index) % 10) * 64,
        }
        for index in range(manifests)
    )
    return {
        "id": release_id,
        "tag_name": tag,
        "draft": draft,
        "prerelease": prerelease,
        "assets": assets,
    }


def closure(*pages: list[dict[str, object]]) -> list[dict[str, object]]:
    json_stream = "".join(json.dumps(page) + "\n" for page in pages)
    result = subprocess.run(
        ["jq", "-sSce", "-f", str(FILTER)],
        input=json_stream,
        check=True,
        capture_output=True,
        text=True,
        timeout=10,
    )
    return json.loads(result.stdout)


class PublicAndroidReleaseClosureTest(unittest.TestCase):
    def test_single_page_keeps_public_prerelease_and_excludes_draft_or_non_android(self) -> None:
        actual = closure(
            [
                release(4, "v0.1.4", draft=True),
                release(3, "v0.1.3", prerelease=True),
                release(2, "desktop-only", manifests=0),
            ]
        )

        self.assertEqual([3], [item["release_id"] for item in actual])
        self.assertEqual("v0.1.3", actual[0]["tag"])
        self.assertEqual(
            [{"id": 31, "size": 100, "digest": "sha256:" + "3" * 64}],
            actual[0]["manifest_assets"],
        )

    def test_multiple_page_json_documents_are_slurped_and_sorted(self) -> None:
        actual = closure(
            [release(20, "v0.2.0")],
            [release(6, "v0.1.6"), release(99, "draft", draft=True)],
        )

        self.assertEqual([6, 20], [item["release_id"] for item in actual])
        self.assertEqual([61, 201], [item["manifest_assets"][0]["id"] for item in actual])

    def test_duplicate_manifest_assets_remain_visible_for_fail_closed_caller_check(self) -> None:
        actual = closure([release(7, "v0.1.7", manifests=2)])

        self.assertEqual(2, len(actual[0]["manifest_assets"]))

    def test_malformed_page_document_fails_closed(self) -> None:
        with self.assertRaises(subprocess.CalledProcessError):
            closure({"not": "a page"})  # type: ignore[arg-type]

    def test_duplicate_public_android_tag_fails_closed(self) -> None:
        with self.assertRaises(subprocess.CalledProcessError):
            closure([release(8, "v0.1.8"), release(9, "v0.1.8")])

    def test_duplicate_public_android_release_id_fails_closed(self) -> None:
        with self.assertRaises(subprocess.CalledProcessError):
            closure([release(8, "v0.1.8"), release(8, "v0.1.9")])


if __name__ == "__main__":
    unittest.main()
