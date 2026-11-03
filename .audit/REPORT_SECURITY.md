# Informe de revisión del enlace de seguridad y permisos (enlace 2)

Objetos de revisión:E:\1Project\Muse\1muse(v2.5.8 línea)
Método:Glob/Grep/Read Revisión estática completa, todos los hallazgos están documentados.:Número de línea + verificación de evidencia de código; no se ha modificado ningún código fuente.
Puntos clave:v2.5.8 Nueva entrada exterior (A2A / Tasker / Gancho de complemento / Lista blanca de firmas de mercado / Exportación de diagnóstico / MCP stdio / Enlace de actualización / SecureKeyStore) si se deben introducir límites de confianza indefensos.

## Resumen

Esta ronda realizó una verificación exhaustiva de la entrada externa recién abierta de la versión 2.5.8. Conclusión general:**Cadena de confianza central (JWT autenticación,webhook Fallo de verificación cerrado, directorio de mercado/Firma de doble capa del emisor,SSRF exportar,MCP stdio restricciones de comando,SecureKeyStore, cadena de aprobación semántica CONFIABLE/ESTRICTA) se implementan sólidamente y no se ha encontrado ningún método que pueda eludir la autenticación o escalar privilegios silenciosamente. P0**. Sin embargo, las tres nuevas entradas agregadas en v2.5.8 dejan cada una un límite de confianza abierto:① Tasker receptor de transmisión exported=true Y sin control de frecuencia/Hay un límite superior en la longitud. Cualquier aplicación puede activar la finalización de un LLM pagado sin interacción (F1);② A2A La autenticación de punto final es correcta, pero GetTask/CancelTask hace un mal uso del ID JSON-RPC cuando taskId,CancelTask Independientemente del taskId, la generación actual se detiene y SendMessage omite el arbitraje de enfoque del modo Host y puede secuestrar la sesión de primer plano del usuario (F2/F3);③ El complemento Prompt_finalize Hook no está visible en la vista previa de la instalación, lo que permite que los complementos confiables obtengan silenciosamente el historial de conversaciones completo en cada ronda (F4). Además, la pila Throwable de exportación de diagnóstico no está insensibilizada (F5), la fuente de descarga del enlace de actualización no está vinculada al almacén oficial (F6),Agent Card Huella digital de versión filtrada (F7) dos lugares son de riesgo medio a bajo.

Distribución encontrada:P0 × 0,P1 × 1,P2 × 5,P3 × 2.

## lista de descubrimiento

### F1. Tasker La entrada de transmisión no tiene autenticación, control de frecuencia ni límite superior de duración. —— Cualquier aplicación puede consumir la cuota del modelo de pago sin interacción (P1, credibilidad: alta)

- Ubicación:
  - app/src/main/AndroidManifest.xml:263-269
  - app/src/main/java/io/zer0/muse/intent/TaskerTriggerReceiver.kt:42-61, 63-111, 125
- Evidencia:
  ```xml
  <receiver android:name=".intent.TaskerTriggerReceiver" android:exported="true">
      <intent-filter><action android:name="io.zer0.muse.action.TASKER_TRIGGER" /></intent-filter>
  </receiver>
  ```
  ```kotlin
  val prompt = intent.getStringExtra(EXTRA_PROMPT)?.trim().orEmpty()   // Sin límite superior de longitud
  ...
  val completion = withTimeoutOrNull(LLM_TIMEOUT_MS) {                  // Sin verificación de llamadas/control de frecuencia
      chatService.completeText(messages = messages, temperature = ..., maxTokens = assistant?.maxTokens)
  }
  ...
  policy = "always",                                                    // Política de notificación codificada always
  ```
  receiver Declaración Ninguna `android:permission`,`exported="true"`;onReceive Solo verifique que el nombre de la acción y el mensaje no estén vacíos.
