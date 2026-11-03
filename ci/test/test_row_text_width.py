#!/usr/bin/env python3
"""
test_row_text_width.py — check_row_text_width.py pruebas unitarias.

correr: py -3 ci/test/test_row_text_width.py
"""

import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parents[1] / "script"
sys.path.insert(0, str(SCRIPT_DIR))

from check_row_text_width import scan_file  # noqa: E402

# Contraejemplo:Row Dentro de los dos subpuntos, el texto no se amplía. → Al ampliar el tamaño de la fuente, se comprimirá en una columna.
RISKY = """
@Composable
fun Row(row: Server) {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = row.name,
            style = MaterialTheme.typography.bodyLarge,
        )
        MuseSwitch(checked = row.enabled, onCheckedChange = {})
    }
}
"""

# Corregido: texto agregado weight(1f) Obtener el ancho restante
SAFE_WEIGHT = """
@Composable
fun Row(row: Server) {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = row.name)
            Text(text = row.url, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        MuseSwitch(checked = row.enabled, onCheckedChange = {})
    }
}
"""

# Corregido: una sola línea de texto + maxLines para cubrir toda la historia (no se extenderá en docenas de líneas)
SAFE_MAXLINES = """
@Composable
fun Row(row: Server) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(text = row.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        MuseIcon(row.icon)
    }
}
"""

# Revisado: cambiado a "amplio y restringido" → seguro
SAFE_WEIGHT_AND_LINES = """
@Composable
fun Row(row: Server) {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = row.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        MuseSwitch(checked = row.enabled, onCheckedChange = {})
    }
}
"""

# Tome solo el ancho, sin límite de línea: cuando el ancho restante es extremadamente pequeño, aún se ajustará palabra por palabra para hacer que toda la línea sea más alta. → Todavía necesito maxLines para saber la verdad.
RISKY_WEIGHT_ONLY = """
@Composable
fun Row(row: Server) {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = row.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        MuseSwitch(checked = row.enabled, onCheckedChange = {})
    }
}
"""

# Fila de un solo hijo y el texto no se amplía explícitamente:Row Al hijo único se le dará el ancho máximo, sin competencia entre hermanos. → Sin juicio
SINGLE_CHILD_UNBOUNDED = """
@Composable
fun Row(row: Server) {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = row.name,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
"""

# Texto en el parámetro de ranura (TextField etiqueta) no es un hijo directo de la línea y no se juzga
SAFE_SLOT_LAMBDA = """
@Composable
fun Row() {
    Row(modifier = Modifier.fillMaxWidth()) {
        MuseTextField(
            label = { Text("Nombre") },
            value = name,
            onValueChange = {},
        )
        MuseSwitch(checked = true, onCheckedChange = {})
    }
}
"""

# Anidamiento:Row Hay texto en la columna interior.Text No es un hijo directo de Row pero toma el ancho del padre → Sin juicio
SAFE_NESTED_COLUMN = """
@Composable
fun Row() {
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "Título")
        }
        MuseSwitch(checked = true, onCheckedChange = {})
    }
}
"""

# Row Método de escritura entre líneas: después del salto de línea del parámetro `) {` Iniciar una nueva línea
RISKY_MULTILINE = """
@Composable
fun Row(row: Server) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = row.name,
            style = MaterialTheme.typography.bodyLarge,
        )
        Icon(imageVector = MuseIcons.x, contentDescription = null)
    }
}
"""

CASES = [
    ("risky_no_width_no_lines", RISKY, 1),
    ("risky_weight_only_no_lines", RISKY_WEIGHT_ONLY, 1),
    ("safe_single_child_no_competition", SINGLE_CHILD_UNBOUNDED, 0),
    ("safe_weight_with_lines", SAFE_WEIGHT, 0),
    ("safe_maxlines_one_liner", SAFE_MAXLINES, 0),
    ("safe_weight_and_lines", SAFE_WEIGHT_AND_LINES, 0),
    ("safe_slot_lambda", SAFE_SLOT_LAMBDA, 0),
    ("safe_nested_column", SAFE_NESTED_COLUMN, 0),
    ("risky_multiline_row", RISKY_MULTILINE, 1),
]


def main() -> int:
    import tempfile

    failed = 0
    with tempfile.TemporaryDirectory() as tmp:
        for name, source, expected in CASES:
            path = Path(tmp) / f"{name}.kt"
            path.write_text(source, encoding="utf-8")
            hits = scan_file(path)
            ok = len(hits) == expected
            status = "OK  " if ok else "FAIL"
            print(f"[{status}] {name}: Expectativa {expected} en, real {len(hits)} en")
            if not ok:
                failed += 1
                for ln, text in hits:
                    print(f"        L{ln}: {text[:90]}")
    if failed:
        print(f"\n{failed} casos de uso fallidos")
        return 1
    print(f"\nTodos {len(CASES)} casos de uso pasados")
    return 0


if __name__ == "__main__":
    sys.exit(main())
