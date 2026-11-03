package io.zer0.muse.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.MainActivity
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.proactive.ProactiveDismissProtocol
import io.zer0.muse.proactive.ProactiveDismissReceiver
import io.zer0.muse.ui.theme.presets.WarmPaperTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal fun isMuseProactiveNotificationId(id: Int): Boolean = id in 0x1000_0000..0x1000_0FFF

/**
 * Phase 8.10: Gestor de notificaciones de generación de mensajes.
 *
 * 3 canales de notificacion:
 *  - [CHANNEL_CHAT_COMPLETED]: Aparece cuando se completa la generación de transmisión en segundo plano(Aplicación en segundo plano)
 *  - [CHANNEL_CHAT_LIVE_UPDATE]: Notificaciones de actualización en tiempo real en compilaciones de transmisión(Barra de progreso en primer plano/Visible en el fondo)
 *  - [CHANNEL_WEB_SERVER]: Phase 8.11 Web Notificación de estado de ejecución del servidor(permanente)
 *
 * diseño:
 *  - Todos los canales LOW importance(silencioso,Barra de estado + banner solamente)
 *  - El canal es [MuseApp.onCreate] Pase [ensureChannels] Crear una vez
 *  - PendingIntent siempre apunta a [MainActivity],Presione el objetivo de notificación nuevamente para ingresar a la función correspondiente.
 *  - No enviar notificación chat_completed cuando esté en primer plano(evitar distracciones),Emitido solo después de que se completa la generación de fondo
 *
 * Permisos:
 *  - Android 13+ Requiere permiso de tiempo de ejecución POST_NOTIFICATIONS(Aplicado por la persona que llama en la capa de UI)
 *  - Android 12- No se requieren permisos de tiempo de ejecución,notificación directa
 */
class MuseNotificationManager(private val context: Context) {

    private val nm: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // v2.0: Limitación de notificaciones de progreso de transmisión — El hilo principal solo escribe la última solicitud.,La notificación real está en la rutina IO.
    // Envío combinado(Máximo 1 vez/segundos),Evite ralentizar la página de chat con actualizaciones frecuentes de notificaciones.
    private data class LiveProgressRequest(
        val sessionTitle: String,
        val currentChars: Int,
        val isStreaming: Boolean,
        val target: MuseNotificationTarget,
    )

    private val liveProgressRequests = MutableStateFlow<LiveProgressRequest?>(null)
    private val liveProgressScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastLiveProgressSentAt = 0L

    init {
        liveProgressScope.launch {
            liveProgressRequests.collect { request ->
                if (request == null) return@collect
                if (!request.isStreaming) {
                    lastLiveProgressSentAt = SystemClock.elapsedRealtime()
                    resultOf { nm.cancel(NOTIF_ID_LIVE_UPDATE) }
                    return@collect
                }
                // acelerador:Espere hasta que termine el intervalo;El nuevo estado se sobrescribirá durante el período de espera.,Enviar siempre el último valor
                val elapsed = SystemClock.elapsedRealtime() - lastLiveProgressSentAt
                if (elapsed < LIVE_PROGRESS_MIN_INTERVAL_MS) {
                    delay(LIVE_PROGRESS_MIN_INTERVAL_MS - elapsed)
                }
                val latest = liveProgressRequests.value ?: return@collect
                if (!latest.isStreaming) {
                    lastLiveProgressSentAt = SystemClock.elapsedRealtime()
                    resultOf { nm.cancel(NOTIF_ID_LIVE_UPDATE) }
                    return@collect
                }
                lastLiveProgressSentAt = SystemClock.elapsedRealtime()
                val notif = buildGenerationNotification(latest.sessionTitle, latest.currentChars, latest.target)
                resultOf { nm.notify(NOTIF_ID_LIVE_UPDATE, notif) }
                    .onError { msg, _ -> Logger.w(TAG, "updateLiveProgress failed: $msg") }
            }
        }
    }

    // pregunta6.4: Secuencia de incremento automático de ID de notificación de mensaje activo,Asegúrese de que a cada mensaje activo se le asigne un nombre único ID(No sobrescribas notificaciones antiguas).
    // Realice la operación OR con NOTIF_ID_PROACTIVE_MESSAGE_BASE para generar el resultado final ID,Tome los 12 bits inferiores para evitar desbordar el rango.
    private val proactiveNotifIdSeq = java.util.concurrent.atomic.AtomicInteger(0)

