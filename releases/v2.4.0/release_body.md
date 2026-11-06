# Muse v2.4.0

Esta es una actualización a gran escala que continúa evolucionando a partir de la serie v2.3. Se centra en completar el Agente celular,Node/npm Cuando se ejecuta localmente,MCP Actualizaciones a múltiples enlaces principales, como expansión, memoria y confiabilidad de la base de conocimientos, entrada de voz y recuperación de diálogos.

## nuevas capacidades

- **Mejora integral del Agente celular**: Amplíe desde operaciones de interfaz de usuario de un solo paso hasta flujos de trabajo celulares de varios pasos observables, verificables y recuperables.Agent Puede leer árboles de control y estados de pantalla, buscar controles por texto o ID de recurso, admitir búsquedas de desplazamiento, validación posterior al clic y detener la reproducción automática en caso de falla o efectos secundarios no especificados.
- **Operación de pantalla virtual en segundo plano**: Admite iniciar aplicaciones, leer la pantalla, hacer clic, deslizar, ingresar texto, enviar pulsaciones de teclas y esperar en una pantalla virtual independiente sin ocupar el primer plano actual del usuario; La lectura de la pantalla en la pantalla virtual estará estrictamente vinculada a la pantalla para evitar confundir la pantalla principal con la pantalla de fondo.
- **plano de control de aplicaciones**: capacidades controladas recientemente agregadas, como enumerar aplicaciones, iniciar aplicaciones, abrir configuraciones de aplicaciones, detención forzada, borrar datos y desinstalar, degradar automáticamente según los niveles de permiso y controlar aplicaciones del sistema.Muse Establezca límites de seguridad para usted y para las operaciones peligrosas.
- **Perfil de capacidad del dispositivo**:Agent Antes de tomar medidas, primero comprenderá la accesibilidad del dispositivo actual,Shell,Root, capturas de pantalla, pantallas virtuales y capacidades de Nodo, y luego seleccione el canal de ejecución apropiado para reducir la prueba y el error ciegos.
- **Node/npm caja de arena local**: La aplicación tiene entornos de ejecución Node.js y npm integrados. Puede ejecutar scripts, utilizar módulos npm locales y procesar archivos en el entorno limitado de Muse./Tareas de red; Los scripts de alto riesgo todavía están sujetos a aprobaciones y tiempos de espera.
- **MCP stdio con extensión de terminal**:MCP Además de la transmisión por red, admite servidor stdio local, sistema de archivos integrado, gráfico de memoria y plantilla de pensamiento secuencial; el terminal también puede utilizar directamente el tiempo de ejecución del nodo en la aplicación. La autoprueba en tiempo de ejecución cubre la ejecución del nodo,JavaScript, biblioteca dinámica,TLS,npm y enlaces de scripts y otros enlaces clave.
- **Carpeta de la base de conocimientos**: La base de conocimientos admite carpetas virtuales similares a los administradores de archivos, lo que le permite explorar, mover y ver documentos por directorio;ZIP La importación preservará la estructura de directorios internos del paquete comprimido.

## memoria y dialogo

- **Aislamiento de memoria del asistente**: Diferentes asistentes utilizan sus propios datos, memoria compilada y contexto reciente de forma predeterminada; La memoria global está controlada por configuraciones compartidas a nivel de asistente, lo que reduce“Sólo se lo conté a otro asistente pero el nuevo asistente se enteró.”problema de memoria de cadena.
- **Consistencia amnésica**: Los hechos, los índices de texto completo y los bordes del gráfico de relaciones de memoria se eliminan en la misma transacción para evitar eliminar el texto pero dejar resultados de búsqueda o bordes de relaciones.
- **El ahorro automático de memoria es más confiable**: Las respuestas vacías, las llamadas fallidas y los fallos de análisis JSON no se registrarán erróneamente como éxitos; las fallas conservarán las oportunidades de recuperación posteriores y la ventana de huellas dactilares también será consistente con el contenido realmente enviado al modelo.
- **Las largas conversaciones ya no conducen a una amnesia silenciosa**: Resumen de desplazamiento mejorado, fragmentación del historial, compresión de rondas de herramientas, juicio resumido de beneficios netos y clasificación de sesiones, conservando descripciones clave de asistentes y resultados de herramientas.
- **La recuperación de mensajes es más segura**:outboxEl orden de envío de los mensajes de los usuarios y el punto de control de primera generación es más estricto; la recuperación y el reintento mantienen el ID del mensaje, el recuento, la vista previa y el tiempo de actualización idempotentes para evitar la generación repetida o que la lista de sesiones se coloque en la parte superior por error.
- **La herramienta hace que la presentación sea más consistente**: La respuesta continua después de la llamada a la herramienta del cliente no acumulará repetidamente la misma ronda de metadatos de pensamiento; la pregunta sobre el intervalo de tiempo utiliza el tiempo de mensajes de usuarios reales adyacentes en lugar de tratar el tiempo dedicado a pensar en modelos o llamar a herramientas como tiempo de espera del usuario.