- Escenario de impacto: cualquier aplicación en el dispositivo (sin ningún permiso) puede enviar mensajes en un bucle `setPackage("io.zer0.muse")` + action `io.zer0.muse.action.TASKER_TRIGGER` Transmisión explícita de , que se activa siempre `ChatService.completeText`(El usuario activa actualmente el modelo de facturación del proveedor),prompt extra Sin límite de longitud (ingrese el token para facturación), no por fuente/Control de frecuencia global, sin límite de número total y sin interruptor para cerrar esta entrada. Al mismo tiempo, cada resultado es `policy="always"` Escriba un aviso y adjunte el contenido a"Tasker gatillo"Sesión (la sesión puede ser spam).
- Verificación de límites (manifest Tenga en cuenta que la mitigación reclamada ha sido verificada):`completeText` No transmitido `tools`(Predeterminado null → `shouldSendTools` juzgar falso), transmisión externa**no puedo**Activa indirectamente la ejecución de la herramienta; La inyección rápida de palabras solo puede obtener la respuesta del modelo al texto del atacante.completion No incluye indicaciones, memorias ni herramientas del sistema y no tiene potencial de fuga de datos del usuario. Por tanto el problema converge a:**Abuso de costos no autenticado + notificación/Inyección de basura de sesión**.
- Sugerencia: agregar para el receptor `android:permission`(signature o permisos personalizados) o proporcionados en la configuración"Activador de automatización externo"cambiar (enabled Receptor de control estatal); establezca un límite superior en la duración del aviso adicional y realice un límite de frecuencia circular en el número de activadores por unidad de tiempo.

### F2. A2A tasks/get Uso incorrecto de la identificación JSON-RPC como taskId con tareas/cancelar;CancelTask Ignorar taskId siempre detendrá la generación global actual (P2, credibilidad: alta)

- Ubicación:
  - app/src/main/java/io/zer0/muse/web/WebServer.kt:662-671
  - app/src/main/java/io/zer0/muse/web/a2a/A2aHandler.kt:126-139
- Evidencia:
  ```kotlin
  "GetTask", "tasks/get" -> {
      val taskId = req.id?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }  // ← usado es JSON-RPC request id
          ?: return a2aError(idJson, ..., "missing task id")
      val task = a2aHandler.getTask(taskId) ...
  }
  "CancelTask", "tasks/cancel" -> {
      val taskId = req.id?.let { ... }.orEmpty()
      a2aResult(idJson, a2aHandler.cancelTask(taskId))     // taskId No involucrado en ninguna verificación
  }
  ```
  ```kotlin
  fun cancelTask(taskId: String): A2aTask {
      return try {
          chatViewModel.stop()                              // Ignore el taskId y detenga cualquier sesión que se esté generando actualmente
  ```
- Impacto:
  1. Defectos del protocolo:A2A En especificación `tasks/get` La identificación de la tarea está en `params.id`, pero la identificación de correlación JSON-RPC se lee aquí. La persona que llama solo establece la identificación de la solicitud en SendMessage `message.messageId` Para recuperar la tarea, el cliente A2A normal (request id El autoincremento independiente) nunca puede obtener el resultado de la tarea y GetTask concurrente no puede distinguirlo.
  2. Aspecto ultra vires:`CancelTask` a cualquier/Se ejecuta el ID de tarea vacío `chatViewModel.stop()`——Cualquier llamante A2A que tenga un JWT (o un cliente web que obtenga un JWT) puede interrumpir la generación del usuario en primer plano en cualquier momento (un límite de confianza dentro de un dispositivo de un solo usuario, pero contrario a los objetivos de aislamiento de enfoque del F-39 de /ws).
- Sugerencias:`tasks/get` leer en su lugar `params` ID de tarea en (MessageSendParams Agregue un campo taskId que acepte valores NULL o un objeto de parámetro dedicado);`CancelTask` Verifique que el taskId exista en `recentTasks` Ejecute la detención nuevamente; de ​​lo contrario, devuelva TASK_NOT_FOUND.