    /** Crea todos los canales a la vez(idempotente,Android 8.0+ requerido). */
    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channels = listOf(
            NotificationChannel(
                CHANNEL_CHAT_COMPLETED,
                context.getString(R.string.notif_channel_chat_completed_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notif_channel_chat_completed_desc)
                setShowBadge(false)
            },
            NotificationChannel(
                CHANNEL_CHAT_LIVE_UPDATE,
                context.getString(R.string.notif_channel_chat_live_update_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notif_channel_chat_live_update_desc)
                setShowBadge(false)
            },
            NotificationChannel(
                CHANNEL_WEB_SERVER,
                context.getString(R.string.notif_channel_web_server_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notif_channel_web_server_desc)
                setShowBadge(false)
            },
            // mensajes no solicitados:IMPORTANCE_HIGH,hay sonido,porque esto es"El asistente contacta proactivamente con el usuario.",Llama la atención como un mensaje instantáneo
            NotificationChannel(
                CHANNEL_PROACTIVE_MESSAGE,
                context.getString(R.string.notif_channel_proactive_message_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notif_channel_proactive_message_desc)
                setShowBadge(true)
            },
            // El canal de notificación de recepción para servicios de mantenimiento de vida para residentes. El servicio de recepción debe publicar una notificación válida.
            // Cuando falta el canal, el sistema descartará directamente la notificación y arrojará CannotPostForegroundServiceNotificationException.
            NotificationChannel(
                CHANNEL_KEEP_ALIVE,
                context.getString(R.string.keep_alive_notification_title),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.keep_alive_notification_text)
                setShowBadge(false)
            },
        )
        channels.forEach { nm.createNotificationChannel(it) }
        Logger.i(TAG, "Notification channels ensured")
    }

    /**
     * mostrar"Generación de mensajes completada"Notificación(Se llama cuando la aplicación está en segundo plano.).
     * @param sessionTitle Título de la sesión
     * @param preview Vista previa del texto(Primeras 50 palabras del mensaje)
     */
    fun notifyChatCompleted(sessionTitle: String, preview: String, target: MuseNotificationTarget = MuseNotificationTarget.Chat) {
        val notif = NotificationCompat.Builder(context, CHANNEL_CHAT_COMPLETED)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notif_chat_completed_title, sessionTitle))
            .setContentText(preview.take(NOTIF_PREVIEW_MAX_LEN))
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setContentIntent(buildMainActivityPendingIntent(target))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        // M2-1: Usar en su lugar resultOf{}
        resultOf { nm.notify(NOTIF_ID_CHAT_COMPLETED, notif) }
            .onError { msg, _ -> Logger.w(TAG, "notifyChatCompleted failed: $msg") }
    }

    /**
     * v2.0.1: "Esperando aprobación"Notificación — Alertar a los usuarios cuando hay una solicitud de aprobación pendiente y la aplicación no está en primer plano
     * (Alinear Meta Muse"Volver contigo cuando necesite aprobación"; Silencio cuando esté en primer plano para evitar interrupciones).
     */
    fun notifyChatPendingApproval(toolLabel: String) {
        if (isAppInForeground()) return
        val notif = NotificationCompat.Builder(context, CHANNEL_CHAT_COMPLETED)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notif_chat_pending_approval_title))
            .setContentText(context.getString(R.string.notif_chat_pending_approval_text, toolLabel))
            .setContentIntent(buildMainActivityPendingIntent(MuseNotificationTarget.Chat))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        resultOf { nm.notify(NOTIF_ID_CHAT_PENDING_APPROVAL, notif) }
            .onError { msg, _ -> Logger.w(TAG, "notifyChatPendingApproval failed: $msg") }
    }

    /**
     * v0.32: con estrategia"Respuesta completada"Notificación.
     *
     * Determinar si enviar notificaciones según la política de notificación establecida por el usuario:
     *  - "never": nunca enviar notificación
     *  - "when_unfocused": Enviado cuando la aplicación no está en primer plano(Comportamiento predeterminado,Evite las distracciones iniciales)
     *  - "always": enviar siempre notificación
     *
     * @param policy Cadena de política de notificación
     * @param sessionTitle Título de la sesión
     * @param preview Vista previa del texto
     */
    fun notifyChatCompletedWithPolicy(
        policy: String,
        sessionTitle: String,
        preview: String,
        target: MuseNotificationTarget = MuseNotificationTarget.Chat,
    ) {
        when (policy) {
            "never" -> return
            "always" -> notifyChatCompleted(sessionTitle, preview, target)
            // when_unfocused o valor desconocido:Comportamiento predeterminado cuando_unfocused(Notificar solo cuando la aplicación no esté en primer plano)
            else -> {
                if (!isAppInForeground()) {
                    notifyChatCompleted(sessionTitle, preview, target)
                }
            }
        }
    }

    /**
     * v0.32: Comprueba si la aplicación está en primer plano.(usar ActivityManager).
     * Se utiliza para informar juicios de políticas.,Evite molestar a los usuarios cuando esté en primer plano.
     */
    @android.annotation.SuppressLint("DiscouragedApi")
    private fun isAppInForeground(): Boolean {
        // M2-1: Usar en su lugar resultOf{}
        return resultOf {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.appTasks.firstOrNull()?.taskInfo?.topActivity?.packageName == context.packageName
            } else {
                @Suppress("DEPRECATION")
                am.runningAppProcesses.any {
                    it.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                        it.processName == context.packageName
                }
            }
        }.onError { msg, _ -> Logger.w(TAG, "isAppInForeground failed: $msg") }.getOrNull() ?: false
    }

    /**
     * Revisión de corrección (2.0 B-34): Juicio de exposición en recepción — mensajes no solicitados/Los resúmenes diarios y otros canales de voz ALTOS se utilizan de manera uniforme,
     * evitar"Silencio en la recepción"La lógica se repite en cada persona que llama.
     */
    fun isAppForeground(): Boolean = isAppInForeground()

    /**
     * mostrar"mensajes no solicitados"Notificación(Se llama al asistente cuando envía mensajes activamente al usuario como si fuera una persona real.).
     *
     * v0.44: Cambiar el ícono de la barra de notificaciones al avatar del asistente(fotos/Emoji/iniciales),Ya no utilices los íconos de aplicaciones de manera uniforme.
     * Si el avatar no se carga o no hay avatar, volverá al pequeño icono predeterminado.
     *
     * Usar el canal IMPORTANCE_HIGH,Con sonido + banner,Capte la atención como si fuera un mensaje instantáneo.
     * Por título"$assistantName Vienen noticias",El cuerpo es la vista previa del mensaje.(Truncado a 100 palabras),
     * BigTextStyle Ampliar contenido completo;Haga clic para saltar a MainActivity y regresar a la página de chat.
     *
     * @param assistant entidad auxiliar(Obtener nombre + avatar)
     * @param preview Contenido completo del mensaje activo.
     * @param outcomeId El id de este mensaje en la cuenta de resultados: Solo con esto podrás poner "Esta vez no" en la notificación.
     */
    suspend fun notifyProactiveMessage(
        assistant: AssistantEntity,
        preview: String,
        target: MuseNotificationTarget = MuseNotificationTarget.Chat,
        outcomeId: String? = null,
    ) {
        // v1.0.80: El mensaje activo es"El asistente contacta proactivamente con el usuario.",Incluso en la recepción, se deben utilizar las notificaciones del sistema. —
        // Es posible que el usuario no esté viendo la sesión.(Mirando otras paginas/Otros app),Las notificaciones de mensajes instantáneos no pueden interceptarse mediante un juicio en primer plano.
        // La interceptación original de la recepción del B-15 resultó en:página de configuración"Probar mensajes proactivos"Cuando la aplicación debe estar en primer plano,Nunca se envían notificaciones,
        // El usuario no puede autenticarse(retroalimentación:Hay mensajes activos pero no en la barra de notificaciones.). Hay un límite diario de mensajes activos.+Motor de puntuación,Sin interrupciones de alta frecuencia.
        val assistantName = assistant.name.ifBlank { "muse" }
        val avatar = loadAssistantAvatar(assistant)
        val builder = NotificationCompat.Builder(context, CHANNEL_PROACTIVE_MESSAGE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notif_proactive_message_title, assistantName))
            .setContentText(preview.take(NOTIF_PREVIEW_MAX_LEN))
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setContentIntent(buildMainActivityPendingIntent(target))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        // v0.44: Solo se establece si el avatar se carga correctamente largeIcon,De lo contrario, utilice el icono pequeño predeterminado
        if (avatar != null) builder.setLargeIcon(avatar)
        // pregunta6.4: A cada mensaje activo se le asigna un nombre único ID(Basado en el hash de ID del asistente + secuencia de incremento automático),
        // Evite conflictos con el ID fijo de notifyReminder,Y no permita que las notificaciones nuevas sobrescriban las notificaciones antiguas.
        // alcance [NOTIF_ID_PROACTIVE_MESSAGE_BASE, NOTIF_ID_PROACTIVE_MESSAGE_BASE + 0x0FFF],
        // con NOTIF_ID_PROACTIVE_MESSAGE(1004)/NOTIF_ID_CHAT_COMPLETED(1001) Espere a que se escalone la identificación fija.
        val notifId = NOTIF_ID_PROACTIVE_MESSAGE_BASE or
            (proactiveNotifIdSeq.getAndIncrement() and 0x0FFF)
        // "Esta vez no": Tachar la notificación significa "No quiero verla ahora" y hacer clic en este botón significa "esto no debería enviarse".
        // Las dos son señales diferentes en la cuenta de resultados, por lo que setAutoCancel no puede manejarlas.
        if (outcomeId != null) {
            builder.addAction(
                R.drawable.ic_quick_capture_close,
                context.getString(R.string.notif_proactive_action_dismiss),
                buildDismissIntent(outcomeId, notifId),
            )
        }
        val notif = builder.build()
        // M2-1: Usar en su lugar resultOf{}
        resultOf { nm.notify(notifId, notif) }
            .onError { msg, t ->
                // B-15: Android 13+ Se genera una SecurityException cuando se deniega el permiso de notificación.
                // No puedo simplemente recordar W y bajar de categoría silenciosamente como un fallo normal.——Mantener registro de ERRORES,Esto permite que la página de configuración solicite al usuario que autorice POST_NOTIFICATIONS.
                if (t is SecurityException) {
                    Logger.e(TAG, "notifyProactiveMessage denied (Permiso de notificación denegado, Android 13+ necesidad POST_NOTIFICATIONS): $msg", t)
                } else {
                    Logger.w(TAG, "notifyProactiveMessage failed: $msg")
                }
            }
    }

    /**
     * Razones por las que el sistema bloquea las notificaciones de mensajes proactivos;null Indica que no hay elementos de bloqueo visibles.
     *
     * Simplemente responda "¿Es posible mostrarlo después de enviarlo?"——El tiempo de envío y el contenido no se cambiarán.
     * La interceptación a nivel del sistema no generará excepciones(notify Descartado después de unirse silenciosamente a la cola),La persona que llama no puede saberlo por el valor de retorno.,
     * Por lo tanto, el enlace del mensaje activo lo utiliza para separar "notificación no enviada" y "notificación enviada pero el sistema la perderá".
     */
    fun proactiveNotificationBlocker(): String? = when {
        !nm.areNotificationsEnabled() -> "El permiso de notificación del sistema ha sido desactivado"
        nm.getNotificationChannel(CHANNEL_PROACTIVE_MESSAGE)?.importance == NotificationManager.IMPORTANCE_NONE ->
            "El canal de notificación de mensajes activos ha sido cerrado."
        else -> null
    }

    /**
     * v0.44: Cargue el avatar del asistente como Bitmap(Icono grande para la barra de notificaciones.).
     *
     * prioridad:① avatar de imagen(avatarImageUrl,Coil Cargar)→ ② Emoji avatar(Canvas dibujar)→ ③ iniciales(Canvas dibujar).
     * Regreso ante cualquier falla null,La persona que llama vuelve al pequeño icono predeterminado.
     *
     * Los colores se toman del tema de papel cálido predeterminado.(WarmPaperTheme.lightScheme)de primary / surfaceVariant / onSurfaceVariant,
     * La barra de notificaciones no depende del tiempo de ejecución de Compose,Por lo tanto, el valor de color del tema predeterminado estático se obtiene directamente.
     */
    private suspend fun loadAssistantAvatar(assistant: AssistantEntity): Bitmap? {
        // H2-1: Usar en su lugar resultOf{}(Lanzamiento correcto CancellationException,loader.execute si suspend)
        return resultOf {
            when {
                // ① avatar de imagen:Cargado con bobina como Bitmap
                assistant.hasImageAvatar() -> {
                    val loader = Coil.imageLoader(context)
                    val req = ImageRequest.Builder(context)
                        .data(assistant.avatarImageUrl)
                        .size(AVATAR_SIZE)
                        .build()
                    val result = loader.execute(req)
                    (result as? SuccessResult)?.drawable?.let { d ->
                        (d as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    }
                }
                // ② Emoji avatar:Canvas dibujar AVATAR_SIZE x AVATAR_SIZE,fondo circulo color primario
                assistant.avatarEmoji.isNotBlank() -> renderTextAvatar(
                    text = assistant.avatarEmoji,
                    bgColor = WarmPaperTheme.lightScheme.primary.toArgb(),
                    textColor = WarmPaperTheme.lightScheme.onPrimary.toArgb(),
                )
                // ③ iniciales:Canvas dibujar AVATAR_SIZE x AVATAR_SIZE,fondosuperficie del círculocolor variante,texto en superficieColor variante
                else -> {
                    val initial = assistant.name.firstOrNull()?.toString()?.ifBlank { "A" } ?: "A"
                    renderTextAvatar(
                        text = initial,
                        bgColor = WarmPaperTheme.lightScheme.surfaceVariant.toArgb(),
                        textColor = WarmPaperTheme.lightScheme.onSurfaceVariant.toArgb(),
                    )
                }
            }
        }.onError { msg, _ -> Logger.w(TAG, "No se pudo cargar el avatar: $msg") }.getOrNull()
    }

    /**
     * v0.44: Usa Canvas para poner texto(Emoji o iniciales)Dibuja en un mapa de bits circular de 96x96.
     *
     * @param text texto para dibujar
     * @param bgColor Color de fondo del círculo(Android int)
     * @param textColor color del texto(Android int)
     */
    private fun renderTextAvatar(text: String, bgColor: Int, textColor: Int): Bitmap {
        val size = AVATAR_SIZE
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor
            style = Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = textColor
        paint.textSize = size * TEXT_SIZE_RATIO
        paint.textAlign = Paint.Align.CENTER
        val baseline = size / 2f - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2
        canvas.drawText(text, size / 2f, baseline, paint)
        return bmp
    }

    /**
     * Actualización de notificación de progreso de compilación de transmisión(Puede actualizarse repetidamente con el mismo NOTIF_ID).
     * @param sessionTitle Título de la sesión
     * @param currentChars Número de caracteres generados actualmente
     * @param isStreaming ¿Sigue generando?(false Cancelar notificación cuando)
     */
    fun updateLiveProgress(
        sessionTitle: String,
        currentChars: Int,
        isStreaming: Boolean,
        target: MuseNotificationTarget = MuseNotificationTarget.Chat,
    ) {
        // v2.0: Ponga en cola solo el estado más reciente y regrese inmediatamente,Las notificaciones reales se envían mediante limitación de rutina de IO(≤1 veces/segundos)
        liveProgressRequests.value = LiveProgressRequest(sessionTitle, currentChars, isStreaming, target)
    }

    /**
     * v2.5.0: Cancelar notificación de progreso ahora(sincronizar,No pases por la cola de limitación).
     * Llamado antes de que se detenga el servicio en primer plano. —— startForeground Las notificaciones no se pueden limitar de forma asincrónica
     * La cancelación se elimina de forma fiable.,Debe sincronizarse con stopForeground.
     */
    fun clearLiveProgress() {
        liveProgressRequests.value = null
        lastLiveProgressSentAt = 0L
        resultOf { nm.cancel(NOTIF_ID_LIVE_UPDATE) }
            .onError { msg, _ -> Logger.w(TAG, "clearLiveProgress failed: $msg") }
    }

    /**
     * v1.43: Cree notificaciones generadas por streaming utilizadas por los servicios de front-end.
     *
     * con [updateLiveProgress] compartir el mismo canal y ID,Asegúrese de que el inicio del servicio sea coherente con las actualizaciones posteriores.
     */
    fun buildGenerationNotification(
        sessionTitle: String,
        currentChars: Int,
        target: MuseNotificationTarget = MuseNotificationTarget.Chat,
    ) = NotificationCompat.Builder(context, CHANNEL_CHAT_LIVE_UPDATE)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(context.getString(R.string.notif_live_progress_title, sessionTitle))
        .setContentText(context.getString(R.string.notif_live_progress_text, currentChars))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(0, 0, true) // Barra de progreso incierta
        .setContentIntent(buildMainActivityPendingIntent(target))
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    /**
     * mostrar/Actualizar la notificación de estado del servidor web(permanente,hasta que el servidor se detenga).
     * @param port puerto de escucha
     * @param isRunning true mostrar"corriendo",false Cancelar aviso
     */
    fun updateWebServerStatus(port: Int, isRunning: Boolean, target: MuseNotificationTarget = MuseNotificationTarget.SettingsData) {
        if (!isRunning) {
            nm.cancel(NOTIF_ID_WEB_SERVER)
            return
        }
        val notif = NotificationCompat.Builder(context, CHANNEL_WEB_SERVER)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notif_web_server_title))
            .setContentText(context.getString(R.string.notif_web_server_text, port))
            .setOngoing(true)
            .setContentIntent(buildMainActivityPendingIntent(target))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        // M2-1: Usar en su lugar resultOf{}
        resultOf { nm.notify(NOTIF_ID_WEB_SERVER, notif) }
            .onError { msg, _ -> Logger.w(TAG, "updateWebServerStatus failed: $msg") }
    }

    /**
     * v1.136: Mostrar notificaciones de recordatorio programadas.
     *
     * @param title Título de la notificación
     * @param message Texto de notificación
     * @param notificationId Notificación id(Usar ID de recordatorio hashCode,Fácil de cancelar)
     */
    fun notifyReminder(title: String, message: String, notificationId: Int, target: MuseNotificationTarget = MuseNotificationTarget.Home) {
        val notif = NotificationCompat.Builder(context, CHANNEL_PROACTIVE_MESSAGE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message.take(NOTIF_PREVIEW_MAX_LEN))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(buildMainActivityPendingIntent(target))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        resultOf { nm.notify(notificationId, notif) }
            .onError { msg, _ -> Logger.w(TAG, "notifyReminder failed: $msg") }
    }

    /**
     * F-04: Recordatorio automático del resultado de la copia de seguridad(Clasificación por motivos de fallo.).
     *
     * por [io.zer0.muse.schedule.CloudBackupScheduler] Se llama después de que se completa la copia de seguridad automática programada.:
     * éxito/Enviar una notificación por cada fallo.(Los recordatorios de fallos son particularmente importantes,Evite que los usuarios no se den cuenta de las interrupciones de la copia de seguridad durante mucho tiempo).
     * El fallo diferencia aún más entre fallo de escritura/fallo de verificación de relectura,El texto proporciona indicaciones ejecutables.;
     * [io.zer0.muse.backup.BackupService.CloudBackupOutcome.NOT_CONFIGURED] Sin aviso(No configurado para no molestar a los usuarios).
     * Reutilizar [notifyReminder] Canales comunes y comportamiento de la página de respaldo en la nube al hacer clic para ingresar,Se corrigieron los ID de notificación para facilitar la anulación.
     */
    fun notifyAutoBackup(outcome: io.zer0.muse.backup.BackupService.CloudBackupOutcome) {
        if (outcome == io.zer0.muse.backup.BackupService.CloudBackupOutcome.NOT_CONFIGURED) return
        val (titleRes, textRes) = when (outcome) {
            io.zer0.muse.backup.BackupService.CloudBackupOutcome.SUCCESS ->
                R.string.notif_auto_backup_success_title to R.string.notif_auto_backup_success_text
            io.zer0.muse.backup.BackupService.CloudBackupOutcome.WRITE_FAILED ->
                R.string.notif_auto_backup_failed_title to R.string.notif_auto_backup_failed_write_text
            io.zer0.muse.backup.BackupService.CloudBackupOutcome.VERIFY_FAILED ->
                R.string.notif_auto_backup_failed_title to R.string.notif_auto_backup_failed_verify_text
            io.zer0.muse.backup.BackupService.CloudBackupOutcome.NOT_CONFIGURED ->
                // No accesible(Ya arriba return),Mantenga las ramas para satisfacer cuando se agoten.
                return
            // B-9: La contraseña de respaldo expiró,Reutilizar copia de recordatorio de error
            io.zer0.muse.backup.BackupService.CloudBackupOutcome.PASSWORD_UNAVAILABLE ->
                R.string.notif_auto_backup_failed_title to R.string.notif_auto_backup_failed_write_text
        }
        notifyReminder(
            context.getString(titleRes),
            context.getString(textRes),
            NOTIF_ID_AUTO_BACKUP,
            MuseNotificationTarget.CloudBackup,
        )
    }

    /** Cancela todas las notificaciones enviadas por muse. */
    fun cancelAll() {
        resultOf {
            nm.cancel(NOTIF_ID_CHAT_COMPLETED)
            nm.cancel(NOTIF_ID_CHAT_PENDING_APPROVAL)
            nm.cancel(NOTIF_ID_LIVE_UPDATE)
            nm.cancel(NOTIF_ID_WEB_SERVER)
            // La versión antigua usa fijo. ID,Mantenga la limpieza de compatibilidad.
            nm.cancel(NOTIF_ID_PROACTIVE_MESSAGE)
            nm.cancel(NOTIF_ID_AUTO_BACKUP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                nm.activeNotifications
                    .filter { isMuseProactiveNotificationId(it.id) }
                    .forEach { nm.cancel(it.id) }
            }
        }.onError { msg, _ -> Logger.w(TAG, "cancelAll failed: $msg") }
    }

    /** Cree un MainActivity PendingIntent con un objetivo de cadena profunda controlado. */
    fun buildMainActivityPendingIntent(target: MuseNotificationTarget): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = target.deepLink
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val requestCode = target.requestKey.hashCode() and Int.MAX_VALUE
        return PendingIntent.getActivity(context, requestCode, intent, flags)
    }

    /**
     * Construya el PendingIntent para el botón "Esta vez no" en la notificación.
     *
     * requestCode Usar ID de notificación: un botón para cada mensaje activo. Si comparten el mismo PendingIntent,
     * El enviado más tarde sobrescribirá el extra del anterior, y el usuario hace clic en la notificación anterior pero será recordada como el mensaje nuevo.
     */
    private fun buildDismissIntent(outcomeId: String, notifId: Int): PendingIntent {
        val intent = Intent(context, ProactiveDismissReceiver::class.java).apply {
            action = ProactiveDismissProtocol.ACTION_DISMISS
            putExtra(ProactiveDismissProtocol.EXTRA_OUTCOME_ID, outcomeId)
            putExtra(ProactiveDismissProtocol.EXTRA_NOTIFICATION_ID, notifId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, notifId, intent, flags)
    }

    companion object {
        private const val TAG = "MuseNotifMgr"
        const val CHANNEL_CHAT_COMPLETED = "chat_completed"
        const val CHANNEL_CHAT_LIVE_UPDATE = "chat_live_update"
        const val CHANNEL_WEB_SERVER = "web_server"
        const val CHANNEL_PROACTIVE_MESSAGE = "proactive_message"
        const val CHANNEL_KEEP_ALIVE = "muse_keep_alive"
        private const val NOTIF_ID_CHAT_COMPLETED = 1001
        private const val NOTIF_ID_CHAT_PENDING_APPROVAL = 1005
        private const val NOTIF_ID_LIVE_UPDATE = 1002

        // v2.0: Intervalo mínimo de envío para notificaciones de progreso de transmisión(milisegundos)
        private const val LIVE_PROGRESS_MIN_INTERVAL_MS = 900L
        private const val NOTIF_ID_WEB_SERVER = 1003
        private const val NOTIF_ID_PROACTIVE_MESSAGE = 1004

        // Keep auto-backup separate from the pending-approval notification.
        private const val NOTIF_ID_AUTO_BACKUP = 1006

        // pregunta6.4: La dirección base del ID de notificación único del mensaje activo.,Escalonado desde la identificación fija anterior.
        // eventualmente ID = BASE or (seq & 0x0FFF),alcance [0x10000000, 0x10000FFF],no se arreglará con ID(1001~1004)conflicto.
        private const val NOTIF_ID_PROACTIVE_MESSAGE_BASE = 0x1000_0000
        private const val NOTIF_PREVIEW_MAX_LEN = 200

        // L2-1: Tamaño de avatar(Píxel),Se utiliza para cargar bobinas y dibujar lienzos.
        private const val AVATAR_SIZE = 96

        // L2-2: Relación entre el tamaño de fuente china en el avatar de texto y el tamaño del avatar
        private const val TEXT_SIZE_RATIO = 0.5f
    }
}
