#!/usr/bin/env python3
"""Mutation tests: prove the Spanish quality guard rejects damaged catalogs."""
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'script'))
from check_spanish_quality import check_resources


def fixture(source, translated):
    temporary = tempfile.TemporaryDirectory()
    root = Path(temporary.name)
    for locale, filename, body in [('values', 'strings_accessibility.xml', source), ('values-es', 'strings_features.xml', translated)]:
        folder = root / locale
        folder.mkdir()
        (folder / filename).write_text(f'<resources>{body}</resources>', encoding='utf-8')
    return temporary, root


def test_resources_can_move_between_files():
    tmp, root = fixture('<string name="title">标题</string>', '<string name="title">Permisos</string>')
    with tmp:
        assert check_resources(root) == []


def test_missing_translation_fails():
    tmp, root = fixture('<string name="title">标题</string>', '')
    with tmp:
        assert any('missing Spanish' in e for e in check_resources(root))


def test_duplicate_translation_fails():
    tmp, root = fixture('<string name="title">标题</string>', '<string name="title">Permisos</string>')
    with tmp:
        (root / 'values-es/another.xml').write_text('<resources><string name="title">Otro</string></resources>')
        assert any('duplicate' in e for e in check_resources(root))


def test_parameter_identity_and_type_are_preserved():
    for value in ['Resultado: %2$s', 'Resultado: %1$d', 'Resultado']:
        tmp, root = fixture('<string name="result">结果 %1$s</string>', f'<string name="result">{value}</string>')
        with tmp:
            assert any('format parameters' in e for e in check_resources(root))


def test_truncated_list_and_sentence_fail():
    for value in [r'Configura lo siguiente:\n1.', r'Paso completo\n2. ', 'Desactivado;']:
        tmp, root = fixture('<string name="guide">说明</string>', f'<string name="guide">{value}</string>')
        with tmp:
            assert any('unfinished' in e for e in check_resources(root))


def test_chinese_residue_and_empty_translation_fail():
    for value in ['标题', '   ']:
        tmp, root = fixture('<string name="title">标题</string>', f'<string name="title">{value}</string>')
        with tmp:
            assert check_resources(root)


def test_plural_forms_and_resource_types_are_checked():
    source = '<plurals name="count"><item quantity="other">%1$d 项</item></plurals>'
    for translated in [
        '<string name="count">%1$d elementos</string>',
        '<plurals name="count"><item quantity="other">%1$d elementos</item></plurals>',
    ]:
        tmp, root = fixture(source, translated)
        with tmp:
            assert check_resources(root)
    tmp, root = fixture(source, '<plurals name="count"><item quantity="one">%1$d elemento</item><item quantity="other">%1$d elementos</item></plurals>')
    with tmp:
        assert check_resources(root) == []


if __name__ == '__main__':
    tests = [value for name, value in sorted(globals().items()) if name.startswith('test_')]
    for test in tests:
        test()
    print(f'Spanish quality guard: {len(tests)} mutation tests passed')
