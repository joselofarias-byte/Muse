# Muse v2.6.19

> Esta versión lo que hace es "omitir el trabajo de la provincia y completar la retroalimentación que se debe brindar":
> Rectificación de rendimiento de cuatro enlaces,JS El sandbox finalmente se puede conectar a Internet. Después de editar el mensaje, se pueden implementar sucursales en el acto.
> Los mensajes activos ya no aparecen inundados en la página del Agente. Cualquier afirmación que no estuviera respaldada por mediciones se eliminó del documento.

Esta versión incorpora todos los cambios desde la v2.6.18 hasta la actualidad.

---

## chino

### Rendimiento: tres beneficios comprobados

| indicador | antes del cambio | Después de la modificación |
|---|---|---|
| El canal tiene un atraso de 50 mensajes con imágenes,45 El disco escribe en segundos | 479.9 MB | **10.6 MB(45×)** |
| Gastos generales del lado del cliente por ronda de conversación (ensamblaje de palabras rápidas + canalización) | aprox. 530 ms | **Aproximadamente 355 ms (−33%)** |
| Tamaño del paquete de instalación | — | **cada uno ABI −2.96 MB** |

- Los datos de la bandeja de entrada, la conversación y la tarjeta se cambian a elementos/Distribuido por fragmentos de sesión, ya no "procesa un mensaje y reescribe toda la bandeja de entrada";
- En cada ronda, las tareas que se "calculan una vez sólo para confirmar que no han cambiado" (lista de herramientas, biblioteca de experiencias, ocupación de contexto, token de compresión) se cambian para contarse una vez y recordarse;
- El archivo de registro se cambia a un identificador residente y el archivo ya no se abre ni se cierra para cada línea.

al mismo tiempo**Retirar**Hay tres afirmaciones que alguna vez estuvieron escritas en el documento pero que fueron volcadas por las medidas: la tarjeta se recarga en toda la página. 68→9, marco de desplazamiento de tarjeta 36→34 ms,
8 memoria de la tarjeta −7.5%. usar n=3 No hubo diferencias significativas entre ellos después de volver a realizar la prueba. —— El código permanece pero ya no cuenta para las ganancias.

### JS Sandbox: redes y valores de retorno asincrónicos

- **fetch / XMLHttpRequest Iniciado de forma nativa por el anfitrión**, no restringido por la política del mismo origen del navegador (el origen de la página sandbox es nulo,
  En el pasado, CORS bloqueaba cualquier solicitud entre sitios y fallaba). Se escribirán registros de auditoría para cada transacción saliente: método, host, volumen, código de estado.
- **El guión puede `return Promise`**: Anteriormente la función asíncrona devolvía un objeto vacío `{}`, el éxito y el fracaso son completamente indistinguibles;
  Ahora esperará a que se complete la Promesa antes de devolver el resultado real y también se procesarán las excepciones. `error` campo.
- Los valores de retorno del script ya no se vuelven a codificar:`return {a:1}` con `return '{"a":1}'` Sólo se codifican una vez.
- Por cierto, se ha solucionado un error existente: el script de inicialización de la zona de pruebas solo se inyecta una vez al crear WebView.
  Después de que se carga la página `host.getConfig()` Espere hasta que la cobertura haya expirado. —— Ahora vuelva a inyectar antes de cada ejecución.

### Diálogo: Editar rama de mensaje en su lugar

En el pasado, después de editar un mensaje de usuario en el medio, la nueva respuesta iba al final de la conversación; ahora lo hará**detenerse en la posición original**formar ramas,
y muestra "2/2"Marca variante, puedes cambiar de izquierda a derecha —— Es el mismo comportamiento que cambiar el árbol de diálogo.

### Botón Copiar: Copiar solo texto

En el pasado, al "copiar", el proceso de pensamiento se agrupaba al final del texto (con un `[proceso de pensamiento]` marca),
Parece que "lo que se copia es pensamiento". Ahora sólo se copia el texto; Vuelve a pensar cuando el texto está vacío.
Si quieres pensar, hay un "Pensamiento de copia" separado.

### Mensajes activos: puntos de entrega y conmutadores

