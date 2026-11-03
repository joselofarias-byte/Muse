"""Shared baseline validation helpers for CI gate scripts."""

from pathlib import Path
import sys


def require_baseline(path: Path, checker: str) -> bool:
    if path.is_file():
        return True
    print(f"[{checker}] ERROR: baseline Falta o no un archivo: {path}", file=sys.stderr)
    return False
