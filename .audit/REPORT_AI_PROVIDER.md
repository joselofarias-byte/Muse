# AI Provider / Informe de revisión de enlace de bucle de herramientas/transmisión (2026-10)

## Resumen

Alcance de la revisión:`ai/` Enlaces principales en los 133 archivos fuente del módulo (OpenAIProvider/AnthropicProvider/GeminiProvider,ChatService,FirstEventWatchdog,ProviderCompat,ToolCallSanitizer,ToolCallArgsAccumulator,ChatEventNormalization,StreamFrameDiagnostics,ProviderPayloadNormalizer,ToolTraceProjection),app ChatViewModel lateral (5141 fila) con 2026-10 dfe3bbe3..15341441 salida dividida de ChatToolLoopRunner(S1)/ChatCompressionCoordinator(S2)/ChatToolApprovalCoordinator(S4)/ChatToolResumeCoordinator(S5),ChatStreamCoordinator,ChatGenerationController,ToolOrchestrator,A2A(web/a2a),TaskerTriggerReceiver, complemento onPromptFinalize Hook,GenerationTrace Enterrado. Método: leer archivos uno por uno + escribir archivos en la ruta crítica (flujo de eventos de transmisión, aprobación, ciclo de vida diferido,outbox/checkpoint Máquina de estado, reanudación de transmisión y deduplicación)+ git Enviar comparación. Conclusión general: la división y la reconstrucción son básicamente fieles ("Copiar palabra por palabra"La declaración se establece dentro del alcance de la inspección aleatoria), pero hay 2 estados de nivel P1/Defectos de carrera (tiempo de reescritura de reanudación, diafonía de seguimiento de compresión),1 Hay brechas semánticas de seguridad de nivel P1 (el truncamiento del piso destruye el emparejamiento de herramientas) y varias confiabilidad de nivel P2./Problemas de observabilidad.A2A La entrada es el lado nuevo donde se concentran más las brechas de concurrencia.

## lista de descubrimiento

> Ordenar por:P0 > P1 > P2;Los hermanos se clasifican por superficie de influencia. Credibilidad: alta=Evidencia directa de código; medio=Es necesario reproducir la inferencia estática; bajo=Riesgo teórico.

### F1. Reanudar reescritura"Reemplazar contenido antiguo"Ocurre después de que finaliza el flujo y se determina después de que se elimina el generador pendiente. —— Una falla de tiempo hace que la decisión de reescritura se base en un intento truncado (P1, credibilidad: alta)

- Ubicación:`app/src/main/java/io/zer0/muse/ui/chat/ChatToolLoopRunner.kt:852-878`(fin de la transmisión flush → supersede juicio),`app/src/main/java/io/zer0/muse/ui/ChatViewModel.kt:1129-1135`(shouldReplaceOnResumeSupersede implementación)
- Evidencia:
  ```kotlin
  // L862-878:Reanudar la transferencia y reescribir al final"La corriente ha terminadopendingBuilder ya sonrojado builder"Determinar más tarde
  if (streamError == null) {
      resumeOriginalText?.let { original ->
          val attempt = resumeAttemptText?.toString()
          if (host.shouldReplaceOnResumeSupersede(original, attempt, params.builder.toString())) {
              params.builder.setLength(0)
              params.builder.append(attempt)
              lastUiUpdateChars = 0    // ← conjunto 0
              ...
          }
      }
  }
  ```
  Y su etapa delta aguas arriba"Detección de reescritura"(L602-642) al golpear `shouldReplaceOnResumeRewrite` cuando:
  ```kotlin
  params.builder.setLength(0)
  params.builder.append(rewritten)   // rewritten = resumeAttemptText
  lastUiUpdateChars = params.builder.length   // ← Sincronizado aquí
  lastNotifChars = ...; lastPersistChars = ...
  effectiveDelta = ""
  ```
  Dos lugares correctos `lastUiUpdateChars` se maneja de manera inconsistente; y cuando reemplazan los golpes**Establecer solo `lastUiUpdateChars = 0`**,`lastPersistChars` / `lastNotifChars` / `lastTokenUpdateChars` Mantenga el valor anterior (es decir, la longitud del antiguo constructor antes de ser reemplazado).
- Impacto: en el escenario de reanudación (interrupción y reconexión de la red + reescritura del proveedor desde cero), si se reemplaza:① Cuando no haya más ContentDelta en el futuro, la interfaz de usuario será corregida por el asistente de actualización final, pero `throttledPersist()` juicio estrangulador `params.builder.length - lastPersistChars < PERSIST_CHAR_THRESHOLD`(L482) calculado en base a la longitud anterior —— Si el contenido nuevo es más corto que el contenido anterior, es posible que se omita la descarga. Una vez finalizado el proceso, el contenido de la base de datos seguirá siendo"artículo antiguo+Nuevo empalme de texto"Contenido duplicado;② Observe el recuento de palabras `lastNotifChars` Retraso, notificación de progreso que muestra un recuento de palabras incorrecto. Esta ruta es exactamente la declaración de referencia de deduplicación."Se han agregado registros reales como el diagnóstico de depuración."palabras perdidas/Región duplicada, perteneciente a**Lanzamiento recientemente propuesto/Ruta del código tipográfico**(no"No reapareció"problema en sí).
- Sugerencias:supersede Restablecimiento de sincronización después del golpe `lastPersistChars/lastNotifChars/lastTokenUpdateChars = params.builder.length`(Alineado con la ruta de reescritura de la etapa delta de L617-619), e inmediatamente compense una caída forzada.

### F2. GenerationTrace El estado pendiente lo lleva ThreadLocal y se usa con la rutina de activación de noteCompression yrecord Separación de llamadas a corrutinas —— Pérdida probabilística de la marca de compresión/diafonía (P1, credibilidad: alta)