- Colocar prioridad**sesión de conversación normal**(De la que habló un usuario recientemente), ya no se apresure a ir a la página del Agente solo porque se actualiza más tarde;
- Solo cuando chatee en Agente, responda**Esa sesión de Agente existente**, ya no crea uno vacío cada vez;
- El interruptor "Solo sesión de agente" en la configuración**Ningún código lo ha leído antes.**(no hay respuesta, no importa cómo lo corte),
  Ahora cámbialo al real "Agent Interruptor "Conversación": después de desactivarlo, los mensajes activos solo se enviarán a conversaciones normales. Es mejor no enviar mensajes que caer en la página del Agente.

### Otros

- `read_file` En caso de error, se enumerarán los archivos existentes en el mismo directorio (anteriormente se le solicitaba que `workspace_list` Incluya el directorio y esa acción simplemente estará fuera de su alcance `tool_outputs/`);
- Versión HTTP de texto sin cifrar: el entorno de pruebas necesita acceder a cualquier host http, pero la política de texto sin cifrar de Android permite permisos basados en nombres de dominio y no se puede abrir solo en el entorno de pruebas.
  Por lo tanto, el lanzamiento global (Provider El registro seguirá avisando cuando se utilice http). Quiero recuperarlo `network_security_config.xml` adentro `true` Cambiar de nuevo `false` Eso es todo.

### Problemas conocidos

- **Los indicadores de velocidad de cuadros aún deben verificarse en máquinas reales**: El límite inferior del tiempo de fotograma del simulador (incluido el paso de GPU) es de aproximadamente 32 ms, que es mayor que la diferencia a verificar.
  No hay diferencia en la suavidad del desplazamiento de la tarjeta medida en el simulador;
- El número y calibre de las herramientas son inconsistentes (índice de palabras rápido 234 vs `find_tools` 231), para reparar por separado;
- Los WebSockets en espacio aislado todavía no son compatibles (fetch/XHR ya disponible).

---

# Muse v2.6.19 (English)

> This release is about *removing work that did not need to happen* and *making feedback honest*:
> performance work across four pipelines, a JS sandbox that can finally reach the network,
> in-place branching when you edit a message, and proactive messages that stop landing in the Agent tab.
> Anything that measurement did not support was removed from the docs.

### Performance: three claims with evidence

| Metric | Before | After |
|---|---|---|
| Disk writes with 50 queued image messages (45 s) | 479.9 MB | **10.6 MB (45×)** |
| Per-turn client cost (prompt assembly + pipeline) | ~530 ms | **~355 ms (−33%)** |
| APK size | — | **−2.96 MB per ABI** |

Three earlier claims were **retracted** after n=3 re-measurement: card full-page reloads 68→9,
card scroll frame time 36→34 ms, and 8-card memory −7.5%. None showed a significant difference.
The code stays; the claims do not.

### JS sandbox: network and async results

- `fetch` / `XMLHttpRequest` are now performed **natively by the host**, so the browser's
  same-origin policy (the sandbox page has a null origin) no longer blocks cross-site requests.
  Every outbound request is audited: method, host, size, status.
- Scripts may `return Promise`. Previously an async function returned `{}` and success was
  indistinguishable from failure; now the promise is awaited and rejections surface in `error`.
- Return values are no longer double-encoded.
- Fixed a long-standing bug where the sandbox prelude was injected only once at WebView creation
  and silently lost after page load (`host.getConfig()` was affected too).

### Chat: editing a message branches in place

Editing a user message used to move the new reply to the end of the conversation. It now branches
**in place**, with a `2/2` variant badge you can switch between — the same behaviour as switching trees.

### Copy: body only

Copy used to append the reasoning (with a `[thinking]` marker), which looked like "it copied the thinking".
It now copies the body only, falling back to reasoning when the body is empty. Reasoning has its own button.

### Proactive messages: target and switch

- Targets the most recent **normal** chat session first, instead of jumping to the Agent tab;
- If you only ever chatted in the Agent tab, it reuses that session instead of creating a new one every time;
- The old "Agent sessions only" switch had **no reader anywhere** (toggling it did nothing).
  It is now a real "Agent sessions" switch: when off, proactive messages only go to normal sessions.

### Other

- `read_file` failures now list the files that actually exist in that directory;
- Cleartext HTTP is allowed app-wide (the sandbox needs arbitrary http hosts and Android's policy
  is per-domain). Flip `cleartextTrafficPermitted` back to `false` to revert.

### Known issues

- Frame-time metrics still require a physical device: the emulator's frame floor (~32 ms) is larger
  than the differences being measured;
- Tool-count reporting is inconsistent (index 234 vs `find_tools` 231);
- WebSocket is still unsupported inside the sandbox.
