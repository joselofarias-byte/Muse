#!/usr/bin/env python3
"""
check_ktlint_report.py — P4-1 Aserción de control de acceso.

fondo: AGP 9 La compatibilidad integrada con Kotlin hace que los enlaces del conjunto de fuentes de Android se rompan en klint-gradle 12.1.1,
ktlintCheck Sólo comprobar .kts(verde falso). root build.gradle.kts para autoconstrucción ktlintKotlinSourceCheck
Tarea,Utilice klint CLI para escanear las fuentes Kotlin de cada módulo,y escribe
build/reports/ktlint/ktlintKotlinSourceCheck.txt(Contiene @linted-file-count=<N> OK).

Este guión afirma: El informe para cada módulo debe existir, no estar vacío y haber registrado fielmente la cantidad de archivos .kt que se eliminaron esta vez.
(N > 0) —— De lo contrario, significa que el control de acceso se degradará y se ejecutará nuevamente en vacío.,Fallo directo.

correr: py -3 ci/script/check_ktlint_report.py
"""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# P4-1: Módulo que se ha conectado a klintKotlinSourceCheck(Consistente con la lista de complementos raíz build.gradle.kts)
MODULES = ["app", "common", "memory", "ai", "material3", "accessibility"]


def linted_file_count(report_text: str) -> int | None:
    """en el informe de análisis @linted-file-count=<N> OK,Devuelve Ninguno si falta."""
    for line in report_text.splitlines():
        line = line.strip()
        if line.startswith("@linted-file-count="):
            try:
                return int(line.split("=", 1)[1])
            except ValueError:
                return None
    return None


def check_module(module: str) -> list[str]:
    """Informe del módulo de lista de verificación,Lista de mensajes de error de devolución(lista vacía = Pase)."""
    errors: list[str] = []
    report = ROOT / module / "build" / "reports" / "ktlint" / "ktlintKotlinSourceCheck.txt"
    if not report.exists():
        errors.append(f"{module}: ktlint Informe faltante {report.relative_to(ROOT)} — ktlintKotlinSourceCheck No ejecutado,El control de acceso se degrada a ejecución vacía")
        return errors
    text = report.read_text(encoding="utf-8", errors="replace")
    if not text.strip():
        errors.append(f"{module}: ktlint Informar como archivo vacío {report.relative_to(ROOT)} — No se puede probar pelusa real en la fuente de Kotlin")
        return errors
    count = linted_file_count(text)
    if count is None:
        errors.append(f"{module}: Informe faltante @linted-file-count= OK — No se ha demostrado que sobrescriba ningún archivo fuente .kt")
    elif count <= 0:
        errors.append(f"{module}: @linted-file-count={count} — La cantidad de archivos .kt eliminados por lint es 0,El control de acceso no tiene sentido")
    return errors


def main() -> int:
    all_errors: list[str] = []
    for module in MODULES:
        all_errors.extend(check_module(module))
    if all_errors:
        print("P4-1 ktlint Error en la aserción del control de acceso:")
        for err in all_errors:
            print(f"  - {err}")
        return 1
    print(f"P4-1 ktlint Se aprobó la afirmación de control de acceso: {len(MODULES)} Todos los informes del módulo no están vacíos y registran la cantidad real de archivos lint.")
    return 0


if __name__ == "__main__":
    sys.exit(main())