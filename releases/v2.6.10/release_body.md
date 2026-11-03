# Muse v2.6.10

> En el pasado, la tarjeta sólo podía dibujarse hasta un tercio de su altura y las llamadas a herramientas se atascaban al aprobarse y nunca se ejecutaban;
> Los mensajes activos tienen una nueva notación, los recuerdos se pueden eliminar en lotes y la página de configuración se reorganiza según "¿Para qué estás aquí?"

---

## chino

### La tarjeta sólo muestra la mitad de su contenido (P0)

Tarjetas de altura automática utilizadas para revelar solo la sección del título.WebView Las dimensiones devueltas son píxeles CSS,
El código los trata como píxeles físicos, excepto por la densidad de la pantalla (2.625), dejando cada tarjeta con solo un 38% de altura.

Ahora presione los píxeles CSS directamente para usarlos. Además, el modelo puede ser `show_card` Declare la altura de la tarjeta aquí
(`auto`, dp fijo,`16:9`), el diseño de varias líneas lo declara uno mismo, lo que evita que el anfitrión adivine y las tarjetas largas ya no serán interceptadas.

**Un cambio que sentirás inmediatamente**: "Salida de tarjeta de preferencias" se vuelve a abrir de forma predeterminada.
(2.6.6 lo había apagado). Después de la actualización, el asistente será más proactivo a la hora de convertir tablas, comparaciones y opciones en tarjetas.
Si no lo quieres, ve a Configuración → Charla fuera.

Esta versión de las palabras clave para la generación de tarjetas también ha cambiado a un conjunto de vocabulario de diseño: diseños ya preparados, como conchas, indicadores, tablas y etiquetas simples.
Un conjunto de escalas de color de datos, una entrada de gráfico y devoluciones de llamada declarativas. En el pasado, el modelo utilizaba código fuente de estilo escrito a mano en una pantalla.
Ahora hay formatos disponibles. Hay mas productos `table` Escriba, las tablas ya no están amontonadas en los documentos y se convierten en un montón de líneas verticales.

### Llamada de herramienta: atascada en la aprobación, nombre contaminado (P0)

Se superponen tres lugares. Mientras pises un lugar, una llamada a la herramienta será en vano:

- **Tiempo de espera de aprobación**: En modo estricto, cada tarjeta y cada actualización pendiente deben ser aprobadas para su aprobación.30 Rechazado automáticamente en segundos,
  La tarjeta desaparece inmediatamente. Las tarjetas y las tareas pendientes son exclusivamente para mostrar, no se escriben archivos y no se toca ningún equipo.
  Del mismo calibre que "renderizar datos", ahora ya no requiere aprobación.
- **El nombre de la herramienta está contaminado por texto**: El modelo ocasionalmente envía líneas y nombres de funciones juntos.
  (`…espera un momento.show_card`). Los nombres que no coincidan resultarán en el fracaso de toda la ronda. Ahora solo puedo igualar
  Se restituirán los nombres de las herramientas expuestas en esta ronda. Si no hay una lista, no hay que adivinar.
- **El parámetro es una oración humana completa.**: En este caso, antes se informó "Excepción de formato de parámetro". Ahora se entrega como texto principal.

Una cosa más: para las respuestas que ya se han escrito, no intente nuevamente simplemente "afirmar que desea ajustar la herramienta pero no puede llamarla".
Nunca tirarás por la borda lo que ya has escrito.

### Mensajería proactiva: pasar del sondeo basado en puntos a la contabilidad

El antiguo mecanismo realiza encuestas a intervalos fijos y se agrega un motor de puntuación para decidir si se abre la boca. Omitir cosas, repetir cosas, decir cosas que no se deberían decir,
Todo se reduce a esta estructura. Ahora cambie a un libro mayor:

- **Preocupado por el libro mayor**: El asistente escribe "cosas que quiere preguntarle en el futuro" y elige una para decirla cuando llegue el momento, en lugar de encontrar palabras de la nada.
- **cuenta de resultados**: Escriba si se hizo clic en él, si se respondió, si se tachó después de decirlo.
- **intimidad**: aprenda de estos comentarios y decida cuántas veces más puede hablar hoy.

