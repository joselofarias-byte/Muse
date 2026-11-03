#!/usr/bin/env python3
"""
check_hardcoded_cjk.py — UI Verificación literal china codificada.

Escanee el código Jetpack Compose en el directorio ui/ e intercepte el código que omite stringResource.
Cadenas chinas codificadas (se ignora la existencia, se agrega una nueva interceptación, solo disminuye pero no aumenta).

Alcance del reconocimiento:
  - La misma línea de cadenas entre comillas dobles contiene literales CJK, como Text("Hola")
  - La misma línea contiene comillas dobles y contiene el código de portada de CJK (que cubre el comienzo de la cadena sin formato)./línea final)
  - Elimine automáticamente los comentarios de línea y bloquee los comentarios para evitar falsos positivos en los ejemplos de documentos.

Limitaciones conocidas:
  - No se reconocen las líneas de continuación sin comillas en cadenas sin formato que abarcan varias líneas
  - El análisis léxico de Kotlin más estricto se deja en manos de los complementos IDE/Lint; este script se utiliza para la puerta rápida de CI

Baseline Mecanismo:
  - CI modo(--baseline ci/baseline/hardcoded_cjk_baseline.txt):
    Compare el número de infracciones archivo por archivo. Si es mayor que la línea base o aparecen nuevos archivos de infracción, fallará.
  - actualizar línea base (--update-baseline): Regenerar después de limpiar el inventario.

Uso:
  py -3 ci/script/check_hardcoded_cjk.py
  py -3 ci/script/check_hardcoded_cjk.py --baseline <path>
  py -3 ci/script/check_hardcoded_cjk.py --update-baseline
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

from baseline_utils import require_baseline

PROJECT_ROOT = Path(__file__).resolve().parents[2]
UI_DIR = PROJECT_ROOT / "app" / "src" / "main" / "java" / "io" / "zer0" / "muse" / "ui"
BASELINE_PATH = PROJECT_ROOT / "ci" / "baseline" / "hardcoded_cjk_baseline.txt"

# Las cadenas entre comillas dobles en la misma línea contienen CJK
CJK_IN_QUOTED_STRING = re.compile(r'"([^"\n]*[\u4e00-\u9fff][^"\n]*)"')
# Solución de problemas: líneas que contienen comillas dobles y CJK (que cubren las líneas límite de una cadena sin formato)
CJK_WITH_QUOTE = re.compile(r'[\u4e00-\u9fff]')
LOGGER_CALL_START = re.compile(r'\b(?:Logger|Log)\.[A-Za-z]+\s*\(')


@dataclass
class Violation:
    file: Path
    line: int
    code: str


def strip_comments(line: str, in_block_comment: bool) -> tuple[str, bool]:
    """bloque de sacrificio/Comentarios de línea,y omitir literales de cadena(No "text/*" adentro /* Cuando bloquear comentarios,Evite tragar el código posterior)."""
    out: list[str] = []
    i = 0
    n = len(line)
    while i < n:
        ch = line[i]
        if in_block_comment:
            if ch == "*" and i + 1 < n and line[i + 1] == "/":
                in_block_comment = False
                i += 2
                continue
            i += 1
            continue
        if ch in "\"'":
            # literal de cadena:tal cual emit,saltarse el /* con //(Contiene escape)
            quote = ch
            out.append(ch)
            i += 1
            while i < n:
                c = line[i]
                out.append(c)
                if c == "\\" and i + 1 < n:
                    out.append(line[i + 1])
                    i += 2
                    continue
                if c == quote:
                    i += 1
                    break
                i += 1
            continue
        if ch == "/" and i + 1 < n and line[i + 1] == "*":
            in_block_comment = True
            i += 2
            continue
        if ch == "/" and i + 1 < n and line[i + 1] == "/":
            break  # Comentarios de línea,Descartar el resto
        out.append(ch)
        i += 1
    return "".join(out), in_block_comment


def strip_cjk_logger_calls(code: str, in_logger_call: bool = False) -> tuple[str, bool]:
    """Eliminar la expresión completa de llamada Logger/Log,Admite parámetros de llamada entre líneas."""
    result: list[str] = []
    cursor = 0
    depth = 1 if in_logger_call else 0
    index = 0
    while index < len(code):
        if depth == 0:
            match = LOGGER_CALL_START.search(code, index)
            if match is None:
                result.append(code[index:])
                return "".join(result), False
            result.append(code[index : match.start()])
            depth = 1
            index = match.end()
            continue
        if code[index] in "\"'":
            quote = code[index]
            index += 1
            while index < len(code):
                if code[index] == "\\":
                    index += 2
                    continue
                if code[index] == quote:
                    index += 1
                    break
                index += 1
            continue
        if code[index] == "(":
            depth += 1
        elif code[index] == ")":
            depth -= 1
        index += 1
    return "".join(result), depth > 0


def scan_file(path: Path) -> list[Violation]:
    violations: list[Violation] = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeDecodeError) as e:
        print(f"WARN: No puedo leer {path}: {e}", file=sys.stderr)
        return violations
    in_block_comment = False
    in_logger_call = False
    for lineno, line in enumerate(lines, 1):
        code, in_block_comment = strip_comments(line, in_block_comment)
        code, in_logger_call = strip_cjk_logger_calls(code, in_logger_call)
        if not code.strip():
            continue
        # Las llamadas de registro son diagnósticos internos,No incluido en las comprobaciones de copia de UI i18n
        if re.search(r'\b(Logger|Log)\.[A-Za-z]+\(.*[\u4e00-\u9fff]', code):
            continue
        if CJK_IN_QUOTED_STRING.search(code):
            violations.append(Violation(path, lineno, line.strip()))
        elif '"' in code and CJK_WITH_QUOTE.search(code):
            violations.append(Violation(path, lineno, line.strip()))
    return violations


def scan_all() -> dict[str, list[Violation]]:
    result: dict[str, list[Violation]] = {}
    for kt in sorted(UI_DIR.rglob("*.kt")):
        vs = scan_file(kt)
        if vs:
            result[str(kt.relative_to(PROJECT_ROOT)).replace("\\", "/")] = vs
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


def write_baseline(path: Path, violations: dict[str, list[Violation]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "# hardcoded_cjk baseline — Lista de exenciones de literales chinos codificados existentes",
        "# Formato: <file>: <Número de violaciones>;Agregar o aumentar fallará,solo se permite descender",
        "# Ejecutar después de limpiar el inventario.: py -3 ci/script/check_hardcoded_cjk.py --update-baseline",
        "#",
        f"# total actual {sum(len(v) for v in violations.values())} en,Distribución {len(violations)} archivos",
    ]
    for file, vs in sorted(violations.items()):
        lines.append(f"{file}: {len(vs)}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="UI Verificación literal china codificada")
    parser.add_argument("--baseline", type=Path, default=BASELINE_PATH, help="baseline ruta del archivo")
    parser.add_argument("--update-baseline", action="store_true", help="Sobrescribir con los resultados del análisis actual baseline")
    parser.add_argument("--verbose", action="store_true", help="Imprimir detalles de cada infracción")
    args = parser.parse_args()

    violations = scan_all()
    total = sum(len(v) for v in violations.values())

    if args.update_baseline:
        write_baseline(args.baseline, violations)
        print(f"baseline actualizado: {args.baseline}")
        print(f"  total {total} Literales chinos codificados,Distribución {len(violations)} archivos")
        return 0

    print(f"[hardcoded-cjk] Escanee el directorio ui/: {total} Literales chinos codificados,Distribución {len(violations)} archivos")

    if args.verbose:
        for file, vs in sorted(violations.items()):
            for v in vs:
                print(f"  {file}:{v.line} {v.code}")

    if not require_baseline(args.baseline, "hardcoded-cjk"):
        return 2

    baseline = load_baseline(args.baseline)
    failures: list[str] = []
    improved: list[str] = []
    for file, vs in sorted(violations.items()):
        current = len(vs)
        allowed = baseline.get(file, 0)
        if current > allowed:
            delta = current - allowed
            failures.append(f"  {file}: {current} en (baseline {allowed},Nuevo {delta})")
        elif current < allowed:
            improved.append(f"  {file}: {allowed} → {current} (limpiado {allowed - current} en)")
    cleaned_files = [f for f in baseline if f not in violations]
    for f in cleaned_files:
        improved.append(f"  {f}: Borrado")

    if improved:
        print("[hardcoded-cjk] hay mejora,Se recomienda ejecutar --update-baseline para ajustar:")
        for line in improved:
            print(line)

    if failures:
        print("[hardcoded-cjk] FAILED — Se agregaron literales chinos codificados:")
        for line in failures:
            print(line)
        print("como solucionarlo: uso stringResource(R.string.xxx);Si está en stock, límpielo. --update-baseline")
        return 1

    print(f"[hardcoded-cjk] PASS — No hay nuevos literales chinos codificados (Valores {total} en,Exento por línea de base)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
