package io.zer0.muse.web

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.zer0.muse.BuildConfig
import io.zer0.muse.automation.AutomationInitializer
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.stats.AutoBackupLogDao
import io.zer0.muse.data.stats.AutoBackupLogEntity
import io.zer0.muse.notification.MuseNotificationListenerService
import io.zer0.muse.tools.system.ShizukuAuthorizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Comando de solo lectura del estado del sistema(`system.status`)proyección de carga.
 *
 * El recibo es sólo para "fuente de datos → Transferencia de Protocolo DTO":Sin medición, sin detección,Las partes inalcanzables conservan el valor predeterminado del DTO.
 */
internal object HostSystemStatusReads {

    const val TYPE = "system.status"

    /** `system.status` Acuse de recibo. */
    fun event(status: HostSystemStatus, requestId: String?): HostEvent = HostEvent(
        type = TYPE,
        requestId = requestId,
        systemStatus = status,
    )
}

/**
 * Fuente de datos para comandos de solo lectura del estado del sistema.
 *
 * La interfaz de comisión permite que la puerta de enlace se ejecute sin Android.(Pruebas, montaje mínimo.)También puedes recibir el recibo como es habitual en el escenario.:
 * Ofrece un estado predeterminado completo cuando no está ensamblado,En lugar de permitir que el comando informe un error.
 */
interface HostSystemStatusSource {

    /** Cuando resulte la próxima medición. */
    suspend fun status(): HostSystemStatus

    /** Implementación vacía cuando no está ensamblada:versión/Volumen/copia de seguridad/Todos los bits de habilidad son valores predeterminados.,La batería es nula. */
    object Empty : HostSystemStatusSource {
        override suspend fun status(): HostSystemStatus = HostSystemStatus()
    }
}

/**
 * Estadísticas de bytes ocupados por directorio.
 *
 * ## Calibre
 *
 * solo cuenta [databaseDir] / [mediaDir] / [voiceDir] / [workspaceDir] / [cacheDir] Estos cinco directorios,
 * Sumar cada uno recursivamente;`totalAppBytes` Es la suma de estos cinco elementos. No atravesar toda la zona de pruebas:Registros, volcados de memoria, archivos temporales
 * El total suele ser menos de una fracción de estos directorios.,Pero se necesitan miles de entradas de directorio más,No sirve de nada "ver cuánto está ocupado".
 * Un directorio no existe(Recién instalado y limpiado)Contado como 0.
 *
 * ## hilo
 *
 * El resumen es un recorrido completo por el directorio.,En dispositivos con muchos archivos, puede tardar entre decenas y cientos de milisegundos.,Por lo tanto [measure] fijo en
 * [Dispatchers.IO] correr hasta,No ocupa la corrutina de llamada.(WebSocket Corrutina de conexión)hilo.
 *
 * Se pueden inyectar todos los directorios raíz.:La producción y el montaje van [of],Pruebe al directorio temporal.
 */
