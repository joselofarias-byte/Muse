#!/usr/bin/env python3
"""
test_repo_hygiene.py — check_repo_hygiene.py pruebas unitarias.

Centrarse en devolver las reglas de marcado de conflictos más estrictas v2.6.1:
  - Banderas de conflicto reales de Git(Exactamente 7 caracteres + final de línea/espacio)debe golpear;
  - markdown de `====` línea divisoria,`----` Los guiones bajos, etc. no deben juzgarse mal(antigua forma de escribir `^={7}` Será rojo falso).

correr: py -3 ci/test/test_repo_hygiene.py
"""

import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parents[1] / "script"
sys.path.insert(0, str(SCRIPT_DIR))

from check_repo_hygiene import CONFLICT_PATTERN


def _has_conflict(text: str) -> bool:
    """Si el texto dado contiene marcadores de conflicto."""
    return bool(CONFLICT_PATTERN.search(text))


def test_real_conflict_markers_detected():
    """Los tres verdaderos marcadores de conflicto deberían aparecer."""
    assert _has_conflict("<<<<<<< HEAD")
    assert _has_conflict("=======")
    assert _has_conflict(">>>>>>> feature/branch")
    # Inicio de línea de marca de conflicto + contenido posterior
    assert _has_conflict("<<<<<<< HEAD\nsome code\n=======\nother\n>>>>>>> other")


def test_conflict_marker_with_trailing_space_detected():
    """La marca puede ir seguida de un espacio.(Algunas herramientas rellenarán los espacios finales)."""
    assert _has_conflict("<<<<<<< HEAD   ")


def test_markdown_separators_not_detected():
    """markdown Los divisores no deben confundirse con marcadores de conflicto(v2.6.1 Rojo falso fijo)."""
    # release_verification.txt Título subrayado y similares.
    assert not _has_conflict("Muse v2.6.1 Verificación de compilación\n============================\n")
    assert not _has_conflict("Título\n====\n")
    assert not _has_conflict("Título\n==========\n")


def test_dashes_and_other_runs_not_detected():
    """Otros símbolos consecutivos(no < / = / > La séptima compañía)No debería golpear."""
    assert not _has_conflict("--------")
    assert not _has_conflict("########")
    assert not _has_conflict("*" * 20)


def test_shorter_or_longer_runs_not_detected():
    """Menos de 7 no cuenta;Más de 7 seguidos de espacios que no estén en blanco no cuentan.(Debe ir seguido de un espacio./final de línea)."""
    assert not _has_conflict("<<<<<<")
    # 8 un < seguido de texto —— No es un formulario de marca estándar
    assert not _has_conflict("<<<<<<<<x")


def test_embedded_marker_not_detected():
    """La secuencia de símbolos que aparecen en la línea no debe coincidir(debe comenzar con)."""
    assert not _has_conflict("code = a <<<<<<< b")


def test_each_pattern_kind_detected():
    """Cada uno de los tres prefijos aparece de forma independiente."""
    for marker in ("<<<<<<<", "=======", ">>>>>>>"):
        assert _has_conflict(marker), f"Golpea el objetivo: {marker}"


def run_all_tests():
    """Ejecute todas las pruebas e informe los resultados."""
    tests = [
        test_real_conflict_markers_detected,
        test_conflict_marker_with_trailing_space_detected,
        test_markdown_separators_not_detected,
        test_dashes_and_other_runs_not_detected,
        test_shorter_or_longer_runs_not_detected,
        test_embedded_marker_not_detected,
        test_each_pattern_kind_detected,
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
