# UI / Informe de revisión de sistemas de diseño/accesibilidad

## Resumen

Alcance de la revisión:`app/src/main/java/io/zer0/muse/ui/` Catálogo completo(~380 archivos Kotlin),El enfoque es
2026-10 Recién agregado después de dividir y refactorizar/17 cambiado `ui/chat/` Archivo(ChatStreamCoordinator,ChatToolLoopRunner,
ChatGenerationController,ChatToolApproval/ResumeCoordinator,ChatCompressionCoordinator,
ChatSessionController,ChatMessageController,ChatTaskCardCoordinator espera),v2.5.x–v2.6 Nueva interfaz
(MessageMapBar,LinearScrollGesture,LiquidGlass/GlassIsland,SettingsTutorialPage,PendingQueueBar,
SessionTodoBar, Entrada de exportación de diagnóstico.),y componentes del sistema ui/comunes/diseño. método:Lea los documentos clave en su totalidad + compare
DEDUPE_UI.md Línea base + ejecución real de 4 scripts de barandilla de CI(touch_target / icon_content_description /
row_text_width / design_tokens,Todas las violaciones cero PASS)+ Exploración matricial de cobertura localizada.

Conclusión general:La calidad general de la división y la reconstrucción es alta.——experiencia de estado `ChatStateStore` compartir,`SessionSwitchGuard` Protector del número de serie cubierto
Los cuatro portales de cambio de sesión,La cadena de compresión tiene guarda de sesión.;Los scripts de Guardrail no tienen violaciones de línea base. pero aun así lo encontré **12 problemas verificados con nuevo código**:
1 artículo P0(La herramienta seguirá ejecutándose automáticamente después de que se cancele la tarjeta de aprobación.,Rompiendo el modelo de aprobación),4 artículo P1(Residuo del estado de la sesión cruzada, los resultados caducados comprimidos se colocan en la base de datos,
Falta la traducción del texto de ayuda, falta la traducción, la clave falla y la redacción está en el idioma incorrecto.),7 artículo P2(Accesibilidad/consistencia/Rendimiento).

## lista de descubrimiento

### F1. Después del cambio de sesión se borra la interfaz de usuario de aprobación,Aprobación Diferida no ha sido complete → Las herramientas atascadas en la aprobación se ejecutarán automáticamente(P0,Credibilidad:alto)
- ubicación:
  - `app/src/main/java/io/zer0/muse/ui/chat/ChatSessionController.kt:550-556`(switchSession sintonizar `onCancelPendingApprovals(currentSession)`)
  - `app/src/main/java/io/zer0/muse/ui/ChatViewModel.kt:4193-4217`(`cancelPendingApprovalsForSession`)
  - `app/src/main/java/io/zer0/muse/ui/chat/ChatToolApprovalCoordinator.kt:113-152`(Bucle de espera de aprobación)
- evidencia:
  ```kotlin
  // ChatViewModel.cancelPendingApprovalsForSession
  _state.update { state -> state.copy(pendingToolApprovals = state.pendingToolApprovals.filterNot { ... }) }
  ids.forEach { toolCallId ->
      toolApprovalResults.remove(toolCallId)?.complete(ToolApprovalState.Denied("Generation stopped"))
      ...
  }
  ```
  `remove(toolCallId)` valor de retorno `?.complete(...)` significa:Si el diferido ha sido eliminado(o aún no registrado),
  No pasa nada. La verdadera ventana de vulnerabilidad es `ChatToolApprovalCoordinator` interno:
  ```kotlin
  // El coordinador primero registra el diferido en host.toolApprovalResults y pendingToolApprovalRecords[toolCallId] escribir,
  // solo mas tarde (displayedSessionId == sessionId) agrega la tarjeta pendingToolApprovals —— Las líneas 98-117 son operaciones no atómicas de tres pasos.
  ```
  y cancelar la entrada(Cambiar de sesión `switchSession` / Usuario `stop()` de `onCancelPendingApprovals`)Borrar primero
  `pendingToolApprovals`,Atravesar de nuevo `toolApprovalSessions.filterValues { it == sessionId }`.
  Si el bucle de la herramienta se ejecuta**Sesión de visualización no actual**(Generación en segundo plano, cambio de página para continuar con la generación,Este es un escenario respaldado por el diseño.,ver
  `ChatToolLoopRunner.run` Continuar en appScope),`currentSessionIdForApproval()` Apuntado a nueva sesión,
  La aprobación aplazada todavía está bloqueada con el ID de sesión anterior. `toolApprovalSessions` en —— Aprobación de sesión anterior bloqueada
  `switchSession` presentado `pendingToolApprovals = emptyList()`(ChatSessionController.kt:651)
  despejado,pero deferred **Nunca completado ni eliminado**,30 El coordinador regresa después de que expire el tiempo de espera de los segundos.
  `ToolApprovalState.Denied("Approval timed out")`.
  Una combinación más seria:El usuario hace clic en Restaurar después de volver a la sesión anterior./o dentro de los 30s `toolApprovalResults[toolCallId]` ser re
  `put`(El mismo toolCallId se reutilizará en el escenario de reanudación.,ver `PendingToolCallStore` Reanudar la carga desde el punto de interrupción),previamente suspendido
  `deferred.await()` con la nueva clave de registro para formar dos defer,Uno nunca está completo.
