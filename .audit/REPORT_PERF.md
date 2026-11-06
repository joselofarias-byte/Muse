# Enlace 9: Informe de revisión de desempeño y estabilidad (2026-10)

## Resumen

Esta ronda versus Muse (almacén `E:\1Project\Muse\1muse`) realizar una revisión exhaustiva del código estático para cubrir los enlaces de rendimiento y estabilidad
7 Dirección de exploración: camino de inicio (MuseApp.onCreate),ChatViewModel Coordinador S1-S5 después de la división
Generar sobrecarga de superposición de ruta activa de enlace (GenerationTrace/shadow evento/Marca de agua de compresión), residencia de memoria de sesión larga, código nuevo
Límite del hilo (BroadcastReceiver / A2A / exportación de diagnóstico/webhook), propagación global de fallas de inicialización singleton,
WorkManager/Doze API Uso. El método consiste en comprobar archivo por archivo utilizando la herramienta Glob/Grep/Read, y todos los resultados se proporcionan en el archivo.:Número de línea
con evidencia de código y coteje los elementos conocidos globales de AUDIT_FRAMEWORK.md con la línea base de deduplicación DEDUPE_PERF.md.
Confirmado en total **12 nuevos descubrimientos**(P1×5,P2×7), sin P0. Conclusión general: limitación del enlace principal/guardia/El diseño degradado es más completo,
Los problemas recientemente introducidos se centran en los nuevos diagnósticos en v2.5.x/A2A Límites de subprocesos y recursos del código, y rutas de sesión largas"una vez por ronda
Lectura completa de DB + estimación de BPE"de costes superpuestos.

---

## lista de descubrimiento

### F1. El empaquetado zip de exportación de diagnóstico se ejecuta en el hilo principal (P1, credibilidad: alta)
- Ubicación:
  - `app/src/main/java/io/zer0/muse/ui/SettingsScreen.kt:704`(`DiagnosticExporter.share(context)` Llamado sincrónicamente por SettingsEntry onClick;`SettingsScreen.kt:1131-1135` de `onSelect = { entry -> ... entry.onClick() }` con `SettingsScreen.kt:1359-1383` de `cardGroupScope.item(onClick = onClick)` Ambos se ejecutan sincrónicamente dentro de la devolución de llamada de clic de Redactar)
  - `app/src/main/java/io/zer0/muse/diagnostic/DiagnosticExporter.kt:38-74`
- Evidencia:
  ```kotlin
  fun exportZip(context: Context): android.net.Uri? {
      return runCatching {
          val zipFile = File(context.cacheDir, "muse_diagnostic_...zip")
          ZipOutputStream(BufferedOutputStream(zipFile.outputStream())).use { zos ->
              GenerationTrace.traceFileOrNull()?.let { traceFile ->
                  zos.putNextEntry(ZipEntry("generation-trace.jsonl"))
                  traceFile.inputStream().use { it.copyTo(zos) }   // Archivo IO
              }
              ...
              DebugLogStore.exportToFile()?.let { debugFile ->      // escribir debug_export_*.txt
                  zos.putNextEntry(ZipEntry("debug-log.txt"))
                  debugFile.inputStream().use { it.copyTo(zos) }    // presentar de nuevo IO
  ```
  sin ningún `withContext(Dispatchers.IO)` paquete; paquete`share()` adentro `exportZip` Ejecute la compresión zip directamente en el hilo de llamada.
  Leer archivo de seguimiento (GenerationTrace Límite de archivos 2 MB,`GenerationTrace.kt:36`), leer/Escribir exportación de registro de depuración
  (DebugLogStore Almacenar 1000 artículos,`DebugLogStore.kt:31`).
- Impacto: se hace clic en la página de configuración cuando el archivo de diagnóstico es demasiado grande (registro de sesión largo + seguimiento de 2 MB)"Exportar diagnóstico"El hilo principal quedará atascado durante decenas o cientos de milisegundos.
  Arriba, lo que provoca caídas de fotogramas o incluso violaciones del modo estricto (debug Construir penalización directaLog).
- Sugerencias:`exportZip` paquete `withContext(Dispatchers.IO)`,`share()` Cambiar la función de suspensión o la rutina interna.

