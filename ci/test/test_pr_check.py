#!/usr/bin/env python3
"""
test_pr_check.py — pr_check.py pruebas unitarias.

Pruebe la exactitud de la clasificación del alcance y la lógica de enrutamiento de carriles.
correr: py -3 ci/test/test_pr_check.py
"""

import sys
import os
from pathlib import Path

# Agregar ci/script path
SCRIPT_DIR = Path(__file__).resolve().parents[1] / "script"
sys.path.insert(0, str(SCRIPT_DIR))

from pr_check import classify_file, compute_lanes


def test_classify_kotlin_file():
    """Kotlin El código fuente debe clasificarse como android_jvm."""
    assert classify_file("app/src/main/java/io/zer0/muse/MainActivity.kt") == "android_jvm"
    assert classify_file("memory/src/main/java/io/zer0/memory/MemoryService.kt") == "android_jvm"


def test_classify_java_file():
    """Java El código fuente debe clasificarse como android_jvm."""
    assert classify_file("app/src/main/java/io/zer0/muse/Legacy.java") == "android_jvm"


def test_classify_localization_file():
    """values-* Los archivos de recursos deben clasificarse como localización."""
    assert classify_file("app/src/main/res/values-zh/strings.xml") == "localization"
    assert classify_file("app/src/main/res/values-ja/strings.xml") == "localization"
    assert classify_file("app/src/main/res/values/strings.xml") == "localization"


def test_classify_resource_file():
    """res/ lo siguiente esta mal values-* Los recursos deben clasificarse como android_resources."""
    assert classify_file("app/src/main/res/layout/activity_main.xml") == "android_resources"
    assert classify_file("app/src/main/res/drawable/ic_launcher.png") == "android_resources"
    assert classify_file("app/src/main/res/xml/accessibility_service_config.xml") == "android_resources"


def test_classify_build_config():
    """build.gradle.kts Debe clasificarse como android_full."""
    assert classify_file("app/build.gradle.kts") == "android_full"
    assert classify_file("build.gradle.kts") == "android_full"
    assert classify_file("settings.gradle.kts") == "android_full"
    assert classify_file("gradle.properties") == "android_full"
    assert classify_file("gradle/libs.versions.toml") == "android_full"


def test_classify_manifest():
    """AndroidManifest.xml Debe clasificarse como android_full."""
    assert classify_file("app/src/main/AndroidManifest.xml") == "android_full"


def test_classify_script():
    """ci/ Los scripts en el directorio deben clasificarse como script."""
    assert classify_file("ci/script/pr_check.py") == "script"
    assert classify_file("ci/script/check_repo_hygiene.py") == "script"


def test_classify_docs():
    """Markdown Los documentos deben clasificarse como documentos."""
    assert classify_file("README.md") == "docs"
    assert classify_file("docs/ACCESSIBILITY.md") == "docs"
    assert classify_file("AGENTS.md") == "docs"


def test_classify_config():
    """Los archivos de configuración deben clasificarse como config."""
    assert classify_file(".editorconfig") == "config"
    assert classify_file("app/src/main/res/values/themes.xml") == "localization"  # values/ El xml devuelto bajo localization


def test_classify_unknown():
    """Los archivos no coincidentes se clasifican como otros."""
    assert classify_file("random_file.txt") == "other"
    assert classify_file("data.json") == "other"


def test_compute_lanes_empty():
    """Sólo se ejecuta quick_checks sin alcance."""
    lanes = compute_lanes(set())
    assert lanes["quick_checks"] is True
    assert lanes["localization"] is False
    assert lanes["android_jvm"] is False
    assert lanes["android_full"] is False


def test_compute_lanes_docs_only():
    """Ejecute Quick_checks solo cuando el documento cambie."""
    lanes = compute_lanes({"docs"})
    assert lanes["quick_checks"] is True
    assert lanes["localization"] is False
    assert lanes["android_jvm"] is False
    assert lanes["android_full"] is False


def test_compute_lanes_android_full():
    """android_full Se deben activar todos los carriles relacionados con Android."""
    lanes = compute_lanes({"android_full"})
    assert lanes["android_full"] is True
    assert lanes["android_jvm"] is True
    assert lanes["android_resources"] is True
    assert lanes["localization"] is False


def test_compute_lanes_mixed():
    """Los ámbitos mixtos deberían activar correctamente el carril correspondiente."""
    lanes = compute_lanes({"android_jvm", "localization"})
    assert lanes["quick_checks"] is True
    assert lanes["android_jvm"] is True
    assert lanes["localization"] is True
    assert lanes["android_full"] is False
    assert lanes["android_resources"] is False


def test_compute_lanes_script():
    """script El alcance solo activa el carril del guión."""
    lanes = compute_lanes({"script"})
    assert lanes["quick_checks"] is True
    assert lanes["script"] is True
    assert lanes["android_jvm"] is False


def run_all_tests():
    """Ejecute todas las pruebas e informe los resultados."""
    tests = [
        test_classify_kotlin_file,
        test_classify_java_file,
        test_classify_localization_file,
        test_classify_resource_file,
        test_classify_build_config,
        test_classify_manifest,
        test_classify_script,
        test_classify_docs,
        test_classify_config,
        test_classify_unknown,
        test_compute_lanes_empty,
        test_compute_lanes_docs_only,
        test_compute_lanes_android_full,
        test_compute_lanes_mixed,
        test_compute_lanes_script,
    ]

    passed = 0
    failed = 0
    for test in tests:
        try:
            test()
            print(f"  [PASS] {test.__name__}")
            passed += 1
        except AssertionError as e:
            print(f"  [FAIL] {test.__name__}: {e}")
            failed += 1
        except Exception as e:
            print(f"  [ERROR] {test.__name__}: {e}")
            failed += 1

    print(f"\n{'='*50}")
    print(f"Total: {passed + failed}, Passed: {passed}, Failed: {failed}")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(run_all_tests())
