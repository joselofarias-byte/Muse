Muse v2.5.8 Versión oficial.

Esta versión tiene una gran extensión (acumulada desde la v2.5.6), y tiene cuatro líneas principales:**Acceder a protocolos externos y automatización.**,**Liberar espacios aislados y puntos de extensión de complementos**,**Convertir "los problemas se pueden localizar" en capacidades del producto**, y**Limpieza de arquitectura de enlaces generados.**.

## Acceso al protocolo externo

- **A2A 1.0 Acuerdo**: Punto final de descubrimiento de tarjeta de agente agregado (`/.well-known/agent-card.json`) con el punto final JSON-RPC (`/a2a`), apoyo SendMessage / GetTask / CancelTask.A2A Las llamadas y el chat en el dispositivo real reutilizan el mismo enlace de generación (análisis de modelo, herramientas, aprobación, memoria) y no existe un segundo conjunto de lógica de generación.
- **Tasker vinculación**: Entrada de transmisión agregada `io.zer0.muse.action.TASKER_TRIGGER`(extra `prompt`), las plataformas de automatización como Tasker / MacroDroid pueden activar una ronda de diálogo, los resultados se registrarán y se enviarán notificaciones. Ejecución de una sola ronda en segundo plano sin autorización de herramientas.

## Lanzar capacidades de complemento

- **Sistema de gancho enchufable**: Los complementos se pueden declarar en el manifiesto `hooks`y exportarlo en el script de entrada `onPromptFinalize(event)`, reescribiendo el historial antes de que finalice la palabra indicada. Conservar el historial original cuando falla o regresa ilegalmente (fail-open), no bloquea la generación.
- **Lanzamiento de la zona de pruebas del complemento**: dejar ir fetch / XHR / WebSocket Se permiten acciones de acceso a la red y puenteo, y solo se verifica el formato del nombre de la capacidad. Se mantienen dos puntos fundamentales: la navegación por la página sigue prohibida;`file://` con `content://` Las lecturas permanecen aisladas.
- **firma compatible**:manifest Los campos que participan en la firma se cambian a una lista blanca explícita. Los campos recién agregados no participan en la firma de forma predeterminada. Los paquetes de complementos firmados existentes no se ven afectados.

## Capacidad de resolución de problemas

- **Exportación de registros de diagnóstico**: Se agregó un registrador de seguimiento de enlaces de nueva generación para registrar el estado final, el consumo de tiempo, el retraso del primer token, las estadísticas de personajes y la clasificación del final del flujo de cada ronda.finish_reason con marcadores de activación de compresión. La entrada está en "Ajustes" → Acerca de → Exportar información de diagnóstico".
  Línea roja: el cuerpo del mensaje y la clave API no se registran y el hash de ID de sesión no está sensible.
- **Tiempo de espera del subagente**: El tiempo de espera predeterminado aumenta de 120 a 240 segundos, nuevo `timeout_ms` Parámetros (30s~900s); el error de tiempo de espera va acompañado de información de progreso (rondas ejecutadas, número de llamadas a herramientas, si hay un resumen parcial), lo que facilita la continuación en lugar de empezar de nuevo.

## Generar limpieza de arquitectura de enlaces

- Los cuatro bloques lógicos del cuerpo del bucle de llamada de herramienta (incluido el procesamiento de secuencia única), la compresión y orquestación del contexto, la aprobación de la herramienta y la llamada de la herramienta a restaurar se extraen como componentes independientes del modelo de vista principal, y el archivo principal se reduce de 7483 líneas a 5141 líneas.**Migrar a extracción pura, el comportamiento permanece sin cambios**y pruebas unitarias completadas para el recuento de compresiones y la clasificación de errores.
- Se corrigió la bifurcación de comportamiento causada por múltiples determinaciones del umbral de compresión y convergió en una única fuente de verdad;system prompt Aumente el monto total, las sesiones largas ya no desplazarán el presupuesto debido a la expansión de las palabras clave.

## Cambios heredados de v2.5.7

- Se corrigió la falsa alarma de desbordamiento del contexto: cuando el modelo no proporciona un tamaño de ventana, la ventana predeterminada aumenta de 32K a 128K.
- Ajuste estricto del umbral de interceptación para permitir un margen de error entre las estimaciones del cliente y el recuento del servidor.

## Interfaz y otros