Se han cambiado varias cosas en el paquete:

- Cinco niveles de reordenamiento del peso: Silencioso 3 / Restricción 6 / Estándar 15 / Activo 24 / Sin límite superior de apego
- En el panel del libro mayor, puede ver en qué ha estado pensando recientemente y cuántas veces puede hablar de ello hoy.
- Hay un "reinicio" adicional: volver al nivel estándar y borrar la cuota de hoy a cero.
- El período de silencio está vigente y no sonará cuando llegue la hora de dormir.
- Tres mesas están respaldadas y no se perderán al cambiar de máquina.

### Los recuerdos se pueden eliminar en lotes

Es inevitable que los recuerdos se confundan y se repitan. En el pasado, sólo podía eliminar elementos uno por uno. Después de eliminar un elemento de la lista, volvía a la parte superior.

Ahora mantenga presionado cualquier elemento para ingresar a la selección múltiple. El punto de la izquierda se convierte en un círculo de selección y solo queda "Cancelar/Eliminar" en la parte superior.
El círculo de nodos en la vista de línea de tiempo se sincroniza con un círculo seleccionado. Solo se actualizará una vez después de la eliminación y no volverá a la parte superior.

### El proceso es visible.

- Una barra plana encima del campo de entrada: lo que estás haciendo en este momento, varias subtareas y dónde se ha ido la herramienta. Haz clic para ver los detalles de esta ronda y desaparecerá automáticamente cuando finalice.
- Hay una tarjeta de proceso permanente al final del flujo de mensajes: qué se está haciendo, cuántos pasos y cuánto tiempo llevó. Girarlo hacia arriba no se reciclará.
- Cada paso se puede plegar de forma independiente, el paso fallido se marca en rojo y se proporciona un fragmento del resultado.
- Las estadísticas de cabeza cambiaron a "N herramientas · N pensamientos · N falló
- Las herramientas individuales también comenzaron a doblarse en tarjetas. En el pasado, se necesitaban más de 2 para recogerlos.
- Hay demasiados "Cerrar temas" atrapados en las subtareas en segundo plano. Los subprocesos que no hayan terminado de ejecutarse se pueden finalizar.

### La página de configuración se reorganiza en "¿Qué estás haciendo aquí?"

El nivel 1 se amplió de 7 grupos a 11 grupos: Asistentes y Personajes/Diálogo y Expresión/Interfaz y Visualización/Modelos e Inferencia /
Capacidades y Herramientas / Voz y Multimedia / Memoria y Conocimiento / Iniciativa y Automatización / Cuentas Conectadas y Seguridad /
Almacenamiento y espacio / Copia de seguridad y migración / Acerca de y diagnóstico.

- Las estrategias de aprobación de herramientas regresan a la página de herramientas; las restricciones de piso y las bases de datos de experiencias se colocan en la página de memoria; Los valores predeterminados de pensamiento profundo y los modelos de compresión se colocan en la página de enrutamiento de tareas.
- El subtítulo de cada entrada dice claramente "¿Qué puedes hacer si entras?"
- El paquete de emoticones se actualizó desde la parte inferior de la página "Medios" a una página separada.
- El backend permanente solía estar bajo la configuración "Teléfono pequeño", por lo que nadie podía encontrarlo. Ahora tiene entrada independiente.
- Se corrigió el bloqueo de inicio causado por la falta de canales de notificación para el servicio Keep-Alive.

### Canal: ahora puedo publicar fotos y también he editado mucho.

Telegram / Feishu / QQ / DingTalk tienen cada uno la implementación de medios salientes y todos usan una entrada.
En el pasado, los canales sólo podían enviar mensajes de texto.

El problema del cierre lateral está más oculto: DingTalk, Feishu,Telegram El mensaje con imagen anteriormente solo producía un literal "[fotos]",
La imagen no se descargó en absoluto y el enlace para identificar la imagen estaba completamente deshabilitado. Ahora los tres lo han descargado. Si falla, se degradará a un marcador de posición y se registrará.

