#!/usr/bin/env python3
"""
delete_unused_resources.py — Presione verificar para eliminar recursos a los que no se hace referencia.

## restricciones de seguridad
- Elimine únicamente la entrada safe_to_delete en verificar_unused_resources.py.
- **Eliminar todas las claves con el mismo nombre en todos los directorios de idiomas juntas**(values + values-xx), de lo contrario se activa MissingTranslation.
- drawable/ Elimine el archivo independiente directamente.
- values abajo <string>/<plurals>/<array> Elimine la línea correspondiente.
- Imprima la lista antes de la eliminación y el informe después de la eliminación.

Uso:
  python ci/script/delete_unused_resources.py --verified E:/tmp/unused-verified.json [--apply]
  Sin --apply simplemente haga un ensayo.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

PROJECT_ROOT = Path(__file__).resolve().parents[2]
RES = PROJECT_ROOT / "app" / "src" / "main" / "res"

# Todos los directorios de idiomas
LOCALES = ["values", "values-en", "values-es", "values-ja", "values-ko", "values-pt-rBR", "values-ru"]


def collect_names(verified: Path) -> tuple[set[str], set[str]]:
    """Regresar (strings+plurals+array colección de nombres, drawable colección de nombres)."""
    d = json.loads(verified.read_text(encoding="utf-8"))
    safe = d["safe_to_delete"]
    value_names = {e["name"] for e in safe if e["type"] in ("string", "plurals", "array")}
    drawables = {e["name"] for e in safe if e["type"] == "drawable"}
    return value_names, drawables


def delete_drawables(drawables: set[str], apply: bool) -> list[str]:
    removed = []
    for name in sorted(drawables):
        for p in RES.rglob(f"{name}.xml"):
            # simplemente eliminar drawable* bajo el directorio
            if "drawable" in p.parent.name:
                if apply:
                    p.unlink()
                removed.append(str(p.relative_to(PROJECT_ROOT)))
    return removed


def delete_value_entries(value_names: set[str], apply: bool) -> list[str]:
    """eliminar values*/**.xml correspondiente a <string>/<plurals>/<array> Entradas (incluidas entradas de varias líneas).

    Importante: puede existir en el archivo fuente.**Múltiples recursos en la misma fila**(Legado del formato histórico),
    Por ejemplo:
        <string name="a">..</string>    <string name="b">..</string>
    por lo tanto no puede“Eliminar línea completa”, en su lugar debes presionar name **Eliminar solo el segmento objetivo**, conserva otro contenido del par.
    """
    removed = []
    tag_re = re.compile(
        r'<(string|plurals|array)\s+name="([^"]+)"[^>]*>.*?</\1>',
        re.DOTALL,
    )
    for loc in LOCALES:
        d = RES / loc
        if not d.is_dir():
            continue
        for p in d.rglob("*.xml"):
            text = p.read_text(encoding="utf-8")
            changed = 0

            def _sub(m: re.Match) -> str:
                nonlocal changed
                if m.group(2) in value_names:
                    changed += 1
                    removed.append(f"{p.relative_to(PROJECT_ROOT)}: {m.group(2)}")
                    return ""  # Eliminar solo este segmento
                return m.group(0)

            new_text = tag_re.sub(_sub, text)
            if changed and apply:
                p.write_text(new_text, encoding="utf-8")
    return removed


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--verified", default="E:/tmp/unused-verified.json")
    ap.add_argument("--apply", action="store_true")
    args = ap.parse_args()

    value_names, drawables = collect_names(Path(args.verified))
    print(f"Para ser eliminado: values entrada {len(value_names)}, drawable {len(drawables)}")
    print("modo:", "APPLY(realmente eliminado)" if args.apply else "DRY-RUN")

    dw = delete_drawables(drawables, args.apply)
    vl = delete_value_entries(value_names, args.apply)

    print(f"\ndrawable Eliminar {len(dw)} archivos")
    print(f"values Eliminar {len(vl)} Artículos (en varios idiomas)")
    if not args.apply:
        print("\n(dry-run, el archivo no ha sido modificado; agregar --aplicar para ejecutar)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
