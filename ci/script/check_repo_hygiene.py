#!/usr/bin/env python3
"""
check_repo_hygiene.py — Inspección de higiene del almacén.

Comprobar rápidamente el estado de higiene básica del almacén:
  - bandera de conflicto (<<<<<<< / >>>>>>>)
  - Fin de línea en blanco
  - Nueva línea al final del archivo
  - JSON/XML/YAML Gramática
  - Detección de archivos grandes

Uso: py -3 ci/script/check_repo_hygiene.py [--path .]
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

# Windows Predeterminado de la consola china GBK,Y este script se imprimirá ⚠/✓/✗(No en el juego de caracteres GBK),
# La salida directa arrojará UnicodeEncodeError y hará que los carriles fallen(2026-09-29 Medición real).
# Unificar stdout/stderr UTF-8;Si el entorno no admite la reconfiguración, déjelo como está.
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # noqa: BLE001 - El cheque en sí no debería verse afectado si el entorno no lo soporta.
    pass

PROJECT_ROOT = Path(__file__).resolve().parents[2]

# bandera de conflicto
# v2.6.1: Ajustado al formato verdadero de Git —— La marca es siempre**exactamente 7**personaje,seguido del final de la línea o un espacio
# (`<<<<<<< HEAD` / `=======` / `>>>>>>> branch`). antigua forma de escribir `^={7}` voluntad
# markdown de `====` divisor(Por ejemplo, el título de release_verification.txt está subrayado.)Mal juzgado como marca de conflicto,
# Causas carriles carril falso rojo.
CONFLICT_PATTERN = re.compile(r"^(<{7}|={7}|>{7})(?=\s|$)", re.MULTILINE)
# Fin de línea en blanco
TRAILING_WHITESPACE = re.compile(r"[ \t]+$", re.MULTILINE)

# Tipos de archivos para comprobar
CHECK_EXTENSIONS = {
    ".kt", ".java", ".xml", ".json", ".yaml", ".yml", ".py", ".md", ".txt", ".gradle", ".kts",
}

# Directorios ignorados
IGNORE_DIRS = {"build", ".gradle", ".idea", ".git", "node_modules", "__pycache__"}

# Tipos de archivos cuyo almacenamiento está prohibido(Higiene del almacén R-DOC-01)
FORBIDDEN_EXTENSIONS = {".db", ".db-wal", ".db-shm"}

# Umbral de archivos grandes (500KB)
MAX_FILE_SIZE = 500 * 1024


def check_conflict_markers(file_path: Path, content: str) -> list[str]:
    """Verifique las banderas de conflicto de Git"""
    issues = []
    for match in CONFLICT_PATTERN.finditer(content):
        line_num = content[:match.start()].count("\n") + 1
        issues.append(f"bandera de conflicto: {file_path.name}:{line_num}")
    return issues


def check_trailing_whitespace(file_path: Path, content: str) -> list[str]:
    """Compruebe si hay espacios en blanco al final"""
    issues = []
    for match in TRAILING_WHITESPACE.finditer(content):
        line_num = content[:match.start()].count("\n") + 1
        issues.append(f"Fin de línea en blanco: {file_path.name}:{line_num}")
    return issues


def check_final_newline(file_path: Path, content: str) -> list[str]:
    """Compruebe si hay una nueva línea al final del archivo"""
    if content and not content.endswith("\n"):
        return [f"Falta una nueva línea final: {file_path.name}"]
    return []


def check_json_syntax(file_path: Path, content: str) -> list[str]:
    """Verifique la sintaxis JSON"""
    try:
        json.loads(content)
    except json.JSONDecodeError as e:
        return [f"JSON error de sintaxis: {file_path.name}:{e.lineno}: {e.msg}"]
    return []


def check_xml_syntax(file_path: Path, content: str) -> list[str]:
    """Comprobar la sintaxis XML"""
    try:
        ET.fromstring(content)
    except ET.ParseError as e:
        return [f"XML error de sintaxis: {file_path.name}: {str(e)}"]
    return []


def check_file_size(file_path: Path) -> list[str]:
    """Comprobar archivos grandes"""
    size = file_path.stat().st_size
    if size > MAX_FILE_SIZE:
        size_kb = size / 1024
        return [f"Advertencia de archivo grande ({size_kb:.0f}KB): {file_path.name}"]
    return []


def main():
    parser = argparse.ArgumentParser(description="Muse Inspección sanitaria del almacén")
    parser.add_argument("--path", default=".", help="Comprobar ruta(Predeterminado: Directorio raíz del proyecto)")
    args = parser.parse_args()

    scan_path = PROJECT_ROOT / args.path if not Path(args.path).is_absolute() else Path(args.path)

    print("=" * 60)
    print("Repository Hygiene Check")
    print("=" * 60)

    all_issues = []
    file_count = 0

    for file_path in scan_path.rglob("*"):
        if not file_path.is_file():
            continue
        if any(part in IGNORE_DIRS for part in file_path.parts):
            continue
        if file_path.suffix not in CHECK_EXTENSIONS:
            continue
        if file_path.suffix in FORBIDDEN_EXTENSIONS:
            all_issues.append(f"Extensiones de prohibición: {file_path.name}")
            continue

        file_count += 1
        content = file_path.read_text(encoding="utf-8", errors="ignore")

        # Inspección general
        all_issues.extend(check_conflict_markers(file_path, content))
        all_issues.extend(check_trailing_whitespace(file_path, content))
        all_issues.extend(check_final_newline(file_path, content))
        all_issues.extend(check_file_size(file_path))

        # Tipo de cheques específicos
        if file_path.suffix == ".json":
            all_issues.extend(check_json_syntax(file_path, content))
        elif file_path.suffix == ".xml":
            all_issues.extend(check_xml_syntax(file_path, content))

    # Resultados de salida
    errors = [i for i in all_issues if "error" in i or "conflicto" in i]
    warnings = [i for i in all_issues if "advertencia" in i or "en blanco" in i or "nueva línea" in i]

    if all_issues:
        print(f"\nScanned {file_count} files, found {len(errors)} errors, {len(warnings)} warnings:\n")
        for issue in all_issues:
            icon = "✗" if "error" in issue or "conflicto" in issue else "⚠"
            print(f"  {icon} {issue}")
    else:
        print(f"\n✓ Scanned {file_count} files, no issues found")

    print(f"\n{'=' * 60}")
    print(f"Summary: {len(errors)} errors, {len(warnings)} warnings")
    print(f"{'=' * 60}")

    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
