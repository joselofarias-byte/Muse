# Muse Revisión exhaustiva del enlace completo. · informe general (2026-10-10/11)

Línea de base de revisión:HEAD `24bf247f`(v2.5.8 Una confirmación más tarde, el árbol de trabajo está limpio).
Método:10 Revisión paralela de cada subagente de enlace + autorrevisión transversal por parte del revisor principal, todo encontrado con archivos:Número de línea+evidencia de código,
controlar `.audit/AUDIT_FRAMEWORK.md` Con 9 copias de deduplicación de referencia de deduplicación de enlaces (elementos modificados históricos/Artículos conocidos/No se informan las compensaciones de diseño).
Cada subinforme está en `.audit/REPORT_*.md`; Los hallazgos clave fueron revisados ​​por el revisor jefe uno por uno en el código fuente (la conclusión de la revisión ha corregido los falsos positivos individuales).

## cantidad total

| enlace | P0 | P1 | P2/P3 | Subtotal |
|---|---|---|---|---|
| datos/copia de seguridad/Migrar | 0 | 5 | 10 | 15 |
| UI/sistema de diseño/accesibilidad | (1→P1,Revisión y cambio de sentencia.) | 6+1 | 9 | 16 |
| AI Provider/transmisión/ciclo de herramienta | 0 | 4 | 9 | 13 |
| Documentación/prueba/Salud de código abierto | 0 | 8 | 5 | 13 |
| Rendimiento/Estabilidad | 0 | 5 | 7 | 12 |
| construir/CI/publicar | 0 | 3 | 9 | 12 |
| memoria/RAG | 0 | 6 | 6 | 12 |
| canal/Fiabilidad de back-end | 0 | 2 | 9+3Observaciones | 11 |
| Agent Control de dispositivos/tiempo de ejecución | 0 | 5 | 5 | 10 |
| Seguridad y permisos | 0 | 1 | 7 | 8 |
| X corte transversal (vdserver/Puente sin barreras, autoexamen por parte del juez superior) | 0 | 2 | 1 | 3 |
| **total** | **0** | **47** | **~75** | **~125** |

## Diez reparaciones prioritarias (evidencia revisada por el revisor principal, ordenada por impacto)

1. **La carpeta de la base de conocimientos no es válida en las cuatro rutas del enlace de respaldo.**(Datos F1/F2,P1 máxima prioridad——Pérdida de datos del usuario)
   - NDJSON Falta importación `"knowledgeFolder"` rama(BackupService.kt:1015-1384 Sin caso, sin almacenamiento en búfer) y fase de limpieza `:988` borrar; eliminar
   - `buildBackup()` Leer carpetas (`:2268`) pero `Backup(...)` Pase perdido construido (`:2326-2372`)→ Se pierde la exportación JSON única + el punto de recuperación está vacío;
   - **Reversión de fallas de recuperación = deleteAll + insertar lista vacía (`:2622`/`:2663`)→ Borrar todas las carpetas con una reversión**;
   - B-34 La conciliación solo cuenta el número de líneas analizadas (`:1007-1013`) no se registra en la base de datos y se pierde silenciosamente.
2. **A2A entrada remota × Bit de lanzamiento global = Ejecute silenciosamente herramientas de dispositivos ALTAS**(Agent F1,P1 seguridad)
   - `ChatToolApprovalCoordinator.kt:58-62`: nivel de proceso `appRunAllowAllTools`(Marque cualquier tarjeta de aprobación una vez)→ TRUSTED →
     `ToolPermissionResolver.kt:147-149` Direct Auto (sin pendiente, sin rescate de los años 30);
   - A2A `/a2a` Solo JWT, sin validación de enfoque → Después de que el usuario lo marca una vez, puede silenciarse de forma remota. `device_shell`/`app_clear_data`/`ui_agent`.
3. **A2A Reutilizar el singleton global ChatViewModel**(Canal F1,AI F4,datos F4,Agent F5, rendimiento F3/F4, seguridad F2/F3 validación cruzada de seis enlaces,P1)
   - `AppChatModule.kt:16-19` ChatViewModel Es un nivel de aplicación único;`WebServer.kt:117` Inyecte la misma instancia;
   - A2A `switchSession` Secuestrar la sesión frontal del usuario,`stop()` Detener la generación de usuarios, el rechazo mutuo concurrente,`cancelTask` Sin verificación de ID de tarea;
   - Dirección de reparación (consistente para cada enlace):A2A Tome el camino de generación de UI independiente (isomorfo `TaskerTriggerReceiver` de `ChatService.completeText`
     modelo de una sola rueda) o mínimo per-handler Mutex + taskId→sessionId Mapeo + arbitraje de enfoque.
