# Informe de revisión de confiabilidad de canal y backend (enlace 4)

## Resumen

Alcance de la revisión:`app/src/main/java/io/zer0/muse/channel/` Los 24 archivos(Receptor de cinco plataformas/cliente/transmisor/medios de comunicación),y punto de integración Despacho ChannelInbox(ChannelAutoReply),webhook enrutamiento(web paquete),A2A/Tasker Entrante, devolución de notificación,channel_media ciclo de vida,MuseApp empezar/ON_STOP observador,ToolRegistry Herramientas de canal. método:Lectura completa del archivo + cross grep para verificar la persona que llama/ciclo de vida/límite de concurrencia,Todos los hallazgos se proporcionan con evidencia de código.;DEDUPE_CHANNEL.md Seis direcciones de exploración y dos"Verificar antes de citar"cuentas antiguas(ON_STOP Detener el receptor, fluctuación de retroceso exponencial)La verificación del código se ha abierto uno por uno.

Conclusión general:Distribución confiable de la cadena principal.(PENDING Realizar el pedido→PROCESSING restaurar→BLOCKED/retirarse)darse cuenta sólidamente,webhook La autenticación fallida, la protección de limpieza de medios y la reescritura de condiciones de compresión son consistentes con la línea base. pero encontrado **Hay múltiples bucles receptores."Muerte permanente y sin autocuración."camino**(P1),**A2A Reutilizar ChatViewModel global con hombre-máquina/A2A concurrente interfiere entre sí**(P1);DEDUPE Las dos preguntas clave tienen respuestas definitivas:①Fallo de un solo canal**No morirá de hambre para siempre**Otros canales(La cola se vaciará.),Pero la bandeja de entrada compartida multiplataforma de un solo consumidor en serie + 100 ranuras traerá**Retrasos de minutos y rechazos 503**;②A2A/Tasker entrante**no compartir** ChannelConversationStore(ir SessionRepository/ChatViewModel),No hay conflictos de escritura simultáneos,Pero A2A tiene su propio problema de secuestro de sesión.ON_STOP detener el receptor no implementado,La fluctuación de retroceso no está implementada(Ver detalles F5).

encontrar distribución:P0 × 0,P1 × 2,P2 × 9.

## lista de descubrimiento

### F1. A2A Reutilizar ChatViewModel global: interfiere con el chat hombre-máquina y A2A concurrente(El resultado no coincide/secuestro de sesión)(P1,Credibilidad:alto)

- ubicación:`app/src/main/java/io/zer0/muse/web/a2a/A2aHandler.kt:70-107`,`app/src/main/java/io/zer0/muse/web/WebServer.kt:614-660`
- evidencia:
  ```kotlin
  // A2aHandler.sendMessage —— chatViewModel si Koin single(Único a nivel mundial,Misma instancia que la interfaz de usuario)
  chatViewModel.switchSession(requested)          // Cambiar directamente la sesión actual del usuario
  ...
  chatViewModel.updateInput(text)
  chatViewModel.send()                            // Envíe el texto al cuadro de entrada global y luego envíelo
  ...
  withTimeout(GENERATION_TIMEOUT_MS) { chatViewModel.state.first { !it.isStreaming } }
  // El juicio de finalización es"Respaldo global de isstreaming",No es una señal de finalización para esta dimensión de la tarea.
  val reply = sessionRepo.observeMessages(sessionId).first()
      .lastOrNull { it.role == ASSISTANT && it.content.isNotBlank() }?.content ?: ""
  ```
  WebServer de `/a2a` El enrutador abre una llamada de rutina independiente para cada solicitud `a2aHandler.sendMessage(params)`(WebServer.kt:659),Sin exclusión mutua, sin cola.
