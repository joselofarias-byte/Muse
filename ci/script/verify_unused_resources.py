#!/usr/bin/env python3
"""
verify_unused_resources.py — Verifique estrictamente si los recursos no utilizados informados por lint realmente no tienen referencias.

## por qué es necesario

lint Los recursos no utilizados se informarán falsamente (Kotlin todavía hace referencia a al menos 25 de los 920 medidos).
Antes de eliminar recursos manualmente, debe verificar con un estándar más estricto que lint; de lo contrario, se eliminará la cadena en uso. →
Interfaz en blanco/colapsar.

## Calibre de verificación (cualquier acierto se considera"todavía usado", no se puede eliminar)

1. Kotlin/XML Aparece en el código fuente. `R.<type>.<name>`(Kotlin)
2. XML en `@<type>/<name>`
3. Literales de nombres de recursos entre comillas en el código fuente (conservadores, que evitan la unión de cadenas);
   Pero excluya la declaración de clave del elemento de configuración (DataStore El nombre de la clave no tiene nada que ver con el recurso con el mismo nombre).

## Rendimiento

Escanee todos los códigos fuente a la vez, cree una colección de "nombres de recursos referenciados" y luego realice una consulta de colección en cada elemento que desee eliminar.
(O(n+m), sin retroceder periódicamente uno por uno).

## Uso

  python ci/script/verify_unused_resources.py --baseline app/lint-baseline.xml \
      --out E:/tmp/unused-verified.json
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

PROJECT_ROOT = Path(__file__).resolve().parents[2]
MAIN = PROJECT_ROOT / "app" / "src" / "main"

MSG_RE = re.compile(r"R\.(?P<type>\w+)\.(?P<name>\w+)")
# Kotlin: R.<type>.<name>
KT_REF_RE = re.compile(r"\bR\.(?P<type>\w+)\.(?P<name>\w+)\b")
# XML: @<type>/<name>
XML_REF_RE = re.compile(r"@(?P<type>\w+)/(?P<name>\w+)\b")


def load_unused(baseline: Path) -> list[dict]:
    """Extraiga las entradas de UnusedResources de la línea base de pelusa.

    Importante:lint voluntad**Múltiples recursos en la misma fila**Fusionar en un registro, solo en el mensaje
    Escribe el nombre. Por lo tanto, los errores en errorLine1 deben analizarse al mismo tiempo.**Todos** name=,
    De lo contrario, se perderán los recursos de seguimiento entre pares (es posible que aún se haga referencia a ellos).
    """
    tree = ET.parse(baseline)
    out = []
    for issue in tree.getroot().findall("issue"):
        if issue.get("id") != "UnusedResources":
            continue
        msg = issue.get("message") or ""
        m = MSG_RE.search(msg)
        if not m:
            continue
        locs = issue.findall("location")
        loc = locs[0] if locs else None
        res_type = m.group("type")
        names = [m.group("name")]

        # Suplemento:errorLine1 Otras personas viajando juntas name=(lint omitido al fusionar registros)
        line1 = issue.get("errorLine1") or ""
        for nm in re.findall(r'name="([^"]+)"', line1):
            if nm not in names:
                names.append(nm)

        for n in names:
            out.append(
                {
                    "type": res_type,
                    "name": n,
                    "file": loc.get("file") if loc is not None else "",
                    "line": loc.get("line") if loc is not None else "",
                }
            )
    return out


def build_index() -> tuple[set[str], set[str], set[str]]:
    """Regresar (kt_refs, xml_refs, kt_literals): Colección de nombres de recursos."""
    kt_refs: set[str] = set()
    xml_refs: set[str] = set()
    kt_literals: set[str] = set()

    for p in MAIN.rglob("*.kt"):
        text = p.read_text(encoding="utf-8", errors="ignore")
        for m in KT_REF_RE.finditer(text):
            kt_refs.add(m.group("name"))
        # Literal: excluir la línea de declaración de clave de configuración
        for line in text.splitlines():
            if re.search(r"KEY_[A-Z_]+|private val [A-Z_]+ =", line):
                continue
            for m in re.finditer(r"[\"']([a-z][a-z0-9_]{3,})[\"']", line):
                kt_literals.add(m.group(1))

    for p in MAIN.rglob("*.xml"):
        text = p.read_text(encoding="utf-8", errors="ignore")
        for m in XML_REF_RE.finditer(text):
            xml_refs.add(m.group("name"))

    return kt_refs, xml_refs, kt_literals


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--baseline", default=str(PROJECT_ROOT / "app" / "lint-baseline.xml"))
    ap.add_argument("--out", default="")
    args = ap.parse_args()

    unused = load_unused(Path(args.baseline))
    kt_refs, xml_refs, kt_literals = build_index()

    safe, used = [], []
    for e in unused:
        n = e["name"]
        ev = []
        if n in kt_refs:
            ev.append(f"kotlin R.{e['type']}.{n}")
        if n in xml_refs:
            ev.append(f"xml @{e['type']}/{n}")
        if n in kt_literals:
            ev.append(f'literal "{n}" in kotlin')
        if ev:
            used.append({**e, "evidence": ev})
        else:
            safe.append(e)

    result = {"safe_to_delete": safe, "still_used": used}
    print(f"UnusedResources total: {len(unused)}")
    print(f"  Confirmar que no hay referencias (se pueden eliminar): {len(safe)}")
    print(f"  Todavía referenciado (retenido)  : {len(used)}")
    if used:
        print("\nTodos los artículos aún citados:")
        for e in used:
            print(f"  {e['type']}.{e['name']}  <- {', '.join(e['evidence'])}")

    if args.out:
        Path(args.out).write_text(
            json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        print(f"\nha sido escrito: {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
