#!/usr/bin/env python3
"""Prove display literals are checked without translating logs or comments."""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'script'))
from check_ui_display_literals import violations


def test_direct_ui_literals_are_rejected():
    for source in ['Text("虚拟屏预览")', 'Text(text = "正在读取")', 'contentDescription = "删除"', 'title = "设置"']:
        assert violations(source), source


def test_comments_logs_resources_and_technical_values_are_allowed():
    for source in ['// Text("示例")', '/* title = "示例" */', 'Logger.d("tag", "读取失败")', 'Text(stringResource(R.string.title))', 'Text("SOCKS5")', 'Text("https://example.com")']:
        assert not violations(source), source


def test_string_urls_do_not_hide_following_literals():
    assert violations('val url = "https://example.com"; Text("设置")')


if __name__ == '__main__':
    tests = [value for name, value in sorted(globals().items()) if name.startswith('test_')]
    for test in tests:
        test()
    print(f'UI display literal guard: {len(tests)} tests passed')
