#!/usr/bin/env python3
"""Reject Chinese literals passed directly to Kotlin UI display APIs.

Logs, model prompts, parsers and technical identifiers are not translated by this
check. Expressions and strings assembled indirectly still need manual review.
"""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
TOKENS = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|/\*[\s\S]*?\*/|//[^\n]*')
DIRECT = re.compile(r'(?:\bText\s*\(\s*(?:text\s*=\s*)?|\b(?:text|contentDescription|title|placeholder)\s*=\s*)"((?:\\.|[^"\\])*)"')
CJK = re.compile(r'[\u3400-\u9fff]')


def violations(source: str) -> list[tuple[int, str]]:
    def mask(match: re.Match) -> str:
        value = match.group()
        return re.sub(r'[^\n]', ' ', value) if value.startswith(('//', '/*')) else value
    code = TOKENS.sub(mask, source)
    return [(code.count('\n', 0, m.start()) + 1, m.group(1)) for m in DIRECT.finditer(code) if CJK.search(m.group(1))]


def main() -> int:
    checked = 0
    errors = []
    for path in sorted(ROOT.glob('*/src/main/**/*.kt')):
        # Only display layers: server payloads, prompts and parsers can use text/title as data keys.
        if '/ui/' not in path.as_posix() and '/widget/' not in path.as_posix():
            continue
        checked += 1
        for line, text in violations(path.read_text()):
            errors.append(f'{path.relative_to(ROOT)}:{line}: hardcoded display copy: {text}')
    if errors:
        print('\n'.join(errors), file=sys.stderr)
        return 1
    print(f'UI display literals: {checked} Kotlin files checked; no direct Chinese display literals')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