- influencia(Tres escenarios reproducibles):
  1. **Interferencia hombre-máquina**:Llamada A2A recibida mientras el usuario chatea en primer plano → `switchSession` Cortar la sesión actual global,La interfaz de usuario salta a otra sesión;A2A El texto también pasa por el global. `updateInput` Escribir en estado de entrada compartido.
  2. **Discrepancia A2A simultánea**:Dos mensajes A2A SendMessage llegan simultáneamente,el segundo `switchSession/createNewSession/send` intercalado con el primero;ambos `state.first { !it.isStreaming }` Estará allí**cualquier momento**está satisfecho al final de la generación,La primera llamada puede leer mal(o vacío)assistant noticias,pero con `COMPLETED` Devolver.
  3. **Error de juicio en el streaming**:A2A entra mientras el usuario transmite la compilación,`streamingBefore=true` saltar"Espera para empezar"Sólo espera"fin",Si A2A `send()` En realidad no es efectivo,El último mensaje de la ronda del usuario se utilizará como respuesta a esta tarea.
  Además `cancelTask(taskId)` Sin verificación de dimensión de tarea,Cualquier ID de tarea `chatViewModel.stop()` Detener lo que se está ejecutando actualmente(Puede ser propio del usuario.)generar(A2aHandler.kt:131-139).
- Sugerencias:A2A Tome el camino de generación independiente de UI sin interfaz de usuario(Isomórfico a ChatService ronda única de TaskerTriggerReceiver,O agregue exclusión mutua de dimensión de sesión para completar la suscripción A2A + mediante sessionId),`cancelTask` Cancelar solo tareas correspondientes a la sesión;Al menos agrégalo primero"A2A Mutex serial + rechazar cuando hay generación de UI activa/cola".

### F2. existe bucle receptor"muerte permanente"camino,Y no hay reinicio en el proceso.(P1,Credibilidad:alto)

- ubicación:
  - `app/src/main/java/io/zer0/muse/channel/TelegramReceiver.kt:53-59, 93-98`(Salir con error de lanzamiento de loadOffset)
  - `app/src/main/java/io/zer0/muse/channel/ChannelStore.kt:22-30, 47-55`(load falló/Falló el descifrado → emptyList/vacío secret)
  - `app/src/main/java/io/zer0/muse/MuseApp.kt:153, 516-528`(appScope llevar GlobalCoroutineExceptionHandler;restart Sólo una vez al inicio)
  - `app/src/main/java/io/zer0/muse/ui/settings/ChannelSettingsPage.kt:85-91`(el único otro lugar restart)
- evidencia:
  ```kotlin
  // TelegramReceiver.pollLoop —— Si no puede obtener la configuración habilitada, entonces return,salida de bucle
  if (config == null) { Logger.i(TAG, "No hay canales activos de Telegram,recibir salida del bucle"); return }
  // cursor.channelId != config.id tiempo:
  cursor = TelegramCursor(config.id, loadOffset(config.id))
  private fun loadOffset(channelId: String): Long? {
      ...
      return target.readText().trim().toLongOrNull()
          ?: error("Telegram offset Formato de archivo no válido")     // Tira fuera de cualquier intento. → todo el trabajo muere
  }
  ```
  Cinco receptores son completamente isomorfos.:`connectLoop/pollLoop` en `config == null → return`.`config` de `channelManager.refresh()` → `ChannelStore.load()`,último `runCatching { ... decrypt(it) }` cualquier falla(La lectura del archivo falló,`SecureKeyStore.decryptOrNull` Regresar null → decrypt revertir "")todos convergen a `emptyList()`.job por `appScope`(SupervisorJob + GlobalCoroutineExceptionHandler)oso,**Las excepciones no detectadas sólo se registran,job Fin, nadie reinicia**;QQ/Feishu/DingTalk/WeClaw Aunque el cuerpo del bucle tiene un problema con una única falla de conexión, catch,pero `connectOnce` adentro `loadBuffer→restoreWeClawCursor→error("No se puede descifrar el cursor de sincronización de WeClaw")`(WeClawReceiver.kt:32-35, 125-129)Espere a que el punto de error vuelva a aparecer cada vez que vuelva a intentarlo.