4. **lanes Control de acceso en estado de envío HEAD medido en rojo**(CI F1/F2,P1 Fallo del proceso)
   - hardcoded_cjk:S1-S5 La exportación dividida y de diagnóstico dejó 9 nuevas incorporaciones fuera de la línea de base (ChatToolLoopRunner +6 / ChatToolResumeCoordinator +2 /
     SettingsScreen 69>68), la línea de fondo finaliza en el 29-09;exit=1 medición real;
   - strict locale:values-en desaparecido `chat_token_estimated_cost`(Revisión del revisor grep 0 visitas), corrección de una línea.
5. **Las credenciales del almacén del sitio web oficial se han ingresado en el historial de git.**(Documento F10a,P1 seguridad y salud)
   - `museai.ltd\DEPLOY.md:3-4` Seguido por git (commit `839dfb3`), incluyendo `root@117.72.212.102` con la ruta local a la clave privada SSH;
   - El almacén actualmente no tiene un extremo remoto (no hay fugas reales), pero cualquier extremo remoto agregado por error en el futuro se filtrará; se recomienda `git rm --cached` + `.gitignore` + Rotación del lado del servidor.
6. **Receptor de cinco plataformas"Muerte permanente sin autocuración"**(Canal F2,P1)
   - `TelegramReceiver.kt:58` loadOffset Lanzar un error fuera del intento significa que el trabajo finaliza; cinco receptores tienen el mismo modo `config == null → return`;
   - `ChannelStore.load():27-29` Lista vacía silenciosa en caso de fallo → Todos los bucles salen silenciosamente, sin que WorkManager quite las envolturas.
7. **Tasker Transmitir sin autenticación/control de frecuencia/Longitud máxima**(Seguridad F1,P1)
   - `AndroidManifest.xml:263-269` exported=true Sin permiso (revisión principal); cualquier ciclo de solicitud activa la facturación LLM;
   - Se establece la mitigación (completeText Sin herramientas el equipo no se puede operar indirectamente); actuación superpuesta F2:goAsync 10s La ventana ejecutó 120 LLM.
8. **supersede Reanudar defecto de sincronización**(AI F1,P1——"La primera palabra fue tragada."nuevo camino adyacente)
   - `ChatToolLoopRunner.kt:869-871` Restablecer solo `lastUiUpdateChars`,`lastPersistChars/lastNotifChars` Detención (revisión por el examinador principal);
   - El contenido nuevo es más corto que el contenido antiguo y la limitación falla. Una vez finalizado el proceso, el contenido antiguo permanece en la base de datos.+Nuevo empalme de texto.
9. **vdserver Bloqueo completo + shell sin tiempo de espera**(X1,P1)
   - Cinco carpetas tienen la misma entrada. `synchronized`;`execShellCapture`/`resolveLauncherComponent` de `waitFor()` Sin tiempo de espera
     (ServerService.java:361/377);`am start` La suspensión arrastra todas las operaciones de la pantalla virtual y el perro guardián inactivo también se bloquea. touch() No deje de hacerlo mientras se alimenta.
10. **ensureIndexed Evitar la incorporación de huellas dactilares**(RAG F1,P1)
    - `BuiltInKnowledgeDocSeeder.kt:108-113` Mire solo chunkCount/contentHash, no la clave incrustada del modelo;
    - Después de cambiar el modelo del mismo tamaño, los vectores antiguos y nuevos de los documentos internos se mezclan y la calidad de recuperación se degrada silenciosamente.

## Vista rápida secundaria P1 (47 Los 37 elementos restantes del artículo)

- **A2A Implementación del protocolo**:GetTask/CancelTask Uso indebido de la identificación JSON-RPC como taskId (seguridad F2, documento F2, descubrimiento independiente de doble enlace);
  INVALID_REQUEST código muerto;sendMessage Sin exclusión mutua (AI F4).
- **UI regresión dividida**: Nueva regresión de código"Actualizaciones perdidas"modo de anulación (UI F2,`ChatToolResumeCoordinator:194/235` Espera,CAS accessor Existe y no se utiliza.——
  La muestra del revisor principal es cierta);`refreshDisplayedSessionFromDatabase:4601` Sesión perdidaSwitchGuard(UI F3, la conversación de cadena es del mismo tipo);
  Cambiar la tarjeta de aprobación hacia atrás"resurrección"Invisible con tiempo de espera de aprobación en segundo plano (UI P0 Después de revisar el residuo restante después de la degradación, el juez presidente cambió el veredicto a P1).
