Muse v2.6.1.

> Esta versión cubre todos los cambios desde el lanzamiento de v2.6.0, enfocándose en reparaciones y agregando un tiempo de ejecución de Python integrado.

## nuevas características

- **Tiempo de ejecución de Python incorporado**: Ejecute Python 3.14 directamente dentro de la aplicación (biblioteca estándar completa:ssl/sqlite3/zlib/hashlib/json/re etc.; lectura y escritura de archivos, red), no es necesario instalar Termux ni ninguna aplicación externa. Nuevas herramientas disponibles para asistentes `execute_python_script` Ejecute directamente scripts de Python y admita pip para instalar paquetes de terceros puros de Python; Los complementos también se pueden escribir en Python (manifest de `runtime` campo).
- **Cambio global de "retorno forzado al pensamiento"**(ajustes → Chat): una vez activado, ya no está limitado por la configuración predeterminada del asistente. Mientras el modelo admita el razonamiento, volverá al proceso de pensamiento.
- **Mensajería activa "abierta todo el día"**(ajustes → mensajes no solicitados → Período de no molestar): interruptor agregado. Anteriormente, solo se podían usar las horas de inicio y finalización para expresar el rango de envío permitido, que no podía completar 24 horas (si se establecían 23-23, no se enviaría nada). No había opción cuando querías "recibirlo en cualquier momento". Puede recibir mensajes no solicitados durante las 24 horas posteriores a la apertura.

## Correcciones importantes

