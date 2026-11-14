#!/usr/bin/env python3
"""Structural Spanish checks for the default catalog and its es mirror.

values/ is the Spanish default. values-es/ must stay a mirror so es-UY does not
fall through to a different wording. These checks catch placeholders, escapes,
plurals, duplicates, slash commands, protected names and known literal
mistranslations. They do not replace reading a string against the screen that uses it.
"""
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FORMAT = re.compile(r'%(?:\d+\$)?[dsf]')
CJK = re.compile(r'[\u3400-\u4dbf\u4e00-\u9fff]')
# Labels verified against the current control, not copied from another branch by key name.
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
    "action_save": "Guardar",
    "settings_common_save": "Guardar",
    "memory_screen_save": "Guardar",
    "memory_screen_retry": "Reintentar",
    "settings_miniphone_title": "Teléfono virtual",
    "home_miniphone_cd": "Teléfono virtual",
    "theme_warm_paper": "Papel cálido",
    "theme_sakura": "Sakura",
    "theme_amber_gold": "Ámbar dorado",
    "review_widget_chat_label": "Conversación",
    "review_pinned_memories_count": "Recuerdos fijados (%1$d)",
}
TECHNICAL_TERMS = {
    "settings_provider_json_parse_failed": ("private_key", "client_email"),
    "settings_rag_common_models": ("text-embedding-3-small", "text-embedding-3-large", "embedding-2"),
    "settings_rag_note3": ("knowledge_search",),
    "settings_rag_markdown_aware": ("Markdown",),
    "permission_termux_config_hint": (
        "allow-external-apps = true",
        "termux-reload-settings",
    ),
}
# RoomPage is not in this tree, so «Casa virtual» is not required here.
KNOWN_TERMS = {
    "settings_provider_anthropic_specific": "Anthropic",
    "settings_provider_max_output_tokens": "tokens",
    "settings_assistant_manage": "asistentes",
    "permission_refresh_status": "Actualizar estado",
    "settings_assistant_lorebook": "Libro de trasfondo",
    "settings_bubble_skin_section": "Diseño",
    "proxy_host": "Servidor",
    "settings_backup_bucket": "bucket S3",
    "settings_preset_group_domestic": "China",
    "data_management_memories": "Recuerdos",
    "settings_search_hint": "ajustes",
}
DANGLING_STEP = re.compile(r'(?:^|\n)\s*(?:\d+\.|[•-])\s*$')


def catalog(folder: Path) -> tuple[dict[str, ET.Element], list[str]]:
    entries: dict[str, ET.Element] = {}
    errors: list[str] = []
    if not folder.is_dir():
        return entries, errors
    for path in sorted(folder.glob('*.xml')):
        for item in ET.parse(path).getroot():
            if item.tag not in {'string', 'plurals', 'string-array'}:
                continue
            if path.name == 'font_certs.xml':
                continue
            name = item.get('name')
            if not name:
                continue
            if name in entries:
                errors.append(f'{folder.name}: duplicate resource {name}')
            entries[name] = item
    return entries, errors


def values_of(item: ET.Element) -> dict[str, str]:
    if item.tag == 'string':
        return {'string': ''.join(item.itertext())}
    return {
        child.get('quantity', str(index)): ''.join(child.itertext())
        for index, child in enumerate(item)
    }


def check_pair(res_dir: Path) -> list[str]:
    source, errors = catalog(res_dir / 'values')
    spanish, duplicates = catalog(res_dir / 'values-es')
    errors += duplicates
    if not source or not spanish:
        errors.append(f'{res_dir}: Spanish default and values-es mirror must both exist')
        return errors
    for name, item in source.items():
        if item.get('translatable') == 'false':
            continue
        translated = spanish.get(name)
        if translated is None:
            errors.append(f'{name}: missing from values-es')
            continue
        if item.tag != translated.tag:
            errors.append(f'{name}: resource type changed')
            continue
        original_values = values_of(item)
        translated_values = values_of(translated)
        if item.tag == 'plurals' and not {'one', 'other'} <= set(translated_values):
            errors.append(f'{name}: Spanish plurals require one and other')
        if item.tag == 'string-array' and len(item) != len(translated):
            errors.append(f'{name}: array length changed')
        for quantity, value in translated_values.items():
            if not value.strip():
                errors.append(f'{name}/{quantity}: empty translation')
            if CJK.search(value):
                errors.append(f'{name}/{quantity}: Chinese residue')
            if name in EXACT_LABELS and value != EXACT_LABELS[name]:
                errors.append(f'{name}/{quantity}: expected {EXACT_LABELS[name]!r}, got {value!r}')
            for term in TECHNICAL_TERMS.get(name, ()):
                if term not in value:
                    errors.append(f'{name}/{quantity}: missing technical identifier {term}')
            if '\u200b' in value or '\ufeff' in value:
                errors.append(f'{name}/{quantity}: invisible character')
            normalized = value.replace(r'\n', '\n').strip()
            if (name.endswith('_save') or name == 'groupchat_edit_btn') and normalized == 'Ahorrar':
                errors.append(f'{name}/{quantity}: save action mistranslated as saving money')
            if normalized in {'p.ej.', 'p. ej.', 'p.'}:
                errors.append(f'{name}/{quantity}: unfinished example')
            if DANGLING_STEP.search(normalized) or normalized.endswith(';'):
                errors.append(f'{name}/{quantity}: unfinished sentence or list')
            original = original_values.get(quantity, original_values.get('string', ''))
            if name.startswith('slash_command_'):
                command = re.match(r'/[a-z0-9_]+\b', original)
                if command and not value.startswith(command.group() + ' '):
                    errors.append(f'{name}/{quantity}: executable slash command changed')
            if item.get('formatted') != 'false' and sorted(FORMAT.findall(original)) != sorted(FORMAT.findall(value)):
                errors.append(f'{name}/{quantity}: format parameters changed')
    for name, term in KNOWN_TERMS.items():
        item = spanish.get(name)
        value = ''.join(item.itertext()) if item is not None else ''
        if term.casefold() not in value.casefold():
            errors.append(f'{name}: missing expected term {term}')
    return errors


def main() -> int:
    errors = check_pair(ROOT / 'app/src/main/res')
    if errors:
        print('\n'.join(errors), file=sys.stderr)
        return 1
    print('Spanish quality: values and values-es match on placeholders, plurals, commands and known labels')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
