#!/usr/bin/env python3
"""Mutation tests for the Spanish catalog guard."""
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'script'))
from check_spanish_quality import check_pair


def fixture(source: str, translated: str):
    temporary = tempfile.TemporaryDirectory()
    root = Path(temporary.name)
    for locale, body in (('values', source), ('values-es', translated)):
        folder = root / locale
        folder.mkdir()
        (folder / 'strings.xml').write_text(f'<resources>{body}</resources>', encoding='utf-8')
    # Known terms are required whenever the pair is otherwise valid.
    known = ''.join(
        f'<string name="{name}">{value}</string>'
        for name, value in {
            'settings_provider_anthropic_specific': 'Anthropic',
            'settings_provider_max_output_tokens': 'tokens',
            'settings_assistant_manage': 'asistentes',
            'permission_refresh_status': 'Actualizar estado',
            'settings_assistant_lorebook': 'Libro de trasfondo',
            'settings_bubble_skin_section': 'Diseño',
            'proxy_host': 'Servidor',
            'settings_backup_bucket': 'bucket S3',
            'settings_preset_group_domestic': 'China',
            'data_management_memories': 'Recuerdos',
            'settings_search_hint': 'Buscar en los ajustes',
        }.items()
    )
    for locale in ('values', 'values-es'):
        path = root / locale / 'known.xml'
        path.write_text(f'<resources>{known}</resources>', encoding='utf-8')
    return temporary, root


def test_mirror_can_live_in_another_file():
    tmp, root = fixture('<string name="title">Permisos</string>', '<string name="title">Permisos</string>')
    with tmp:
        assert check_pair(root) == []


def test_missing_mirror_fails():
    tmp, root = fixture('<string name="title">Permisos</string>', '')
    with tmp:
        assert any('missing from values-es' in error for error in check_pair(root))


def test_duplicate_fails():
    tmp, root = fixture('<string name="title">Permisos</string>', '<string name="title">Permisos</string>')
    with tmp:
        (root / 'values-es/another.xml').write_text(
            '<resources><string name="title">Otro</string></resources>',
            encoding='utf-8',
        )
        assert any('duplicate' in error for error in check_pair(root))


def test_placeholder_and_plural_shape():
    tmp, root = fixture(
        '<string name="result">Resultado %1$s</string>',
        '<string name="result">Resultado %2$s</string>',
    )
    with tmp:
        assert any('format parameters' in error for error in check_pair(root))
    tmp, root = fixture(
        '<plurals name="count"><item quantity="one">%d</item><item quantity="other">%d</item></plurals>',
        '<plurals name="count"><item quantity="other">%d</item></plurals>',
    )
    with tmp:
        assert any('plurals require one and other' in error for error in check_pair(root))


def test_unfinished_copy_and_money_save_fail():
    for value in [r'Configura lo siguiente:\n1.', 'Desactivado;', 'p.ej.', 'Ahorrar']:
        name = 'guide_save' if value == 'Ahorrar' else 'guide'
        tmp, root = fixture(
            f'<string name="{name}">Completo</string>',
            f'<string name="{name}">{value}</string>',
        )
        with tmp:
            errors = check_pair(root)
            assert errors, value


def test_slash_command_stays_executable():
    tmp, root = fixture(
        '<string name="slash_command_new">/new Nueva conversación</string>',
        '<string name="slash_command_new">Nueva conversación</string>',
    )
    with tmp:
        assert any('slash command' in error for error in check_pair(root))


def test_protected_name_and_technical_token():
    tmp, root = fixture(
        '<string name="proxy_type_socks5">SOCKS5</string>',
        '<string name="proxy_type_socks5">Calcetines</string>',
    )
    with tmp:
        assert any('proxy_type_socks5' in error for error in check_pair(root))
    tmp, root = fixture(
        '<string name="permission_termux_config_hint">allow-external-apps = true &amp;&amp; termux-reload-settings</string>',
        '<string name="permission_termux_config_hint">abre Termux</string>',
    )
    with tmp:
        assert any('termux-reload-settings' in error for error in check_pair(root))


if __name__ == '__main__':
    test_mirror_can_live_in_another_file()
    test_missing_mirror_fails()
    test_duplicate_fails()
    test_placeholder_and_plural_shape()
    test_unfinished_copy_and_money_save_fail()
    test_slash_command_stays_executable()
    test_protected_name_and_technical_token()
    print('test_spanish_quality: ok')