### F2. TaskerTriggerReceiver:goAsync Ejecute una llamada LLM de 120 segundos dentro de la ventana de transmisión de 10 segundos (P1, credibilidad: alta)
- Ubicación:`app/src/main/java/io/zer0/muse/intent/TaskerTriggerReceiver.kt:50-61, 71-80, 125`
- Evidencia:
  ```kotlin
  val pendingResult = goAsync()
  CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
      try { runTrigger(context.applicationContext, prompt) }
      finally { pendingResult.finish() }
  }
  ...
  val completion = withTimeoutOrNull(LLM_TIMEOUT_MS) { chatService.completeText(...) }  // LLM_TIMEOUT_MS = 120_000L
  ```
- Impacto:`goAsync()` Déle al receptor una ventana de prioridad de proceso de aproximadamente 10 segundos;LLM Ronda única (tiempo de espera de lectura de 300 s para OkHttp)
  Puede ir mucho más allá de esa ventana. El proceso pierde el primer plano después de exceder la ventana./broadcast prioridad (fondo congelado o eliminado),`pendingResult.finish()`
  tarde,Tasker El lado puede conseguir `RESULT_FAILED` o no responde, y el proceso es presionado por el sistema"Transmisión en segundo plano bloqueada"gobernado
  El riesgo es elevado. El cambio de hilo a la rutina IO solo resuelve el problema"onReceive Bloquear hilo principal", no resuelve el problema de la ventana de 10 segundos.
- Sugerencia: inicie el servicio de recepción en su lugar o `WorkManager.OneTimeWorkRequest` Ejecutar LLM,receiver Sólo hacia adelante.

### F3. A2aHandler.sendMessage en"Nueva sesión"La ruta utiliza suspendCancellableCoroutine y no admite la cancelación. Después de la cancelación, se llama a onReady tarde y la continuación se reanuda dos veces (P1, credibilidad: media)
- Ubicación:`app/src/main/java/io/zer0/muse/web/a2a/A2aHandler.kt:76-82`;Fuente de devolución de llamada `ChatSessionController.kt:199`(`onReady?.invoke()` en `accessor.coroutineScope.launch` (dentro)
- Evidencia:
  ```kotlin
  kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
      val ready: () -> Unit = { cont.resumeWith(Result.success(Unit)) }
      chatViewModel.createNewSession(onReady = ready)
  }
  ```
  Ninguno `invokeOnCancellation` Registro de liberación;`createNewSession` OnReady se llama dentro del bloque de lanzamiento asíncrono,
  Una vez que la persona que llama (Ktor solicitar corrutina) se cancela,`cont` ya no está suspendido, el currículum tardío será CancellationException
  Tragado pero onReady lógica posterior (`refreshContext` / `saveViewedSessionId`) todavía se ejecuta.
- Impacto:a) A2A Solicitar tiempo de espera/Una vez desconectado el cliente, se seguirá creando la nueva sesión y se eliminará el currentSessionId de ChatViewModel.
  causa"La llamada externa A2A secuestra la sesión en primer plano"Problemas de experiencia (la conversación con la que el usuario está chateando se corta);b) Múltiples SendMessage simultáneos
  Hablad y esperaos `isStreaming` De nuevo, la semántica de encadenamiento no está aislada.
- Sugerencias:A2A En cambio, las rutas utilizan cadenas de ejecución ligeras e independientes (como las rutas de origen de ScheduledTaskRunner), o al menos se serializan por taskId.
  Usar juntos `invokeOnCancellation` liberar.

### F4. A2A / Host WebSocket Ambos impulsan directamente el singleton ChatViewModel y no existe protección intergeneracional para las condiciones de carrera de cambio de sesión (P1, credibilidad: media)
- Ubicación:
  - `app/src/main/java/io/zer0/muse/web/a2a/A2aHandler.kt:74, 87-101`
  - `app/src/main/java/io/zer0/muse/web/HostWebSocketGateway.kt:209-241`(`chat.send` directo `chatViewModel.updateInput(text); chatViewModel.send()`)
- Evidencia:A2aHandler espera `chatViewModel.state.first { !it.isStreaming }`(`A2aHandler.kt:99-101`) Juicio
  Esta generación de solicitud finaliza; pero `state` ¿Es el flujo de estado de la interfaz de usuario del singleton global?——Si el usuario envía un mensaje a otra conversación en primer plano al mismo tiempo,
  A2A voluntad"fin de la transmisión para la sesión del usuario"Juzgó mal que su tarea estaba completa, recuperada.**otra sesión**mensaje del asistente
  (`sessionRepo.observeMessages(sessionId).first().lastOrNull{...}` Consigue el último asistente durante el período de espera,
  `A2aHandler.kt:104-107`,sessionId Es la propia sesión de A2A, por lo que el valor es correcto, pero el juicio de finalización está contaminado por el flujo global).