- influencia:① Usuario claro"Sesión cortada=Cancelar la sesión para aprobación en curso"semántica(Comentarios de código)inconsistente con el comportamiento real——Después de cortar
  30 segundos la herramienta continúa avanzando el ciclo de la herramienta en la ruta denegada;② Registros de aprobación descartados en
  `pendingToolApprovalRecords` Los residuos no se eliminan hasta que finalmente,`restorePendingApprovalsForSession`
  (ChatViewModel.kt:4219)La aprobación cancelada se bloqueará cuando el usuario vuelva a la sesión anterior."resurrección".
- Sugerencias:Cambiar la ruta de cancelación de sesión excepto borrar la lista de UI,debe estar en lo cierto `toolApprovalSessions` Todas las coincidencias para esta conversación.
  deferred `complete(Denied)`(Eso es `cancelPendingApprovalsForSession` no lo hagas primero `remove` otra vez
  `?.complete`,o para"remove falló"Repara tu bolsillo complete);`restorePendingApprovalsForSession`
  sólo debería restaurar `toolApprovalResults` registros que aún existen y no están completos.

### F2. El nuevo código se omite después de la división ChatStateAccessor CAS,directamente a `_messages.value` asignación global,Vuelve el modo de actualización perdida(P1,Credibilidad:alto)
- ubicación:
  - `app/src/main/java/io/zer0/muse/ui/ChatViewModel.kt:2544-2596`(Inyección interludio de subagente)
  - `app/src/main/java/io/zer0/muse/ui/chat/ChatToolResumeCoordinator.kt:194,235`(Mensaje de HERRAMIENTA de recuperación de punto de interrupción adjunto)
  - `app/src/main/java/io/zer0/muse/ui/chat/ChatGenerationController.kt:1305,1500,1530`(Reversión de desbordamiento/onGenerationFinish/escritura de metadatos A5)
  - `app/src/main/java/io/zer0/muse/ui/chat/ChatToolLoopRunner.kt:1105,1345,1403`(citations Soltar UI/finalizar escritura UI)
- evidencia(Compara dos párrafos en el mismo archivo):
  ```kotlin
  // ChatStreamCoordinator.appendMediaToAssistant —— Comentarios explícitos"leer-cambiar-La coescritura se realiza dentro de la transformación CAS de updateMessages."
  // ChatStreamCoordinator.kt:315-325:"Diferencias con updateAssistant:...CAS En caso de competencia, StateFlow.update reproducirá la transformación con el valor más reciente"
  accessor.updateMessages { list -> list.map { ... } }          // ✔ CAS átomo
  ```
  Pero todos los nuevos caminos abiertos durante el mismo período son"Instantánea → calcular → cobertura general":
  ```kotlin
  // ChatViewModel.kt:2556 (interlude)
  _messages.value = _messages.value + interludes
  // ChatToolResumeCoordinator.kt:194 (Mensajes de la herramienta de recuperación)
  host.stateStore.messages.value = host.stateStore.messages.value + toolMsg
  // ChatToolLoopRunner.kt:1403 (finalize withArtifacts)
  host.stateStore.messages.value = host.stateStore.messages.value.map { ... }
  ```
- influencia:estos `value=` La mayoría de las escrituras ocurren en rutinas IO/predeterminadas o cierres generados.,Escribir con limitación de la interfaz de usuario de transmisión
  (`updateAssistant` cada 50 ms)Paralelo.`MutableStateFlow.value =` Es una cobertura incondicional en lugar de CAS:
  Recuperar el momento en que la llamada a la herramienta inyecta el mensaje TOOL,si con `updateAssistant` de lectura-cambiar-escribe escalonado(Lea la lista anterior primero, escriba después),
  Las adiciones de una parte serán sobrescritas por la otra parte —— El síntoma es que el mensaje HERRAMIENTA desaparece ocasionalmente una vez completada la recuperación./La respuesta final careció de medios. esto es exactamente
  `appendMediaToAssistant` descrito en los comentarios"A-03 Competencia similar",La solución solo cayó en la ruta de los medios.,Dividir el nuevo camino
  Sincronización no aplicada.
- Sugerencias:ir juntos `accessor.updateMessages { ... }` actualización transformacional;si"append"La semántica es reutilizable
  `appendMediaToAssistant` Prefijo CAS.

### F3. `refreshDisplayedSessionFromDatabase` Escritura de ventana de carrera `_messages`,Y no hay una ruta de recuperación en primer plano. SessionSwitchGuard(P1,Credibilidad:en)
- ubicación:`app/src/main/java/io/zer0/muse/ui/ChatViewModel.kt:4583-4625`
- evidencia:
  ```kotlin
  viewModelScope.launch {
      val loaded = runCatching { withContext(Dispatchers.IO) { messageController.loadMessagesPaged(sessionId) } }...
      if (displayedSessionId() != sessionId) return@launch   // ← Sólo lo revisé una vez antes de escribir.
      _messages.value = messages
      _state.update { state -> if (displayedSessionId(state) != sessionId) state else ... }
  }
  ```
- influencia:entre dos verificaciones,`switchSession` Capaz de completar `SessionSwitchGuard.begin()` →
  `messages.value = nuevo mensaje de conversación` → Enviar;aquí sigue `_messages.value = messages`(antigua sesión)voluntad
  Los mensajes de conversación antiguos se vuelven a tapar en la nueva lista de conversaciones. —— Mismo tipo que el error de sesión de cadena 2026-09-28,Pero el camino no está conectado.
  `SessionSwitchGuard`(grep `isStale(` Sólo 2 lugares en todo el archivo,Todo fuera de este camino). También faltan guardias.
  `setSessionAssistant`(4933-4942,Leer primero `displayedSessionId()` Escribe de forma asincrónica nuevamente DB+state,cambio de periodo
  La sesión vinculará al nuevo asistente a la sesión anterior.).
