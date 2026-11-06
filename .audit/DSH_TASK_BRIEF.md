# DSH Declaración de misión:Muse Revisión de planes de restauración para seguir adelante

> Este archivo es la especificación completa para el ejecutor dsh. Después de leer, siga estrictamente esta guía. Si se informa un error, continúe con el autodiagnóstico. No te detengas y esperes a los demás.
> Cada vez que se completa un paso, el progreso se agrega a este directorio. `DSH_PROGRESS.md`.

## papel

Eres el ejecutor de reparaciones del proyecto Muse para Android, avanzando `.audit/REPORT_MASTER.md` Revisión del plan de reparación.
Lea REPORT_MASTER.md primero y relacionado `.audit/REPORT_*.md` Informe por separado y luego ejecútelo de acuerdo con la lista a continuación.

## Fijo——No rehacer (git log comprobado)

1. Copia de seguridad de la carpeta de la base de conocimientos de datos F1/F2 (commit `764823a2`)
2. A2A aislar sin cabeza (`92c2d1fa`, incluida la semántica del protocolo)
3. Tasker cambiar+Limitación de frecuencia (`54c8224f`)
4. Credenciales del sitio web oficial (`3d2cdedc`)
5. CI F2 locale(values-en Ya `chat_token_estimated_cost`)
6. CI F1 cjk puerta (`df740a6d`, cadena de registrador multilínea + recurso de transferencia codificado de OAuth, la puerta ha sido PASS)
7. AI F1 supersede cursor(`787ca9cf`, incluidas las pruebas de regresión)

## Progreso actual: desde X1 (vdserver) empezar

## Lotes posteriores (en orden, salte reparado)

- **AI F1**: supersede Reanudar el tiempo de carga (ChatToolLoopRunner.kt:869-871 Restablecer solo lastUiUpdateChars,lastPersistChars/lastNotifChars Retención, el contenido nuevo es más corto que el contenido antiguo y la limitación falla)
- **X1**: vdserver waitFor() Sin tiempo de espera (ServerService.java:361/377)+ bloqueo completo
- **UI F2/F3**: ChatToolResumeCoordinator:194/235 Perder y actualizar CAS;refreshDisplayedSessionFromDatabase:4601 recoger SessionSwitchGuard
- **RAG F1**: BuiltInKnowledgeDocSeeder.ensureIndexed Suplemento de incrustación de comparación de huellas digitales de modelos (108-113)
- **RAG F3**: guess Contaminación dimensional Huella digital HNSW
- **canal F2**: Cinco receptores están permanentemente muertos sin autocuración (TelegramReceiver.kt:58,ChannelStore.load:27-29, inténtalo de nuevo/WorkManager detalles completos)
- **datos**: B-34 La conciliación solo cuenta el número de líneas y no verifica la entrada en la base de datos (BackupService.kt:1007-1013)
- **CI F3/F5/F7**: tag↔Gradle Sincronización de versiones,detekt cap libro mayor,.gitattributes Bloquear finales de línea de Kotlin
- **NOTICE** Suplemento v2.5.x nuevas dependencias

## Disciplina (ley de hierro)

1. `.audit/` Sólo se permite escribir en `DSH_PROGRESS.md`, el resto son de sólo lectura;`releases/` sin moverse
2. Ejecute control de acceso total al final del lote `.\gradlew.bat :app:ktlintKotlinSourceCheck :app:detekt :app:testDebugUnitTest`(pwsh 7, redirigir para tomar `$LASTEXITCODE`)
3. baseline Número de línea sensible: reconstrucción bajo demanda+diff Confirmar que no hay nuevas exenciones; A las nuevas violaciones se les dará prioridad para corregir el código y la estructura inherente. `@Suppress` exención por punto
4. Compromiso de git independiente para cada reparación (`fix(scope)` + AUD número), exacto `git add`, prohibido `add -A`
5. Corregir errores sin refactorizar;UI Copiar sincronización 7 idiomas strings*.xml(values/values-en/es/ja/ko/pt-rBR/ru)
6. Sin embalaje, sin publicación, sin envío remoto, sin impresión tag
7. git/gradle Si necesita exceder su autoridad, presente la solicitud directamente y el anfitrión lo dejará ir automáticamente.

## Estándares de finalización

Una vez completados todos los lotes: ejecute el control de acceso total+La confirmación de prueba única es verde y el resumen de resultados (lo que se reparó)/Resultados de la verificación/lista sobrante).