- Ubicación:`app/src/main/java/io/zer0/muse/diagnostic/GenerationTrace.kt:131-152`(ThreadLocal pending),`app/src/main/java/io/zer0/muse/ui/chat/ChatCompressionCoordinator.kt:45`(noteCompression punto de llamada),`app/src/main/java/io/zer0/muse/ui/chat/ChatGenerationController.kt:1129-1149`(record punto de llamada)
- Evidencia:
  ```kotlin
  // GenerationTrace.kt:131-133
  private val pendingStreamEndKind = ThreadLocal.withInitial { "" }
  private val pendingFinishReason = ThreadLocal.withInitial { "" }
  private val pendingCompression = ThreadLocal.withInitial { false }
  // ChatCompressionCoordinator.kt:38-47 —— triggerAutoCompress en"Iniciar corrutina comprimida"grabar
  fun triggerAutoCompress(sessionId: String) {
      ...
      runCatching { io.zer0.muse.diagnostic.GenerationTrace.noteCompression() }   // (a)
      host.coroutineScope.launch(AppDispatchers.io) { ... }                        // (b) La compresión se implementa realmente en la nueva corrutina.
  ```
  mientras que noteStreamEnd está representado por `ChatToolLoopRunner.kt:895` Al final de streamRound (generar rutina,appScope grupo de subprocesos IO) escribe,record por `ChatGenerationController.recordDebugSummary`(finalmente cadena de la misma corrutina generada) leer —— Este par puede llegar al mismo hilo en la mayoría de los caminos (Dispatchers.IO Reutilización de hilos), pero `noteCompression` Escrito en la rutina de llamada finalizeResponse,`noteStreamEnd` Escrito en la rutina streamRound, ambos solo tienen**Sucede reutilizar subprocesos en el mismo grupo**solo cae en el mismo TraceRecord; de lo contrario:(a) Se perdió la marca de compresión (próximo registro). record() en tomarPendiente de poner"Escrito por otra persona pending"quitar → diafonía);(b) Peor aún: cuando el pendienteStreamEnd/finishReason escrito en la ronda de generación anterior no se consume (cancelled registro de ruta en withContext(NonCancellable) Las roscas interiores pueden ser diferentes),**Cadena en la siguiente ronda de generado TraceRecord**.
- Impacto: Diagnósticos exportados en generate-trace.jsonl `compressionTriggered`,`streamEndKind`,`finishReason` El campo no es confiable —— Los rastros de contaminación cruzada pueden ser engañosos"truncamiento de flujo/atascado"Esta es la dirección para solucionar problemas similares y este es exactamente el propósito de esta función.
- Sugerencia: cambie los tres campos pendientes a `record()` Parámetros de entrada explícitos (pasados ​​a lo largo de la cadena de llamadas desde streamRound/finalizeResponse a recordDebugSummary), o al menos crear un mapa con clave basado en sessionId/traceId en lugar de ThreadLocal.

### F3. FloorContextLimiterHook Pérdida de emparejamiento de herramientas y continuidad de giro al truncar el bloque SISTEMA conservado —— Genera 400 o ruptura semántica (P1, credibilidad: alta)

- Ubicación:`app/src/main/java/io/zer0/muse/hook/FloorContextLimiterHook.kt:42-68`
- Evidencia:
  ```kotlin
  val systemMsgs = history.filter { it.role == MessageRole.SYSTEM }
  val nonSystemMsgs = history.filter { it.role != MessageRole.SYSTEM }
  ...
  val trimmed = systemMsgs + nonSystemMsgs.drop(keepFromIndex)
  ```
  El gancho es `PromptFinalizeHook`(`SkillHook.kt:160-164`), en `ChatStreamCoordinator.applyTransformers` Ejecutado después de la canalización y antes de enviar al Proveedor (`ChatStreamCoordinator.kt:1015-1034`). presiona"USER piso"Truncado, pero:(1) `nonSystemMsgs` Si el punto de partida del área de reserva central cae sobre ASSISTANT(toolCalls) con sus resultados HERRAMIENTA**entre**, o el punto de partida es un mensaje HERRAMIENTA, un resultado_herramienta huérfano (aguas arriba `splitContextWindow`(UIMessage.kt:271-290) está especialmente procesado para evitar huérfanos, este gancho no lo tiene);(2) `systemMsgs` fue movido al encabezado de la lista en su conjunto, pero en la historia original**Mensaje de resumen comprimido del SISTEMA,TRUNCATED_TOOL_CHAIN_MARKER,RAG Inyectar otros bloques de SISTEMA con mensajes tiene semántica posicional**(ToolTraceProjection El resumen del SISTEMA de salida reemplaza la ronda asistente en el medio), todos los modelos frontales ven"Resumen de rondas de herramientas antiguas"Aparece antes de todos los diálogos, haciendo referencia al número./Confusión de referencias contextuales;(3) Rueda de continuación `resumeFromText` Inyecte el final del mensaje ASISTENTE (ChatService.kt:168-174) también se puede conservar después de truncar el punto de partida, pero el texto anterior se trunca y el contexto de continuación del modelo se rompe.
- Impacto: un usuario que activa FloorLimiterEnabled puede emitir una solicitud de 400 en un escenario de conversación larga + bucle de herramientas (el resultado_herramienta huérfano será ProviderPayloadNormalizer.stripOrphanToolMessages eliminar —— Pero después de borrar ASSISTANT(toolCalls) También queda huérfano y se eliminará junto con stripInvalidToolCalls, lo que provocará"Todos los rastros de llamadas a herramientas en la historia se han borrado silenciosamente."), lo que es más común es que la semántica del contexto del modelo sea confusa.
- Sugerencia: truncar y reutilizar `List<UIMessage>.splitContextWindow` Lógica alternativa huérfana (o entregar directamente el punto de corte del anzuelo a una función ya preparada);SYSTEM Los bloques se mantienen en sus posiciones relativas originales.

### F4. A2A Handler Las solicitudes simultáneas se bloquean entre sí: comparta el singleton ChatViewModel + "Espere a que isStreaming retroceda"Se determina que dos SendMessages concurrentes deben interferir entre sí (P1, credibilidad: alta)