- influencia:
  1. Telegram offset Una vez que aparece contenido no digital en un archivo(escritura de excepción/destrucción provocada por el hombre),recibir bucle**Muere cada vez que comienza.**,Y nunca se cura por sí solo.
  2. Keystore Falla tipo falla(KeyStore no está disponible después de borrar las credenciales de la pantalla de bloqueo en algunos modelos)dejará `decrypt` Cadena vacía de respaldo silencioso → Todas las plataformas `appSecret.isBlank()` → **Los cinco bucles salen silenciosamente.**,El canal está completamente desconectado.;A menos que el usuario ingrese manualmente a la página de configuración y la guarde una vez(Sin embargo, se produce la misma salida cuando el descifrado sigue fallando.)O matar el proceso y reiniciarlo.(Mismo resultado).
  3. chatear con grupo/Diferentes tareas programadas,receptor de canal**No hay WorkManager para realizar un seguimiento**,Tampoco está bien `config==null` reintentar/Retrocede y espera——Después de que la ROM elimine el proceso,La disponibilidad del canal solo depende del efecto secundario Application.onCreate la próxima vez que WorkManager inicie el proceso.
- Sugerencias:`config == null` no lo hagas return,Cambiar a retroceso de baja frecuencia(Como por ejemplo 30–60s)Continuar encuestando,hacer"Lista vacía temporal/La clave no está disponible temporalmente"Puede autocurarse;`loadOffset` Los errores de formato se aíslan+Restablecer cursor(Es mejor perder el cursor una vez que tener un bucle infinito);job Los reinicios son supervisados ​​por los padres en caso de muerte.

### F3. QQ WebSocket No hay recuperación de REANUDAR:Los eventos dentro de la ventana de desconexión se pierden silenciosamente(P2,Credibilidad:en)

- ubicación:`app/src/main/java/io/zer0/muse/channel/QqReceiver.kt:99-112, 145-171`
- evidencia:`connectOnce` Después de construir WS básico, solo implemente op=10(Hello)/11(latido del corazón ACK)/0(dispatch)/7(Reconectar)/9(Sesión no válida);`lastSeq` recogido(`obj["s"]... lastSeq.set(seq)`,:168-170)pero**nunca usado para op=6 RESUME**;Después de la desconexión `connectLoop` Nuevo después de arreglar 5s Identify(QqReceiver.kt:79-83). Contraste:Telegram tener perseverancia offset(TelegramReceiver.kt:80-83),WeClaw Persistencia del buffer cifrado(WeClawReceiver.kt:96, 115).
- influencia:Cambio de red celular/Mensajes entregados dentro de la ventana de desconexión, como patadas del lado del servidor.,No será reproducido por la puerta de enlace QQ en una nueva sesión sin REANUDAR(semántica de plataforma),Los mensajes se pierden silenciosamente.;Las otras cuatro plataformas pueden confiar en el cursor bajo la misma falla./Reinversión del lado del servidor + deduplicación y recuperación de la bandeja de entrada.
- Sugerencias:darse cuenta op=6 RESUME(lastSeq ya en la mano,Falta carga útil y session_id para guardar),O al menos en la documentación./línea de base"QQ Eventos intermitentes"Marcados como límites conocidos.

### F4. Si falla la recuperación del almacenamiento del canal tres, se borrará silenciosamente.,Sin respaldo de cuarentena(Violar las prácticas de cuarentena propias del proyecto.)(P2,Credibilidad:alto)

- ubicación:`app/src/main/java/io/zer0/muse/channel/ChannelInbox.kt:168-196`,`app/src/main/java/io/zer0/muse/channel/ChannelConversationStore.kt:70-84`,`app/src/main/java/io/zer0/muse/channel/WebhookEventDeduplicator.kt:45-50`
- evidencia:
  ```kotlin
  // ChannelInboxJournal.attach —— Solo registros de fallas de decodificación,Continuar con la lista vacía;file conjunto,Los registros posteriores sobrescribirán el archivo defectuoso con contenido nuevo.
  }.onFailure { error -> Logger.w(TAG, "Falló la recuperación de la bandeja de entrada: ${error.message}") }
  // ChannelConversationStore.attach Mismo estilo
  .onFailure { e -> Logger.w(TAG, "Error al recuperar el historial de conversaciones: ${e.message}") }
  // WebhookEventDeduplicator.loadEntries —— Falló la decodificación/Super capacidad directa throw → attach Error al marcar
  require(loaded.size <= MAX_ENTRIES) { "Webhook event ledger exceeds its capacity" }
  ```
  Ya en el proyecto `AtomicFileStore.quarantine()`(AtomicFileStore.kt:47-65)Copia de seguridad corrupta con DataStore(MuseSettingsDataStore)precedente,Estos tres lugares no se utilizan.
