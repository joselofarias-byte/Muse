#!/usr/bin/env python3
"""
check_release_body_bilingual.py — release_body Verificación de especificaciones de escritura tanto en chino como en inglés.

normativo(Desde v2.3.0): `releases/v<versión>/release_body.md` Debe estar escrito tanto en chino como en inglés. —
Párrafo anterior en chino(Publicar texto original), linea independiente `---` separar, Próximo párrafo en inglés., El título está en la forma. `# Muse vX.Y.Z (English)`.
El inglés es una traducción fiel., No introducir información que no esté incluida en el párrafo chino., No más caracteres chinos en el párrafo.

Este script verifica(Ejecutar antes de publicar):
  1. El archivo existe, y existir `(English)` Título del párrafo en inglés en el formulario.;
  2. Hay una línea separada antes del título del párrafo en inglés. `---` delimitador;
  3. El segmento chino contiene chino y no está vacío.;
  4. El párrafo en inglés contiene contenido en inglés y no incluye caracteres chinos.

Uso:
    python ci/script/check_release_body_bilingual.py releases/v2.3.0/release_body.md

código de salida:
    0 — Pase
    1 — no cumple con las especificaciones(CI/Error en la comprobación previa a la publicación)
"""

import argparse
import re
import sys
from pathlib import Path

# Título de la sección en inglés.: 1~3 título de nivel, a (English) finalizando
ENGLISH_HEADING_RE = re.compile(r"^#{1,3}\s+.*\(English\)\s*$", re.MULTILINE)
# caracteres chinos(CJK Área básica ideográfica unificada)
CJK_RE = re.compile(r"[\u4e00-\u9fff]")
# Características del contenido en inglés.(3 o más letras latinas seguidas)
LATIN_RE = re.compile(r"[A-Za-z]{3,}")
# delimitador(linea independiente)
SEPARATOR = "---"

# Excepciones permitidas en párrafos en inglés.(Muy pocas abreviaturas originales que deben conservarse./nombre propio;todos son ASCII, Deje este espacio en blanco para usarlo más adelante.)
ALLOWED_CJK_IN_ENGLISH: tuple[str, ...] = ()


def check(path: Path) -> list[str]:
    """Realizar verificación, Volver a la lista de preguntas(lista vacía = Pase)."""
    if not path.exists():
        return [f"El archivo no existe: {path}"]
    try:
        text = path.read_text(encoding="utf-8")
    except Exception as e:  # noqa: BLE001 - Guión de verificación, Conversión unificada a información de fallas
        return [f"Error de lectura: {e}"]

    match = ENGLISH_HEADING_RE.search(text)
    if match is None:
        return ["Falta el título de la sección en inglés.(debería ser `# Muse vX.Y.Z (English)` forma, linea independiente)"]

    zh_part = text[: match.start()]
    en_part = text[match.end():]

    problems: list[str] = []

    # 2. delimitador: Los títulos de los párrafos en inglés deben ir precedidos de una línea separada. ---
    zh_lines = [line.strip() for line in zh_part.splitlines()]
    if SEPARATOR not in zh_lines:
        problems.append("Falta una línea separada antes del título del párrafo en inglés `---` delimitador")

    # 3. sección china
    if not CJK_RE.search(zh_part):
        problems.append("El segmento chino está vacío o faltan caracteres chinos.")

    # 4. sección en inglés
    if not LATIN_RE.search(en_part):
        problems.append("El párrafo en inglés está vacío o carece de contenido en inglés.")
    cjk_in_en = CJK_RE.findall(en_part)
    if cjk_in_en:
        for ch in set(cjk_in_en):
            if ch not in ALLOWED_CJK_IN_ENGLISH:
                problems.append(f"Párrafo en inglés intercalado con caracteres chinos `{ch}`(El párrafo en inglés debe ser una traducción pura al inglés.)")
                break

    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description="release_body Verificación de especificaciones de doble escritura en chino e inglés.")
    parser.add_argument("path", type=Path, help="release_body.md camino, Como por ejemplo releases/v2.3.0/release_body.md")
    args = parser.parse_args()

    problems = check(args.path)
    if problems:
        print(f"FAIL: {args.path} No cumple con las especificaciones de escritura dual en chino e inglés.:")
        for p in problems:
            print(f"  - {p}")
        return 1

    print(f"PASS: {args.path} Formato bilingüe completo en chino e inglés.(Segmento chino + delimitador + segmento inglés)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
