#!/usr/bin/env python3
"""
test_font_scale_clipping.py — check_font_scale_clipping.py pruebas unitarias.

correr: py -3 ci/test/test_font_scale_clipping.py
"""

import json
import sys
import tempfile
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parents[1] / "script"
sys.path.insert(0, str(SCRIPT_DIR))

from check_font_scale_clipping import (  # noqa: E402
    MIN_RISK_DP,
    evaluate,
    scan_roots,
    update_baseline,
)

UI_REL = "app/src/main/java/io/zer0/muse/ui"

# El método de escritura para cortar palabras: altura fija + cuerpo del contenedor. Text
RISKY = """
@Composable
fun Card() {
    Box(modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text("Título")
    }
}
"""

# Modificado:heightIn(min=) La altura garantizada permanece sin cambios pero aumenta con el tamaño de la fuente.
SAFE_HEIGHT_IN = """
@Composable
fun Card() {
    Box(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text("Título")
    }
}
"""

# Altura fija de tamaño pequeño:icono/Nivel de punto, no lleva texto
SAFE_SMALL = """
@Composable
fun Dot() {
    Box(modifier = Modifier.height(16.dp)) { Text("x") }
}
"""

# Spacer:el espaciado no lleva texto
SAFE_SPACER = """
@Composable
fun Gap() {
    Spacer(Modifier.height(48.dp))
    Text("A continuación se muestra el texto del componente adyacente.")
}
"""

# Divisor de línea delgada: 0,5 dp de ancho + alto fijo, no lleva texto
SAFE_DIVIDER = """
@Composable
fun Hairline() {
    Box(modifier = Modifier.width(0.5.dp).height(40.dp).background(Color.Gray))
    Text("texto de componentes adyacentes")
}
"""

# Contenedor de medios: la altura la determina Canvas y no aumenta con el tamaño de fuente.
SAFE_MEDIA = """
@Composable
fun Chart() {
    Box(modifier = Modifier.fillMaxWidth().height(100.dp)) {
        val cd = stringResource(R.string.chart_cd)
        Canvas(modifier = Modifier.fillMaxSize().semantics { contentDescription = cd }) {
            drawLine(Color.Red, Offset.Zero, Offset(10f, 10f))
        }
    }
}
"""

# El texto de los componentes adyacentes no está incluido en el texto del cuerpo del contenedor (el texto está detrás de la ventana multimedia y a una altura fija). >12 bien)
SAFE_NEIGHBOUR_TEXT = """
@Composable
fun Slider() {
    Box(modifier = Modifier.weight(1f).height(40.dp)) {
        detectHorizontalDragGestures(onDragStart = { }, onDragEnd = { }, onDragCancel = { }, onHorizontalDrag = { _, _ -> })
    }
    Canvas(modifier = Modifier.fillMaxSize()) { drawCircle(Color.Red) }
    Text("Descripción del control deslizante")
}
"""


# Altura ilimitadaIn + fillMaxSize niño: el niño solicitará la altura máxima de los padres(1.3x Un recorrido por el incidente real en la barandilla superior de la isla.)
RISKY_UNBOUNDED = """
@Composable
fun Island() {
    Surface(modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
        Box(modifier = Modifier.fillMaxSize()) { Text("Muse") }
    }
}
"""

# Hay un límite superior para la altura In: no crecerá indefinidamente.,forma correcta de escribir
SAFE_BOUNDED = """
@Composable
fun Capsule() {
    Surface(modifier = Modifier.heightIn(min = 48.dp, max = 72.dp)) {
        Box(modifier = Modifier.fillMaxSize()) { Text("Tasks") }
    }
}
"""


def make_root(files: dict[str, str]) -> Path:
    root = Path(tempfile.mkdtemp())
    ui = root / UI_REL
    ui.mkdir(parents=True)
    for name, src in files.items():
        (ui / name).write_text(src, encoding="utf-8")
    return root


def counts(files: dict[str, str]) -> dict[str, int]:
    return {s.file: s.occurrences for s in scan_roots(make_root(files)) if s.occurrences}


def test_risky_fixed_height_on_text_is_counted():
    got = counts({"Card.kt": RISKY})
    assert got == {f"{UI_REL}/Card.kt": 1}, got


