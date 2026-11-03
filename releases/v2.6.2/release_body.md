# Muse v2.6.2

> Esta versión cubre todos los cambios desde el lanzamiento de v2.6.1 a"Complementar las habilidades del asistente","confiabilidad de la memoria"con"personalización"Caballero.

## nuevas características

- **Fondo separado para cada conversación**:El fondo del chat admite tres capas——Nivel de sesión, nivel de asistente, valor predeterminado global. Las prioridades se reducen en orden y se proporciona un asistente para diferentes propósitos sin interferir entre sí; una sola conversación se puede anular nuevamente. Las imágenes se comprimen y descargan al disco de forma unificada y el archivo de configuración no se ampliará.
- **El fondo cambia automáticamente entre claro y oscuro.**: Se agregó "Fondo en modo oscuro". Después de activar el cambio automático de claro y oscuro, se utilizará un color claro y el color oscuro se cambiará automáticamente a otro; si no está configurado, se utilizará el fondo principal.
- **La personalidad del asistente es editable**:Detalles del asistente → Se ha agregado una nueva "Definición de personalidad" a la página de palabras de aviso, que es independiente de la palabra de aviso personalizada completa. Utilice el primero para una personalización ligera (quién es usted, cómo habla) y el segundo para una adquisición completa.
- **Proceso de pensamiento personalizado**: Se agregó "Proceso de pensamiento personalizado" en la misma página para restringir fuertemente la composición de la cadena de pensamiento, por ejemplo."Reformulemos la pregunta primero. → Enumere 2-3 hipótesis → Excluir uno por uno → dar una conclusión". Déjelo en blanco para pensar de la forma predeterminada.
- **El asistente puede explorar otras conversaciones**: Nuevo `session_read`, el asistente puede leer los últimos mensajes de la conversación según el ID de la conversación (el valor predeterminado es 20, hay límites superiores para un solo mensaje y el número total) y, con la lista de conversaciones, puede revisar lo que ha hablado antes.
- **Notificación → memoria**: Las notificaciones se pueden ordenar automáticamente en memorias (desactivadas de forma predeterminada, deben activarse manualmente en la página de notificaciones) y se desintegrarán durante la clasificación; Después de encenderlo, el asistente puede mencionar cosas en las notificaciones de los mensajes activos.
- **constelación de memoria**: Se agrega una nueva vista de gráfico de conocimiento, que admite el llenado automático de bordes (misma entidad, misma sesión, misma categoría, vecino semántico) y conexión manual; La fijación y confirmación de relaciones se pueden operar directamente en el gráfico.
- **El asistente ahora puede gestionar la base de conocimientos.**: enumerar documentos, eliminar documentos, crear una base de conocimientos, eliminar una base de conocimientos (junto con la limpieza de fragmentos e índices vectoriales). La base de conocimientos y los documentos internos están protegidos de forma predeterminada; Las operaciones destructivas se aprueban artículo por artículo.
- **El asistente ahora puede gestionar conversaciones**: enumerar conversaciones, cambiar título, mover a carpeta, archivar/Desarchivar y eliminar conversaciones. La eliminación es irrevocable y será revisada y aprobada punto por punto.
- **Lea el texto completo de la base de conocimientos.**: Nuevo `knowledge_read_doc`, lea el texto completo según el ID del documento y segmente automáticamente el texto superlargo (30.000 caracteres cada vez, el desplazamiento para continuar leyendo se proporciona al final).
- **Autogestión de habilidades y asistentes.**: El asistente puede enumerar/habilitar/desactivar/Para eliminar habilidades, también puedes crear asistentes, cambiar el perfil y eliminar asistentes. Solo abre el subconjunto seguro——Herramientas/Habilidades/MCP Las fijaciones, modelos y proveedores todavía solo se configuran manualmente en la página de configuración.
- **Inyección automática del cabezal de solicitud de la estación de transferencia**: haga coincidir declarativamente los encabezados de solicitud requeridos por nombre de dominio (el primer lote OpenCode Go → `x-opencode-session`), el valor se deriva por sesión y es estable; su propio encabezado personalizado con el mismo nombre siempre tiene prioridad.
- **OpenAI El proveedor de tipos abre encabezados de solicitud personalizados/Cuerpo de la solicitud**: Los proveedores predeterminados de OpenAI (incluidas las estaciones de transferencia) también se pueden completar, ya no solo"Personalizar"Los tipos tienen entradas.
- **"Muchos" mensajes activos + programación autónoma de IA**: Se agregaron muchos archivos nuevos (30 intervalo de minutos, límite superior diario de 50 mensajes), y admite que la IA determine el siguiente intervalo de contacto (10 minutos~24 horas).
- **El asistente integrado también se puede eliminar**: Abierto a todos los asistentes de manera uniforme (cambia automáticamente el puntero predeterminado y conserva al menos uno).

