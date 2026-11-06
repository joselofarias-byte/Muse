Muse v2.6.0 Versión funcional.

> Esta versión del registro de cambios cubre todos los cambios desde el lanzamiento de la versión 2.5.8 (incluido el contenido de la versión 2.5.9 que no se ha publicado por separado).

## nuevas características

- **GPT Inicio de sesión de cuenta (Codex OAuth)**: Admite inicio de sesión con un clic con cuenta ChatGPT (autorización del navegador + PKCE), inyecta automáticamente el encabezado de solicitud ChatGPT-Account-ID, conexión directa `chatgpt.com/backend-api/codex` Utilice la cuota de suscripción sin clave API; y admite acceso directo con un clic a la página de autorización. Solución unificada para xAI/GitHub y otros inicios de sesión OAuth con un solo clic (completa el client_id público,Device Flow ventana emergente del código de verificación).
- **Codex La lista de modelos está conectada al directorio de la nube.**:Codex Los modelos disponibles están sujetos al directorio de modelos en la nube (ya no está preestablecido en la aplicación) y se retirarán según la versión oficial./Se agregaron actualizaciones automáticas.
- **Tarjetas generativas (show_card)**: El asistente puede generar tarjetas interactivas (gráficos, paneles, procesos) directamente en la conversación./Imágenes comparativas, componentes jugables), esta versión completa el renderizador de tarjetas.
- **Tasker Portal de automatización externo**: Desactivado de forma predeterminada; la página de configuración puede habilitar explícitamente la transmisión externa para activar una ronda de finalización de texto y solicitar que se puedan consumir los créditos del modelo. No se otorgarán permisos de herramientas locales.
- **Operaciones de acceso directo a mensajes configurables**:Via Menú personalizado, grupo de acciones completo + lista de activación ordenada (copiar/pensar/Traducción/Compartir/regenerar/eliminar, etcétera).
- **Las copias de seguridad incluyen opcionalmente claves de proveedor**: Opcional si se incluye la clave API del proveedor al realizar la copia de seguridad.
- **Herramienta de autoprueba de plataforma**: Nuevo `platform_selfcheck`, el asistente puede autoverificar el entorno operativo y el estado de preparación de la capacidad.
- **mejora diagnóstica**: La "Exportación de información de diagnóstico" en la página de configuración cubre la generación del búfer de anillo de enlace; cuando la herramienta llama a la excepción de la primera pieza, registra el fragmento original para facilitar la resolución de problemas de excepciones de formato ascendentes.

## Correcciones importantes

- **A2A Aislamiento de conversación remota**: Las solicitudes remotas ya no cambian los chats en primer plano ni llaman a las herramientas del dispositivo local;context/task De forma aislada, aún pueden continuar múltiples rondas de conversaciones de texto y la cancelación solo afecta a las solicitudes correspondientes.
- **A2A Refuerzo de seguridad**: mundial“Aprobar todo”El bit ya no actúa en sesiones no enfocadas, bloqueando el camino hacia la ejecución silenciosa remota de herramientas de dispositivos de alto riesgo.
- **Tasker Línea de seguridad de transmisión**: La entrada está cerrada de forma predeterminada + límite superior de longitud + límite de frecuencia, lo que reduce el costo del abuso de la transmisión externa.
- **Agent El cambio de asistente no tiene efecto**: Se corrigió el error cuando la sala del Agente se restaura y regresa a la sesión anterior de otros asistentes. La propiedad de la sala ahora es correcta después de cambiar de asistente.
- **Protección de asistente predeterminada**: El asistente predeterminado ya no se puede eliminar directamente; el puntero cambia automáticamente al eliminar otros asistentes y no cambiará después de reiniciar.“resurrección”Se eliminó el ayudante predeterminado.
- **Etiqueta de capacidad del modelo**: autoconstruido/Los proveedores de estaciones de transferencia ya no aparecen mal etiquetados en el directorio“Imagen no compatible”, mostrado como una conclusión desconocida y no falsa.
- **Botón de prueba del proveedor falso positivo**: Detección de dos niveles (la lista de modelos no degrada la verificación de estado), la información de error ya no se trunca.
- **La información de error se puede ampliar y copiar.**: Los errores largos admiten lectura ampliada y pulsación larga para copiar.
- **Termux Salto de autorización**: Avisos de orientación y respaldo de varios niveles si falla la autorización.
- **Responses API mensaje vacío**: Se corrigió el bucle infinito 400 cuando algunas fuentes ascendentes devolvían contenido vacío.
- **burbuja de mensaje**: Cuando hay una imagen de fondo, el texto conserva el color de fondo de las burbujas; el texto principal se invierte automáticamente bajo un fondo de pantalla oscuro para mejorar la legibilidad.
- **Resultados de búsqueda de la base de conocimientos**: Ya no aparece como una burbuja de texto en la interfaz del chat.
- **Página de escucha de notificaciones**: Se corrigieron problemas de filtrado y truncamiento de botones.
- **El botón Editar anula la autoridad**: El botón de edición ya no aparece debajo de los mensajes del asistente (incluidas las tarjetas) y solo debería aparecer en los mensajes enviados por el propio usuario.

