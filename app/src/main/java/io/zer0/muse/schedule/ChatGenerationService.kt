package io.zer0.muse.schedule

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.RequiresApi
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.notification.MuseNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * v1.43: El chat genera servicio front-end.
 *
 * Cuando el usuario pasa al fondo,Mantenga vivo el proceso como un servicio en primer plano,Asegúrese de que la generación de IA no se vea interrumpida por el reciclaje del sistema.
 * - Mostrar notificaciones persistentes al inicio(Reutilizar [MuseNotificationManager.updateLiveProgress] de ID)
 * - Enviado por la persona que llama después de que se completa la generación [ACTION_STOP] detener el servicio
 * - El servicio en sí no realiza la lógica de compilación.,Solo como contenedor frontend keep-alive;La lógica de generación continúa. [ChatGenerationManager] En la corrutina a nivel de aplicación
 *
 * v1.112: Reparación ForegroundServiceDidNotStartInTimeException colapsar.
 *  - Originales:Sólo se llama en StartCommand startForeground(),Si el hilo principal está ocupado/Koin inyectar/compilación de notificaciones
 *    tarda más de 5 segundos,O pasar una acción desconocida para omitir la rama cuando,El sistema genera una excepción de tiempo de espera y falla directamente.
 *  - nuevo:en onCreate() Inmediatamente startForeground()(La creación del servicio ingresa inmediatamente a la recepción.),onStartCommand solo manejar
 *    Lógica posterior;when Añade más para decir la verdad.;startForeground Pasar adaptación de tipo explícita Android 14+(SDK 34+).
 */
class ChatGenerationService : Service() {

    private val notificationManager: MuseNotificationManager by inject()
    private val chatGenerationManager: ChatGenerationManager by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observeJob: Job? = null
    private var emptyStateStopJob: Job? = null
    private val stopGate = ForegroundServiceStopGate()
    private var latestStartId: Int = 0