## Correcciones importantes

- **Los temas claros y oscuros no cambian instantáneamente con el sistema**: Anteriormente, solo entraba en vigor cuando la aplicación se iniciaba bajo el tema correspondiente.——Debido a que los cambios de configuración no reconstruyen la actividad, la configuración anterior del sistema almacenada en caché aún se lee. Ahora se ha cambiado para monitorear explícitamente los cambios de color claro y oscuro del sistema, y el cambio entra en vigor inmediatamente durante la operación.
- **El texto original no se puede abrir al hacer clic en favoritos**: Se hizo clic en la tarjeta antes"Selección de grupo"ocupado y la vista previa está limitada a 280 caracteres. Ahora haga clic en el botón para abrir directamente la pantalla completa, desplazarse por el texto original y mover la agrupación a la tarjeta.
- **Juicio erróneo sobre el éxito o el fracaso de la herramienta.**: Anteriormente, adivina el éxito o el fracaso según el prefijo chino, lee después"error/falló"Un documento normal al principio se considerará fallido y provocará reintentos innecesarios. Ahora la señal de falla estructural tiene prioridad y los textos largos ya no se juzgan mal.
- **Las herramientas de alto riesgo vuelven a intentar eludir la aprobación**:Punto de paso fallido"Inténtalo de nuevo"Se reenviará directamente, sin pasar por permisos y aprobación.——Es posible que operaciones como enviar mensajes de texto y eliminar datos se realicen repetidamente. Ahora las herramientas de alto riesgo ya no reintentan automáticamente, lo que solicita que se reinicien.
- **Coexisten dos conjuntos de análisis de parámetros de herramientas**:hook Los parámetros vistos en la aprobación parametrizada son inconsistentes con los de la ejecución. JSON mal formado puede incluso causar `file://` Este tipo de interceptación falla. La misma resolución de origen ahora está unificada y se conservan los tipos.
- **Riesgo de pérdida de datos de la memoria**: Arreglar la fusión de LLM"Borrar primero y luego escribir."Los problemas resultantes incluyen pérdida irreversible (cambiada a fusión atómica de transacción única), cobertura concurrente resumida, extracción repetida para procesamiento en profundidad y progreso diario que solo guarda una fila, lo que resulta en que no se realice ninguna compilación el mismo día después de cambiar de espacio.
- **Biblioteca de cadenas de memoria entre espacios**: Compilar objetivo unificado de lectura y escritura, subasistente de reparación/Hay una serie de problemas, como ranuras incorrectas para productos compilados en otros espacios, enrutamiento de reflexión a la biblioteca predeterminada y corrección de constelaciones en la biblioteca incorrecta.
- **Codex Todas las solicitudes después de iniciar sesión 403**: Se corrigió el error de ruta de análisis JWT para el ID de la cuenta (en el espacio de nombres de la URL en lugar de en el nivel superior).base64url Hay tres problemas superpuestos: falta de relleno y ni siquiera se guarda el token cuando falla el análisis de la devolución de llamada de inicio de sesión. Los usuarios antiguos no necesitan iniciar sesión nuevamente y se reparará automáticamente al iniciarse.
- **Codex Por favor agregue dos adaptaciones más.**: El análisis de ID de cuenta agrega un respaldo de access_token y completa los requisitos del protocolo de Respuestas `OpenAI-Beta` cabeza.
- **Web Fallo en el inicio del servicio**: Se solucionó el problema de estabilidad en Ktor 3.x `Array has more than one element` problema.
- **La herramienta deshabilitada todavía se llama repetidamente**: Las herramientas deshabilitadas se eliminan directamente del esquema visible para el modelo y ya no"llamar→rechazado→continuar llamando"Bucle infinito.
- **El asistente personalizado no puede recibir mensajes activos**: Fuerce la ubicación de la sesión por parte del asistente y corrija la discrepancia entre las rutas generadas y escritas.
- **"Permitir todo el día" aún no se emite por la noche**: Cuando está habilitado, en realidad omite la verificación del período de silencio incorporado.
- **Falta el control de aplicaciones y el registro de la herramienta MCP**: Complete el registro de categoría y la lista blanca incorporada, y solucione el problema de que el asistente predeterminado no puede ver las herramientas de control de aplicaciones y algunas herramientas MCP en cada ronda.
- **Configuración de pérdida de conexión de prueba**: Reutilice la configuración y compilación unificadas y ya no pierda el cambio de protocolo de Respuestas ni los encabezados de solicitud personalizados.
- **Los mensajes activos informan 502 como tiempo de espera**: La falla de llamada siempre se registrará como "tiempo de espera de llamada"(60s)", el HTTP 502 real se devuelve dentro de 100 ms y la dirección de la investigación está sesgada. Ahora diferencie entre el tiempo de espera verdadero y el error de llamada con el código de estado.
- **El retiro de mensajes activos se puede aumentar a 32 horas**: Se detiene durante 32 horas después de 6 fallas consecutivas, e incluso se bloquea el envío de prueba. Ahora el flujo ascendente falla (5xx / (red inalcanzable) tiene un límite de 8 horas y solo se produce un retroceso exponencial completo por errores de configuración.

## Descripción

- Esta versión se centra en la mejora de capacidades, reparaciones de confiabilidad y personalización. Se recomienda actualizar.
- Esta versión incluye actualizaciones de la base de datos (v110 → v111, nueva personalidad del asistente y campos de cadena de pensamiento), que se migrarán automáticamente cuando se inicie por primera vez.

---

# Muse v2.6.2 (English)

> This release covers all changes since v2.6.1, focused on closing assistant capability gaps, memory reliability and personalization.

## New

- **Per-conversation chat backgrounds**: backgrounds now have three tiers — per conversation, per assistant, and a global default, resolved in that order. Give each assistant its own background and conversations can still override it. Images are compressed to disk so settings stay small.
- **Backgrounds follow light/dark automatically**: a new "dark mode background" is used when auto light/dark switching is on. Leave it unset to reuse the main background.
- **Editable assistant persona**: the assistant detail → prompt page gains a "persona definition" field, separate from the full custom prompt. Use the former for light customization, the latter to take over entirely.
- **Custom thinking process**: the same page gains a field that constrains how the assistant structures its reasoning, e.g. restate the question -> list 2-3 hypotheses -> rule them out -> conclude. Leave empty for default reasoning.
- **The assistant can read other conversations**: new `session_read` reads the most recent messages of a session by id (20 by default, with per-message and total caps), so it can recall what you discussed elsewhere.
- **Notifications to memory**: notifications can be distilled into memories automatically (off by default, enabled on the notification page), deduplicated and consolidated; once enabled the assistant can bring them up in proactive messages.
- **Memory constellation**: a new knowledge-graph view with automatic edge building (same entity, same session, same category, semantic neighbors) and manual linking; pinning and confirming relations work directly on the graph.
- **The assistant can manage your knowledge base**: list documents, delete a document, create or delete a knowledge base (chunks and vector index cleaned up). The default knowledge base and internal documents are protected; destructive operations are approved one by one.
- **The assistant can manage sessions**: list, rename, move to a folder, archive/unarchive and delete. Deletion is irreversible and approved item by item.
- **Full-document reading**: new `knowledge_read_doc` reads a whole document by id, returning long documents in segments (30000 characters each with the next offset).
- **Skill and assistant self-management**: list/enable/disable/delete skills, create assistants, edit personas, delete assistants. Only a safe subset is exposed — tool/skill/MCP bindings, models and providers remain manual configuration.
- **Automatic relay header injection**: declarative per-host required headers (first rule: OpenCode Go -> `x-opencode-session`), with session-derived stable values. Your own headers with the same name always win.
- **Custom headers/body for OpenAI-type providers**: preset OpenAI-type providers (including relays) can now set them too.
- **"Extreme" proactive pace + AI-paced scheduling**: a new extreme tier (30-minute interval, 50/day cap), and the assistant can pick its own next-contact interval (10 minutes to 24 hours).
- **The built-in assistant can be deleted too**: available for all assistants (the default pointer switches automatically, at least one is kept).

## Fixes

- **Theme did not follow the system immediately**: dark mode only applied if the app started under that theme, because configuration changes without an Activity rebuild left a stale cached configuration. It now listens to system light/dark changes explicitly and applies instantly.
- **Favorites could not be opened**: tapping a card was taken over by the group picker and the preview was capped at 280 characters. Tapping now opens the full scrollable text, with grouping moved to a button on the card.
- **Tool success misjudged**: success was guessed from Chinese prefixes, so reading a document that starts with "error"/"failure" was marked failed and triggered pointless retries. Structured failure signals now take priority.
- **High-risk retries bypassed approval**: retrying a failed step re-ran it directly, bypassing permissions and approval — messages could be sent or data deleted twice. High-risk tools are no longer auto-retried.
- **Two parameter parsers**: hooks and parameterized approval saw different arguments than execution, and malformed JSON could even disable `file://` blocking. Parsing is now unified with types preserved.
- **Memory data-loss risks**: fixed irreversible loss in LLM consolidation (now a single atomic transaction), concurrent summary overwrites, duplicate deep extraction, and daily progress stored as a single row that skipped compilation after switching spaces.
- **Memory leaking across spaces**: compilation read/write now uses a single target, fixing wrong-slot writes, reflection running against the default store, and constellation pinning hitting the wrong database.
- **All requests 403 after Codex sign-in**: fixed the account-id JWT claim path (under a URL namespace, not top level), base64url without padding, and the login callback not saving the token when parsing failed. Existing users self-heal on startup without re-login.
- **Two more Codex adaptations**: access-token fallback for account id, plus the `OpenAI-Beta` header required by the Responses protocol.
- **Web server startup crash**: fixed the `Array has more than one element` crash under Ktor 3.x.
- **Disabled tools kept being called**: disabled tools are removed from the model's schema, ending the call-reject-call loop.
- **Custom assistants received no proactive messages**: sessions are now resolved strictly per assistant.
- **"All day" still blocked at night**: enabling it now truly skips the built-in quiet hours.
- **Missing tool registrations**: category registration and the built-in whitelist were completed.
- **Test-connection losing configuration**: now reuses the shared config builder.
- **Proactive messages reported 502 as a timeout**: every failed call was logged as "timeout (60s)" even though an HTTP 502 returned in 100ms, sending diagnosis the wrong way. Timeouts and call failures are now reported separately with the status code.
- **Proactive backoff could reach 32 hours**: after six consecutive failures it stopped for 32 hours, blocking even test sends. Upstream faults (5xx / unreachable network) now cap at 8 hours; only configuration errors get the full exponential backoff.

## Notes

- This release is primarily capability completion, reliability fixes and personalization; upgrading is recommended.
- It includes a database upgrade (v110 -> v111, new persona and thinking-chain fields), migrated automatically on first launch.
