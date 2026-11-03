package io.zer0.muse.rag

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.knowledge.KnowledgeBaseDao
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.notification.MuseNotificationTarget
import org.koin.core.context.GlobalContext

/**
 * v1.133: Reindexación por lotes Worker — Reindexar todos los documentos para todos los KB en segundo plano.
 *
 * escena desencadenante:
 *  - El usuario está en"Gestión de la base de conocimientos."Clic en la página"Reindexar todo"(Aplicación en primer plano,Ir directamente [RagService.reindexAllInKbs] Eso es todo)
 *  - Después de que el usuario ajusta los parámetros de bloqueo en la página de configuración de RAG,Quiere reindexar de forma masiva todos los documentos(toma el libro Worker,ejecución en segundo plano)
 *  - Después de actualizar el modelo de incrustación,Las dimensiones vectoriales antiguas no coinciden → toma el libro Worker
 *
 * diseño:
 *  - Utilice setForeground + notificación de progreso,Los usuarios pueden percibir tareas largas(Android 12+ Requisitos obligatorios foreground service)
 *  - No reintentar en caso de error(Lo mismo [CloudBackupWorker]):Devolver el reintento hará que WorkManager lo vuelva a intentar repetidamente dentro de los 30 segundos.
 *  - Reutilización del canal de notificación [MuseNotificationManager.CHANNEL_WEB_SERVER](baja prioridad,Sin sonido)
 *  - Las actualizaciones de progreso se sincronizan con el WorkManager,UI observable
 *  - retroalimentación del estado final(UX Revisión):Cuando finalice la tarea, WorkManager eliminará la notificación de progreso en primer plano.,Así que aquí hay una cosa más.
 *    Notificación de resultados(Todos exitosos/Algunos fallidos/Todos fallidos/No hay documentos indexables),De lo contrario, el usuario hace clic en el botón.
 *    no puedo ver nada —— Los fallos ya no se registran simplemente.
 *
 * Uso:
 * ```
 * ReindexAllWorker.enqueue(context, kbIds = listOf("default", "kb-xxx"))
 * ```
 */

/** reindexar el estado final:Determinar el texto y la prioridad de las notificaciones de resultados. */
internal enum class ReindexOutcome { SUCCESS, PARTIAL, FAILED }

/**
 * por el éxito/El número total conduce al estado final.total<=0 Trátelo como "nada que hacer" y presione Listo.;
 * successCount==0 y total>0 si**Todo falló**,debe distinguirse del éxito(Result.success siempre estuvo en silencio antes).
 */