### Correcciones de revisión en profundidad (nuevas en esta ronda)

- **Coherencia/simultaneidad de datos**: Los mensajes de escritura de recuperación de punto de interrupción y actualización de primer plano se cambian a CAS atómico. Una vez completada la compresión, el nivel de agua de la sesión de corte ya no se escribe en el global (modificar sesión de cadena); el recuento de referencia de la sala del asistente de conmutación se aumenta primero y luego se disminuye para evitar liberar accidentalmente la sesión activa; el número de fila real se verifica después de importar la copia de seguridad y agregarla a la biblioteca.
- **RAG Fiabilidad**: Función incorporada de comparación de índice de documentos que incorpora la huella digital del modelo;embedding La huella digital se cambia a un calibre de dimensión estable para eliminar la reconstrucción de arranque en frío del índice HNSW causada por adivinar las dimensiones.
- **confiabilidad del canal**:Telegram El daño del cursor ya no termina el bucle de recepción; si no se descifra el token de devolución, se vuelve al estado final (ya no hay reintentos infinitos); El webhook entrante de Feishu analiza el contenido JSON (consistente con la ruta de conexión larga); las respuestas automáticas se envían correctamente antes de registrar el historial (corregir la desviación del contexto);Tasker La notificación del resultado es cambiar la política global del usuario; el almacenamiento del canal está dañado y aislado.
- **Fiabilidad de la copia de seguridad**: Acceso a la carpeta de la base de conocimientos Recuperación e instantánea de NDJSON, reparación“Recuperar carpetas perdidas”.
- **confiabilidad de la memoria**:Memoria guardada automáticamente“Análisis no analizado”No se lanzan más excepciones.
- **Herramientas y UI**: Se corrigió el error de análisis causado por el tratamiento de los parámetros de objetos anidados de la herramienta (como los datos de render_data) como cadenas de escape; reinicio correcto al salir del estado de pausa de aprobación; imePadding agregado a la página de edición de pantalla completa.
- **CI / Ingeniería Salud**: Unifique LF al final de las líneas del código fuente (elimine el ruido de deriva del número de línea de referencia);detekt Sincronización del libro mayor y medición real;NOTICE Complete las dependencias que faltan; unifique las configuraciones del SDK de 22 pruebas Robolectric para eliminar fallas aleatorias en todas las ejecuciones.
- **Sistema de palabras rápidas**: Idioma no chino (japonés/Corea/Oeste/portugués/Rusia) ya no recurre a las palabras clave en chino, sino que recurre al inglés; el chat grupal MOOD reutiliza plantillas de chat individuales (dos definiciones en la versión anterior han cambiado); las palabras del mensaje del subagente siguen el idioma de la aplicación (versión antigua codificada en chino); el texto de la plantilla de escena incorporada está conectado a recursos localizados; Se repara la extracción de memoria./Deriva de las reglas chino-inglesas para los resúmenes continuos.

## Mejoras en la experiencia

- **Las tarjetas generativas se pueden renderizar directamente**: Gráfico generado por el asistente usando show_card/Panel de control/Las interfaces interactivas ahora se pueden representar en conversaciones (anteriormente solo se mostraba el texto original de los parámetros de la herramienta); Los scripts integrados en la tarjeta también pueden leer datos mediante enlace de datos.
- Todas las operaciones de acceso directo a mensajes son configurables y ya no quedan líneas en blanco cuando están completamente cerradas; La copia de pensamiento está cerrada de forma predeterminada y la eliminación de mensajes tiene una segunda confirmación para reducir los toques accidentales.
- ChatViewModel Refactorización dividida (bucle de herramientas)/Disposición de compresión/Aprobación/Restaurar módulos independientes) para reducir los costes de mantenimiento posteriores.

---

# Muse v2.6.0 (English)

Muse v2.6.0 feature release.

> This changelog covers all changes since the v2.5.8 release (including the never-published v2.5.9 contents).

## New Features

