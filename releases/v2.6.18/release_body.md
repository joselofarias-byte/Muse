# Muse v2.6.18

> Esta versión reorganiza la configuración en 14 categorías según "¿Qué estás haciendo aquí?" y realmente conecta las cuatro configuraciones en el mensaje activo que nunca han tenido efecto.
> Deje que los sonidos clonados finalmente suenen y haga que las cosas que se pueden leer en el resumen de la memoria se puedan buscar;
> Al mismo tiempo, limpiaremos algunos defectos que fueron descubiertos por la autoinspección antes de la publicación.

Esta versión incorpora todos los cambios desde la v2.6.10 hasta la actualidad.

---

## chino

### La página de configuración se reorganiza en 14 categorías según "¿Qué estás aquí para hacer?"

La versión anterior tenía 12 categorías. Esta versión ha sido reclasificada según los elementos de configuración más detallados y ampliada a 14 categorías.48 Artículo:

- Se agregó "Privacidad y seguridad" para separar el intercambio y la seguridad de los datos.
- Los agentes se fusionan en "conexiones" y ya no ocupan una categoría separada
- Los "Datos y Privacidad" originales se dividieron en "Datos y Copia de Seguridad" y "Privacidad y Seguridad".
- No más de 5 elementos por categoría, las opciones de desarrollador no colapsan

### Noticias activas: las cuatro configuraciones están realmente conectadas esta vez

Ajustar el intervalo, el desplazamiento aleatorio, enviar probabilidad y dejar que la IA decida el ritmo. Estos cuatro elementos se podían cambiar previamente en la interfaz.
Pero el despacho no lee nada. Ahora conectado, la interfaz es consistente con el comportamiento:

- El intervalo predeterminado es 240 minutos, que está alineado con "aproximadamente 4 horas" escrito en la interfaz.
- El presupuesto total de material es de 6.000 palabras y el resultado se reduce a 200 palabras.
- "Dejemos que la IA determine el ritmo" se eliminó de la interfaz y las capacidades que describía no se implementaron.
- Bucle cerrado que no responde: al hacer clic en "Esta vez no" en la notificación se marcará como no interesado, y abrir la conversación se marcará como vista.
  24 Ninguna respuesta durante una hora se registrará como vencida y nadie participará en la siguiente ronda de juicio.
- Los tipos que son repetidamente impopulares ya no son candidatos.

### Voz: Enviar después de hacer coincidir

Hubo varias razones por las que "la voz fue clonada y el personaje todavía no hablaba". Arreglemoslos uno por uno:

- La página de clonación de voz ahora guarda la clave API completada manualmente. En el pasado sólo se conservaban los motores y los sonidos.
  La síntesis en la nube debe fallar por falta de credenciales
- Las fallas en la nube ya no vuelven silenciosamente al tono del sistema e informan sobre el éxito. Los resultados de la herramienta se escribirán.
  "Se ha enviado un mensaje de voz, pero actualmente se utiliza el tono integrado del teléfono" y se proporciona el motivo del fallo de la nube.
- Al cambiar de motor TTS, los sonidos que no pertenecen al nuevo motor se borrarán y se le pedirá que vuelva a seleccionarlo.
- TTS Los modelos relacionados con ASR se modifican para seleccionarse de la lista de modelos de proveedores en lugar de completarse manualmente.
- El habla se convierte en una herramienta permanente. En el pasado, era un cargador bajo demanda. Si no usaras palabras como "voz" en tu discurso, no aparecería.
  Cuando el asistente personalizado ha configurado una lista blanca de herramientas, ni siquiera se puede buscar.

### Memoria: Lo que se puede leer en el resumen ahora se puede buscar

Después de inyectar las palabras clave en el resumen de la memoria a largo plazo, utilice `search_memory` La búsqueda de "Ziqi Developer Assistant" no arrojó ningún resultado.
La razón es que la capa de búsqueda coincide con el texto completo a nivel de línea. Las palabras chinas se segmentan en palabras binarias y luego se conectan con espacios, lo que equivale a requerir que todas las palabras aparezcan al mismo tiempo.
En la misma línea de hechos; y el resumen es prosa con múltiples líneas de hechos más adaptación del modelo.

