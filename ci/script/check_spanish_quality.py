#!/usr/bin/env python3
"""Check Spanish resource coverage, formatting and known translation regressions.

These checks catch structural defects; they do not replace a language review.
Resource names, not XML filenames, determine Android's translation fallback.
"""
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FORMAT = re.compile(r'%(?:\d+\$)?[dsf]')
CJK = re.compile(r'[\u3400-\u4dbf\u4e00-\u9fff]')
# Values that were translated as ordinary words and broke names or UI actions.
EXACT_LABELS = {
    "proxy_type_socks5": "SOCKS5",
    "action_close": "Cerrar",
    "settings_common_close": "Cerrar",
    "settings_common_show": "Mostrar",
    "settings_provider_category_custom": "Personalizado",
    "settings_asr_provider_openai_whisper": "Whisper",
    "settings_asr_provider_agnes": "Agnes",
    "settings_rag_parser_mineru": "MinerU",
    "settings_theme_font_small": "P",
    "settings_theme_font_medium": "M",
    "settings_theme_font_large": "G",
    "settings_theme_font_xlarge": "XG",
}
TECHNICAL_TERMS = {
    "settings_provider_json_parse_failed": ("private_key", "client_email"),
    "settings_rag_common_models": ("text-embedding-3-small", "text-embedding-3-large", "embedding-2"),
    "settings_rag_note3": ("knowledge_search",),
    "settings_rag_markdown_aware": ("Markdown",),
}
DANGLING_STEP = re.compile(r'(?:^|\n)\s*(?:\d+\.|[•-])\s*$')


def catalog(folder: Path) -> tuple[dict[str, ET.Element], list[str]]:
    entries: dict[str, ET.Element] = {}
    errors = []
    for path in sorted(folder.glob('*.xml')):
        for item in ET.parse(path).getroot():
            if item.tag not in {'string', 'plurals', 'string-array'}:
                continue
            # Android certificate arrays are configuration, not UI copy.
            if path.name == 'font_certs.xml':
                continue
            name = item.get('name')
            if not name:
                continue
            if name in entries:
                errors.append(f'{folder.name}: duplicate resource {name}')
            entries[name] = item
    return entries, errors


def values(item: ET.Element) -> dict[str, str]:
    if item.tag == 'string':
        return {'string': ''.join(item.itertext())}
    return {
        child.get('quantity', str(index)): ''.join(child.itertext())
        for index, child in enumerate(item)
    }


def check_resources(res_dir: Path) -> list[str]:
    source, errors = catalog(res_dir / 'values')
    spanish, duplicates = catalog(res_dir / 'values-es')
    errors += duplicates
    if not source or not spanish:
        errors.append('source and Spanish catalogs must both exist and contain resources')
    for name, item in source.items():
        if item.get('translatable') == 'false':
            continue
        translated = spanish.get(name)
        if translated is None:
            errors.append(f'{name}: missing Spanish resource')
            continue
        if item.tag != translated.tag:
            errors.append(f'{name}: resource type changed')
            continue
        original_values = values(item)
        translated_values = values(translated)
        if item.tag == 'plurals' and not {'one', 'other'} <= translated_values.keys():
            errors.append(f'{name}: Spanish plurals require one and other')
        if item.tag == 'string-array' and len(item) != len(translated):
            errors.append(f'{name}: array length changed')
        for quantity, value in translated_values.items():
            if not value.strip():
                errors.append(f'{name}/{quantity}: empty translation')
            if CJK.search(value):
                errors.append(f'{name}/{quantity}: Chinese residue')
            if name in EXACT_LABELS and value != EXACT_LABELS[name]:
                errors.append(f'{name}/{quantity}: incorrect contextual label or protected name')
            for term in TECHNICAL_TERMS.get(name, ()):
                if term not in value:
                    errors.append(f'{name}/{quantity}: missing technical identifier {term}')
            original = original_values.get(quantity, original_values.get('other', ''))
            if name.startswith('slash_command_'):
                command = re.match(r'/[a-z_]+\b', original)
                if command and not value.startswith(command.group() + ' '):
                    errors.append(f'{name}/{quantity}: executable slash command changed')
            if '\u200b' in value or '\ufeff' in value:
                errors.append(f'{name}/{quantity}: invisible character in translation')
            normalized = value.replace(r'\n', '\n').strip()
            if (name.endswith('_save') or name == 'groupchat_edit_btn') and normalized == 'Ahorrar':
                errors.append(f'{name}/{quantity}: save action mistranslated as saving money')

            if normalized in {'p.ej.', 'p. ej.', 'p.'}:
                errors.append(f'{name}/{quantity}: unfinished example')
            if DANGLING_STEP.search(normalized) or normalized.endswith(';'):
                errors.append(f'{name}/{quantity}: unfinished sentence or list')
            original = original_values.get(quantity, original_values.get('other', ''))
            if item.get('formatted') != 'false' and sorted(FORMAT.findall(original)) != sorted(FORMAT.findall(value)):
                errors.append(f'{name}/{quantity}: format parameters changed')
    return errors


def check_known_terms(res_dir: Path) -> list[str]:
    entries, _ = catalog(res_dir / 'values-es')
    expected = {
        'settings_provider_anthropic_specific': 'Anthropic',
        'settings_provider_max_output_tokens': 'tokens',
        'settings_assistant_manage': 'asistentes',
        'permission_refresh_status': 'Actualizar estado',
        'settings_assistant_lorebook': 'Libro de trasfondo',
        'settings_bubble_skin_section': 'Diseño',
        'proxy_host': 'Servidor',
        'settings_backup_bucket': 'bucket S3',
        'settings_preset_group_domestic': 'China',
        'memory_screen_retry': 'Reintentar',
    }
    errors = []
    for name, term in expected.items():
        value = ''.join(entries[name].itertext()) if name in entries else ''
        if term.casefold() not in value.casefold():
            errors.append(f'{name}: missing expected term {term}')
    return errors


INTERFACE_MODULES = ("app", "miniphone", "accessibility", "accessibility-provider")


def check_all_modules(project_root: Path) -> list[str]:
    errors = []
    for module in INTERFACE_MODULES:
        res_dir = project_root / module / "src/main/res"
        errors.extend(f"{module}: {error}" for error in check_resources(res_dir))
    return errors


def check_miniphone_terms(project_root: Path) -> list[str]:
    entries, _ = catalog(project_root / "miniphone/src/main/res/values-es")
    expected = {
        "mp_discover_roam": "Paseos",
        "mp_room_entry": "Casa virtual",
        "mp_discover_redpacket": "Regalos virtuales",
        "mp_tab_me": "Mi perfil",
    }
    return [
        f"miniphone/{name}: expected natural Spanish label {term}"
        for name, term in expected.items()
        if name not in entries or "".join(entries[name].itertext()) != term
    ]


def main() -> int:
    res_dir = ROOT / 'app/src/main/res'
    errors = check_all_modules(ROOT) + check_known_terms(res_dir) + check_miniphone_terms(ROOT)
    if errors:
        print('\n'.join(errors), file=sys.stderr)
        return 1
    print('Spanish quality: all 4 interface modules passed coverage, types, placeholders, lists and terminology')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
