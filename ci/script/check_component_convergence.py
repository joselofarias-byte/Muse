#!/usr/bin/env python3
"""
check_component_convergence.py — CMP-11 Barandilla de convergencia de componentes.

Escanear app/src/main/java/io/zer0/muse/ui/** Archivos Kotlin en Material3
Referencias directas a controles interactivos, archivos y ocurrencias estadísticas y líneas de base.
ci/component_convergence_baseline.json Comparación: si se agrega recientemente o es superior a la línea de base, fallará (solo disminuye pero no aumenta).

¿Por qué parar?:
  Muse El componente interactivo unificado está en ui/common (MuseDialog / MuseFormDialog /
  MuseBottomSheet / MuseSelectionSheet / MuseTextField Espera, nos vemos
  guia-biblioteca-ui.md). Si la página comercial utiliza directamente el Botón/Tarjeta/IconButton nativo M3, se volverá a
  Introducir esquinas redondeadas, espaciado, tamaño táctil y comportamiento de ventanas emergentes inconsistentes; este guión es a la vez una medida de convergencia (el stock sólo disminuye),
  También es la puerta CI de "los nuevos archivos UI no deben usar controles M3 directamente".

Calibre de identificación (cuenta 1 por citación):
  1. `import androidx.compose.material3.<controlar>`(Compatible con `as` Método de escritura de alias)
  2. `import androidx.compose.material3.*` Importación con comodines: presione 1 vez para contar el archivo mientras ingresa
     "Colección de archivos de importación comodín", esta colección sólo se puede reducir pero no aumentar (la importación de comodines invalidará el recuento símbolo por símbolo y las nuevas adiciones
     falla, evita usar `.*` Omitir estadísticas)
  3. Referencias totalmente calificadas en el código. `androidx.compose.material3.<controlar>`, como
     `androidx.compose.material3.Button(...)`(Varias apariciones de la misma línea se contarán como múltiples veces)
  Sólo se escanean las tres citas directas anteriores; las menciones en los comentarios no se cuentan (lógica léxica despojada y
  ci/script/check_hardcoded_cjk.py de strip_comments sigue siendo consistente). Archivo de interfaz de usuario en blanco /
  Los archivos sin resultados no se ingresan en la línea de base.

lista de control: Ver SÍMBOLOS, conjunto mínimo que cubre al menos los requisitos del plan Button / OutlinedButton /
  TextButton / IconButton / Card / FilterChip / CircularProgressIndicator /
  DropdownMenu, también incluye AlertDialog / ModalBottomSheet / clase de entrada / control de clase de selección.
  La instantánea del inventario se escribe en el JSON de línea base y, una vez que el inventario cambia, la línea base se verá obligada a revisarse nuevamente.

mecanismo de referencia:
  - Comparación predeterminada ci/component_convergence_baseline.json: el número de apariciones por archivo no debe ser mayor que la línea base
    (Los archivos nuevos fuera de la línea base se consideran 0) y el número total es el mismo que el conjunto de importación con comodines.
  - Falta el archivo de referencia = falló(fail closed),Evite eliminar líneas base para evitar el control de la puerta.
  - --update-baseline Reescribir la línea de base; el valor predeterminado es denegar la escritura de entradas "ascendentes", lo que requiere --allow-increase
    Simplemente fuerce la escritura. Después de la limpieza de la migración, ejecute --update-baseline para ajustarlo y que el control de acceso sea más estricto con la migración de CMP.

Limitaciones conocidas:
  - No se reconoce el uso indirecto de controles M3 a través de funciones contenedoras, variables de alias o reflexión.
  - Solo cubre la aplicación ui/**;material3/,common/ La implementación del sistema de diseño de otros módulos no está incluida en esta lista.

Uso:
  py -3 ci/script/check_component_convergence.py
  py -3 ci/script/check_component_convergence.py --baseline <path>
  py -3 ci/script/check_component_convergence.py --update-baseline
  py -3 ci/script/check_component_convergence.py --allow-increase --update-baseline
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
UI_REL = Path("app") / "src" / "main" / "java" / "io" / "zer0" / "muse" / "ui"
BASELINE_REL = Path("ci") / "component_convergence_baseline.json"

# CMP-11 Controles interactivos M3 cubiertos (incluidas las variantes; cambiar esta lista requiere --update-baseline para volver a establecer la línea de base)
SYMBOLS = [
    "AlertDialog",
    "AssistChip",
    "BasicAlertDialog",
    "Button",
    "Card",
    "Checkbox",
    "CircularProgressIndicator",
    "DropdownMenu",
    "DropdownMenuItem",
    "ElevatedButton",
    "ElevatedCard",
    "FilterChip",
    "FloatingActionButton",
    "IconButton",
    "InputChip",
    "LinearProgressIndicator",
    "ModalBottomSheet",
    "OutlinedButton",
    "OutlinedCard",
    "OutlinedTextField",
    "RadioButton",
    "Slider",
    "SuggestionChip",
    "Switch",
    "TextButton",
    "TextField",
]
SYMBOL_SET = frozenset(SYMBOLS)

M3_IMPORT_RE = re.compile(
    r"^\s*import\s+androidx\.compose\.material3\.([A-Za-z_][A-Za-z0-9_]*|\*)"
    r"(?:\s+as\s+[A-Za-z_][A-Za-z0-9_]*)?\s*;?\s*$"
)
M3_FQ_RE = re.compile(r"androidx\.compose\.material3\.([A-Za-z_][A-Za-z0-9_]*)")


@dataclass
class FileScan:
    """Estadísticas de referencia directa para archivos de UI individuales.file Una ruta posix relativa a la raíz del proyecto."""

    file: str
    occurrences: int = 0
    wildcard: int = 0
    symbols: Counter[str] = field(default_factory=Counter)


def strip_comments(line: str, in_block_comment: bool) -> tuple[str, bool]:
    """bloque de sacrificio/Comentarios de línea,Los literales de cadena se dejan intactos(No "https://x" El // interior se utiliza como comentario.).

    El mismo conjunto de lógica que strip_comments de ci/script/check_hardcoded_cjk.py.
    """
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


def scan_file(path: Path, rel: str) -> FileScan:
    """Cuente las referencias directas de M3 en un archivo .kt (import / importación comodín/referencia totalmente calificada)."""
    scan = FileScan(file=rel)
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeDecodeError) as e:
        print(f"WARN: No puedo leer {path}: {e}", file=sys.stderr)
        return scan
    in_block = False
    for line in lines:
        code, in_block = strip_comments(line, in_block)
        if not code.strip():
            continue
        m = M3_IMPORT_RE.match(code)
        if m:
            name = m.group(1)
            if name == "*":
                scan.wildcard += 1
                scan.occurrences += 1
            elif name in SYMBOL_SET:
                scan.occurrences += 1
                scan.symbols[name] += 1
            continue  # import fila procesada,No más partidos totalmente clasificados
        for hit in M3_FQ_RE.finditer(code):
            name = hit.group(1)
            if name in SYMBOL_SET:
                scan.occurrences += 1
                scan.symbols[name] += 1
    return scan


def scan_ui(root: Path) -> list[FileScan]:
    """Escanear root/ui/** Descargue todos los archivos .kt y devuelva archivos con referencias directas (ordenados por ruta)."""
    ui_dir = root / UI_REL
    if not ui_dir.is_dir():
        raise FileNotFoundError(f"UI El directorio no existe: {ui_dir}")
    results: list[FileScan] = []
    for kt in sorted(ui_dir.rglob("*.kt")):
        scan = scan_file(kt, kt.relative_to(root).as_posix())
        if scan.occurrences:
            results.append(scan)
    return results


def load_baseline(path: Path) -> dict:
    """Leer línea de base JSON;No existe y regresa vacío. dict(Equivalente a "solo informar sin interceptación")."""
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def evaluate(current: list[FileScan], baseline: dict) -> tuple[list[str], list[str]]:
    """Comparar la exploración actual con la línea de base,Regresar (failures, improvements) Dos grupos leen el mensaje."""
    failures: list[str] = []
    improvements: list[str] = []

    base_symbols = baseline.get("symbols")
    if base_symbols is not None and list(base_symbols) != SYMBOLS:
        failures.append(
            "  La instantánea de los símbolos de referencia no es coherente con los SÍMBOLOS del script —— La lista de controles ha cambiado.,"
            "Revise el impacto y utilice --update-baseline para restablecer la línea de base."
        )

    base_files = {k: int(v) for k, v in (baseline.get("files") or {}).items()}
    base_wild = {k: int(v) for k, v in (baseline.get("wildcard_imports") or {}).items()}

    for scan in current:
        allowed = base_files.get(scan.file, 0)
        if scan.occurrences > allowed:
            kind = "Agregar nuevo archivo" if scan.file not in base_files else "por encima de la línea de base"
            failures.append(
                f"  {scan.file}: actual {scan.occurrences} en > línea de base {allowed} en ({kind}) "
                f"{dict(scan.symbols)}"
            )
        elif scan.occurrences < allowed:
            improvements.append(f"  {scan.file}: {allowed} → {scan.occurrences}")
        w_allowed = base_wild.get(scan.file, 0)
        if scan.wildcard > w_allowed:
            failures.append(
                f"  {scan.file}: importación comodín androidx.compose.material3.* actual {scan.wildcard} en "
                f"> línea de base {w_allowed} en —— Deshabilite el uso de importaciones con comodines para omitir las estadísticas por control"
            )
        elif scan.wildcard < w_allowed:
            improvements.append(f"  {scan.file}: importación comodín {w_allowed} → {scan.wildcard}")

    for gone in sorted(set(base_files) - {s.file for s in current}):
        improvements.append(f"  {gone}: Borrado")

    cur_total = sum(s.occurrences for s in current)
    base_total = int((baseline.get("totals") or {}).get("occurrences", 0))
    if cur_total > base_total:
        failures.append(f"  Total de ocurrencias: actual {cur_total} > línea de base {base_total}")

    return failures, improvements


def update_baseline(path: Path, current: list[FileScan], allow_increase: bool) -> int:
    """Sobrescribir la línea de base con los resultados del análisis actual;Denegar escritura de entradas ascendentes por defecto(CMP-11 Solo cayendo pero no subiendo)."""
    old = load_baseline(path)
    old_files = {k: int(v) for k, v in (old.get("files") or {}).items()}
    old_wild = {k: int(v) for k, v in (old.get("wildcard_imports") or {}).items()}
    increases = [
        f"  {s.file}: {old_files.get(s.file, 0)} → {s.occurrences}"
        for s in current
        if s.occurrences > old_files.get(s.file, 0)
    ]
    increases += [
        f"  {s.file}: importación comodín {old_wild.get(s.file, 0)} → {s.wildcard}"
        for s in current
        if s.wildcard > old_wild.get(s.file, 0)
    ]
    if increases and old and not allow_increase:
        print("[component-convergence] Negarse a responder: Las siguientes entradas están por encima de la línea base existente(Solo cayendo pero no subiendo)")
        for line in increases:
            print(line)
        print("Primero limpie estas referencias mediante la migración CMP;Si realmente necesita conservarlo, agregue explícitamente --allow-increase y explique el motivo.")
        return 1

    total = sum(s.occurrences for s in current)
    wild = {s.file: s.wildcard for s in current if s.wildcard}
    payload = {
        "note": (
            "CMP-11 Los componentes convergen a la línea de base. solo se permite descender: Los archivos nuevos fuera de la línea base o los archivos que aparecen con más frecuencia que este archivo fallarán."
            "Después de borrar el inventario, ejecute py -3 ci/script/check_component_convergence.py --update-baseline para ajustar"
            "(Denegar escritura de valor ascendente por defecto,necesidad --allow-increase)."
        ),
        "ui_root": UI_REL.as_posix(),
        "symbols": SYMBOLS,
        "totals": {
            "files": len(current),
            "occurrences": total,
            "wildcard_files": len(wild),
        },
        "wildcard_imports": wild,
        "files": {s.file: s.occurrences for s in current},
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(
        f"[component-convergence] Línea base actualizada: {path}"
        f" ({len(current)} archivos / {total} en/importación comodín {len(wild)} archivos)"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="CMP-11 M3 Comprobación de barandilla de convergencia de componentes")
    parser.add_argument("--root", type=Path, default=PROJECT_ROOT, help="raíz del proyecto(Pruebas disponibles fixture)")
    parser.add_argument("--baseline", type=Path, default=None, help="Ruta JSON de referencia")
    parser.add_argument("--update-baseline", action="store_true", help="Sobrescribir la línea de base con los resultados del análisis actual")
    parser.add_argument("--allow-increase", action="store_true", help="Permitir la escritura de valores crecientes al actualizar la línea base")
    parser.add_argument("--verbose", action="store_true", help="Imprimir detalles de cada archivo")
    args = parser.parse_args()
    baseline_path = args.baseline or (args.root / BASELINE_REL)

    try:
        current = scan_ui(args.root)
    except FileNotFoundError as e:
        print(f"[component-convergence] Error de escaneo: {e}")
        return 1

    total = sum(s.occurrences for s in current)
    wild_files = [s for s in current if s.wildcard]
    print(
        f"[component-convergence] Escanear {UI_REL.as_posix()}: "
        f"{len(current)} archivos / {total} Referencia directa al control interactivo M3"
        f" (importación comodín {len(wild_files)} archivos), lista de control {len(SYMBOLS)} un"
    )
    if args.verbose:
        for s in current:
            print(f"  {s.occurrences:3d}  {s.file}  {dict(s.symbols)}")

    if args.update_baseline:
        return update_baseline(baseline_path, current, args.allow_increase)

    baseline = load_baseline(baseline_path)
    if not baseline:
        # Línea de base faltante = Control de acceso no válido,debe fallar(La eliminación del archivo de referencia no se puede utilizar como una omisión.)
        print(f"[component-convergence] FAILED — Línea base no encontrada {baseline_path};"
              f"generar: --update-baseline")
        return 1

    base_files = baseline.get("files") or {}
    base_wild = baseline.get("wildcard_imports") or {}
    base_total = int((baseline.get("totals") or {}).get("occurrences", 0))
    print(
        f"[component-convergence] actual/línea de base: Archivo {len(current)}/{len(base_files)}, "
        f"aparecer {total}/{base_total}, Archivo de importación comodín {len(wild_files)}/{len(base_wild)}"
    )

    failures, improvements = evaluate(current, baseline)
    if improvements:
        print("[component-convergence] hay mejora,Se recomienda ajustar --update-baseline:")
        for line in improvements:
            print(line)
    if failures:
        print("[component-convergence] FAILED — Nuevo/Por encima de la línea base, use los controles M3 directamente:")
        for line in failures:
            print(line)
        print("como solucionarlo: Priorizar la reutilización de componentes unificados de ui/common(MuseDialog/MuseBottomSheet/MuseSelectionSheet/"
              "MuseTextField espera);Si realmente necesita agregar un nuevo inventario, primero borre la misma cantidad de referencias.,En lugar de elevar la línea de base.")
        return 1
    print(f"[component-convergence] PASS — no superó la línea de base (Valores {len(base_files)} archivos / {base_total} en,Solo cayendo pero no subiendo)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