    // v1.0.15: Wakelock mantener vivo,Evite los bloqueos de la red causados por la hibernación de la CPU en modo Doze
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        stopGate.reset()
        // v2.2.0: Koin Salir con gracia cuando no se haya iniciado(no choques) — El reinicio del servicio fijo del sistema puede ocurrir en
        // MuseApp Saltar el proceso de modo seguro de startKoin; Si aquí no hay defensa, Seguimiento by inject
        // puede tirar "KoinApplication has not been started" Se forma un ciclo de choque.
        if (org.koin.core.context.GlobalContext.getOrNull() == null) {
            Logger.w("ChatGenService", "Koin No iniciado(Safe Mode/Recuperación de excepción de proceso) — Renunciar a seguir vivo")
            stopService()
            return
        }
        // v1.112: Cuando se crea el servicio, entra en estado de primer plano.,Evite el retraso de onStartCommand que provoca un bloqueo del tiempo de espera de 5 segundos.
        // Construir una notificación con un título predeterminado;El onStartCommand posterior se actualizará de acuerdo con el estado de generación real.
        try {
            val notification = notificationManager.buildGenerationNotification(
                getString(R.string.schedule_generating_default_title),
                0,
                io.zer0.muse.notification.MuseNotificationTarget.Chat,
            )
            startForegroundCompat(notification)
        } catch (t: Throwable) {
            // También se debe llamar cuando se notifica que la compilación falla. startForeground,De lo contrario, se activará un bloqueo del tiempo de espera;
            // Utilice un aviso mínimo para cubrir todo(Si NotificationManager no está disponible,Deténgase directamente para renunciar a mantenerse con vida.).
            Logger.e("ChatGenService", "onCreate startForeground falló,Renunciar a seguir vivo", t)
            stopSelf()
            return
        }
        // v1.0.15: Obtener PARTIAL_WAKE_LOCK,Asegúrese de que la CPU no entre en suspensión y que Doze no suspenda la red durante la generación.
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Muse:ChatGeneration")
            .also { it.setReferenceCounted(false) }
        // El ciclo de vida del servicio front-end es generar un ciclo de vida de mantenimiento.,No se utiliza la versión de tiempo de espera de 10 minutos.
        // Las tareas largas de MCP/herramienta pueden exceder los 10 minutos;stopService/onDestroy El bloqueo se liberará explícitamente.
        runCatching { wakeLock?.acquire() }
            .onFailure { Logger.w("ChatGenService", "wakelock acquire falló", it) }
        Logger.i("ChatGenService", "service created & foreground started & wakelock acquired")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        // v2.2.0: Igual que la defensa Koin de onCreate — null Intent(sticky Reiniciar)Lo más común en este escenario.,
        // Debe ser interceptado antes de acceder a cualquiera de las propiedades de inyección.
        // 2026-09-27 Reparación de accidentes de máquinas reales: ChatGenerationService Era el único componente sin defensa Koin.,
        // Lanzar cuando falta "KoinApplication has not been started" colapsar.
        if (org.koin.core.context.GlobalContext.getOrNull() == null) {
            Logger.w("ChatGenService", "Koin No iniciado(Safe Mode?) — detener el servicio,No entres en el circuito de observación.")
            stopService()
            return START_NOT_STICKY
        }
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_START -> startObserve()
            ACTION_STOP -> stopService()
            else -> {
                // v1.112: La acción desconocida también inicia la observación.,Asegúrese de que omitir ramas no provoque que el servicio quede inactivo.
                Logger.w("ChatGenService", "desconocido action: $action,Presione INICIO para procesar")
                startObserve()
            }
        }
        // El servicio solo es responsable de mantener vivo y observar el administrador de compilación a nivel de aplicación.;Después de ser reciclado por la ROM, debes intentar reanudar la observación.,
        // Deje que los engendros supervivientes de appScope sigan teniendo prioridad en primer plano.
        return START_STICKY
    }

    private fun startObserve() {
        stopGate.reset()
        // Actualizar el título de la notificación con el estado de compilación más reciente
        val active = chatGenerationManager.activeGenerations.value.values
            .maxByOrNull { it.lastUpdatedAt }
        val title = active?.sessionTitle ?: getString(R.string.schedule_generating_default_title)
        try {
            val target = active?.sessionId
                ?.takeIf { it.isNotBlank() }
                ?.let(io.zer0.muse.notification.MuseNotificationTarget::Session)
                ?: io.zer0.muse.notification.MuseNotificationTarget.Chat
            val notification = notificationManager.buildGenerationNotification(
                title,
                0,
                target,
            )
            startForegroundCompat(notification)
        } catch (t: Throwable) {
            Logger.w("ChatGenService", "No se pudo actualizar la notificación en primer plano", t)
        }

        // Supervisar los cambios de estado de compilación,Actualizar el contenido de la notificación/Progreso
        observeJob?.cancel()
        observeJob = serviceScope.launch {
            chatGenerationManager.activeGenerations.collect { generations ->
                if (generations.isEmpty()) {
                    // dar“Iniciar servicio”y“Registrar tarea”Deja una ventana de carrera corta;Si se reinicia el proceso
                    // manager sin tareas,luego sal rapido,No dejes que START_STICKY quede sin usar durante 15 minutos.
                    if (emptyStateStopJob == null) {
                        emptyStateStopJob = launch {
                            delay(EMPTY_STATE_GRACE_MS)
                            if (chatGenerationManager.activeGenerations.value.isEmpty()) {
                                stopService()
                            }
                        }.also { job ->
                            job.invokeOnCompletion { emptyStateStopJob = null }
                        }
                    }
                    return@collect
                }
                emptyStateStopJob?.cancel()
                emptyStateStopJob = null
                val stale = generations.values.firstOrNull { generation ->
                    System.currentTimeMillis() - generation.lastUpdatedAt > HEARTBEAT_TIMEOUT_MS
                }
                if (stale != null) {
                    Logger.w("ChatGenService", "Generar tiempo de espera de latido,Detener esta sesión(sesión: ${stale.sessionTitle})")
                    // Cuando hay varias sesiones simultáneas, solo se cancelan las tareas que pierden latidos.,No se puede mantener viva la recepción de otras tareas normales
                    // Apágalo juntos. La cancelación de la ruta será respondida por la parte de persistencia de ChatViewModel/Control.
                    chatGenerationManager.stop(stale.sessionId)
                    return@collect
                }
                // Las notificaciones muestran tareas actualizadas recientemente cuando hay varias sesiones simultáneas;Las decisiones de mantenimiento de la vida utilizan instantáneas completas.
                // v2.5.0 fix: Mostrar recuento de palabras reales(Anteriormente Hengchuan 0,El número de palabras en la copia del aviso permanece sin cambios.,El usuario piensa erróneamente que está atascado).
                val gen = generations.values.maxByOrNull { it.lastUpdatedAt } ?: return@collect
                notificationManager.updateLiveProgress(
                    gen.sessionTitle,
                    gen.currentChars,
                    true,
                    io.zer0.muse.notification.MuseNotificationTarget.Session(gen.sessionId),
                )
            }
        }
    }

    private fun stopService() {
        if (!stopGate.tryRequest()) return
        observeJob?.cancel()
        observeJob = null
        emptyStateStopJob?.cancel()
        emptyStateStopJob = null
        // v1.0.15: liberar wakelock
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        // v2.5.0 fix: Cancele la notificación primero y luego salga de la recepción. —— startForeground Las notificaciones no pueden ser nm.cancel
        // Eliminar individualmente(El sistema se recuperará.),primero debe stopForeground;Por el contrario, si cancela primero, la identidad del front-end lo tragará.,
        // Queda un mensaje con la misma cantidad de caracteres en la barra de notificaciones."Barra de progreso dinámica"(Prueba real del usuario).
        notificationManager.clearLiveProgress()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            .onFailure { Logger.w("ChatGenService", "No se pudo eliminar el estado de primer plano", it) }
        if (latestStartId > 0) {
            stopSelfResult(latestStartId)
        } else {
            stopSelf()
        }
        Logger.i("ChatGenService", "service stopped")
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, foregroundServiceType: Int) {
        Logger.w(
            "ChatGenService",
            "El servicio de recepción ha alcanzado el límite de tiempo del sistema.,Cancelar la compilación activa y detener el servicio. (startId=$startId, type=$foregroundServiceType)",
        )
        runCatching { stopService() }
            .onFailure { Logger.e("ChatGenService", "No se pudo detener el servicio en primer plano después del tiempo de espera del sistema", it) }
        runCatching { chatGenerationManager.stop() }
            .onFailure { Logger.e("ChatGenService", "No se pudo cancelar la generación después del tiempo de espera del sistema", it) }
    }

    /**
     * v1.112: compatibles Android 14+(SDK 34+)Se inicia el servicio de recepción.
     *
     * Android 14 Se requiere que startForeground se pase explícitamente foregroundServiceType,y debe ser manifest
     * Los tipos declarados son consistentes.(Esta declaración de servicio es dataSync). Se inferirá una sobrecarga de dos parámetros debido a la inferencia de tipos en algunas ROM OEM.
     * Activado en caso de falla ForegroundServiceDidNotStartInTimeException.
     */
    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        observeJob?.cancel()
        emptyStateStopJob?.cancel()
        // v1.0.15: liberar wakelock(Defensivo:stopService liberado,Aquí está el resultado final)
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        // M-009: Cancelar serviceScope,Evite las fugas causadas por corrutinas que aún contienen referencias después de la destrucción del servicio.
        serviceScope.cancel()
        // v2.5.0 fix: Notificación sobre el progreso de la limpieza de la base. —— stopService no se ejecutará cuando el sistema cierre el servicio a la fuerza,
        // En la barra de notificaciones permanecerá una barra de progreso dinámica con el mismo recuento de palabras.(Prueba real del usuario"Salir de la aplicación también mantiene").
        runCatching { notificationManagerOrNull()?.clearLiveProgress() }
            .onFailure { Logger.w("ChatGenService", "No se pudo limpiar la notificación al detener el servicio", it) }
    }

    private fun notificationManagerOrNull(): MuseNotificationManager? =
        runCatching { org.koin.core.context.GlobalContext.getOrNull()?.get<MuseNotificationManager>() }.getOrNull()

    companion object {
        private const val NOTIFICATION_ID = 2001

        /** P1 auditoría: Generar umbral de tiempo de espera de latido(15 Si no hay progreso en minutos, se detendrá el mantenimiento.,Evite la residencia permanente). */
        private const val HEARTBEAT_TIMEOUT_MS = 15 * 60 * 1000L

        /** Breve almacenamiento en búfer de carrera cuando no hay tareas después del reinicio del servicio,Evite START_STICKY el ralentí. */
        private const val EMPTY_STATE_GRACE_MS = 5_000L
        const val ACTION_START = "io.zer0.muse.action.START_GENERATION"
        const val ACTION_STOP = "io.zer0.muse.action.STOP_GENERATION"

        fun start(context: Context) {
            val intent = Intent(context, ChatGenerationService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ChatGenerationService::class.java).apply {
                action = ACTION_STOP
            }
            // Detén el servicio y vete. stopService,Evite volver a llamar a startForegroundService en 5 segundos
            // Activado llamando a startForeground ForegroundServiceDidNotStartInTimeException.
            context.stopService(intent)
        }
    }
}