### F3. A2A SendMessage Omitir el arbitraje de enfoque del modo Host —— Puede cortar por la fuerza la sesión de front-end del usuario, sobrescribir el borrador del cuadro de entrada y al mismo tiempo cruzar (P2, credibilidad: alta)

- Ubicación:
  - app/src/main/java/io/zer0/muse/web/a2a/A2aHandler.kt:69-88
  - app/src/main/java/io/zer0/muse/web/HostWebSocketGateway.kt:209-241(Contraste:chat.send el control focus.holds)
- Evidencia:
  ```kotlin
  val requested = params.message.sessionId?.takeIf { it.isNotBlank() }
  if (requested != null) {
      sessionRepo.getSessionById(requested) ?: ...
      chatViewModel.switchSession(requested)          // Cortar por la fuerza la sesión que el usuario está viendo
      awaitCurrentSession(requested)
  }
  ...
  chatViewModel.updateInput(text)                     // Sobrescribir el cuadro de entrada (incluidos los borradores no enviados por el usuario)
  chatViewModel.send()
  ```
  Host El comando de modo de escritura debe `focus.holds(context.id, command.sessionId)`, mientras que el procesador A2A opera directamente el mismo ChatViewModel, sin token de enfoque, sin"El usuario está generando/Entrando"Controlar.
- Escenario de impacto: el mismo dispositivo tiene solo un JWT (contraseña/PIN emitido). Se llama a un agente A2A externo que contiene JWT cuando el usuario está escribiendo o generando `SendMessage(sessionId=...)`:① La interfaz de usuario se cambia por la fuerza a la sesión de destino;② El borrador del cuadro de entrada está cubierto por texto A2A;③ Si el usuario está generando transmisiones,`send()` porque `canStartGeneration` La verificación isStreaming se descarta silenciosamente (A2A Tiempo de espera después de esperar 300 segundos);④ Dos sesiones simultáneas de intercambio cruzado de SendMessage A2A, posiblemente enviando el texto de A a la sesión de B (`sessionId` en `send()` Anteriormente `state.currentSessionId` Decisión, existe ventana TOCTOU).
- Sugerencias:A2A SendMessage Acceda al mismo arbitraje de enfoque que /ws (devuelve un error claro cuando no se puede obtener el enfoque); o A2A toma una ruta de inyección independiente (no depende del estado de la interfaz de usuario de updateInput/send), primero verifique si se está generando actualmente.

### F4. La vista previa de la instalación del complemento no muestra la declaración de enlaces —— prompt_finalize Hook Permita que los complementos confiables obtengan silenciosamente el historial de conversaciones completo en cada ronda (P2, credibilidad: alta)

- Ubicación:
  - app/src/main/java/io/zer0/muse/data/plugin/PluginSecurityGate.kt:337-370(summarize/InstallPreview El campo no tiene ganchos)
  - app/src/main/java/io/zer0/muse/data/plugin/PluginHookBridge.kt:51-68
  - app/src/main/java/io/zer0/muse/data/plugin/PluginPromptFinalizeHook.kt:48-89
  - app/src/main/java/io/zer0/muse/hook/SkillHook.kt:135-147
  - app/src/main/java/io/zer0/muse/ui/chat/ChatStreamCoordinator.kt:1017-1034
