#!/usr/bin/env python3
"""
check_translation_residue.py — I18N-02 Comprobación residual CJK del paquete de idioma distinto del chino.

Utilice análisis XML (en lugar de "<string name="x"> deben estar inmediatamente adyacentes entre sí) para escanear cada
values-<locale>/ Entradas de cadena que contienen CJK en el paquete de idioma, cuente el número de entradas y compárelas con la línea base
ci/translation_residue_baseline.json Comparación: si es superior a la línea base, falla (solo disminuye pero no aumenta).
Con atributos como formato/herramientas <string> No más falsos negativos (antiguo script de PowerShell
scripts/i18n_cjk_guard.ps1 defecto, el script ha sido descartado y eliminado con esta rectificación).

Calibre (escrito en el guión para evitar que el segundo guión diga cosas diferentes):
  - Objeto de escaneo: El camino parece values-<Idioma>[-r<área>] directorio (en/es/ko/pt-rBR/ru…)
    Todo .xml; entradas bajo = <string>, y <plurals>/<string-array> adentro <item>
    (No se omite ningún valor de cadena visible para el usuario).
  - excluir valores (paquete predeterminado = Fuente china, debería ser china).
  - Excluir valores-ja: los caracteres chinos japoneses se encuentran legalmente en el rango de caracteres ideográficos unificados de CJK y el rango de caracteres no se puede comparar con los caracteres simplificados.
    Distinción china, por lo que no hay intercepción regular (ja Los restos de ja se dejan a los revisores de la traducción; actualmente ja todavía tiene uno
    settings_agent_send_probability_hint Está en chino, ver informe de rectificación).
  - excluir zh* Paquetes de idiomas y directorios de calificadores puros (values-v31 etcétera).
  - Sólo cuenta "valor": El contenido de texto del elemento (XML El análisis cubre naturalmente cualquier método de escritura de atributos);
    XML Los comentarios no son elementos y, naturalmente, no se cuentan.
  - translatable="false" Las entradas no se computarán (no intervendrán en la traducción y se permitirá conservar el texto original).
  - CJK Intervalo de decisión: [\\u4e00-\\u9fff\\u3400-\\u4dbf](Mismo dominio que check_hardcoded_cjk.py).
  - Cada entrada cuenta solo una vez (por número de entradas, no por número de caracteres).

mecanismo de referencia:
  - La comparación predeterminada es ci/translation_residue_baseline.json (el número de cada paquete de idioma, actualmente todos 0).
  - Los nuevos paquetes de idiomas que son superiores a la línea de base o están fuera de la línea de base parecen ser residuales → fallido.
  - Falta el archivo de referencia = falló(fail closed),Evite eliminar líneas base para evitar el control de la puerta.
  - --update-baseline Reescribir la línea de base; rechace escribir entradas de "aumento" de forma predeterminada, requiera --allow-increase.
  - Después de completar la traducción, mantenga la línea base en 0 (en lugar de aumentarla) y la puerta será real.

Uso:
  py -3 ci/script/check_translation_residue.py
  py -3 ci/script/check_translation_residue.py --baseline <path>
  py -3 ci/script/check_translation_residue.py --update-baseline
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
RES_REL = Path("app") / "src" / "main" / "res"
BASELINE_REL = Path("ci") / "translation_residue_baseline.json"

LOCALE_DIR_RE = re.compile(r"^values-([a-z]{2,3}(?:-r[A-Z]{2})?)$")
CJK_RE = re.compile(r"[\u4e00-\u9fff\u3400-\u4dbf]")
EXCLUDED_LOCALES = frozenset({"ja"})
EXCLUDED_LOCALE_PREFIXES = ("zh",)
STRING_CONTAINERS = ("plurals", "string-array")


@dataclass(frozen=True)
class Residue:
    """Un remanente de CJK.file ¿Es la ruta relativa posix en res/."""

    locale: str
    file: str
    name: str
    value: str


def locale_of(dir_name: str) -> str | None:
    """values-en → en;values-pt-rBR → pt-rBR;Los directorios no escaneados devuelven Ninguno."""
    m = LOCALE_DIR_RE.match(dir_name)
    if not m:
        return None
    locale = m.group(1)
    if locale in EXCLUDED_LOCALES or locale.startswith(EXCLUDED_LOCALE_PREFIXES):
        return None
    return locale


def iter_string_entries(root: ET.Element):
    """salida (name, text):<string> y dentro de su contenedor <item>,saltar translatable="false"."""
    for el in root.iter():
        if el.tag == "string":
            if el.get("translatable") == "false":
                continue
            yield (el.get("name") or "?"), "".join(el.itertext())
        elif el.tag in STRING_CONTAINERS:
            if el.get("translatable") == "false":
                continue
            for item in el.findall("item"):
                quantity = item.get("quantity")
                base = el.get("name") or "?"
                yield (f"{base}[{quantity}]" if quantity else base), "".join(item.itertext())


def scan_res(root: Path) -> dict[str, list[Residue]]:
    """Escanee todos los paquetes de idiomas marcados,Regresar locale → lista residual(Paquete de idiomas con 0 artículos)."""
    res_dir = root / RES_REL
    if not res_dir.is_dir():
        raise FileNotFoundError(f"El directorio de recursos no existe: {res_dir}")
    found: dict[str, list[Residue]] = {}
    for d in sorted(res_dir.glob("values-*")):
        if not d.is_dir():
            continue
        locale = locale_of(d.name)
        if locale is None:
            continue
        hits: list[Residue] = []
        for xml in sorted(d.glob("*.xml")):
            try:
                tree = ET.parse(xml)
            except ET.ParseError as e:
                # El error de análisis debe fallar y salir:Saltar en silencio hará que el control de acceso se ponga verde
                raise ValueError(f"{xml} El análisis falló: {e}") from e
            for name, text in iter_string_entries(tree.getroot()):
                if CJK_RE.search(text):
                    hits.append(
                        Residue(
                            locale=locale,
                            file=f"{d.name}/{xml.name}",
                            name=name,
                            value=text.strip()[:80],
                        )
                    )
        found[locale] = hits
    return found


def load_baseline(path: Path) -> dict:
    """Leer línea de base JSON;No existe y regresa vacío. dict(Equivalente a "solo informar sin interceptación")."""
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def evaluate(current: dict[str, list[Residue]], baseline: dict) -> tuple[list[str], list[str]]:
    """Comparar el residuo actual con el valor inicial,Regresar (failures, improvements) Dos grupos leen el mensaje."""
    failures: list[str] = []
    improvements: list[str] = []
    base = {k: int(v) for k, v in (baseline.get("locales") or {}).items()}

    for locale in sorted(current):
        hits = current[locale]
        allowed = base.get(locale, 0)
        if len(hits) > allowed:
            new_locale = " (Paquetes de idiomas no básicos)" if locale not in base else ""
            failures.append(
                f"  {locale}: actual {len(hits)} Artículo > línea de base {allowed} Artículo{new_locale}"
            )
            for h in hits[:20]:
                failures.append(f"      {h.file}: {h.name} = {h.value}")
            if len(hits) > 20:
                failures.append(f"      … el resto {len(hits) - 20} Artículo omitido,Disponible: vista detallada")
        elif len(hits) < allowed:
            improvements.append(f"  {locale}: {allowed} → {len(hits)}")

    for locale in sorted(set(base) - set(current)):
        improvements.append(f"  {locale}: Paquete de idioma eliminado")

    return failures, improvements


def update_baseline(
    path: Path, current: dict[str, list[Residue]], allow_increase: bool
) -> int:
    """Sobrescribir la línea de base con los resultados del análisis actual;Denegar escritura de entradas ascendentes por defecto(Solo cayendo pero no subiendo)."""
    old = load_baseline(path)
    old_locales = {k: int(v) for k, v in (old.get("locales") or {}).items()}
    increases = [
        f"  {locale}: {old_locales.get(locale, 0)} → {len(hits)}"
        for locale, hits in sorted(current.items())
        if len(hits) > old_locales.get(locale, 0)
    ]
    if increases and old and not allow_increase:
        print("[translation-residue] Negarse a responder: Los siguientes paquetes de idiomas están por encima de la línea base existente(Solo cayendo pero no subiendo)")
        for line in increases:
            print(line)
        print("Primero traduzca estos elementos al idioma local.;Si realmente necesita una exención, agregue explícitamente --allow-increase y explique el motivo.")
        return 1

    payload = {
        "note": (
            "I18N-02 Línea de base residual CJK del paquete de idioma distinto del chino. solo se permite descender: por encima de este documento o fuera de la línea base"
            "Los paquetes de idiomas restantes fallan. Ejecutar después de completar la traducción. "
            "py -3 ci/script/check_translation_residue.py --update-baseline apretar"
            "(Denegar escritura de valor ascendente por defecto,necesidad --allow-increase)."
        ),
        "measure": "Número de entradas de cadena/elemento de plurales/elemento de matriz de cadena que contienen CJK en cada paquete de idioma",
        "excluded": ["values(Predeterminado/fuente china)", "values-ja(Los kanji japoneses son legales)", "values-zh*(variante china)", "values-vNN(puro calificador)"],
        "locales": {locale: len(hits) for locale, hits in sorted(current.items())},
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    total = sum(len(v) for v in current.values())
    print(f"[translation-residue] Línea base actualizada: {path} ({len(current)} paquetes de idiomas / total {total} Artículo)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="I18N-02 Comprobación residual CJK del paquete de idioma distinto del chino")
    parser.add_argument("--root", type=Path, default=PROJECT_ROOT, help="raíz del proyecto(Pruebas disponibles fixture)")
    parser.add_argument("--baseline", type=Path, default=None, help="Ruta JSON de referencia")
    parser.add_argument("--update-baseline", action="store_true", help="Sobrescribir la línea de base con los resultados del análisis actual")
    parser.add_argument("--allow-increase", action="store_true", help="Permitir la escritura de valores crecientes al actualizar la línea base")
    parser.add_argument("--verbose", action="store_true", help="Imprima los detalles de cada residuo")
    args = parser.parse_args()
    baseline_path = args.baseline or (args.root / BASELINE_REL)

    try:
        current = scan_res(args.root)
    except (FileNotFoundError, ValueError) as e:
        print(f"[translation-residue] Error de escaneo: {e}")
        return 1

    total = sum(len(v) for v in current.values())
    excluded_note = "excluir values(fuente china) / values-ja(kanji japonés) / values-zh*, "
    print(
        f"[translation-residue] Escanear {RES_REL.as_posix()} ({excluded_note}"
        f"bajo inspección {len(current)} paquete de idiomas): total {total} residuo de CJK"
    )
    if args.verbose:
        for locale, hits in sorted(current.items()):
            for h in hits:
                print(f"  {locale}  {h.file}: {h.name} = {h.value}")

    if args.update_baseline:
        return update_baseline(baseline_path, current, args.allow_increase)

    baseline = load_baseline(baseline_path)
    if not baseline:
        # Línea de base faltante = Control de acceso no válido,debe fallar(La eliminación del archivo de referencia no se puede utilizar como una omisión.)
        print(f"[translation-residue] FAILED — Línea base no encontrada {baseline_path};"
              f"generar: --update-baseline")
        return 1

    base = baseline.get("locales") or {}
    pairs = ", ".join(
        f"{locale} {len(current.get(locale, []))}/{int(base.get(locale, 0))}"
        for locale in sorted(set(current) | set(base))
    )
    print(f"[translation-residue] actual/línea de base: {pairs}")

    failures, improvements = evaluate(current, baseline)
    if improvements:
        print("[translation-residue] hay mejora,Se recomienda ajustar --update-baseline:")
        for line in improvements:
            print(line)
    if failures:
        print("[translation-residue] FAILED — Aparecen restos de CJK en paquetes en idiomas distintos del chino:")
        for line in failures:
            print(line)
        print("como solucionarlo: Traducir elementos a valores en el idioma correspondiente.(values-<locale>/strings*.xml);"
              "No utilice --allow-increase para aumentar la línea de base.")
        return 1
    print(f"[translation-residue] PASS — Todos los paquetes de idiomas probados tienen recuentos residuales de 0 o dentro del valor inicial (total {total} Artículo)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
