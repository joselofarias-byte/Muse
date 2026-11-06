# SDD ledger — plan: E:\1Project\Muse\1muse\.audit\REPORT_MASTER.md

Plan source: REPORT_MASTER.md"Lote de acciones recomendadas"1-5(Instrucciones de usuario para avanzar en su totalidad).
Execution: inline(executing-plans), el trabajo se realiza directamente en 1muse main (el usuario lo requiere claramente después del informe general
"Impulsamos este plan con toda su fuerza", considerado como consentimiento explícito a la obra principal; cada tarea se puede revertir una vez enviada).
Baseline: HEAD 24bf247f(v2.5.8 + platform_selfcheck),1muse El árbol de trabajo está limpio.

Pre-flight: No hay ningún conflicto de uso compartido en la interfaz de tareas dentro del lote——
- Hemostasia A/B (CI control de acceso) y C/D (BackupService) no se cruzan entre sí;
- Seguridad A (ChatToolApprovalCoordinator/ChatUiState) no toca BackupService/Tasker;
- Seguridad B (TaskerTriggerReceiver + manifest) documento independiente;
- A2A Proyecto especial (lote3) depende de la posición de juicio del seguro A y se satisface naturalmente mediante ejecución secuencial.
Ruling(espacio de trabajo): No hay necesidad git worktree —— El árbol de trabajo del almacén principal está limpio y las sesiones anteriores del usuario avanzan directamente en principal.
Además, las tareas planificadas son independientes entre sí y se ejecutan en una sola línea; Costo: si hay un problema, puede revertirlo y enviarlo solo.

Task 1: dejar de sangrar A — values-en suplemento chat_token_estimated_cost
Task 2: dejar de sangrar B — hardcoded_cjk Actualización de línea base (proceso de reconstrucción de línea base que solo reduce el control de acceso pero no lo aumenta)
Task 1: complete (commit fe691617, tests: python ci/script/check_localizations.py → exit 1→0, strict en 0 desaparecido)
Task 2: complete (commit fe691617, tests: python ci/script/check_hardcoded_cjk.py → exit 1→0 PASS 508 consistente con la línea de base;
  Ruling: La línea base es el proceso --update-baseline propio del script de acceso (script generar orientación explícita), no"Eleve la línea de base para volverla verde."——
  Los dos nuevos archivos son los productos divididos de S1-S5,SettingsScreen +1 Es un lote de exportación de diagnóstico. Todas son acciones que han sido impulsadas y enviadas. Eliminarlos pertenece a lotes posteriores.;
  Costo:508 Las deudas existentes seguirán registrándose y se reducirán una vez que se hayan liquidado verdaderamente.)
Task 3: complete (commit 764823a2, tests: BackupNdJsonFolderRestoreTest 3/3 → backup El caso de uso 0 del paquete 54 falló;
  RED Ambas afirmaciones fallan primero.[Distribuir rama faltante/desaparecido flush],GREEN Dorso completamente verde)
Task 4: complete (Lo mismo commit 764823a2; RED buildBackup La afirmación falló→GREEN Pasando parámetros knowledgeFolders = snap.knowledgeFolders;
  Ruling: para probar"Prueba de contrato de código fuente"modo(El almacén ya existe BackupNdJsonRestoreContractTest/BackupEntityCoverageTest Mismo tipo),
  Afirmar que el código fuente contiene parámetros.——Porque buildBackup es una suspensión privada y depende del conjunto completo de Room,No se puede llamar directamente dentro de la unidad;costo:La reorganización del código fuente provocará falsos positivos,
  Luego puedes cambiar el punto de anclaje.)
Task 5: complete (commit a35df745, tests: RemoteApprovalModeTest 5/5 + ToolPermissionResolverTest 17/17
  + ToolApprovalCardTest 2/2 Todo verde; :app:compileDebugKotlin aprobar.
  Ruling: Determine la función pura en el archivo separado ApprovalModeResolver(en lugar de en línea en el coordinador)——El método del coordinador es suspend
  y depender de bridge,No se puede probar directamente;costo:Un archivo interno más,Aceptable.
  Ruling: persistedModeProvider usar suspend lambda —— Durante la compilación, se descubrió que no se podía llamar a la lambda que no cuelga.
  sessionPermissionStore.getMode()(dentro de ella first() esta colgado),Firma cambiada.
  Ruling: Solo reduzca el alcance del bit de liberación,No hay cambios en la arquitectura de extremo a extremo A2A —— secuestro de sesión/La serialización concurrente cura lotes 3(A2A Especial),
  Esta presentación sólo elimina"Ejecución desatendida y silenciosa de herramientas de dispositivo ALTA."La consecuencia más peligrosa.)