internal class HostStorageUsage(
    private val databaseDir: File?,
    private val mediaDir: File?,
    private val voiceDir: File?,
    private val workspaceDir: File?,
    private val cacheDir: File?,
) {

    /** contar una vez;Si algún elemento no se puede leer, se contará como 0.,No se lanza ninguna excepción. */
    suspend fun measure(): HostStorageBytes = withContext(Dispatchers.IO) {
        val database = sizeOf(databaseDir)
        val media = sizeOf(mediaDir)
        val voice = sizeOf(voiceDir)
        val workspace = sizeOf(workspaceDir)
        val cache = sizeOf(cacheDir)
        HostStorageBytes(
            databaseBytes = database,
            mediaBytes = media,
            voiceBytes = voice,
            workspaceBytes = workspace,
            cacheBytes = cache,
            totalAppBytes = database + media + voice + workspace + cache,
        )
    }

    companion object {
        /** Nombre del directorio de imágenes del mensaje,con [WebServer] Los directorios utilizados para registrar rutas de medios son consistentes. */
        private const val IMAGE_MEDIA_DIR = "muse_images"

        /** Nombre del directorio del archivo de voz,con [WebServer] Los directorios utilizados para registrar rutas de medios son consistentes. */
        private const val VOICE_MEDIA_DIR = "voice_messages"

        /** nombre del directorio del espacio de trabajo,con [io.zer0.muse.workspace.WorkspaceManager] El directorio de montaje predeterminado es el mismo. */
        private const val WORKSPACE_DIR = "workspace"

        /** Nombre del archivo de base de datos principal,Sólo se utiliza para razonamiento inverso. `databases/` Directorio. */
        private const val MAIN_DB_NAME = "muse.db"

        /** Producción y montaje:El directorio se toma del entorno limitado de la aplicación. */
        fun of(context: Context): HostStorageUsage = HostStorageUsage(
            databaseDir = context.getDatabasePath(MAIN_DB_NAME).parentFile,
            mediaDir = File(context.filesDir, IMAGE_MEDIA_DIR),
            voiceDir = File(context.filesDir, VOICE_MEDIA_DIR),
            workspaceDir = File(context.filesDir, WORKSPACE_DIR),
            cacheDir = context.cacheDir,
        )
    }

    /** Número de bytes recursivos para un solo directorio;Los directorios que no existen o no son legibles se cuentan como 0. */
    private fun sizeOf(dir: File?): Long {
        val root = dir?.takeIf { it.isDirectory } ?: return 0L
        return runCatching { root.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
    }
}

/**
 * Mapeo del estado de la copia de seguridad.
 *
 * [latest] si `auto_backup_log` El último récord exitoso en;Dos veces cuando no hay registro/El campo de volumen da null ——
 * Dar 0 hará que la página web muestre "Nunca se realizó una copia de seguridad" como "1970 Realizado una copia de seguridad una vez al año,Tamaño 0".
 */
internal fun backupStatusOf(
    latest: AutoBackupLogEntity?,
    autoBackupEnabled: Boolean,
    cloudConfigured: Boolean,
    cloudAutoSync: Boolean,
): HostBackupStatus = HostBackupStatus(
    lastBackupAt = latest?.createdAt?.takeIf { it > 0L },
    lastBackupBytes = latest?.fileSizeBytes,
    autoBackupEnabled = autoBackupEnabled,
    cloudBackupEnabled = cloudConfigured && cloudAutoSync,
)

/**
 * Lea directamente la fuente de datos de estado del sistema del tiempo de ejecución de Android.
 *
 * - Volumen:ver [HostStorageUsage] calibre;
 * - copia de seguridad:Control maestro de programación backend + configuración de respaldo en la nube + `auto_backup_log` El último récord exitoso;
 * - Posición de habilidad:La misma entrada que la herramienta de verificación previa interna de la aplicación.([AutomationManager.liveSnapshot]) —— es solo tiempo real
 *   Accesibilidad con una sobrecarga de actualización mínima,Shell/Root Utilice los últimos resultados de detección,No se ejecuta en una solicitud de página web `su` sonda;
 * - poder:[BatteryManager] capacidad restante + transmisión del estado de carga,Si alguno no se puede leer, devuelve nulo.
 */
internal class AndroidHostSystemStatusSource(
    private val context: Context,
    private val settings: SettingsRepository,
    private val autoBackupLogDao: AutoBackupLogDao,
    private val storage: HostStorageUsage = HostStorageUsage.of(context),
    /** Gerente de Automatización;App Cuando el módulo de automatización no está inicializado, es null,En este momento, los seis bits de habilidad son falsos. */
    private val automation: () -> AutomationManager? = {
        if (AutomationInitializer.isInitialized) AutomationInitializer.manager else null
    },
) : HostSystemStatusSource {

    override suspend fun status(): HostSystemStatus = HostSystemStatus(
        appVersion = BuildConfig.VERSION_NAME,
        appVersionCode = BuildConfig.VERSION_CODE.toLong(),
        uptimeMs = processUptimeMs(),
        storage = storage.measure(),
        backup = backupStatus(),
        capabilities = capabilities(),
        battery = batteryStatus(),
    )

    /** El tiempo que este proceso ha estado ejecutándose.;Si no se puede obtener se contará como 0. */
    private fun processUptimeMs(): Long = runCatching {
        (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()).coerceAtLeast(0L)
    }.getOrDefault(0L)

    private suspend fun backupStatus(): HostBackupStatus {
        val latest = runCatching { autoBackupLogDao.getLatestSuccess() }.getOrNull()
        val scheduleWorkEnabled = runCatching { settings.scheduleWorkEnabledFlow.first() }.getOrDefault(false)
        val cloud = runCatching { settings.cloudBackupConfigFlow.first() }.getOrNull()
        return backupStatusOf(
            latest = latest,
            autoBackupEnabled = scheduleWorkEnabled,
            cloudConfigured = cloud?.isConfigured == true,
            cloudAutoSync = cloud?.autoSync == true,
        )
    }

    private fun capabilities(): HostDeviceCapabilities {
        val state = automation()?.liveSnapshot()
        val shell = state?.shellEnabled == true
        val root = state?.rootEnabled == true
        return HostDeviceCapabilities(
            accessibility = state?.accessibilityEnabled == true,
            shell = shell,
            root = root,
            shizuku = state?.shizukuState == ShizukuAuthorizer.ShizukuState.READY,
            // La puerta de permiso de la pantalla virtual tiene el mismo origen que Shell/Root:Se puede construir una pantalla con uno de estos canales.
            virtualDisplay = shell || root,
            notificationListener = notificationListenerEnabled(),
        )
    }

    /** Aviso de derechos de uso:El servicio de escucha está conectado.,O esta aplicación ha sido marcada en la lista del sistema.(El período de ventana durante el cual el servicio no ha estado vinculado). */
    private fun notificationListenerEnabled(): Boolean = runCatching {
        MuseNotificationListenerService.isConnected() || MuseNotificationListenerService.hasListenerAccess(context)
    }.getOrDefault(false)

    /** poder;Si no se puede leer ni la capacidad ni el estado de carga, todo el bloque será nulo. */
    private fun batteryStatus(): HostBatteryStatus? {
        val manager = runCatching {
            context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        }.getOrNull() ?: return null
        val capacity = runCatching {
            manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrDefault(-1)
        val level = capacity.takeIf { it in 0..100 }
        val charging = chargingState()
        if (level == null && charging == null) return null
        return HostBatteryStatus(level = level, charging = charging)
    }

    /** de `ACTION_BATTERY_CHANGED` La transmisión fija lee el estado de carga.;El estado desconocido se devuelve como nulo. */
    private fun chargingState(): Boolean? {
        // Al pasar nulo, el receptor simplemente consulta al sistema el valor actual de las transmisiones fijas.,No hay remitentes de terceros involucrados,Fijo NOT_EXPORTED;
        // Pero el logo no se puede omitir.:ContextCompat Cuando vea 0, se lanzará una excepción directamente.
        val intent = runCatching {
            ContextCompat.registerReceiver(
                context,
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.getOrNull() ?: return null
        return when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
            BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
            else -> null
        }
    }
}