- Evidencia:
  ```kotlin
  // InstallPreview Campos:id/name/version/author/description/entry/trust/capabilities/permissions/tools/
  // entrySha256/contentSha256/entryBytes/extraFileCount/totalCodeBytes/signatureStatus/publisherId/
  // publisherKeyFingerprint/requiresPublisherTrust —— No hooks
  ```
  ```kotlin
  if (HOOK_PROMPT_FINALIZE !in verified.manifest.hooks) return@forEach   // Mientras el manifiesto lo declare estará enganchado.
  val hook = PluginPromptFinalizeHook(pluginId, verified.entryCode, configJson)
  hookRegistry.register(hook)
  ```
  ```kotlin
  val historyJson = event.preparedHistory.joinToString(",") { msg -> ... }   // Historial preparado completo (a través de transformador/memoria)/worldbook Todos los mensajes después de la inyección y antes del envío) se serializan y se ingresan en el complemento JS
  ```
  ```kotlin
  // SkillHook.kt: PromptFinalizeEvent(preparedHistory, assistantId, sessionId, transformContext)
  ```
- Escenarios de impacto:`hooks` Ya incluido en la lista blanca de firmas (SIGNED_TOP_LEVEL_FIELDS Contiene "hooks", integridad garantizada), pero el cuadro de diálogo de confirmación de instalación**no mostrar**este campo——El usuario está en"Confía en el editor e instala"no puedo ver"Este complemento registrará Prompt_finalize y leerá el contexto completo en cada ronda.". Cada ronda de diálogo después de la conexión (incluida la inyección de memoria/libro mundial, mensajes de usuario,system prompt) ingresará completamente al complemento JS; la red de complementos ha sido lanzada (fetch/XHR conexión directa), se ha instalado un complemento confiable pero malicioso.**Sin darse cuenta, cada ronda, contexto completo.**canal de fuga, y no requiere ninguna herramienta para solicitar la aprobación (hook No siga el enlace de la herramienta).
- Verificación de límites: la declaración de capacidad solo verifica el formato y no realiza un rechazo total; es una decisión del producto v2.5.8 (sin informes repetidos); este hallazgo es para**brecha de consentimiento informado**(La vista previa no genera ganchos)+ hook El rango de datos del objeto de evento (historial completo, más allá del rango que cualquier parámetro de herramienta puede tocar) está abierto"En implementación"en lugar de dejarse llevar por sí mismo.
- Sugerencias:InstallPreview aumentar `hooks` campos y se muestran explícitamente en el cuadro de diálogo de confirmación"El complemento leerá el historial de conversaciones completo antes de enviar cada ronda.";Considere agregar un interruptor de habilitación independiente para el registro de ganchos (desactivado de forma predeterminada, confirmación doble cuando está habilitado).

### F5. La pila Throwable en debug-log.txt no se desactiva durante la exportación de diagnóstico. —— linea roja"no contiene API key"Hay un bypass (P2, credibilidad: media)

- Ubicación:
  - common/src/main/java/io/zer0/common/Logger.kt:155-189(solo desinfectar mensajes,throwable Enviar al fregadero tal cual)
  - app/src/main/java/io/zer0/muse/debug/DebugLogStore.kt:81-95, 118-136, 169-180
  - app/src/main/java/io/zer0/muse/diagnostic/DiagnosticExporter.kt:65-70
- Evidencia:
  ```kotlin
  // Logger.kt
  fun w(tag: String = DEFAULT_TAG, msg: String, t: Throwable? = null) {
      if (enabled) {
          val safeMsg = sanitizePii(msg)          // Sólo desensibilización de mensajes
          ...
          dispatchToSink(Level.WARN, tag, safeMsg, t)   // t tal cual
  ```
  ```kotlin
  // DebugLogStore.log
  throwable = throwable?.let { stackTraceToString(it) },     // El texto original de la pila se almacena en la base de datos.
  // formatLine(): return if (throwable.isNullOrEmpty()) base else "$base\n$throwable"
  ```
  DiagnosticExporter poner `DebugLogStore.exportToFile()` Ingrese el zip tal como está (consulte la declaración de la línea roja DiagnosticExporter.kt:20-28 "No incluye texto de mensaje,prompt,API key Texto original").
