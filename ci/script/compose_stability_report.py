#!/usr/bin/env python3
"""
compose_stability_report.py — Reúna los informes de estabilidad del compilador Compose en una lista de verificación verificable.

## por qué es necesario

`app/build.gradle.kts` abierto `composeCompiler { reportsDestination / metricsDestination }`,
El producto es `app/build/compose_reports/` Los siguientes tres archivos de texto son de varios cientos de KB. Hay tres problemas con la lectura directa:
Los informes se enumeran por parámetros componibles e inestables.**sin `unstable` prefijo**(no escribí `stable` Es simplemente inestable),
y "lo que cae en el diálogo/Se requiere filtrado manual en el enlace de la tarjeta.

Lo que realmente es necesario responder al realizar la rectificación del rendimiento son dos preguntas:
  1. ¿Cuántos elementos componibles no se pueden omitir (condiciones de entrada para la optimización de la reorganización)?
  2. dialogo/¿Qué elementos componibles tienen parámetros inestables en el enlace de la tarjeta?

## Uso

    # Ejecute primero una compilación para generar el informe.
    .\\gradlew.bat :app:compileDebugKotlin --rerun-tasks
    python ci/script/compose_stability_report.py

Salida:`build/perf/compose_report_summary.txt`e imprima un resumen en la salida estándar.

## Calibre

- `restartable skippable ...` = Se puede omitir;`restartable ...`(no saltable)= No se puede omitir.
- La línea de parámetros solo tiene `stable ` Los que tienen el prefijo se consideran estables; el resto se considera inestable según el calibre del compilador.
- "Diálogo/enlace de tarjeta= Accesos al nombre del paquete o al nombre de la función [FOCUS_PACKAGES] / [FOCUS_NAMES], se puede ampliar según sea necesario.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
REPORT = PROJECT_ROOT / "app" / "build" / "compose_reports" / "app-composables.txt"
CLASSES = PROJECT_ROOT / "app" / "build" / "compose_reports" / "app-classes.txt"
OUT = PROJECT_ROOT / "build" / "perf" / "compose_report_summary.txt"

FOCUS_PACKAGES = (
    "ui.MessageBubble",
    "ui.markdown",
    "ui.chat",
    "ui.InputBar",
    "ui.ChatScreenComponents",
    "ui.common",
)
FOCUS_NAMES = (
    "MessageBubble",
    "MarkdownText",
    "RichContentCard",
    "CardRuntime",
    "ChatScreen",
    "InputBar",
    "CodeBlockView",
    "TableView",
    "MoodSection",
    "MuseCard",
)


def parse_entries(text: str) -> list[tuple[str, list[str]]]:
    """Cortar el informe en [(fila de encabezado, [Línea de parámetros...]), ...]."""
    entries: list[tuple[str, list[str]]] = []
    head: str | None = None
    params: list[str] = []
    for line in text.splitlines():
        if line.startswith("restartable"):
            if head is not None:
                entries.append((head, params))
            head, params = line, []
        elif head is not None:
            if line.startswith("  "):
                params.append(line.strip())
            elif not line.strip():
                entries.append((head, params))
                head, params = None, []
    if head is not None:
        entries.append((head, params))
    return entries


def main() -> None:
    if not REPORT.exists():
        print(f"[compose-stability] El informe no existe:{REPORT}", file=sys.stderr)
        print(
            "[compose-stability] Ejecutar una vez:.\\gradlew.bat :app:compileDebugKotlin --rerun-tasks",
            file=sys.stderr,
        )
        sys.exit(1)

    entries = parse_entries(REPORT.read_text(encoding="utf-8", errors="replace"))
    total = len(entries)
    if total < 300:
        print(
            f"[compose-stability] Advertencia: el informe solo analiza {total} un componible,"
            "Lo más probable es que sea producto de una compilación incremental (esta vez solo se recompilan archivos)."
            "Para obtener una lista completa, ejecute --rerun-tasks.",
            file=sys.stderr,
        )
    skippable = [entry for entry in entries if "skippable" in entry[0].split("scheme")[0]]
    non_skippable = [entry for entry in entries if entry not in skippable]

    unstable: dict[str, list[str]] = {}
    for head, params in non_skippable:
        match = re.search(r"fun ([\w.$]+)\(", head)
        fn = match.group(1) if match else head
        bad = [param for param in params if not param.startswith("stable")]
        if bad:
            unstable[fn] = bad

    lines: list[str] = [
        f"composables total = {total}",
        f"skippable = {len(skippable)}  ({len(skippable) * 100 // max(total, 1)}%)",
        f"NOT skippable = {len(non_skippable)}",
        "",
        "=== No se puede omitir y entra dentro del diálogo./tarjeta/En enlaces de componentes públicos (clasificados por número de parámetros inestables)===",
    ]
    rows: list[tuple[int, str, list[str]]] = []
    for fn, bad in unstable.items():
        simple = fn.split(".")[-1]
        if any(key in fn for key in FOCUS_PACKAGES) or any(name in simple for name in FOCUS_NAMES):
            rows.append((len(bad), fn, bad))
    rows.sort(reverse=True)
    for count, fn, bad in rows:
        lines.append(f"- {fn}  [{count} parámetros inestables]")
        for param in bad:
            lines.append(f"    · {param}")
    lines.append("")
    lines.append(f"(Diálogo/Número total de tarjetas en el enlace {len(rows)} no se puede omitir componible)")

    lines.extend(["", "=== Clases consideradas inestables (eliminación de duplicaciones, top 60)==="])
    if CLASSES.exists():
        names = re.findall(
            r"unstable class ([\w.$]+)",
            CLASSES.read_text(encoding="utf-8", errors="replace"),
        )
        seen: list[str] = []
        for name in names:
            if name not in seen:
                seen.append(name)
        lines.append(f"unstable class Después de la deduplicación = {len(seen)}")
        lines.extend(f"- {name}" for name in seen[:60])
    else:
        lines.append("(app-classes.txt desaparecido)")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(lines), encoding="utf-8")
    print("\n".join(lines[:24]))
    print(f"\n[full] {OUT}")


if __name__ == "__main__":
    main()
