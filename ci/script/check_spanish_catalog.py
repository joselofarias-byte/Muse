#!/usr/bin/env python3
"""Validate Spanish coverage and catch format damage and known translation regressions.

This is a structural check, not a claim of editorial or device validation.
"""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

RES = Path(__file__).resolve().parents[2] / 'app/src/main/res'
FORMAT = re.compile(r'%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]')
REGRESSION = re.compile(
    r'[\u3400-\u9fff]|Discapacitado\s+skill|\bgolpear\b|\bSomatosensorial\b|'
    r'\bLoading conversation\b|\bNo conversation history yet\b|'
    r'provinciaToken|soloAgentsesi[oó]n|\bpalabra de aviso\b', re.I,
)


def catalog(folder):
    result = {}
    for path in sorted((RES / folder).glob('*.xml')):
        for entry in ET.parse(path).getroot():
            if entry.tag not in {'string', 'plurals', 'string-array'}:
                continue
            if entry.get('translatable') == 'false' or entry.attrib['name'].startswith('com_google_'):
                continue
            name = entry.attrib['name']
            children = list(entry) if entry.tag != 'string' else [entry]
            for index, child in enumerate(children):
                key = name if entry.tag == 'string' else f'{name}[{child.get("quantity", index)}]'
                if key in result:
                    raise ValueError(f'{folder}: duplicate {key}')
                result[key] = ''.join(child.itertext())
    return result


def validate(default, spanish, english):
    errors = []
    if default != spanish:
        errors.append('Default and Spanish catalogs differ')
    if {k.split('[')[0] for k in spanish} != {k.split('[')[0] for k in english}:
        errors.append('Spanish and English resource keys differ')
    for key, value in spanish.items():
        if sorted(FORMAT.findall(value)) != sorted(FORMAT.findall(english.get(key, english.get(key.split('[')[0] + '[other]', '')))):
            errors.append(f'{key}: format arguments differ')
        if not value.strip() or REGRESSION.search(value):
            errors.append(f'{key}: empty text or translation regression')
    return errors


if __name__ == '__main__':
    errors = validate(catalog('values'), catalog('values-es'), catalog('values-en'))
    if errors:
        raise SystemExit('\n'.join(errors))
    print('Spanish: coverage, default parity, format arguments and known residues PASS')
