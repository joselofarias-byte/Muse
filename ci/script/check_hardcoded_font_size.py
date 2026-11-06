#!/usr/bin/env python3
"""
check_hardcoded_font_size.py — UI Comprobación del tamaño de fuente codificada.

Escanee el directorio ui/ (theme/ Excepto), la interceptación pasa por alto MuseTypography fontSize = N.sp.
Todo lo que tenga un comentario // en la misma línea se considera intencionalmente exento (como el tamaño de fuente decorativa de efectos especiales emocionales).

Uso:
  py -3 ci/script/check_hardcoded_font_size.py
  py -3 ci/script/check_hardcoded_font_size.py --update-baseline
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

from baseline_utils import require_baseline

PROJECT_ROOT = Path(__file__).resolve().parents[2]
UI_DIR = PROJECT_ROOT / "app" / "src" / "main" / "java" / "io" / "zer0" / "muse" / "ui"
BASELINE_PATH = PROJECT_ROOT / "ci" / "baseline" / "hardcoded_font_size_baseline.txt"

FONT_SIZE = re.compile(r"fontSize\s*=\s*\d+\.sp")


def scan_file(path: Path) -> list[tuple[int, str]]:
    hits: list[tuple[int, str]] = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeDecodeError):
        return hits
    for i, line in enumerate(lines):
        if not FONT_SIZE.search(line):
            continue
        if "//" in line:
            continue
        hits.append((i + 1, line.strip()))
    return hits


def scan_all() -> dict[str, list[tuple[int, str]]]:
    result: dict[str, list[tuple[int, str]]] = {}
    for kt in sorted(UI_DIR.rglob("*.kt")):
        try:
            rel = kt.relative_to(UI_DIR)
        except ValueError:
            continue
        if rel.parts and rel.parts[0] == "theme":
            continue
        hits = scan_file(kt)
        if hits:
            result[str(kt.relative_to(PROJECT_ROOT)).replace("\\", "/")] = hits
    return result


def load_baseline(path: Path) -> dict[str, int]:
    baseline: dict[str, int] = {}
    if not path.exists():
        return baseline
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        file_part, sep, count_part = line.rpartition(":")
        if not sep:
            continue
        try:
            baseline[file_part.strip()] = int(count_part.strip())
        except ValueError:
            continue
    return baseline


def write_baseline(path: Path, violations: dict[str, list[tuple[int, str]]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "# hardcoded_font_size baseline — UI Inventario de tamaño de fuente codificado",
        "# Formato: <file>: <Número de violaciones>;Agregar o aumentar fallará,solo se permite descender",
        "# Ejecutar después de limpiar el inventario.: py -3 ci/script/check_hardcoded_font_size.py --update-baseline",
        "#",
        f"# total actual {sum(len(v) for v in violations.values())} en,Distribución {len(violations)} archivos",
    ]
    for file, hits in sorted(violations.items()):
        lines.append(f"{file}: {len(hits)}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Verificación del tamaño de fuente codificada")
    parser.add_argument("--baseline", type=Path, default=BASELINE_PATH)
    parser.add_argument("--update-baseline", action="store_true")
    args = parser.parse_args()

    violations = scan_all()
    total = sum(len(v) for v in violations.values())

    if args.update_baseline:
        write_baseline(args.baseline, violations)
        print(f"baseline actualizado: {args.baseline}")
        print(f"  total {total} en,Distribución {len(violations)} archivos")
        return 0

    print(f"[font-size] Escanee el directorio ui/: {total} codificado fontSize,Distribución {len(violations)} archivos")

    if not require_baseline(args.baseline, "font-size"):
        return 2

    baseline = load_baseline(args.baseline)
    failures: list[str] = []
    improved: list[str] = []
    for file, hits in sorted(violations.items()):
        current = len(hits)
        allowed = baseline.get(file, 0)
        if current > allowed:
            failures.append(f"  {file}: {current} en (baseline {allowed},Nuevo {current - allowed})")
        elif current < allowed:
            improved.append(f"  {file}: {allowed} → {current}")
    cleaned = [f for f in baseline if f not in violations]
    for f in cleaned:
        improved.append(f"  {f}: Borrado")

    if improved:
        print("[font-size] hay mejora,Se recomienda ajustar --update-baseline:")
        for line in improved:
            print(line)

    if failures:
        print("[font-size] FAILED — Se agregó codificación rígida fontSize:")
        for line in failures:
            print(line)
        print("como solucionarlo: Utilice MaterialTheme.typography o MuseTypography;Exención intencional más // comentario")
        return 1

    print(f"[font-size] PASS — Sin nueva codificación fontSize (Valores {total} en,Exento por línea de base)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