- influencia:JSON realmente dañado(escritura sin lágrimas,Como errores a nivel del sistema de archivos./schema deriva irreversible)tiempo:①inbox Cola PENDIENTE con conversaciones, contexto de conversación**pérdida silenciosa**,La primera vez que se coloca un nuevo registro en el disco, se sobrescribirá el archivo defectuoso.;②Después de que el libro mayor de deduplicación no esté disponible,**Todos los registros entrantes con ID de eventos arrojan errores permanentemente**(ChannelInbox.kt:418-431 `error("Webhook event deduplication is unavailable...")`),webhook Heng 503, cuatro largas encuestas/Bucle de conexión largo 5 s, un reintento permanente,Solo se puede restaurar borrando datos o reinstalando——fail-closed dirección correcta,Pero falta una salida autorreparable.
- Sugerencias:attach Cuando falla la decodificación `AtomicFileStore.quarantine()` Inicio garantizado y luego vacío.;Cuando falla la construcción del libro mayor de deduplicación, los archivos antiguos también se aíslan y se reconstruye el libro mayor vacío.(Dejar cerrado por error para anular únicamente"Aún no está disponible después de un intento fallido de recuperación"escena).

### F5. Verificar resultados:ON_STOP El receptor no se detiene, no hay fluctuaciones durante el retroceso/Sin índice(DEDUPE Ambas cuentas antiguas son"aún no ha aterrizado")(P2,Credibilidad:alto)

- ubicación:`app/src/main/java/io/zer0/muse/MuseApp.kt:354-404`(ON_STOP Los observadores sólo manejan ChatViewModel/memoryTicker/hooks,sin tocar cinco receiver);`grep` Almacén lleno sin ningún `receiver.stop()` llamada de producción(solo los tipos mismos `fun stop()` nadie llama).
  retirarse del status quo:`ChannelInboxJournal.retryDelayMillis`(ChannelInbox.kt:476-479)El retroceso exponencial tiene un límite de 5 minutos, pero**Sin nerviosismo**;Cinco receptores se vuelven a conectar/Las encuestas largas siempre fallan**Fijo 5s constante**Y no hay límite superior para el incremento.——`TelegramReceiver.kt:109`,`WeClawReceiver.kt:261`,`QqReceiver.kt:251`,`FeishuReceiver.kt:281`,`DingtalkReceiver.kt:190`.
- influencia:①Desconexión prolongada/Tiempo de inactividad de la plataforma,Cinco ciclos para golpear la plataforma con una frecuencia constante de 5 segundos.(Riesgo de control de riesgos de potencia + plataforma);②Múltiples dispositivos/Varias instancias que se reconectan al mismo tiempo tienen un efecto de rebaño atronador.(El proyecto ya existe `retryOnNetworkError` de 0–30% Sacudir el precedente,NetworkRetry.kt:46,No reutilizado);③ON_STOP No gestionar el receptor en sí es"Necesidades del canal 7×24 permanente"orientación razonable,pero con"Sin servicio de recepción, sin WorkManager"Después de la superposición,La tasa de supervivencia del canal trasero depende completamente de la ROM.
- Sugerencias:La reconexión del receptor se cambia a retroceso exponencial + fluctuación(Reutilizar NetworkRetry);Reintento de envío para compensar la inquietud;El canal de evaluación es un servicio front-end permanente o al menos conectado a WorkManager para activar la detección.

### F6. Entrega en serie para un solo consumidor + bandeja de entrada compartida multiplataforma de 100 ranuras:Los eventos lentos en un solo canal ralentizan toda la plataforma,Los brotes en un solo canal pueden causar 503/rechazo en otras plataformas(P2,Credibilidad:alto;Conclusión=Estado de diseño probado + radio de explosión multiplataforma)

