"""CI Control de acceso: prohíbe pasar % de literales de marcador de posición como parámetros de formato para recursos de cadena.

fondo (v2.6.2 Accidente en línea):
    stringResource(R.string.settings_sticker_deleted_toast, "%1$d")
`stringResource(id, args...)` Dentro esta `Resources.getString(id, args...)`,
será ejecutado inmediatamente `String.format`. La plantilla de recursos es `Eliminado %1$d emoticones`(%1$d a un número entero),
El parámetro real es un literal de cadena. "%1$d" → lanzar durante el tiempo de ejecución
`IllegalFormatConversionException: d != java.lang.String`, todo el período de combinación de páginas falla.

Este tipo de escritura es completamente legal.lint Ni el compilador ni el compilador pueden detectarlo, y solo explota durante el período de combinación de la máquina real.
Si la intención original es "obtener primero el texto original de la plantilla y luego reemplazarlo más tarde", debe utilizar**sin ginseng** `stringResource(id)`.

Este script solo reconoce cierta forma de error "el primer parámetro real es % literal" para evitar falsos positivos.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java"
RES = ROOT / "app/src/main/res"

# Nombre del recurso -> Texto original de la plantilla (solo toma valores/idioma predeterminado)
STR_RE = re.compile(r'<string\s+name="([^"]+)"[^>]*>(.*?)</string>', re.S)
strings: dict[str, str] = {}
for f in RES.glob("values/*.xml"):
    text = f.read_text(encoding="utf-8", errors="ignore")
    for m in STR_RE.finditer(text):
        strings.setdefault(m.group(1), m.group(2))

INT_SPEC = re.compile(r"%(?:\d+\$)?[dix]")

# R.string.xxx , "%1$d" ...   (El primer argumento es el % literal)
BAD_CALL = re.compile(
    r"(?:stringResource|getString)\(\s*(?:androidx\.compose\.ui\.res\.)?R\.string\.(\w+)\s*,\s*\"%\d*\$?[sdifx]"
)

problems: list[str] = []
for kt in JAVA.rglob("*.kt"):
    src = kt.read_text(encoding="utf-8", errors="ignore")
    for m in BAD_CALL.finditer(src):
        name = m.group(1)
        template = strings.get(name, "")
        # Cuando no hay un marcador de posición de número entero en la plantilla, es legal pasar un literal de cadena (por ejemplo, tool_alarm_set es todo %s).
        if not INT_SPEC.search(template):
            continue
        line = src.count("\n", 0, m.start()) + 1
        end = src.find("\n", m.start())
        frag = src[m.start(): end if end > 0 else len(src)]
        problems.append(
            f"{kt.relative_to(ROOT)}:{line}\n"
            f"    Recursos {name} = {template!r}\n"
            f"    {frag.strip()}"
        )

if problems:
    print("Llamadas encontradas que usan % literales como parámetros de formato (se bloquearán durante el ensamblaje):\n")
    for p in problems:
        print(p)
    print("\nSolución: eliminar parámetros —— stringResource(R.string.xxx)(Sin parámetros, sin formato, vuelve al texto de la plantilla original)")
    sys.exit(1)

print("OK: % literal no encontrado al llamar con argumento de formato")
