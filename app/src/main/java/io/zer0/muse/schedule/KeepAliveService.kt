package io.zer0.muse.schedule

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.notification.MuseNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * v2.6.9: Servicio de recepción residente keep-alive (activado explícitamente por el usuario).
 *
 * Por qué lo necesitas:
 * tareas programadas,AI Los mensajes privados activos y la programación del círculo de amigos se ejecutan en el proceso de solicitud. Una vez que la ROM recicla el proceso,
 * WorkManager Se necesitarían 15 minutos como máximo para regresar y se perderían todos los mensajes intermedios.
 * Un servicio de primer plano residente puede fijar el proceso en el nivel de primer plano, que es el único método de mantenimiento "compatible y eficaz" en Android.
 *
 * con [ChatGenerationService] La diferencia:
 * Éste "pasa temporalmente al primer plano durante la generación" y sale tras generación; éste es "residente activo por el usuario".
 * El ciclo de vida se controla mediante interruptores y no termina con una sola generación.
 *
 * Límites de la honestidad (escriba claramente para evitar engañar a los usuarios):
 *  - Android Es obligatorio que los servicios front-end tengan notificaciones visibles,**no se puede ocultar**. Cualquier cosa que pretenda estar oculta no es un medio legítimo.
 *  - ROM nacional (MIUI/EMUI/ColorOS…) además limpiará el fondo y el usuario aún deberá incluir una lista blanca de inicio automático en la configuración del sistema.
 *    Esta parte sólo puede ser guiada y no puede completarse automáticamente.
 *  - No hagas nada para hacerse pasar por otra aplicación: cambia el nombre del paquete/Icono de falsificación/Los íconos ocultos son todas características del malware.
 *    La tienda de aplicaciones la considerará una aplicación riesgosa.
 *
 * Medios para mantenerse con vida (todos conformes):
 *  1. startForeground + Notificación permanente (la prioridad del proceso pasa al primer plano)
 *  2. WakeLock opcional (se mantiene cuando el usuario lo enciende para evitar que Doze ponga la CPU en suspensión y provoque que la red se cuelgue)
 *  3. onTaskRemoved Activar un reinicio (el usuario intenta recuperarlo después de tacharlo de la tarea reciente)
 *  4. START_STICKY(La memoria del sistema se reconstruye automáticamente después del reciclaje)
 *  5. La rutina de latido actualiza periódicamente el estado de supervivencia (la programación perdida es responsabilidad del propio trabajador de cada función y la activación complementaria en primer plano, que no se realizará aquí)
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAliveService"

        const val ACTION_START = "io.zer0.muse.action.KEEP_ALIVE_START"
        const val ACTION_STOP = "io.zer0.muse.action.KEEP_ALIVE_STOP"

        private const val NOTIFICATION_ID = 31001
        private const val CHANNEL_ID = MuseNotificationManager.CHANNEL_KEEP_ALIVE
        private const val HEARTBEAT_INTERVAL_MS = 60_000L

        /** Iniciar el servicio residente. Cuando ya se encuentra en el estado de residencia, las llamadas repetidas no tienen efectos secundarios. */
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, KeepAliveService::class.java).setAction(ACTION_START)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { t ->
                Logger.w(TAG, "No se pudo iniciar el servicio para residentes: ${t.message}")
            }
        }

        /** Detener el servicio residente. */
        fun stop(context: Context) {
            runCatching {
                context.startService(Intent(context, KeepAliveService::class.java).setAction(ACTION_STOP))
            }.onFailure { t ->
                Logger.w(TAG, "No se pudo detener el servicio para residentes: ${t.message}")
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        // Android Requiere que se llame a startForeground lo antes posible; de ​​lo contrario, ANR/crash. Crea e ingresa a la recepción.
        startForegroundCompat()
        startHeartbeat()
        acquireWakeLockIfEnabled()
        Logger.i(TAG, "Se ha iniciado el servicio a residentes.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Logger.i(TAG, "Comando de parada recibido")
                stopSelf()
                return START_NOT_STICKY
            }
            else -> Logger.d(TAG, "El servicio para residentes está funcionando.")
        }
        // START_STICKY: Reconstruir automáticamente el servicio después de recuperar la memoria del sistema (la intención no se conserva)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Se activa cuando el usuario elimina la aplicación de tareas recientes.
     *
     * Intenta levantarte de nuevo aquí.——Los procesos suelen sobrevivir poco tiempo después de ser tachados.
     * La tasa de éxito al reiniciar el servicio es mayor dentro de esta ventana.ROM Todavía falla durante la limpieza profunda, lo cual es de esperar.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Logger.i(TAG, "La tarea fue tachada, intenta reiniciar el servicio residente.")
        val restart = Intent(applicationContext, KeepAliveService::class.java).setAction(ACTION_START)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(restart)
            } else {
                applicationContext.startService(restart)
            }
        }.onFailure { t ->
            Logger.w(TAG, "No se pudo reiniciar el servicio residente (puede estar restringido por ROM): ${t.message}")
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        heartbeatJob?.cancel()
        releaseWakeLock()
        serviceScope.cancel()
        Logger.i(TAG, "El servicio para residentes se ha detenido.")
        super.onDestroy()
    }

    /** Ingrese a la recepción y cuelgue la notificación para residentes. Cuando falla, aún se debe llamar a startForeground; de lo contrario, el sistema generará una excepción de tiempo de espera. */
    private fun startForegroundCompat() {
        // Los canales son registrados uniformemente por el administrador de notificaciones (idempotente).
        MuseNotificationManager(this).ensureChannels()
        val notification = buildNotification()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ El tipo de servicio front-end debe declararse explícitamente
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { t ->
            Logger.e(TAG, "startForeground Fallar, renunciar permanentemente", t)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                runCatching {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                    )
                }
            }
        }
    }

    private fun buildNotification(): Notification {
        // v2.6.9: Leer texto de notificación definido por el usuario (cadena vacía → predeterminado).
        // La lectura está suspendida y debe obtenerse sincrónicamente antes de iniciarForeground.
        // Por lo tanto, runBlocking está limitado a 200 ms: si no puede obtenerlo, use el valor predeterminado y nunca bloquee el inicio.
        val (customTitle, customText) = runCatching {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(200L) {
                    val settings = GlobalContext.get().get<SettingsRepository>()
                    settings.keepAliveNotificationSnapshot()
                }
            }
        }.getOrNull() ?: ("" to "")
        val title = customTitle.ifBlank { getString(R.string.keep_alive_notification_title) }
        val body = customText.ifBlank { getString(R.string.keep_alive_notification_text) }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_muse_logo)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        // Haga clic para regresar a la interfaz principal de la aplicación.
        runCatching {
            val launch = packageManager.getLaunchIntentForPackage(packageName)
            if (launch != null) {
                builder.setContentIntent(
                    android.app.PendingIntent.getActivity(
                        this,
                        0,
                        launch,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                            android.app.PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
        }
        return builder.build()
    }

    /**
     * Latido del corazón: registre el estado de supervivencia con regularidad.
     *
     * Aquí deliberadamente solo hacemos "activación ligera + registro" y no ejecutamos directamente la tarea de generación.——
     * La generación de tareas es responsable del sondeo respectivo de ScheduledTaskRunner/ProactiveMessageRunner,
     * El servicio solo garantiza que el proceso esté activo y no implemente la lógica de programación repetidamente (de lo contrario, habrá doble sondeo y captura mutua).
     */
    private fun startHeartbeat() {
        heartbeatJob = serviceScope.launch {
            var tick = 0L
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                tick++
                if (tick % 10 == 0L) {
                    Logger.d(TAG, "El latido del corazón del residente está vivo y funcionando $tick minutos")
                }
            }
        }
    }

    /** Mantenga presionado WakeLock cuando el usuario active explícitamente \"Mantener conectado incluso cuando la pantalla esté apagada\". */
    private fun acquireWakeLockIfEnabled() {
        val settings = runCatching {
            GlobalContext.get().get<SettingsRepository>()
        }.getOrNull() ?: return
        // Configure el modo de lectura para suspender, use el alcance IO para obtenerlo de forma asincrónica y, si no puede obtenerlo, no lo mantenga presionado.
        serviceScope.launch {
            val enabled = runCatching { settings.keepAliveWakeLockFlowSnapshot() }.getOrDefault(false)
            if (!enabled) return@launch
            runCatching {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Muse::KeepAlive").apply {
                    setReferenceCounted(false)
                    acquire(24 * 60 * 60 * 1000L) // Hasta 24 horas, liberado automáticamente después del vencimiento para evitar olvidar liberar el consumo de energía
                }
                Logger.i(TAG, "WakeLock adquirido (usuario habilitado)")
            }.onFailure { t ->
                Logger.w(TAG, "No se pudo obtener WakeLock: ${t.message}")
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }
}