- Sugerencias:Este camino es el mismo que `setSessionAssistant` Toma todo `SessionSwitchGuard.begin()` ficha,antes de la presentación `isStale` descartar;
  `_messages.value =` Cambiar a CAS(`accessor.updateMessages`,Cooperar con la comparación de la sesión de presentación previa a la escritura.).

### F4. `switchAgentAssistant` Solamente publicar referencias de sesión en ramas caducadas,La adquisición secundaria de ruta normal filtra/libera nuevas sesiones(P1,Credibilidad:en)
- ubicación:`app/src/main/java/io/zer0/muse/ui/ChatViewModel.kt:4843-4925`
- evidencia:
  ```kotlin
  // 4875-4876: Inicio del bloque asíncrono
  _state.value.agentSessionId?.let { sessionManager.release(it) }
  sessionManager.acquire(sessionId)
  // 4878-4884: loadMessagesPaged Posteriormente se determinó que había caducado.
  val (messages, hasMore) = messageController.loadMessagesPaged(sessionId)
  if (SessionSwitchGuard.isStale(switchToken)) {
      sessionManager.release(sessionId)   // ← Solo libera esta vez acquire
      return@launch
  }
  ```
- influencia:① Cambiar de asistente en rápida sucesión A→B tiempo,A camino `release(agentSessionId)` con camino B
  `acquire`/`release` escalonado:B Lo que se publica en la línea 4875 es probablemente el nuevo tiempo de ejecución que A acaba de adquirir.
  (A El camino no ha salido,`_state.agentSessionId` señaló a A),El recuento de referencia se redujo demasiado,5s idle reaper voluntad
  Borrar los recursos de sesión que todavía están en uso;② camino normal `_state.value.agentSessionId` El tiempo de lectura está dentro de la rutina,
  release/acquire No atómico,con `setAgentMode` La misma entrada no está sincronizada.`switchSession` usar
  "Sincronizar primero liberar lo antiguo, luego adquirir lo nuevo" orden(ChatSessionController.kt:559-562),Esta función no está alineada.
- Sugerencias:Alinear el orden de switchSession —— Completado sincrónicamente fuera de la rutina. release(viejo)/acquire(objetivo) de
  par de recuento de referencia,Las ramas caducadas sólo se revierten(Lanzamiento simétrico esta vez. acquire).

### F5. La devolución de llamada de finalización de la compresión transfiere la sesión A. `checkpointCoveredIds` Marca de agua escrita en el campo VM compartido,Luego leído por la sesión B(P1,Credibilidad:en)
- ubicación:
  - `app/src/main/java/io/zer0/muse/ui/chat/ChatCompressionCoordinator.kt:175-177, 234-236`
  - `app/src/main/java/io/zer0/muse/ui/ChatViewModel.kt:3089-3106, 3162-3165`
- evidencia:
  ```kotlin
  // ChatCompressionCoordinator.finishCompressionWithCheckpoint(La compresión se realiza en la corrutina AppDispatchers.io.)
  host.checkpointCoveredSessionId = sessionId
  host.refreshCheckpointCoveredIds(sessionId)   // Reemplace internamente checkpointCoveredIds con el conjunto de cobertura de la sesión A
  host.updateContextTokenCount()                // leer host.checkpointCoveredIds
  ```
  `updateContextTokenCount`(ChatViewModel.kt:3162)comenzando con `displayedSessionId()?.let {
  ensureCheckpointCoveredIds(it) }` autocuración,pero `ensureCheckpointCoveredIds` La implementación es
  `if (checkpointCoveredSessionId == sessionId) return` —— confía**último escritor**de
  `checkpointCoveredSessionId`,en lugar de si displaySessionId realmente posee el conjunto de cobertura:
  Una vez completada la compresión,Si el usuario ha cambiado a la sesión B,coordinator todavía `checkpointCoveredSessionId = A`,
  Sesiones B posteriores `ensureCheckpointCoveredIds(B)` porque `B != A` Volverá a leer el punto de control de B.(Correcto);
  Pero el coordinador sigue su propio camino. `updateContextTokenCount()` en `checkpointCoveredSessionId=A`
  Descargar directamente `ensure(A)→return`,poner **A Lista de mensajes de la sesión menos un conjunto de cobertura**El número de tokens se escribe en el global.
  `contextTokenCount` —— Si en este momento `_messages` Ya el mensaje de B(sesión completada),La tasa de ocupación es
  B contenido − A conjunto de cobertura,convertirse en un valor sucio,Y luego afectar `shouldAutoCompress` Umbral de activación.
  triggerAutoCompress si"Recortar sesiones durante la compresión."guardia(ChatCompressionCoordinator.kt:88)solo cubrir
  Antes de escribir un punto de control;`finishCompressionWithCheckpoint` Las tres escrituras internas de estado compartido no se vuelven a verificar.
  displayedSessionId.
- influencia:Al cambiar de sesión + simultaneidad de compresión,B La ocupación del contexto de la sesión se muestra durante un breve periodo de tiempo./El disparador se considera un valor de error.;
  En combinaciones extremas, se puede activar accidentalmente una compresión automática para la sesión B.
