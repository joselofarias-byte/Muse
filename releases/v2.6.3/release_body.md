# Muse v2.6.3

> Solucione la inevitable regresión por falla en v2.6.2 y concéntrese en GitHub #3 ~ #7 comentario.

---

## chino

### Solución de emergencia: configuración → El chat colapsará

v2.6.2 Abra "Configuración" → "Chat" se bloqueará (se activa al desplazarse hacia abajo hasta el área de emoticones):

```
java.lang.IllegalFormatConversionException: d != java.lang.String
```

La razón es que la copia del recordatorio utilizó parámetros durante el período de combinación. `stringResource(id, "%1$d")`,
será ejecutado inmediatamente `String.format`, inserte la cadena en la ranura de números enteros. Se ha cambiado a ningún parámetro. `stringResource`.
Por cierto, se ha solucionado el problema del mismo origen donde el mensaje "reenvío exitoso" siempre muestra marcadores de posición.

v2.6.1 y las versiones anteriores no tienen este problema.

### WebServer No se puede iniciar (#4)

`WebServer El inicio falló: Array has more than one element` ¿Ktor 3.0.x está ofuscado en R8?
release Defectos conocidos en la construcción (KTOR-7298), y solo se reproduce en el paquete de lanzamiento,debug No se puede medir.

- Ktor 3.0.3 → 3.1.3(solución oficial)
- Se agregó R8 para mantener las reglas de ServiceLoader/fábrica de motores como doble seguro.

### Las llamadas a herramientas fallan silenciosamente (#6)

**#6**: En el chat grupal `channel_reply` Cuando la verificación falla (por ejemplo, el contenido está vacío), el valor de retorno se descarta directamente.
El proceso va hasta el final. `implicit pass`, lo que el usuario ve es "no hay respuesta ni se ha informado de ningún error en toda la ronda". Ahora:

- Si la herramienta no se ejecuta, el error se rellenará en el modelo, permitiéndole corregirlo y volver a intentarlo (hay un número máximo de veces).
- La respuesta vacía ya no equivale a "omitir el modelo activamente" y se volverá a intentar antes de bajar de categoría.
- explícito `[PASS]` Completamente separados de las respuestas vacías, los registros pueden distinguir

> **#5 Descripción**:DeepSeek Flash Esperando el modelo "afirma ajustar la herramienta pero no devuelve un valor válido `tool_call`"Cuando
> reasoning El contenido seguirá mostrándose como texto. Esta es una decisión de producto existente ("vomitar está bien, pero vaciarlo no está bien"),
> Existe un conflicto semántico con "No trate los pensamientos como texto" y debe evaluarse por separado antes de cambiar. Esta edición**Sin modificar**Este punto,
> Evite introducir cambios de comportamiento indecisos en las liberaciones de emergencia.

### El modelo multimodal de la estación de transferencia fue juzgado mal (#3.1)

La tabla de capacidades del modelo incorporada anulará incondicionalmente la declaración ascendente, lo que hará que la estación de transferencia admita el modelo con el mismo nombre para el reconocimiento de imágenes.
(como `deepseek-v4.1-flash`) se considera texto sin formato y el reconocimiento de imágenes no está disponible en absoluto.
Ahora bien, si el upstream afirma claramente que apoya `image`, ya no están cubiertos por etiquetas integradas.

También puedes ir a "Proveedores" → modelo → Corríjalo manualmente en "Editar capacidades del modelo". Después de la modificación, prevalecerá su configuración.

### Botón "Prueba" del proveedor falso positivo (#3.2)

La mayoría de las pruebas de puntos del modelo fallaron, pero el chat real fue normal. Se han solucionado los tres motivos:

- `maxTokens` Mencionado desde 16 256 —— El valor original no es suficiente para el modelo de cadena de pensamiento y toda la cuota se la come el razonamiento.
- Si hay una llamada a una herramienta o solo una salida de la cadena de pensamiento, ahora se considera un pase de enlace.
- Da una razón procesable en caso de falla (Key No válido/el modelo no existe/límite actual/tiempo de espera/formato ascendente anormal), ya no se pierde solo una oración del texto original ascendente

### El mensaje de error está truncado y no se puede copiar (#3.3)

