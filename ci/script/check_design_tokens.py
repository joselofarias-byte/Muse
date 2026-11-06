#!/usr/bin/env python3
"""
check_design_tokens.py — Verificación de CI del token de diseño.

Escanee el código Jetpack Compose en el directorio ui/,Bloquear codificación rígida que pasa por alto el sistema de token de diseño:
  - Color(0x...) desnudo literal (error)
  - Color(255, 0, 0) RGB color desnudo (error)
  - Estadísticas .dp / .sp codificadas (info,Informar solo la cantidad sin interceptación.)

Catálogo de exenciones:
  - ui/theme/ — Color.kt / PresetTheme.kt / CustomTheme.kt / DesignTokens.kt espera,
    Este es el único lugar legal para las definiciones de colores.

Baseline mecanismo (Deja ir las acciones,Agregar intercepción,Solo cayendo pero no subiendo):
  - CI modo (--baseline ci/baseline/design_tokens_baseline.txt):
    Compare el número de infracciones archivo por archivo,Si hay más archivos que los de referencia o aparecen nuevos archivos infractores, fallará.;Menos pases que la línea de fondo.
  - actualizar baseline (--update-baseline):Regenerar después de limpiar el inventario.

Uso:
  py -3 ci/script/check_design_tokens.py                      # Escanee e imprima todas las infracciones
  py -3 ci/script/check_design_tokens.py --baseline <path>    # CI modo de contraste
  py -3 ci/script/check_design_tokens.py --update-baseline    # regenerar baseline
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

# exención:directorio de definición de temas,La única posición legal para los literales de color
EXEMPT_DIRS = {"theme"}

# exención:producto —— ui/common/icons/ Bajo el oleoducto de los iconos de las musas(to-compose.py)archivo de salida,
# Contiene el valor del color de la ruta del icono(La codificación dura es generar el resultado.,Los cambios manuales serán sobrescritos por la próxima generación.),no debería ser"ficha de diseño"Intercepción de reglas.
GENERATED_ICON_DIR = ("common", "icons")
GENERATED_MARK = "Generado automáticamente"

BASELINE_PATH = PROJECT_ROOT / "ci" / "baseline" / "design_tokens_baseline.txt"

# Definición de regla
RULES = {
    "hardcoded_hex_color": {
        "pattern": re.compile(r"\bColor\(\s*0[xX][0-9a-fA-F]{6,8}"),
        "severity": "error",
        "message": "Color nude codificado de forma rígida Color(0x...),Utilice MaterialTheme.colorScheme o el token definido en tema/",
    },
    "hardcoded_rgb_color": {
        # Color(255, 0, 0) / Color(red = 255, ...) forma
        "pattern": re.compile(r"\bColor\(\s*(?:red\s*=\s*)?\d{1,3}\s*,"),
        "severity": "error",
        "message": "Color desnudo RGB codificado Color(r, g, b),Utilice MaterialTheme.colorScheme o el token definido en tema/",
    },
    # copy(alpha=...) El Color.XXX.copy no está codificado,no interceptar
}

# Reglas de estadísticas de información.(contar solo,No afecta el paso/falló)
INFO_RULES = {
    "hardcoded_dp": re.compile(r"\b\d+(?:\.\d+)?\.dp\b"),
    "hardcoded_sp": re.compile(r"\b\d+(?:\.\d+)?\.sp\b"),
}


@dataclass
class Violation:
    file: Path
    line: int
    rule: str
    message: str
    code: str


def is_exempt(path: Path) -> bool:
    """theme/ Directorio(área de definición de color)Genere controles de inmunidad con íconos de musa."""
    try:
        rel = path.relative_to(UI_DIR)
    except ValueError:
        return False
    if rel.parts[0] in EXEMPT_DIRS:
        return True
    if tuple(rel.parts[:2]) == GENERATED_ICON_DIR:
        head = path.read_text(encoding="utf-8", errors="ignore")[:800]
        if GENERATED_MARK in head:
            return True
    return False


def strip_comments(code: str, in_block: bool) -> tuple[str, bool]:
    """bloque de sacrificio/Comentarios de línea,y omitir literales de cadena(No "text/*" adentro /* Cuando bloquear comentarios,Evite tragar el código posterior)."""
    out: list[str] = []
    i = 0
    n = len(code)
    while i < n:
        ch = code[i]
        if in_block:
            if ch == "*" and i + 1 < n and code[i + 1] == "/":
                in_block = False
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
                c = code[i]
                out.append(c)
                if c == "\\" and i + 1 < n:
                    out.append(code[i + 1])
                    i += 2
                    continue
                if c == quote:
                    i += 1
                    break
                i += 1
            continue
        if ch == "/" and i + 1 < n and code[i + 1] == "*":
            in_block = True
            i += 2
            continue
        if ch == "/" and i + 1 < n and code[i + 1] == "/":
            break  # Comentarios de línea,Descartar el resto
        out.append(ch)
        i += 1
    return "".join(out), in_block


def scan_file(path: Path) -> list[Violation]:
    violations: list[Violation] = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeDecodeError) as e:
        print(f"WARN: No puedo leer {path}: {e}", file=sys.stderr)
        return violations
    in_block_comment = False
    for lineno, line in enumerate(lines, 1):
        code, in_block_comment = strip_comments(line, in_block_comment)
        if not code.strip():
            continue
        for rule_name, rule in RULES.items():
            if rule["pattern"].search(code):
                violations.append(
                    Violation(path, lineno, rule_name, rule["message"], line.strip())
                )
                break  # Recuerda cada línea solo una vez.,Evite el doble conteo de reglas dobles
    return violations


def scan_all() -> dict[str, list[Violation]]:
    """Regresar {ruta relativa: [Lista de violaciones]},Sólo se incluyen archivos con infracciones."""
    result: dict[str, list[Violation]] = {}
    for kt in sorted(UI_DIR.rglob("*.kt")):
        if is_exempt(kt):
            continue
        vs = scan_file(kt)
        if vs:
            result[str(kt.relative_to(PROJECT_ROOT)).replace("\\", "/")] = vs
    return result


def count_info_stats() -> tuple[int, int]:
    """Cuente el número de .dp/.sp codificados en todo el directorio ui/(Contiene theme/,Sólo como referencia)."""
    dp = sp = 0
    for kt in UI_DIR.rglob("*.kt"):
        try:
            text = kt.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        dp += len(INFO_RULES["hardcoded_dp"].findall(text))
        sp += len(INFO_RULES["hardcoded_sp"].findall(text))
    return dp, sp


def load_baseline(path: Path) -> dict[str, int]:
    """baseline Formato: por línea 'file/path.kt: <count>'."""
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
        "# design_tokens baseline — Lista de exenciones de colores nude codificadas en stock",
        "# Formato: <file>: <Número de violaciones>;CI Solo permita que disminuya el número de infracciones.,Agregar o aumentar fallará",
        "# Ejecutar después de limpiar el inventario.: py -3 ci/script/check_design_tokens.py --update-baseline",
        "#",
        f"# total actual {sum(len(v) for v in violations.values())} en,Distribución {len(violations)} archivos",
    ]
    for file, vs in sorted(violations.items()):
        lines.append(f"{file}: {len(vs)}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Verificación codificada del token de diseño")
    parser.add_argument("--baseline", type=Path, default=BASELINE_PATH, help="baseline ruta del archivo")
    parser.add_argument("--update-baseline", action="store_true", help="Sobrescribir con los resultados del análisis actual baseline")
    parser.add_argument("--verbose", action="store_true", help="Imprimir detalles de cada infracción")
    args = parser.parse_args()

    violations = scan_all()
    total = sum(len(v) for v in violations.values())
    dp_count, sp_count = count_info_stats()

    if args.update_baseline:
        write_baseline(args.baseline, violations)
        print(f"baseline actualizado: {args.baseline}")
        print(f"  total {total} color desnudo,Distribución {len(violations)} archivos")
        return 0

    print(f"[design-tokens] Escanee el directorio ui/: {total} Color nude codificado,Distribución {len(violations)} archivos")
    print(f"[design-tokens] Estadísticas de referencia: {dp_count} en .dp / {sp_count} en .sp codificado")

    if args.verbose:
        for file, vs in sorted(violations.items()):
            for v in vs:
                print(f"  {file}:{v.line} [{v.rule}] {v.message}")
                print(f"    {v.code}")

    if not require_baseline(args.baseline, "design-tokens"):
        return 2
    # baseline El archivo es efectivo cuando existe.(lista vacía = tolerancia cero,Cualquier nueva incorporación falló.)
    baseline = load_baseline(args.baseline)

    # modo de contraste:Solo cayendo pero no subiendo
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
        print(f"[design-tokens] hay mejora,Se recomienda ejecutar --update-baseline para ajustar:")
        for line in improved:
            print(line)

    if failures:
        print(f"[design-tokens] FAILED — Se agregó color nude codificado (AGENTS.md Especificación del token de diseño):")
        for line in failures:
            print(line)
        print("como solucionarlo: Utilice MaterialTheme.colorScheme/theme/ token;Si está en stock, límpielo. --update-baseline")
        return 1

    print(f"[design-tokens] PASS — No hay nuevos colores nude (Valores {total} en,Exento por línea de base)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