- La consulta de varias palabras se cambia a una forma de dos etapas: primero, busque según una semántica precisa y luego relájese y combine según los resultados si los resultados son insuficientes.
- En el lado de la consulta, elimine las últimas palabras que solo agregan restricciones pero no recuerdan.
- El respaldo difuso divide la coincidencia por palabras y ya no trata la cadena completa como una subcadena.
- Cuando la tabla de hechos no tiene resultados, recurre al resumen compilado para el escaneo de subcadenas. La fuente de anotación del elemento de impacto es "resumen compilado"

### Superficie de la herramienta

- **JS El enlace completo en el sandbox no está disponible**: Hay un script de autocomprobación del entorno menos antes de iniciar el entorno sandbox. `return`,
  La devolución de llamada obtiene un valor nulo, por lo que la autoprueba siempre falla. No es sólo el `execute_javascript`,
  La activación de complementos está obsoleta junto con la evaluación de habilidades JS. Después de la reparación, también se separaron las categorías de errores:
  Cuando el entorno no esté disponible, dirá claramente "Volver a intentar el mismo código no tendrá éxito" y el modelo ya no pensará que los parámetros están escritos incorrectamente.
- **Se puede encontrar una salida larga.**:`tool_outputs` El directorio está registrado como un montaje de solo lectura,`workspace_list` Se puede enumerar,
  La ruta escrita en el recibo se cambia al método de montaje. En el pasado, el nombre del archivo contenía una cadena aleatoria y, una vez que se perdía el recibo, sólo se podía adivinar.
- **Recuento de herramientas unificado**: La palabra que aparece es "240 registrados",`find_tools` Informe 231, iniciar informe de registro 172,
  Los tres números provienen de tres conjuntos diferentes. Ahora ha convergido en una definición y la palabra indicadora se cambia a "Se pueden realizar N números de llamadas en esta ronda".
- **Node tiempo de ejecución**:`os.cpus()` Devuelve una matriz vacía en algunos sistemas, paquete npm para determinar la simultaneidad por número de núcleos
  obtendrá 0. Ahora el número de CPU se inyecta y sintetiza cuando no se puede obtener. Esta detección también se suma a la autocomprobación en ejecución.

### Problema por solucionar

- **Fallo al iniciar**: configura la página del grupo para usar la enumeración como parámetros de navegación.R8 Después de cambiar el nombre, tírelo tan pronto como se construya la ruta.
  `Cannot find class with name`. Cámbielo a una identificación de cadena y luego asigne la página de destino
- **Ocasionalmente se pierde la recuperación del enfoque**: La protección del cambio de sesión es un singleton a nivel de proceso, un cambio tardío de una instancia de interfaz
  El cambio que acaba de iniciar otra instancia se considerará caducado. Cambiar a nivel de instancia
- **No se puede instalar el complemento**: El umbral de versión durante la instalación lee los parámetros de compilación y la compilación local no incluye un número de versión.
  Es el valor centinela, que es menor que la versión requerida por el complemento. La versión Sentinel ahora salta este umbral y toma nota de la advertencia,
  Los paquetes formales se verifican como de costumbre y las comprobaciones de firma y degradación no se ven afectadas.

### Instalación

- `arm64-v8a`: La mayoría de los teléfonos celulares
- `armeabi-v7a`:Equipo viejo
- `x86_64`: emulador
- `universal`: No estoy seguro del modelo de este modelo.

Simplemente sobrescriba la instalación, el número de versión se incrementará y no se activará el rechazo de la degradación.

### cosas conocidas

- Cuando el TTS en la nube vuelve al sonido del sistema, el sonido viene con el teléfono y los resultados de la herramienta explicarán el motivo.
- El mp3 devuelto por la síntesis en la nube se almacena en `.wav` sufijo, la reproducción es normal
- Después de eliminar un mensaje, el archivo de voz no se reciclará inmediatamente y se borrará junto con el caché.

---

# Muse v2.6.18 (English)

> This release reorganizes settings into 14 groups by what you came to do, makes four
> proactive-message settings actually take effect, gets cloned voices to speak, makes
> what the memory summary shows searchable, and clears several hard failures found by
> the pre-release self-check.