internal fun reindexOutcomeOf(successCount: Int, total: Int): ReindexOutcome = when {
    total <= 0 || successCount >= total -> ReindexOutcome.SUCCESS
    successCount <= 0 -> ReindexOutcome.FAILED
    else -> ReindexOutcome.PARTIAL
}
class ReindexAllWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val koin = resultOf { GlobalContext.get() }.getOrNull()
        if (koin == null) {
            Logger.w(TAG, "Koin no inicializado(Safe Mode?),Vuelve a intentarlo más tarde")
            return Result.retry()
        }
        val ragService = resultOf { koin.get<RagService>() }.getOrNull()
            ?: return Result.retry().also { Logger.w(TAG, "RagService El análisis falló,Vuelve a intentarlo más tarde") }
        val settings = resultOf { koin.get<SettingsRepository>() }.getOrNull()
            ?: return Result.retry().also { Logger.w(TAG, "SettingsRepository El análisis falló,Vuelve a intentarlo más tarde") }
        val kbDao = resultOf { koin.get<KnowledgeBaseDao>() }.getOrNull()
            ?: return Result.retry().also { Logger.w(TAG, "KnowledgeBaseDao El análisis falló,Vuelve a intentarlo más tarde") }
        val notifMgr = resultOf { koin.get<MuseNotificationManager>() }.getOrNull()

        // 1. Obtenga una lista de KB para reindexar(Todo si no se especifica)
        val kbIds = inputData.getStringArray(KEY_KB_IDS)?.toList()
            ?: kbDao.getAll().map { it.id }
        if (kbIds.isEmpty()) {
            Logger.i(TAG, "No se requieren KB para volver a indexar")
            notifyNothingToDo()
            return Result.success()
        }

        // 2. Cortar el servicio de recepción + notificación de progreso
        val totalCount = resultOf { koin.get<io.zer0.muse.data.knowledge.KnowledgeDocDao>().getByKbIds(kbIds).size }
            .getOrNull() ?: 0
        if (totalCount == 0) {
            Logger.i(TAG, "No es necesario reindexar ningún documento(kbIds=$kbIds)")
            notifyNothingToDo()
            return Result.success()
        }
        setForeground(buildForegroundInfo(0, totalCount, applicationContext))
        notifMgr?.ensureChannels()

        // 3. Leer la configuración de RAG
        val ragConfig = resultOf { settings.getRagConfig() }.getOrNull() ?: RagConfig()

        // 4. Realizar reindexación
        Logger.i(TAG, "Empezar a reindexar:$totalCount documentos,kbIds=$kbIds")
        val failures = ragService.reindexAllInKbs(
            kbIds = kbIds,
            ragConfig = ragConfig,
            onProgress = { current, total ->
                // Actualizar el progreso de WorkManager(no suspend,Llamable desde una devolución de llamada normal)
                setProgressAsync(workDataOf(KEY_PROGRESS to current, KEY_TOTAL to total))
                // setForeground si suspend,No se puede llamar directamente en la devolución de llamada. — Actualice las notificaciones directamente a través de NotificationManager
                resultOf { updateProgressNotification(current, total, applicationContext) }
                    .onError { msg, _ -> Logger.w(TAG, "Error en la notificación de progreso de la actualización: $msg") }
            },
        )
        val successCount = (totalCount - failures.size).coerceAtLeast(0)
        Logger.i(TAG, "Reindexación completada:éxito $successCount/$totalCount,falló ${failures.size}")
        // UX Revisión:La notificación de progreso en primer plano se elimina cuando finaliza la tarea.,Notificación final aquí —— El error debe tener comentarios visibles para el usuario.
        notifyResult(successCount, totalCount, failures)
        return Result.success()
    }

    /**
     * Notificación del resultado final(Todo éxito / fracaso parcial / todo fracaso).
     *
     * No se devuelve el reintento: el motivo del error es principalmente la incrustación de la configuración o el documento en sí no se puede analizar.,Al volver a intentarlo, golpearás la misma pared repetidamente en 30 segundos.;
     * Los usuarios pueden ver el motivo del error + hay una entrada a la página de administración de KB,Puedes manejarlo tú mismo y ejecutarlo nuevamente.
     */
    private fun notifyResult(successCount: Int, totalCount: Int, failures: Map<String, String>) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val outcome = reindexOutcomeOf(successCount, totalCount)
        val title = applicationContext.getString(R.string.kb_reindex_all)
        val text = when (outcome) {
            ReindexOutcome.SUCCESS -> applicationContext.getString(R.string.kb_reindex_done, successCount)
            else -> {
                val reason = failures.values.firstOrNull()?.take(80) ?: "-"
                applicationContext.getString(
                    R.string.kb_reindex_failed,
                    applicationContext.getString(
                        R.string.kb_reindex_partial,
                        failures.size,
                        totalCount,
                        reason,
                    ),
                )
            }
        }
        val contentIntent = MuseNotificationManager(applicationContext).buildMainActivityPendingIntent(
            MuseNotificationTarget.KnowledgeBaseManage,
        )
        val notification = NotificationCompat.Builder(
            applicationContext,
            MuseNotificationManager.CHANNEL_WEB_SERVER,
        )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(
                if (outcome == ReindexOutcome.SUCCESS) {
                    NotificationCompat.PRIORITY_DEFAULT
                } else {
                    NotificationCompat.PRIORITY_HIGH
                },
            )
            .build()
        resultOf {
            MuseNotificationManager(applicationContext).ensureChannels()
            nm.notify(NOTIF_ID_REINDEX_RESULT, notification)
        }.onError { msg, _ -> Logger.w(TAG, "No se pudo enviar la notificación de resultados: $msg") }
    }

    /** \"No hay documentos que deban volver a indexarse\", pero también entregue un recibo al usuario,De lo contrario, no habrá comentarios después de hacer clic en el botón. */
    private fun notifyNothingToDo() {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val notification = NotificationCompat.Builder(
            applicationContext,
            MuseNotificationManager.CHANNEL_WEB_SERVER,
        )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(applicationContext.getString(R.string.kb_reindex_all))
            .setContentText(applicationContext.getString(R.string.kb_reindex_nothing))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        resultOf {
            MuseNotificationManager(applicationContext).ensureChannels()
            nm.notify(NOTIF_ID_REINDEX_RESULT, notification)
        }.onError { msg, _ -> Logger.w(TAG, "No se pudo enviar la notificación de resultados: $msg") }
    }

    /**
     * Crear notificación de servicio en primer plano(formulario de barra de progreso).
     *
     * Reutilizar [MuseNotificationManager.CHANNEL_WEB_SERVER] canal(baja prioridad,Sin sonido),
     * Evite molestar a los usuarios creando nuevos canales.
     */
    private fun buildForegroundInfo(current: Int, total: Int, context: Context): ForegroundInfo {
        val contentIntent = MuseNotificationManager(context).buildMainActivityPendingIntent(
            MuseNotificationTarget.KnowledgeBaseManage,
        )
        val title = context.getString(R.string.kb_reindex_all)
        val content = if (total > 0) {
            context.getString(R.string.kb_reindex_in_progress, current, total)
        } else {
            title
        }
        val builder = NotificationCompat.Builder(context, MuseNotificationManager.CHANNEL_WEB_SERVER)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (total > 0) {
            builder.setProgress(total, current, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        val notification: Notification = builder.build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID_REINDEX, notification)
        } else {
            @Suppress("DEPRECATION")
            ForegroundInfo(NOTIF_ID_REINDEX, notification)
        }
    }

    /**
     * Actualice las notificaciones de progreso directamente a través de NotificationManager(no suspend,Llamable desde devolución de llamada).
     * con [buildForegroundInfo] compartir [NOTIF_ID_REINDEX] Notificación id,Actualizaciones de cobertura.
     */
    private fun updateProgressNotification(current: Int, total: Int, context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val contentIntent = MuseNotificationManager(context).buildMainActivityPendingIntent(
            MuseNotificationTarget.KnowledgeBaseManage,
        )
        val title = context.getString(R.string.kb_reindex_all)
        val content = if (total > 0) {
            context.getString(R.string.kb_reindex_in_progress, current, total)
        } else {
            title
        }
        val builder = NotificationCompat.Builder(context, MuseNotificationManager.CHANNEL_WEB_SERVER)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (total > 0) {
            builder.setProgress(total, current, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        nm.notify(NOTIF_ID_REINDEX, builder.build())
    }

    companion object {
        private const val TAG = "ReindexAllWorker"
        const val UNIQUE_WORK_NAME = "muse_reindex_all_worker"
        const val KEY_KB_IDS = "kb_ids"
        const val KEY_PROGRESS = "progress"
        const val KEY_TOTAL = "total"

        // Keep RAG progress/results outside MuseNotificationManager's fixed chat/backup IDs.
        private const val NOTIF_ID_REINDEX = 1105

        /** La notificación del resultado final utiliza una identificación independiente: WorkManager eliminará la notificación de progreso en primer plano al final de la tarea.,La reutilización desaparecerá por completo. */
        private const val NOTIF_ID_REINDEX_RESULT = 1106

        /**
         * Poner en cola una tarea de reindexación única.
         *
         * @param context arbitrario Context
         * @param kbIds Lista de identificadores de KB para reindexar;Reindexar todo si está vacío KB
         */
        fun enqueue(context: Context, kbIds: List<String> = emptyList()) {
            val request = OneTimeWorkRequestBuilder<ReindexAllWorker>()
                .setInputData(workDataOf(KEY_KB_IDS to kbIds.toTypedArray()))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                androidx.work.ExistingWorkPolicy.REPLACE,
                request,
            )
            Logger.i(TAG, "Tarea de reindexación en cola,kbIds=$kbIds")
        }
    }
}