- La barra de navegación lateral se reescribe para que se asigne linealmente según la altura del contenido, y la posición del control deslizante corresponde al volumen del contenido desplazado; Se corrige el salto de arrastre causado por mensajes largos y se agrega adsorción en ambos extremos.
- Se ha añadido una nueva interfaz generativa (renderizado progresivo + adaptación celular + refuerzo de seguridad); Se ha agregado una nueva capacidad de captura de pantalla del navegador, que permite al modelo ver los resultados de la representación de la página.
- Se solucionaron problemas como falla del interruptor multimodal después de guardar, saltar a la posición atascada del botón inferior, nivel de resistencia del vidrio, etc.
- La versión 110 de la base de datos, sin cambios desde la versión 2.5.4, se puede actualizar directamente sin perder datos.

## Instalación

- Paquete universal:Muse_v2.5.8_universal.apk(cualquier dispositivo)
- 64 poco:Muse_v2.5.8_arm64-v8a.apk(la mayoría de los teléfonos celulares modernos)
- 32 poco:Muse_v2.5.8_armeabi-v7a.apk(Equipo más antiguo)

---

# Muse v2.5.8 (English)

Muse v2.5.8 stable release.

This release spans a wide range of work accumulated since v2.5.6, along four main lines: **external protocol and automation integration**, **opening up the plugin sandbox and extension points**, **turning "we can locate the problem when it happens" into a product capability**, and **architectural cleanup of the generation pipeline**.

## External protocol integration

- **A2A 1.0 protocol**: adds an Agent Card discovery endpoint (`/.well-known/agent-card.json`) and a JSON-RPC endpoint (`/a2a`) supporting SendMessage / GetTask / CancelTask. A2A calls reuse the same generation pipeline as on-device chat (model resolution, tools, approval, memory); there is no second generation path.
- **Tasker integration**: adds a broadcast entry `io.zer0.muse.action.TASKER_TRIGGER` (extra `prompt`), allowing Tasker / MacroDroid and similar automation platforms to trigger a conversation turn whose result is stored and reported back via notification. Runs a single background turn without granting tool access.

## Plugin capability opening

- **Plugin hook system**: plugins may declare `hooks` in the manifest and export `onPromptFinalize(event)` from the entry script to rewrite history before the prompt is finalized. On failure or invalid return, the original history is kept (fail-open) and generation is not blocked.
- **Plugin sandbox opening**: fetch / XHR / WebSocket network access is enabled, bridge actions are fully allowed, and capability names are only format-checked. Two limits remain: page navigation is still blocked, and `file://` / `content://` reads remain isolated.
- **Signature compatibility**: fields participating in manifest signing are now an explicit allowlist; newly added fields do not participate by default, so existing signed plugin packages are unaffected.

## Troubleshooting capability

- **Diagnostic log export**: adds a generation-pipeline trace recorder that logs each turn's terminal state, duration, time-to-first-token, character counts, stream-end classification, finish_reason and compression trigger marks. Entry point: Settings → About → Export Diagnostics.
  Hard rule: no message body or API key is recorded; session identifiers are hash-redacted.
- **Sub-agent timeout**: the default timeout is raised from 120s to 240s, with a new `timeout_ms` parameter (30s–900s); timeout errors now carry progress information (rounds executed, tool calls made, whether a partial summary exists) so work can be resumed instead of redone.

## Generation pipeline cleanup

- Four blocks — the tool-call loop (including single-round streaming), context compression orchestration, tool approval, and pending tool-call resume — were extracted wholesale from the main view model into independent components, reducing the main file from 7483 to 5141 lines. **The migration is a pure extraction with unchanged behavior**, and it adds unit tests for compression counting and error classification.
- Fixes behavior divergence caused by compression thresholds being judged in multiple places, consolidated into a single source of truth; a total budget gate is added to the system prompt so long sessions no longer blow the budget from prompt bloat.

## Changes inherited from v2.5.7

- Fixes false context-overflow warnings: when a model does not report its window size, the fallback window is raised from 32K to 128K.
- Adjusts the hard-block threshold to leave margin for the difference between client-side estimation and server-side counting.

## UI and misc

- The side navigation rail is rewritten as a linear mapping by content height, with the thumb position corresponding to scrolled content; fixes the drag jump caused by long messages, with snapping added at both ends.
- Adds generative UI (progressive rendering + mobile adaptation + security hardening); adds browser screenshot capability so the model can see rendered pages.
- Fixes multimodal toggle failing to persist, the scroll-to-bottom button positioning, and glass-intensity presets.
- Database version 110, unchanged since v2.5.4; upgrading directly loses no data.

## Installation

- Universal: Muse_v2.5.8_universal.apk (any device)
- 64-bit: Muse_v2.5.8_arm64-v8a.apk (most modern phones)
- 32-bit: Muse_v2.5.8_armeabi-v7a.apk (older devices)