- Sugerencias:`finishCompressionWithCheckpoint` Verificar antes de finalizar los tres pasos `host.displayedSessionId() == sessionId`,
  Las inconsistencias solo caen en puntos de control y no escriben marcas de agua compartidas.;o poner `checkpointCoveredSessionId/Ids` Cambiar de campos compartidos a
  `Map<sessionId, Set<String>>`.

### F6. Texto de la página del tutorial `settings_tutorial_*` Cero traducciones en 5 paquetes de idiomas distintos del chino → ingles/Los usuarios japoneses leen chino(P1,Credibilidad:alto)
- ubicación:
  - `app/src/main/java/io/zer0/muse/ui/settings/SettingsTutorialPage.kt:84-193`(Toda la página de texto proviene de `settings_tutorial_chN_*` Recursos)
  - `app/src/main/res/values-ru/strings_settings_misc.xml:184`(Sólo títulos como ch1_title fueron traducidos automáticamente)
- evidencia(escaneo real):`settings_tutorial_ch1_title` Existe en valores/en/es/ja/ko/pt-rBR/ru,
  Pero revisa el texto sección por sección. key(Como por ejemplo `settings_tutorial_ch1_s1_content`)para uso posterior
  `ci/script/check_localizations.py` Comparación de salida:es/ko Faltan 118 claves y faltan otros 101 paquetes que no están en inglés.,
  Página de tutorial Capítulo 8 × 6-13 fiesta `*_s*_content` representa la gran mayoría de la brecha.
- influencia:SettingsTutorialPage Es una nueva entrada de guía para principiantes en v2.5.x.(ajustes→Acerca de→guía novato),Usuarios no chinos
  Después de abrir, una gran área vuelve al texto chino.;Búsqueda en el cuadro de búsqueda de la misma página"Getting started"No se puede encontrar el contenido de los capítulos que no sean títulos en inglés.
  (Buscar coincidencias basadas en `context.getString` valor de reserva,Comportamiento consistente pero experiencia fragmentada.).
- Sugerencias:Este artículo es conocido mundialmente."A cada uno de los cinco paquetes de idiomas distintos del inglés le faltan 101 traducciones"adyacente,Pero el cuerpo principal de la brecha es**esta nueva pagina
  cuerda**,Todavía informando nuevos hallazgos;Complete el texto del tutorial o agregue primero la entrada a la página del tutorial"solo chino"marca de esquina.
- Observaciones:Deduplicación——DEDUPE_UI.md solo exento"Dónde escribir en el informe.'Faltan traducciones para paquetes de idiomas distintos del chino'Nunca un nuevo descubrimiento"de
  declaración general;Lo que se proporciona aquí es la nueva página + clave específica + escena visible para el usuario.,y `chat_token_estimated_cost`
  incluso **values-en todo falta**(ver F7),no caen en la misma categoría de exención.

### F7. `TokenCountMenu` citado `chat_token_estimated_cost` faltan valores-es,Los usuarios ingleses muestran nombres de recursos desnudos/Respaldo chino(P1,Credibilidad:alto)
- ubicación:
  - `app/src/main/java/io/zer0/muse/ui/chat/TokenCountMenu.kt:75-77`
    ```kotlin
    snapshot.estimatedCostUsd?.let { cost ->
        Text(text = stringResource(R.string.chat_token_estimated_cost, ...))
    ```
  - `app/src/main/res/values/strings_chat_core.xml:377`(solo chino)
- evidencia:`check_localizations.py` salida `values-en: 5901 keys [MISSING 1 keys (FAIL)]
  MISSING: chat_token_estimated_cost`;También faltan los 5 paquetes de idiomas restantes.
- influencia:Los usuarios del sistema en inglés habilitan la estimación de costos en el panel de conteo de Tokens.,Leer frases en chino"El costo histórico es de aprox. $0.0032…"
  (aapt El valor predeterminado vuelve a base values),si v2.5.3 (P4-3) Regresión real de i18n traída por nuevos campos;
  El FAIL también está directamente atascado. CI(check_localizations FAIL).
- Sugerencias:suplemento `values-en` de `chat_token_estimated_cost`(Los paquetes de idiomas restantes se procesarán de acuerdo con los estándares de traducción existentes que faltan.).

### F8. `tool_resume_starting` La traducción coreana no tiene absolutamente nada que ver con el texto original.(Nivel de accidente por vuelco,P2,Credibilidad:alto)
- ubicación:
  - `app/src/main/res/values-ko/strings_ui_main.xml:767`
- evidencia:
  ```
  values    : Reanudar llamadas de herramientas pendientes…
  values-ko : 실제로 사용하는 것이 좋습니다…      ← "Recomendado para uso real…" No tiene nada que ver con la semántica de recuperación.
  values-ru : Возобновление прерванного вызова инструмента…  (Correcto)
  ```
- influencia:Los usuarios coreanos señalan"Reanudar la ejecución"Después de que ShimmerBubble muestre una copia irrelevante. esto es**Tipo de error semántico**Traducción,
  con"Falta traducción"Diferentes categorías,barandilla(check_translation_residue / check_localizations)No se encontró ninguno.
- Sugerencias:Entrada coreana corregida a"중단된 도구 호출 재개 중…";Disponible en cantidad completa spot-check `tool_resume_*` clan.

