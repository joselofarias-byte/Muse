#!/usr/bin/env python3
"""test_hardcoded_cjk.py — check_hardcoded_cjk.py pruebas unitarias.

correr: py -3 ci/test/test_hardcoded_cjk.py
"""

import sys
import tempfile
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parents[1] / "script"
sys.path.insert(0, str(SCRIPT_DIR))

from check_hardcoded_cjk import scan_file, strip_cjk_logger_calls


def test_hardcoded_cjk_in_text_detected():
    with tempfile.TemporaryDirectory() as tmp:
        p = Path(tmp) / "Sample.kt"
        p.write_text(
            'Text("hola mundo")\n'
            'val label = "chino"\n',
            encoding="utf-8",
        )
        vs = scan_file(p)
        assert len(vs) == 2, vs


def test_comment_with_cjk_ignored():
    with tempfile.TemporaryDirectory() as tmp:
        p = Path(tmp) / "Sample.kt"
        p.write_text(
            "// Chino en comentarios\n"
            "/* Bloquear comentarios: chino */\n"
            "val x = 1 // Chino de fin de línea\n",
            encoding="utf-8",
        )
        vs = scan_file(p)
        assert vs == [], vs


def test_multiline_kdoc_before_logger_call_is_ignored():
    with tempfile.TemporaryDirectory() as tmp:
        p = Path(tmp) / "Sample.kt"
        p.write_text(
            "/**\n"
            " * KDoc Contiene instrucciones en chino.\n"
            " */\n"
            "Logger.i(\n"
            '    "texto de diagnóstico",\n'
            "    error,\n"
            ")\n",
            encoding="utf-8",
        )
        assert scan_file(p) == []


def test_logger_call_stripping_preserves_following_ui_text():
    code = 'Logger.i(TAG, "Diagnóstico") Text("Hola")'
    stripped, in_logger_call = strip_cjk_logger_calls(code)
    assert 'Text("Hola")' in stripped
    assert "Diagnóstico" not in stripped
    assert not in_logger_call


def test_string_resource_not_detected():
    with tempfile.TemporaryDirectory() as tmp:
        p = Path(tmp) / "Sample.kt"
        p.write_text(
            'Text(stringResource(R.string.hello))\n',
            encoding="utf-8",
        )
        vs = scan_file(p)
        assert vs == [], vs


def test_mixed_line_only_reports_string_cjk():
    with tempfile.TemporaryDirectory() as tmp:
        p = Path(tmp) / "Sample.kt"
        p.write_text(
            'Text("Hola") // También hay chino en los comentarios.\n',
            encoding="utf-8",
        )
        vs = scan_file(p)
        assert len(vs) == 1, vs


def test_logger_cjk_ignored():
    with tempfile.TemporaryDirectory() as tmp:
        p = Path(tmp) / "Sample.kt"
        p.write_text(
            'Logger.w(TAG, "Error al cargar: $msg")\n'
            'Text("Por favor inténtalo de nuevo")\n',
            encoding="utf-8",
        )
        vs = scan_file(p)
        assert len(vs) == 1, vs

if __name__ == "__main__":
    test_hardcoded_cjk_in_text_detected()
    test_comment_with_cjk_ignored()
    test_multiline_kdoc_before_logger_call_is_ignored()
    test_logger_call_stripping_preserves_following_ui_text()
    test_string_resource_not_detected()
    test_mixed_line_only_reports_string_cjk()
    test_logger_cjk_ignored()
    print("test_hardcoded_cjk OK")