- **RAG**:guess Contaminación de dimensionalidad Las huellas dactilares HNSW se reconstruyen cada arranque en frío (F3); Inyección de tres vías sin compuerta total (F5); elimine KB y escriba todo el disco archivo por archivo (F4);
  search_conversation Sin truncamiento de nivel de entrada (F6);ensureIndexed Fallo sin disyuntor de retroceso (F2); la subcadena de lápida se eliminó accidentalmente (F7).
- **Rendimiento**:diagnóstico exportar hilo principal zip(F1, revisado por el revisor principal); al final de cada ronda, la lectura completa de la base de datos se desperdicia 4/5 (F5);suspendCancellableCoroutine
  Lanzamiento sin cancelación (F3); Determinación global de isStreaming completada (F4).
- **Automatización**:workflow displayId lease El área ciega de verificación puede secuestrar la pantalla virtual dedicada del Agente (F2);DeviceCapabilityHook Ejecutando con bloqueo sin tiempo de espera
  su Detectar nodos congelables (F3);patchelf El producto no tiene barandillas CI (F4).
- **CI**:tag↔Gradle La versión tres predeterminada no está sincronizada (F3);detekt cap 32 No hay espacio en la cuenta (F5);horizontal_inset El único caso huérfano que no se pudo abrir (F6);
  .gitattributes Finales de línea de Kotlin desbloqueados (F7,CRLF Causas fundamentales del ruido).
- **Documentación/prueba**:A2A/Tasker/Gancho de complemento/búsqueda 13/Prueba de nivel de combinación de línea roja de diagnóstico Todos los ceros (F1/F2/F3/F4/F6);
  devdocs con deriva de código (13 casa vs "tres tipos");NOTICE Faltan nuevas dependencias v2.5.x;5 Paquete de idiomas 101→118.
- **X corte transversal**:ui_screenshot La escritura entre UID en el directorio caché de la aplicación principal debe fallar + la ruta no tiene lista blanca (X2); Captura de pantalla del canal de Capa 3 no conectado al canal dual del Proveedor (X3).
- Canal:QQ WS Sin evento de pérdida de RESUME, tres fallas de recuperación de almacenamiento, limpieza silenciosa,WeClaw/El descifrado de DingTalk falló en un reintento infinito,
  ON_STOP Detener el receptor y retroceder la verificación de fluctuación no ha aterrizado.

## Revisar el acta de modificación de sentencia (enmiendas previa verificación por el examinador jefe)

- UI Reportado por enlace **P0(aprobación diferida pendiente) rebajada a P1**:`ChatViewModel.kt:4209` `remove()?.complete(Denied)` En realidad lo hará
  complete deferred,30s El tiempo de espera también está ahí; el verdadero residuo es"Resurrección de la tarjeta de aprobación"Dos fallas semánticas son invisibles con tiempos de espera en segundo plano.
- AI Vincular FloorContextLimiterHook(AI F3)"Huérfano tool_result"Parte de la condición es verdadera (el punto de corte cae exactamente en
  assistant(toolCalls) y HERRAMIENTA);SYSTEM La anteposición de bloques destruye la semántica posicional como evidencia directa.
- Confirmación de bucle cerrado de brecha de referencia:Gemini usage-only terminal,Anthropic/Gemini abort listener,BM25 chino 2-gram
  (historia"fracaso silencioso"Ha sido reparado y tiene clavos de devolución).

## Lote de acciones recomendadas

1. **Dejar de sangrar (medio día)**:CI F2 Agregue una línea de traducción al inglés + actualización de línea base de cjk de CI F1 → lanes Vuelva al verde completo;Data F1/F2 suplemento
   knowledgeFolder Importar rama + transferencia de parámetros buildBackup (reparación de cuatro líneas)+ suplemento"Exportar→restaurar→El número de carpetas permanece sin cambios."Devolver.
2. **seguro (1 días)**:Agent F1(A2A No hereda appRunAllowAllTools, o la sesión A2A fuerza el modo de aprobación independiente);
   Seguridad F1 (Tasker agregar interruptor+Limitación de frecuencia+Longitud máxima); Documento F10a (Seguimiento de salida de credenciales + Rotación).
3. **A2A Especial (1-2 días)**: Genere rutas de forma independiente (consulte el modelo de ronda única de Tasker) para resolver el secuestro de sesión del descubrimiento cruzado de seis enlaces al mismo tiempo/
   serialización concurrente/cancel Lesión accidental/Arbitraje de enfoque/task id Pruebas semánticas de protocolo complementario.
4. **Fiabilidad (2-3 días)**: Autocuración del canal F2,AI F1 supersede cursor,X1 vdserver tiempo de espera,UI F2/F3 CAS+guard unidad.
5. **Cierre del libro mayor**:detekt cap,NOTICE,devdocs deriva,.gitattributes final de línea,RAG F1 Huellas dactilares.
