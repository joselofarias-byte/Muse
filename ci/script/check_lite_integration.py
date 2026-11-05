#!/usr/bin/env python3
"""Fail closed when an integration loses the Lite delivery boundaries."""
from pathlib import Path


def check(root: Path) -> None:
    update = root / "app/src/main/java/io/zer0/muse/update"
    checker = (update / "UpdateChecker.kt").read_text()
    notifier = (update / "UpdateNotifier.kt").read_text()
    workflow = (root / ".github/workflows/ci.yml").read_text()
    assert 'const val DEFAULT_REPO = "joselofarias-byte/Muse"' in checker
    assert "https://github.com/joselofarias-byte/Muse/releases/latest" in notifier
    for source in (checker, notifier):
        assert "museai.ltd" not in source, "Updater mirror must not return"
        assert "Zer0Qing/Muse" not in source, "Upstream updater must not return"
    assert "refs/heads/integration/muse-lite-upstream" in workflow
    assert "name: muse-lite-arm64" in workflow
    assert 'test "${#apks[@]}" -eq 1' in workflow
    assert "build-metadata.json" in workflow
    assert "SHA256SUMS" in workflow
    gradle = (root / "app/build.gradle.kts").read_text()
    assert 'if (museLite) {\n                include("arm64-v8a")' in gradle
    assert 'isUniversalApk = !museLite &&' in gradle
    assert "PRAGMA compile_options" in (root / "app/src/main/java/io/zer0/muse/data/session/MuseDb.kt").read_text()


if __name__ == "__main__":
    check(Path(__file__).resolve().parents[2])
    print("Lite integration boundaries verified")
