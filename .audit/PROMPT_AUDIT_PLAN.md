# Plan de reparación y revisión del sistema Word rápido

Alcance de la revisión:Muse Todos los recursos de palabras clave del proyecto (assets Plantilla/SystemPromptAssembler/módulo de memoria/en línea para cada subsistema).
Resultado: Lista de reparaciones calificadas + registro de implementación artículo por artículo.

## Contexto: raíces comunes del problema

La palabra clave es un conjunto.**Sin compilador ni guardias de prueba."sistema de sombra"**:
- Escrito a mano tanto en chino como en inglés, depende del esfuerzo consciente de la persona por mantener la coherencia. → a la deriva
- El multilenguaje solo cubre zh/en, la esencia de las plantillas universales=chino → ja/ko/es/pt/ru El usuario toma chino
- Escribe una versión diferente de lo mismo en varios lugares (MOOD, compresión, personalidad) → Definición de conflicto
- Cheque chino codificado `check_hardcoded_cjk.py` Escanear solo `ui/` → Los directorios restantes son áreas ciegas.

## lista calificada

### P0 — Afecta la experiencia del usuario real (solucionado en este lote)

| # | pregunta | ubicación | modificar la ley |
|---|---|---|---|
| P0-1 | Los idiomas distintos del zh/en vuelven al chino | `PromptTemplateLoader.loadTemplate` / `AssistantRepository.loadDefaultPrompt` | Alternativa de configuración regional no china `_en` en lugar de universal(=chino) |
| P0-2 | Chat grupal MOOD y chat individual son dos conjuntos de definiciones (Vibe semánticamente opuesto) | `GroupChatScheduler.GROUP_CHAT_MOOD_SECTION` | Reutilizar chat único `mood_format` Plantilla |
| P0-3 | Subagente fuerza salida china | `SubagentRunner.buildSystemPrompt` | seguir locale |
| P0-4 | Contenido de plantilla de escena incorporado codificado en chino | `PromptTemplate.getBuiltInPromptTemplates` | ir stringResource |
| P0-5 | desaparecido `cover_generation_en.prompt` | assets | Versión suplementaria en inglés |

### P1 — Defectos de consistencia (corregidos en este lote)

| # | pregunta | ubicación | modificar la ley |
|---|---|---|---|
| P1-1 | A las reglas de fechas futuras les falta un chino y un inglés + el tiempo de ejemplo es inconsistente | `FactExtractionPrompt` | Reglas suplementarias en inglés y ejemplos de revisión. |
| P1-2 | Restricciones de uso de la lista/Al rango de exclusión de memoria le falta un chino y un inglés. | `RollingSummaryPrompt` | finalización de inglés |
| P1-3 | El contrato compartido está solo en chino. | `MemoryPromptContract` | Agregar sucursal en ingles |
| P1-4 | decision_tree falta ingles"Esquema de herramientas disponible" | `decision_tree_en.prompt` | completo |
| P1-5 | Conflicto de reglas de emoji de caracteres predeterminados | `PresetCharacters.MUSE` vs `default_persona` | **El juicio no está establecido.**:PresetCharacters Es una biblioteca de caracteres opcional (sin referencias externas) y es un objeto diferente de la personalidad del asistente predeterminada; Las diferencias de emoji entre personajes están diseñadas intencionalmente y no se cambiarán. |
| P1-6 | 8 Diseños de caracteres predeterminados integrados sin localización (chino codificado) | `PresetCharacters` | **siguiente lote**: La función predeterminada es una biblioteca opcional seleccionada activamente por el usuario y no es una ruta predeterminada; el costo de traducir 8 conjuntos de caracteres es alto y la prioridad es menor que la del asistente predeterminado |

### P2 — Proyecto antideriva (reparación en este lote)

| # | pregunta | ubicación | modificar la ley |
|---|---|---|---|
| P2-1 | Escaneo solo de cheques chinos codificados ui/ | `check_hardcoded_cjk.py` | Directorio de análisis ampliado (incluidas las actualizaciones básicas) |
| P2-2 | No hay prueba de coherencia de palabras entre chino e inglés | app/memory test | agregar"Las plantillas existen en pares + el número de entradas es consistente"prueba |

### P3 — Riesgo bajo (registrado, no revisado)

- legacy plantilla (6 ): solo se usa para comparar migración de datos antiguos, archivado normal
- legacy persona "Emoticones/emojis" Deriva terminológica: pequeño impacto
- Dos conjuntos de palabras clave comprimidas (`ConversationCompressor` vs `ContextCompressTransformer`): La semántica es similar y no se fusionará todavía.
- Palabras solicitadas en chino en línea en cada subsistema (lista de herramientas/Workspace/Chat grupal/multiagente/Traducción/Diario, etc.) Internacionalización: gran carga de trabajo, abandono por lotes

## Orden de ejecución

1. P0-1 Estrategia de retroceso (básica, alto apalancamiento)
2. P0-5 suplemento cover_generation_en
3. P0-2 Chat grupal MOOD unificado
4. P0-3 subagente locale
5. P0-4 Internacionalización de plantillas de escenas.
6. P1 Todos
7. P2 Antideriva
8. Control de acceso + embalaje 2.6.0

