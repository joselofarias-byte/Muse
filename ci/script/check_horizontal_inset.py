#!/usr/bin/env python3
"""
check_horizontal_inset.py — Barandillas de acolchado horizontal (borde).

Fuente de la regla: `guia-biblioteca-ui.md` Sección "Relleno horizontal".
texto/La razón por la que la tarjeta llega al borde de la pantalla es casi la misma: El relleno horizontal del contenedor se reduce explícitamente a 0.
Este script bloquea este tipo de compensación explícita.:

  - horizontalPadding = 0.dp        (MuseBottomSheet Panel de contenido)
  - padding(horizontal = 0.dp)
  - PaddingValues(horizontal = 0.dp)
  - contentPadding = 0.dp

Muy pocas escenas que requieran welding.(Medios de pantalla completa, galería de desplazamiento horizontal.)Agregar al final de la línea
`// inset-guard: allow` Exención explícita.

Uso:
  py -3 ci/script/check_horizontal_inset.py
  py -3 ci/script/check_horizontal_inset.py --root app/src/main/java/io/zer0/muse/ui
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

PATTERNS = [
    (re.compile(r"horizontalPadding\s*=\s*0(?:\.\w+)?\b"), "El acolchado horizontal del panel se presiona para 0"),
    (re.compile(r"padding\(\s*horizontal\s*=\s*0(?:\.\w+)?\b"), "Modifier.padding(horizontal = 0)"),
    (re.compile(r"PaddingValues\(\s*horizontal\s*=\s*0(?:\.\w+)?\b"), "PaddingValues(horizontal = 0)"),
    (re.compile(r"contentPadding\s*=\s*0(?:\.\w+)?\b"), "contentPadding para 0"),
]
ALLOW_MARK = "inset-guard: allow"


def scan(root: Path) -> list[tuple[Path, int, str, str]]:
    hits: list[tuple[Path, int, str, str]] = []
    for path in sorted(root.rglob("*.kt")):
        lines = path.read_text(encoding="utf-8", errors="ignore").splitlines()
        for lineno, line in enumerate(lines, 1):
            if ALLOW_MARK in line:
                continue
            # v2.7: guard También se permite colocarlo en**Al lado de la línea anterior**.ktlint La bifurcación de parámetros es obligatoria y está prohibida en
            # Al escribir comentarios en la misma línea que la lista de parámetros, sólo puede colocar el comentario de exención en una línea separada; la línea sobre la línea de impacto también cuenta.
            if lineno >= 2 and ALLOW_MARK in lines[lineno - 2]:
                continue
            stripped = re.sub(r'"[^"]*"', '""', line)
            if stripped.lstrip().startswith("//"):
                continue
            for pattern, reason in PATTERNS:
                if pattern.search(stripped):
                    hits.append((path, lineno, reason, line.strip()))
                    break
    return hits


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default="app/src/main/java/io/zer0/muse/ui")
    args = parser.parse_args()

    root = Path(args.root)
    if not root.is_dir():
        print(f"[horizontal-inset] El directorio no existe: {root}")
        return 0

    hits = scan(root)
    if not hits:
        print("[horizontal-inset] escanear ui/hecho: 0 El relleno horizontal se borra en")
        print("[horizontal-inset] PASS — No se pega ningún contenido en el borde de la pantalla.")
        return 0

    print(f"[horizontal-inset] descubrir {len(hits)} El relleno horizontal se borra en(El contenido se desplazará hasta el borde de la pantalla.):")
    for path, lineno, reason, snippet in hits:
        print(f"  {path}:{lineno}  {reason}\n      {snippet}")
    print("[horizontal-inset] FAIL — Utilice MusePaddings.screen en su lugar.")
    print("                  Si realmente necesitas tejerlo, agrégalo al final de la línea. `// inset-guard: allow` y explicar las razones")
    return 1


if __name__ == "__main__":
    sys.exit(main())
