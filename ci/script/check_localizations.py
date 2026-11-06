#!/usr/bin/env python3
"""
check_localizations.py — Android Comprobación de integridad de recursos de cadena.

Contraste values/(punto de referencia)con cada uno values-<locale>/ bajo directorio strings_*.xml diferencia clave,
Informar claves faltantes para cada ubicación.CI se utiliza para hacer cumplir la integridad de la traducción.

Uso:
    python ci/script/check_localizations.py [--res-dir app/src/main/res] [--strict-locales en]

código de salida:
    0 — strict locales(Predeterminado en)No falta nada key
    1 — hay algo que falta key(CI falló)

Descripción:
    v1.0.56: Nuevo --strict-locales — Sólo fallarán las configuraciones regionales enumeradas a las que les faltan claves.;
    otros idiomas(es/ja/ko/pt/ru espera)solo falta imprimir WARN,No bloquea CI.
    La traducción multilingüe es un trabajo incremental,La liberación de la función principal no debe bloquearse;idioma primario obligatorio(en)La integridad es suficiente.
"""

import argparse
import os
import re
import sys
from pathlib import Path

# Windows Predeterminado de la consola china GBK,Y este script se imprimirá ✓/✗(No en el juego de caracteres GBK),
# La salida directa arrojará UnicodeEncodeError y hará que los carriles fallen. Cortado uniformemente a UTF-8.
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # noqa: BLE001 - El cheque en sí no debería verse afectado si el entorno no lo soporta.
    pass

# Extracto <string name="key"> regularidad
STRING_NAME_RE = re.compile(r'<string\s+name="([^"]+)"', re.IGNORECASE)

# Modo de archivo a escanear
STRING_FILE_PATTERN = "strings*.xml"
# Nombre del directorio de valores predeterminados(punto de referencia)
DEFAULT_VALUES_DIR = "values"
# Prefijos de directorios locales admitidos
LOCALE_DIR_PREFIX = "values-"


def extract_keys_from_file(file_path: Path) -> set:
    """de un solo strings_*.xml Extraiga todas las claves de cadena del archivo."""
    if not file_path.exists():
        return set()
    try:
        content = file_path.read_text(encoding="utf-8")
    except Exception as e:
        print(f"  WARNING: leer {file_path} falló: {e}", file=sys.stderr)
        return set()
    return set(STRING_NAME_RE.findall(content))


def extract_all_keys(values_dir: Path) -> dict:
    """
    Extraer todo del directorio de valores. strings_*.xml llave.

    Regresar: {filename: set(keys)} Como por ejemplo {"strings_ui_main.xml": {"key1", "key2"}}
    """
    result = {}
    if not values_dir.exists():
        return result
    for xml_file in sorted(values_dir.glob(STRING_FILE_PATTERN)):
        keys = extract_keys_from_file(xml_file)
        result[xml_file.name] = keys
    return result


def find_locale_dirs(res_dir: Path) -> list:
    """encontrar todo values-* Directorio(Excluir directorios que no sean de idiomas, como valores-noche)."""
    locales = []
    if not res_dir.exists():
        return locales
    for d in sorted(res_dir.iterdir()):
        if not d.is_dir():
            continue
        name = d.name
        if not name.startswith(LOCALE_DIR_PREFIX):
            continue
        # Excluir directorios que no sean de idiomas(values-night, values-v31 espera)
        suffix = name[len(LOCALE_DIR_PREFIX):]
        if suffix.startswith("v") or suffix in ("night", "land", "port", "desk", "car", "television", "appliance", "watch", "small", "normal", "large", "xlarge", "hdpiv", "wdpiv"):
            continue
        locales.append(d)
    return locales


def normalize_locale_name(name: str) -> str:
    """Accept either Android resource directory names or plain locale codes."""
    return name[len(LOCALE_DIR_PREFIX):] if name.startswith(LOCALE_DIR_PREFIX) else name