- Escenario de impacto: cualquier Logger.w/e con Throwable, su mensaje/pila de excepción (puede incluir la URL de solicitud,`key=`/`Bearer` Espere el fragmento de credencial——sanitizePii Los tres conjuntos de reglas regulares solo se aplican a los mensajes, no a la pila) y entrarán en el paquete de exportación de diagnóstico sin desensibilización.`MuseCrashHandler.redactSensitive` Sólo se sobrescribe el archivo de registro de fallos,DebugLogStore Los enlaces no se tratan por igual.
- Sugerencias:DebugLogStore.log Reutilice Logger.sanitizePii (o MuseCrashHandler.redactSensitive) para texto arrojable antes de almacenarlo en la biblioteca; Realice una desensibilización general de debug-log.txt antes de exportar.

### F6. El dominio de confianza de la fuente de descarga del enlace de actualización no está vinculado al almacén oficial.digest Opcional —— Cuando se altera la imagen del sitio web oficial, se puede dirigir a cualquier APK del repositorio de GitHub (P2, credibilidad: alta[ruta del código] / en[La accesibilidad real depende de que museai.ltd esté comprometido])

- Ubicación:
  - app/src/main/java/io/zer0/muse/update/UpdateChecker.kt:169-191, 242-261
- Evidencia:
  ```kotlin
  private val TRUSTED_DOWNLOAD_HOSTS = setOf("github.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")
  ...
  val validDigest = digest == null || (sha256 != null && SHA256_REGEX.matches(sha256))   // digest desaparecido → Omitir la verificación directamente (compatible con la versión anterior)
  ```
  `isTrustedDownloadUrl` Sólo verificar esquema/puerto/Sufijo de nombre de dominio/.apk, no verifica la propiedad del almacén;`isTrustedApkAsset` También permitido `sha256 == null`.
- Escenarios de impacto:`https://museai.ltd/api/latest.json` es el punto final preferido (UpdateChecker.kt:30-36, 240). Si la imagen es manipulada, el atacante puede colocar un activo APK que apunte a su propio repositorio de GitHub (también un nombre de dominio confiable) sin digest——La aplicación indica como de costumbre"nueva versión encontrada", la entrada de descarga apunta al APK del atacante. Aliviar:Android La verificación de la firma durante la instalación hará que la instalación de la superposición falle directamente; sin embargo,"Desinstalar y luego reinstalar"El camino de la ingeniería social aún está abierto y la ausencia de un resumen significa que no se puede verificar la integridad de la descarga.`htmlUrl` Ningún consumidor lo utiliza para redireccionamientos (solo HomeScreen usa una página de descarga oficial codificada), no hay problemas de redireccionamiento abierto.
- Sugerencia: limite el nombre de dominio confiable a `github.com/Zer0Qing/Muse/releases/download/**`(o verificar mediante el propietario de la versión anterior);digest Si falta, se indicará claramente en la interfaz de usuario."No se puede verificar la integridad", el editor garantiza que la nueva versión debe contener un resumen.

### F7. Agent Card Exposición de terminales públicos appVersion —— El calibre de la huella digital de la versión C-18 convergence /api/health es inconsistente (P3, credibilidad: alta)

- Ubicación:
  - app/src/main/java/io/zer0/muse/web/WebServer.kt:606-611
  - app/src/main/java/io/zer0/muse/web/a2a/A2aHandler.kt:35-50
- Evidencia:
  ```kotlin
  // WebServer.kt:607 Nota:"Agent Card: Metadatos públicos, insensibles, no se requiere autenticación (para descubrimiento de agentes externos)."
  get(io.zer0.muse.web.a2a.AGENT_CARD_PATH) { call.respond(a2aHandler.agentCard(base)) }
  // A2aHandler.agentCard: version = appVersion
  ```
  Control C-18 (WebServer.kt:432-447):allowLan Cuando /api/health converge deliberadamente versión/tiempo de actividad, el motivo es"número de versión/El tiempo de ejecución es información precisa de la huella digital y puede ser detectado por un tercero dentro de la red de área local.".Agent Card Revela la misma huella digital.