- **El proceso de pensamiento se filtró al texto.**: Se solucionó el problema por el cual algunos modelos de inferencia (como la serie DeepSeek) muestran la cadena de pensamiento como texto en escenarios de llamada de herramientas y el interruptor "Mostrar proceso de pensamiento" no se puede desactivar. Mantenga la línea final de "sin respuestas en blanco".
- **Accesibilidad falso positivo**: Corregido "El sistema ha activado la accesibilidad y el asistente de permisos muestra que está activado, pero cuando el asistente llama a la herramienta, informa que los permisos no están activados".——El estado del permiso cambia a actualización en tiempo real y el caché anterior al inicio ya no se lee.
- **Fallo al seleccionar texto**: Se solucionó el problema de fallas al seleccionar texto en cualquier interfaz y hacer clic en "Procesar texto" en el menú flotante del sistema.
- **El resumen diario de la página de inicio está truncado**: El resumen ya no se comprime en dos líneas y se omite, sino que se muestra completamente con saltos de línea naturales; la longitud está controlada por el lado de la generación.
- **No se puede obtener una vista previa del texto largo del flujo de memoria**: Ahora se puede hacer clic directamente en recuerdos largos en la línea de tiempo de la memoria para ver el texto completo sin tener que ingresar a la página de edición.
- **Fish Audio Clonación de voz**: Corregido a puntos finales y campos oficiales. Anteriormente, los errores se informaban de manera estable incluso si la clave API era correcta.
- **Plataforma de edición de proveedores**: Se solucionó el problema por el cual la clave API y otros estados de la anterior se trasladaban al siguiente al editar diferentes proveedores continuamente.
- **API Key Pegar verificación**: Proporciona un mensaje legible cuando se mezclan caracteres no válidos, como el chino, en la clave, reemplazando el incomprensible mensaje de error original.
- **Codex Iniciar sesión**: Complete la identificación del encabezado de la solicitud y el análisis de la lista de modelos.
- **Fallo al ver la biblioteca de memoria**: Se corrigió un fallo ocasional al explorar la memoria en el ámbito "Todos"——Las memorias del asistente principal y de cada subasistente se almacenan por separado y están numeradas respectivamente. Las entradas con el mismo número colisionarán y la lista no se podrá representar.
- **Ocasionalmente falla al abrir la aplicación.**:Sistema fijo "apagar animación" (opciones de desarrollador/Cuando la duración de la animación se establece en 0 en el modo de ahorro de energía, la página ocasionalmente fallará durante el cambio de página.——Reducir la duración de la animación a 0 provocará valores ilegales en el cálculo del progreso del cambio.
- **Barra flotante de registro rápido**: soluciona el problema de que el cuadro de entrada a veces no puede recibir el foco y el teclado virtual no aparece después de expandir la barra flotante.
- **La copia de seguridad automática ahora incluye configuraciones**:Anteriormente, solo sesiones empaquetadas de copia de seguridad automática diaria/memoria/Para los datos de la base de conocimientos, la configuración se perderá después de la restauración con un solo clic desde el "Registro de copia de seguridad reciente"; ahora es consistente con la exportación manual y la copia de seguridad en la nube, y los elementos de configuración también se respaldan y restauran juntos (las credenciales confidenciales, como las claves del proveedor, aún no se incluyen).

## Interfaz y experiencia

- Los componentes públicos, como ventanas emergentes, capas emergentes inferiores, títulos de grupos, filas de listas y cuadrículas de múltiples imágenes, están unificados y organizados: las ventanas emergentes son más compactas, las máscaras ya no oscurecen el área de navegación, la alineación de los títulos de grupos se corrige, los títulos largos ya no abarrotan el diseño y varias imágenes se cambian a cuadrículas de miniaturas cuadradas.
- Los botones de eliminación de imágenes están unificados en componentes compartidos (reservando suficiente área táctil).
- **Los mensajes que envías se pueden eliminar directamente**: Se ha agregado un botón de eliminar a la barra de acceso directo de los mensajes del usuario (anteriormente, solo copiar/Inténtalo de nuevo/Editar, eliminar (oculto en el menú de pulsación larga). Al eliminarlo, se procesará junto con su respuesta y se proporcionarán las opciones "solo este artículo/junto con los siguientes".
- **El asistente usa la forma incorrecta de ejecutar Python**: Cuando le pide al asistente que use Python, se ejecutará en la línea de comando para buscar `python` Intérprete, hojeando directorios y holgazaneando una y otra vez. El punto de entrada correcto para el tiempo de ejecución integrado es una herramienta especial. La entrada anterior no fue enviada al modelo con la solicitud, por lo que no se pudo ajustar en absoluto. Ahora se ha solucionado y codificado para arrancar (este es el único punto de entrada para ejecutar Python, no busque el intérprete nuevamente).
- **Un fragmento de JavaScript no debe ejecutarse**: Una tabla de estado del entorno limitado de JS integrado utiliza nulo como clave (no permitido por algunas estructuras de datos), lo que hace que todas las llamadas de ejecución de JS integrado informen errores estables. Fijado.
- **La herramienta aún no se puede cargar después de recuperarla.**: Después de recuperar una herramienta usando la biblioteca de herramientas, dirá“Próxima ronda disponible”, pero cuando realmente se llama, informa "no expuesto en esta ronda". La razón es que el alcance de la búsqueda no considera la configuración de la herramienta del asistente, por lo que hace promesas vacías a herramientas fuera de la configuración. Ahora la búsqueda sólo arroja las herramientas que realmente están disponibles en este asistente. Si no se encuentran las herramientas, se le informará directamente para habilitarlas en la configuración del asistente.
- **Aparece un botón de acceso directo debajo de la tarjeta de plan/tarjeta de ejecución**: Las acciones personalizadas agregadas en el "Botón de acceso directo a mensajes" aparecían anteriormente debajo de la tarjeta de tarea (tarjeta de plan/tarjeta de ejecución). Estas ubicaciones no deberían tener botones de acceso directo a mensajes y ahora están excluidas.

## Descripción

- Esta versión se centra en reparaciones y estabilidad, y se recomienda actualizarla.
- Al mismo tiempo, se borró un lote de recursos integrados que ya no se utilizan y, en consecuencia, se redujo el tamaño del paquete de instalación.

---

# Muse v2.6.1 (English)

> This release covers all changes since v2.6.0, focused on fixes, plus a new embedded Python runtime.

## New

- **Embedded Python runtime**: Run Python 3.14 directly inside the app (full standard library: ssl/sqlite3/zlib/hashlib/json/re; file and network access), with no need to install Termux or any external app. Assistants can use the new `execute_python_script` tool, install pure-Python packages via pip, and plugins can opt into Python (manifest `runtime` field).
- **Global "always request reasoning" switch** (Settings → Chat): when enabled, reasoning is requested whenever the model supports it, regardless of assistant defaults.
- **"All day" for proactive messages** (Settings → Proactive messages → Quiet hours): new switch. The allowed window could only be expressed as a start/end hour range, which cannot cover a full 24 hours (23-23 would block every message), so there was no way to say "any time is fine". Enable it to allow proactive messages at any hour.

## Fixes

- **Reasoning leaking into the reply body**: fixed reasoning chains being shown as message content for some reasoning models (e.g. DeepSeek family) during tool calls, where the "show reasoning" switch could not hide them. The never-empty reply guarantee is preserved.
- **Accessibility permission misreport**: fixed "system shows accessibility enabled and the wizard agrees, but tools report permission not granted" by refreshing permission state live instead of reading a stale startup cache.
- **Crash on selecting text**: fixed a crash when selecting text anywhere and tapping "Process text" in the system floating menu.
- **Home daily summary truncated**: summaries are no longer squeezed into two ellipsized lines; length is controlled at generation time.
- **Memory timeline long entries**: long memories can now be expanded in place instead of only inside the editor.
- **Fish Audio voice cloning**: corrected to the official endpoint and fields; previously failed even with a valid API key.
- **Provider edit state bleed**: fixed state (such as API key) from one provider carrying over to the next when editing sequentially.
- **API key paste validation**: readable message when the key contains non-ASCII characters, replacing the previous cryptic error.
- **Codex sign-in**: added request headers and model list parsing.
- **Crash while browsing memories**: fixed an occasional crash when viewing memories in the "All" scope — main and sub-assistant memories are stored separately with independent numbering, and same-numbered entries collided so the list could not render.
- **Occasional crash on app open**: fixed an occasional crash during screen transitions when system animations are turned off (developer options / battery saver setting animation duration to 0) — zero-length animations produced an invalid transition progress value.
- **Quick capture overlay**: fixed the input sometimes not receiving focus so the keyboard did not appear when the overlay expanded.
- **Automatic backup now includes settings**: the daily automatic backup previously packaged only conversations, memories and knowledge data, so restoring from "recent backups" lost your settings. It now backs up and restores settings too, matching manual export and cloud backup (provider secrets and similar credentials are still excluded).

## UI and polish

- Unified dialogs, bottom sheets, section labels, list rows and multi-image grids: tighter dialogs, scrim no longer dims the navigation area, corrected section label alignment, long titles no longer break the layout, square thumbnail grid for multiple images.
- The image remove button is now a shared component with an adequate touch target.
- **Delete your own messages directly**: the quick action row for user messages now includes delete (it previously only had copy / retry / edit, with delete hidden in the long-press menu). Deleting also removes its reply, and offers "only this one / with following".
- **Assistant took the wrong path to run Python**: asked to run Python, the assistant would hunt for a `python` interpreter on the command line and scan directories, spinning without result. The embedded runtime has one dedicated tool as its entry point, but that tool was not being sent to the model, so it could not be called at all; this is fixed, with guidance pinned down (it is the only entry point — stop looking for an interpreter).
- **A JavaScript execution path always failed**: an internal JS sandbox state table used a null key (not allowed by the data structure), so every built-in JS execution call failed consistently. Fixed.
- **A tool found via search still could not be called**: after searching the tool library for a tool, it would say "available from your next call", but the actual call failed with "not exposed in this turn". The search range ignored the assistant's own tool configuration, so it promised tools that were configured off. Search now returns only what the assistant can actually use, and says so plainly when a tool needs to be enabled in assistant settings.
- **Quick action buttons appearing under plan/execution cards**: custom actions added in "message quick actions" used to show up under task cards (plan / execution cards). Those spots should never carry message quick actions; they are now excluded.

## Notes

- This release is primarily fixes and stability; upgrading is recommended.
- A batch of unused built-in resources was removed, making the install package smaller.
