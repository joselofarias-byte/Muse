#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Generar el manifiesto del modelo propio de Muse (ModelCatalog formato).

## ¿Por qué guardarlo usted mismo?

Anteriormente, esta lista era producto de otras: copie HANA en su totalidad o espere actualizaciones externas.
Cada lado solo cubre la mitad de los hechos. —— HANA Sepa "en qué dialecto se llama esta herramienta y cuáles son los resultados",
models.dev Sepa "cuán largo es el contexto, cuál es el resultado máximo y si comer la imagen o no".
Hay agujeros cuando lo miras por separado, pero está completo cuando lo juntas.

## El papel de los datos bidireccionales

- **esqueleto**:HANA de proveedores. Ese es el conjunto de proveedores que Muse realmente admite.
  Solo viene con toolUse.dialect(openai / anthropic / gemini diferencia).
- **suplemento**:models.dev de limit / modalities / tool_call / reasoning.

Prioridad de campo:HANA La entrada misma > HANA fallbacks > models.dev este canal > models.dev A través de canales.
Motivo: cuanto más cerca esté del hecho "verificado en nuestra ruta de llamada".

## Formato de salida

con `io.zer0.ai.core.ModelCatalog` Alineación:
    { schemaVersion, publishedAt, providers: { pid: { mid: entry } } }
entry Campos:name / context / maxOutput / image / reasoning / toolUse / thinkingLevels

## Uso

    python tools/model-catalog/build_catalog.py \\
        --hana  /path/to/model-catalog.v2.json \\
        --modelsdev /path/to/models_dev_api.json \\
        --out app/src/main/assets/model-catalog.json
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys
from datetime import datetime, timezone

SCHEMA_VERSION = 1

# HANA de provider id → models.dev clave del proveedor.
# Acepte únicamente aquellos que se puedan confirmar: las discrepancias son más difíciles de detectar que las faltantes, y aquellos que no se pueden confirmar preferirían dejarse en blanco y utilizar solo datos de HANA.
PROVIDER_ALIAS = {
    "dashscope": "alibaba",
    "dashscope-coding": "alibaba-coding-plan",
    "dashscope-token-plan": "alibaba-token-plan",
    "fireworks": "fireworks-ai",
    "gemini": "google",
    "kimi-coding": "kimi-code-plan-cn",
    "minimax-token-plan": "minimax-coding-plan",
    "moonshot": "moonshotai",
    "openai-codex-oauth": "openai",
    "together": "togetherai",
    "volcengine-coding": "volcengine-coding-plan",
    "zhipu": "zhipuai",
    "zhipu-coding": "zhipuai-coding-plan",
}


