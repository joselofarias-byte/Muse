#!/usr/bin/env python3
"""
check_engineering_discipline.py — Inspección de CI de disciplina de ingeniería.

Escanee el código de diferenciación en busca de patrones que violen la especificación AGENTS.md:
  - conversión de tipo inseguro (as String / as Int / as Any)
  - Aserción no nula sin comentarios !!
  - Bloque de captura vacío
  - nulo incondicional
  - Valores predeterminados incondicionales
  - El tipo vuelve a Any (val/var : Any Declaración)
  - when expresión else -> null cubierta tipo junta
  - La función devuelve el tipo Cualquiera
  - TODO/FIXME La cantidad excede el límite

Uso:
  py -3 ci/script/check_engineering_discipline.py [--diff] [--base main] [--head HEAD]
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

# Windows Predeterminado de la consola china GBK,Y este script se imprimirá ⚠/✓/✗(No en el juego de caracteres GBK),
# La salida directa arrojará UnicodeEncodeError. Cortado uniformemente a UTF-8.
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # noqa: BLE001 - El cheque en sí no debería verse afectado si el entorno no lo soporta.
    pass

# Directorio raíz del proyecto
PROJECT_ROOT = Path(__file__).resolve().parents[2]

# Consultar reglas
RULES = {
    "unsafe_cast": {
        "pattern": re.compile(r"\bas\s+(String|Int|Long|Float|Double|Boolean|Any|JsonObject)\b"),
        "severity": "warning",
        "message": "Conversión de tipo no segura, utilice una conversión segura (as? + ?: o if-let)",
    },
    "non_null_assertion": {
        # partido !! Pero a los compañeros se les permite tener // comentarios más tarde
        # (?![^\n]*//) expresar: si !! Hay un // comentario en la misma línea después,entonces no marques
        "pattern": re.compile(r"!!(?![^\n]*//)"),
        "severity": "warning",
        "message": "Aserción no nula sin comentarios !!, por favor agregue un comentario o use require/check",
    },
    "empty_catch": {
        "pattern": re.compile(r"catch\s*\([^)]+\)\s*\{\s*\}"),
        "severity": "error",
        "message": "Bloque de captura vacío, debe registrar o manejar excepciones",
    },
    "null_fallback": {
        "pattern": re.compile(r"\?:\s*null(?!\s*//)"),
        "severity": "warning",
        "message": "Nulo incondicional. Por favor explique el motivo o uso. error()",
    },
    "default_fallback": {
        "pattern": re.compile(r'\?:\s*"(?:unknown|default)"'),
        "severity": "warning",
        "message": "El valor predeterminado incondicional es una obviedad, explique por qué",
    },
    # P3-5: Escriba reglas de seguridad (AGENTS.md §2.1 / §5)
    "any_type_declaration": {
        # Coincide con val/var x: Cualquiera o val/var x: Any? Una declaración explícita de cualquier tipo para
        # excluir Map<String, Any> espera(Ese es un parámetro genérico.,No es un tipo de variable alternativa)
        "pattern": re.compile(r"\b(?:val|var)\s+\w+\s*:\s*Any\b(?!\s*[<,])"),
        "severity": "warning",
        "message": "El tipo de variable vuelve a ser Cualquiera, utilice un tipo específico o sealed class (AGENTS.md §2.1)",
    },
    "any_return_type": {
        # partido fun foo(...): Any o fun foo(...): Any? La función regresa Any
        "pattern": re.compile(r"\bfun\s+\w+\s*\([^)]*\)\s*:\s*Any\b(?!\s*[<,])"),
        "severity": "warning",
        "message": "La función devuelve el tipo Cualquiera, utilice un tipo concreto o sealed class (AGENTS.md §5)",
    },
    "when_else_null": {
        # Coincidencias dentro de un bloque cuando else -> null (cubierta tipo junta)
        "pattern": re.compile(r"\belse\s*->\s*null\b(?!\s*//)"),
        "severity": "warning",
        "message": "when expresión else -> null Cubierta tipo junta, utilice error() o sealed class (AGENTS.md §5)",
    },
    # P3-5: conversión anulable insegura (AGENTS.md §2.2)
    "nullable_unsafe_cast": {
        # partido (value as String) Esta conversión insegura a valores que aceptan valores NULL
        "pattern": re.compile(r"\(\s*\w+\s+as\s+(String|Int|Long|Float|Double|Boolean)\s*\)"),
        "severity": "warning",
        "message": "Para usar como conversión para valores que aceptan valores NULL, use as? + ?: o if-let (AGENTS.md §2.2)",
    },
}

# TODO/FIXME Límite
MAX_TODO_COUNT = 10
TODO_PATTERN = re.compile(r"\b(?:TODO|FIXME)\b")

# Extensiones de archivos escaneados
SCAN_EXTENSIONS = {".kt", ".java"}


def get_diff_files(base: str = "main", head: str = "HEAD") -> list[Path]:
    """Obtener la lista de archivos modificados en diff"""
    try:
        result = subprocess.run(
            ["git", "diff", "--name-only", f"{base}...{head}"],
            cwd=PROJECT_ROOT,
            capture_output=True,
            text=True,
            timeout=30,
        )
        if result.returncode != 0:
            # revertir: Utilice git diff --cached o git status
            result = subprocess.run(
                ["git", "diff", "--name-only", "HEAD"],
                cwd=PROJECT_ROOT,
                capture_output=True,
                text=True,
                timeout=30,
            )
        files = []
        for line in result.stdout.strip().split("\n"):
            if line:
                f = PROJECT_ROOT / line
                if f.exists() and f.suffix in SCAN_EXTENSIONS:
                    files.append(f)
        return files
    except Exception:
        return []


def get_diff_lines(file_path: Path, base: str = "main") -> list[str]:
    """Obtener nuevas líneas en el archivo diff"""
    try:
        rel = file_path.relative_to(PROJECT_ROOT).as_posix()
        result = subprocess.run(
            ["git", "diff", f"{base}...HEAD", "--unified=0", rel],
            cwd=PROJECT_ROOT,
            capture_output=True,
            text=True,
            timeout=30,
        )
        if result.returncode != 0:
            result = subprocess.run(
                ["git", "diff", "HEAD", "--unified=0", rel],
                cwd=PROJECT_ROOT,
                capture_output=True,
                text=True,
                timeout=30,
            )
        added_lines = []
        for line in result.stdout.split("\n"):
            if line.startswith("+") and not line.startswith("+++"):
                added_lines.append(line[1:])
        return added_lines
    except Exception:
        return []


def _raw_string_mask(lines: list[str]) -> list[bool]:
    """Marque si cada línea se encuentra dentro de la cadena sin formato entre comillas triples de Kotlin (incluidas las líneas inicial y final)."""
    mask: list[bool] = []
    inside = False
    for line in lines:
        starts_inside = inside
        # Puede aparecer en pares en la misma línea ("""..."""), contando invertidas sucesivamente
        count = line.count('"""')
        # Eliminar interferencias de los comentarios:`//` Ya no participar en el juicio (procesamiento aproximado)
        if count % 2 == 1:
            inside = not inside
        mask.append(starts_inside or inside or count > 0)
    return mask