- Impacto: cuando el usuario front-end y A2A se generan simultáneamente,A2A La respuesta puede devolver nulo prematuramente/Contenido incorrecto; invertir la misma lógica,Web fin chat.send
  Envíen y esperen unos a otros con la recepción.ChatGenerationManager Se serializa mediante sessionId, pero no se utiliza el juicio de finalización A2A.
  `activeGenerations[sessionId]`, pero el mundo `state.isStreaming`.
- Sugerencias:A2A Espera completa cambiada a `chatGenerationManager.activeGenerations`(por sessionId), o escuchar
  `streamGenerationSerial` + sessionId Señal exclusiva vinculada.

### F5. Cada ronda genera el final."Historial completo del canal de memoria"superpuesto en O(Número de rondas×cantidad total) costo (P1, credibilidad: alta)
- Ubicación:`app/src/main/java/io/zer0/muse/ui/chat/ChatGenerationController.kt:1572-1591`(finalizeResponse (dentro)
- Evidencia:
  ```kotlin
  val dbConversationMessages =
      if (deps.settings.memoryConfigCache.conversationRecallEnabled) {
          resultOf { deps.sessionRepository.getAllMessagesForWarmup(sessionId) }   // Leer completo
              ...
      }
  ...
  deps.memoryTicker.notifyTurn(sessionId, conversationMessages, ...)   // En realidad, el resumen se ejecuta cada 5 rondas.
  ```
  `getAllMessagesForWarmup` No pase por paginación (`SessionRepository.kt:680-684`,`observeBySession.first()` cantidad total
  + `toUIMessage()` cartografía). y `MemoryTicker.notifyTurn` solo hay `count % 5 == 0` recién comenzado
  rollingSummary(`MemoryTicker.kt:860-885`), es decir **4/5 Esta lista completa de mensajes se acaba de construir en la ronda de
  Solo tíralo**(La referencia permanece en el cierre hasta GC). Las sesiones largas (mensajes de nivel 10.000) pueden asignar completamente docenas de cadenas de MB a la vez, lo que constituye una memoria medible.
  Picos + fluctuación IO.
- Impacto: al final de cada ronda de respuesta para una sesión larga, hay una lectura de tabla completa más + construcción de objeto completa, incluso si el resumen no se activa en absoluto en esta ronda; superposición
  F6 de estimación de tokens, lo que resulta en"Después de responder, la interfaz de usuario se cerrará.".
- Sugerencias:`notifyTurn` Pasar en su lugar `messagesProvider`(perezoso, igual que `notifySessionEndFromProvider` práctica,
  `ChatStreamCoordinator.kt:143-152`), sólo en `shouldRollSummary` El importe total se lee sólo al acertar.

### F6. updateContextTokenCount Durante la transmisión, la lista completa de mensajes de la interfaz de usuario está codificada en BPE y contiene mensajes comprimidos (P1, credibilidad: alta)
- Ubicación:`app/src/main/java/io/zer0/muse/ui/chat/ChatToolLoopRunner.kt:706-710`(400 personaje/1.5s estrangulamiento)→
  `ChatViewModel.kt:3162-3165` → `ChatGenerationController.kt:670-686`
- Evidencia:
  ```kotlin
  suspend fun updateContextTokenCount(excludedMessageIds: Set<String> = emptySet()) {
      val msgsSnapshot = deps.stateStore.messages.value
      ...
      val tokenCount = withContext(Dispatchers.Default) {
          runCatching { TokenEstimator.estimate(counted, sysPromptSnapshot) }
  ```
  - `counted` Sólo se excluyen los mensajes de anulación de puntos de control; pero los mensajes antiguos fuera de la ventana paginada de la interfaz de usuario aparecerán cada vez que se carguen más
    `_messages`(`loadMoreHistory` Preinserción,`ChatMessageController.kt:101-103`), cuanto más larga sea la lista cada vez
    Cuanto mayor sea el costo estimado.
  - `TokenEstimator.estimate` para cada mensaje content/reasoning/mood/reflection/toolCalls Párrafo por párrafo BPE
    (`TokenEstimator.kt:63-86`), sin caché (mensaje id → token número).
  - Esta llamada también es `buildSystemPromptForStream`(`ChatGenerationController.kt:927`) y estrangulación del flujo,
    Final de compresión (`ChatCompressionCoordinator.kt:177`) desencadenado en varios lugares.
- Impacto: Sesiones largas (>200 mensaje) durante la transmisión, cientos de ms de tareas de CPU cada 1,5 segundos, compitiendo con la representación de la interfaz de usuario.
  Dispatchers.Default Hilo;v1.97 Arreglar notas también léame"Larga historia de realización de pedidos. 50-200ms"(`ChatViewModel.kt:3158-3160`).
- Sugerencia: agregar TokenEstimator (messageId, contentHash) → token caché LRU; La limitación de la transmisión solo reevalúa
  Los valores almacenados en caché del prefijo N + más recientes.

### F7. Logger El registro del archivo se escribe en el disco de forma sincrónica en todos los subprocesos de llamada + bloqueo global (P2, credibilidad: alta)
- Ubicación:`common/src/main/java/io/zer0/common/Logger.kt:155-189, 201-233`
- Evidencia:`d/i/w/e` se ajustan directamente `maybeWriteFile` → `synchronized(lock) { ... file.appendText(line) }`,
  Sin sumidero asíncrono, sin almacenamiento en búfer por lotes;`initFileLog` en `MuseApp.onCreate` Línea 172 se abre incondicionalmente
  (`MuseApp.kt:172`),`fileLogLevel` DEPURAR (predeterminado`Logger.kt:33`), es decir**Disco una vez para cada registro de depuración
  append + 100KB rolling juicio**y mantiene un bloqueo global.
- Impacto: ciclo de herramientas/Registros de alta frecuencia en devoluciones de llamadas de transmisión (como `updateAssistant` detached Registro de frecuencia limitada,ToolOrchestrator
  (2-3 registros por herramienta) serializar todos los llamadores de registros en un bloqueo en dispositivos IO lentos puede amplificar la latencia de ruta activa resultante.
- Sugerencia: cambie la escritura de archivos a una cola ejecutora de un solo subproceso + agregación de memoria; o fileLogLevel tiene por defecto INFO.

### F8. DebugLogStore Todos los registros se sincronizan a través de Logger.sink y cada registro ejecuta 7 reglas regulares (P2, credibilidad: alta)
- Ubicación:`MuseApp.kt:186-188`(sink Registrarse)+ `common/.../Logger.kt:142-153`(sanitizePii 7 reemplazar)
  + `app/.../debug/DebugLogStore.kt:81-95`(addLast + trim sincrónico)
- Evidencia:`Logger.d` Cada vez:sanitizePii(7 Regex.reemplazar)→ Logcat → `maybeWriteFile`(Bloquear+escribir disco)→
  `dispatchToSink`(DebugLogStore ConcurrentLinkedDeque add). Registro del hilo principal (como en la devolución de llamada de Compose
  Logger.i) Los tres gastos generales están en el hilo de llamada.
- Impacto: Superpuesto con F7, se genera en la ruta activa"Impuesto logarítmico implícito"La fuente principal; más de una docena de elementos en onCreate durante el período de inicio Logger.i
  Recorra también toda la cadena.
- Sugerencias:sanitize Resultado de prensa (tag,msg hash) caché de cortocircuito;DebugLogStore Limite la frecuencia o registre el receptor solo en compilaciones de depuración.

### F9. ScheduledTaskRunner / ProactiveMessageRunner encuestas de los 60 + GenerationGate(2) La estructura es correcta bajo Doze pero el proceso se mantiene residente sin demonios (P2, credibilidad: media)
- Ubicación:
  - `app/.../schedule/ScheduledTaskRunner.kt:150-160`(`while (isActive) { tickOnce(); delay(60_000) }`)
  - `app/.../schedule/ProactiveMessageRunner.kt:127-141`(encuesta isomorfa)
  - `app/.../schedule/GenerationGate.kt:16-39`(`MAX_CONCURRENT_LLM = 2`, semáforo en proceso)
  - Trabajador espalda con espalda:`MuseApp.kt:533-597`(Tres trabajos periódicos de 15 minutos,KEEP, sin restricciones)
- Evidencia: Polling Runner se basa en la supervivencia del proceso; Una vez finalizado el proceso, WorkManager se encarga de él durante 15 minutos (tenga en cuenta el archivo Léame).
  `ScheduledTaskWorker`/`ProactiveMessageWorker` Ninguno `setConstraints(RequiresStorageNotLow())`
  /limitaciones de la red,`Constraints.Builder().build()`(`MuseApp.kt:538, 576, 591`).Doze abajo PeriodicWork
  Solo se ejecuta en la ventana de mantenimiento, el retraso de sondeo de 60 segundos en proceso en Doze se congela y se alinea.——dos caminos
  `lastTriggeredAt`/`nextTriggerAt` Tiene persistencia (B8-01), la lógica es correcta.
- Impacto:**No es un defecto sino una confirmación.**:Doze abajo"puntualidad"Determinado por la granularidad de la ventana de mantenimiento de WorkManager
  (15min período + ventana del sistema), el bucle de retardo en el proceso no es confiable pero el código no depende de él como único desencadenante; el resumen diario es diferente
  `startInProcess` recogerÚltimo retraso(`DailySummaryWorker.kt:248-277`)+ compensación de recepción
  `enqueueCatchUpIfDue`(:346-375)Doble seguro.`ReminderToolsRegistrar` usar
  `setExactAndAllowWhileIdle`(`ReminderToolsRegistrar.kt:163`) cae sobre"Herramientas de despertador que deben llegar a tiempo"arriba,
  API Selección correcta;QuickNotes Mismo estilo (`QuickNotesViewModel.kt:632`).
- Conclusión: no constituye un defecto nuevo; registrado como"Verificado correcto"artículo. El único pequeño defecto es que los tres trabajadores de 15 minutos ya no están.
  Debido a limitaciones de la red, los dispositivos fuera de línea también iniciarán procesos para realizar escaneos no válidos (ahorro de energía y pequeña pérdida), como se observa en la columna P2.

### F10. MuseApp.onCreate El segmento de sincronización contiene 5 puntos de bloqueo y la ruta de recuperación es además serial (P2, credibilidad: alta)
- Ubicación:`app/src/main/java/io/zer0/muse/MuseApp.kt:223, 224-716, 718-748`
- Evidencia (el hilo principal se ejecuta sincrónicamente antes del primer fotograma):
  1. `restoreJournal.incompleteEntry()`(:223)——Archivo/DB Leer.
  2. `toolRegistrarBootstrapper`(:226)——forzar la creación de instancias ~28 Registrador (3-20 herramientas registradas por inicio),
     `ToolRegistrarBootstrapper.kt:53-61` Después de la finalización `assertBuiltInCategoryCoverage()`.
  3. `AutomationInitializer.initialize`(:233)——KoinJavaComponent.get ×3 + VirtualDisplayServerManager
     construir (`AutomationInitializer.kt:79-95`), la excepción tiene captura pero**La inicialización en sí está sincronizada.**.
  4. `mcpRegistry.startAll()`(:238)——El alcance interno.launch se conecta de forma asincrónica, el método en sí es ligero (`McpRegistry.kt:226-235`).
  5. `chatViewModel.toString()` Forzar inicialización ChatViewModel(:352)——init bloquear el registro ~30 un Flow collector
     + FTS Comprobación de coherencia + recuperación de la bandeja de salida (`ChatViewModel.kt:2165-2669`).
  6. `notificationManager.ensureChannels()`,`PendingToolCallStore.init`,`OAuthManager.init`,
     `MuseNotificationListenerService.initialize`(:264-272)——Respectivamente Preferencias Compartidas/Archivo IO.
  Rama de recuperación inacabada (:727-747) en begin() 30s Gira y espera a que se bloquee. `initializeRuntime()`,`_startupReady`
  Sólo configura true——MainActivity Está diseñado para que el usuario vea la pantalla de inicio durante el período de bloqueo, pero `ProcessWriteGate.begin()` girar
  Una vez cada 100 ms (:729-735) está ocupado esperando (debe cambiarse `mutex`/channel).
- Impacto:v2.5.x Después de la superposición, onCreate segmento de sincronización (2/3/5 Los tres más pesados) estimaron entre 300 y 800 ms (máquina de rango medio); inicio en frío del primer fotograma
  El retraso aumenta aún más. Ya existe el modo seguro y el sistema indica precalentamiento y retraso durante 3 segundos (:699-715), la dirección es correcta pero
  Las herramientas bootstrap y ChatViewModel fuerzan la inicialización aún antes del fotograma anterior.
- Sugerencia: Mover 2/3/5 a `_startupReady` La rutina IO posterior (la herramienta está registrada con la revisión StateFlow,UI puede esperar);
  begin() Gira para colgar y espera.

### F11. ChatViewModel dividir (S1-S5) son equivalentes en general, pero los eventos ocultos introducen de 2 a 6 transacciones de base de datos adicionales por ronda (P2, credibilidad: media)
- Ubicación:`app/.../ui/chat/ChatToolLoopRunner.kt:207, 1210, 1233, 1317, 1457`(TOOL_CALLED/TOOL_RESULT/
  turn empezar/final)+ `ChatGenerationController.kt:1366-1377, 1463-1475`
- Evidencia:`shadowEventsEnabled` Predeterminado **true**(`ConversationRebuildFlags.kt:6`), cada llamada a la herramienta produce
  TOOL_CALLED + TOOL_RESULT Dos eventos en la sombra;`recordConversationShadow` por
  `ConversationShadowEventSequencer.enqueue` ejecución en serie `sessionRepository.recordConversationEvent`
  (`SessionRepository.kt:906-917`,**Uno por evento `withTransaction`**:select + getByEventId +
  nextEventSeq + insert).
- Impacto: ronda única de 3 herramientas = 6 un asunto independiente + turn upsert + finish;Amplificación de escritura de base de datos de sesión de agente con uso intensivo de herramientas
  obvio (Room Transacciones fsync individualmente).Sequencer prensa `sessionId/turnId/generationSerial` Bucketing, sin sesiones cruzadas
  Problema de serialización, pero la cadena diferida que sobrevive a cada bloqueo de depósito crecerá con el número de rondas (`tails` Solo que al retirarlo finalmente, cuanto más larga sea la cadena, más profunda será la espera).
- Sugerencias:shadow Descarga de lotes de eventos (igual que la transacción de turno); o asignación de memoria eventSeq de nivel de turno + insertar procesamiento por lotes.

### F12. CompressionSummaryStore / recoverInterruptedGenerations / ensureIndexed La superposición de inicio y el límite de simultaneidad (P2, credibilidad: media)
- Ubicación:
  - `app/.../transformer/CompressionSummaryStore.kt:25-37`(MAX_ENTRIES=32 LinkedHashMap)
  - `MuseApp.kt:462-467`(seedDevDocs + indexDevDocs La misma corrutina está serializada,ensureIndexed Para cada documento no indexado
    uno por uno `rag.indexDocument`,`BuiltInKnowledgeDocSeeder.kt:119-141`)
  - `MuseApp.kt:289-299`(recoverInterruptedGenerations) y :342-344 (recuperación del libro mayor de chat grupal) se inician simultáneamente
- Evidencia:a) `CompressionSummaryStore` El nivel del agua está en `prepareHistory.applySessionCheckpoint` Escribe cada ronda
  (`ChatStreamCoordinator.kt:681-686, 701-707`),32 LRU desalojará repetidamente a los usuarios de sesiones múltiples, lo que resultará en
  `reusableSummary` Fallo volver a pasar la compactación LLM (`ContextCompressTransformer.kt:370-374`);b)
  `ensureIndexed` Al iniciar la rutina IO, incruste los documentos integrados uno por uno y cargue el índice HNSW (`MuseApp.kt:481-484`),
  memory backfill(:278-282) competir por el grupo IO——v1.0.52 El comentario reconoció el problema de la contención del calentamiento y retrasó los triples (:696-698),
  Pero indexDevDocs no se retrasa;c) Las tareas de recuperación no son mutuamente excluyentes.`recoverInterruptedGenerations` Enviar mensajes instantáneos a los usuarios
  Los mensajes se pueden escribir en la misma sesión al mismo tiempo (el punto de control es idempotente y el riesgo es bajo).