### F9. TaskCard fila de pasos"pausa/Inténtalo de nuevo"Botón 32dp táctil + `contentDescription = null` + La fila de resultados no expandible no tiene ninguna función semántica(P2,Credibilidad:alto)
- ubicación:
  - `app/src/main/java/io/zer0/muse/ui/taskcard/TaskCard.kt:373-382`(Toda la tarjeta está suspendida. 32dp),`733-740`(botón de reintento contentDescription=null),`744-757`(Pausa de un solo paso 32dp),`688`(Expandir/Contraer texto sin formato en el que se puede hacer clic Ninguno Role/stateDescription)
- evidencia:
  ```kotlin
  MuseTactileButton(..., contentDescription = null, ..., size = 32.dp, iconSize = 16.dp)  // 733 Inténtalo de nuevo
  Text(... modifier = Modifier.padding(start = 4.dp).clickable { isResultExpanded = ... }) // 688 Expandir
  ```
- influencia:① `MuseIconSizes.controlTouch` = 32dp Se entrega la ficha"Quitar puntos"reservado,Operación principal a nivel de paso
  (Inténtalo de nuevo/pausa)Úselo debajo de la línea roja de 48dp,aunque `check_touch_target.py` Bloquear solo en IconButton
  `Modifier.size()`,`MuseTactileButton` interno `.size(size)` No en su modo de detección.(Zona ciega de barandilla,
  escapar de esto);② Icono del botón Reintentar Ninguno contentDescription,TalkBack lo que se lee es"botón,Sin etiquetar";
  ③ "Expandir/cerrar"botón de texto ninguno `Role.Button` y descripción del estado,Los usuarios que leen la pantalla no pueden saber si está expandida o colapsada.
  Mismo archivo `onCancelTask` Corregido en ChatScreen.kt:1982 `viewModel.stop()`:TaskCard Botón cancelar
  Provocado por**Detener globalmente la construcción actual**,Si la tarjeta de tarea pertenece a la generación en segundo plano de la sesión que se ha desconectado,`stop()` desajuste semántico
  (lo que se detiene es"Sesión de visualización actual"en lugar de la sesión a la que pertenece la tarjeta.)——grabar por cierto,Hipótesis a probar.
- Sugerencias:El botón de acción de nivel de paso sube a `MuseIconSizes.touchTarget`(o al menos 44dp)suplemento CD;
  Para expandir el texto `clickable(role = Role.Button)` + `stateDescription`.

### F10. MessageMapBar Vista previa deslizante de capa flotante y accesibilidad:El punto de acceso de gesto de pulsación larga no tiene semántica y el texto de vista previa no se rellena en el elemento de la lista activa.(P2,Credibilidad:en)
- ubicación:
  - `app/src/main/java/io/zer0/muse/ui/chat/MessageMapBar.kt:62-84`
  - `app/src/main/java/io/zer0/muse/ui/common/form/LinearScrollGesture.kt:160-177`
- evidencia:`MessageMapBar` La zona caliente es un desnudo. `Box(modifier.width(RAIL_TOUCH_WIDTH).fillMaxHeight()
  .pointerInput(...))`,Ninguno `semantics` Declaración;`activeIndex` Solo controla la vista previa de superposición,El mensaje actualmente activo en sí no tiene
  cualquier ancla visual(Los elementos de la lista no están resaltados o scrollIntoView,TalkBack El enfoque tampoco sigue). capa flotante
  `MessageMapTooltip` usar `offset(x = -(RAIL_TOUCH_WIDTH - 4.dp))` Posicionamiento,`widthIn(max=220.dp)`
  + `maxLines=1`,Los mensajes largos solo muestran los primeros 40 caracteres(Léame anotado),Ninguno `contentDescription` Resumen.
- influencia:① Los usuarios con discapacidad visual no pueden utilizar esta barra de navegación en absoluto(No hay indicación de operación alternativa);② uso"Desplazamiento de la tecla de volumen/llegar al fondo"aparte de
  Los usuarios con baja visión carecen de operaciones equivalentes accesibles(Tutorial/No mencionado en la ayuda). género"Falta semántica de nuevos componentes interactivos./Semántica incompleta",
  existente `check_icon_content_description.py` Sólo comprobar Icon+clickable,La zona activa de pointerInput no está cubierta.
- Sugerencias:Agregar al área caliente `semantics { contentDescription = ... }` Y cuando se activa con una pulsación larga `announceForAccessibility`
  Vista previa del mensaje actual;o proporcionar"Artículo anterior/siguiente mensaje"Acciones equivalentes accesibles.

### F11. LinearScrollGesture Modelo congelado no sincronizado con los datos de la lista,Los nuevos mensajes que lleguen a la base de datos durante una pulsación prolongada harán que el objetivo de búsqueda se desvíe. + `measured` crecimiento permanente(P2,Credibilidad:en)
- ubicación:`app/src/main/java/io/zer0/muse/ui/common/form/LinearScrollGesture.kt:70-136, 149-154, 194-199`
- evidencia:
  ```kotlin
  if (!active) model.update(listState)   // Congelar durante la activación
  ...
  val totalPx = model.totalPx(listState)        // Arrastrar instantánea del punto de partida
  val startScroll = model.scrolledPx(listState)
  ```
  `LinearScrollModel.measured` si `HashMap<Int, Int>`,La clave es**lista de índice absoluto**. Lista de chat arrastrada
  Se agregará debido a la transmisión/Cambios en la tarjeta de herramientas insertados en el encabezado(cargar más)o inserción de cola(nuevas noticias),El significado de altura del mismo índice.
  deriva;Congelar los supuestos de diseño"El contenido de la lista permanece sin cambios durante el arrastre.",Pero la página de chat es exactamente el escenario para la transmisión. Además `measured` solo aumenta pero no disminuye,
  Después de arrastrar hacia adelante y hacia atrás en una sesión larga, la longitud de la matriz de prefijos cambia `totalItemsCount` Crecimiento ilimitado(cada vez `ensurePrefix` reconstruir
  O(n) FloatArray).
