#!/usr/bin/env python3
"""Mutation tests: prove the Spanish quality guard rejects damaged catalogs."""
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'script'))
from check_spanish_quality import INTERFACE_MODULES, check_all_modules, check_resources


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
    for value in [r'Configura lo siguiente:\n1.', r'Paso completo\n2. ', 'Desactivado;', 'p.ej.']:
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


def test_all_interface_modules_are_checked():
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        for module in INTERFACE_MODULES:
            for locale, text in [("values", "标题"), ("values-es", "Título")]:
                folder = root / module / "src/main/res" / locale
                folder.mkdir(parents=True)
                (folder / "strings.xml").write_text(f'<resources><string name="title">{text}</string></resources>')
        assert check_all_modules(root) == []
        (root / "miniphone/src/main/res/values-es/strings.xml").write_text('<resources/>')
        errors = check_all_modules(root)
        assert any(error.startswith("miniphone:") and "missing Spanish" in error for error in errors)


def test_save_action_is_not_mistranslated_as_saving_money():
    for label, expected_error in [("Ahorrar", True), ("Guardar", False)]:
        tmp, root = fixture('<string name="action_save">保存</string>', f'<string name="action_save">{label}</string>')
        with tmp:
            assert bool(check_resources(root)) == expected_error


def test_names_and_contextual_actions_cannot_be_literal_translations():
    for name, bad, good in [
        ("proxy_type_socks5", "CALCETINES5", "SOCKS5"),
        ("settings_common_close", "Cerca", "Cerrar"),
        ("settings_common_show", "Espectáculo", "Mostrar"),
        ("settings_provider_category_custom", "Costumbre", "Personalizado"),
        ("settings_asr_provider_openai_whisper", "Susurro", "Whisper"),
        ("settings_asr_provider_agnes", "Inés", "Agnes"),
        ("settings_rag_parser_mineru", "MineroU", "MinerU"),
        ("settings_theme_font_medium", "METRO", "M"),
    ]:
        for value, should_fail in [(bad, True), (good, False)]:
            tmp, root = fixture(f'<string name="{name}">Label</string>', f'<string name="{name}">{value}</string>')
            with tmp:
                assert bool(check_resources(root)) == should_fail, (name, value)


def test_slash_command_remains_executable():
    for value, should_fail in [("/nueva Nueva conversación", True), ("/newer Nueva conversación", True), ("/new Nueva conversación", False)]:
        tmp, root = fixture('<string name="slash_command_new">/new 新对话</string>', f'<string name="slash_command_new">{value}</string>')
        with tmp:
            assert bool(check_resources(root)) == should_fail


def test_documented_model_ids_are_not_translated():
    good = "text-embedding-3-small / text-embedding-3-large / embedding-2"
    for value, should_fail in [("incrustación de texto-3-pequeño", True), (good, False)]:
        tmp, root = fixture('<string name="settings_rag_common_models">Models</string>', f'<string name="settings_rag_common_models">{value}</string>')
        with tmp:
            assert bool(check_resources(root)) == should_fail


if __name__ == '__main__':
    tests = [value for name, value in sorted(globals().items()) if name.startswith('test_')]
    for test in tests:
        test()
    print(f'Spanish quality guard: {len(tests)} mutation tests passed')
