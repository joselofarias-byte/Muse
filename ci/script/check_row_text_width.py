#!/usr/bin/env python3
"""
check_row_text_width.py — Row El texto interno no está ampliado (ajuste de palabras/colapso de columnas de texto).

## ¿Por qué necesitas este control de acceso?

El mismo tipo de problema en línea ha aparecido repetidamente en la historia: el texto en una determinada línea aumenta el tamaño de fuente (el sistema"Tamaño de visualización/tamaño de fuente")
O ser apretujado en una columna bajo una ventana estrecha, dejando sólo 1~4 Un personaje era estirado en docenas de líneas y luego cortado por la tarjeta.
(Ejemplo:MCP fila de la lista de servidores `McpServerRow`, fila del panel de acciones `MuseActionSheetRow`).

La causa principal no es el tamaño de fuente, sino la estructura:
  - `Row` Los subelementos de ancho fijo (interruptores/botones/iconos/insignias) se miden primero según el ancho que necesita;
  - El niño que lleva el texto no tiene `Modifier.weight(1f)`(o `fillMaxWidth()`)→ solo tengo
    "Ancho mínimo disponible"(El chino puede ser tan estrecho como 1 carácter), y el ancho restante lo come el hijo de ancho fijo;
  - no hay texto `maxLines`/`overflow` No conozco el resultado final, por lo que puedes cambiar la línea infinitamente.

Sentencia:**Hay competencia de ancho dentro de la fila (≥2 niño), el niño que lleva el texto debe ampliarse explícitamente**
(`Modifier.weight(...)` / `fillMaxWidth()` / `fillMaxSize()` / explícito `width(...)`),
o al menos tener `maxLines` Mantenga la línea final (líneas limitadas + puntos suspensivos, no se extenderá en docenas de líneas).

Exención: Fila de un solo elemento (sin competencia de ancho),FlowRow(en sí mismo un contenedor de envoltura de línea de transmisión),
Texto en el parámetro de ranura (`label = { Text(...) }`, el ancho lo determina el contenedor).

Uso:
  py -3 ci/script/check_row_text_width.py
  py -3 ci/script/check_row_text_width.py --update-baseline
  py -3 ci/script/check_row_text_width.py --verbose
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

from baseline_utils import require_baseline

PROJECT_ROOT = Path(__file__).resolve().parents[2]
BASELINE_PATH = PROJECT_ROOT / "ci" / "baseline" / "row_text_width_baseline.txt"
SCAN_ROOTS = ("app/src/main/java/io/zer0/muse/ui",)

# Exención permanente: estos archivos representan**cuerpo del usuario en sí**(texto de transmisión de rebajas de chat),
# La intención del diseño son varias líneas; agregar maxLines truncará el contenido del usuario. Decoración de la interfaz de usuario dentro del archivo (título/etiqueta)
# Han impuesto sus propias restricciones en sus respectivos lugares de llamadas y ya no están sujetos a estas reglas.
EXEMPT_FILES = frozenset(
    {
        "app/src/main/java/io/zer0/muse/ui/markdown/MarkdownText.kt",
    }
)

# Contenedor de filas (abrirá un alcance de fila).
# Nota:`\bRow\s*\(` no hay coincidencia `MuseActionSheetRow(`(`n` con `R` sin límites de palabras entre sí);
# y debe excluir declaraciones y llamadas calificadas——`fun Row(` / `fun XxxRow(` / `.Row(` Ninguna de ellas son llamadas de contenedor.
ROW_OPEN = re.compile(r"(?<!fun )(?<![\w.])(?<!fun \w)Row\s*\(|\bRowScope\b")
# Componente de fila de hojas con relleno horizontal incorporado: cuando se usa como hijo, el ancho lo proporciona la persona que llama y ya no se determina hacia adentro.
LEAF_ROW = re.compile(r"\bMuseActionSheetRow\s*\(")
# Componente que contiene texto.
TEXT_CHILD = re.compile(
    r"\b(?:Text|BasicText|MuseActionSheetRow|MuseListItem|SettingsItemRow|"
    r"SettingsSwitchRow|SettingsItemRow)\s*\("
)
# Texto en el parámetro de ranura: no es un hijo directo de la línea
SLOT_LAMBDA = re.compile(r"[A-Za-z_]\w*\s*=\s*\{\s*(?:Text|BasicText)\s*\(")
# Cualquier llamada Composable (usada para contar el número de hijos seguidos)
CALL_OPEN = re.compile(r"\b([A-Z]\w*)\s*\(")
# Escritura de línea de ancho/límite
WIDTH_SAFE = re.compile(
    r"\.weight\s*\(|\.fillMaxWidth\s*\(|\.fillMaxSize\s*\(|"
    r"\.width\s*\(|\.widthIn\s*\(|\.requiredWidth\s*\("
)
LINES_SAFE = re.compile(r"\bmaxLines\s*=")

# Las palabras clave que no son subclaves (construcciones/modificadores del lenguaje) no participan en el recuento de subclaves
NON_CHILD_CALLS = frozenset(
    {
        "Row", "Column", "Box", "FlowRow", "Spacer", "Surface", "Card", "when", "if",
        "else", "for", "while", "return", "require", "check", "let", "also", "apply",
        "run", "with", "remember", "rememberSaveable", "rememberCoroutineScope",
        "LaunchedEffect", "DisposableEffect", "AnimatedVisibility", "Modifier",
    }
)


def _strip_comment(line: str) -> str:
    idx = line.find("//")
    return line[:idx] if idx >= 0 else line


def _extract_call(lines: list[str], start: int, max_lines: int = 40) -> tuple[str, int]:
    """Extraer una llamada completa (equilibrio de brackets), devolver (texto, Subíndice de última línea)."""
    buf: list[str] = []
    depth = 0
    seen_open = False
    i = start
    while i < len(lines) and i - start < max_lines:
        line = _strip_comment(lines[i])
        buf.append(line)
        for ch in line:
            if ch == "(":
                depth += 1
                seen_open = True
            elif ch == ")":
                depth -= 1
        if seen_open and depth <= 0:
            return "\n".join(buf), i
        i += 1
    return "\n".join(buf), i


def scan_file(path: Path) -> list[tuple[int, str]]:
    """Regresar [(Número de línea, contenido de la fila)], cada elemento es "hay competencia de ancho dentro de la línea pero el texto no está ampliado"."""
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeDecodeError):
        return []

    findings: list[tuple[int, str]] = []
    # Una entrada por cuerpo de fila:[profundidad inicial del cuerpo, Número de niños, Texto no ampliado, Texto ampliado pero con líneas ilimitadas.]
    scopes: list[list] = []
    depth = 0
    pending_open = 0  # `Row(` ha aparecido, su cuerpo `{` Número de niveles de brackets que no aparecen
    pending_row = False
    i = 0

    while i < len(lines):
        raw = lines[i]
        line = _strip_comment(raw)
        opens = line.count("{")
        closes = line.count("}")

        row_here = bool(ROW_OPEN.search(line))
        leaf_here = bool(LEAF_ROW.search(line))
        if row_here and not leaf_here:
            pending_open += line.count("(") - line.count(")")
            pending_row = True
        elif pending_row:
            # Row Llamada entre filas: solo se acumulan las diferencias entre corchetes de las filas siguientes (las diferencias entre la primera fila y la primera fila no se pueden contar repetidamente) `Row(`)
            pending_open += line.count("(") - line.count(")")

        created_scope = False
        if pending_row:
            if "{" in line and pending_open <= 0:
                # profundidad del cuerpo = `{` Profundidad después de eso (el nivel del niño)
                scopes.append([depth + 1, 0, [], []])
                created_scope = True
                pending_row = False
            elif opens > 0 and pending_open <= 0:
                # `Row(...) {` Compañeros:`(` Ya cerrado en nuestro banco.
                scopes.append([depth + 1, 0, [], []])
                created_scope = True
                pending_row = False

        cur = scopes[-1] if scopes else None
        in_row_body = cur is not None and depth == cur[0]

        if in_row_body and not created_scope and CALL_OPEN.search(line):
            m = TEXT_CHILD.search(line)
            is_slot = bool(SLOT_LAMBDA.search(line))
            if m and not is_slot:
                cur[1] += 1  # niño de texto
                call_text, end_line = _extract_call(lines, i)
                has_width = bool(WIDTH_SAFE.search(call_text))
                has_lines = bool(LINES_SAFE.search(call_text))
                if not has_width and not has_lines:
                    # No hay límite de ancho ni de fila: cuando se amplía el tamaño de la fuente, se presionará en una columna y se elevará.
                    cur[2].append((i + 1, raw.strip()))
                elif has_width and not has_lines:
                    # Seleccione líneas anchas pero ilimitadas: si el ancho restante es extremadamente pequeño, aún así se ajustará palabra por palabra
                    cur[3].append((i + 1, raw.strip()))
                # Omita todo el cuerpo de la llamada, pero las llaves dentro del cuerpo deben incluirse en la profundidad; de lo contrario, el alcance posterior quedará desalineado.
                skipped = 0
                for x in lines[i : end_line + 1]:
                    sx = _strip_comment(x)
                    skipped += sx.count("{") - sx.count("}")
                depth += skipped
                while scopes and depth < scopes[-1][0] and scopes[-1] is not cur:
                    scope = scopes.pop()
                    if scope[1] >= 2:
                        findings.extend(scope[2])
                    findings.extend(scope[3])
                i = end_line + 1
                continue
            for name in CALL_OPEN.findall(line):
                if name not in NON_CHILD_CALLS:
                    cur[1] += 1
                    break

        depth += opens - closes
        # El alcance recién creado en esta fila no puede aparecer en esta fila (`{` ya incluido en profundidad)
        if not created_scope:
            while scopes and depth <= scopes[-1][0]:
                scope = scopes.pop()
                # No ampliado: es necesario que haya competencia de ancho dentro de la fila (subelemento ≥2) se considera un riesgo
                if scope[1] >= 2:
                    findings.extend(scope[2])
                # Filas ampliadas pero ilimitadas: cualquier situación se puede comprimir en una columna, se calculan todos los riesgos
                findings.extend(scope[3])
        i += 1

    while scopes:
        scope = scopes.pop()
        if scope[1] >= 2:
            findings.extend(scope[2])
        findings.extend(scope[3])

    findings.sort(key=lambda x: x[0])
    return findings


def scan_all() -> dict[str, list[tuple[int, str]]]:
    result: dict[str, list[tuple[int, str]]] = {}
    for rel_root in SCAN_ROOTS:
        base = PROJECT_ROOT / rel_root
        if not base.is_dir():
            continue
        for kt in sorted(base.rglob("*.kt")):
            rel = kt.relative_to(PROJECT_ROOT).as_posix()
            if rel in EXEMPT_FILES:
                continue
            hits = scan_file(kt)
            if hits:
                result[rel] = hits
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
            baseline[file_part] = int(count_part)
        except ValueError:
            continue
    return baseline


def write_baseline(path: Path, violations: dict[str, list[tuple[int, str]]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    out = [
        "# Row El texto interior no toma una línea de base amplia (sólo desciende pero no sube)",
        "# Formato: <Ruta relativa del archivo>:<Número de transacciones permitidas>",
        "# apretar: py -3 ci/script/check_row_text_width.py --update-baseline",
        "# Descripción: línea base vacía = Tolerancia cero para todas las posiciones. texto de usuario (ui/markdown/MarkdownText.kt) están permanentemente exentos por el script EXEMPT_FILES,",
        "#       decoración de tarjetas (DataCardRenderer / RichContentCard El título y la etiqueta de idioma) han sido restringidos en el sitio de la llamada.",
        "",
    ]
    for file, hits in sorted(violations.items()):
        out.append(f"{file}:{len(hits)}")
    path.write_text("\n".join(out) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Row El texto interior no está ampliado ni marcado.")
    parser.add_argument("--baseline", type=Path, default=BASELINE_PATH)
    parser.add_argument("--update-baseline", action="store_true")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    violations = scan_all()
    total = sum(len(v) for v in violations.values())

    if args.update_baseline:
        write_baseline(args.baseline, violations)
        print(f"[row-text-width] baseline actualizado: {args.baseline}")
        print(f"  total {total} lugar, distribución {len(violations)} archivos")
        return 0

    print(f"[row-text-width] Escanear ui/: {total} El texto en Row no está ampliado ni distribuido. {len(violations)} archivos")
    if args.verbose:
        for file, hits in sorted(violations.items()):
            print(f"  {file}")
            for ln, text in hits:
                print(f"    L{ln}: {text[:110]}")

    if not require_baseline(args.baseline, "row-text-width"):
        return 2

    baseline = load_baseline(args.baseline)
    failures: list[str] = []
    improved: list[str] = []
    for file, hits in sorted(violations.items()):
        current = len(hits)
        allowed = baseline.get(file, 0)
        if current > allowed:
            failures.append(f"  {file}: {current} en (baseline {allowed}, Nuevo {current - allowed})")
        elif current < allowed:
            improved.append(f"  {file}: {allowed} → {current}")
    for file in baseline:
        if file not in violations:
            improved.append(f"  {file}: Borrado")

    if improved:
        print("[row-text-width] Hay mejora, se recomienda ajustar --update-baseline:")
        for line in improved:
            print(line)

    if failures:
        print("[row-text-width] FAILED — Añadir "Row "El texto interior no está ampliado":")
        for line in failures:
            print(line)
        print(
            "como solucionarlo: Agregar al subelemento que contiene el texto. Modifier.weight(1f)(tome el ancho restante) o Modifier.fillMaxWidth();"
            "Maquillar si es necesario maxLines + overflow",
        )
        return 1

    print(f"[row-text-width] PASS — Sin nuevas incorporaciones (stock {total} (exento según línea base)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