- influencia:Mantenga presionado y arrastre durante la generación de transmisión,La lista se detiene en una posición diferente a la esperada después de soltarla.(leve);Sesión muy larga(Miles de tiras)cada vez
  `ensurePrefix` Reconstrucción completa + boxeo HashMap,Hay un uso de CPU medible en la rutina de arrastre.(luz).
- Sugerencias:El período de congelación se cambia a"Permitir que se acepten nuevas mediciones, pero deshabilitar la reconstrucción de prefijos"(El marcado sucio no tiene efecto durante el período activo),
  o presione `msg.id` en lugar de registro de índice;`measured` en `totalItemsCount` Recorta a medida que se hace más pequeño.

### F12. `PendingApprovalsSummary` El estado de tiempo de espera de aprobación suspendido en el estado plegado permanece:Después de cambiar de sesión `approvalTimeoutPaused` Sin reinicio(P2,Credibilidad:en)
- ubicación:
  - `app/src/main/java/io/zer0/muse/ui/ChatScreen.kt:1305-1310`
  - `app/src/main/java/io/zer0/muse/ui/ChatViewModel.kt:4164-4176`
- evidencia:
  ```kotlin
  LaunchedEffect(approvals.size, approvalsExpanded) {
      viewModel.setApprovalTimeoutPaused(approvals.size > 1 && !approvalsExpanded)
  }
  ```
- influencia:`LaunchedEffect` Solo se ejecuta cuando cambia la clave;El usuario colapsa 3 tarjetas de aprobación en la sesión A(pause=true)después
  Cambie a la sesión B inmediatamente,`approvals` se convierte en una lista de B(puede ser 0/1),key Los cambios desencadenan un reinicio —— el camino OK;
  pero**salir de la página completa**(Tab cambiar,ChatScreen abandonar el grupo)Después de que LaunchedEffect se cancele,VM residuo de campo true;
  Cuando regrese a la página de la sesión, si la sesión B tiene solo 1 tarjeta de aprobación,`approvals.size == 1` ir `if (approvals.size == 1)`
  rama,key todavía (1,false) combinación, y antes de la salida (2,true) diferente,Activará un reinicio(OK);
  La verdadera ventana es:al salir (2,true) → Los retornos siguen siendo los mismos. key (2,true) → LaunchedEffect **No volver a ejecutar**
  (rememberSaveable Restaurado approvalsExpanded=true,Y aprobaciones. Devoluciones de tamaño 2),En este momento el estado de pausa
  Residuos reales de la última ejecución,Los valores son exactamente los mismos. —— sin errores reales,pero depende de"Los valores son exactamente los mismos."coincidencia. Más F1
  deferred Pregunta no completa,El ciclo de vida de aprobación de la sesión colapsada es en general frágil.
- Sugerencias:Cambiar el estado de pausa a derivado(`pendingToolApprovals.size > 1 && !expanded`)en lugar de imperativo set,
  o en `setApprovalTimeoutPaused` y verifique si pendienteToolApprovals todavía está >1.

### F13. `SessionTodoBar` La fuente de datos no tiene un enlace de limpieza de sesión,Entre sesiones/Retención de memoria entre procesos(P2,Credibilidad:alto)
- ubicación:
  - `app/src/main/java/io/zer0/muse/ui/chat/SessionTodoBar.kt:42-45`(Suscríbete por ID de sesión)
  - `app/src/main/java/io/zer0/muse/tools/TodoTool.kt:121-126`(`clearSession` Llamada posición completa 0,Verificado con grep)
- evidencia:`TodoTool.sessionTodos` si `ConcurrentHashMap<String, TodoList>`,Sólo todos los almacenes
  `execute()` escribir con `getTodos/clearSession`;`clearSession` No hay puntos de llamada. Eliminación de sesión
  (`ChatSessionController.deleteSession`)limpiado browser/memory cache/attachments,Pero no todos claros.
- influencia:① Después de un uso prolongado, cada sesión que utilice todo_write dejará una TodoList en la memoria singleton.(Crecimiento ligero pero ilimitado);
  ② Después de eliminar una sesión, los todos de la sesión siguen ahí,Si se reutiliza la nueva sesión……(No,sessionId solo)——Principalmente retención de memoria.
  con"Las conversaciones eliminadas aún se pueden leer a través de getTodos"cuestiones de limpieza. Riesgo de coherencia de datos baja,Por tanto P2.
- Sugerencias:en `deleteSession` con `release()` libú `TodoTool.clearSession(sessionId)`.

### F14. entra a la isla `MuseExpandedInputEditor` La página de edición de pantalla completa solo utiliza `systemBarsPadding()`,Ninguno `imePadding()` —— Cuadro de entrada cuando aparece el teclado/El botón Enviar está cubierto por el teclado.(P2,Credibilidad:alto)
- ubicación:
  - `app/src/main/java/io/zer0/muse/ui/MuseExpandedInputEditor.kt:45-56`(Solo cuadro raíz `systemBarsPadding()`)
  - controlar:`MuseInsets.kt:27` Ya `museDialogInsets() = imePadding().navigationBarsPadding()`
