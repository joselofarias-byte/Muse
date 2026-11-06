#!/usr/bin/env python3
"""
check_perf_hotspots.py — "Return Sentinel" (estilo básico) para puntos críticos de rendimiento.

## ¿Por qué necesitas este control de acceso?

2026-11-01 Entre las mejoras de rendimiento, los elementos más valiosos no son"lo que se agregó", pero**lo que fue eliminado**:
Archivo de cambio de registro por línea, búfer circular por línea O(n) Recorra, ejecute BPE para cada cuadro y cree un nuevo serializador para cada llamada/regular……
Estos están todos escritos**Se siente natural escribir,review Parece inofensivo y nadie se da cuenta cuando se retira.**.

Esta puerta los clava:**Utilice el estado actual como punto de referencia y solo informe las nuevas incorporaciones.**.
De esta manera, el ruido bursátil no afecta el juicio, pero"Lo escribiré la próxima vez que refactorice."Será atrapado de inmediato.

## reglas

| reglas | partido | ¿Por qué es un punto caliente? |
|---|---|---|
| `log_append_text` | `appendText(` | Abrir+escribir+cerrar tres llamadas al sistema por línea (se debe cambiar el identificador residente) |
| `deque_size` | `ConcurrentLinkedDeque` en el archivo `.size` juez | `size()` si O(n) atravesar |
| `inline_json` | no `val x =` formales `Json {` | Crea un nuevo serializador cada vez |
| `inline_regex` | no `val x =` formales `Regex("` | Recompilar cada llamada |
| `bpe_in_composable` | `@Composable` Aparecen filas adyacentes `TokenEstimator.estimate(` | Ejecución del período combinado BPE |

Uso:
  py -3 ci/script/check_perf_hotspots.py                  # Informar únicamente de nuevas incorporaciones
  py -3 ci/script/check_perf_hotspots.py --update-baseline
  py -3 ci/script/check_perf_hotspots.py --verbose
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
BASELINE_PATH = PROJECT_ROOT / "ci" / "baseline" / "perf_hotspots_baseline.json"

SCAN_DIRS = [
    PROJECT_ROOT / "app" / "src" / "main" / "java",
    PROJECT_ROOT / "common" / "src" / "main" / "java",
    PROJECT_ROOT / "memory" / "src" / "main" / "java",
]

# La "implementación en sí" no es una violación
EXEMPT_FILES = {"Logger.kt", "DebugLogStore.kt"}

VAL_DECL = re.compile(r"^\s*(?:private |internal |public |protected |const )?val \w+\s*(?::[^=]+)?=")
COMPOSABLE = re.compile(r"^\s*@Composable\b")
RULES = {
    # Sólo verdadero File.appendText; nombre del método personalizado appendText(...) Excluir por fila a continuación
    "log_append_text": re.compile(r"\.appendText\("),
    "inline_json": re.compile(r"\bJson\s*\{"),
    "inline_regex": re.compile(r"\bRegex\(\s*\""),
    "bpe_in_composable": re.compile(r"TokenEstimator\.estimate\("),
    "deque_size": re.compile(r"\.size\b"),
}


def iter_kt_files():
    for base in SCAN_DIRS:
        if base.is_dir():
            yield from (p for p in base.rglob("*.kt") if p.name not in EXEMPT_FILES)


def scan_file(path: Path) -> dict[str, int]:
    counts: dict[str, int] = {}
    text = path.read_text(encoding="utf-8", errors="ignore")
    lines = text.splitlines()
    mentions_deque = "ConcurrentLinkedDeque" in text
    composable_window = 0

    for line in lines:
        stripped = line.strip()
        if stripped.startswith("//") or stripped.startswith("*"):
            continue

        if COMPOSABLE.match(line):
            composable_window = 12   # El rango aproximado del cuerpo de función combinada.
        elif composable_window > 0:
            composable_window -= 1

        # Excluir definiciones de métodos personalizados/Llamar:`fun appendText(` El mismo nombre que en el buffer de streaming. appendText(...)
        if RULES["log_append_text"].search(line) and "fun appendText" not in line and "appendText(sequenceInStream" not in line:
            counts["log_append_text"] = counts.get("log_append_text", 0) + 1

        # Asignación declarativa (clase/valor a nivel de objeto) no cuenta"Crear nuevo cada vez"
        declared = bool(VAL_DECL.match(line))
        if not declared and RULES["inline_json"].search(line):
            counts["inline_json"] = counts.get("inline_json", 0) + 1
        if not declared and RULES["inline_regex"].search(line):
            counts["inline_regex"] = counts.get("inline_regex", 0) + 1

        if composable_window > 0 and RULES["bpe_in_composable"].search(line):
            counts["bpe_in_composable"] = counts.get("bpe_in_composable", 0) + 1

        if mentions_deque and RULES["deque_size"].search(line) and re.search(r"\b(?:if|while|for)\b", line):
            counts["deque_size"] = counts.get("deque_size", 0) + 1

    return counts


def current_counts() -> dict[str, int]:
    result: dict[str, int] = {}
    for path in sorted(iter_kt_files()):
        rel = path.relative_to(PROJECT_ROOT).as_posix()
        for rule, count in scan_file(path).items():
            result[f"{rel}|{rule}"] = count
    return result


def load_baseline() -> dict[str, int]:
    if not BASELINE_PATH.exists():
        return {}
    return json.loads(BASELINE_PATH.read_text(encoding="utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--update-baseline", action="store_true")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    current = current_counts()

    if args.update_baseline:
        BASELINE_PATH.parent.mkdir(parents=True, exist_ok=True)
        BASELINE_PATH.write_text(
            json.dumps(dict(sorted(current.items())), indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )
        print(f"Línea de base escrita:{BASELINE_PATH.relative_to(PROJECT_ROOT)}({len(current)} artículo)")
        return 0

    baseline = load_baseline()
    regressions = []
    for key, count in sorted(current.items()):
        allowed = baseline.get(key, 0)
        if count > allowed:
            regressions.append((key, allowed, count))

    if regressions:
        print("FAILED — Método de escritura de punto de acceso de rendimiento recientemente agregado:")
        for key, allowed, count in regressions:
            path, rule = key.rsplit("|", 1)
            print(f"  [{rule}] {path}: {count} en (línea de base {allowed})")
        print()
        print("Consulte los comentarios del encabezado de este script; si es necesario, explique el motivo --update-baseline.")
        return 1

    if args.verbose:
        print(f"total actual {len(current)} registros, todos dentro de la línea de base.")
    print("OK: No existe una nueva forma de escribir puntos críticos de rendimiento.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