- Impacto: los analizadores de LAN pueden obtener versiones precisas de las aplicaciones sin autenticación para realizar coincidencias específicas de vulnerabilidades conocidas. Riesgo bajo pero contradictorio con las decisiones de seguridad establecidas.
- Sugerencias:Agent Card La versión de enableLan devuelve un valor aproximado (p. ej. "2.x") o converger con el mismo calibre que /api/health.

### F8. McpStdioCommand Comprobación de prefijo de ruta absoluta que falta el límite delimitador —— Inconsistente con el calibre WorkspaceManager.resolveSafe (P3, credibilidad: alta[Defecto establecido] / (En realidad no hay escalada de derechos)

- Ubicación:app/src/main/java/io/zer0/muse/mcp/McpStdioCommand.kt:62-74
- Evidencia:
  ```kotlin
  head.startsWith("/") -> {
      val allowed = runCatching {
          f.canonicalPath.startsWith(MuseRuntime.nativeLibDir(context).canonicalPath) ||
              f.canonicalPath.startsWith(MuseRuntime.runtimeDir(context).canonicalPath)
      }.getOrDefault(false)
  ```
  Ninguno `+ File.separator` Límite:`/data/user/0/io.zer0.muse/files/muse-runtime2/evil` golpeará `runtimeDir` prefijo (`muse-runtime` si `muse-runtime2` prefijo). Contraste WorkspaceManager.resolveSafe(workspace/WorkspaceManager.kt:379-398) usado `rootCanonical + File.separator` Manéjelo correctamente.
- Impacto: El rango de escape es**Dentro del propio directorio de archivos de la aplicación**cualquier archivo——Mismo uid que la aplicación, sin escalada de privilegios (App ProcessBuilder se puede utilizar para ejecutar cualquier archivo en su propio directorio), por lo que solo se utiliza como elemento de refuerzo. El lado real de alto riesgo (ejecutar /system/bin, etc.) todavía está bloqueado correctamente.
- Sugerencia: cambiar a `canonicalPath == root || canonicalPath.startsWith(root + File.separator)`, alineado con el WorkspaceManager.

## Comprobación de elementos conocidos (elementos históricos que se han verificado en esta ronda y se ha confirmado que no se informarán repetidamente)