QQ También pisé un montón:

- **Reanudar la carga después de la desconexión**: ID de sesión de persistencia y último número de secuencia; la reconexión devolverá el evento de desconexión. En el pasado, cuando se desconectaba la conexión, se abría una nueva sesión y los mensajes del medio se perdían inmediatamente.
- **Las ventanas de respuesta pasiva se calculan por separado**: El límite oficial es de 5 minutos para el chat grupal, 60 minutos para el chat individual y la frecuencia es 5/4. Solía ​​ser 60 minutos en ambos lados;
  El chat grupal utilizará la ID del mensaje caducado para enviar, y el chat individual caducará el tema al que se podría haber respondido dentro de una hora.
- **Devuelve una cadena vacía después de la expiración**: Esta cadena vacía simplemente omite la identificación del mensaje disponible en el caché, cámbiela nuevamente null
- **Los mensajes grupales no se repiten**:@ El robot alcanzará dos eventos y el mismo evento solo se procesará una vez; si no hay @, solo dejará rastros y no regresará.
- **No recibí el mensaje en 5 minutos. id**: Déjelo claro en el acto y ya no se verá obligado a decir "no me responderá"

Además, cuando haces clic en la lista de contactos, puedes ver toda la conversación en forma de burbuja, en lugar de mensajes en mosaico uno por uno;
El código de error de transmisión fallida se muestra en la parte superior de la lista, por lo que no es necesario volver a examinar el registro.

### Reelaboración del presupuesto contextual

En el pasado, los presupuestos históricos se daban en función de una proporción fija de la ventana, y las indicaciones del sistema, las definiciones de herramientas, las reservas de salida y los márgenes de seguridad se trataban como constantes.
Con una herramienta y varias herramientas, se puede encontrar el mismo fragmento de la historia en la sesión A,B La sesión no excede, y el ratio fijo no tiene conciencia de ello.

Ahora estos cuatro bloques se descuentan según la ocupación real, dejando un margen de seguridad real:8000 La ventana ya no da historial 4800,
Pero 2880, porque el modelo primero debe terminar de escribir esta respuesta. El juicio de superación de límites sólo mira la parte histórica.
Las indicaciones del sistema y los resultados de la búsqueda ya no se incluyen en el presupuesto comprimible.

La compresión en sí ha solucionado dos errores de larga data: el resumen ahora viene con "Qué archivos se han modificado en esta sección", que se extrae de la llamada a la herramienta de escritura.
No se basa en la representación del modelo; la versión anterior del resumen retroalimentará el modelo para solicitar la fusión y no es necesario aclarar el contenido inicial una capa a la vez.

### La ronda en la que la herramienta devuelve resultados debe fallar (P0)

Responses API El campo de resultado de la herramienta se escribe como `content`, lo que quiere el servidor es `output`,
directo 422 `missing field 'output'`. El rendimiento es que la ronda en la que se ejecuta la herramienta y se devuelven los resultados debe fallar.
No importa cuánta razón tengas en el pasado, todo fue en vano. Cambiado.

### El asistente empezó a tomar notas.

- **`note_thought`**: Escribir "cosas que quiero preguntarte en el futuro" en el libro de preocupaciones es el combustible para los mensajes proactivos.
- **`remember_preference`**: Precipite "No hagas esto en el futuro" en una restricción estricta e inyéctelo en la palabra inicial
- **`tool_status`**: Informe "registrado" y "realmente utilizable ahora" a la vez y agrúpelos según el canal que falta.
  Las herramientas que no están listas le indicarán claramente que no las acepte todavía.
