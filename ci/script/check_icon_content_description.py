#!/usr/bin/env python3
"""
check_icon_content_description.py — Comprobación semántica de iconos interactivos.

Escanee el directorio ui/ e identifíquelo aproximadamente“El icono en el contenedor en el que se puede hacer clic no es contentDescription”situación:
  - en contentDescription = null Aparece dentro de las 8 líneas anteriores IconButton / clickable / combinedClickable
    / toggleable / selectable
  - No hay nada más en esta ventana. contentDescription = Tarea (para evitar falsos positivos que el padre haya proporcionado semántica)

Baseline Mecanismo: abandonar las existencias existentes, agregar nuevas intercepciones, solo disminuir pero no aumentar.

Limitaciones conocidas:
  - No es un análisis de nivel AST; es posible que se omitan contenedores de clics que abarcan varias filas o que se obtengan falsos positivos.
  - Si el padre con semántica está a más de 8 líneas del ícono, aún se informará falsamente y requerirá revisión manual.

Uso:
  py -3 ci/script/check_icon_content_description.py
  py -3 ci/script/check_icon_content_description.py --update-baseline
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

from baseline_utils import require_baseline

PROJECT_ROOT = Path(__file__).resolve().parents[2]
UI_DIR = PROJECT_ROOT / "app" / "src" / "main" / "java" / "io" / "zer0" / "muse" / "ui"
BASELINE_PATH = PROJECT_ROOT / "ci" / "baseline" / "icon_content_description_baseline.txt"

INTERACTIVE = re.compile(
    r"IconButton\(|clickable\(|combinedClickable\(|toggleable\(|selectable\("
)
NULL_CD = re.compile(r"contentDescription\s*=\s*null")
HAS_CD = re.compile(r"contentDescription\s*=\s*(?!null)")


def scan_file(path: Path) -> list[tuple[int, str]]:
    hits: list[tuple[int, str]] = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeDecodeError):
        return hits
    for i, line in enumerate(lines):
        if not NULL_CD.search(line):
            continue
        start = max(0, i - 8)
        window = "\n".join(lines[start:i + 1])
        if INTERACTIVE.search(window) and not HAS_CD.search(window):
            hits.append((i + 1, line.strip()))
    return hits


def scan_all() -> dict[str, list[tuple[int, str]]]:
    result: dict[str, list[tuple[int, str]]] = {}
    for kt in sorted(UI_DIR.rglob("*.kt")):
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
        "# icon_content_description baseline — Inventario semántico de iconos interactivos",
        "# Formato: <file>: <Número de violaciones>;Agregar o aumentar fallará,solo se permite descender",
        "# Ejecutar después de limpiar el inventario.: py -3 ci/script/check_icon_content_description.py --update-baseline",
        "#",
        f"# total actual {sum(len(v) for v in violations.values())} en,Distribución {len(violations)} archivos",
    ]
    for file, hits in sorted(violations.items()):
        lines.append(f"{file}: {len(hits)}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Comprobación semántica de iconos interactivos")
    parser.add_argument("--baseline", type=Path, default=BASELINE_PATH)
    parser.add_argument("--update-baseline", action="store_true")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    violations = scan_all()
    total = sum(len(v) for v in violations.values())

    if args.update_baseline:
        write_baseline(args.baseline, violations)
        print(f"baseline actualizado: {args.baseline}")
        print(f"  total {total} en,Distribución {len(violations)} archivos")
        return 0

    print(f"[icon-cd] Escanee el directorio ui/: {total} Falta el icono interactivo contentDescription,Distribución {len(violations)} archivos")

    if args.verbose:
        for file, hits in sorted(violations.items()):
            for lineno, text in hits:
                print(f"  {file}:{lineno} {text}")

    if not require_baseline(args.baseline, "icon-cd"):
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
        print("[icon-cd] hay mejora,Se recomienda ajustar --update-baseline:")
        for line in improved:
            print(line)

    if failures:
        print("[icon-cd] FAILED — Falta el nuevo ícono interactivo contentDescription:")
        for line in failures:
            print(line)
        print("como solucionarlo: Agregar al icono en el que se puede hacer clic contentDescription;Los iconos decorativos proporcionan semántica a nivel principal")
        return 1

    print(f"[icon-cd] PASS — No hay nuevos íconos interactivos ni falta semántica (Valores {total} en,Exento por línea de base)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