- Ubicación:`app/src/main/java/io/zer0/muse/web/a2a/A2aHandler.kt:59-124`;`WebServer.kt:613-618`(JSON-RPC El punto final se llama en cada solicitud. `a2aHandler.sendMessage`)
- Evidencia:
  - `A2aHandler.sendMessage` Sin exclusión mutua/Cola: dos concurrentes `SendMessage` Ejecutar individualmente `switchSession(...)` o `createNewSession(...)`(L74-82), la llegada posterior interrumpirá la conversación del primero; el primero entonces `chatViewModel.send()`(L88) el mensaje enviado entra en el**después de ser cambiado por este último currentSessionId**(`ChatGenerationController.send()` L217 usar `accessor.snapshot.currentSessionId`).
  - juicio completo `withTimeout { chatViewModel.state.first { !it.isStreaming } }`(L99-101) es**Bandera de transmisión única global**: La solicitud A está esperando `!isStreaming` Cuando la solicitud B comienza a generarse,A La condición de espera es verdadera en la compilación B (isStreaming=true), A no regresa hasta que B termina y A recupera `sessionRepo.observeMessages(sessionId).first()` El último asistente es**B Responder**.
  - `cancelTask(taskId)`(L131-139) llamar `chatViewModel.stop()` —— lo que se detuvo es"sesión actual"La generación de (`ChatGenerationController.stop()` L118:`effectiveGenerationSessionId(accessor.snapshot)`), no tiene ninguna asignación a taskId; La cancelación de la tarea X por parte de un agente externo detendrá cualquier generación que estuviera en la sesión actual.
- Impacto:dedupe Dirección de enfoque de línea base 2 (A2A/Tasker Simultaneidad de entrada, propagación de cancelación,generation guard cobertura)——A2A si**El único punto de entrada que envía directamente solicitudes simultáneas externas a la interfaz de usuario para generar enlaces.**y sin serialización.generationSerial guard(ChatGenerationState.streamGenerationSerial) puede evitar que la generación anterior borre la interfaz de usuario de la nueva generación, pero no puede evitarlo."Se confunde la propiedad de dos solicitudes externas.".
- Sugerencias:A2A Capa más Mutex serial por controlador (MVP La semántica es sincrónica y de bloqueo, y la serialización no pierde capacidades);CancelTask prensa taskId→sessionId Post-mapeo `chatGenerationManager.stop(sessionId)`; O cambiar a `chatService` Generar directamente (sin pasar por el singleton de la interfaz de usuario).

### F5. ChatToolApprovalCoordinator El juicio del modo de aprobación utiliza la instantánea displaySessionId y el juicio de CONFIANZA se puede omitir cuando la herramienta recorre sesiones y se ejecuta en segundo plano./Liberado por error (P2, credibilidad: media)

- Ubicación:`app/src/main/java/io/zer0/muse/ui/chat/ChatToolApprovalCoordinator.kt:57-70`
- Evidencia:
  ```kotlin
  val displayedSessionId = host.currentSessionIdForApproval()   // ← Leer en tiempo real UI state
  val mode =
      if (host.stateStore.state.value.appRunAllowAllTools) {
          SessionPermissionMode.TRUSTED
      } else if (displayedSessionId == sessionId) {
          host.stateStore.state.value.sessionPermissionMode
      } else {
          host.sessionPermissionStore.getMode(sessionId, host.settings.defaultSessionPermissionModeFlow.first())
      }
  ```
  `appRunAllowAllTools` si**Estado global de la interfaz de usuario**(No vinculante a la sesión): Usuario registrado en la sesión A"Todos están liberados para esta carrera."Luego pase a la sesión B (state No restablecido por switchSession —— `ChatSessionController.switchSession` appRunAllowAllTools no se borra en la lista de actualización), la aprobación de las herramientas generadas en la sesión B se suspenderá debido a `appRunAllowAllTools=true` TRUSTED se lanza directamente y el modo de sesión B es PREGUNTAR. Contraste:`ChatToolResumeCoordinator`/Declaración semántica del modo de aprobación"TRUSTED Siguiente diseño ALWAYS_ALLOW"para compensaciones conocidas (AUDIT_FRAMEWORK Disciplina 4), pero**El límite de compensación es"esta carrera"**, la continuación de las sesiones excede los límites declarados.
- Impacto: la granularidad de aprobación entre sesiones en la generación en segundo plano (el cambio de página no se cancela) es un nivel más amplio de lo que esperaba el usuario; es una amplificación de permisos en lugar de una vulnerabilidad (el analizador unificado todavía es rechazado), por lo que P2.
- Sugerencias:switchSession/createNewSession restablecer cuando `appRunAllowAllTools`, o registre la sesión en el momento de la verificación por sessionId durante la aprobación.

### F6. streamRound Uso de la rueda de herramientas Anulación directa delta state.usageTokens —— Anthropic El token de entrada de uso dividido se utilizará en la siguiente ronda."salida pura"La anulación es 0(P2, credibilidad: media)

- Ubicación:`app/src/main/java/io/zer0/muse/ui/chat/ChatToolLoopRunner.kt:793-797`;`ai/src/main/java/io/zer0/ai/anthropic/AnthropicProvider.kt:372-386`
- Evidencia:
  ```kotlin
  // runner L793-796
  is ChatStreamEvent.UsageDelta -> {
      state.usageTokens = event.usage   // Los comentarios dicen"La cobertura directa garantiza que se retenga la última vez(cantidad total)"
  }
  // AnthropicProvider L381-386 —— UsageDelta Entrada combinadaUso y salida message_delta de message_start
  ChatStreamEvent.UsageDelta(
      UsageTokens(
          promptTokens = inputUsage?.input_tokens ?: 0,
          ...
  ```
  La fusión antrópica dentro de una sola ronda es autoconsistente (Provider Completar la fusión de entrada+salida en el lateral). pero**Múltiples ciclos de herramientas**Cada ronda aquí es message_start/message_delta independiente: el UsageDelta de la enésima ronda solo refleja el mensaje de la enésima ronda;`finalizeResponse`(ChatGenerationController.kt:1516-1543) poner"ronda final usage"Se conserva como metadatos simbólicos para todo el mensaje del asistente. —— Cuando la herramienta se repite durante muchas rondas, los PromptTokens registrados en el mensaje final son solo la entrada de la última ronda (y el historial de la herramienta es más pequeño después de ser comprimido por ToolTraceProjection en la última ronda), y el calibre estadístico es sistemáticamente bajo. Escribe en los comentarios."Puede emitirse en cada ronda del ciclo de herramientas de múltiples rondas y el consumidor toma la última (cantidad total)"(Provider.kt:98), el"cantidad total"La suposición no es válida para rondas múltiples antrópicas.