def scan_file(file_path: Path, added_lines: list[str] | None = None) -> list[dict]:
    """Escanee archivos individuales en busca de patrones de infracción"""
    violations = []
    content = file_path.read_text(encoding="utf-8", errors="ignore")
    lines = content.split("\n")

    # Si hay líneas de diferencias, sólo se verifican las líneas nuevas; en caso contrario, se comprueba el texto completo.
    if added_lines is not None:
        check_lines = added_lines
    else:
        check_lines = lines

    # saltar**cadena cruda (Kotlin líneas dentro del bloque de comillas triples)**.
    #
    # El enlace de la tarjeta escribe una sección completa de JS inyectada en WebView directamente en """…""" adentro,
    # que dentro `try { ... } catch (e) { }` Es un truco vacío en JS, es intencional.
    # (Una llamada de puente fallida no debería provocar que falle la tarjeta). Son todos falsos positivos según las reglas de Kotlin.
    raw_string_mask = _raw_string_mask(lines)


    for rule_name, rule in RULES.items():
        for line_num, line in enumerate(check_lines, 1):
            if added_lines is None and line_num - 1 < len(raw_string_mask) and raw_string_mask[line_num - 1]:
                continue
            for match in rule["pattern"].finditer(line):
                violations.append({
                    "file": str(file_path.relative_to(PROJECT_ROOT)),
                    "line": line_num,
                    "rule": rule_name,
                    "severity": rule["severity"],
                    "message": rule["message"],
                    "match": match.group(0),
                    "code": line.strip(),
                })

    # TODO/FIXME contar(Texto completo)
    todo_count = len(TODO_PATTERN.findall(content))
    if todo_count > MAX_TODO_COUNT:
        violations.append({
            "file": str(file_path.relative_to(PROJECT_ROOT)),
            "line": 0,
            "rule": "todo_overflow",
            "severity": "warning",
            "message": f"TODO/FIXME La cantidad excede el límite ({todo_count} > {MAX_TODO_COUNT})",
            "match": "",
            "code": "",
        })

    return violations


def main():
    parser = argparse.ArgumentParser(description="Muse Inspección de CI de disciplina de ingeniería")
    parser.add_argument("--diff", action="store_true", help="Verifique solo las líneas nuevas en diff")
    parser.add_argument("--base", default="main", help="diff rama base(Predeterminado: main)")
    parser.add_argument("--head", default="HEAD", help="diff objetivo(Predeterminado: HEAD)")
    parser.add_argument("--path", default=None, help="Escanear solo rutas especificadas")
    args = parser.parse_args()

    print("=" * 60)
    print("Engineering Discipline Check")
    print("=" * 60)

    # Recopilar archivos para escanear
    if args.diff:
        files = get_diff_files(args.base, args.head)
        print(f"Scanning {len(files)} changed files (diff mode)")
    else:
        scan_path = Path(args.path) if args.path else PROJECT_ROOT
        files = []
        for ext in SCAN_EXTENSIONS:
            files.extend(scan_path.rglob(f"*{ext}"))
        # Filtrar el directorio de compilación
        files = [f for f in files if "build" not in f.parts]
        print(f"Scanning {len(files)} files (full mode)")

    all_violations = []
    for file_path in files:
        if args.diff:
            added = get_diff_lines(file_path, args.base)
            violations = scan_file(file_path, added)
        else:
            violations = scan_file(file_path)
        all_violations.extend(violations)

    # Resultados de salida
    errors = [v for v in all_violations if v["severity"] == "error"]
    warnings = [v for v in all_violations if v["severity"] == "warning"]

    if all_violations:
        print(f"\nFound {len(errors)} errors, {len(warnings)} warnings:\n")
        for v in all_violations:
            icon = "✗" if v["severity"] == "error" else "⚠"
            print(f"  {icon} [{v['severity'].upper()}] {v['file']}:{v['line']}")
            print(f"    Rule: {v['rule']} — {v['message']}")
            if v["code"]:
                print(f"    Code: {v['code']}")
            print()
    else:
        print("\n✓ No violations found")

    print(f"\n{'=' * 60}")
    print(f"Summary: {len(errors)} errors, {len(warnings)} warnings")
    print(f"{'=' * 60}")

    # error evitar la fusión, warning solo recuerda
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