## base de conocimientos y RAG

- **Respaldo de búsqueda mixta**: Cuando la recuperación del vector falla o se interrumpe, los fragmentos reales se recuperan primero del FTS/BM25 del fragmento de la base de conocimientos. En lugar de buscar únicamente vistas previas de documentos, también se puede recuperar la segunda mitad de documentos largos.
- **Aislamiento de documentos y filtrado de seguridad.**: Documentos internos, ámbitos de documentos dirigidos y filtrado de metadatos en vectores.FTS Sea coherente con el camino alternativo; utilice el cierre fallido cuando la lectura de metadatos falle para evitar la inyección accidental de datos internos en el contexto.
- **Los índices de transmisión se pueden revertir**: Cuando el reemplazo del índice falla, se cancela o las dimensiones no coinciden, el índice anterior se conservará tanto como sea posible; Si el reemplazo falla después de haber comenzado, se limpiará la base de datos.FTS y resultados parciales de HNSW, y simultáneamente restablece el estado del índice del documento.
- **Importación de metadatos completada**: Registre el hash de contenido y la configuración de incrustación después de una importación exitosa, admita la detección de omisión y cambio de modelo del mismo contenido; El hash de archivos grandes se calcula en función del incremento del fragmento leído para evitar recargar el texto completo.
- **El presupuesto de inyección es consistente**: El cálculo del presupuesto del fragmento de conocimiento y el texto realmente escrito en el mensaje del sistema utilizan el mismo contenido truncado para evitar que la parte larga y completa pase por alto el contexto del diálogo de reducción del presupuesto.

## Servicios de voz y externos

- **Unificación del ciclo de vida del reconocimiento de voz.**:Step,Whisper,DashScope Tanto el controlador Realtime como el controlador Realtime admiten eliminación idempotente, que no volverá a conectarse ni iniciará una sesión falsa después del lanzamiento.
- **Los resultados de voz no están desordenados**: La descarga segmentada comparte el bloqueo en serie con la descarga final que deja de grabar. Las devoluciones de llamada tardías del controlador anterior se descartarán mediante la generación y la verificación de identidad de la instancia; La monitorización se cancelará por completo al salir de la conversación de voz.
- **Recuperable si falla el reconocimiento**:Whisper Si la segmentación falla, el segmento de audio se retendrá y se volverá a intentar de acuerdo con el límite superior para evitar descartar silenciosamente la última oración cuando falle la descarga final; También se conservarán espacios legibles entre varias transcripciones en inglés.
- **NewAPI / OpenAI Diagnóstico de servicio compatible**: La finalización automática es común cuando solo se ingresa el nombre de dominio de la estación de transferencia. `/v1` API Camino; cuando la interfaz de la lista de modelos devuelve una página web en lugar de JSON, solicitará explícitamente verificar la dirección base de la API, el enrutamiento del proxy y la protección de la página web en lugar de simplemente mostrar un error de análisis.
- **OAuth y seguridad del canal**:OAuth El registro de errores ya no registra el cuerpo de la respuesta original; la limpieza de medios del canal protege los archivos a los que todavía hace referencia la bandeja de entrada y los mensajes históricos; Los límites de recuperación de los cursores de canales, las conversaciones y los puntos de control de los agentes son más claros.

## Experiencia y calidad

- Se solucionó el problema de diseño por el cual la copia de fila se comprimía en una o dos palabras en fuentes grandes o pantallas estrechas, y se agregó control de acceso DISEÑO-01.
- Base de conocimientos completa, asistente de memoria,Agent Pruebas de regresión de flujo de trabajo, voz, permisos de herramientas, migraciones de bases de datos y rutas de recuperación.
- Puertas de calidad de código Kotlin/Compose actualizadas, líneas de base y comprobaciones en varios idiomas; el paquete candidato oficial ha pasado cinco carriles locales, puertas de script de lanzamiento, verificación de firma y verificación de versión.

---

# Muse v2.4.0 (English)

This is a major update built through the v2.3 series. It advances the phone Agent, the local Node/npm runtime, MCP extensions, memory and knowledge-base reliability, voice input, and conversation recovery across the core application.

## New Capabilities

