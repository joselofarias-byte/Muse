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
            normalized = value.replace(r'\n', '\n').strip()
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
    }
    errors = []
    for name, term in expected.items():
        value = ''.join(entries[name].itertext()) if name in entries else ''
        if term.casefold() not in value.casefold():
            errors.append(f'{name}: missing expected term {term}')
    return errors


def main() -> int:
    res_dir = ROOT / 'app/src/main/res'
    errors = check_resources(res_dir) + check_known_terms(res_dir)
    if errors:
        print('\n'.join(errors), file=sys.stderr)
        return 1
    print('Spanish quality: coverage, types, placeholders, lists and terminology passed')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