Task 6: complete (commit 51ac9006, tests: TaskerTriggerPolicyTest 7/7 + RemoteApprovalModeTest 5/5;
  :app:compileDebugKotlin Pase; check_hardcoded_cjk Todavía PASO.
  Ruling: No cambiar la semántica exportada(Tasker Requisitos previos necesarios para la transmisión entre aplicaciones),Sólo compensar la línea de defensa de costos;
  UI entrada del interruptor(página de configuración)Pertenece al final de seguimiento. —— costo:El usuario actual no puede activar el interruptor en la interfaz de usuario,La función está desactivada de forma predeterminada.
  Ruling: El estado de frecuencia limitada es más acompañante que persistente. —— Restablecer cuando se reinicia el proceso,Sin embargo, hacer coincidir un interruptor que está apagado de forma predeterminada no constituye una derivación.;
  costo:El reinicio de procesos cruzados en escenarios extremos se puede activar una vez más)
Task 7: complete (museai.ltd commit 305a03b, tests: git grep Credenciales 0 visitas / git check-ignore visitas /
  report.py ast.parse OK + --help Se aprobó el ensayo de fragmento ejecutable/parametrizado.
  Ruling: solo hazlo"Eliminar seguimiento", no reescribas el historial de git para esta confirmación —— Aún quedan credenciales en la historia. La eliminación completa requiere filter-repo o
  Tratada como filtrada, es una operación irreversible que requiere la toma de decisiones del usuario (executing-plans una de las cuatro condiciones de parada);
  Costo: los envíos históricos aún contienen direcciones de servidor y rutas clave. Si el almacén se agrega de forma remota en el futuro, primero debe procesarse.
  Ruling: Mantenga DEPLOY.md ejecutable —— usar $DeployUser/$DeployHost/$DeployKey/$DeployRoot variables + locales
  .deploy.local.ps1(gitignored); Costo: Un paso más antes de la implementación, dot-source, que se indica al principio del documento.)
