#!/usr/bin/env python3
"""Check the compatibility page translations preserve executable JS and routes."""
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'app/src/main/java/io/zer0/muse/web/WebServerUi.kt'


def translated_page(locale):
    code = SOURCE.read_text()
    html = code.split('const val INDEX_HTML: String = """', 1)[1].rsplit('"""', 1)[0]
    labels = re.findall(r'"([^"\n]+)" to R.string.(review_legacy_\d+)', code)
    assert len(labels) == 33
    values = {e.get('name'): ''.join(e.itertext()).replace(r'\u0020', ' ') for e in ET.parse(ROOT / f'app/src/main/res/{locale}/strings_review_literals.xml').getroot()}
    for source, key in labels:
        html = html.replace(source, values[key])
    return html


def test_spanish_compatibility_page_has_no_untranslated_owned_copy():
    html = translated_page('values-es')
    # Strip comments, keeping strings intact; comments may retain developer notes.
    text = re.sub(r'<!--.*?-->|/\*.*?\*/|^\s*//[^\n]*', '', html, flags=re.S | re.M)
    assert not re.search(r'[\u3400-\u9fff]', text)
    for phrase in ['Nueva conversación', 'Proveedor: ', 'No se pudo cargar: ', ' modelos', 'PIN incorrecto']:
        assert phrase in html, phrase


def test_localized_compatibility_scripts_parse_and_keep_protocol():
    original = translated_page('values')
    for locale in ['values', 'values-en', 'values-es']:
        html = translated_page(locale)
        script = html.split('<script>', 1)[1].split('</script>', 1)[0]
        result = subprocess.run(['node', '-e', 'let s="";process.stdin.on("data",b=>s+=b);process.stdin.on("end",()=>new(require("node:vm").Script)(s));'], input=script, capture_output=True, text=True)
        assert result.returncode == 0, result.stderr
        for protocol in ['/api/auth/pin-login', '/api/auth/logout', 'chat.send', 'session.new', 'state.sync', 'httpOnly', 'requestId']:
            assert protocol in html and protocol in original, (locale, protocol)


if __name__ == '__main__':
    tests = [value for name, value in sorted(globals().items()) if name.startswith('test_')]
    for test in tests:
        test()
    print(f'Compatibility web localization: {len(tests)} tests passed (3 script languages)')