- ubicación:`app/src/main/java/io/zer0/muse/channel/ChannelAutoReply.kt:126-168, 380-408`,`app/src/main/java/io/zer0/muse/channel/ChannelInbox.kt:222, 268-280, 442-453`
- evidencia:dispatcher si**consumidor único global** while bucle,`drainPending` Procese los elementos uno por uno hasta que la cola esté vacía;El peor camino de un solo evento. = 5 rueda LLM×60s(`LLM_TIMEOUT_MS=60_000`,`MAX_TOOL_ROUNDS=5`,ChannelAutoReply.kt:816, 842)+ Sin tiempo de espera de capa exterior para llamadas a herramientas(`toolRegistry.executeFromJson` melodía recta,:598)+ Enviar reintento;`nextRetryDelayMillis` Siempre regresa cuando hay un elemento PROCESANDO 1s(ChannelInbox.kt:367-369),Es decir, durante el procesamiento lento, el bucle está inactivo con un latido de 1 segundo pero**No paralelo**. Tamaño de la bandeja de entrada `MAX_PENDING_ITEMS=100` para**Compartido en todas las plataformas**,por encima del límite `throw ChannelInboxCapacityException()`(webhook → 503 Reintento de plataforma;encuesta larga → 5s Inténtalo de nuevo). Además `claimNextPending` tomar `indexOfFirst`,y `retain` poner nuevo evento**Salta al frente de la fila**(ChannelInbox.kt:223),Cuando se acumulan, los mensajes nuevos se procesan antes que los mensajes antiguos.(LIFO).
- influencia(responder preguntas centrales):Fallo de un solo canal**No morirá de hambre para siempre**Otros canales(BLOCKED/El retroceso garantiza el vaciado de la cola,Los mensajes de otros canales eventualmente se procesan),pero:①Evento lento único(El modelo es lento./tarjeta de herramientas)Permitido**Todas las plataformas**Las respuestas se retrasan varios minutos.;②Una sola ráfaga de chat grupal activo puede llenar la cola compartida,Hacer que el webhook en otras plataformas reciba rechazos repetidos 503 y encuestas largas;③En el escenario de acumulación, el orden LIFO hace que los primeros usuarios esperen más.
- Sugerencias:Declarar límites que deben preservarse,Pero se recomienda:Ejecución de herramienta más tiempo de espera de capa exterior;por plataforma/Rotación simple de canales.(Recoge objetos de diferentes plataformas en cada ronda.)O simultaneidad de depósitos por canal;La capacidad es por cuota de plataforma.

### F7. El descifrado del token de contexto de devolución de datos falla y en su lugar se realiza un reintento infinito BLOCKED,Inconsistente con el manejo de fallas del punto de control(P2,Credibilidad:alto)

- ubicación:`app/src/main/java/io/zer0/muse/channel/ChannelAutoReply.kt:323-338` vs `:342-348, 416-418`
- evidencia:
  ```kotlin
  val restored = when (inbound.platform) { ... restoreWeClawReplyContextToken(encrypted) ... }
  return restored?.let { ContextTokenOverride.Value(it) } ?: ContextTokenOverride.Unavailable
  // Unavailable → HandleResult.Retry → retryDispatch → attemptCount Sin límite superior,Limitado a 5 minutos,nunca termina
  ```
  Cuando el punto de control del Agente es ilegible, se transfiere explícitamente a `BLOCKED(CHECKPOINT_UNREADABLE)` estado final(ChannelAutoReply.kt:45-48, 347).
- influencia:Keystore Después del vencimiento,WeClaw/Eventos entrantes de DingTalk con tokens a intervalos de 5 minutos**reintentos infinitos**(El descifrado falla cada vez),Ocupar los registros de ritmo y actualización del despachador compartido;con"No se puede restaurar automáticamente a la revisión manual"son incompatibles con los principios existentes.
- Sugerencias:Falló el descifrado del token(No en blanco pero excepción de descifrado)clasificado como estado final BLOCKED(con código de error),Mismo calibre que el puesto de control.