- Impacto:A5 token Visualización de uso/Distorsión estadística (no participa en la determinación del presupuesto,AgentTokenBudget Se ha omitido nulo, pero aquí no es nulo y es demasiado pequeño), son estadísticas/Demostrar defectos.
- Sugerencias:runner La rueda de clic lateral acumula tokens de aviso (o solo para"Sin rondas de herramientas"anular), o marcar cuando finalice"usage para el calibre de ronda final".

### F7. streamRound vaciar al final de pendienteBuilder y leer directamente después de pendienteFlushJob cancelarAndJoin sin bloqueo —— Hay una condición de carrera de ventana con la rutina de actualización independiente (P2, credibilidad: media)

- Ubicación:`app/src/main/java/io/zer0/muse/ui/chat/ChatToolLoopRunner.kt:450-475`(flushPendingToUi mantener pendienteFlushMutex),`527-533`(pendingFlushJob),`844-860`(join sin bloqueo después de la descarga)
- Evidencia:
  ```kotlin
  // L844-853: collect Después del regreso normal
  } finally {
      pendingFlushJob.cancelAndJoin()
  }
  ...
  if (pendingBuilder.isNotEmpty()) {
      params.builder.append(pendingBuilder)     // ← Leer sin bloqueo pendingBuilder
  ```
  `cancelAndJoin` Se garantiza que la rutina de actualización finalizará, por lo que**estado estacionario**sin carrera —— Pero hay una ruta de excepción:`takeWhile` Bucles de reintento compatibles (L535-843 do-while) dentro `collect` Cuando se lanza una CancellationException (el usuario se detiene), se usa el mismo método para finalmente unirse y luego salir normalmente; la verdadera ventana es `Done` rama de eventos (L801-811) los comentarios escriben claramente "The flush coroutine may still own pendingBuilder until collect returns. Defer all content mutations..." —— El retraso se ha producido. El riesgo restante se concentra en **Error/StreamInterrupted Collect no regresa hasta después del evento.**brecha: actualizar corrutina `flushPendingToUi()` puede estar en `streamError` Después de la asignación,join Anteriormente, corté parte de pendienteBuilder en el constructor y llamé `updateAssistant(isStreaming=true)` —— La orden es inofensiva. Juicio integral de seguridad en estado estacionario, pero `chunkIntervals`(ArrayDeque) en ContentDelta `pendingFlushMutex.withLock` escrito dentro (L665-672), lea en el bloqueo de rutina de descarga (computeAdaptiveSlice L143-149), las dos cerraduras son consistentes —— **Los únicos que no están bloqueados son L989-991 y L1054-1055. `chunkIntervals.clear()` / `pendingBuilder.clear()`**(inténtalo de nuevo/Ruta de reinicio de rueda complementaria, la rutina de actualización se ha unido en este momento, seguro). Conclusión: Actualmente no existe ningún Estado competidor, pero su idoneidad depende de"Los tres puntos de reinicio son todos después de unirse."Este acuerdo implícito no tiene comentarios./Sin protección de aserción, marcada como observación P2.
- Sugerencia: Convergir todas las operaciones de escritura dependienteBuilder/chunkIntervals en mutex (restablecer la capa del paquete de ruta) `withLock`), eliminando el acuerdo implícito.

### F8. ChatToolLoopRunner Retroceso de rueda complementario (A-01) Cuando toolModel responde, el registro de toolMediaMessages de esta generación y los restos pendientes de ToolCall no se limpian (P2, credibilidad: media)

- Ubicación:`app/src/main/java/io/zer0/muse/ui/chat/ChatToolLoopRunner.kt:1044-1092`(retroceso de rueda complementario),`1300-1313`(Bolsa final)
- Evidencia: retroceso compensatorio `return replayed`(L1092) devuelve directamente StreamRoundResult.Error, capa exterior `runToolLoop` Continuar por L1315 `if (!toolLoopResult.success)` rama fallida → L1303-1313 La cobertura de medios aún se aplicará (la cobertura estará en run() capa, no capa streamRound), no hay ningún problema aquí. El verdadero residuo es: la solicitud de vuelta atrás es `params.copy(forceMainModel = true)` de**recursividad streamRound**, es `duplicateRemaining`(L1056) hasta que se haya reiniciado el estado local de esta ronda, pero el upstream `ToolOrchestrator.runLoop` Ejecutado antes de entrar en esta ronda. `savePendingToolCalls`(ToolOrchestrator.kt:1060-1062)——Esta ronda es"Rueda de respuesta final sin herramientaLlamadas", no hay pendientes, seguro. Después de verificaciones aleatorias, este artículo fue degradado a**Comprobado y aprobado**, no lo reportes como defecto; conserve la descripción para demostrar que ha sido verificada.

### F9. TaskerTriggerReceiver Una única ronda de diálogo reconstruye el contexto predeterminado completo cada vez:messages Contiene solo un USUARIO, pero el texto completo por defecto es el modelo global y la activación. Provider —— Tasker La radiodifusión puede generar solicitudes de cobro y no tiene control de frecuencia (P2, credibilidad: alta)