- **verificación previa del canal**: Las herramientas que están condenadas a fallar se bloquean antes de ser llamadas y se proporciona una ruta para abrirlas. Ya no es necesario ponerse de acuerdo primero y luego chocar contra la pared.
- **`read_user_file`**: Lea el archivo que seleccionó a través del selector del sistema. Para tipos que no son de texto, explique directamente por qué no funciona.
- **`workspace_grep`**: busca recursivamente por texto o expresión regular en el espacio de trabajo, devuelve la ruta, el número de línea y el contenido. Deténgase cuando se alcancen 100 elementos y marque la marca de truncamiento.
- **`memory_config_get` / `memory_config_set`**: Antes, los parámetros de la memoria solo se podían arrastrar manualmente a la página de configuración, pero ahora el modelo también se puede leer y escribir.

### Mensaje activo: Prefiero no enviarlo

Se han agregado varias restricciones estrictas: no enviar mensajes dentro de las 3 horas posteriores a la última vez; no habrá mensajes dentro de los 10 minutos posteriores a lo que acabas de decir;
Se está generando y no se emitirá; si el cupo es insuficiente, no se emitirá. Deje que el modelo determine si se puede atender la llamada. Si no, omítelo.
No más calificaciones ganadas con tanto esfuerzo.

### Otros

