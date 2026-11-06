# Enlace 6:Agent Control de dispositivos/Automatización/Tiempo de ejecución integrado — informe de revisión

## Resumen

De acuerdo con las seis direcciones clave de exploración de DEDUPE_AGENT.md, realice una excursión de un día `automation/`(AutomationManager tres pisos,execTiered,capability,appcontrol,vdisplay,agent/UiAgentRunner),`runtime/MuseRuntime`,`automation_workflow`(AutomationWorkflow + WorkflowJournal),`tools/` herramientas de equipo (device_shell,virtual_screen,virtual_screen_input,screen_*,ui_agent,execute_node_script,platform_selfcheck,root herramientas del sistema,termux_exec),A2A(`web/a2a/A2aHandler` + WebServer enrutamiento),Tasker Enlace de entrada y aprobación (ToolPermissionResolver / ChatToolApprovalCoordinator / ChatToolController / HostWebSocketGateway) para construir barandillas con el producto patchelf. Conclusión general: degradación de tres niveles,lease libro mayor,workflow punto de interrupción/Bloqueadores de efectos secundarios desconocidos, subagentes./canal/Las líneas de defensa existentes, como el filtrado ALTO de tareas programadas, son sólidas y coherentes con la línea base de deduplicación; sin embargo, **A2A Nueva entrada y situación global."Suelta esta vez"intersección de bits**,**workflow Área ciega de verificación de propiedad de arrendamiento de displayId explícito**,**Cada mensaje realiza una prueba su sin tiempo de espera mientras se mantiene el bloqueo.**,**patchelf El producto sólo tiene un acuerdo verbal y no tiene barreras de protección de CI.** Éste es un defecto sustancial recientemente descubierto en esta ronda. 10 hallazgos en total:P0 0 tiras,P1 5 tiras,P2 5 artículo.

## lista de descubrimiento

### F1. A2A La entrada remota hereda global"Aprobar todo durante este período de apertura", la herramienta del dispositivo ALTA se puede ejecutar silenciosamente sin que nadie esté presente de forma remota (P1, credibilidad: alta)

- Ubicación:
  - `app/src/main/java/io/zer0/muse/ui/chat/ChatToolApprovalCoordinator.kt:58-62`
  - `app/src/main/java/io/zer0/muse/ui/chat/ChatToolController.kt:57-71`
  - `app/src/main/java/io/zer0/muse/ui/ChatViewModel.kt:639-646`
  - `app/src/main/java/io/zer0/muse/web/a2a/A2aHandler.kt:86-101`
  - `app/src/main/java/io/zer0/muse/tools/ToolPermissionResolver.kt:145-149`
- Evidencia:
  ```kotlin
  // ChatToolApprovalCoordinator.kt:58-62
  val mode =
      if (host.stateStore.state.value.appRunAllowAllTools) {
          // "Todos están liberados para esta carrera."Omita únicamente la aprobación paso a paso; Todavía use el analizador unificado, conserve los parámetros y rechace por completo.
          SessionPermissionMode.TRUSTED
  ```
  `appRunAllowAllTools` si**Nivel de proceso global** ChatUiState campo, escrito junto a la casilla de verificación de cualquier tarjeta de aprobación (ChatToolController.kt:58-71), archivo Léame anotado"Aprobación de todas las llamadas a herramientas durante el inicio de esta aplicación (estado de memoria, no persistente)"(ChatViewModel.kt:639-646).A2A de `sendMessage` directo `chatViewModel.updateInput(text); chatViewModel.send()` Siga la cadena de generación estándar (A2aHandler.kt:87-88), reutilice el mismo ChatViewModel/misma cadena de aprobación.`resolve()` Devuelve Auto para todas las herramientas bajo CONFIANZA (ToolPermissionResolver.kt:145-149),`device_shell`/`automation_workflow`/`ui_agent`/`app_clear_data` Espere a ALTO y la herramienta se ejecutará sin tarjeta.
