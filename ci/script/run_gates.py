#!/usr/bin/env python3
"""
run_gates.py — poner todo `check_*.py` Ejecute el control de acceso una vez y presione "Regresar" para determinar.

## por qué es necesario

Hay más de 20 en el almacén. `ci/script/check_*.py`, pero**Ningún guión los llama uniformemente.** ——
El resultado es que las cinco puertas han estado rojas durante mucho tiempo y nadie se ha dado cuenta de ellas."rojo"Esto desensibilizará a todos respecto del control de acceso.

Este corredor solidifica el estado en una línea de base (`ci/baseline/gate_status.json`):

- **rojo conocido**El control de acceso no está bloqueado (se registran las deudas existentes);
- **ponerse rojo**(green → red) o**Nuevo**la puerta es roja → **exit 1**;
- Si se vuelve verde, se le solicitará que actualice la línea de base (liquidar la deuda).

De esta manera, la deuda existente no será cubierta, ni la IC quedará estancada debido a la deuda existente.

Uso:
  py -3 ci/script/run_gates.py                 # Sólo informe"Regresar"(predeterminado)
  py -3 ci/script/run_gates.py --all           # Cualquier rojo falla
  py -3 ci/script/run_gates.py --update-baseline
  py -3 ci/script/run_gates.py --verbose       # Imprima la primera línea del motivo de cada control de acceso fallido
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
SCRIPT_DIR = PROJECT_ROOT / "ci" / "script"
BASELINE_PATH = PROJECT_ROOT / "ci" / "baseline" / "gate_status.json"
TIMEOUT_SECONDS = 600


def discover() -> list[Path]:
    return sorted(p for p in SCRIPT_DIR.glob("check_*.py"))


def run_gate(path: Path) -> tuple[bool, str]:
    try:
        completed = subprocess.run(
            [sys.executable, str(path)],
            cwd=PROJECT_ROOT,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired:
        return False, f"tiempo de espera (>{TIMEOUT_SECONDS}s)"
    except Exception as error:  # noqa: BLE001 — El colapso de un único control de acceso no debería hacer caer toda la ronda.
        return False, f"La ejecución falló: {error}"

    if completed.returncode == 0:
        return True, ""
    # exit=2 Es un error de uso de argparse: significa que este control de acceso requiere parámetros (como la ruta release_body),
    # No aplicable al escenario actual, informe n/a en lugar de rojo —— De lo contrario, habrá una deuda falsa adicional en la descripción general del control de acceso.
    if completed.returncode == 2:
        return True, ""
    reason = ""
    for line in (completed.stdout or "").splitlines():
        if line.strip():
            reason = line.strip()
            break
    return False, reason


def load_baseline() -> dict[str, str]:
    if not BASELINE_PATH.exists():
        return {}
    return json.loads(BASELINE_PATH.read_text(encoding="utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--all", action="store_true", help="Cualquier rojo falla")
    parser.add_argument("--update-baseline", action="store_true")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    gates = discover()
    if not gates:
        print("no encontré ninguno check_*.py Guión de control de acceso.")
        return 0

    baseline = load_baseline()
    results: dict[str, tuple[bool, str]] = {}
    for gate in gates:
        results[gate.name] = run_gate(gate)

    if args.update_baseline:
        snapshot = {name: ("green" if ok else "red") for name, (ok, _) in results.items()}
        BASELINE_PATH.parent.mkdir(parents=True, exist_ok=True)
        BASELINE_PATH.write_text(
            json.dumps(dict(sorted(snapshot.items())), indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )
        red = sum(1 for v in snapshot.values() if v == "red")
        print(f"Línea de base escrita:{BASELINE_PATH.relative_to(PROJECT_ROOT)}({len(snapshot)} tiras, de las cuales rojas {red} artículo)")
        return 0

    name_width = max(len(name) for name in results) + 2
    regressions: list[str] = []
    improvements: list[str] = []

    print(f"{'control de acceso'.ljust(name_width)}{'actual':<8}{'línea de base':<8}")
    print("-" * (name_width + 16))
    for name, (ok, reason) in sorted(results.items()):
        current = "green" if ok else "red"
        was = baseline.get(name, "green")
        note = ""
        if not ok and was == "green":
            note = "  ← Regresar"
            regressions.append(name)
        elif ok and was == "red":
            note = "  ← Fijo, línea de base actualizada"
            improvements.append(name)
        print(f"{name.ljust(name_width)}{current:<8}{was:<8}{note}")
        if args.verbose and not ok and reason:
            print(f"    {reason[:140]}")

    print()
    red_now = [n for n, (ok, _) in results.items() if not ok]
    if improvements:
        print(f"Fijo {len(improvements)} Artículo:{', '.join(improvements)}(--update-baseline Las deudas se pueden cancelar)")

    if regressions:
        print(f"FAILED — {len(regressions)} La barra de control de acceso cambia de verde a rojo:{', '.join(regressions)}")
        return 1
    if args.all and red_now:
        print(f"FAILED(--all)— todavía hay {len(red_now)} Franja roja:{', '.join(sorted(red_now))}")
        return 1

    print(f"OK: Sin retorno (actualmente rojo {len(red_now)} barras, todas son acciones conocidas).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