def load(path: pathlib.Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def pick(*values):
    for v in values:
        if v is not None:
            return v
    return None


def bare_id(model_id: str) -> str:
    """Elimine el prefijo del agregador y `:free` Tales sufijos."""
    return model_id.split("/")[-1].split(":")[0]


def build_dev_index(models: dict) -> dict[str, dict]:
    """Múltiples índices dentro de un canal: nombre original / identificación canónica / nombre desnudo."""
    index: dict[str, dict] = {}
    for mid, model in models.items():
        if not isinstance(model, dict):
            continue
        index.setdefault(mid, model)
        canonical = model.get("canonical_model_id")
        if isinstance(canonical, str) and canonical:
            index.setdefault(canonical, model)
        index.setdefault(bare_id(mid), model)
    return index


def lookup(index: dict, model_id: str) -> dict:
    hit = index.get(model_id)
    if hit is not None:
        return hit
    return index.get(bare_id(model_id)) or {}


def build_global_limits(dev: dict) -> dict[str, dict]:
    """Limite la indexación entre canales.

    La salida máxima es un atributo de modelo, no un atributo de canal:openrouter Las entradas generalmente no tienen salida.
    Pero el mismo modelo está disponible en la entrada del canal oficial. Cuando este canal no encuentra el valor, es más efectivo utilizar los valores de otras personas que dejarlos en blanco.
    """
    index: dict[str, dict] = {}
    for provider in dev.values():
        if not isinstance(provider, dict):
            continue
        for mid, model in (provider.get("models") or {}).items():
            if not isinstance(model, dict):
                continue
            limit = model.get("limit") or {}
            keys = {mid, bare_id(mid)}
            canonical = model.get("canonical_model_id")
            if isinstance(canonical, str) and canonical:
                keys.add(canonical)
            for key in keys:
                if not key:
                    continue
                current = index.get(key) or {}
                index[key] = {
                    "context": limit.get("context") or current.get("context"),
                    "output": limit.get("output") or current.get("output"),
                }
    return index


def dev_vision(dev_model: dict) -> bool | None:
    mod = (dev_model.get("modalities") or {}).get("input") or []
    if not isinstance(mod, list):
        return None
    return True if "image" in mod else None


def build_entry(model_id: str, hana_model: dict, fallbacks: dict, dev_index: dict, global_limits: dict) -> dict:
    dev_model = lookup(dev_index, model_id)
    global_limit = lookup(global_limits, model_id)
    fb = fallbacks.get(model_id) or {}
    dev_limit = dev_model.get("limit") or {}

    entry: dict = dict(hana_model)  # Primero, mantenga las entradas de HANA como están, incluidos campos como thinkLevelMap que no hemos modelado.
    name = pick(hana_model.get("name"), dev_model.get("name"), model_id)
    if name:
        entry["name"] = name

    context = pick(hana_model.get("context"), fb.get("context"), dev_limit.get("context"), global_limit.get("context"))
    if context:
        entry["context"] = context

    max_output = pick(
        hana_model.get("maxOutput"),
        fb.get("maxOutput"),
        dev_limit.get("output"),
        global_limit.get("output"),
    )
    if max_output:
        entry["maxOutput"] = max_output

    image = pick(hana_model.get("image"), fb.get("image"), dev_vision(dev_model))
    if image is not None:
        entry["image"] = image

    reasoning = pick(hana_model.get("reasoning"), fb.get("reasoning"), dev_model.get("reasoning"))
    if reasoning is not None:
        entry["reasoning"] = reasoning

    tool_use = hana_model.get("toolUse")
    if isinstance(tool_use, dict):
        entry["toolUse"] = tool_use
    elif dev_model.get("tool_call"):
        entry["toolUse"] = {"supportsTools": True, "dialect": "openai", "toolResultFormat": "message"}

    levels = hana_model.get("thinkingLevels")
    if isinstance(levels, list) and levels:
        entry["thinkingLevels"] = levels
        default_level = hana_model.get("defaultThinkingLevel")
        if isinstance(default_level, str) and default_level:
            entry["defaultThinkingLevel"] = default_level

    compat = hana_model.get("compat")
    if isinstance(compat, dict) and compat:
        entry["compat"] = compat

    return entry


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--hana", required=True)
    ap.add_argument("--modelsdev", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--published-at", default="", help="Anular publicado en; predeterminado a la hora actual")
    args = ap.parse_args()

    hana = load(pathlib.Path(args.hana))
    dev = load(pathlib.Path(args.modelsdev))
    fallbacks = hana.get("fallbacks") or {}
    global_limits = build_global_limits(dev)

    providers: dict[str, dict[str, dict]] = {}
    for provider_id, models in hana.get("providers", {}).items():
        dev_provider = dev.get(PROVIDER_ALIAS.get(provider_id, provider_id)) or {}
        dev_index = build_dev_index(dev_provider.get("models") or {})
        bucket: dict[str, dict] = {}
        for model_id, hana_model in models.items():
            if not isinstance(hana_model, dict):
                continue
            bucket[model_id] = build_entry(model_id, hana_model, fallbacks, dev_index, global_limits)
        providers[provider_id] = bucket

    published = args.published_at or datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
    doc = {"schemaVersion": SCHEMA_VERSION, "publishedAt": published, "providers": providers}

    out = pathlib.Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(doc, ensure_ascii=False, indent=0, separators=(",", ":")), encoding="utf-8")

    total = sum(len(v) for v in providers.values())
    with_ctx = sum(1 for v in providers.values() for e in v.values() if e.get("context"))
    with_out = sum(1 for v in providers.values() for e in v.values() if e.get("maxOutput"))
    with_tools = sum(1 for v in providers.values() for e in v.values() if e.get("toolUse"))
    print(f"generar {out}")
    print(f"  canal {len(providers)} Inicio / Modelo {total} un")
    print(f"  contexto {with_ctx} / salida máxima {with_out} / Especificaciones de la herramienta {with_tools}")
    print(f"  tamaño de archivo {out.stat().st_size // 1024} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())