- **A much stronger phone Agent**: Moved from single-step UI actions to observable, verifiable, resumable multi-step phone workflows. The Agent can inspect the control tree and screen state, find controls by text or resource ID, scroll within bounds, verify clicks, and stop instead of silently replaying uncertain side effects.
- **Background virtual displays**: Launch apps, read screens, tap, swipe, type, press keys, and wait inside an isolated display without taking over the user's current foreground app. Screen reads remain bound to the requested display instead of accidentally reading the main screen.
- **Application control**: Added controlled app listing, launching, settings, force-stop, data-clear, and uninstall capabilities with permission-aware fallback and guardrails for system apps, Muse itself, and destructive actions.
- **Device capability profile**: Before acting, the Agent learns whether Accessibility, Shell, Root, screenshots, virtual displays, and Node are available, then chooses an appropriate execution channel instead of guessing.
- **Local Node/npm sandbox**: Muse now includes its own Node.js and npm runtime for scripts, local npm modules, and file/network tasks inside the app sandbox. High-risk scripts remain protected by approval and timeouts.
- **MCP stdio and terminal extensions**: MCP now supports local stdio servers in addition to network transports, with built-in filesystem, memory-graph, and sequential-thinking templates. The terminal can use the same bundled Node runtime, with self-checks for execution, JavaScript, native libraries, TLS, npm, and script links.
- **Knowledge-base folders**: Knowledge documents can be browsed and moved in virtual folders like a file manager; ZIP imports retain their internal directory structure.

## Memory and Conversation

- **Assistant memory isolation**: Each assistant primarily uses its own facts, compiled memory, and recent context. Global memory is controlled by an assistant-level sharing setting, preventing knowledge from one assistant from unexpectedly appearing in another.
- **Consistent memory deletion**: Facts, full-text indexes, and memory-graph edges are deleted in one transaction, preventing stale search results or graph links after deletion.
- **More reliable memory saving**: Empty responses, failed calls, and invalid JSON no longer count as successful memory saves. Failed work remains eligible for retry, and the fingerprint window matches the actual model input.
- **Better long-context continuity**: Improved rolling summaries, history chunking, tool-round compression, net-benefit checks, and conversation ordering preserve important assistant notes and tool results instead of silently forgetting them.
- **Safer message recovery**: Outbox messages, user messages, and the first generation checkpoint follow a stricter persistence order. Recovery and retry remain idempotent for message IDs, counters, previews, and timestamps, preventing duplicate generations and incorrect conversation reordering.
- **Cleaner tool-round presentation**: Follow-up replies after client-side tools no longer repeat the same reasoning metadata unnecessarily. Message-interval questions use the timestamps of adjacent real user messages instead of model thinking or tool latency.

## Knowledge Base and RAG

- **Hybrid retrieval fallback**: When vector retrieval fails or is circuit-broken, fallback search now uses FTS/BM25 over actual knowledge chunks instead of only document previews, so later passages in long documents remain searchable.
- **Isolation and safety filters**: Internal-document exclusion, scoped document IDs, and metadata filters stay consistent across vector, FTS, and fallback retrieval. Metadata failures fail closed instead of injecting potentially internal content.
- **Rollback-safe streaming indexing**: If replacement indexing fails, is cancelled, or encounters an embedding-dimension mismatch, the old index is preserved where possible. Once replacement has started, partial DB, FTS, and HNSW results are cleaned up and the document index state is reset.
- **Complete import metadata**: Successful imports now record content hashes and embedding configuration, enabling same-content skips and model-change detection. Large-file hashes are computed incrementally while reading.
- **Consistent injection budgets**: Budget estimation and the text actually inserted into the system prompt use the same truncated snippet, preventing long chunks from bypassing the RAG budget.

## Voice and External Services

- **Unified speech-recognition lifecycle**: Step, Whisper, DashScope, and Realtime controllers now share idempotent disposal semantics; disposed controllers cannot silently reconnect or enter a fake Connecting state.
- **Ordered voice results**: Segment flushes and the final stop flush share a serial lock, while late callbacks from replaced controllers are rejected by generation and identity checks. Leaving a voice conversation fully cancels listening.
- **Recoverable recognition failures**: Failed Whisper segments retain their audio for bounded retry, preventing the final sentence from disappearing; completed English transcripts also keep readable spacing.
- **NewAPI / OpenAI-compatible diagnostics**: Host-only relay URLs receive the common `/v1` API path automatically. If a models endpoint returns HTML instead of JSON, the error now points to the API base URL, proxy route, and web-protection page.
- **OAuth and channel safety**: OAuth logs no longer record raw response bodies. Channel-media cleanup protects files still referenced by inboxes and conversation history, while cursor, conversation, and Agent-checkpoint recovery boundaries are clearer.

## Experience and Quality

- Fixed Row layouts that collapsed text to one or two characters on narrow screens or larger font settings, and added the LAYOUT-01 gate.
- Added regression coverage for knowledge imports, assistant memory, Agent workflows, voice, tool permissions, migrations, and recovery paths.
- Updated Kotlin/Compose quality gates, baselines, and localization checks. The local release candidate passed all five lanes, release-script gates, signing checks, and version checks.
