# Muse v2.5.0

Esta versión es la versión oficial posterior a la v2.4.6 y cubre todas las actualizaciones acumulativas desde la v2.4.2:AI Genere refuerzo de enlaces, actualización del sistema de herramientas, rediseño de la interfaz y el campo de entrada, carpeta de la base de conocimientos, extensión de importación de terceros y un lote de correcciones de estabilidad.

## nuevas características

### 1. Carpeta de la base de conocimientos

La base de conocimientos ahora es compatible**carpeta independiente**: Puede crear, cambiar el nombre y eliminar carpetas (incluidas las carpetas vacías), los documentos se pueden clasificar en carpetas y el alcance se puede delimitar por carpetas durante la recuperación.

- Las carpetas pueden existir de forma independiente y ya no están adjuntas a la ruta del documento.
- Cuando el asistente vincula la base de conocimientos, además de toda la base de datos, también puede**Vincular a una carpeta específica**, varias carpetas se convierten cada una en dominios de búsqueda independientes sin interferencias.
- Cuando no se selecciona ninguna carpeta, se mantiene el comportamiento de enlace original de toda la biblioteca y los usuarios antiguos no se darán cuenta.

### 2. Extensión de importación de terceros

- **Nueva versión de exportación de fragmentos ChatGPT**(`chat.json` + Se pueden importar varios archivos de fragmentos directamente, los fragmentos se fusionarán automáticamente y se reconstruirá el orden de la sesión.
- importar par**Chat grupal, asistente, configuración de proveedores.**La cobertura de campo es más completa y se dan razones específicas cuando falla.

### 3. Actualización del sistema de herramientas

- Salida larga de la herramienta (lectura de archivos,Shell, rastreo web, etc.) soporte**Captura y paginación**, el contenido muy largo se trunca con fidelidad y ya no actualiza la pantalla.
- Nuevo**Herramienta de recuperación de sesiones**, el asistente puede buscar contenido histórico de conversaciones.
- Los permisos de herramientas, los niveles de riesgo y las políticas de privacidad de datos se endurecen simultáneamente.

### 4. Refinamiento de consultas de búsqueda en Internet

Haz la entrada coloquial antes de buscar.**Conservador y refinado**:Quitar"ayudame""gracias"Como los clichés y las partículas modales, las consultas muy largas se truncan por palabra; Las palabras clave cortas y las consultas con patrones de oraciones claros se transmiten tal como están para evitar la eliminación accidental de información válida.

### 5. Rediseño del campo de entrada

- La isla de entrada se cambia a**Cápsula real**La forma se levanta en su totalidad y ya no se pega al borde inferior.
- Cambie la clave de envío a**avión de papel sólido**El ícono (el único ícono sólido en todo el conjunto de íconos que se enfatiza deliberadamente, haciendo que la operación principal sea identificable de un vistazo) se adapta automáticamente a colores claros y oscuros según el tema.
- Soporte de mensajes de usuario**Edición en línea**Botón de acceso directo.
- comando de barra diagonal**finalización en línea**.
- Varias operaciones dentro de una fila convergen en el menú "Más" y las burbujas están más limpias.
- Mapa de mensajes, soporte de índice alfabético**Arrastrar posicionamiento continuo**.

### 6. Mejoras en la página de traducción

- Reparar área de resultados de traducción**bloque vacío gris**.
- Soporte de historial de traducción**Mantenga presionado para copiar**, la traducción y el texto original se copian juntos en un formato fijo.

### 7. Panel de vista previa del diseño de configuración de chat

ajustes → Se ha agregado el área "Diseño de texto" del chat.**Vista previa en vivo**:Dos burbujas simuladas (usuario/AI) junto con el tamaño de fuente, el espaciado entre caracteres, las esquinas redondeadas, el banner, la marca de tiempo, el nombre del modelo,MOOD/Piensa que los interruptores de la pantalla cambian instantáneamente, lo que ves es lo que obtienes.

### 8. efecto de vidrio líquido

Se agregó el modo visual "Vidrio líquido" (Configuración → Apariencia): barra superior, campo de entrada y**burbuja de mensaje**Cambiado a una textura de vidrio, el mensaje aparece suavemente en perspectiva mientras rueda debajo del vidrio.

En referencia a la solución de estilo dual de Operit AI, proporciona dos texturas de vidrio y tres niveles de intensidad, que los usuarios pueden elegir según el rendimiento del dispositivo:

- **vaso de agua**: Delgado y translúcido, con una gran cantidad de contenido revelado, adecuado para usuarios a quienes les gusta una apariencia y sensación claras.
- **vidrio esmerilado**: Blanco lechoso espeso (predeterminado), textura clásica de vidrio esmerilado
- **nivel de fuerza**: Bajo/Medio/Alto —— Para dispositivos de gama baja, se recomienda seleccionar "Bajo" o apagarlo directamente para obtener la mejor suavidad.

Android 10+ Disponible; Los dispositivos más antiguos cambian automáticamente a sombreado translúcido. Migración automática de conmutadores booleanos antiguos (en→helado, apagado→cerrar).

## Correcciones de estabilidad

### 1. Interrupción de la transmisión y reintento de endurecimiento del enlace

- Nuevo**Vigilancia del primer evento**: Cuando el primer evento no se recibe durante mucho tiempo después de enviar la solicitud, se interrumpirá y avisará activamente y ya no girará infinitamente.
- Provider La clasificación de errores es más detallada y se manejan la cancelación de carrera, el reintento de generación y la continuación y reubicación de llamadas de herramientas; "Desactivar el pensamiento profundo" ya no se disfraza como el razonamiento de nivel más bajo.

### 2. Programación en segundo plano y coherencia de escritura de canales

- Agregado antes de que finalice el servicio de recepción.**puerta de consistencia**: Espere hasta que se complete la escritura antes de detenerse para evitar perder mensajes.
- Alineación de rutas de escritura para respaldo, resumen diario, mensajes activos, tareas programadas y programación de chat grupal.inbox Restaurar los límites es más estable.

### 3. Temperatura de muestreo y salida del presupuesto de salida

- Algunos proveedores `temperature` Con un rango abierto (como SenseTime 2.0 es ilegal), si el control deslizante está lleno o el cartucho de caracteres tiene alguna temperatura, se emitirá HTTP 400. Ahora la exportación está sujeta de manera uniforme.
- Cuando el modelo no declara un límite superior de salida, todo el contexto ya no se considera como `max_tokens`, devuelve el valor predeterminado del proveedor, corrige `max_tokens exceeds the limit`.

### 4. Las respuestas siguen automáticamente el desplazamiento

Siga automáticamente los que antes habrían estado al final del área de mensajes."Pensando/Marcador de posición/tarjeta de tarea"El artículo se interrumpe y no sigue durante todo el período de respuesta. Llega ahora/Después de cruzar el final del área de mensajes, se contará hasta el final y se seguirá automáticamente, y el usuario se detendrá inmediatamente al deslizar el dedo hacia arriba.

### 5. Fantasma en blanco después del cierre del proceso de herramienta

Después de desactivar "Mostrar proceso de llamada de herramienta", solo los mensajes de carga de herramienta se ocultan en la capa de renderizado, pero aún ocupan los elementos de la lista. Cada rueda de herramientas continua acumula un espacio entre mensajes, dejando un gran espacio entre los bloques de pensamiento. Ahora estos mensajes no se ingresan en la lista de renderizado y el espacio en blanco desaparece por completo.

### 6. Gesto de retorno del sistema de base de conocimientos

Utilizar el gesto de retroceso del sistema en una carpeta o en la vista de la base de conocimientos antes salía directamente de toda la página de la base de conocimientos. Ahora salga en el mismo nivel que la tecla de retorno en la barra superior: primero borre la búsqueda → Volver a la carpeta anterior → Volver a la lista de la base de conocimientos → Salida.

### 7. Los temas oscuros y claros cambian instantáneamente con el sistema.

Cuando la aplicación está configurada en "Seguir el sistema", el sistema debe salir y volver a ingresar antes de que el cambio entre colores oscuros y claros surta efecto (Manifest Declarar el manejo de cambios de configuración de uiMode hace que la actividad no se reconstruya). El cambio ahora entra en vigor inmediatamente y no es necesario reiniciar.

### 8. No se puede acceder al control deslizante del mapa de mensajes

El punto de acceso táctil del mapa de mensajes se redujo de 36 dp a 24 dp y se pegó al borde derecho de la pantalla. Estaba cubierto por la banda de gestos de retorno del sistema Android 10+ (aproximadamente 20 dp). El área táctil real era insuficiente, lo que provocaba que no se pudiera activar el control deslizante durante conversaciones largas. Ahora el punto de acceso se amplía a 40 dp y se retrae para evitar la banda de gestos del sistema. Se puede acceder arrastrándolo normalmente; los clics en la fila de operación en la parte inferior del mensaje no se ven afectados.

### 9. Generar excepción de notificación de progreso

Solucione tres tipos de problemas de notificación:

- **El número de palabras no cambia.**: Cuando el servicio de primer plano actualiza la notificación, siempre se transmiten 0 caracteres, lo que cubre la cantidad real de caracteres en la capa de la interfaz de usuario. El servicio ahora muestra la cantidad real de palabras generadas y el progreso es visible dinámicamente.
- **residuo de notificación**: Una vez completada la generación, la barra de progreso dinámica con la misma cantidad de palabras restantes en la barra de notificaciones permanece sin cambios (incluso cuando se cierra la aplicación). El motivo es que la notificación del servicio de recepción no se puede cancelar individualmente y debe enviarse primero a la recepción y luego borrarse. Ahora el servicio se limpia sincrónicamente cuando se detiene y también se limpia cuando el sistema lo cierra a la fuerza.
- **Las notificaciones se acumulan**: El problema de la coexistencia de múltiples notificaciones se eliminará con la solución anterior.

### 10. Interrupción de generación de fondo

El enlace keepalive en segundo plano (servicio en primer plano + wakelock + rutina de nivel de aplicación) está completo. Algunas ROM radicales (Xiaomi/Huawei, etc.) aún pueden restringir la red en segundo plano y causar interrupciones en la transmisión. Se recomienda agregar Muse a la lista blanca de optimización de batería./Permitir la ejecución en segundo plano. El contenido después de la interrupción se conserva automáticamente a través del punto de control y se puede reanudar al regresar a la recepción.

### 11. Otros

- Corrección de atribución de altavoz para análisis de memoria, alineación de puerta de escritura de memoria.
- El tiempo de entrega de Binder de la pantalla virtual está alineado con los campos del protocolo; el Agente del teléfono celular realiza una reversión jerárquica (Root/Shell degradar) y mostrar falla en la actualización.
- Complete y corrija 7 cadenas de idiomas; documentación de conocimientos integrada y actualizaciones del catálogo de modelos (2026-09-30 versión,35 provider / 1024 modelo).

## Información de construcción

- Versión:2.5.0 (250)
- Versión de la base de datos:109
- Distribución:arm64-v8a / armeabi-v7a / universal

---
# Muse v2.5.0 (English)

The first stable release after v2.4.6, covering everything accumulated since v2.4.2: AI generation pipeline hardening, tool system upgrades, an input bar and UI redesign, knowledge base folders, third-party import extensions, and a batch of stability fixes.

## New Features

### 1. Knowledge base folders

Knowledge bases now support **standalone folders**: create, rename and delete folders (including empty ones), organize documents into them, and scope retrieval to a folder.

- Folders exist independently, no longer tied to document paths.
- When binding a knowledge base to an assistant, you can bind **specific folders** in addition to the whole base. Each folder becomes an isolated retrieval scope.
- With no folder selected the existing whole-base binding applies, so existing users see no change.

### 2. Third-party import extensions

- **New-format ChatGPT shard exports** (`chat.json` plus multiple shard files) import directly; shards are merged and conversation order rebuilt automatically.
- Imports cover group chats, assistants and provider configs more completely, and report specific reasons on failure.

### 3. Tool system upgrade

- Long tool outputs (file reads, shell, web fetches) now support **capture and paging**, with truncation that preserves fidelity instead of flooding the screen.
- A new **conversation search tool** lets the assistant search past sessions.
- Tool permissions, risk levels and data privacy policies are tightened in sync.

### 4. Web search query refinement

Before searching, colloquial input is **conservatively refined**: filler words and polite phrases are removed, and overly long queries are truncated by word. Short keywords and clearly structured queries pass through untouched to avoid deleting useful information.

### 5. Input bar redesign

- The input island is now a **true pill** shape, lifted off the bottom edge.
- The send key uses a **solid paper-plane** icon (the only intentionally weighted filled icon in the set, instantly recognizable as the primary action) and adapts to light/dark themes automatically.
- User messages gain an **inline edit** shortcut.
- Slash commands get **inline completion**.
- Multiple inline actions are consolidated into a "more" menu for cleaner bubbles.
- The message map and letter index support **continuous drag positioning**.

### 6. Translate page improvements

- Fixed the **gray empty block** in the translation result area.
- Translation history supports **long-press to copy**, copying translation and source text together in a fixed format.

### 7. Chat settings typography preview panel

The "Typography" section in Settings → Chat gains a **live preview**: two mock bubbles (user/AI) that update instantly with font size, letter spacing, bubble radius, full-width mode, timestamp, model name, and MOOD/reasoning visibility toggles.

### 8. Liquid glass effect

A new "Liquid glass" appearance mode (Settings → Appearance) applies **app-wide**: the top bar, input bar, message bubbles, and back/more buttons can all use glass material, with messages scrolling softly beneath.

Inspired by Operit AI's dual-style approach:

- **Water glass**: light and translucent, more content shows through
- **Frosted glass**: thick and milky (default), classic frosted feel
- **Strength slider** (0-100%): continuous blur adjustment; low-end devices can dial it down or turn it off for the best smoothness

The chat top bar becomes a **standalone glass island** (aligned with the group chat top bar language), with back and menu buttons integrated. Available on Android 10+; older devices fall back to translucent tinting automatically. Legacy toggles migrate automatically.

## Stability Fixes

### 1. Streaming interruption and retry hardening

- A **first-event watchdog**: when no first event arrives long after the request is sent, generation is aborted with a clear notice instead of spinning forever.
- Provider errors are classified more finely; cancel races, retry generations and tool-call continuation fragments are all handled. Turning off deep thinking no longer masquerades as the lowest reasoning tier.

### 2. Background scheduling and channel write consistency

- A **consistency gate** before a foreground service stops: pending writes complete before shutdown, so messages are no longer lost.
- Write paths for backup, daily summary, proactive messages, scheduled tasks and group chat scheduling are aligned; inbox recovery boundaries are more robust.

### 3. Upstream constraint clamping at the request boundary

- Some providers constrain `temperature` with an open interval (e.g. SenseNova rejects 2.0). A maxed-out slider or an arbitrary value from an imported character card no longer causes HTTP 400; values are clamped at the request boundary.
- When a model declares no output limit, the full context is no longer used as `max_tokens`; the provider default applies instead, fixing `max_tokens exceeds the limit`.

### 4. Auto-follow scrolling during replies

Auto-follow used to break whenever trailing items ("thinking", placeholders, task cards) appeared in the message area, so the list never followed during a reply. It now treats "reached or passed the end" as at-bottom and follows actively; a manual scroll up still stops it immediately.

### 5. Ghost whitespace after hiding tool details

With "show tool call details" off, messages carrying only tool payloads were hidden by the renderer but still occupied list items; consecutive tool rounds stacked message gaps into large blank areas between thinking blocks. These messages are now excluded from the render list entirely.

### 6. Knowledge base system back gesture

Inside a folder or knowledge base view, the system back gesture previously exited the whole knowledge page. It now mirrors the top-bar back button: clear search → up one folder level → back to the KB list → exit.

### 7. Dark/light theme follows system instantly

With the app set to "follow system", toggling the system dark mode previously required restarting the app (the Manifest declared uiMode config-changes handling, so the Activity was not recreated). The switch now applies immediately.

### 8. Message map rail could not be summoned

After the message map touch zone was narrowed from 36dp to 24dp flush against the screen edge, it was covered by the Android 10+ system back gesture zone (~20dp), leaving too little reachable area — the rail could not be summoned in long conversations. The touch zone is now 40dp wide and inset away from the system gesture zone; bottom action-row taps are unaffected.

### 9. Others

- Memory parsing speaker attribution fixed; memory write gates aligned.
- Virtual display Binder handoff timing and protocol fields aligned; phone agent execution falls back across privilege levels (Root/Shell) and refreshes on display loss.
- Strings completed and corrected across 7 languages; bundled knowledge docs and model catalog updated (2026-09-30, 35 providers / 1024 models).

## Build

- Version: 2.5.0 (250)
- Database version: 109
- Distributions: arm64-v8a / armeabi-v7a / universal
