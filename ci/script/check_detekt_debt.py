#!/usr/bin/env python3
"""
check_detekt_debt.py — P4-2 detekt Aseveración del techo de deuda.

El número total de entradas CurrentIssues en detekt-baseline.xml de cada módulo debe estar dentro de ci/detekt_debt_cap.json
Dentro del límite total registrado,Y ningún módulo individual no debe exceder el límite superior de su nivel de módulo.(valla doble,Se prohíben nuevas incorporaciones.).
Después de la limpieza por lotes: Regenerar el módulo correspondiente. baseline → Ajustar manualmente el valor del archivo cap DOWN,Y explicar qué limpiar.
Nunca está permitido simplemente ajustar la tapa hacia ARRIBA sin limpiarla.(Entonces el control de acceso será sólo una decoración.).

correr: py -3 ci/script/check_detekt_debt.py [--root ROOT] [--cap CAPFILE]
"""

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

DEFAULT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CAP = Path(__file__).resolve().parents[1] / "detekt_debt_cap.json"


def count_baseline(baseline_file: Path) -> int:
    """Número de CurrentIssues de referencia de detección de estadísticas;Falla si falta una estructura o no es válida."""
    if not baseline_file.is_file():
        raise FileNotFoundError(f"desaparecido detekt baseline: {baseline_file}")
    tree = ET.parse(baseline_file)
    current = tree.getroot().find("CurrentIssues")
    if current is None:
        raise ValueError(f"detekt baseline desaparecido CurrentIssues: {baseline_file}")
    return len(current)


def check(root: Path, cap_file: Path) -> list[str]:
    errors: list[str] = []
    cap = json.loads(cap_file.read_text(encoding="utf-8"))
    total_cap = int(cap["total"])
    module_caps = {k: int(v) for k, v in cap.get("modules", {}).items()}

    actual: dict[str, int | str] = {}
    total = 0
    for module in sorted(module_caps.keys()):
        baseline = root / module / "detekt-baseline.xml"
        if not baseline.is_file():
            actual[module] = "MISSING"
            errors.append(f"{module}: desaparecido detekt baseline ({baseline})")
            continue
        n = count_baseline(baseline)
        actual[module] = n
        total += n
        module_cap = module_caps.get(module)
        if module_cap is not None and n > module_cap:
            errors.append(
                f"{module}: línea de base {n} Artículo > Límite de módulo {module_cap} Artículo —— Se prohíben nuevas incorporaciones.,Limpiar por lotes primero"
            )

    if total > total_cap:
        errors.append(f"detekt deuda total {total} Artículo > límite superior {total_cap} Artículo —— Se prohíben nuevas incorporaciones.")

    print(f"detekt Medición de la deuda: {total} Artículo (Cada módulo {actual})")
    if errors:
        return errors
    print(f"en {total_cap} dentro del límite superior,control de acceso")
    return []


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=str(DEFAULT_ROOT))
    parser.add_argument("--cap", default=str(DEFAULT_CAP))
    args = parser.parse_args()
    errors = check(Path(args.root), Path(args.cap))
    if errors:
        print("P4-2 detekt Fracasa la afirmación del techo de deuda:")
        for err in errors:
            print(f"  - {err}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