- Impacto: el usuario ha registrado cualquier sesión"Aprobar todo durante este período de apertura"Después de eso, la persona que llama externamente A2A (mantener JWT 24h es suficiente,`WebServer.kt:613-618` Solo autenticación JWT, verificación de token de enfoque sin canal de host) está disponible en**Nadie frente a la pantalla.**disparar continuamente tap / pm clear / ui_agent y otros efectos secundarios del dispositivo,30 La segunda garantía de tiempo de espera de aprobación no tendrá efecto en absoluto (TRUSTED La sucursal no queda Pendiente en absoluto).A2A de `sessionId` Los parámetros también permiten especificar externamente cualquier sesión existente y heredar su modo de permiso. La declaración de alcance de autorización para esta casilla de verificación ("Aprobar todas las herramientas durante esta apertura") nunca estuvo cubierto"Control remoto de dispositivos desatendidos", la intersección no declarada del alcance del bit delegado con la nueva entrada A2A.
- Sugerencias:A2A Sesiones controladas deshabilitadas durante el análisis de aprobación `appRunAllowAllTools`(por ejemplo, dar `requestToolApprovalForSession` aumentar"Fuente=remoto"Los parámetros y las fuentes remotas se degradarán al modo de juicio de sesión), o se indicará explícitamente en la interfaz de usuario de verificación que este bit también es efectivo para A2A/disparo remoto.

### F2. automation_workflow La primera acción explícita displayId omite la verificación de propiedad del arrendamiento y puede secuestrar y destruir las pantallas virtuales dedicadas de otras ejecuciones (P1, credibilidad: alta)

- Ubicación:`app/src/main/java/io/zer0/muse/automation/tools/AutomationWorkflow.kt:319-338` con `:348-366`
- Evidencia:
  ```kotlin
  val leasedDisplayId = displayLeases.displayFor(runId)
  if (step.displayId != null && leasedDisplayId != null && !displayLeases.isKnownDisplayId(runId, step.displayId)) {
      return failure(index, action, "displayId=${step.displayId} Una pantalla virtual que no pertenece al run_id actual lease")
  }
  ...
  val displayId = leasedDisplayId ?: step.displayId ?: displayManager.lastDisplayId
  ...
  if (leasedDisplayId == null && action != "virtual_close") {
      displayLeases.acquire(runId, displayId)
  }
  ```
  La verificación de propiedad se basa en `leasedDisplayId != null` Como condición previa:**El primero del nuevo runId `virtual_tap/virtual_text/virtual_close` Si se transporta directamente `displayId`(sin virtual_ensure),`leasedDisplayId == null`, se omite toda la sección de verificación**, entonces `displayLeases.acquire(runId, displayId)` Registre un displayId desconocido como arrendamiento de la ejecución actual.`virtual_close` camino(:348-366)`release()` Devuelve verdadero (no hay ningún otro displayId en la tabla de arrendamiento en proceso). owner —— Por ejemplo es `UiAgentRunner.createDedicatedDisplay` Creado**No registrado en esta tabla de arrendamiento**pantalla dedicada, ver `UiAgentRunner.kt:238-254`) directamente después `client.destroy(displayId)`.
- Impacto: escenarios de simultaneidad (Agent + Herramienta manual + disparador A2A, que es el escenario de la dirección de exploración 3), un flujo de trabajo puede inyectar información en la pantalla virtual dedicada del GUI-Agent que está siendo utilizada por otra ejecución, o incluso destruirla.——Agent Informe inmediatamente"No se puede leer la captura de pantalla virtual"O ingrese a la rama de actualización y la acción se interrumpirá silenciosamente.`displayLeases` La semántica del propietario sólo protege"Persona registrada en la misma mesa.", sin ninguna protección para pantalla externa.
- Recomendación: explícita `displayId` No importa `leasedDisplayId` Se requiere si está vacío o no. `isKnownDisplayId(runId, displayId)`;Rechace siempre el displayId desconocido (o solicite primero `virtual_ensure` La atribución la establece el servidor).

### F3. Cada mensaje se ensambla con un candado y se ejecuta sin tiempo de espera al ensamblar palabras de aviso. `su -c id` detección,root ventana emergente/El bloqueo congelará la cadena de construcción y todas las herramientas del dispositivo (P1, credibilidad: media——El código es alto y la frecuencia real de activación de la máquina depende de la política de ROM su)

