#!/usr/bin/env python3
"""
check_touch_target.py — Toca el objetivo para comprobarlo.

escaneo aproximado IconButton / clickable / combinedClickable se presiona explícitamente por debajo de 48 dp
de Modifier.size(...).IconButton Solo marque el modificador del botón en sí (ingrese { antes),
Evite malinterpretar los tamaños de los íconos internos como objetivos táctiles.

Baseline Mecanismo: abandonar las existencias existentes, agregar nuevas intercepciones, solo disminuir pero no aumentar.

Uso:
  py -3 ci/script/check_touch_target.py
  py -3 ci/script/check_touch_target.py --update-baseline
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

from baseline_utils import require_baseline

PROJECT_ROOT = Path(__file__).resolve().parents[2]
UI_DIR = PROJECT_ROOT / "app" / "src" / "main" / "java" / "io" / "zer0" / "muse" / "ui"
BASELINE_PATH = PROJECT_ROOT / "ci" / "baseline" / "touch_target_baseline.txt"

SIZE_MODIFIER = re.compile(r"Modifier\.size\((\d+(?:\.\d+)?)\.dp\)")


def scan_file(path: Path) -> list[tuple[int, str]]:
    hits: list[tuple[int, str]] = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeDecodeError):
        return hits
    for i, line in enumerate(lines):
        if re.search(r"IconButton\(", line):
            end = min(len(lines), i + 6)
            for j in range(i, end):
                if "{" in lines[j]:
                    end = j
                    break
            window = "\n".join(lines[i:end])
            sizes = [float(x) for x in SIZE_MODIFIER.findall(window)]
            if sizes and min(sizes) < 48:
                hits.append((i + 1, line.strip()))
        elif re.search(r"clickable\(|combinedClickable\(", line):
            sizes = [float(x) for x in SIZE_MODIFIER.findall(line)]
            if sizes and min(sizes) < 48:
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
        "# touch_target baseline — Lista de inventario de objetivos táctiles pequeños",
        "# Formato: <file>: <Número de violaciones>;Agregar o aumentar fallará,solo se permite descender",
        "# Ejecutar después de limpiar el inventario.: py -3 ci/script/check_touch_target.py --update-baseline",
        "#",
        f"# total actual {sum(len(v) for v in violations.values())} en,Distribución {len(violations)} archivos",
    ]
    for file, hits in sorted(violations.items()):
        lines.append(f"{file}: {len(hits)}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Verificación de objetivo táctil")
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

    print(f"[touch-target] Escanee el directorio ui/: {total} pequeño objetivo táctil,Distribución {len(violations)} archivos")

    if not require_baseline(args.baseline, "touch-target"):
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
        print("[touch-target] hay mejora,Se recomienda ajustar --update-baseline:")
        for line in improved:
            print(line)

    if failures:
        print("[touch-target] FAILED — Se agregó un pequeño objetivo táctil.:")
        for line in failures:
            print(line)
        print("como solucionarlo: IconButton/clickable uso Modifier.size(48.dp) o minimumInteractiveComponentSize")
        return 1

    print(f"[touch-target] PASS — No hay nuevos objetivos táctiles pequeños (Valores {total} en,Exento por línea de base)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