- Impacto: bajo usuarios intensivos de sesiones múltiples"Tasa de aciertos de caché de resumen"caer → Llamadas de compresión repetidas; cuando comienza la primera actualización, el índice RAG difiere del
  backfill Tome el grupo de subprocesos y arranque en frío la ventana de inanición de IO.
- Sugerencias:ensureIndexed Retrasar 5 segundos o fusionarse con la rutina de calentamiento del sistema;CompressionSummaryStore prensa
  sessionId LRU Aumento de peso.

---

## Verifique los elementos conocidos (confirme que no se informen repetidamente)

1. ANR Se ha solucionado la falsa alarma de vigilancia (ticket Conteo + exceso de congelación + controlador de referencia fuerte + tiempo real transcurrido)——esta ronda
   Revisión `AnrWatcher.kt:124-167` con `AnrDetector` Anotación de calibre (:256-263), no se encontró regresión ni informe.
2. AssistantAvatar OOM(Decodificado según el tamaño de la pantalla) Corregido——En esta ronda no se encontró ninguna nueva ruta OOM para el caché de imágenes de mensajes.
   (Coil ImageLoader Limitado a 25% de RAM + disco de 256 MB,`MuseApp.kt:951-960`;Colocación de la imagen
   `MessageImageStore` Sólo se salva el camino,`AppKoinModule.kt:131-135`)."Otras posibilidades no cubiertas"no encontrado
   Los nuevos caminos específicos no se consideran evidencia nueva según las reglas.