- Ubicación:
  - `app/src/main/java/io/zer0/muse/automation/capability/DeviceCapabilityHook.kt:71-79`(Cada vez que después de las llamadas ComposeSystemPrompt `refreshPermissions()`)
  - `app/src/main/java/io/zer0/muse/automation/core/AutomationManager.kt:61-90`(`refreshPermissions` mantener `mutex`,`probe("root") { root.isAvailable() }`)
  - `app/src/main/java/io/zer0/muse/automation/executors/RootExecutor.kt:33-37`(`isAvailable` → `exec("id")`)
  - `app/src/main/java/io/zer0/muse/automation/executors/ShellExecutor.kt:224-235`(locales `sh/su` Camino:`ProcessBuilder(...).start()` después `readText()` + `waitFor()`,**Sin tiempo de espera**)
- Evidencia:
  ```kotlin
  // ShellExecutor.kt:224-235(RootExecutor Hereda este camino,shellPrefix=["su"])
  runCatching {
      val proc = ProcessBuilder(shellPrefix + listOf("-c", command))
          .redirectErrorStream(true)
          .start()
      val output = proc.inputStream.bufferedReader().use { it.readText() }   // Esperar proceso hijo EOF
      val exit = proc.waitFor()                                              // Sin parámetro de tiempo de espera
  ```
  con tiempo de espera `RootRequest.requestRootAccess`(`RootExecutor.kt:73-102`,30s destroyForcibly) es diferente, la detección de capacidad toma una ruta sin tiempo de espera.`refreshPermissions` mantener `AutomationManager.mutex`, mientras `DeviceCapabilityHook`(priority 80, cada ronda de ensamblaje del indicador del sistema (ejecutada una vez) lo esperará sincrónicamente.
- Impacto: el dispositivo tiene su instalado pero el administrador raíz muestra un cuadro de confirmación para la llamada (por primera vez, después de haber sido revocada, o"pregunta cada vez"configuración): el envío de chat se atasca en el ensamblaje del indicador del sistema (`detectProfile` → `refreshPermissions`) y todas las herramientas del dispositivo (readScreen/tap/execTiered Reexploración de invalidación de caché,`VirtualDisplayServerManager.ensureStarted` dentro `shell.refreshPermissions()`) están todos en cola en el mismo mutex——Un solo su hang congela toda la cadena de control del dispositivo hasta que el usuario maneja la ventana emergente. Además, aunque no esté suspendido, una vez por mensaje `su -c id` Los procesos secundarios también son conscientes de los retrasos.
- Sugerencias:root/shell Sonda de cambio de sonda con timeout (multiplexada `requestRootAccess` de waitFor(timeout)+destroyForcibly modo o almacenar en caché resultados TTL cortos), la sonda de capacidad no compite por el mismo bloqueo que la ejecución de la herramienta.
- Método de verificación: en `su` configurado como"pregunta cada vez"Envíe un mensaje al dispositivo Magisk y observe si el hilo de ensamblaje del mensaje está `su -c id` Obstruido; o una sola prueba falló `RootExecutor.isAvailable` Reemplazado con una espera mutex de validación de código auxiliar que nunca regresa.

### F4. patchelf La integridad del producto sólo se acuerda verbalmente:CI sin barreras de seguridad keepDebugSymbols/md5 (P1, credibilidad: alta)

- Ubicación:
  - `tools/runtime-pack/README.md:52-55`(md5 El cheque se escribe como un paso de verificación manual:"Verificación:stripped_native_libs/.../lib*.so con jniLibs/.../lib*.so md5 debe ser consistente")
  - `app/build.gradle.kts:248-268`(`keepDebugSymbols += listOf("**/libmuse_node.so", "**/libssl.so", ...)` Lista de verificación manual, archivo Léame anotado"Probado y reproducido SIGSEGV")
  - `.github/workflows/ci.yml` con `ci/` Directorio:grep `keepDebugSymbols|stripped_native_libs|md5|patchelf|runtime-pack` No hay coincidencia (verificado en esta ronda)
- Evidencia:README Explícitamente la verificación de consistencia md5 es"Operación local única"propiedades, construir script `build-runtime-pack.sh` descripción correspondiente (:21-29) también solo permanece en el documento;CI el carril de construcción (`./ci/run_ci_checks.ps1 -Lane debug` + `assembleRelease`) No existe ningún paso para comparar los productos antes y después de la tira AGP, y no existe ninguna verificación. `keepDebugSymbols` Lista con `tools/runtime-pack` El conjunto de archivos de la biblioteca de salida real es coherente.
- Impacto: agregar una nueva dependencia de tiempo de ejecución `.so`(Por ejemplo, al actualizar Node se generan nuevas dependencias) y se olvida sincronizar `keepDebugSymbols` cuando,AGP llvm-strip destruirá el diseño del segmento reescrito por patchelf y se ejecutará el APK de salida `execute_node_script`/MCP stdio tiempo SIGSEGV——Y esta falla solo quedará expuesta cuando se ejecute en una máquina real.CI Todo verde liberado. Esta es exactamente la base de deduplicación"strip Romperá el diseño de la sección del parche"Faltan barandillas en accidentes conocidos.
- Sugerencia: agregue un paso de CI liviano: descomprima el APK después de compilarlo y compararlo `lib/*/` La siguiente colección de bibliotecas en tiempo de ejecución es `tools/runtime-pack/FILEMAP`(y el archivo fuente jniLibs), si es inconsistente, falla; o agregue una sola afirmación de prueba `keepDebugSymbols` Anule todos los nombres de bibliotecas en el manifiesto del paquete de tiempo de ejecución.

### F5. A2aHandler Sin concurrencia/Protección de atribución: tragar silenciosamente el tiempo de espera de cambio de sesión, atribución incorrecta,CancelTask Detener e interrumpir globalmente la aprobación pendiente de la sesión en primer plano (P1, credibilidad: alta)

- Ubicación:`app/src/main/java/io/zer0/muse/web/a2a/A2aHandler.kt:59-124`,`:131-139`,`:142-146`;`app/src/main/java/io/zer0/muse/ui/chat/ChatSessionController.kt:553-556`
- Evidencia:
  ```kotlin
  // A2aHandler.kt:142-146 —— runCatching traga silenciosamente el tiempo de espera de 5 segundos para cambiar de sesión.
  private suspend fun awaitCurrentSession(sessionId: String) {
      runCatching {
          withTimeout(5_000L) { chatViewModel.state.first { it.currentSessionId == sessionId } }
      }
  }
  ```
  posteriormente `chatViewModel.state.value.currentSessionId` se utiliza como sesión de destino (:83-84)——Se enviará un mensaje cuando el interruptor falle.**Sesión anterior en primer plano**.`sendMessage` Sin exclusión mutua: cuando se realizan dos solicitudes A2A simultáneamente, la segunda `send()` ser `canStartGeneration` El guardia isStreaming descarta (ChatViewModel.kt:1055-1056), pero ambas solicitudes esperan hasta `!isStreaming` mango trasero"Último mensaje del asistente"Cuando cada respuesta regresa (:104-114), sin asociación de identificación de generación/turno.`cancelTask` directo `chatViewModel.stop()`(:133), lo que se detiene es**Generación actual global**en lugar de la tarea. Además `switchSession(requested)` activará `sessionDeps.onCancelPendingApprovals(currentSession)`(ChatSessionController.kt:553-556)——Una solicitud A2A remota puede forzar que la tarjeta de aprobación que el usuario está mirando Denied("Generation stopped").
- Impacto: múltiples llamadas A2A o"A2A + Chatea en esta máquina simultáneamente"Escenario: Conversación de cadena de respuesta/Atribución incorrecta, un comando de cancelación elimina la generación irrelevante, la solicitud remota rechaza silenciosamente la tarjeta de aprobación local pendiente. Todos son problemas de corrección funcional en lugar de anulación de seguridad (la escritura de comandos tiene JWT), pero son fallas alcanzables de alta frecuencia en el contexto de la nueva entrada en v2.5.8 A2A 1.0.
- Sugerencias:`sendMessage` Agregar exclusión mutua por controlador;`awaitCurrentSession` El error devuelve explícito FALLADO; responde con `generationId`/turnId vinculante;`cancelTask` Se detiene la sesión correspondiente a la tarea; la cancelación de aprobación del cambio de sesión solo se ejecuta cuando el cambio efectivamente ha entrado en vigor.

### F6. Tasker receptor exported=true y sin autoridad/Control de frecuencia, contradiciendo la declaración de anotación (P2, credibilidad: alta)

- Ubicación:`app/src/main/AndroidManifest.xml:260-269`;`app/src/main/java/io/zer0/muse/intent/TaskerTriggerReceiver.kt:36-49`
- Evidencia:
  ```xml
  <receiver android:name=".intent.TaskerTriggerReceiver" android:exported="true">
      <intent-filter><action android:name="io.zer0.muse.action.TASKER_TRIGGER" /></intent-filter>
  </receiver>
  ```
  comentario (TaskerTriggerReceiver.kt:37-38) dijo"Responda solo a acciones explícitas de este paquete (no exporte ninguna recepción de Intent)", pero la lista real exported=true y no declarado `android:permission`, cualquier aplicación en el dispositivo puede enviar esta acción con cualquier `prompt`.
- Impacto: Se confirma el lado bueno——el enlace va `chatService.completeText` Sin herramientas (TaskerTriggerReceiver.kt:70-80), la herramienta del dispositivo es inalcanzable. El lado malo: cualquier aplicación puede activar la finalización ilimitada de LLM (quema de cuota de API de usuario)./tarifa), a"Tasker gatillo"La sesión escribe cualquier contenido y finaliza con "always" Notificación de bomba estratégica (:103-110), sin ningún control de frecuencia. género"Deriva del documento + superficie de activación externa no autenticada".
- Sugerencia: agregue permiso personalizado a nivel de firma al receptor o cámbielo a exported=false + Tasker ir `setPackage` La transmisión explícita agregará al menos control de frecuencia a nivel de minutos y cambiará cuando algunas ROM aún sean inalcanzables; comentarios de corrección de sincronización.