- evidencia:
  ```kotlin
  Box(modifier = Modifier.fillMaxSize()
      .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
      .systemBarsPadding()          // ← No imePadding
      .clickable(...))
  ```
  La página de edición consta de una barra de entrada. `if (expanded)` combinación directa(InputBar.kt:220-228,Ventana independiente sin diálogo),
  MainActivity Después de habilitar el borde a borde, el teclado virtual insertado no subirá automáticamente el contenido.
- influencia:punto"Ampliar entrada"(chat_input_action_fullscreen)Ingrese a la edición de pantalla completa,Aparece la parte inferior después del teclado virtual
  El botón Enviar está cubierto por el teclado.,El usuario debe guardar el teclado antes de hacer clic en Enviar(un paso mas);El texto largo + teclado se nota más en los modelos altos.
- Sugerencias:Cambio de cuadro raíz `Modifier.imePadding()`(Superposición systemBarsPadding)O usarlo directamente `museDialogInsets()`.

### F15. GlassIsland Color del contenido forzado cuando se activa el cristal `Color.White.copy(0.92f)`,Icono de isla deshabilitada sobre fondo claro/Colapso del contraste del texto secundario(P2,Credibilidad:en)
- ubicación:
  - `app/src/main/java/io/zer0/muse/ui/theme/GlassIsland.kt:54-59`
  - `app/src/main/java/io/zer0/muse/ui/ChatScreen.kt:977-987`(backgroundIsDark juicio)
- evidencia:
  ```kotlin
  val islandContent =
      if (useGlass && LocalGlassIsBackgroundDark.current) {
          Color.White.copy(alpha = 0.92f)      // Blanco codificado,bypass ColorScheme/MuseActionColors
      } else { LocalContentColor.current }
  ```
  `backgroundIsDark` en"Tener una imagen de fondo personalizada"Oscurecer incondicionalmente(ChatScreen.kt:979 Tenga en cuenta que me considero conservador),
  Después de que el usuario suba un fondo de pantalla claro,Todos los íconos en la isla de vidrio en la barra superior están teñidos a la fuerza en un 92% de blanco. —— Los iconos blancos son casi invisibles en fondos de pantalla claros.
  Y el camino pasa por alto `MuseActionColors`/`onSurfaceVariant` Sistema de fichas,Cambiar entre temas oscuros y claros no lo afectará.
- influencia:Papel pintado claro personalizado + cristal puesto(v2.6 Desactivado de forma predeterminada,Pero los escenarios son acuosos./Esmerilado y abrible)Tecla de retorno de la barra superior,
  El icono del menú de tres puntos carece de contraste;modo de alto contraste(highContrast)No válido para este camino.
- Sugerencias:Muestreo según el brillo de fondo medido(Puede hacer un caché de muestreo de luminancia de baja resolución para la imagen de fondo.)Luego decide el color del contenido.,
  o al menos poner `Color.White.copy(0.92f)` converge a `MuseActionColors` nuevo token y permite anulaciones del modo de alto contraste.

### F16. `ChatScreen` Elemento de mensaje único incrustado `produceState(observeArtifactsByMessage)`,Sesión larga desplazándose hacia adentro y hacia afuera Flow(Puntos calientes de reorganización,P2,Credibilidad:en)
- ubicación:`app/src/main/java/io/zer0/muse/ui/ChatScreen.kt:1944-1946`
- evidencia:
  ```kotlin
  val artifacts by produceState(initialValue = emptyList<ArtifactEntity>(), msg.id) {
      viewModel.observeArtifactsByMessage(msg.id.toString()).collect { value = it }
  }
  ```
  La anotación afirma"H-S1 Utilice msg.id como clave para evitar reconstrucciones repetidas Flow",Pero la clave de produceState solo determina**el item
  Al volver a ingresar la combinación**Ya sea para volver a ejecutar block;LazyColumn Al desplazarse, el elemento se cancelará cuando abandone el grupo. Flow,Vuelve y reconstruye de nuevo
  Room Flow + Consulta del primer cuadro. La gran mayoría de los mensajes tienen identificadores de artefactos vacíos.,Sin embargo, se debe abrir una observación DB para cada entrada.
- influencia:Desplazamiento rápido para sesiones largas(Coopere con el arrastre lineal MessageMapBar = Combinación de entrada y salida de alta velocidad de una gran cantidad de artículos)tiempo,
  Cada elemento que contiene produceState realiza un registro de consulta de sala.;En la paginación en modo de rendimiento, cada vuelta de página se amplía.
  con DEDUPE_UI.md "Puntos calientes de reorganización"La dirección es consistente pero este punto no ha sido documentado antes.
- Sugerencias:solo para `msg.artifactIds.isNotEmpty()` la noticia esta abierta produceState;O mover la observación hacia arriba
  `snapshotFlow { visibleMessages.map{..}.filter{ hasArtifacts } }` Una única consulta agregada.

