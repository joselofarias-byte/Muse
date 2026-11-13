# Revisión del español — 13 de noviembre de 2026

Base: `e443788f1e651dbc2348502d389644847ba5fa29`. Se corrigieron **537 recursos en 20 archivos** en esta revisión. El TSV adjunto conserva cada clave y su texto antes y después.

## Alcance

Lectura del catálogo español por archivo: 6.614 entradas de app (6.304), miniphone (307), accessibility (1) y accessibility-provider (2). El recuento incluye recursos compuestos y entradas no traducibles; no equivale al número de frases de la interfaz. Se contrastaron con el original los nombres técnicos y los casos de significado ambiguo.

Se corrigieron acciones traducidas literalmente, nombres de servicios y protocolos, identificadores en ejemplos, comandos, textos truncados, tono de tratamiento, ayudas, estados y mensajes de error. Se unificó «stickers» en el catálogo del chat.

## Verificación

- Comprobaciones de los cuatro módulos: cobertura, XML, duplicados, tipos de recurso, plurales, placeholders, restos de chino y listas incompletas.
- 12 pruebas de mutación de calidad y 7 pruebas del comprobador de localizaciones aprobadas.
- Comprobación de argumentos de recursos y de residuos de traducción aprobadas. Localizaciones estrictas en/es sin claves faltantes.
- Pruebas Android de regresión ampliadas para nombres técnicos, etiquetas y contenido de la guía de memoria. No ejecutadas en este entorno.
- Sin compilación Android ni recorrido visual en emulador/dispositivo en esta revisión. La revisión textual no valida recortes, escala de fuente ni todas las combinaciones de estado.

## Archivos corregidos

| Archivo | Recursos |
|---|---:|
| `app/src/main/res/values-es/strings.xml` | 13 |
| `app/src/main/res/values-es/strings_agent_directives.xml` | 4 |
| `app/src/main/res/values-es/strings_ai_memory.xml` | 14 |
| `app/src/main/res/values-es/strings_audit_templates.xml` | 2 |
| `app/src/main/res/values-es/strings_chat_core.xml` | 59 |
| `app/src/main/res/values-es/strings_code_gaps.xml` | 1 |
| `app/src/main/res/values-es/strings_data.xml` | 5 |
| `app/src/main/res/values-es/strings_debug.xml` | 12 |
| `app/src/main/res/values-es/strings_errors.xml` | 4 |
| `app/src/main/res/values-es/strings_features.xml` | 50 |
| `app/src/main/res/values-es/strings_notif_schedule.xml` | 9 |
| `app/src/main/res/values-es/strings_services.xml` | 30 |
| `app/src/main/res/values-es/strings_settings_main.xml` | 50 |
| `app/src/main/res/values-es/strings_settings_misc.xml` | 50 |
| `app/src/main/res/values-es/strings_settings_sub.xml` | 113 |
| `app/src/main/res/values-es/strings_tools.xml` | 13 |
| `app/src/main/res/values-es/strings_translate.xml` | 12 |
| `app/src/main/res/values-es/strings_ui_main.xml` | 77 |
| `app/src/main/res/values-es/strings_ui_misc.xml` | 13 |
| `app/src/main/res/values-es/strings_ui_viewmodel.xml` | 6 |

## Inventario leído

- `app/src/main/res/values-es/automation_strings.xml`: 39 entradas.
- `app/src/main/res/values-es/automation_tasker_strings.xml`: 35 entradas.
- `app/src/main/res/values-es/strings.xml`: 238 entradas.
- `app/src/main/res/values-es/strings_agent_directives.xml`: 16 entradas.
- `app/src/main/res/values-es/strings_ai_memory.xml`: 190 entradas.
- `app/src/main/res/values-es/strings_audit_memory_groups.xml`: 10 entradas.
- `app/src/main/res/values-es/strings_audit_schedule_formula.xml`: 5 entradas.
- `app/src/main/res/values-es/strings_audit_settings.xml`: 30 entradas.
- `app/src/main/res/values-es/strings_audit_states.xml`: 16 entradas.
- `app/src/main/res/values-es/strings_audit_templates.xml`: 50 entradas.
- `app/src/main/res/values-es/strings_chat_core.xml`: 553 entradas.
- `app/src/main/res/values-es/strings_code_gaps.xml`: 15 entradas.
- `app/src/main/res/values-es/strings_code_gaps_more.xml`: 88 entradas.
- `app/src/main/res/values-es/strings_data.xml`: 96 entradas.
- `app/src/main/res/values-es/strings_debug.xml`: 70 entradas.
- `app/src/main/res/values-es/strings_doc_preview.xml`: 3 entradas.
- `app/src/main/res/values-es/strings_errors.xml`: 113 entradas.
- `app/src/main/res/values-es/strings_features.xml`: 850 entradas.
- `app/src/main/res/values-es/strings_gallery.xml`: 16 entradas.
- `app/src/main/res/values-es/strings_greeting_generation.xml`: 1 entradas.
- `app/src/main/res/values-es/strings_localization_gaps.xml`: 5 entradas.
- `app/src/main/res/values-es/strings_notif_schedule.xml`: 126 entradas.
- `app/src/main/res/values-es/strings_onboarding.xml`: 54 entradas.
- `app/src/main/res/values-es/strings_services.xml`: 434 entradas.
- `app/src/main/res/values-es/strings_settings_browser.xml`: 16 entradas.
- `app/src/main/res/values-es/strings_settings_main.xml`: 488 entradas.
- `app/src/main/res/values-es/strings_settings_misc.xml`: 300 entradas.
- `app/src/main/res/values-es/strings_settings_sub.xml`: 803 entradas.
- `app/src/main/res/values-es/strings_settings_websearch.xml`: 10 entradas.
- `app/src/main/res/values-es/strings_terminal.xml`: 10 entradas.
- `app/src/main/res/values-es/strings_tools.xml`: 201 entradas.
- `app/src/main/res/values-es/strings_translate.xml`: 70 entradas.
- `app/src/main/res/values-es/strings_tutorial.xml`: 33 entradas.
- `app/src/main/res/values-es/strings_ui_main.xml`: 872 entradas.
- `app/src/main/res/values-es/strings_ui_misc.xml`: 186 entradas.
- `app/src/main/res/values-es/strings_ui_viewmodel.xml`: 182 entradas.
- `app/src/main/res/values-es/strings_update.xml`: 2 entradas.
- `app/src/main/res/values-es/strings_voice_reply.xml`: 16 entradas.
- `app/src/main/res/values-es/strings_workspace_menu.xml`: 4 entradas.
- `app/src/main/res/values-es/strings_worldbook.xml`: 58 entradas.
- `miniphone/src/main/res/values-es/strings.xml`: 176 entradas.
- `miniphone/src/main/res/values-es/strings_call.xml`: 19 entradas.
- `miniphone/src/main/res/values-es/strings_forum.xml`: 20 entradas.
- `miniphone/src/main/res/values-es/strings_misc.xml`: 45 entradas.
- `miniphone/src/main/res/values-es/strings_misc_entry.xml`: 1 entradas.
- `miniphone/src/main/res/values-es/strings_relation.xml`: 12 entradas.
- `miniphone/src/main/res/values-es/strings_roam.xml`: 10 entradas.
- `miniphone/src/main/res/values-es/strings_roleplay.xml`: 24 entradas.
- `accessibility/src/main/res/values-es/strings.xml`: 1 entradas.
- `accessibility-provider/src/main/res/values-es/strings.xml`: 2 entradas.