### F7. MuseRuntime.ensureData Existe una condición de carrera de descompresión concurrente sin exclusión mutua; y el enlace de producción no tiene ningún punto de activación de descompresión (P2, credibilidad: alta)

- Ubicación:`app/src/main/java/io/zer0/muse/runtime/MuseRuntime.kt:85-121`;`app/src/main/java/io/zer0/muse/tools/PlatformSelfCheckTool.kt:61`;`app/src/main/java/io/zer0/muse/tools/RuntimeSelfCheckTool.kt:51`;`app/src/main/java/io/zer0/muse/MuseApp.kt:444`
- Evidencia:`ensureData` Sin Mutex/sincronizado en todo el proceso:`mark.readText()` leer la versión → señorita regla `npmDir.deleteRecursively()` + Desempaque artículo por artículo. Almacén completo grep `ensureData(` Sólo 3 (definición + dos herramientas de autoevaluación);`MuseApp` Empezar a ajustar sólo `MuseRuntime.isReady`(:444, verificación pura de existencia de archivos);`ProcessSkillEngine.eval` con `NodeScriptTool.evaluate` ni asegurar datos,MCP stdio(McpStdioCommand.kt:53-60) deletrea directamente `npm-cli.js` camino.
- Impacto:① Cuando se llaman simultáneamente dos herramientas de autoverificación (o la autoverificación es simultánea con cualquier llamador futuro), los dos subprocesos realizan operaciones en el mismo archivo de destino al mismo tiempo."Eliminar+Descomprimir+escribir", la escritura entrelazada provocará archivos dañados——Se ha escrito la siguiente ronda de marcado de versiones,`npmCli.isFile` Si es verdadero, los datos corruptos se utilizarán silenciosamente (la marca de versión se escribe después de que la descompresión sea exitosa, pero nadie verificará el contenido después de que ambos subprocesos escriban exitosamente la marca).② Scripts de los que depende npm en el dispositivo recién instalado (`require('npm')`)/npm El servidor tipo MCP no tiene un punto de activación de descompresión automática antes del primer uso:`isReady`(= nodeBinary && npmCli) es falso, el archivo de capacidad muestra que Node no está disponible hasta que el usuario lo ejecute manualmente `runtime_selfcheck`/`platform_selfcheck`.
- Sugerencias:`ensureData` Agregue Mutex y haga una afirmación de existencia de npmCli después de que se complete la descompresión; cuelgue un sureData diferido antes de que se inicie MuseApp (o antes de ejecutar_node_script/MCP stdio por primera vez).