- **Conexión entre sesiones**: Al abrir una nueva conversación, se recuperarán las últimas rondas de la conversación anterior (4~40 barra (ajustable), cerrada por defecto
- **motor de búsqueda personalizado**: Conecte su propio servicio de búsqueda al frente de la cadena Auto
- **DeepSeek Buscar**Conectarse a la cadena automática
- **Mostrar nombre del asistente**Activar, activado de forma predeterminada, desacoplado de la marca de tiempo
- **La tarea pendiente de la sesión está realmente almacenada.**, no se perderá al cambiar de sesión o reiniciar; la lista principal también indicará "Todavía hay N sesiones con asuntos pendientes"
- El receptor del canal solía estar "permanentemente muerto": si se desactivaba y se reactivaba, ya no se recibían mensajes. Ahora se ha cambiado a reintento retrasado.
- El estado de la tarjeta JSON solía estar separado en un solo lugar. Verá la cola al copiar, compartir, exportar, leer en voz alta y buscar en el índice. Ahora está condensado en tres salidas.
- Las configuraciones de la colección de cadenas (colección de momentos, colección de álbumes de fotos) no se incluyeron en la copia de seguridad y se perdieron sin ningún aviso al cambiarlas. Por favor agréguelos ahora.
- La posición de desplazamiento de la sesión se restablece y sigue en la misma posición después de eliminar el fondo.
- El umbral de compresión se unifica a 20/15 (memoria larga 10/8). Cambiar un punto ya no tendrá ruta y no tendrá efecto.
- avatar 28→36dp, el color de fondo de la burbuja sigue el color del tema.
- Problemas blancos sucios y superpuestos en las cápsulas del navegador, máscaras modales y barras de búsqueda en el techo bajo temas oscuros
- El botón "Desplazarse hacia abajo" se coloca de acuerdo con la altura real de la barra inferior y la nueva barra plana de proceso ya no lo mantiene presionado.
- El número de días de compañía se calcula en función del tiempo de creación del asistente y se restablecerá a cero si no se crea una nueva sesión.
- La ventana emergente de selección de modelo ya no solo tiene botones sino tampoco una lista.
- El error de importación de la copia de seguridad y el error de conexión de la página de inicio se han cambiado a una frase que se puede seguir, y el nombre de la tabla de la base de datos y el código de estado ya no se desechan.
- El visor de productos reconoce Markdown/Table/JSON/CSV y exporta extensiones por tipo.
- La llamada a la herramienta en medio de una ronda de respuesta ya no repetirá el avatar y el nombre.
- Los mensajes privados en teléfonos celulares pequeños ya no son accesibles a todos. Se calculan en función de la intimidad de la relación y el nivel de disposición, y se adhieren al límite superior diario y al período sin molestias.
- El proveedor preestablecido ha añadido el modelo:Agnes 2.0→2.5(contexto 131072→262144), varias estaciones de transferencia ya no son listas vacías
- La lista de modelos incorporados se actualizó hasta el 26 de octubre y se agregaron varias familias de modelos nuevos; la longitud del archivo ha aumentado de 137 KB a 184KB
- El tiempo de ejecución del nodo incorporado aumentó de 24 a 26 (npm 11.21.1),MCP Servicios, scripts de habilidades y terminales se ejecutan en él.
- Los paquetes de diagnóstico suelen venir con registros de archivos completos (1MB desplazarse). En el pasado, solo había 1000 elementos más recientes en la memoria y los problemas del canal a menudo solo necesitaban ser examinados después de eliminarlos.
- Se corrigió "La copia de seguridad que exportaste no se puede volver a importar": los documentos integrados se excluyeron durante la conciliación, el cálculo en el nuevo dispositivo fue 0 y la cantidad no coincidió.
- Se solucionó el problema de "reiniciar la computadora cada vez que falla una restauración": cuando falla la reversión, el libro mayor se cierra y se deja una copia para diagnóstico.
- Se solucionó el problema de incluirse en la lista de delegación; Se solucionó el conflicto entre "nombre escrito en el perfil de usuario" y ancla de identidad.
  Si cambia un nombre, habrá un problema con dos nombres.
- Si el moderador del chat grupal se deja en blanco, la plantilla dejará de ser válida. Ahora es compatible; Después de crear un grupo, ya no aparece "El moderador que configuré no tiene efecto".
- Termux El comando fue exitoso pero se informó el "código de error -1": el código de éxito originalmente era -1, que se ha corregido
- Los modelos de imagen y video ya no se consideran contextos 131072 según los valores predeterminados de la marca, lo que evita que se coloquen en largas colas de contexto.

### Instalación

- Simplemente sobrescriba e instale.**No es necesario desinstalar**, retención de datos
- base de datos **111 → 119**, que abarca 8 versiones, se inicia por primera vez la migración automática (incluidas tres nuevas tablas de mensajes activos)
- La firma es la misma que todas las versiones posteriores a la 2.6.1.
- esta edición**Desactivada la optimización R8 pass**, la ofuscación y el recorte de recursos todavía están habilitados.
  La razón es que la burbuja del mensaje es una sola línea de más de 2000,70+ Componible de parámetros, el uso de registros supera las 200 ranuras,
  R8 La optimización en este tamaño producirá dex con tipos inconsistentes, que serán rechazados por el validador de Android 14+.
  Se manifiesta como un bloqueo directo al abrir la conversación. El precio es que el cuerpo del paquete será más grande que la versión anterior.

### cosas conocidas

- Los antiguos parámetros de los mensajes activos (intervalo, probabilidad de envío, límite superior diario) no tienen equivalente en el nuevo modelo y ya no funcionan.
  El ritmo está determinado por el libro de atención y el nivel de iniciativa. Después de la actualización, el libro mayor estará vacío y permanecerá en silencio durante unos días. Esta es la intención del diseño.
- El alcance visible predeterminado de la herramienta ha cambiado: autoverificación, memoria, tarjeta, lectura de archivos, así como autoverificación de estado, notificación, organización de tareas y entrada de delegación.
  De "opcional" a "estándar", el asistente no es confiable `find_tools` También puedes verlo directamente.
- Esta versión de la descripción de la herramienta se cambió a chino en lotes y el idioma de descripción leído por el modelo cambió. Estas descripciones no cambian en la interfaz en inglés.
- El sistema indica que se ha agregado una nueva "Orden de efectividad": comando de ronda actual > Requisitos para este modo de sesión > Configuración de personalidad > Memoria, libro mundial, base de conocimientos.
  El tono de voz del personaje ya no domina tus solicitudes específicas para la ronda.
- La cuota de nivel activo se ha cambiado a un nuevo nivel. Después de actualizar desde la versión anterior, los tiempos restantes del día se volverán a calcular según el nuevo algoritmo.
- La mensajería activa ahora depende del asistente para hacer su propia contabilidad. Si el asistente nunca recuerda nada de lo que quería decir en una conversación,
  No tomará la iniciativa de hablar. Esto es por diseño, no roto.

---

# Muse v2.6.10 (English)

> Cards used to render at a third of their height and tool calls could stall on an approval prompt forever;
> proactive messages run on a different model now, memory can be deleted in bulk, and Settings is organized by what you came to do.

---

## Cards only showed half their content (P0)

Auto-height cards used to show nothing but the title strip. The WebView reports sizes in CSS pixels,
the code treated them as physical pixels and then divided once more by screen density (2.625),
so every card rendered at 38 percent of its height.

Sizes are now taken as CSS pixels directly. The model can also declare a card height through `show_card`
(`auto`, a fixed dp, or `16:9`), so multi-line layouts state their own height instead of letting the host guess,
and tall cards are no longer clipped.

**One change you will notice right away**: "prefer card output" is on by default again
(2.6.6 turned it off). After upgrading, the assistant will more readily turn tables, comparisons and option lists
into cards. Turn it off in Settings → Chat if you do not want that.

The card generation prompt now uses a design vocabulary: ready-made shells, metric rows, tables and badges,
a data color scale, a chart entry point and declarative callbacks. The model used to hand-write a screen of style code;
now it has layouts to fill. Artifacts gained a `table` type too, so tables are no longer crammed into a document
and rendered as a wall of pipes.

## Tool calls: stuck on approval, names polluted (P0)

Three separate problems, any one of which wasted a tool call:

- **Approval timeout**: in strict mode every card and every todo update opened an approval prompt.
  Ignore it for 30 seconds and it auto-rejects, so the card vanished. Cards and todos are pure display,
  they write no files and touch no device state, so they now match "render data" and need no approval.
- **Tool name polluted by prose**: the model sometimes submits its line and the function name as one string
  (`…hold on.show_card`). A name that does not match fails the whole round. Names are now recovered only when
  they match a tool exposed in the current round; with no list, it does not guess.
- **Arguments that are one plain sentence**: this used to raise "invalid argument format". It is now delivered as text.

One more: a reply that already produced real text is no longer retried for claiming a tool call it never made,
so written content is not thrown away.

## Proactive messages: from polling to a ledger

The old mechanism was a fixed-interval poll plus a scoring engine deciding whether to speak.
Missed messages, repeats, and saying the wrong thing all came from that shape. It is a ledger now:

- **Pending thoughts**: the assistant writes down what it wants to ask you later, and picks one when the moment fits,
  instead of inventing something to say
- **Outcome ledger**: whether what it said was opened, answered, or dismissed is recorded
- **Intimacy**: learned from those outcomes, and it decides how many times it may speak today

Around that:

- Five levels rebalanced: Quiet 3 / Reserved 6 / Standard 15 / Active 24 / Clingy uncapped
- The ledger panel shows what it is currently holding on to and how many times it may speak today
- A new "Reset" returns to Standard and clears today's quota
- Quiet hours are honored, so it stays silent when you should be asleep
- The three tables are included in backups

## Memory can be deleted in bulk

Memory accumulates wrong and duplicate entries, and deleting one at a time flung the list back to the top.

Long-press any entry to enter selection mode: the dot on the left becomes a check circle and the top bar
keeps only Cancel and Delete. The timeline view switches to check circles too. Deletion refreshes once,
so the list stays where it was.

## You can see what it is doing

- A strip above the input shows what is happening now, how many subtasks, and how far the tools got.
  Tap for this round's detail; it disappears when the round ends
- A persistent process card at the end of the stream shows what it did, in how many steps, and how long it took.
  Scrolling up does not recycle it
- Each step collapses independently; failures are marked red with a snippet of the result
- The header counts by type: N tools · N thoughts · N failures
- A single tool call now folds into a card too; it used to require two
- Background subtask cards gained "close thread", so finished-but-open threads can be dismissed

## Settings organized by what you came to do

The top level went from 7 groups to 11: Assistants and Persona / Chat and Expression / Interface and Display /
Models and Reasoning / Capabilities and Tools / Voice and Media / Memory and Knowledge / Proactive and Automation /
Accounts and Security / Storage / Backup and Migration / About and Diagnostics.

- Tool approval policy moved to the tools page; floor-based context limits and the experience library moved to memory;
  default deep thinking and the compression model moved to task routing
- Every entry's subtitle now says what you will find inside
- Stickers moved from the bottom of the media page to its own page
- Keep-alive used to sit under the mini-phone settings where nobody found it; it has its own entry now
- Fixed a startup crash when the keep-alive service had no notification channel

## Channels can send images, and a lot else got fixed

Telegram, Feishu, QQ and DingTalk each have an outbound media implementation behind one entry point.
Channels used to be text only.

The inbound side was worse than it looked: images arriving through DingTalk, Feishu and Telegram used to produce
the literal text "[image]" and were never downloaded, so vision was effectively dead on those channels.
All three download now, and fall back to a placeholder with a log line when they fail.

QQ had a run of its own:

- **Reconnect resume**: the session id and last sequence number are persisted and a reconnect replays the gap.
  A drop used to open a new session, losing everything in between
- **Separate reply windows**: the platform allows 5 minutes for groups and 60 for direct messages, with 5 and 4 replies.
  Both used to use 60, so groups sent with an expired message id and direct chats gave up early
- **Expired lookups returned an empty string**, which happened to bypass the usable message id in the cache; it returns null now
- **Group messages are no longer handled twice**: mentioning the bot matched two events, and a message without a mention
  is now recorded without a reply
- **When no message id within 5 minutes is available**, it says so instead of sending anyway and being read as silence

Tapping a contact also opens the whole conversation as bubbles instead of one line per message,
and a failed send shows its error code at the top of the list instead of staying in the logs.

## Context budget reworked

History budget used to be a fixed fraction of the window, treating the system prompt, tool definitions,
output reserve and safety margin as constants. With many tools, the same history overflows in one session
and fits in another, and a fixed fraction cannot tell.

Those four blocks are now deducted by their actual size, leaving a real safety margin: an 8000 window no longer
hands 4800 to history but 2880, because the model has to finish its reply first. The overflow check looks only at
history and no longer counts the system prompt and retrieval results against the compressible budget.

Compression itself had two long-standing problems: the summary now lists which files the compressed span touched,
taken from write-type tool calls rather than the model retelling it; and the previous summary is fed back
for merging, so early content no longer fades a little with every compression.

## The round that sends tool results back always failed (P0)

The Responses API request named the tool result field `content`; the server wants `output`
and answers 422 `missing field 'output'`. So the round right after a tool ran always failed,
no matter how well everything before it went. Fixed.

## The assistant keeps notes now

- **`note_thought`** writes down what it wants to ask you later; this is what feeds proactive messages
- **`remember_preference`** turns "don't do that again" into a hard constraint injected into the prompt
- **`tool_status`** reports what is registered and what actually works right now, grouped by the channel that is missing.
  Tools that are not ready say so up front instead of promising and failing
- **Channel preflight** stops a tool that cannot work before it runs, and says how to enable it,
  instead of agreeing first and hitting the wall
- **`read_user_file`** reads files you pick yourself through the system picker, and explains why it cannot read non-text types
- **`workspace_grep`** searches the workspace recursively by text or regex, returning path, line and content,
  stopping at 100 hits and marking the result as truncated
- **`memory_config_get` / `memory_config_set`** let the model read and write memory parameters that only a slider could change before

## Proactive messages would rather say nothing

Hard limits were added: nothing within 3 hours of the last message, nothing within 10 minutes of you speaking,
nothing while a generation is running, nothing when the quota is spent. Whether it can pick up the conversation
is left to the model, which can skip. No more forcing a message out of a score.

## Also

- **Cross-session carry-over**: a new conversation can bring the last few turns of the previous one (4 to 40, adjustable), off by default
- **Custom search engines**: plug your own search service in at the front of the Auto chain
- **DeepSeek search** joined the Auto chain
- **Show assistant name** toggle, on by default, decoupled from the timestamp
- **Session todos are persisted** now; switching sessions or restarting keeps them, and the main list notes
  how many sessions still have unfinished items
- Channel receivers used to die permanently: disabling and re-enabling a channel meant no messages ever again.
  They retry now
- Card state JSON used to be stripped in one place only, so copying, sharing, exporting, reading aloud
  and the search index all showed that trailing fragment. It is now stripped at three clear exits
- Backups missed string-set settings (moments favorites, gallery favorites), which were lost on a new device
  with no warning. Now included
- Session scroll position is persisted, so it survives the process being killed
- Compression thresholds unified at 20/15 (long memory 10/8), so changing one place actually applies everywhere
- Avatars 28 to 36dp, bubble color follows the theme
- Dark theme fixes for the browser capsule, modal scrim and pinned search bar
- The jump-to-bottom button is positioned from the real bottom bar height, so the new process strip no longer covers it
- Companion days count from the assistant's creation time, so a new session no longer resets it to zero
- The model picker no longer renders buttons with no list
- Backup import and onboarding connection failures now say what to do next instead of raising table names and status codes
- The artifact viewer recognizes markdown, tables, JSON and CSV, and exports with the right extension
- Tool calls in the middle of a reply no longer redraw the avatar and name
- Mini-phone direct messages are no longer equally clingy toward everyone; they follow intimacy and willingness,
  the daily cap and the same do-not-disturb window as the main chat
- Preset providers gained models: Agnes 2.0 to 2.5 (context 131072 to 262144), and several relay providers are no longer empty lists
- The built-in model catalog was refreshed through October 26 and gained several new model families; the file grew from 137KB to 184KB
- The bundled Node runtime moved from 24 to 26 (npm 11.21.1); MCP servers, skill scripts and the terminal all run on it
- Diagnostic bundles now include the full file log (1MB rolling). It used to carry only the last 1000 lines in memory,
  and channel problems are usually wanted after those are gone
- Fixed "my own export will not import": the self-check excluded built-in documents, so a new device counted 0 and the totals disagreed
- Fixed "a failed restore retries on every boot": when the rollback itself fails the ledger is settled and the copy kept for diagnostics
- The delegation list no longer includes the assistant itself, and the name written in your profile no longer fights
  the identity anchor and produces two different names
- An empty group chat host no longer silently disables the template you picked
- A Termux command that succeeded no longer reports "error code -1"; the success code is -1
- Image and video models are no longer treated as 131072-context because of a brand default

## Installation

- Install over the existing app, **no uninstall needed**, data is kept
- Database **111 to 119**, across 8 versions; the first launch migrates automatically, including the three new proactive tables
- Same signing certificate as every release since 2.6.1
- **R8 optimization passes are disabled in this version**; obfuscation and resource shrinking stay on.
  The message bubble is a single 2000+ line Composable with 70+ parameters and more than 200 registers of pressure;
  R8 optimizing at that size emits dex with inconsistent types, which the Android 14+ verifier rejects,
  surfacing as a crash the moment you open a conversation. The APK is somewhat larger than the previous version as a result

## Known issues

- The old proactive settings (interval, send probability, daily cap) have no counterpart in the new model and no longer apply;
  pacing comes from the ledger and the proactive level. After upgrading the ledger is empty, so it stays quiet
  for the first few days. That is intended
- Default tool visibility changed: self-check, memory, cards and file reading, plus status check, notifications,
  task organization and delegation, moved from optional to standard, so the assistant sees them without `find_tools`
- Tool descriptions were translated to Chinese in one sweep, so the language the model reads them in changed.
  These descriptions do not follow the English interface
- The system prompt gained a precedence section: this turn's instruction > this session's mode > persona >
  memory, world book, knowledge base. A character voice no longer overrides what you asked for right now
- Proactive levels use new magnitudes, so the remaining count for today is recalculated once after upgrading
- Proactive messages now depend on the assistant keeping its own ledger. If it never wrote down anything it wanted to say
  during a conversation, it will not speak up; that is by design, not a fault