## Adjunto:genui renderizador de tarjetas (v2.6.0 Reparar esta ronda)

### pregunta

- `show_card` Herramientas y `generative_ui` La palabra de aviso ya existe, pero**El HTML de la tarjeta nunca tuvo una entrada de renderizado.**——Solo herramientas `data` escribe en `CardDataStore`, devolver resumen de texto,`code` Renderizado en ninguna parte.
- Lo que realmente ve el usuario es la tarjeta de herramientas ampliada.**Par clave-valor de parámetro original**(title/code/data).

### modificar la ley

- `ToolCallCard` identificar `show_card`: Los parámetros incluyen `code` Cuando, el área expandida se renderiza directamente `RichContentCard`(Reutilizar WebView + token de tema + puente de tarjeta + limpieza de seguridad), ya no muestra el área de parámetros/Texto del resultado.
- `RichContentCard` Nuevo `inlineCardData`: poner `show_card` de `data` Script en línea para tarjeta `window.muse.getData()`, para evitar la interrupción en la búsqueda de cardId entre mensajes.
- `show_card` Tratar como tarjeta de contenido: no controlado por el interruptor "Mostrar detalles de llamada de herramienta".
- Nuevo `parseShowCardArgs`(Incluyendo prueba única).
- reparar `QuickAction.EDIT`: Visible solo para los mensajes del usuario (la versión anterior es incondicionalmente verdadera y se colgará debajo de los mensajes del asistente).

### Cambiar archivos

- `app/.../ui/MessageBubbleExtras.kt` — show_card Análisis + rama de renderizado en línea
- `app/.../ui/markdown/RichContentCard.kt` — inlineCardData parámetros
- `app/.../ui/MessageBubble.kt` — OnCardAction transparente + exención de cambio de tarjeta de contenido + visibilidad EDITAR
- Prueba:`app/src/test/.../ui/ShowCardArgsTest.kt`

- [x] P0-1 ~ P0-5(Refuerzo en varios idiomas / unificación de MOOD de chat grupal / configuración regional del subagente / internacionalización de plantilla de escena / finalización de cover_en)
- [x] P1-1 ~ P1-4(Reparación de deriva en chino e inglés:FactExtraction / RollingSummary / MemoryPromptContract / decision_tree)
- [x] P2 Prueba anti-deriva (el emparejamiento de plantillas + configuración regional no china no acepta chino)+ memory Prueba Bilingüe Contractual
- [ ] Control de acceso + embalaje (ktlint/detekt/Libro mayor/La prueba unitaria completa ha pasado y se empaquetará)

### Cambiar archivos

- `app/src/main/java/io/zer0/muse/transformer/PromptTemplateLoader.kt` — Los no chinos recurren al inglés
- `app/src/main/java/io/zer0/muse/data/assistant/AssistantRepository.kt` — loadDefaultPrompt Los no chinos recurren al inglés
- `app/src/main/java/io/zer0/muse/schedule/GroupChatScheduler.kt` — Plantilla de chat único de reutilización de MOOD de chat grupal
- `app/src/main/java/io/zer0/muse/tools/SubagentRunner.kt` — Subagente mensaje palabra bilingüe seguir locale
- `app/src/main/java/io/zer0/muse/data/prompttemplate/PromptTemplate.kt` — Texto de plantilla incorporado stringResource
- `app/src/main/java/io/zer0/muse/data/MemoryLlmClientImpl.kt` — Los contratos de memoria se adjuntan por configuración regional.
- `app/src/main/res/values/strings_data.xml` + 6 un locale — 13 Texto de plantilla de artículo key
- `app/src/main/assets/prompt_templates/cover_generation_en.prompt` — Nuevo
- `memory/.../FactExtractionPrompt.kt` — Reglas de fechas futuras en inglés + ejemplos de corrección time
- `memory/.../RollingSummaryPrompt.kt` — Restricciones + exclusiones del roster suplementario en inglés
- `memory/.../MemoryPromptContract.kt` — contrato bilingüe
- `app/src/main/assets/prompt_templates/decision_tree_en.prompt` — Agregar "El esquema de herramienta está disponible"
- Prueba:`PromptTemplateLoaderTest`(se agregaron 6 nuevos)+ `MemoryPromptContractTest`(se agregaron 2 nuevos)

### No hecho (grabado, siguiente lote)

- Internacionalización completa de palabras clave chinas en línea en cada subsistema (lista de herramientas/Workspace/Chat grupal/multiagente/Traducción/diario/Momentos/Visión/RAG etc)
- 8 Localización de diseños de personajes preestablecidos integrados.
- Se fusionan dos conjuntos de palabras clave comprimidas (`ConversationCompressor` vs `ContextCompressTransformer`)
- `check_hardcoded_cjk.py` Expandir a directorios que no son de interfaz de usuario (use la prueba de coincidencia de plantillas como protección en su lugar, motivo: el escaneo completo del directorio explotará una gran cantidad de ruido de palabras internas legales)
- ja/ko/es/pt/ru Excelente traducción de palabras clave en cinco idiomas (actualmente en inglés)