def test_height_in_is_not_counted():
    assert counts({"Card.kt": SAFE_HEIGHT_IN}) == {}


def test_small_fixed_height_is_not_counted():
    assert MIN_RISK_DP >= 32
    assert counts({"Dot.kt": SAFE_SMALL}) == {}


def test_spacer_is_not_counted():
    assert counts({"Gap.kt": SAFE_SPACER}) == {}


def test_hairline_divider_is_not_counted():
    assert counts({"Hairline.kt": SAFE_DIVIDER}) == {}


def test_media_container_is_not_counted():
    assert counts({"Chart.kt": SAFE_MEDIA}) == {}


def test_neighbour_text_is_not_counted():
    assert counts({"Slider.kt": SAFE_NEIGHBOUR_TEXT}) == {}


def test_comments_do_not_count():
    src = """
@Composable
fun Card() {
    // Box(modifier = Modifier.height(64.dp)) { Text("x") }
    Box(modifier = Modifier.fillMaxWidth())
}
"""
    assert counts({"Card.kt": src}) == {}


def test_missing_ui_dir_fails_closed():
    root = Path(tempfile.mkdtemp())
    try:
        scan_roots(root)
    except FileNotFoundError:
        return
    raise AssertionError("UI Debe lanzarse cuando falta el directorio FileNotFoundError(No hay paso en silencio)")


def test_evaluate_flags_new_file_and_total_increase():
    root = make_root({"Card.kt": RISKY})
    current = [s for s in scan_roots(root) if s.occurrences]
    failures, _ = evaluate(current, {"files": {}, "totals": {"occurrences": 0}, "min_risk_dp": MIN_RISK_DP})
    assert any("Agregar nuevo archivo" in f for f in failures), failures


def test_evaluate_detects_rule_drift():
    root = make_root({"Card.kt": RISKY})
    current = [s for s in scan_roots(root) if s.occurrences]
    failures, _ = evaluate(current, {"files": {}, "totals": {"occurrences": 0}, "min_risk_dp": 99})
    assert any("El calibre de identificación ha cambiado." in f for f in failures), failures


def test_update_baseline_refuses_increase():
    root = make_root({"Card.kt": RISKY})
    current = [s for s in scan_roots(root) if s.occurrences]
    path = Path(tempfile.mkdtemp()) / "baseline.json"
    path.write_text(json.dumps({"files": {}, "totals": {"occurrences": 0}}), encoding="utf-8")
    rc = update_baseline(path, current, allow_increase=False)
    assert rc == 1, "Se debe rechazar el aumento de la línea de base"
    assert json.loads(path.read_text(encoding="utf-8"))["files"] == {}, "La línea de base no debe reescribirse en caso de rechazo"


def test_update_baseline_writes_and_round_trips():
    root = make_root({"Card.kt": RISKY})
    current = [s for s in scan_roots(root) if s.occurrences]
    path = Path(tempfile.mkdtemp()) / "baseline.json"
    assert update_baseline(path, current, allow_increase=False) == 0
    payload = json.loads(path.read_text(encoding="utf-8"))
    assert payload["totals"]["occurrences"] == 1
    assert payload["files"] == {f"{UI_REL}/Card.kt": 1}
    failures, _ = evaluate(current, payload)
    assert failures == [], failures



def test_unbounded_height_in_with_fill_max_size_is_counted():
    scans = {x.file: x.unbounded for x in scan_roots(make_root({"Island.kt": RISKY_UNBOUNDED}))}
    assert scans == {f"{UI_REL}/Island.kt": 1}, scans


def test_bounded_height_in_is_not_counted():
    scans = {x.file: x.unbounded for x in scan_roots(make_root({"Capsule.kt": SAFE_BOUNDED}))}
    assert scans == {}, scans


TESTS = [v for k, v in sorted(globals().items()) if k.startswith("test_")]

if __name__ == "__main__":
    failed = 0
    for fn in TESTS:
        try:
            fn()
        except Exception as e:  # noqa: BLE001
            failed += 1
            print(f"FAIL {fn.__name__}: {type(e).__name__}: {e}")
        else:
            print(f"PASS {fn.__name__}")
    print("all tests passed" if not failed else f"{failed} test(s) failed")
    sys.exit(1 if failed else 0)