- Ubicación:`app/src/main/java/io/zer0/muse/intent/TaskerTriggerReceiver.kt:63-100`
- Evidencia:
  ```kotlin
  val completion = withTimeoutOrNull(LLM_TIMEOUT_MS) {
      chatService.completeText(
          messages = messages,          // ← solo uno USER
          temperature = assistant?.temperature,
          maxTokens = assistant?.maxTokens,
      )
  }
  ```
  - Sin antivibración/Control de frecuencia:Tasker La configuración de activadores de alta frecuencia (como cada minuto) en el lateral completará continuamente las solicitudes de facturación (120s El resultado pendiente de goAsync se bloquea dentro del tiempo de espera,BroadcastReceiver Una vez agotada la ventana, el sistema puede reciclarse por adelantado, pero la rutina se construye sola. `CoroutineScope(SupervisorJob()+Dispatchers.IO)` Continúe ejecutando la llamada LLM y abandone la biblioteca. —— scope Desacoplada del ciclo de vida del receptor, la llamada no será cancelada).
  - `KoinJavaComponent.get<ChatService>` Resolver singleton en receptor —— OK; pero `sessionRepo.observeSessions().first()` Construir con coincidencia de título/Reutilizar sesión, se pueden crear dos activadores con el mismo nombre cuando se activa dos veces al mismo tiempo. "Tasker gatillo" sesión (observeSessions().first() Sin atomicidad con createSession).
- Impacto: el portal de automatización externo carece de control de frecuencia y protección de simultaneidad, y una mala configuración dará lugar a facturación continua y sesiones de spam.
- Sugerencia: agregue una limitación simple en la entrada (como la deduplicación en 10 segundos); use la unicización de título al crear una sesión o verifique primero y luego cree una exclusión mutua.

### F10. PluginPromptFinalizeHook Serialice el historial preparado completo en una cadena de script JS y ejecútelo a través de WebView —— Hacer una evaluación WebView del hilo principal (P2, credibilidad: alta)

- Ubicación:`app/src/main/java/io/zer0/muse/data/plugin/PluginPromptFinalizeHook.kt:48-72, 75-89`;`app/src/main/java/io/zer0/muse/tools/JsSandbox.kt:199-308`
- Evidencia:
  - `buildCallScript` Deletree todo el historial (incluido el texto del marcador de posición después del enmascaramiento de PII y el texto completo de los resultados de la herramienta) en una cadena de origen JS (L76-88), una vez por mensaje `AppJson.encodeToString`, largas conversaciones (cientos de × varios KB) para generar un script de nivel MB y luego `webView.evaluateJavascript` Se analiza y ejecuta toda la sección, y `JsSandbox.execute` Fijo `withContext(Dispatchers.Main)`(L204) y mantener el global `executionMutex`(L208)—— Una sesión de complemento con un tiempo de espera de 10 segundos**Bloqueo del mutex del hilo principal**(El bloqueo se suspende y espera sin bloquear el hilo principal, pero WebView eval realiza la compilación y ejecución de V8 en el hilo principal, y los scripts largos bloquearán los marcos de la interfaz de usuario).
  - El objeto de evento contiene sólo `{role, content}`(L78),**no contiene reasoning/toolCalls/id** —— Nuevo historial devuelto por el complemento `parseHistory`(L92-98) El UIMessage construido solo rellena el rol/contenido,**thinkingSignature / thinkingEncryptedContent / toolCalls / variant Todos los campos están perdidos.**. Si el complemento devuelve el evento tal como está (convención "return event"), todos los mensajes se degradan y se reconstruyen:① en el historial del ciclo de la herramienta ASSISTANT(toolCalls) convertirse en texto plano assistant —— Próxima ronda de solicitudes ProviderPayloadNormalizer.stripOrphanToolMessages Los mensajes de HERRAMIENTA emparejados se eliminarán como huérfanos (todo el historial de la herramienta se evapora y el modelo"olvidar"He llamado a la herramienta yo mismo);② Anthropic thinking Firma perdida → Múltiples rondas de pensamiento solicitan 400 (AnthropicProvider.kt:718-725 Se requiere repetición firmada).
  - fail-open Verificación de declaración: excepción/tiempo de espera/La ruta de retorno vacía es fallback(event)(L51-69),fail-open establecido; pero**"Regresar exitosamente"El camino, en cambio, destruye la fidelidad histórica** —— Esto no es una brecha de ruta anormal, sino un defecto de ruta normal.
- Impacto: bucle con herramientas después de habilitar cualquier complemento con el enlace Prompt_finalize/El comportamiento del diálogo en Thinking Replay se deteriora a partir de la segunda ronda (el historial de la herramienta se pierde,Anthropic thinking 400).
- Sugerencias:hook lo único correcto"Permitir modificación"Haga diferencias en los campos (devuelva el parche o permita que se devuelva nulo para indicar que no hay cambios); o al menos fusionar las llamadas a herramientas/campos de pensamiento del mensaje original nuevamente en el nuevo UIMessage por identificación/posición durante parseHistory.

### F11. ChatToolApprovalCoordinator bucle de tiempo de espera en approvalTimeoutPaused=true ralentí en pasos de 200 ms,remainingMs No disminuido sino diferido. Espera reconstrucciones en cada paso withTimeoutOrNull —— La activación por suspensión de alta frecuencia se produce al plegar durante mucho tiempo (P2, credibilidad: alta)

- Ubicación:`app/src/main/java/io/zer0/muse/ui/chat/ChatToolApprovalCoordinator.kt:120-145`
- Evidencia:
  ```kotlin
  while (approvalResult == null) {
      val stepMs = if (host.approvalTimeoutPaused) 200L else minOf(200L, remainingMs)
      val finished = withTimeoutOrNull(stepMs) { deferred.await() }
      if (finished != null) { approvalResult = finished }
      else if (!host.approvalTimeoutPaused) { remainingMs -= stepMs; ... }
  }
  ```
  Tarjeta de aprobación doblada por el usuario (approvalTimeoutPaused=true) período: encuesta diferida (cada 200 msCompletableDeferred.await dentro de withTimeoutOrNull), colapsa durante 10 minutos = 3000 Despierta después del tiempo de espera. La funcionalidad es correcta (la semántica de congelación del tiempo de espera cumple con los comentarios de CHAT-08), pero la implementación está ocupada sondeando; el usuario abandona la aplicación durante el plegado y la rutina generada continúa despertándose a 5 Hz.