- **GPT account login (Codex OAuth)**: one-tap sign-in with a ChatGPT account (browser authorization + PKCE), automatically injecting the ChatGPT-Account-ID header and connecting to `chatgpt.com/backend-api/codex` to use subscription quota — no API key required; with a direct jump to the authorization page. Unified fixes for one-tap OAuth on xAI / GitHub (filled in public client_id, Device Flow user-code dialog).
- **Codex model list from the cloud catalog**: available Codex models now come from the cloud model catalog (no longer preset in the app) and follow official retirements/additions automatically.
- **Generative cards (show_card)**: the assistant can render interactive cards directly in the conversation (charts, dashboards, flow/comparison diagrams, playable widgets); this release completes the card renderer.
- **Tasker external automation**: off by default; Settings can explicitly enable external broadcasts for a single text-completion request, with a model-quota warning. It does not grant local tool access.
- **Configurable message quick actions**: a Via-style customization menu with a full action pool and an ordered enabled list (copy/reasoning/translate/share/regenerate/delete, etc.).
- **Backup can include provider keys**: choose whether to include provider API keys in a backup.
- **Platform self-check tool**: a new `platform_selfcheck` lets the assistant verify its runtime and capability readiness.
- **Diagnostics enhancements**: the in-app "Export diagnostics" now covers a generation-trace ring buffer; raw tool-call fragments are logged when the first chunk is lost, easing upstream anomaly triage.

## Important Fixes

- **Isolated A2A remote conversations**: requests no longer switch foreground chats or invoke local device tools; task/context sessions are isolated, multi-turn text conversations remain supported, and cancellation affects only the caller's request.
- **A2A hardening**: the global "approve all" flag no longer applies to non-focused sessions, closing a path to remotely execute high-risk device tools silently.
- **Tasker broadcast defenses**: off by default plus length caps and rate limiting, raising the cost of abuse.
- **Agent assistant switching**: fixed Agent rooms restoring from another assistant's stale session; room ownership now follows the selected assistant.
- **Default assistant protection**: the default assistant can no longer be deleted directly; the pointer switches automatically and deleted defaults no longer "revive" after restart.
- **Model capability labels**: custom/proxied providers are no longer mislabeled "image unsupported"; they show unknown instead of a wrong conclusion.
- **Provider test button false alarms**: two-stage probing (falls back to health check when model listing fails) and untruncated error messages.
- **Expandable, copyable errors**: long errors can be expanded and long-pressed to copy.
- **Termux authorization jump**: multi-level fallback with guidance toasts when authorization fails.
- **Responses API empty messages**: fixed a 400 retry loop when some upstreams return empty content.
- **Message bubbles**: long text keeps a bubble background over wallpaper images; body text auto-inverts on dark backgrounds for readability.
- **Knowledge search results**: no longer appear in the chat as body-text bubbles.
- **Notification listener page**: fixed filtering and button truncation issues.
- **Edit-button leak**: the edit button no longer appears under assistant messages (including cards) — it belongs only on the user's own messages.

### Deep-audit fixes (this cycle)

- **Data consistency / concurrency**: resume and foreground-refresh message writes now use atomic CAS; a finished compression no longer writes a switched-away session's watermark into global state (cross-session leak); assistant-room switching increments before decrementing refcounts to avoid releasing a session still in use; backup import reconciles real inserted row counts.
- **RAG reliability**: built-in doc indexing now compares the embedding model fingerprint; the embedding fingerprint uses a stable dimension token, eliminating cold-start HNSW rebuilds caused by a guessed dimension.
- **Channel reliability**: a corrupt Telegram offset no longer kills the receive loop; reply-context token decrypt failure becomes a terminal state (no infinite retry); Feishu webhook inbound parses the content JSON (matching the long-connection path); auto-replies record history only after a successful send (fixes context drift); Tasker result notifications honor the user's global policy; corrupt channel storage is quarantined.
- **Backup reliability**: knowledge folders are restored from NDJSON backups and snapshots, fixing "restore loses folders".
- **Memory reliability**: memory auto-save no longer throws when no analysis is parsed.
- **Tools and UI**: fixed nested-object tool arguments (e.g. render_data's data) being passed as escaped strings and failing to parse; approval pause now resets when leaving; the full-screen editor gained imePadding.
- **CI / engineering health**: source line endings normalized to LF (removes baseline line-drift noise); detekt debt cap resynced with measurements; NOTICE lists previously missing dependencies; 22 Robolectric tests pinned to an explicit SDK, eliminating random full-run failures.
- **Prompt system**: non-Chinese locales (Japanese/Korean/Spanish/Portuguese/Russian) no longer fall back to Chinese prompts, falling back to English instead; group-chat MOOD now reuses the single-chat template (the two definitions had drifted); sub-agent prompts follow the app language (previously hardcoded Chinese output); built-in scene template bodies moved to localized resources; fixed zh/en rule drift in memory extraction and rolling summary.

## Experience Improvements

- **Generative cards render for real**: cards produced by the assistant via show_card (charts, dashboards, interactive UIs) now actually render in the conversation (previously only the raw tool arguments were shown); card scripts can also read their bound data.
- All message quick actions are configurable; hiding them all no longer leaves an empty row. Reasoning copy is off by default and message deletion requires confirmation to reduce accidents.
- ChatViewModel refactor split (tool loop / compression / approval / resume coordinators) to lower maintenance cost.


