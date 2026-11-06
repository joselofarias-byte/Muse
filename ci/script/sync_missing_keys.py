"""
sync_missing_keys.py — Falta la clave en la configuración regional de sincronización.

Leer todo desde el directorio valores base/ strings_*.xml de key,
para cada uno values-<locale>/ Directorio,falta suplemento key(Utilice el valor chino base como marcador de posición).

Uso:
    py -3 ci/script/sync_missing_keys.py [--res-dir app/src/main/res]
"""

import re
import sys
from pathlib import Path

# Extrae solo el atributo de nombre(Igual que check_localizations.py)
NAME_RE = re.compile(r'<string\s+name="([^"]+)"', re.IGNORECASE)
# extraer completo <string ...>...</string> elemento(Admite contenido multilínea)
FULL_STRING_RE = re.compile(r'<string\s+name="([^"]+)"[^>]*>.*?</string>', re.IGNORECASE | re.DOTALL)
LOCALE_DIR_PREFIX = "values-"
NON_LOCALE_SUFFIXES = ("v31", "night", "land", "port", "desk", "car", "television", "appliance", "watch")


def extract_names(file_path: Path) -> set:
    """Extraer todo del archivo string name(Devolver sólo recogida de llaves)."""
    if not file_path.exists():
        return set()
    content = file_path.read_text(encoding="utf-8")
    return set(NAME_RE.findall(content))


def extract_full_elements(file_path: Path) -> dict:
    """Extraer del archivo {key: full_xml_element} mapeo(Soporta múltiples líneas)."""
    if not file_path.exists():
        return {}
    content = file_path.read_text(encoding="utf-8")
    result = {}
    for match in FULL_STRING_RE.finditer(content):
        key = match.group(1)
        result[key] = match.group(0)
    return result


def sync_locale(locale_dir: Path, baseline_by_file: dict) -> int:
    """Sincronizar un único directorio local,Devuelve el número de claves nuevas."""
    added = 0
    for filename, baseline_strings in baseline_by_file.items():
        locale_file = locale_dir / filename
        locale_names = extract_names(locale_file)

        missing_keys = set(baseline_strings.keys()) - locale_names
        if not missing_keys:
            continue

        if locale_file.exists():
            content = locale_file.read_text(encoding="utf-8")
        else:
            content = '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n'

        # en </resources> indel anterior key
        insert_lines = []
        for key in sorted(missing_keys):
            if key in baseline_strings:
                insert_lines.append(f"    {baseline_strings[key]}")
                added += 1

        if not insert_lines:
            continue

        if "</resources>" in content:
            new_content = content.replace(
                "</resources>",
                "\n".join(insert_lines) + "\n</resources>"
            )
        else:
            new_content = content + "\n" + "\n".join(insert_lines) + "\n"

        locale_file.write_text(new_content, encoding="utf-8")

    return added


def main():
    res_dir = Path("app/src/main/res").resolve()
    if not res_dir.exists():
        print(f"ERROR: res El directorio no existe: {res_dir}", file=sys.stderr)
        sys.exit(2)

    # 1. Leer todos los valores base/ strings(elemento completo)
    default_dir = res_dir / "values"
    baseline_by_file = {}
    for xml_file in sorted(default_dir.glob("strings_*.xml")):
        baseline_by_file[xml_file.name] = extract_full_elements(xml_file)

    total_baseline = sum(len(v) for v in baseline_by_file.values())
    print(f"Directorio de referencia: {default_dir}, Número de archivos: {len(baseline_by_file)}, key total: {total_baseline}")

    # 2. Buscar todos los directorios locales
    locale_dirs = []
    for d in sorted(res_dir.iterdir()):
        if not d.is_dir() or not d.name.startswith(LOCALE_DIR_PREFIX):
            continue
        suffix = d.name[len(LOCALE_DIR_PREFIX):]
        if suffix.startswith("v") or suffix in NON_LOCALE_SUFFIXES:
            continue
        locale_dirs.append(d)

    # 3. Sincronizar cada locale
    for locale_dir in locale_dirs:
        added = sync_locale(locale_dir, baseline_by_file)
        print(f"  {locale_dir.name}: Nuevo {added} desaparecido key")

    print("\nDone.")


if __name__ == "__main__":
    main()