- Impacto: poder/Los gastos generales de programación son pequeños pero reales; el inconveniente más práctico es**Sin límite superior para la congelación del tiempo de espera** —— Cuando el usuario olvida expandir y el proceso está activo, el bucle de la herramienta se cuelga permanentemente esperando aprobación (continuo). stop() Puede cancelarlo, pero el estado inactivo se bloquea sin dejar rastro) y siempre se muestra la notificación de servicio en primer plano. La aceptabilidad es cuestionable pero consistente con"30s congelación semántica"La afirmación es consistente, entonces P2.
- Sugerencias:paused Usar en su lugar `host.approvalTimeoutPaused` El flujo espera la recuperación (impulsada por eventos) en lugar de un sondeo de 200 ms.

### F12. ChatToolResumeCoordinator La cadena de recuperación rellena todos los resultados de la herramienta con mensajes de HERRAMIENTA y directamente launchStream —— Si el pendiente restaurado contiene assistant(toolCalls) Para los huérfanos fuera de la correspondencia (colgados pendientes generados por la ventana de bloqueo), la siguiente ronda de solicitudes puede ser 400 (P2, credibilidad: media)

- Ubicación:`app/src/main/java/io/zer0/muse/ui/chat/ChatToolResumeCoordinator.kt:47-246`
- Evidencia: Proceso de recuperación de `PendingToolCallStore.getForChat` Realizar artículo por artículo y `upsertMessage(chatId, toolMsg)`(L196).TOOL Después de que el mensaje se almacena en la base de datos, si es correspondiente ASSISTANT(toolCalls) Mensaje en la ventana de bloqueo**No en stock**(ToolOrchestrator.kt:1047-1055 La condición de inclusión es que el contenido/razonamiento/medios no esté vacío —— ¡La rueda de llamada de herramienta pura se omite cuando el contenido está vacío! ), entonces hay una HERRAMIENTA huérfana y ningún asistente emparejado en la base de datos. La próxima vez, prepareHistory de launchStream defiende la limpieza (ChatStreamCoordinator.kt:607-614) solo eliminar"ASSISTANT con toolCalls pero el siguiente no lo es TOOL",**No elimines a los huérfanos TOOL**;Finalmente por la capa Proveedor ProviderPayloadNormalizer.stripOrphanToolMessages eliminar —— No 400, pero los resultados de la herramienta no son visibles para el modelo (el modelo no sabe que se ejecutó la herramienta). Al mismo tiempo, la condición de omisión de biblioteca de ToolOrchestrator.kt:1048-1056 hace**El mensaje toolCalls de la rueda de llamada de herramienta pura no cae en la biblioteca**, después de reiniciar buildCommitParts/toolRounds, la restauración y el juicio huérfano de prepareHistory perderán sus puntos de anclaje.
- Impacto: Es posible que los resultados de las herramientas restauradas no sean visibles para los modelos que vuelven a invocar la misma herramienta (las herramientas idempotentes son inofensivas, las herramientas de efectos secundarios se ejecutan repetidamente) —— y artículos mundialmente conocidos 1"at-least-once"La frontera está cerca, pero aquí está.**Pérdida de emparejamiento reparable por código**, no es un problema intrínseco de falla del proceso).
- Sugerencias:ToolOrchestrator Par de condiciones de almacenamiento"mensaje del asistente con herramientaLlamadas"Entrada sin condiciones (toolCalls Ya sea vacío o eliminado, desacoplado de si el contenido está vacío).

### F13. GeminiProvider usage-only La determinación final del cuadro se complementa con la protección hasEmittedContent, pero la ruta StreamInterrupted"Truncamiento seguro normal sin contenido"Cuando la interrupción de la red hace que la interfaz de usuario reanude la transmisión y vuelva a intentarlo (P2, credibilidad: media)

- Ubicación:`ai/src/main/java/io/zer0/ai/gemini/GeminiProvider.kt:56-60, 318-337, 1223-1249`
- Evidencia:dedupe columna de referencia"Gemini usage-only terminal"es un elemento de bucle cerrado conocido. Situación actual:`shouldCompleteUsageOnlyFrame(hasEmittedContent, candidateCount, finished)` Realizado (L331), las tramas de solo uso se envían cuando hay contenido Done("STOP") —— **La brecha se ha cerrado y este informe está marcado como requerido por deduplicación."Bucle cerrado"**. Margen restante: sin contenido + finishReason=STOP Shifa `StreamInterrupted`(L1244),runner Juez lateral L920-927 `isNetworkError = streamInterruptedThrowable is IOException || (streamInterrupted && retryType == ChatErrorType.NETWORK)` —— StreamInterrupted No hay arrojable y el mensaje es. "STREAM_INTERRUPTED" clasificación,classifyErrorTypeStatic Existe una alta probabilidad de que sea DESCONOCIDO, no satisfecho. NETWORK → Vuelva a intentar manualmente la degradación. Comportamiento aceptable pero mensaje de error."Corte de red"Engañoso (en realidad, una respuesta vacía del modelo). bajo riesgo,P2.
- Sugerencia: cuando no haya contenido en FinishReason, envíe un error con una copia clara ("El modelo no genera contenido.") en lugar de reutilizar StreamInterrupted.

### F14. ChatGenerationController.launchStream En la capa exterior de chatGenerationManager.launchGeneration, primero se incrementa streamGenerationSerial, pero A2A/continueGeneration/regenerateLastAssistant Hay tres entradas en isStreaming=true Aún puedes ingresar (canStart Sólo se envían bloques)—— La serialización de dos generaciones de generación se basa en el trabajo anterior.join, mientras que state.builder y otros StreamRunState compartidos se crean recientemente en cada generación y se verifican sin compartir; la brecha real es el estado adjunto (P2, credibilidad: media)

