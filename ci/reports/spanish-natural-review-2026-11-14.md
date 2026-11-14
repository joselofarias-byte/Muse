# Revisión del español — 14 de noviembre de 2026

Esta rama sale de `main` e incorpora el trabajo de `e8eb6c89` (13 de noviembre, «Localize remaining interface literals across Android and web»), que no estaba en `main`. Ese commit vive en `fix/spanish-permissions-review`, 458 commits por delante del ancestro común. No se mezcló esa historia: el default de `main` ya es español y reintroducir el `values/` chino habría deshecho el idioma por defecto. Se tomó el catálogo español ya revisado de ese commit cuando el inglés coincidía, y se añadieron sus recursos, guardas y la página de compatibilidad adaptadas a este árbol.

## Qué se revisó

| Área | Revisado | Cambio |
|---|---:|---|
| Recursos Android `values/` y `values-es/` (cadenas, plural y arreglos) | ~5.590 | ~4.351 textos reemplazados por la redacción ya revisada el 13 de noviembre, más correcciones propias donde el inglés de `main` no coincidía |
| Cadenas nuevas `strings_review_literals.xml` | 73 | Añadidas en español (default y `values-es`) e inglés. Se conectaron galería, recuerdos fijados, marca de tarjeta, compilación, etiqueta de depuración, prueba de visión y la página web de compatibilidad |
| Accesibilidad | 3 | Descripción del servicio y nombre del proveedor |
| Cliente web (`messages.ts`, español) | 275 | Reescritura a español neutro; se actualizaron las aserciones que fijaban la traducción automática |
| Página de compatibilidad del host | 33 | Marcadores localizados; el protocolo (`chat.send`, PIN, rutas) no cambia |
| Prompt `default_persona_es.prompt` | 1 | Reescrito desde el inglés, sin el calco «TA» |
| README y manual | lectura | El arranque de `README.md` ya está en español natural. No se reescribió el manual largo |
| Otros idiomas (`en`, `pt`, `ja`, `ko`, `ru`) | — | Intactos, salvo el inglés de las 73 cadenas nuevas y el pin de paquetes del runtime |

## Glosario

Tratamiento de la interfaz: **tú** e imperativo (`Toca`, `Introduce`, `Prueba`, `Elige`). No se usa usted.

| Concepto | Término |
|---|---|
| Pantalla de ajustes de la app o del sistema | Ajustes |
| Parámetros de un proveedor, VAD o copia | Configuración |
| Chat | Conversación |
| Memoria a largo plazo | Recuerdos |
| Settings (web) | Ajustes |
| Favorite | Favorito |
| Back | Volver |
| Knowledge chunks | Fragmentos |
| System prompt | Instrucciones del sistema |
| Mini phone, cuando el inglés de `main` habla del teléfono embebido | Teléfono virtual |
| Virtual display | Pantalla virtual |
| Nombres de producto | Muse, Shizuku, WeChat, PIN, ZIP, JSON, MCP |

## Pendiente

- Varias cadenas de `strings_review_literals.xml` (pantalla virtual, hilo de subtarea, widget) no tienen pantalla en este `main`: el panel `VirtualDisplayHandoffPanel` existe en `e8eb6c89` y no en esta rama. Quedan como recursos. `UnusedResources` es advertencia, no error.
- `ja` / `ko` / `pt` / `ru` siguen sin unas 250 claves que ya faltaban, más las 73 nuevas. `check_localizations.py` solo falla si falta inglés.
- El manual de usuario y los README largos no se reescribieron frase por frase.
- El empaquetado de Node/ICU/zlib de Termux estaba roto (404 de `nodejs` 26.13.0, `libicu` 78.3, `zlib` 1.3.2 y `npm` 12.2.1). Se fijó a las versiones actuales del índice (Node 26.13.1, ICU 79.1, zlib 1.4, npm 12.2.2). Eso no es una traducción; sin ese cambio el APK de CI no arranca.