def check_locale(res_dir: Path, verbose: bool = False, strict_locales: set = None) -> int:
    """
    Verifique la integridad de todas las claves locales.

    Regresar: Número total de claves faltantes(0 significa todo completo)
    """
    default_dir = res_dir / DEFAULT_VALUES_DIR
    baseline = extract_all_keys(default_dir)
    strict_locale_codes = {
        normalize_locale_name(locale) for locale in (strict_locales or set())
    }

    if not baseline:
        print(f"ERROR: Directorio de referencia {default_dir} Ninguno strings_*.xml Archivo", file=sys.stderr)
        return 1

    total_baseline_keys = sum(len(keys) for keys in baseline.values())
    print(f"Directorio de referencia: {default_dir}")
    print(f"Número de archivos de referencia: {len(baseline)}, Número total de claves de referencia: {total_baseline_keys}")
    print()

    locale_dirs = find_locale_dirs(res_dir)
    if not locale_dirs:
        print("WARNING: no encontré ninguno values-* locale Directorio", file=sys.stderr)
        return 0

    total_missing = 0

    for locale_dir in locale_dirs:
        locale_name = locale_dir.name
        locale_keys = extract_all_keys(locale_dir)

        locale_total = sum(len(keys) for keys in locale_keys.values())
        # v1.0.56: Cambiar a comparación de conjuntos de claves entre archivos — key distribuidos en diferentes strings_*.xml es legal
        #   (Como por ejemplo onboarding_* Chino en strings_features.xml, inglés en strings.xml),
        #   La fusión de recursos solo analiza la unicidad clave;La comparación por archivo informará falsamente de información faltante y confundirá la traducción.
        baseline_all = set().union(*baseline.values())
        locale_all = set().union(*locale_keys.values())
        missing_keys = baseline_all - locale_all
        extra_keys = locale_all - baseline_all if verbose else set()

        missing_count = len(missing_keys)
        # v1.0.56: strict locale Fallo acumulado solo si falta;A otros idiomas les falta solo advertencia
        is_strict = normalize_locale_name(locale_name) in strict_locale_codes
        if is_strict:
            total_missing += missing_count

        if missing_count == 0:
            status = "OK"
        else:
            status = f"MISSING {missing_count} keys" + (" (FAIL)" if is_strict else " (WARN)")
        print(f"  {locale_name}: {locale_total} keys [{status}]")

        if missing_keys:
            for key in sorted(missing_keys):
                print(f"    MISSING: {key}")

        if verbose and extra_keys:
            for key in sorted(extra_keys):
                print(f"    EXTRA:   {key}")

    print()
    if total_missing == 0:
        print(f"✓ strict locales({', '.join(sorted(strict_locales))}) Traducción completa(0 desaparecido)")
        return 0
    else:
        print(f"✗ Traducción incompleta: {total_missing} faltan llaves(strict locales: {', '.join(sorted(strict_locales))})", file=sys.stderr)
        return 1


def main():
    parser = argparse.ArgumentParser(description="Android Verificación de integridad de recursos de cadena")
    parser.add_argument(
        "--res-dir",
        default="app/src/main/res",
        help="Android res ruta del directorio(Predeterminado: app/src/main/res)",
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="Mostrar información adicional(Incluir configuración regional redundante key)",
    )
    parser.add_argument(
        "--strict-locales",
        default="en",
        help="La clave faltante causará fallas locale(separados por comas,Predeterminado: en);A otros idiomas les falta solo advertencia",
    )
    args = parser.parse_args()
    strict_locales = {
        normalize_locale_name(locale.strip())
        for locale in args.strict_locales.split(",")
        if locale.strip()
    }

    res_dir = Path(args.res_dir).resolve()
    if not res_dir.exists():
        print(f"ERROR: res El directorio no existe: {res_dir}", file=sys.stderr)
        sys.exit(2)

    exit_code = check_locale(res_dir, verbose=args.verbose, strict_locales=strict_locales)
    sys.exit(exit_code)


if __name__ == "__main__":
    main()
