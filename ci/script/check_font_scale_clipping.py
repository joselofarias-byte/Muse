#!/usr/bin/env python3
"""
check_font_scale_clipping.py — A11Y-03 Barandilla con corte tipo letra grande.

Escanee el código fuente de la interfaz de usuario de app/src/main para `.height(<N>.dp)` de**altura fija**Uso: cuando
N >= MIN_RISK_DP(32) Y hay contenido de texto renderizable en el contenedor donde se encuentra el Modificador (Text /
BasicText / stringResource / label / title / subtitle etc.) y no es un marcador de posición de medios
(Image / AsyncImage / Canvas / Icon Contenedor) se cuenta como 1 "riesgo de recorte de fuente grande".

¿Por qué parar?:
  Altura fija cuando se amplían las fuentes del sistema(1.3x / 1.5x,Android Accesibilidad "Fuente grande")no irá acompañado de texto
  crecimiento,El texto está recortado o se superpone a elementos adyacentes.;La forma correcta de escribirlo es `heightIn(min = N.dp)`
  —— La altura mínima se mantiene sin cambios.,El contenedor crece a medida que se amplía el texto. Hay una gran cantidad de proyectos. heightIn(min=) Uso
  (MuseChip / MuseCapsuleTab / ChatScreen espera)Se puede utilizar como ejemplo.

Identificar calibre (contado 1 vez por cada ubicación):
  1. en forma de `.height(48.dp)` llamada modificadora;`heightIn(` no cuentes,`Spacer` No importa lo que pase
     (Gap no lleva texto,No hay nada de malo en aumentar el tamaño de la fuente).
  2. N >= MIN_RISK_DP;La altura fija más pequeña es el icono/puntos/línea divisoria/cápsula,No se requiere ningún recorte de texto.
  3. reglas B(Igual que fuente grande/Alto riesgo ilimitado):`heightIn(min = …)` **No max** y dentro de las 12 líneas siguientes
     aparecer `.fillMaxSize()` —— El niño solicitará la altura máxima del padre.,en contenedor principal ilimitado(Ranura de barra superior,Scaffold)
     llenará toda la pantalla.1.3x Medición real guiada:Como resultado, la isla en la barra superior del chat ha crecido hasta 1608px,El contenido de la página se elimina de la pantalla.
     con máximo heightIn(min=…, max=…) No en esta lista(Ya limitado al límite superior.).
  4. Mirando dentro del cuerpo del contenedor, la línea TEXT_FORWARD debe tener un marcado de texto (de lo contrario, es solo el texto del componente adyacente);
     Si la etiqueta multimedia aparece en "antes de la línea MEDIA_LOOKBACK + después de la línea MEDIA_FORWARD", presione la etiqueta multimedia
     Procesamiento (el título en el encabezado de la imagen pertenece a la superposición, control deslizante/La altura del contenedor del gráfico está determinada por el lienzo o la imagen.
     decidir,No aumenta con el tamaño de la fuente).
  Las menciones en comentarios no cuentan (strip_comments Mismo conjunto que check_component_convergence.py).

mecanismo de referencia:
  - Comparación predeterminada ci/font_scale_clipping_baseline.json: el número archivo por archivo no debe ser mayor que la línea base,
    El total solo disminuirá pero no aumentará.;Falta el archivo de referencia = falló(fail closed).
  - --update-baseline Reescribir la línea base;Denegar escritura de entradas ascendentes por defecto,Requiere --allow-increase.
  - Después de eliminar un riesgo, ejecute --update-baseline para ajustarlo,El control de acceso se ha vuelto más estricto con la renovación.

Limitaciones conocidas:
  - Heurística léxica pura,no reconocido `LocalDensity` Convertir o personalizar el recorte del diseño
  - No cubre la implementación del sistema de diseño y diseño XML de material3/módulo común
  - `.size(N.dp)` / `.requiredHeight(N.dp)` No incluido en estadísticas (el primero es más común en íconos y avatares)

Uso:
  py -3 ci/script/check_font_scale_clipping.py
  py -3 ci/script/check_font_scale_clipping.py --verbose
  py -3 ci/script/check_font_scale_clipping.py --update-baseline [--allow-increase]
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
BASELINE_REL = Path("ci") / "font_scale_clipping_baseline.json"
SCAN_ROOTS = [
    Path("app") / "src" / "main" / "java" / "io" / "zer0" / "muse" / "ui",
    Path("app") / "src" / "main" / "java" / "io" / "zer0" / "muse" / "automation" / "ui",
]
MIN_RISK_DP = 32
TEXT_FORWARD = 12
MEDIA_FORWARD = 40
MEDIA_LOOKBACK = 12

HEIGHT_RE = re.compile(r"\.height\(\s*([0-9]+(?:\.[0-9]+)?)\.dp\s*\)")
# Regla B: Sólo partido**No** max de heightIn(min=…);Con max, significa que el autor ha limitado el límite superior
HEIGHTIN_MIN_ONLY_RE = re.compile(r"\.heightIn\(\s*min\s*=\s*[0-9.]+(?:\.dp)?\s*\)")
TEXT_RE = re.compile(
    r"\bText\(|BasicText|stringResource|label\s*=|title\s*=|subtitle\s*=|placeholder\s*="
)
MEDIA_RE = re.compile(
    r"Image\(|AsyncImage|SmartImage|SubcomposeAsyncImage|Canvas\(|Icon\(|VideoView|WebView"
)


@dataclass
class FileScan:
    """Se corrigieron estadísticas de alto riesgo para archivos de interfaz de usuario individuales.file Una ruta posix relativa a la raíz del proyecto."""

    file: str
    occurrences: int = 0
    unbounded: int = 0
    fixed_heights: int = 0
    details: list[str] = field(default_factory=list)
    unbounded_details: list[str] = field(default_factory=list)


def strip_comments(line: str, in_block_comment: bool) -> tuple[str, bool]:
    """bloque de sacrificio/Comentarios de línea,Los literales de cadena se dejan intactos (de conformidad con check_component_convergence.py)."""
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
            break
        out.append(ch)
        i += 1
    return "".join(out), in_block_comment


def scan_file(path: Path, rel: str) -> FileScan:
    """Cuente el número de "alturas fijas para transportar texto" en un archivo .kt."""
    scan = FileScan(file=rel)
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeDecodeError) as e:
        print(f"WARN: No puedo leer {path}: {e}", file=sys.stderr)
        return scan

    # Quita los comentarios primero,Estadísticas de nuevo;El juicio contextual también se basa en el texto después de eliminar los comentarios.,Evite comentarios
    # 「Text(" o "Image("Error de juicio.
    code_lines: list[str] = []
    in_block = False
    for line in lines:
        code, in_block = strip_comments(line, in_block)
        code_lines.append(code)

    for i, code in enumerate(code_lines):
        m = HEIGHT_RE.search(code)
        if not m:
            continue
        scan.fixed_heights += 1
        value = float(m.group(1))
        if value < MIN_RISK_DP or "Spacer" in code:
            continue
        # linea delgada/tira separadora(width 0.5~2dp)No lleva texto,Altamente fijo e inofensivo.
        nearby = "\n".join(code_lines[max(0, i - 3) : i + 1])
        if re.search(r"\.width\(\s*(?:0\.5|1|2)\.dp\s*\)", nearby):
            continue
        # El texto debe estar dentro del cuerpo del contenedor inmediato.(Las primeras líneas TEXT_FORWARD),De lo contrario, es sólo el texto del componente adyacente.;
        # Ventana multimedia más amplia(antes de las líneas MEDIA_LOOKBACK + después de las líneas MEDIA_FORWARD):Pistas deslizantes, gráficos
        # La altura del contenedor está determinada por Canvas/Imagen.,La máscara de título en la imagen del encabezado también es una superposición multimedia.,No aumenta según el tamaño de fuente.
        body = "\n".join(code_lines[i : i + TEXT_FORWARD])
        if not TEXT_RE.search(body):
            continue
        media = "\n".join(code_lines[max(0, i - MEDIA_LOOKBACK) : i + MEDIA_FORWARD])
        if MEDIA_RE.search(media):
            continue
        scan.occurrences += 1
        scan.details.append(f"L{i + 1}: height({m.group(1)}.dp)")

    # reglas B:heightIn(min=…) Límite infinito + niños fillMaxSize()
    for i, code in enumerate(code_lines):
        if not HEIGHTIN_MIN_ONLY_RE.search(code):
            continue
        if ".fillMaxSize()" in "\n".join(code_lines[i + 1 : i + 13]):
            scan.unbounded += 1
            scan.unbounded_details.append(f"L{i + 1}: heightIn(min=…) Sin máximo + niños fillMaxSize()")

    return scan


def scan_roots(root: Path) -> list[FileScan]:
    """Escanee todos los directorios raíz de la interfaz de usuario,Regresar**Todo con altura fija**archivos (ordenados por ruta).

    Devolver el conjunto completo en lugar de solo el conjunto de resultados,Para que el informe pueda dar con veracidad "el número total de alturas fijas / el número de riesgos que lleva el texto";
    Usos solo de referencia occurrences > 0 subconjunto.
    """
    results: list[FileScan] = []
    seen: set[str] = set()
    for rel_root in SCAN_ROOTS:
        base = root / rel_root
        if not base.is_dir():
            continue
        for kt in sorted(base.rglob("*.kt")):
            rel = kt.relative_to(root).as_posix()
            if rel in seen:
                continue
            seen.add(rel)
            scan = scan_file(kt, rel)
            if scan.fixed_heights or scan.unbounded:
                results.append(scan)
    if not seen:
        raise FileNotFoundError(
            "UI El directorio del código fuente no existe.: " + ", ".join((root / r).as_posix() for r in SCAN_ROOTS)
        )
    results.sort(key=lambda s: s.file)
    return results


def load_baseline(path: Path) -> dict:
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def evaluate(current: list[FileScan], baseline: dict) -> tuple[list[str], list[str]]:
    """Comparar la exploración actual con la línea de base,Regresar (failures, improvements)."""
    failures: list[str] = []
    improvements: list[str] = []

    if baseline.get("min_risk_dp") is not None and int(baseline["min_risk_dp"]) != MIN_RISK_DP:
        failures.append(
            f"  línea de base min_risk_dp={baseline['min_risk_dp']} con guión {MIN_RISK_DP} inconsistente ——"
            f"El calibre de identificación ha cambiado.,Revise y utilice --update-baseline para volver a establecer la línea de base"
        )

    base_files = {k: int(v) for k, v in (baseline.get("files") or {}).items()}
    base_unbounded = {k: int(v) for k, v in (baseline.get("unbounded") or {}).items()}

    for scan in current:
        u_allowed = base_unbounded.get(scan.file, 0)
        if scan.unbounded > u_allowed:
            kind = "Agregar nuevo archivo" if scan.file not in base_unbounded else "por encima de la línea de base"
            failures.append(
                f"  {scan.file}: Altura ilimitada en la actualidad {scan.unbounded} en > línea de base {u_allowed} en "
                f"({kind}) {scan.unbounded_details}"
            )
        elif scan.unbounded < u_allowed:
            improvements.append(f"  {scan.file}: ilimitado heightIn {u_allowed} → {scan.unbounded}")

        allowed = base_files.get(scan.file, 0)
        if scan.occurrences > allowed:
            kind = "Agregar nuevo archivo" if scan.file not in base_files else "por encima de la línea de base"
            failures.append(
                f"  {scan.file}: actual {scan.occurrences} en > línea de base {allowed} en ({kind}) "
                f"{scan.details}"
            )
        elif scan.occurrences < allowed:
            improvements.append(f"  {scan.file}: {allowed} → {scan.occurrences}")

    for gone in sorted(set(base_files) - {s.file for s in current}):
        improvements.append(f"  {gone}: Borrado")
    for gone in sorted(set(base_unbounded) - {s.file for s in current}):
        improvements.append(f"  {gone}: Altura ilimitadaEn borrado")

    cur_unbounded = sum(s.unbounded for s in current)
    base_unbounded_total = int((baseline.get("totals") or {}).get("unbounded", 0))
    if cur_unbounded > base_unbounded_total:
        failures.append(f"  Altura ilimitadaEn número total de posiciones: actual {cur_unbounded} > línea de base {base_unbounded_total}")

    cur_total = sum(s.occurrences for s in current)
    base_total = int((baseline.get("totals") or {}).get("occurrences", 0))
    if cur_total > base_total:
        failures.append(f"  Número total de casos: actual {cur_total} > línea de base {base_total}")

    return failures, improvements


def update_baseline(path: Path, current: list[FileScan], allow_increase: bool) -> int:
    """Sobrescribir la línea de base con los resultados del análisis actual;De forma predeterminada, se deniega la escritura de entradas ascendentes (solo descendentes pero no ascendentes)."""
    old = load_baseline(path)
    old_files = {k: int(v) for k, v in (old.get("files") or {}).items()}
    old_unbounded = {k: int(v) for k, v in (old.get("unbounded") or {}).items()}
    increases = [
        f"  {s.file}: {old_files.get(s.file, 0)} → {s.occurrences}"
        for s in current
        if s.occurrences > old_files.get(s.file, 0)
    ]
    increases += [
        f"  {s.file}: ilimitado heightIn {old_unbounded.get(s.file, 0)} → {s.unbounded}"
        for s in current
        if s.unbounded > old_unbounded.get(s.file, 0)
    ]
    if increases and old and not allow_increase:
        print("[font-scale-clipping] Negarse a responder: Las siguientes entradas están por encima de la línea base existente(Solo cayendo pero no subiendo)")
        for line in increases:
            print(line)
        print("Primero cambie la altura fija a heightIn(min = …) o quitar la altura;Si realmente necesita conservarlo, agregue explícitamente --allow-increase y explique el motivo.")
        return 1

    total = sum(s.occurrences for s in current)
    payload = {
        "note": (
            "A11Y-03 Las fuentes grandes recortan la línea base. solo se permite descender: Los archivos nuevos fuera de la línea base o los archivos con un número mayor que este archivo fallarán."
            "Después de la transformación, ejecute py -3 ci/script/check_font_scale_clipping.py --update-baseline para ajustar"
            "(Denegar escritura de valor ascendente por defecto,necesidad --allow-increase)."
        ),
        "scan_roots": [r.as_posix() for r in SCAN_ROOTS],
        "min_risk_dp": MIN_RISK_DP,
        "text_forward": TEXT_FORWARD,
        "media_forward": MEDIA_FORWARD,
        "media_lookback": MEDIA_LOOKBACK,
        "totals": {
            "files": len(current),
            "occurrences": total,
            "unbounded": sum(s.unbounded for s in current),
        },
        "files": {s.file: s.occurrences for s in current},
        "unbounded": {s.file: s.unbounded for s in current if s.unbounded},
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        f"[font-scale-clipping] Línea base actualizada: {path} "
        f"({len(current)} archivos / {total} Mantener texto a una altura fija)"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="A11Y-03 Inspección de barandillas de fuente grande")
    parser.add_argument("--root", type=Path, default=PROJECT_ROOT, help="raíz del proyecto(Pruebas disponibles fixture)")
    parser.add_argument("--baseline", type=Path, default=None, help="Ruta JSON de referencia")
    parser.add_argument("--update-baseline", action="store_true", help="Sobrescribir la línea de base con los resultados del análisis actual")
    parser.add_argument("--allow-increase", action="store_true", help="Permitir la escritura de valores crecientes al actualizar la línea base")
    parser.add_argument("--verbose", action="store_true", help="Imprimir detalles de cada archivo")
    args = parser.parse_args()
    baseline_path = args.baseline or (args.root / BASELINE_REL)

    try:
        current = scan_roots(args.root)
    except FileNotFoundError as e:
        print(f"[font-scale-clipping] Error de escaneo: {e}")
        return 1

    risky = [s for s in current if s.occurrences or s.unbounded]
    fixed = sum(s.fixed_heights for s in current)
    total = sum(s.occurrences for s in current)
    unbounded = sum(s.unbounded for s in current)
    print(
        f"[font-scale-clipping] Escanear el código fuente de la interfaz de usuario: altura fija {fixed} en"
        f"(lleva texto y >= {MIN_RISK_DP}dp de {total} en),"
        f"ilimitado heightIn(min=…)+fillMaxSize niño {unbounded} en,a través {len(risky)} archivos"
    )
    if args.verbose:
        for s in risky:
            print(f"  {s.occurrences:3d}/{s.unbounded:1d}  {s.file}  {s.details} {s.unbounded_details}")
    current = risky

    if args.update_baseline:
        return update_baseline(baseline_path, current, args.allow_increase)

    baseline = load_baseline(baseline_path)
    if not baseline:
        print(f"[font-scale-clipping] FAILED — Línea base no encontrada {baseline_path};generar: --update-baseline")
        return 1

    base_files = baseline.get("files") or {}
    base_total = int((baseline.get("totals") or {}).get("occurrences", 0))
    unbounded_total = sum(s.unbounded for s in current)
    base_unbounded_n = len(baseline.get("unbounded") or {})
    print(
        f"[font-scale-clipping] actual/línea de base: Archivo {len(current)}/{len(base_files)}, "
        f"Número de plazas {total}/{base_total}, ilimitado heightIn {unbounded_total}/{base_unbounded_n}"
    )

    failures, improvements = evaluate(current, baseline)
    if improvements:
        print("[font-scale-clipping] hay mejora,Se recomienda ajustar --update-baseline:")
        for line in improvements:
            print(line)
    if failures:
        print("[font-scale-clipping] FAILED — Nuevo texto en altura fija por encima de la linea de base:")
        for line in failures:
            print(line)
        print(
            "como solucionarlo: ① altura fija → Modifier.heightIn(min = N.dp)(Mantenga el resultado final sin cambios y crezca con el tamaño de fuente),"
            "Tenga en cuenta que si usa fillMaxSize para un subelemento, también debe dar un límite superior a max o cambiar el subelemento a fillMaxWidth;"
            "② Altura ilimitadaIn + fillMaxSize niño → Límite superior máximo,O cambie el elemento secundario para llenarMaxWidth."
        )
        return 1
    print(
        f"[font-scale-clipping] PASS — no superó la línea de base "
        f"(Valores {len(base_files)} archivos / {base_total} en,Solo cayendo pero no subiendo)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