### F8. El webhook entrante de Feishu no se analiza content JSON,El modelo recibe la cadena JSON sin formato.;Comportamiento inconsistente con rutas de conexión largas(P2,Credibilidad:alto)

- ubicación:`app/src/main/java/io/zer0/muse/web/WebhookDispatch.kt:70` vs `app/src/main/java/io/zer0/muse/channel/FeishuReceiver.kt:207-223`
- evidencia:
  ```kotlin
  // webhook camino:text = message?.get("content")?.jsonPrimitive?.contentOrNull   ← content En sí misma es una cadena JSON
  // largo camino de conexión:parseTextContent(...) analizar {"text": "..."} y quitar el marcador de posición @
  ```
- influencia:Habilitar el canal webhook Feishu,respuesta automática"Mensajes de usuario"si `{"text":"Hola"}` tan original JSON(La imagen es `{"image_key":"..."}`),No solo contamina el contexto sino que también hace que el modelo lea JSON como texto.;Los comportamientos de dos rutas de entrada para la misma plataforma FEISHU divergen.
- Sugerencias:webhook Reutilización de caminos `parseTextContent` Y mapeo de marcador de posición de tipo de mensaje.

### F9. Después de que el envío falla y se vuelve a intentar, la biblioteca de conversaciones conserva la primera respuesta generada.,El usuario recibió la segunda respuesta.(deriva del contexto)(P2,Credibilidad:alto)

- ubicación:`app/src/main/java/io/zer0/muse/channel/ChannelAutoReply.kt:263-302` + `ChannelConversationStore.kt:114-136`
- evidencia:`sendPreparedReply` **primero** `appendTurn(assistant, reply, dispatchId)` Enviar de nuevo;Envío fallido → Retry → próxima ronda `prepareReply` porque `preparedReply` no persistido**Vuelva a ejecutar el modelo para generar texto nuevo.** `reply'`;segunda vez `appendTurn(assistant, reply', mismo dispatchId)` ser `channelDispatchTurnIdentity in recentDispatchTurnIds` Eliminar duplicados**saltar**(ChannelConversationStore.kt:118-119).
- influencia:Después de un reintento exitoso,El contexto de la conversación es reply(primera vez),Lo que recibe el usuario en su teléfono celular es reply'(segunda vez);El modelo de rondas posteriores continúa la conversación basándose en texto no visto por el usuario.,Las desviaciones se acumulan después de varias rondas. Las rondas de usuario tienen el mismo Id. de despacho para eliminar duplicados, de modo que no haya duplicaciones.(Correcto),Sólo la rueda auxiliar está desalineada.
- Sugerencias:Cuando el envío falla, la respuesta generada también persiste como preparedReply(Revertir la ronda del asistente o soltar la respuesta preparada antes de ingresar a la ronda.),garantia"Lo que ves es lo que hay".

### F10. Tasker Notificación de resultados codificada policy="always",Ignorar la política de notificación global del usuario(P2,Credibilidad:alto)

- ubicación:`app/src/main/java/io/zer0/muse/intent/TaskerTriggerReceiver.kt:103-110`,`app/src/main/java/io/zer0/muse/notification/MuseNotificationManager.kt:194-210`
- evidencia:`notifyChatCompletedWithPolicy(policy = "always", ...)`;El enlace principal configura al usuario.(`never/when_unfocused/always`)Pase la misma API.
- influencia:Incluso si el usuario elige"nunca notificar",Cada vez que se activa Tasker estará en `chat_completed` canal(IMPORTANCE_LOW)Notificación emergente.
- Sugerencias:Cambiar política de usuario,O proporcionar interruptores independientes en escenarios de activación de Tasker;Tenga en cuenta también que esta notificación no tiene verificación de permiso POST_NOTIFICATIONS.(notify descartar silenciosamente,No hay comentarios visibles del usuario).

### F11. Tasker Cola entrante no persistente:El proceso muere/Congelar significa descartar solicitudes silenciosamente(P2,Credibilidad:en)