Los detalles del error ya no se truncan a 80 caracteres, lo que deja intacto el texto completo.

### Termux Autorización sin comentarios (#3.4)

- "Ir a otorgar permisos" todavía brinda orientación paso a paso después del salto (el interruptor no es visible en la primera pantalla de información de la aplicación)
- El mensaje de configuración cambió a un comando completo de dos pasos: Escribir configuración **+** `termux-reload-settings`(Es posible que simplemente reiniciar Termux no surta efecto)
- Se agregó un botón "Copiar comando" para evitar errores de escritura manual.

### Velocidad de respuesta (#3.5)

Se agregó el interruptor "Modo de respuesta rápida" (Configuración → charlar → comportamiento interactivo).
Cuando esté habilitado, omita la recuperación de memoria relevante en cada ronda.——Esta sección de la consulta FTS puede representar un retraso de más de 500 ms en la primera palabra cuando se mide en una máquina real.
La memoria a largo plazo todavía se inyecta como de costumbre, pero el recuerdo no se realiza en función de la relevancia de la pregunta.

---

## English

### Critical: Settings → Chat crashed

v2.6.2 crashed when opening Settings → Chat (triggered on scrolling down to the
sticker section) with `IllegalFormatConversionException: d != java.lang.String`.

The format string was passed as an argument to `stringResource(id, "%1$d")`,
which executes `String.format` immediately and put a String into an integer slot.
Fixed by using the argument-less `stringResource`. The same pattern in the
"forwarded" toast (which silently showed a literal placeholder) is fixed too.

Not present in v2.6.1 or earlier.

### WebServer failed to start (#4)

`Array has more than one element` is Ktor 3.0.x's known release-only defect under
R8 (KTOR-7298); it never reproduces in debug builds.

- Ktor 3.0.3 → 3.1.3 (official fix)
- Added R8 keep rules for ServiceLoader / engine factory as a second layer

### Silent tool-call failures (#6)

**#6**: In group chats, `channel_reply` validation failures were discarded, so the
round silently ended as `implicit pass`. Now failures are fed back to the model for
a bounded retry, empty responses are no longer treated as an explicit skip, and
`[PASS]` is distinguished from an empty response in logs.

> **Note on #5**: When a model claims a tool call but returns no valid `tool_call`,
> reasoning content is still shown as the reply body. That is an existing product
> decision ("something is better than blank") which conflicts with "don't show
> thinking as body text", so it needs a separate decision. This release does **not**
> change that behavior, to avoid shipping an undecided semantic change in a hotfix.

### Relay-hosted multimodal models misjudged (#3.1)

The built-in capability table unconditionally overrode upstream declarations, so
relay models that genuinely support images were marked text-only. Upstream
`image` declarations are now respected. You can still override per model under
Provider → Model → Edit capabilities, and manual edits always win.

### Provider "Test" false negatives (#3.2)

- `maxTokens` 16 → 256 (16 was not enough for reasoning models; the budget was
  consumed entirely by reasoning, leaving empty content)
- Tool calls or reasoning-only output now count as a healthy link
- Failures report an actionable cause instead of a raw upstream string

### Error text truncated and not copyable (#3.3)

Error detail is no longer truncated to 80 characters.

### Termux permission flow (#3.4)

- Step-by-step guidance after jumping to the app info page
- Config hint now includes both writing the property **and** `termux-reload-settings`
- Added a "copy command" button

### Reply latency (#3.5)

New "fast reply mode" toggle (Settings → Chat → Interaction). It skips the
per-turn relevant-memory retrieval, whose FTS query measured 500ms+ of
time-to-first-token on device. Long-term memory is still injected.

---

## Instalación

- Simplemente sobrescriba e instale.**No es necesario desinstalar**, retención de datos
- La versión de la base de datos aún está **111**, consistente con v2.6.2,**Sin riesgo de migración**
- La firma es la misma que v2.6.1 / v2.6.2

## cosas conocidas

- Si actualiza directamente desde v2.6.1 a esta versión, igualmente la revisará una vez v110 → v111 Migración (nueva personalidad asistente/La cadena de pensamiento (dos columnas) es normal
- El "modo de respuesta rápida" está desactivado de forma predeterminada; actívalo cuando necesites empezar a escribir más rápido
