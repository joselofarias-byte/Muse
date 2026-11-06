#!/usr/bin/env python3
"""
pr_check.py — PR Clasificación de alcance y enrutamiento de carriles.

Cambiar la ruta del archivo según PR,Clasificados en diferentes alcances de inspección.(scope),Genera el carril en el que debe ejecutarse la salida.
GitHub Actions Controle qué trabajos se ejecutan realmente a través de la salida,Evite los PR que solo contienen documentación que activan compilaciones de Android.

clasificación del alcance:
  - localization: values-* Cambios en el archivo de recursos
  - android_resources: res/ Cambios en recursos como XML/dibujable en
  - android_jvm: Kotlin/Java Cambios en el código fuente(Requiere compilación + prueba única)
  - android_full: build.gradle/kts o cambios manifiestos(Necesito estar completo assemble)
  - script: ci/script/ Cambios en el script Python en
  - docs: Sólo cambios en archivos .md(Sólo una revisión rápida)
  - config: gradle/config Cambios de archivos

Uso:
  # prueba local(Comparar CABEZA con main)
  py -3 ci/script/pr_check.py --base origin/main

  # CI utilizado en(GitHub Actions)
  py -3 ci/script/pr_check.py --base origin/${{ github.base_ref }} --output json

salida(JSON):
  {
    "scopes": ["localization", "android_jvm"],
    "lanes": {
      "quick_checks": true,
      "localization": true,
      "android_resources": false,
      "android_jvm": true,
      "android_full": false,
      "script": false
    },
    "changed_files": 42,
    "classification": {"android_jvm": 15, "localization": 5, "docs": 22}
  }
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]

# ── reglas de clasificación de alcance ──────────────────────────────────────────────────────────

# ruta del archivo → Reglas de coincidencia de alcance(Ordenar por prioridad de mayor a menor)
SCOPE_RULES = [
    # localization: values-* Archivos de recursos en el directorio.
    (
        "localization",
        lambda p: (
            "/values-" in p
            or p.startswith("app/src/main/res/values-")
            or (p.startswith("app/src/main/res/values/") and p.endswith(".xml"))
        ),
    ),
    # script: ci/ Scripts en el directorio
    ("script", lambda p: p.startswith("ci/")),
    # android_full: Configuración de compilación o cambios de manifiesto
    (
        "android_full",
        lambda p: (
            p.endswith("build.gradle.kts")
            or p.endswith("build.gradle")
            or p.endswith("AndroidManifest.xml")
            or p.startswith("gradle/")
            or p == "settings.gradle.kts"
            or p == "gradle.properties"
        ),
    ),
    # android_resources: res/ archivos de recursos bajo(no values-* XML/dibujable, etc.)
    (
        "android_resources",
        lambda p: (
            ("/res/" in p and p.endswith(".xml"))
            or ("/res/" in p and any(p.endswith(ext) for ext in (".png", ".webp", ".jpg", ".svg")))
            or "/res/" in p
        ),
    ),
    # android_jvm: Kotlin/Java código fuente
    (
        "android_jvm",
        lambda p: p.endswith(".kt") or p.endswith(".java"),
    ),
    # docs: Markdown Archivo
    ("docs", lambda p: p.endswith(".md")),
    # config: Archivo de configuración
    (
        "config",
        lambda p: any(
            p.endswith(ext)
            for ext in (".properties", ".toml", ".yml", ".yaml", ".editorconfig", ".gitignore")
        ),
    ),
]


def classify_file(filepath: str) -> str:
    """Clasifique las rutas de archivos individuales en ámbitos."""
    for scope, matcher in SCOPE_RULES:
        if matcher(filepath):
            return scope
    # Los archivos no coincidentes se clasifican como "other"
    return "other"


def get_changed_files(base: str) -> list[str]:
    """Obtenga una lista de archivos modificados en relación con la rama base."""
    try:
        result = subprocess.run(
            ["git", "diff", "--name-only", f"{base}...HEAD"],
            capture_output=True,
            text=True,
            cwd=PROJECT_ROOT,
            check=True,
        )
        files = [f.strip() for f in result.stdout.strip().split("\n") if f.strip()]
        return files
    except subprocess.CalledProcessError as e:
        print(f"Error getting changed files: {e.stderr}", file=sys.stderr)
        sys.exit(1)
    except FileNotFoundError:
        print("Error: git not found in PATH", file=sys.stderr)
        sys.exit(1)


def compute_lanes(scopes: set[str]) -> dict[str, bool]:
    """Calcula el carril que se debe ejecutar en función de la colección de alcance."""
    # quick_checks Siempre corriendo(Inspección sanitaria + disciplina de ingeniería.)
    lanes = {
        "quick_checks": True,
        "localization": "localization" in scopes,
        "android_resources": "android_resources" in scopes or "android_full" in scopes,
        "android_jvm": "android_jvm" in scopes or "android_full" in scopes,
        "android_full": "android_full" in scopes,
        "script": "script" in scopes,
    }
    return lanes


def main() -> int:
    parser = argparse.ArgumentParser(description="PR Clasificación de alcance y trazado de carriles")
    parser.add_argument(
        "--base",
        default="origin/main",
        help="Comparar rama base(Predeterminado: origin/main)",
    )
    parser.add_argument(
        "--output",
        choices=["json", "text"],
        default="text",
        help="Formato de salida(Predeterminado: text)",
    )
    parser.add_argument(
        "--files",
        help="Pasar directamente la lista de archivos(separados por nueva línea),saltar git diff",
    )
    args = parser.parse_args()

    # Obtener archivos de cambios
    if args.files:
        changed_files = [f.strip() for f in args.files.split("\n") if f.strip()]
    else:
        changed_files = get_changed_files(args.base)

    if not changed_files:
        if args.output == "json":
            print(json.dumps({
                "scopes": [],
                "lanes": {"quick_checks": True, "localization": False, "android_resources": False,
                          "android_jvm": False, "android_full": False, "script": False},
                "changed_files": 0,
                "classification": {},
            }, indent=2))
        else:
            print("No changed files detected.")
        return 0

    # Clasificación
    classification: dict[str, int] = defaultdict(int)
    scopes: set[str] = set()
    for filepath in changed_files:
        scope = classify_file(filepath)
        classification[scope] += 1
        scopes.add(scope)

    # calcular Lane
    lanes = compute_lanes(scopes)

    # salida
    if args.output == "json":
        result = {
            "scopes": sorted(scopes),
            "lanes": lanes,
            "changed_files": len(changed_files),
            "classification": dict(classification),
        }
        print(json.dumps(result, indent=2))
    else:
        print(f"Changed files: {len(changed_files)}")
        print(f"\nScope classification:")
        for scope in sorted(classification.keys()):
            print(f"  {scope}: {classification[scope]} files")
        print(f"\nLanes to run:")
        for lane, should_run in lanes.items():
            status = "RUN" if should_run else "skip"
            print(f"  [{status}] {lane}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