- ubicación:`app/src/main/java/io/zer0/muse/intent/TaskerTriggerReceiver.kt:50-60, 63-80`
- evidencia:`goAsync()` + autoconstruido `CoroutineScope(SupervisorJob()+IO)` Ejecute llamadas LLM por hasta 120 segundos;prompt Sólo existe en Intent extra y memoria.,Sin colocaciones. Contraste:El canal entrante tiene una cola de persistencia ChannelInbox.
- influencia:Proceso en segundo plano congelado durante una larga llamada de LLM/matar(Manténgase vivo sin servicio de recepción),Solicitar pérdida silenciosa,Tasker No habrá comentarios de falla en el lateral.(broadcast Terminado o el proceso murió).
- Sugerencias:Realizar pedidos al entrar(Cola ligera o reutilización directa del modo ChannelInbox),Recibo después de la finalización;O al menos abreviado para fallar rápido + notificación"El gatillo falló".

### adjunto:Notas de baja prioridad(No cuenta para el descubrimiento)

- `FeishuPbbp2.decode` No hay protección contra campos de longitud mal formados:`skipField` de `next + length.toInt()` Cuando la longitud es un valor muy grande y se convierte a int, se vuelve negativa.,disponible `pos` La reversión provoca oscilación en el análisis,`copyOfRange` Dependencia transfronteriza de la capa exterior `runCatching`(FeishuPbbp2.kt:98-104, 191-200). El par es el servidor Feishu y tiene una pequeña superficie de ataque.,Se recomienda complementar la verificación del límite superior de longitud.
- Telegram En el momento del reinicio, los pollLoops nuevos y antiguos pueden ejecutarse en paralelo. getUpdates ≤45s,Activa Telegram 409 5 de autocuración después de un conflicto,Aceptable.
- La desinfección del nombre del archivo multimedia ha bloqueado el recorrido de la ruta(`substringAfterLast('/')` + prefijo de marca de tiempo,WeClawReceiver.kt:200-203, 280-283);`channel_media` Conjunto de protección limpia anulación de bandeja de entrada+referencias de conversación( MuseApp.kt:301-320),Sin embargo, las referencias redondas antiguas expulsadas por la compresión de resumen caducarán después de 30 días TTL.(dentro del diseño).

## DEDUPE_CHANNEL Conclusiones clave de la verificación de la dirección.

1. **Estado actual del ciclo de vida del receptor de cinco canales**:restart solo dos lugares(MuseApp.onCreate:516-528,ChannelSettingsPage:85-91);Bucle con"Salir sin habilitar la configuración"autogestión,Sin enlace ON_STOP/ON_START, sin servicio front-end, sin cobertura de WorkManager → Ver F2/F5.
2. **ChannelInbox dispatcher Modelo de concurrencia**:Consumidor único global, serialización multiplataforma,Los eventos lentos ralentizan toda la plataforma pero se debe vaciar la cola(Sin hambre permanente);100 La capacidad de las ranuras se comparte en todas las plataformas.;claim para LIFO → Ver F6.
3. **A2A/Tasker Simultaneidad de sesión con canal entrante**:no compartir ChannelConversationStore(A2A ir ChatViewModel+SessionRepository,Tasker ir ChatService+SessionRepository),No hay escritura simultánea del mismo archivo.;Pero A2A hay un secuestro global de ChatViewModel/Discrepancia de simultaneidad → Consulte F1/F11.
4. **webhook lento body deadline**:Dos rutas de webhook(Feishu/QQ)Todos los clásicos `readLimitedRequestBody`(1MB/15s,WebhookRequestBody.kt:9-29, ChannelWebhookRoutes.kt:20-30, 67-77)——Totalmente cubierto,Bucle cerrado.
5. **Devolución de notificación**:La respuesta automática del canal no envía notificación local(Responder directamente a la plataforma,dentro del diseño);Tasker La notificación de resultados utiliza el canal IMPORTANCE_LOW pero está codificada always → F10.
6. **channel_media ciclo de vida**:Realizar el pedido filesDir/channel_media(privado), descarga de transmisión+protección de tapa+limpieza fallida(WeClawClient.kt:303-352);Limpiador 30 días TTL+512MB LRU+protección ref;La brecha es sólo en F4(La recuperación del almacenamiento falla sin aislamiento)La compactación con anotaciones expulsa las referencias.

