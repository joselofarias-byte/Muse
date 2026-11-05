#!/usr/bin/env python3
"""Regression checks for the integration delivery contract."""
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "ci/script"))
from check_lite_integration import check


class LiteIntegrationTest(unittest.TestCase):
    def test_current_boundaries(self):
        check(ROOT)

    def test_reject_upstream_update_fallback(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for path in (
                "app/src/main/java/io/zer0/muse/update",
                "app/src/main/java/io/zer0/muse/data/session",
                ".github/workflows",
            ):
                shutil.copytree(ROOT / path, root / path)
            shutil.copy2(ROOT / "app/build.gradle.kts", root / "app/build.gradle.kts")
            checker = root / "app/src/main/java/io/zer0/muse/update/UpdateChecker.kt"
            checker.write_text(checker.read_text() + '\n// https://museai.ltd/api/latest.json\n')
            with self.assertRaises(AssertionError):
                check(root)


if __name__ == "__main__":
    unittest.main()