- Ubicación:`app/src/main/java/io/zer0/muse/ui/chat/ChatGenerationController.kt:229-244`(continueGeneration),`248-302`(regenerateLastAssistant)
- Evidencia:`continueGeneration` Sólo comprobar `accessor.snapshot.isStreaming`(UI Bandera, establecida en falso mediante detachStreaming al cortar páginas.ChatStreamCoordinator.kt:121-123),**La generación de fondo aún está en ejecución.**(chatGenerationManager.isStreaming(sessionId)==true) cuando el usuario hace clic"continuar generando":`launchStream` → `launchGeneration` → `previousJob.cancel()`(ChatGenerationManager.kt:117-118) Cancelar la vieja generación. → join Después comienza la nueva generación. La cadena de comportamiento es correcta (la vieja generación se cancela,[interrumpido] ubicación del disco, continuación de nueva generación), pero el isStreaming de la interfaz de usuario se estableció en falso mediante desconexión, y el usuario"Pensé que no se había generado."Cuando el momento continúa, en realidad se activa una cancelación implícita de la generación de fondo. a A2A (F4) Este es el multiplicador de carrera.
- Impacto:detachStreaming de"Corte de páginas y generación continua de fondos."La promesa entra en conflicto con el juicio de disponibilidad de continueGeneration: cambie de página y vuelva a ver [interrumpido] Haga clic en Continuar. Si el fondo aún se está generando, se interrumpirá silenciosamente.P2(El comportamiento puede justificarse como una acción explícita por parte del usuario).
- Sugerencias:continueGeneration verificación previa `chatGenerationManager.isStreaming(sessionId)`, cuando sea cierto, deténgase primero o rechace directamente.

### F15. GenerationTrace.appendToFile El truncamiento continuo lee el archivo completo en la memoria (2MB) y cortar por punto medio de bytes —— El punto de corte puede quedar en medio de caracteres multibyte UTF-8, lo que genera líneas incorrectas; y rodar hace el disco IO dentro de la cerradura (P2, credibilidad: alta)

- Ubicación:`app/src/main/java/io/zer0/muse/diagnostic/GenerationTrace.kt:177-188`
- Evidencia:
  ```kotlin
  if (file.exists() && file.length() > MAX_FILE_BYTES) {
      val bytes = file.readBytes()
      val half = bytes.copyOfRange(bytes.size / 2, bytes.size)
      file.writeBytes(half)
  }
  file.appendText(AppJson.encodeToString(rec) + "\n")
  ```
  - `bytes.size / 2` es el punto medio del byte,UTF-8 Los caracteres chinos son de 3 bytes. Después de cortar desde el medio, lo más probable es que la primera línea sea JSON dañada;DiagnosticExporter Codificación línea por línea (DiagnosticExporter.kt:49-53) En caso de mal comportamiento, no existe una explicación de tolerancia a fallos.
  - Cada récord tiene `synchronized(lock)` Hacer E/S de disco (appendText), la rutina del enlace principal espera la escritura del disco en este bloqueo cuando se genera el estado final. —— record El paquete runCatching en sí no afecta la corrección, pero"Los observadores puros no introducen esperas en los caminos calientes."declaración (GenerationTrace.kt:25) después de alcanzar el umbral de desplazamiento**Agregar cada vez**Ambos disparan readBytes(2MB)+writeBytes —— 2MB Los niveles se leen y escriben repetidamente hasta que el archivo vuelve a superar el límite.
- Impacto: el archivo de seguimiento exportado puede contener líneas incorrectas; después de alcanzar los 2 MB, se generarán 4 MB de IO adicionales en cada ronda.
- Sugerencia: Desplácese para alinear por fila (busque la más cercana) \n), restablezca el juicio de umbral después de desplazarse (actualmente, se verifica que cada anexo supere el límite). → Después de alcanzar el límite superior, rueda cada vez hasta que la siguiente línea lo empuja por encima del límite y rueda nuevamente. —— De hecho, después de cada desplazamiento, el archivo vuelve a ~1MB, entonces normal; pero el problema del mal comportamiento todavía existe).

### F16. OpenAIProvider stream-guard Ventana de verificación secundaria:`streamSourceClosed` El intervalo de verificación con la rutina de respaldo es de 500 ms + fluctuación,SSE cerrado pero streamProducedMore=true La combinación bloqueará permanentemente el flujo (P2, credibilidad: media)

- Ubicación:`ai/src/main/java/io/zer0/ai/openai/OpenAIProvider.kt:430-447`
- Evidencia:
  ```kotlin
  val streamProducedMore = currentContent > totalChars
  if (!streamSourceClosed.get() && streamProducedMore) { ...Renunciar y retroceder,restablecer streamGuardDone... return@launch }
  ```
  v1.0.51 Un archivo Léame de comentario describe la solución ("Si la conexión realmente ya está onClosed,Esto hará que la rutina alternativa regrese directamente y se cuelgue permanentemente."), agregó `streamSourceClosed` guardia —— La condición de carrera conocida después de la verificación.**Fijo**. Subcaso residual:`streamSourceClosed` Establecido como verdadero por onClosed durante la espera de reserva, pero vea la rama onClosed L920-923 pendingFallback=true cuando el regreso no es close —— fallback La corrutina luego verifica streamSourceClosed=true → Continuar con el respaldo sin transmisión → correcto.**Aprobado, no considerado defecto**;Marcar sólo la dependencia de guardia `streamSourceClosed` de sucede antes (EventSource hilo de devolución de llamada vs corrutina alcance.launch):`streamSourceClosed` Es AtomicBoolean, la visibilidad es correcta. Mantenga este artículo como"Verificado y no reportado repetidamente"registro.

### F17. ChatToolLoopRunner runToolLoop mango de acabado `generationState.toolAssistantId/activeToolSessionId` Establecer nulo e incrementar el token —— Pero finalizeResponse (camino de éxito) está en run() Ejecutar después de devolver verdadero.MediaGenHost La generación de video en vuelo (tal vez decenas de segundos) todavía se juzga por el token anterior después del final. isActive —— comprobar (P2, credibilidad: media)

