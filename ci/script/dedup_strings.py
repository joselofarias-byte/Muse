#!/usr/bin/env python3
"""
dedup_strings.py — Detectar y eliminar strings_*.xml Duplicar claves entre archivos.

pregunta: strings.xml y strings_features.xml pueden definir lo mismo key,
Hace que el compilador de Android informe "Duplicate resources" Error.

Estrategia:
  1. strings.xml máxima prioridad(archivo maestro)
  2. el resto strings_*.xml En orden alfabético por nombre de archivo,El primero en llegar es la primera prioridad
  3. Se eliminan las claves duplicadas en archivos posteriores.

Uso: py -3 ci/script/dedup_strings.py [--res-dir app/src/main/res] [--dry-run]
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

STRING_TAG_PATTERN = re.compile(r'<string\s+name="([^"]+)"\s*(?:[^>]*?)>(.*?)</string>', re.DOTALL)


def extract_string_names(xml_content: str) -> list[str]:
    """Extraer todo el XML string name"""
    return [m.group(1) for m in STRING_TAG_PATTERN.finditer(xml_content)]


def remove_string_tag(xml_content: str, name: str) -> tuple[str, bool]:
    """Elimina el nombre especificado del XML. <string> etiqueta(Eliminar toda la fila)"""
    # coincidir con toda la línea(Incluir espacios en blanco iniciales y nuevas líneas)
    pattern = re.compile(
        r'\s*<string\s+name="' + re.escape(name) + r'"\s*(?:[^>]*?)>.*?</string>\s*\n?',
        re.DOTALL,
    )
    new_content, count = pattern.subn('', xml_content)
    return new_content, count > 0


def dedup_locale(locale_dir: Path, dry_run: bool = False) -> int:
    """
    Deduplicar un único directorio local.
    Devuelve el número total de claves duplicadas eliminadas.
    """
    # prioridad de archivo: strings.xml más alto,El resto está ordenado por nombre de archivo.
    files = sorted(locale_dir.glob("strings_*.xml"))
    main_strings = locale_dir / "strings.xml"
    if main_strings.exists():
        files.insert(0, main_strings)

    if not files:
        return 0

    seen_keys: set[str] = set()
    total_removed = 0

    for file_path in files:
        content = file_path.read_text(encoding="utf-8")
        names = extract_string_names(content)

        duplicates = [n for n in names if n in seen_keys]
        if not duplicates:
            seen_keys.update(names)
            continue

        # Eliminar duplicados key
        new_content = content
        for dup_name in duplicates:
            new_content, removed = remove_string_tag(new_content, dup_name)
            if removed:
                total_removed += 1

        if not dry_run:
            file_path.write_text(new_content, encoding="utf-8")

        status = "[DRY]" if dry_run else "[FIXED]"
        print(f"  {status} {file_path.name}: removed {len(duplicates)} duplicates: {', '.join(duplicates[:5])}{'...' if len(duplicates) > 5 else ''}")

        # Actualizar la colección de claves vistas(Solo agregue no duplicados)
        seen_keys.update(n for n in names if n not in duplicates)

    return total_removed


def main():
    parser = argparse.ArgumentParser(description="Android strings_*.xml Repetir la deduplicación de claves")
    parser.add_argument("--res-dir", default="app/src/main/res", help="Android res ruta del directorio")
    parser.add_argument("--dry-run", action="store_true", help="Sólo detectar pero no modificar")
    args = parser.parse_args()

    res_dir = Path(args.res_dir)
    if not res_dir.exists():
        print(f"ERROR: res directory not found: {res_dir}")
        return 1

    # Buscar todos los directorios locales
    locale_dirs = sorted([d for d in res_dir.iterdir() if d.is_dir() and d.name.startswith("values")])

    print(f"Found {len(locale_dirs)} locale directories")
    print(f"Mode: {'DRY RUN' if args.dry_run else 'FIX'}")
    print()

    grand_total = 0
    for locale_dir in locale_dirs:
        removed = dedup_locale(locale_dir, dry_run=args.dry_run)
        if removed > 0:
            print(f"  {locale_dir.name}: {removed} duplicates removed")
        grand_total += removed

    print()
    print(f"Total duplicates {'detected' if args.dry_run else 'removed'}: {grand_total}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