This release bundles everything since v2.6.10.

## Settings reorganized into 14 groups

The previous release had 12 groups. This one splits by the finest setting unit
into 14 groups and 48 entries:

- New "Privacy & security", separating sharing and security from data
- Proxy merged into "Connection"
- "Data & privacy" split into "Data & backup" and "Privacy & security"
- No group exceeds five entries; developer options stay expanded

## Proactive messages: four settings now actually work

Interval, random offset, send probability, and "let the model pace it" were editable
but never read by the scheduler. They are wired now and the UI matches the behavior:

- Default interval 240 minutes, matching the "about 4 hours" label
- Material budget 6000 characters, output hard-truncated at 200
- "Let the model pace it" removed from the UI; the capability did not exist
- Feedback loop: "not this time" in the notification marks it dismissed, opening
  the session marks it seen, 24 hours without a response marks it expired
- Repeatedly unpopular categories stop entering the candidate pool

## Voice: configure it and it sends

Several causes stacked up behind "the voice was cloned but the character never speaks":

- The cloning page now saves the API key you typed. It previously saved only the
  engine and voice, so cloud synthesis always failed for lack of credentials
- Cloud failure no longer silently falls back to the system voice and reports success.
  The tool result says the message was sent with the phone's built-in voice and
  includes the reason cloud synthesis failed
- Switching TTS engines clears voices that do not belong to the new engine
- TTS and ASR models are picked from the provider model list instead of typed by hand
- Sending voice is now an always-on tool. It used to load on demand, so it was
  invisible unless you said "voice", and assistants with a tool allowlist could not
  even find it

## Memory: what the summary shows is now searchable

After the long-term summary was injected into the prompt, searching
"name developer assistant" with `search_memory` returned nothing. Retrieval matched
whole rows: Chinese is split into bigrams joined by spaces, which requires every term
to appear in the same row, while the summary is many rows plus model-written prose.

- Multi-term queries now run in two stages: exact first, then a relaxed union when
  results fall short
- Query-side single-character tail tokens removed; they only added constraints
- Fuzzy fallback splits the query into terms instead of matching the whole string
- When the fact table is empty, retrieval scans the compiled summary and labels those
  hits as coming from the summary

## Tool surface

- **The JS sandbox never ran**: the pre-flight self-check script was missing a
  `return`, so the callback received nothing and verification always failed. That took
  down `execute_javascript`, plugin activation, and JS skill evaluation together.
  Error classification is now split as well: an unavailable environment says retrying
  the same code will not help, instead of looking like a bad argument
- **Long output is discoverable**: `tool_outputs` is a read-only mount, listed by
  `workspace_list`. The receipt now uses the mount path. File names contain random
  strings, so a lost receipt used to mean guessing
- **Tool counts unified**: the prompt said 240 registered, `find_tools` reported 231,
  and the startup log said 172, each from a different set. They now come from one
  definition and the prompt says "N callable this round"
- **Node runtime**: `os.cpus()` returns an empty array on some systems, so npm
  packages sizing concurrency by core count got 0. The CPU count is injected and
  synthesized when unavailable, and the runtime self-check probes it

## Fixed

- **Startup crash**: the settings group route used an enum as a navigation argument.
  After R8 renamed it, building the route threw `Cannot find class with name`. It now
  carries a string id and maps back at the destination
- **Focus restore occasionally lost**: the session switch guard was a process-wide
  singleton, so a late switch in one UI instance invalidated a fresh switch in another.
  It is instance-scoped now
- **Plugins refused to install**: the minimum app version check read the build
  parameter, which is a sentinel in local builds. Sentinel builds now skip that gate
  with a warning; release builds still enforce it, as do downgrade and signature checks

## Installation

- `arm64-v8a`: most phones
- `armeabi-v7a`: older devices
- `x86_64`: emulators
- `universal`: pick this if unsure

Overwrite install works; the version code increases.

## Known issues

- When cloud TTS falls back to the system voice, the result explains why
- Cloud mp3 output is stored under a `.wav` extension; playback is fine
- Voice files are not reclaimed immediately when a message is deleted