## Comprobando elementos conocidos(Confirmar para no volver a informar)

- **at-least-once semántica saliente**(Conocido mundialmente 1):`sendPreparedReply` Después de enviar con éxito `completeDispatch`,Ventana de bloqueo enviada repetidamente——límites de diseño,No reportado.
- **ProcessWriteGate volatile Semántica**(Conocido mundialmente 2):`persist()`/`claimEvent` cheque previo a la escritura(ChannelInbox.kt:426, 458;ChannelConversationStore.kt:213)Consistente con el comportamiento de reintento de retroceso del período de recuperación 1,No reportado.
- **Cola de rechazo completo / PENDIENTE de primera colocación / PROCESANDO recuperación / Reintento BLOQUEADO sin bucle**(Línea de base confirmada):record→persist→claim orden(:222-230),`recoverInterruptedDispatches`(:250-265),`blockDispatch`(:316-328)se implementan,No reportado.
- **Telegram Sólo avance después de que todo el lote haya llegado. offset**(Línea de base confirmada):TelegramReceiver.kt:66-89 Sólo después de un registro exitoso uno por uno. `saveOffset`,No se pudo retener el cursor y volver a intentarlo,circuito cerrado;solo queda"Error al guardar el cursor+>10min+>100 eliminación histórica"La ventana de reproducción es demasiado estrecha y coincide con la bandeja de entrada.+Libro mayor duplicado doble relieve,No figura como descubrimiento.
- **WeClaw Cursor/context token Persistencia cifrada, el error de escritura no avanza**(Línea de base confirmada):WeClawReceiver.kt:96, 115-116, 21-41,Bucle cerrado.
- **QQ encuadernación original sourceEventId**(Línea de base confirmada):ChannelAutoReply.kt:277-291 + QqClient.replyContext(QqClient.kt:139-145)implementar;60 El mensaje activo de reversión de ventana de minutos es correcto,No reportado.
- **Error de puerta de recuperación cerrada/excepción de webhook 503**(Línea de base confirmada):record falló→503(ChannelWebhookRoutes.kt:44-49, 100-105), período de recuperación persist=false→check Tira un error y vuelve a intentarlo.,Bucle cerrado.
- **Protección de limpieza de medios de canal**(Línea de base confirmada):MuseApp.kt:301-320,Bucle cerrado.
- **El chat grupal channel_reply/pass/read_context no tiene nada que ver con este enlace**(GroupChatRepository escritura independiente,GroupChatRepository.kt:248-269),No reportado.
- **La seguridad ha sido arreglada.**(Conocido mundialmente 11):webhook Desactivado de forma predeterminada(ChannelModels.kt:34), devolución de llamada de cifrado Feishu 501(WebhookDispatch.kt:35-37),QQ firma/ventana de tiempo fail-closed(WebhookAuthPolicy.kt:81-103), sesión de DingTalkDesensibilización de webhook(DingtalkReceiver.kt:22-27), log desensibilización PII(Logger.kt:142-153),La inspección aleatoria cumple con los estándares,No reportado.
- **Falta la aceptación de la matriz de plataforma real**(Se conoce la línea de base):No reportado;F3 de"QQ Ningún evento perdido de REANUDAR"Credibilidad de anotación semántica abierta basada en la plataforma:en,Se recomienda verificar con un dispositivo real.

## Prioridades de corrección sugeridas

1. **F1**(A2A aislamiento de sesión)——v2.5.8 Funciones lanzadas,concurrencia/Cuando los humanos y las máquinas coexisten, inevitablemente habrá desajustes.
2. **F2**(Ruta de muerte permanente del receptor)——causa raíz de usabilidad,Pequeños cambios(config==null Espera de retroceso + tolerancia a fallas de compensación de carga).
3. **F6/F7**(Tiempo de espera del despachador y calibre BLOQUEADO)——Experiencia y consistencia bajo colas compartidas.
