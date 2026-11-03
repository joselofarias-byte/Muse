#!/usr/bin/env python3
"""
translate_locales.py — Traduce línea por línea usando la API gratuita de Google Translate strings_*.xml archivo.

Versión reparada: Traducir elemento por elemento(No en lotes) + Simultaneidad multiproceso, Evite la desalineación de los resultados de la traducción por lotes.
Protección de marcador de posición: Utilice etiquetas de comentarios HTML, Restauración de regularización post-traducción.

Flujo de trabajo:
  1. Leer todo en valores-es/ strings_*.xml como referente inglés
  2. para cada objetivo locale (es/ja/ko/ru/pt-rBR):
     a. Lea que esta configuración regional ha sido traducida.(Mantener cadenas traducidas por humanos)
     b. a no traducido(Sigue siendo un marcador de posición en inglés o chino)La cadena se llama una por una. Google Translate
     c. Vuelva a escribir el archivo traducido
  3. Preservar estructura XML, comentarios y marcadores de posición de formato(%1$s espera)
"""

from __future__ import annotations

import json
import re
import sys
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

# Directorio raíz del proyecto
PROJECT_ROOT = Path(__file__).resolve().parents[2]
RES_DIR = PROJECT_ROOT / "app" / "src" / "main" / "res"
EN_DIR = RES_DIR / "values-en"

# mapeo del idioma de destino: Android locale → Google Translate language code
TARGET_LOCALES = {
    "values-es": "es",
    "values-ja": "ja",
    "values-ko": "ko",
    "values-ru": "ru",
    "values-pt-rBR": "pt",
}

# Número de hilos simultáneos
MAX_WORKERS = 15
# Intervalo de solicitud(segundos),Cada hilo se controla de forma independiente.
REQUEST_DELAY = 0.2
# Número de reintentos
MAX_RETRIES = 3

# Reglas regulares de detección de caracteres chinos
CJK_PATTERN = re.compile(r"[\u4e00-\u9fff]")
# Etiqueta de cadena normal(partido <string name="...">...</string>)
STRING_TAG_PATTERN = re.compile(r'<string\s+name="([^"]+)"\s*(?:[^>]*?)>(.*?)</string>', re.DOTALL)
# Formato de marcador de posición normal(%1$s, %1$d, \n, \", XML entidad, etc.)
# Proteger mediante etiquetas de comentarios HTML,Evite averías en el motor de traducción
PLACEHOLDER_PATTERN = re.compile(r"%\d+\$[sd]|\\n|&amp;|&lt;|&gt;|&quot;|&apos;")


def log(msg: str):
    """Salida de registro con descarga"""
    print(msg, flush=True)


def has_chinese(text: str) -> bool:
    """Detectar si una cadena contiene caracteres chinos"""
    return bool(CJK_PATTERN.search(text))


def google_translate_single(text: str, target_lang: str, source_lang: str = "en") -> str | None:
    """
    Llame a la API gratuita de Google Translate para traducir un solo texto.
    Traducir elemento por elemento,Evite problemas de segmentación y desalineación en la traducción por lotes.
    """
    url = (
        "https://translate.googleapis.com/translate_a/single"
        f"?client=gtx&sl={source_lang}&tl={target_lang}&dt=t&q={urllib.parse.quote(text)}"
    )

    for attempt in range(MAX_RETRIES):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req, timeout=15) as resp:
                data = json.loads(resp.read().decode("utf-8"))
            # data[0] es una serie de resultados de traducción,cada elemento [translated_text, original_text, ...]
            # Al traducir artículo por artículo data[0] solo un elemento
            if data and data[0] and data[0][0] and data[0][0][0]:
                return data[0][0][0]
            return None
        except Exception as e:
            if attempt < MAX_RETRIES - 1:
                wait = (attempt + 1) * 2
                time.sleep(wait)
            else:
                return None
    return None


def protect_placeholders(text: str) -> tuple[str, list[tuple[str, str]]]:
    """
    marcador de posición de formato protegido(%1$s espera),Reemplazado con etiquetas de comentarios HTML,Restaurar después de la traducción.
    HTML Los comentarios no serán modificados por el motor de traducción.
    """
    placeholders: list[tuple[str, str]] = []
    counter = [0]

    def replace(match):
        token = f"<!--PH{counter[0]:04d}-->"
        placeholders.append((token, match.group(0)))
        counter[0] += 1
        return token

    protected = PLACEHOLDER_PATTERN.sub(replace, text)
    return protected, placeholders


def restore_placeholders(text: str, placeholders: list[tuple[str, str]]) -> str:
    """Restaurar marcadores de posición protegidos"""
    for token, original in placeholders:
        text = text.replace(token, original)
        # Ten todo en cuenta: El motor de traducción puede romper el formato de los comentarios(Como agregar espacios y minúsculas.)
        # Extraer número del token,Utilice variantes coincidentes habituales
        m = re.search(r"PH(\d{4})", token)
        if m:
            num = m.group(1)
            pattern = re.compile(
                r"<!--\s*[Pp][Hh]\s*" + num + r"\s*-->",
                re.IGNORECASE,
            )
            text = pattern.sub(original, text)
    return text


def extract_strings(xml_content: str) -> list[tuple[str, str, str]]:
    """
    Extraer todo <string> etiqueta.
    Regresar [(name, value, full_match), ...]
    """
    results = []
    for match in STRING_TAG_PATTERN.finditer(xml_content):
        name = match.group(1)
        value = match.group(2).strip()
        results.append((name, value, match.group(0)))
    return results