- Ubicación:`app/src/main/java/io/zer0/muse/ui/chat/ChatToolLoopRunner.kt:1310-1313`;`app/src/main/java/io/zer0/muse/ui/chat/ChatMediaGenHost.kt:78`
- Evidencia:A-13 Comenta claramente"token de incremento de cierre,Deshabilite cualquier herramienta que aún esté en tránsito para realizar escrituras multimedia."——esto es**Compensaciones de diseño declaradas**(La herramienta multimedia se cerrará antes de que se complete.=Descargo de responsabilidad), no es un defecto. Pero verificación de límites: la herramienta de video espera a que se complete antes de regresar mediante withTimeoutOrNull de ToolOrchestrator (TOOL_TIMEOUT_MS=120s), por lo tanto"todavía en camino"Sólo ocurre cuando el usuario se detiene./Ruta de cancelación de excepción (cancel Ya cubierto en ChatGenerationController.kt:1382-1384).**Comprobado y aprobado**. El único rincón: el camino hacia el éxito. run() La anulación de cola ocurre antes de finalizeResponse, y triggerAutoCompress y la escritura de memoria en finalizeResponse no dependen de los medios. —— Sin impacto.

### F18. A2aHandler.recentTasks Utilice LinkedHashMap + sincronizado pero GetTask también lee synchronized —— Sin defectos concurrentes;CancelTask La espera withTimeout del sendMessage original no volverá inmediatamente (P2, credibilidad: alta)

- Ubicación:`app/src/main/java/io/zer0/muse/web/a2a/A2aHandler.kt:99-101, 127-139`
- Evidencia:`cancelTask` sintonizar `chatViewModel.stop()` La generación se cancela, pero el camarero de sendMessage sigue ahí. `chatViewModel.state.first { !it.isStreaming }` —— stop() conjunto isStreaming=false Luego espere el regreso y luego observeMessages obtiene"último artículo assistant"posible obtener**Media respuesta interrumpida**y marque COMPLETADO (L108-114 COMPLETADO Incondicionalmente). La semántica de cancelación no se propaga al estado de la tarea.
- Impacto: recibido después de que un agente externo inicia CancelTask state=COMPLETED La recepción exitosa (incluida la mitad del contenido) viola la semántica A2A.
- Sugerencias:cancelTask grabar taskId→cancelled colección,sendMessage Consulta y devuelve CANCELADO al finalizar.

## Comprobando elementos conocidos

controlar `AUDIT_FRAMEWORK.md` Mundialmente conocido 1-13 y `DEDUPE_AI_PROVIDER.md` Fijo/Lista conocida, los siguientes son todos**No reportado como nuevo descubrimiento**:

1. OpenAI stream-guard Si la reversión falla, se emitirá un error.FirstEventWatchdog respaldo único,Done/pendingBuilder La carrera de cierre,callback generation guard —— control al azar `OpenAIProvider.kt:313-553`,`FirstEventWatchdog.kt`, el mecanismo está en su lugar (F16 registrado como"Comprobado y aprobado").
2. HerramientaRondaPresentaciónPolítica / pensamientoInstrucción / Sin falsificación reasoning —— runner L186,L276-292 con `ToolRoundPresentationPolicy` coherente.
3. resumeOverlapToDrop sastrería conservadora / "La primera palabra fue tragada."No reapareció —— El problema en sí no se informó repetidamente.F1 es adyacente a**Nuevos defectos de sincronización**(lastPersistChars fuera de sincronización).
4. Herramienta de catálogo de capacidadesUtilice semántica predeterminada,catalog Tres estados,SUSPICIOUS marca —— `AiModule.shouldSendTools`(L111-135) es consistente con la línea de base.
5. debugInfo exportación unificada —— `recordDebugSummary` en completed/failed/cancelled/context_overflow Los cuatro caminos se llaman (L1304/1326/1380/1426/1554), verificado y aprobado.
6. Gemini usage-only terminal —— **F13 marca"Bucle cerrado"**(shouldCompleteUsageOnlyFrame se ha realizado,GeminiProvider.kt:56-60, 331).
7. Anthropic/Gemini stream abort listener —— **Realizado**(AnthropicProvider.kt:157-161,GeminiProvider.kt:183-186 Ambos registran addAbortListener y cancelan llamada/eventSource), anotan de acuerdo con los requisitos básicos"Bucle cerrado".
8. La antigua carrera de reintento de EventSource se verificará en la red real —— Las condiciones no fueron reproducidas y el informe no fue reportado.
9. Mundial 1/2/3(at-least-once,ProcessWriteGate Semántica, falta aceptación del dispositivo)—— Intacto.
10. detekt Deuda estructural, traducción de paquetes de idiomas —— No reportado.
11. El sandboxing de complementos libera la toma de decisiones en sí —— no reportado;F10 Lo que se informa es la falla de fidelidad histórica en la implementación del gancho (pertenece a"Dejemos de lado las extralimitaciones en la implementación/Defectos"categoría).
12. subagente timeout_ms 30s~900s Análisis y tiempos de espera con progreso. —— `SubagentRunSkill.kt:86-88`(coerceIn(30_000, 900_000)) y `SubagentRunner.kt:545-559`(error Adjunto (rondas/toolCalls/lastText) pasó la verificación sin defectos.

## Declaraciones de método y anulación

- Lea archivo por archivo:ai/ Archivos principales 16 (Provider 3 + ChatService + watchdog + compat + sanitizer + accumulator + normalization + frame-diagnostics + payload-normalizer + trace-projection + Provider/UIMessage/HttpSupport/HttpDefaults + ToolTraceProjection);app/ Generar archivo enlace 20 (ChatViewModel sección clave,4 nuevo coordinador,2 un puente anfitrión,StreamCoordinator,GenerationController,StreamRunState,GenerationState,StateStore,ToolController,ToolOrchestrator,ToolExecutionPolicy,SubagentRunSkill/Runner sección clave,MediaGenHost,A2A×2,Tasker,GenerationTrace,Hook×3,PluginHookBridge,PluginPromptFinalizeHook,JsSandbox).
- git Contraste:dfe3bbe3(S1)/a75b6176(S2)/15341441(S4+S5) Envíe la información y verifíquela con diff stat, y verifique aleatoriamente la integridad de reenvío de la interfaz del puente (toolLoopHostBridge 16 elemento + herramientaAprobaciónHostBridge todos los elementos).
- no cubierto/Por verificar:okhttp3/sse implementación interna,ChatMessageController/ChatTaskCardCoordinator Texto completo, chat grupal y enlaces de canales (se revisarán por separado),memory módulo cliente LLM,tests Uso del catálogo caso por caso.
