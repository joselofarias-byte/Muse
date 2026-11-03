# Muse v2.5.1

Esta versión es una versión de reparación de emergencia de v2.5.0, que corrige dos regresiones graves.**Por favor asegúrese de actualizar a esta versión**——v2.5.0 Ambos problemas harán que la conversación no esté disponible.

## Reparación

### 1. El nombre de la llamada a la herramienta se sobrescribe con la continuación de cadena vacía y todas las llamadas a la herramienta se descartan (glm / DeepSeek Debes presentarte en la estación de transferencia)

Streaming en algunas estaciones de transferencia `tool_calls` llevado en fragmentos incrementales posteriores**campo de nombre de cadena vacía**. Anteriormente, aguas abajo usaba "null Nombre de la herramienta de acumulación lógica "Fusionar"——La cadena vacía no es nula. Penetra en la lógica de fusión y devuelve el nombre completo de la herramienta de la primera pieza que ha llegado correctamente (como `knowledge_search`,`search_memory`) se sobrescribe en una cadena vacía.

Una vez finalizado el flujo, el limpiador presiona "name "Vacío" descarta todas las llamadas a herramientas, el rendimiento es el siguiente:

- Mensaje "El modelo inició N llamadas a herramientas, pero el formato del parámetro era anormal y no se pudo ejecutar".
- proceso de pensamiento (CoT) se genera normalmente, pero el texto está vacío y está bloqueado directamente por una falla en la llamada a la herramienta.
- Durante la revisión de la sesión, esta ronda de mensajes parpadea y luego desaparece (la capa de representación filtra los mensajes vacíos)

Reparación de doble seguro: el flujo ascendente cambia el nombre de la herramienta en blanco de los incrementos posteriores a nulo (la semántica descendente conserva el nombre anterior); el flujo descendente también filtra cadenas en blanco cuando se acumulan.

### 2. Deep Thought fue eliminado por un tiempo de espera total de 10 minutos (respuesta truncada)

glm-5.3 Espere el período de reflexión del modelo de pensamiento profundo en la estación de transferencia (más de 10 minutos en medición real)**No escupe ningún dato de transmisión**, mientras que las solicitudes de streaming anteriormente estaban sujetas a un tiempo de espera total de 10 minutos.——Cuando el modelo se considera durante 10 minutos, todo el flujo se elimina por completo y la respuesta se trunca (algunas herramientas que se han llamado con éxito antes también se descartarán).

Solución: las solicitudes de transmisión ya no tienen un tiempo de espera total. La suspensión silenciosa todavía está cubierta por el tiempo de espera de lectura de 5 minutos (aún se interrumpirá si realmente se suspende) y el botón de cancelación del usuario sigue siendo efectivo; No importa cuánto tiempo lo pienses, el sistema ya no lo interrumpirá.

## cosas conocidas

- Algunas estaciones de transferencia (como newapi.0z.hk) devolverán 503/400 (`code:11133 Parámetro rechazado por el proveedor`), este es un problema aguas arriba de la estación de transferencia y no se puede resolver reintentando al cliente. Se recomienda cambiar el modelo o cambiar el sitio para su verificación.
- Ocasionalmente se genera cuando el modelo nombra automáticamente la sesión `...` El problema de los literales que provocan que los nombres de las sesiones cambien a elipses se solucionará en la próxima versión.

## Información de construcción

- Versión:2.5.1 (251)
- Versión de la base de datos:109(Igual que 2.5.0, sobrescribe directamente la instalación)
- Distribución:arm64-v8a / armeabi-v7a / universal

---
# Muse v2.5.1 (English)

An urgent hotfix release for v2.5.0, fixing two severe regressions that make conversations unusable. **Please update.**

## Fixes

### 1. Tool call names overwritten by empty-string continuation fragments, causing all tool calls to be dropped (reproducible on GLM / DeepSeek relays)

Some relays' streaming `tool_calls` carry an **empty-string name field** in subsequent delta fragments. The downstream name accumulation used null-coalescing — an empty string is not null, so it slipped through and overwrote the complete tool name (e.g. `knowledge_search`, `search_memory`) that had already arrived correctly in the first fragment.

After the stream ended, the sanitizer dropped every tool call for having an "empty name", resulting in:

- The notice "N tool calls were initiated but could not be executed due to malformed arguments"
- Reasoning (CoT) output normally, but an empty body cut off by the tool-call failure
- The round's messages disappearing on session revisit (empty messages filtered by the render layer)

Fixed with double insurance: the upstream provider now sends null for blank names in continuation fragments (downstream treats null as "keep the existing name"), and the downstream accumulator filters blank strings as well.

### 2. Deep thinking killed by a 10-minute total timeout (truncated replies)

Deep-thinking models like glm-5.3 on relays can think for 10+ minutes **emitting no streaming data at all**. Streaming requests were subject to a 10-minute total timeout — exactly when the model finished thinking, the entire stream was killed and the reply truncated (even successfully executed tool calls from earlier rounds were discarded).

Fix: streaming requests no longer have a total timeout. Silent hangs are still caught by the 5-minute read timeout (genuinely stuck streams still disconnect), and the cancel button still works. Thinking for any length is no longer cut off by the system.

## Known issues

- Some relays (e.g. newapi.0z.hk) return 503/400 (`code:11133 parameters rejected by the provider`) for models like glm-5.3 — this is an upstream relay problem that client retries cannot fix. Try a different model or endpoint.
- Auto session naming occasionally outputs `...` literal, turning session titles into ellipses. Will be intercepted in the next release.

## Build

- Version: 2.5.1 (251)
- Database version: 109 (unchanged from 2.5.0; install over the existing app)
- Distributions: arm64-v8a / armeabi-v7a / universal