3. Las operaciones de CPU/disco de indexación de documentos se sacaron del hilo principal;44MB zip Fallo de importación (HNSW Se ha solucionado el problema de aceleración después de la colocación del disco.——`ensureIndexed`
   en appScope(IO), este informe F12 solo informa**Iniciar superposición de cronometraje**, no informar"Salir del hilo principal"sí mismo.
4. Guardia de vencimiento del cambio de sesión (SessionSwitchGuard) ha sido arreglado——`ChatSessionController.kt:542, 585-589` Revisión en marcha.
5. Se ha solucionado el salto de la rueda de precalentamiento de compresión.——`ChatStreamCoordinator.kt:994-997` `"compress_enabled" to !warmupActive` En posición.
6. Compose Costo de reorganización de lista larga/trampa de desplazamiento de MuseDialog——género PROJECT_GUIDE §5 Las alertas son conocidas y no serán reportadas.
7. SQLiteConnection.close warning / DataStore flake / TransformerPipelineTest——Las observaciones son conocidas y no serán reportadas.
8. canalizar al menos una vez,ProcessWriteGate volatile Comprueba antes de escribir,STRICT abajo ALWAYS_ALLOW——Compensaciones de diseño
   (AUDIT_FRAMEWORK §4), no informado;F10 El único periódico de China. begin() Detalles de implementación de giro.
9. detekt Deuda Estructurada (LargeClass etc.) con stock ktlint——No lo reportes.
10. Se ha revisado la semántica predeterminada del directorio de capacidades del modelo.——No se encontraron nuevas brechas en la resolución del directorio.
11. El lanzamiento del plug-in sandbox es una decisión de producto——La implementación del sandbox no fue tocada en esta ronda.
12. La exportación de diagnóstico no recuerda el texto./API key,sessionId Desensibilización al hachís——Revisión `GenerationTrace.TraceRecord`(solo estadísticas),
    `DiagnosticExporter`(excluyendo mensajes/texto) cumple con el estándar;F1 Sólo se informa el rendimiento del hilo principal, no"Registro faltante/ultra vires".
13. HANDOFF §11 P1 histórico no resuelto (presupuesto,SSE,prompt cache etc)——No repetido.

## Dirección 7(WorkManager/Doze) Conclusión especial

- `setExactAndAllowWhileIdle` Solo se utiliza para recordatorios creados explícitamente por el usuario./Nota Despertador (`ReminderToolsRegistrar.kt:163`,
  `QuickNotesViewModel.kt:632`), con `canScheduleExactAlarms` Rama, usada correctamente.
- tareas programadas/mensajes no solicitados/Momentos/Copia de seguridad en la nube: sondeo en proceso de 60 s/10 min + copia de seguridad de trabajo periódico de 15 min (KEEP)+
  Persistencia nextTriggerAt/lastTriggeredAt + disparador de primer plano (`enqueueCatchUpIfDue`),Doze comportamiento negativo
  "maintenance window Granularidad precisa", el código no tiene una precisión de retardo de dependencia de ruta única——**API La cadena de selección y degradación son correctas.**.
- Resumen diario:OneTimeWorkRequest autorrenovación + `startInProcess` collectLatest + Página de inicio remuneración de tres niveles, nombre de trabajo único
  prensa (hour,minute) Dividir y usar `claimDailySummarySlot` Preferencia idempotente——correcto.
- El único P2: Ninguno de los tres trabajadores de respaldo y los trabajadores de AutoBackup/StatsCache tienen acceso a la red/Restricciones de potencia (ver F9).