def translate_one(name: str, en_value: str, target_lang: str) -> tuple[str, str]:
    """
    Traduce una sola cadena. Regresar (name, translated_value).
    Retorno del fracaso (name, en_value) Mantén el inglés.
    """
    # Proteger marcador de posición
    protected, placeholders = protect_placeholders(en_value)

    # Traducción
    translated = google_translate_single(protected, target_lang)
    if translated is None:
        return (name, en_value)

    # Restaurar marcador de posición
    restored = restore_placeholders(translated, placeholders)

    # limpiar: Eliminar espacios en blanco iniciales y finales
    restored = restored.strip()

    # Si el resultado de la traducción está vacío,mantener el ingles
    if not restored:
        return (name, en_value)

    return (name, restored)


def translate_xml_file(
    en_content: str,
    existing_content: str | None,
    target_lang: str,
    file_name: str,
) -> str | None:
    """
    Traducir un solo strings_*.xml archivo.
    """
    en_strings = extract_strings(en_content)
    if not en_strings:
        return None

    # Analizar traducciones existentes
    existing_map: dict[str, str] = {}
    if existing_content:
        for name, value, _ in extract_strings(existing_content):
            existing_map[name] = value

    # Clasificación: Necesita traducción versus reservada
    to_translate: list[tuple[str, str]] = []  # (name, english_value)
    final_values: dict[str, str] = {}

    for name, en_value, _ in en_strings:
        existing = existing_map.get(name)

        if existing is not None:
            if existing == en_value:
                # El valor es el mismo que en inglés. → Necesita traducción
                to_translate.append((name, en_value))
            elif has_chinese(existing):
                # marcador de posición chino → Necesita traducción
                to_translate.append((name, en_value))
            else:
                # Ya tengo traducción real. → Reservar
                final_values[name] = existing
        else:
            # No hay traducción existente → Necesita traducción
            to_translate.append((name, en_value))

    if not to_translate:
        log(f"  [{file_name}] all strings already translated, skipping")
        return existing_content

    log(f"  [{file_name}] translating {len(to_translate)} strings to {target_lang}...")

    # Traducción concurrente multiproceso
    with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
        futures = {
            executor.submit(translate_one, name, en_val, target_lang): name
            for name, en_val in to_translate
        }
        done_count = 0
        for future in as_completed(futures):
            name, translated = future.result()
            final_values[name] = translated
            done_count += 1
            if done_count % 50 == 0:
                log(f"    [{file_name}] {done_count}/{len(to_translate)} done")

    # Reconstrucción de XML: uso de la línea base en inglés como plantilla,valor de reposición
    result_content = en_content
    for name, en_value, full_match in en_strings:
        translated = final_values.get(name, en_value)
        # XML escapar(Reservar & < > escapando)
        # Nota: Es posible que los resultados de la traducción ya contengan entidades XML(Como por ejemplo &amp;),Necesidad de evitar el doble escape.
        # Estrategia: Primera desescapada de las entidades en el benchmark inglés,Luego vuelva a escapar de acuerdo con los resultados de la traducción.
        # Pero aquí está simplificado.: Si el resultado de la traducción contiene & Entidad al principio,No más escapar
        if "&" in translated and not translated.startswith("&amp;"):
            # Compruebe si la entidad XML ya existe
            has_entity = bool(re.search(r"&\w+;", translated))
            if not has_entity:
                translated = translated.replace("&", "&amp;")
        translated = translated.replace("<", "&lt;").replace(">", "&gt;")
        new_tag = f'<string name="{name}">{translated}</string>'
        result_content = result_content.replace(full_match, new_tag, 1)

    return result_content


def main():
    # Habilitar el almacenamiento en búfer de línea
    try:
        sys.stdout.reconfigure(line_buffering=True)
        sys.stderr.reconfigure(line_buffering=True)
    except Exception:
        pass

    log("=" * 60)
    log("Muse Locale Translator (v2 - per-item translation)")
    log("=" * 60)

    if not EN_DIR.exists():
        log(f"ERROR: English baseline directory not found: {EN_DIR}")
        return 1

    # recoger todo strings_*.xml Archivo
    en_files = sorted(EN_DIR.glob("strings_*.xml"))
    main_strings = EN_DIR / "strings.xml"
    if main_strings.exists():
        en_files.append(main_strings)

    log(f"Found {len(en_files)} English baseline files")
    log(f"Target locales: {', '.join(TARGET_LOCALES.keys())}")
    log(f"Concurrency: {MAX_WORKERS} threads")

    total_translated = 0
    total_skipped = 0
    total_failed = 0

    for locale_dir_name, gtx_lang in TARGET_LOCALES.items():
        locale_dir = RES_DIR / locale_dir_name
        log(f"\n{'─' * 40}")
        log(f"Processing locale: {locale_dir_name} (gtx: {gtx_lang})")
        log(f"{'─' * 40}")

        locale_dir.mkdir(parents=True, exist_ok=True)

        for en_file in en_files:
            file_name = en_file.name
            target_file = locale_dir / file_name

            en_content = en_file.read_text(encoding="utf-8")
            existing_content = None
            if target_file.exists():
                existing_content = target_file.read_text(encoding="utf-8")

            try:
                result = translate_xml_file(en_content, existing_content, gtx_lang, file_name)
                if result is not None:
                    target_file.write_text(result, encoding="utf-8")
                    total_translated += 1
                    log(f"  ✓ {file_name}")
                else:
                    total_skipped += 1
                    log(f"  - {file_name} (skipped)")
            except Exception as e:
                total_failed += 1
                log(f"  ✗ {file_name} (ERROR: {e})")

    log(f"\n{'=' * 60}")
    log(f"Translation complete:")
    log(f"  Translated: {total_translated}")
    log(f"  Skipped:    {total_skipped}")
    log(f"  Failed:     {total_failed}")
    log(f"{'=' * 60}")

    return 0 if total_failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