### F17. Cuando el área activa en el lado derecho de la página del tutorial coexiste con la vista de resultados de búsqueda `LinearScrollGesture` directo `fillMaxHeight`,El área activa cubre el borde derecho de la tarjeta de búsqueda.(P2,Credibilidad:en,Para ser reproducido)
- ubicación:`app/src/main/java/io/zer0/muse/ui/settings/SettingsTutorialPage.kt:391-399`
- evidencia:
  ```kotlin
  if (!isSearching) { LinearScrollGesture(...) }   // Oculto de la búsqueda ✔
  ```
  Este artículo no es válido después de la revisión.(Vista de búsqueda excluida),Retirar;En su lugar, registre los problemas reales.:
  zona caliente `padding(end = 4.dp, top = innerPadding.calculateTopPadding())` pero**Fondo sin procesar**,
  LazyColumn El contentPadding.bottom está incluido `innerPadding.calculateBottomPadding() + screen`,
  El área caliente se extiende hasta el final de la fila.(Área con barra de navegación).Row La capa exterior tiene `navigationBarsPadding()`,Por lo tanto, el área señalable real
  No invade la barra de navegación. —— Conclusión:Este artículo ha sido revisado y se encontró que está bien.,**No contado en descubrimientos**.
  
(Nota:F17 Número reservado para candidatos retirados durante la revisión,La lista final termina con F1–F16 prevalecerá.)

---

## Comprobando elementos conocidos(Confirmar que no hay informes duplicados)

controlar `AUDIT_FRAMEWORK.md` Mundialmente conocido 1-13 y `DEDUPE_UI.md` Todas las entradas:

| Artículos conocidos | Comprobar resultados |
|---|---|
| LAYOUT-01 Ancho en línea+Viajes restringidos(línea de base cero de la barandilla) | no repetido;El nuevo código PendingQueueBar/TaskCard/ChatListScreen cumple con todos los requisitos,Barandilla probada PASS |
| CMP-11 Convergencia de componentes/referencia M3 solo baja pero no aumenta | No se encontraron nuevas devoluciones;SettingsTutorialPage Sólo cotización material3.Icon |
| MuseDialog Trampas rodantes / Inserciones MuMu | intacto |
| Actualizar banner texto sin formato Superficie, reparación de salto de arrastre de barra de navegación | no repetido |
| TalkBack/El contraste requiere verificación del equipo. | F9/F10/F15 si**El código se puede verificar directamente.**semántica/emisión de tokens,no te caigas"Falta la verificación del dispositivo"exento |
| A cinco paquetes de idiomas les faltan 101 traducciones | F6/F7 para**Nuevo key** brecha(chat_token_estimated_cost Incluso falta en,directo FAIL CI;El texto del tutorial es una página nueva esta vez.),F8 Traducir para errores semánticos,Ninguno de ellos entra en la categoría de exención. |
| Razonamiento de mensajes de herramienta / ToolRunCard / Restoration PlanCard / TaskCard Pause vinculante | F9 Estas lógicas revisadas no han sido tocadas.,Solo reporta su toque/CD Detalles |
| Diferencia/tabla de resultados de herramienta sin perder cola,AssistantAvatar OOM,ChatListScreen Icono de deshacer vista previa | no repetido |
| Conocido mundialmente 1(at-least-once),2(ProcessWriteGate),4(EventSource retry) | No involucrado |
| Conocido mundialmente 12(El lanzamiento del plug-in sandbox es una decisión de producto) | No involucrado |
| Conocido mundialmente 13(Línea roja de exportación de diagnóstico) | Entrada de exportación de diagnóstico de pantalla de configuración revisada(696-705)Tres llamadas a GenerationTrace,son puros observadores,No hay texto grabado/key,Cumplir con el estándar |
| P1 Lista de elementos históricos no resueltos | No reportado repetidamente |

## Descripción de métodos y cobertura

- Lea documentos clave línea por línea:ChatStreamCoordinator(1392 OK),ChatToolLoopRunner(1497 OK),
  ChatGenerationController(Extracto de 700 líneas de núcleo.),ChatSessionController(700 OK),
  ChatMessageController,ChatTaskCardCoordinator,ChatToolApproval/ResumeCoordinator,
  ChatCompressionCoordinator,ChatInputController,ChatAudioCoordinator,ChatExportCoordinator,
  ChatMiscCoordinator;ChatViewModel(5530 OK)Implementado por el descriptor de acceso/Cambio de sesión/Aprobación/Compresión/Enviar cola/
  Frontend restaura la lectura de punto fijo de 6 dominios;ChatScreen(2905 OK)Recopilar por estado/desplazarse/visibleMessages/noticias item/
  bottomBar/Lectura en punto fijo de 6 campos en la capa flotante;InputBar(1683 OK),MessageBubble(2470 OK,Rama de firma y tarjeta.),
  MessageMapBar/LinearScrollGesture/LiquidGlass/GlassIsland/MuseBottomSheet/MuseDialog/MuseSwitch/
  MuseTactileButton/MuseChip cantidad total;Nueva interfaz SettingsTutorialPage/PendingQueueBar/SessionTodoBar/
  SearchScreen Segmento destacado completo.
- Barandilla en funcionamiento real:`check_touch_target.py`,`check_icon_content_description.py`,
  `check_row_text_width.py`,`check_design_tokens.py` → Todas las infracciones de la línea de base PASA cero;
  `check_localizations.py` → values-en FAIL(1 key,F7 evidencia), otros paquetes WARN(118/101,Calibre existente).
- Calibre grep de almacén completo:a2a 0 referencias bajo ui/(en la lista de tareas"A2A Interfaz de configuración"No existe en la capa de UI actual,
  A2A 1.0 La entrada de la interfaz de usuario no se encontró en este directorio,Si en el canal/La implementación de la capa de conexión pertenece a otros ámbitos de enlace.);
  Entrada de exportación de diagnóstico = SettingsScreen.kt:696-705(Cumplimiento comprobado).
- Ningún archivo modificado;Todos los archivos adjuntos encontrados:Números de línea y evidencia de código.