### F8. uiautomator dump XML Suelte el almacenamiento compartido/tarjeta SD y todo el árbol de la interfaz de usuario permanecerá cuando falle la limpieza (P2, credibilidad: alta)

- Ubicación:`app/src/main/java/io/zer0/muse/automation/executors/ShellExecutor.kt:104-125`(readScreenOnDisplay),`:282-294`(dumpUiAutomator)
- Evidencia:
  ```kotlin
  val dumpPath = "/sdcard/muse_window_dump_${System.currentTimeMillis()}_$displayId.xml"
  ...
  } finally {
      exec("rm $dumpPath")   // Si fallas, sólo serás tragado, sin ninguna pista.
  }
  ```
- Impacto:dump XML Contiene el texto completo del árbol de control de la aplicación en primer plano (vista previa de notificaciones, resumen del chat, contenido del cuadro de entrada, etc.). El punto de escritura está en el directorio raíz de almacenamiento externo compartido; si `rm` Por interrupción del canal/El comando falló y no se ejecutó (finally El ejecutivo interno falló y fue tragado.Shizuku Cuando se desconecta (execDetailed devuelve -1 pero el archivo se generó en el lado del shell), las instantáneas confidenciales de la interfaz de usuario permanecen en /sdcard durante mucho tiempo y pueden ser leídas por cualquier componente con permisos de almacenamiento. con enlaces de accesibilidad"Nodo de contraseña de filtro de volcado"Comparado con el calibre de reparación (conocido mundialmente 11)shell dump es la superficie residual de bypass que evita este filtrado.
- Sugerencia: vuelva a escribir en el directorio privado de la aplicación (`context.cacheDir`,shell Cuando el lado no se puede leer directamente, se puede eliminar `/data/local/tmp` y finalmente verificar firmemente el resultado de la eliminación), o al menos registrar registros de nivel de error para fallas de rm e intentar nuevamente.

### F9. WorkflowJournal.cleanup Posición completa sin llamador:automation_workflow Los registros de puntos de interrupción se acumulan infinitamente (P2, credibilidad: alta)

- Ubicación:`app/src/main/java/io/zer0/muse/tools/WorkflowJournal.kt:253-263`(`cleanup(maxAgeMs = DEFAULT_MAX_AGE_MS)`); posición completa grep `\.cleanup\(` Solo llega a ChannelMediaCleaner y BackupService restoreStagingStore
- Evidencia:`AutomationTools` Cada llamada de flujo de trabajo comienza con `automation-${UUID}` generar nuevo runId → `filesDir/workflow_journals/<runId>.jsonl`;`MuseApp` La limpieza de inicio solo sobrescribe `cleanupOldToolOutputs`(MuseApp.kt:688) y canal_media(:315), sin tocar flow_journals. Resultados de la herramienta de pasos no leídos (incluidos los resultados de cifrado de node_script,screen resumen, etc.) se comercializan.
- Impacto: el volumen de registro del punto de interrupción crece sin límites con el uso a largo plazo;7 política de retención de días (`DEFAULT_MAX_AGE_MS`) es un código muerto. Aunque Keystore ha cifrado los resultados de node_script en los datos históricos, el texto cifrado antiguo aún amplía la exposición después de que se filtra la clave.
- Sugerencia: agregar en la secuencia de limpieza de inicio de MuseApp `workflowJournal.cleanup()`(O ejecutado en el ciclo WorkManager).

### F10. automation_workflow Una sola llamada puede mantener el bloqueo mutex global del dispositivo por hasta 100 minutos (P2, credibilidad: alta)

- Ubicación:`app/src/main/java/io/zer0/muse/tools/ToolRegistry.kt:378-391` + `:644-659`(automation_workflow ∈ DEVICE_INTERACTION_TOOL_NAMES, ejecutar todo el proceso y mantener `deviceInteractionMutex`);`app/src/main/java/io/zer0/muse/automation/tools/AutomationWorkflow.kt:230`(cada paso node_script `timeoutMs` coerce a 300_000)+ `:537-547`(20 límite superior del paso)
- Prueba: pieza única `automation_workflow` El peor camino para llamar = 20 × 300s = 100 Actas de ejecución en serie, durante `withDeviceInteractionLock` habilitar otras sesiones `screen_read`/`ui_agent`/`virtual_screen` Espere a que todos estén en cola (la serialización entre sesiones está diseñada intencionalmente, pero no hay un presupuesto total para ese momento).
- Impacto: cuando un flujo de trabajo aprobado incorpora un script de nodo largo y continuo, las herramientas del dispositivo de otras sesiones (incluidos A2A y el subagente) no estarán disponibles durante un período prolongado. La observación externa es"La herramienta se cuelga permanentemente"en lugar de fracaso.
- Sugerencia: aumente el presupuesto de tiempo total para el flujo de trabajo (como 10 minutos) o aumente la cantidad de pasos de node_script × timeout La suma se utiliza para la verificación del límite superior y cualquier exceso se rechazará durante el período de análisis.

## Comprobando elementos conocidos

Al comparar DEDUPE_AGENT.md y AUDIT_FRAMEWORK.md, los siguientes elementos históricos se verificaron y no se informaron repetidamente:

1. Degradación jerárquica del canal de tres capas/entrada exclusiva en niveles/fallo de Shizuku Cobertura de raíz (`AutomationManager.kt:122-141` La implementación actual es consistente con la línea de base)——Sin venganza.
2. DeviceCapabilityProfile única fuente de verdad + Hook(priority 80), el subagente no se inyecta, la detección falla y se degrada (`DeviceCapabilityHook.kt:48-64`)——Sin represalias; dirección de la sonda 5"Antigüedad del archivo"Conclusión: el archivo se actualiza en cada ronda de ensamblaje (`DeviceCapabilityHook.kt:71-79`), tras revocar staleness ≤ 1 rueda, el verdadero defecto es que la actualización puede bloquearse (indicada como F3).
3. AppControlCore Lista blanca de nombres de paquetes/La aplicación del sistema prohibió la desinstalación de datos./Prohibición de Muse/Coincidencia de nivel de nombre 4 (`AppControlCore.kt:120-197`)——Sin venganza.
4. El servicio de pantalla virtual se reinicia y se repara automáticamente.virtual_close idempotente,≤3 Si falla la visualización simultánea o la lectura de la pantalla semántica, no se devolverá la pantalla principal (`VirtualDisplayClient.kt:92-161`,`ServerService.java:34` MAX_ACTIVE_DISPLAYS=3,`AutomationWorkflow.kt:354-365` stale idempotente)——No informado; Los defectos de concurrencia de arrendamientos en la dirección de exploración 3 se informan con nueva evidencia F2.
5. virtual_screen_input / virtual_tap_text El constructor DeviceCommandPolicy comprueba,≤5 desplazamiento, verificación de clic, resultados desconocidos bloquean la reproducción (`VirtualDisplayTool.kt:164-197`,`VirtualDisplaySemanticActions.kt`)——Sin venganza.
6. Android 14+ takeScreenshot Acceso con degradación 13 e inferior (`AccessibilityExecutor.kt:46-64`,`MuseAccessibilityService.kt:98-114`)——Sin venganza.
7. screen_tap_text Herramienta estructuradaOutcome/tap_text/tap_id Las coordenadas/resultados de puntuación son desconocidos y está prohibida la reproducción (`AutomationManager.kt:301-390`,`UiAgentRunner.kt:258-333`)——Sin venganza.
8. automation_workflow:node_script HIGH Aprobación, resultado Cifrado del almacén de claves (`NODE_KIND_SENSITIVE_TOOL`,`WorkflowJournal.kt:141-145`), igual que run_id append, bloqueo de efectos secundarios desconocidos (`AutomationWorkflow.kt:74-87, 151-192`;Prueba única `AutomationWorkflowResumeTest` cobertura)——Sin represalias; conclusión de verificación de entrada de la dirección de exploración 2:MAX_SCRIPT_CHARS=128k,Tiempo de espera de un solo paso 1-300s,≤20 Paso, la salida se desvía a través de ToolOutputCapture, el resultado intermedio está limitado y no hay una nueva brecha de verificación (solo el problema de la cantidad total aparece como F10).
9. Distribución jniLibs de tiempo de ejecución de nodo incorporado + envoltura ejecutiva de NativeLibraryDir W^X,RUNTIME_DATA_VERSION Re-descompresión,exec Junta LD_PRELOAD,npm assets Descomprimir (`MuseRuntime.kt`)——Sin represalias;npm El nuevo error en la máquina de estado descomprimida (carrera de simultaneidad + sin punto de activación) aparece como F7.
10. MCP stdio El comando solo permite nodo/npm/npx con rutas absolutas dentro del tiempo de ejecución (`McpStdioCommand.kt:53-67`)——Sin venganza.
11. platform_selfcheck Autoprueba de nivel de canal solo lectura ESTÁNDAR (`PlatformSelfCheckTool.kt`)——Sin venganza.
12. Sin Shizuku ni límites de habilidad de Root,auto La estrategia es simple y determinista,workflow Sin rama condicional arbitraria,Node Los ejecutores totalmente capaces no están sujetos a DeviceCommandPolicy (por diseño)——Todas estas son opciones de diseño y no figuran como hallazgos.
13. Envío de canal externo al menos una vez/semántica de ProcessWriteGate/falta de aceptación de matriz de máquina real/traducción de paquetes de idiomas/inventario de detección/semántica predeterminada del catálogo de capacidades de IA——El mundo entero es conocido y está intacto.
14. canal (ChannelAutoReply) y tareas programadas (ScheduledTaskRunner.executeCallTool) filtrado a prueba de fallos para herramientas ALTAS (`ChannelToolPolicy.kt:21-23`,`ScheduledTaskRunner.kt:540-548`) y subagente filtrado ALTO (`SubagentRunner.kt:703-717`) La verificación es consistente y no se encuentra ninguna derivación.
15. HostWebSocketGateway de `tool.approval.resolve` token de enfoque + anulación de parámetro deshabilitada (`HostWebSocketGateway.kt:242-266`) se verifica que es válido y no figura como descubrimiento;A2A El espacio de entrada sin mecanismo de enfoque equivalente se fusionó en F1/F5.

## Adjunto: Revise las instrucciones de cobertura

- Método de revisión:Glob/Grep/Read La herramienta es puramente estática y no modifica ningún archivo de almacén.
- Archivo de acceso (clave):`automation/core/{AutomationManager,DeviceCommandPolicy}.kt`,`automation/capability/*.kt`,`automation/tools/{AutomationTools,AutomationWorkflow}.kt`,`automation/vdisplay/*.kt`,`automation/agent/*.kt`,`automation/appcontrol/*.kt`,`automation/executors/{ShellExecutor,RootExecutor,AccessibilityExecutor}.kt`,`accessibility/.../MuseAccessibilityService.kt`,`runtime/MuseRuntime.kt`,`tools/script/ProcessSkillEngine.kt`,`tools/{NodeScriptTool,WorkflowJournal,ToolRegistry,ToolPermissionResolver,ToolApprovalRouter,RootToolsRegistrar,PlatformSelfCheckTool,RuntimeSelfCheckTool,FindToolsTool,SubagentRunner,TermuxExecTool,ToolCategories,ToolExposurePolicy}.kt`,`tools/system/{ShizukuAuthorizer,ShellService}.kt`,`ui/chat/{ChatToolApprovalCoordinator,ChatToolController,ChatToolResumeCoordinator,ChatSessionController,ChatGenerationController,SessionDeps}.kt`,`ui/ChatViewModel.kt`(Párrafos relacionados con la aprobación),`chat/PendingToolCallStore.kt`,`web/{WebServer,HostWebSocketGateway,HostWebSocketProtocol}.kt`,`web/a2a/*.kt`,`intent/TaskerTriggerReceiver.kt`,`terminal/TermuxChannel.kt`,`schedule/ScheduledTaskRunner.kt`,`channel/{ChannelAutoReply,ChannelToolPolicy,ChannelMediaCleaner}.kt`,`virtual-display-server/src/.../ServerService.java`,`app/build.gradle.kts`,`.github/workflows/ci.yml`,`ci/*`,`tools/runtime-pack/*`,`app/src/main/AndroidManifest.xml`.
- Estado de prueba única:DeviceCommandPolicy/VirtualDisplayLeaseRegistry/AutomationWorkflow(resume+lease)/UiAgentProtocol Todos tienen cobertura de prueba única;A2aHandler,TaskerTriggerReceiver,MuseRuntime.ensureData Ninguna prueba única.