1. **webhook Cerrado de forma predeterminada / Falta el token de verificación de Feishu Cerrado por falla / Falta la firma QQ Cerrado por falla / El webhook de cifrado Feishu claramente no es compatible**: Consulte WebhookAuthPolicy.kt:13-26,56-72,81-97 con WebhookDispatch.kt:34-40 (encrypt → NotImplemented), la implementación es consistente con la línea de base y no se encuentra regresión.
2. **TRUSTED abajo ui_* Aprobación, nodo de contraseña de filtro de volcado sin barreras**:Compruebe ToolPermissionResolver.kt:237(ui_ → HIGH) y AccessibilityPathUtils.kt:20-21(isPassword → null), establecido.
3. **La verdadera fuente del riesgo de falla de la herramienta del canal（P1 #17)**:ScheduledTaskRunner.kt:540-548 ir `ToolPermissionResolver.riskLevelFor` Fuente única de verdad y bloque ALTO;GroupChatToolPolicy.kt:48-49 El canal de ejecución directa del chat grupal también se filtra mediante el analizador ALTO;SubagentRunner.kt:708-717 Puertas compartidas de presentación y ejecución. `allowedToolNamesForSubagent()`, tres plazas tienen el mismo calibre.
4. **Se lanzó la zona de pruebas del complemento (fetch/XHR/Versión completa del puente, formato de verificación solo del nombre de la capacidad)**:JsSandbox.kt:88-127,SkillBridge.kt:66-78,PluginSecurityGate.kt:29-47. No se informa sobre la liberación en sí; esta ronda solo informa F4 después de que se realiza el lanzamiento (hooks Falta la vista previa/acontecimientos a lo largo de la historia).
5. **manifest La inclusión en la lista blanca de campos de firma es una decisión del producto**: Verifique PluginSecurityGate.kt:394-426 (SIGNED_TOP_LEVEL_FIELDS De acuerdo con el conjunto de campos PluginManifest,"hooks"/"toolCards" @EncodeDefault(NEVER) coincidencia), la implementación de la lista blanca en sí no ha encontrado ninguna forma de manipular los resultados de la verificación de firma (canonical Base64 Verificación bidireccional: 214-246,P-256 Limitaciones: 501-507, la raíz de confianza no se puede intercambiar con el mismo ID (PluginTrustStore.kt:60-75), los elementos integrados de la raíz de confianza del directorio no se pueden sobrescribir (PluginMarketSettings.kt:65-95). Género F4 recién descubierto en esta ronda"Vista previa de visibilidad", defectos de implementación sin firma.
6. **La exportación de diagnóstico no registra el texto ni la clave API,sessionId La desensibilización al hachís es una línea roja y se puede comprobar si cumple con los estándares.**:GenerationTrace.kt Todos los campos se verifican como estadísticas + los primeros 8 dígitos del ID de sesión SHA-256 (session id Es un UUID aleatorio, no se puede enumerar sin agregar sal y cumple con el estándar);**pero** debug-log.txt Se encuentra una derivación F5 en el enlace, que es un defecto de cumplimiento de la línea roja en lugar de"Registro faltante".
7. **STRICT Siguiente ALWAYS_ALLOW penetración,at-least-once,ProcessWriteGate, token de carpeta de pantalla virtual,render_data/Root Truncar**: Verifique uno por uno (ToolPermissionResolver.kt:156-176,VirtualDisplayBinderRegistry.kt:31-37 token La verificación está establecida,WorkflowJournal.kt:141-145 node_script Los resultados se cifran y se colocan en el disco, son consistentes con la declaración de referencia y no se informarán repetidamente.
8. **web Aprobación respondida inyección de parámetro (B-12)**: Revisión HostWebSocketGateway.kt:242-259 —— answered Equivalente a aprobado,argOverrides De hecho, la ruta de fusión es nula;ChatToolController.approveToolCall Los argOverrides solo están disponibles para las herramientas de lista blanca de referencia_imagen (ChatToolController.kt:86-100), no se encontró regresión.
9. **A2A /a2a JSON-RPC Autenticación de punto final**: Consulte WebServer.kt:612-618,`authenticate(AUTH_JWT_NAME)` El paquete está establecido;Agent Card Diseño de propiedad pública (la lista de problemas de huellas dactilares de la versión es F7).
10. **OAuth state/PKCE**:OAuthManager.kt:382-400 state verificación,McpClient.kt:510-536 MCP estado lateral + PKCE,SSE postEndpoint Verificación del mismo origen McpClient.kt:629-637, todos están establecidos.
11. **Faltan traducciones de paquetes de idiomas distintos del inglés/inventario de detekt/falta la aceptación del equipo**: No cubierto.

## Conclusión

v2.5.8 De las tres nuevas entradas exteriores:**A2A La autenticación es correcta pero la implementación del protocolo es defectuosa y carece de arbitraje de enfoque (F2/F3);Tasker entrada"No otorgar ejecución de herramienta"Se establece la mitigación, pero el abuso sin costo de autenticación está indefenso (F1); el umbral de confianza del complemento Hook está establecido pero falta el consentimiento informado (F4)**. Se recomienda dar prioridad a la reparación de F1 (control de frecuencia complementario/interruptor), seguido de F3/F2 (A2A Acceda al arbitraje de enfoque y corrija la semántica de identificación de la tarea),F4(La instalación de enlaces de visualización de vista previa) se puede gestionar con la próxima versión de la interfaz de usuario.
