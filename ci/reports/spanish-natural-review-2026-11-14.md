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
| `manual-de-usuario.md`, `guia-biblioteca-ui.md`, `guia-api-de-busqueda.md` | reescritura | Español de tú, mismo glosario que la app. El README ya estaba natural; se afinaron la lista de pendientes y el registro de resultados |
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

## Usos reales de los 73 recursos

No se copió `RoomPage` ni `VirtualDisplayHandoffPanel`: esas clases están en `e8eb6c89` y no en este árbol. Por eso «Casa virtual» no se fuerza: no hay entrada que abra esa habitación. El teléfono embebido que sí existe se llama «Teléfono virtual» (`settings_miniphone_title`, `home_miniphone_cd`).

Conectados a una pantalla de este código: galería de componentes (salvo `review_gallery_19`, que describía una variante que aquí sigue rotulada con el nombre de componente `MuseIsland`), recuerdos fijados, marca de la tarjeta de personaje, variante de compilación, etiqueta de depuración, pregunta de la prueba de visión, los 33 textos de la página de compatibilidad, el título del widget (`review_widget_chat_label` → «Conversación») y el hilo de la subtarea (`review_subagent_thread`).

Sin pantalla en este árbol, y por eso no se inventó una: `review_virtual_display_*`. Los prefijos de reintento (`review_retry_*`) coinciden con el texto que ya escribe `ChatTaskCardCoordinator` y que clasifica `ToolResultJudge`; el coordinador no tiene `Context`, así que no se le inyectó el recurso.

## Firma y versionCode

La release publicada como muse-es.5 usa `versionCode` 1000005 y el certificado permanente `cc8a183c…aac497`. El flujo de APK deja de generar un keystore temporal y restaura `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`. Compila con `versionCode` 1000006 y `versionName` 2.6.25-muse-es.6 para poder instalar encima de muse-es.5.

## Dependencias de Termux

En `tools/runtime-pack/build-runtime-pack.sh` el índice de Termux ya no publica los paquetes que pedía el script y el empaquetado respondía 404. Se fijó, sin cambiar el protocolo:

| Paquete | Antes (404) | Ahora |
|---|---|---|
| nodejs | 26.13.0 | 26.13.1 |
| libicu | 78.3 | 79.1 |
| zlib | 1.3.2 | 1.4 |
| npm | 12.2.1 | 12.2.2 |

Los `.so` de ICU que se renombran pasan a `libicu*.so.79` y `libicu*.so.79.1`. Sin esas versiones el runtime embebido no se descarga y el APK de CI no arranca.

## Pendiente

- `ja` / `ko` / `pt` / `ru` siguen con las claves que ya les faltaban, más las 73 nuevas. `tools:ignore="MissingTranslation"` en `strings_review_literals.xml` evita que lint exija esas traducciones. `check_localizations.py` solo falla si falta inglés.
- No hay `RoomPage` ni panel de pantalla virtual en este `main`. Esas traducciones de `e8eb6c89` no se aplican.
- El recorrido en emulador y la comprobación del secreto de firma dependen de que el entorno los tenga. Si no están, el informe final lo dice y no los da por aprobados.