Task 8: A2A Especial core complete (commit 92c2d1fa; A2A headless generator + Agent-only context sessions + bounded LRU context cleanup + task registry/job cancellation + JSON-RPC params.id/INVALID_REQUEST/PARSE_ERROR semantics + sendMutex + prompt cap 16000;
  tests: A2aHeadlessGenerator 2/2, A2aHandlerIsolation 5/5, A2aContextRegistry 3/3,
  A2aTaskRegistry 6/6, A2aJsonRpcSemantics 9/9, RemoteApprovalMode 5/5; compileDebugKotlin PASS.
  Ruling: A2A is intentionally text-only/headless in this batch (tools=null, no ChatViewModel). This is the safe
  interpretation of REPORT_MASTER “Consulte el modelo Tasker de una sola rueda.”; reopening remote device tools requires a new design/approval
  and must not reconnect A2A to UI ChatViewModel. Existing v2.5.8 historical release notes still describe the old
  shared tool path and need a documentation correction in the docs/ledger batch.
  Ruling: JSON-RPC compatibility keeps envelope-id fallback for legacy GetTask/CancelTask callers while preferring params.id.
  Ruling: unknown CancelTask returns TASK_NOT_FOUND and is not inserted into the task registry (prevents task-book poisoning).

Task 9: Tasker Cambio de interfaz de usuario de la página de configuración completado (commit 54c8224f; user-approved bounded design):
  - AutomationSettingsPage Se agregó el interruptor Tasker/Automatización externa que está desactivado de forma predeterminada;NavGraph Inyectar repositorio de configuración;
  - Reutilizar taskerTriggerEnabledFlow/saveTaskerTriggerEnabled;La copia es clara“La finalización de texto externo puede consumir créditos y no otorgará permisos de herramienta.”;
  - 7 locales: zh/en/es/ja/ko/pt-BR/ru isolated resource files;AutomationSettingsPageTest 3/3;Tasker policy suite PASS;
  - localization gate PASS;ktlint repository gateTodavía existen SettingsModels/SettingsRepository/ChatSettings/ChatScreen/MessageBubble Espere el artículo rojo en stock,
    Tasker/A2A El archivo en sí no tiene hallazgos de ktlint.

Punto de pausa (el usuario requiere que se publique un nuevo paquete): el alcance de esta ronda de reparaciones se ha completado, el avance de los lotes 4-5 está suspendido y las mejoras de confiabilidad no continuarán./CAS/vdserver/RAG/Ledger, esperando que los usuarios lancen paquetes de parches.
Pendiente de recuperación: Lote 4 (channel self-healing,AI supersede cursor,vdserver timeout,CAS/session guard); Lote 5 (detekt/license/devdocs/CRLF/RAG);
Limpieza de credenciales históricas/SSH rotationAún se requiere la aprobación explícita del usuario y no se ejecutará en esta ronda.

Task 10: Verificación previa local completada antes del lanzamiento (candidate v2.5.9 / versionCode 259):
  - clean serial full Gradle preflight first exposed three existing regression contracts / one environment-sensitive test;
    MessageBubbleActionMenuWiringTest and MessageBubbleModelMetaWiringTest were stale relative to already committed quick-action
    behavior (5fbc40ad/aac53073) and were updated in commit 5137215f; OpenAIProviderAbortTest passed when isolated.
  - :app:testDebugUnitTest --no-parallel PASS (2238 tests, 4 skipped); :ai:testDebugUnitTest abort regression PASS;
    test + :app:testDebugUnitTest + :app:assembleDebug serial PASS; :app:assembleRelease -PversionName=2.5.9 -PversionCode=259 PASS.
  - Candidate artifacts materialized locally under releases/v2.5.9 (APK files remain gitignored by policy): universal/arm64/armeabi-v7a/x86_64;
    aapt package identity 2.5.9/259; apksigner v2 verification PASS with one signer; SHA-256 and sizes recorded in manifest.json.
  - Release body bilingual gate, localization gate, CJK gate PASS. Full repository ktlint still has known unrelated baseline debt.
  - No push/tag/upload/publication/credential rotation performed. Release notes/manifest/verification files are local candidate deliverables;
    user must perform publication and real-device acceptance before final release.

Task 11: v2.6.18 published (2026-11-01) — scope is everything since tag v2.6.10:
  - Functional work sat uncommitted (722 paths); the 11 commits between v2.6.10 and the release were docs-only.
    Landed as three commits: 797df89f (features, fixes, tests), 36f46304 (release materials + web host client sources),
    040ab3fa (stale doc cleanup + market signer tweak).
  - Gates before packaging: :app 3572 / :memory 335 / :ai 485 / :miniphone 309 = 4701 tests, 0 failures; :app:detekt 0 issues;
    release lint no errors/warnings; release body bilingual gate PASS.
  - Artifacts: versionName 2.6.18 / versionCode 281, four ABIs (universal, arm64-v8a, armeabi-v7a, x86_64);
    apksigner verify PASS; sizes and SHA-256 recorded in releases/v2.6.18/manifest.json and release_verification.txt.
  - Publication was performed by the agent, per explicit user instruction. This supersedes the Task 10 note that publication
    is the user's step, for this release only.
  - Network: github.com TLS was blocked on this host (schannel and openssl backends both failed; api.github.com and
    uploads.github.com were reachable). An API-based push script was written and abandoned at the rate limit.
    After the user switched proxy node, plain push worked:
    `git -c http.proxy=http://127.0.0.1:7897 push origin main` -> d2c73195..040ab3fa; tag v2.6.18 = 78a4c6e0.
  - Release created with ci/script/publish_release.py (HTTPS_PROXY set): four assets uploaded on the first attempt and
    remote names/sizes/SHA-256 verified. URL: https://github.com/Zer0Qing/Muse/releases/tag/v2.6.18
  - Release body deliberately omits the mini phone and the web UI surfaces: both are hidden in this build
    (settings entry `browseVisible = false`, home entry gated by `FeatureFlags.MINI_PHONE_ENTRY`), still searchable and
    still functional. `manifest.json` drops the `webShell` field for the same reason.
  - Open items: four stale `.zip` files under releases/ remain untracked; the APK carries ~59 unused hashed web assets from
    repeated builds, so cleaning the build dir before the next release is worth doing;
    queued product work: proactive watchdog criteria, thread/event model, acceptance surface.

