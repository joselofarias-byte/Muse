package io.zer0.muse.update

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.notification.MuseNotificationTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.IOException

/**
 * Notificador de actualización de aplicaciones — coordinar [UpdateChecker] con [SettingsRepository]/[MuseNotificationManager].
 *
 * comportamiento:
 *  - de [SettingsRepository.lastUpdateCheckTimeFlow] Leer la hora del último control,Saltar si el intervalo es inferior a 24 horas
 *    (forceCheck=true obligatorio)
 *  - llamar [UpdateChecker.checkLatestRelease] Tira lo último Release
 *  - Comparar con la semántica actual del nombre de la versión,Cuando se descubre una nueva versión:
 *      · poner [UpdateChecker.ReleaseInfo] Serializado en JSON almacenado en caché en
 *        [SettingsRepository.latestReleaseInfoFlow](Para que se lea el banner de la interfaz de usuario)
 *      · Pase [MuseNotificationManager] Ya tengo canales para enviar notificaciones.(Reutilizar CHANNEL_CHAT_COMPLETED)
 *  - Buscar actualizaciones lastUpdateCheckTime
 *
 * @param settings Almacén de configuración global
 * @param checker GitHub Releases inspector
 */
sealed interface UpdateCheckResult {
    data class NewVersion(val release: UpdateChecker.ReleaseInfo) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data class Failed(val message: String) : UpdateCheckResult
    data object Skipped : UpdateCheckResult
}

/** Convertir los resultados de la consulta de red en un estado explícito consumible por la interfaz de usuario; Lógica pura para pruebas fáciles. */
internal fun classifyUpdateResult(currentVersion: String, result: io.zer0.common.Result<UpdateChecker.ReleaseInfo>): UpdateCheckResult {
    val release = result.getOrNull() ?: return UpdateCheckResult.Failed(
        (result as? io.zer0.common.Result.Error)?.message ?: "unknown",
    )
    return if (UpdateChecker.compareVersions(currentVersion, release.tagName) >= 0) {
        UpdateCheckResult.UpToDate
    } else {
        UpdateCheckResult.NewVersion(release)
    }
}

class UpdateNotifier(
    private val settings: SettingsRepository,
    private val checker: UpdateChecker,
) {

    /**
     * Consulta y recibe notificaciones de las actualizaciones.
     *
     * @param context Se utiliza para leer el nombre de la versión + enviar notificación
     * @param forceCheck true Omita la verificación de intervalo de 24 horas cuando(Se utiliza cuando el usuario lo activa manualmente)
     */
    suspend fun checkAndNotify(context: Context, forceCheck: Boolean = false): UpdateCheckResult {
        // Los usuarios pueden desactivar el registro automático en la configuración;Verificación manual(forceCheck=true)todavía permitido
        val enabled = settings.updateCheckEnabledFlow.firstSafe() ?: true
        val allowedByInterval = forceCheck || (enabled && shouldCheck())
        if (!forceCheck && !enabled) {
            Logger.i(TAG, "update check disabled, skip")
        } else if (!allowedByInterval) {
            Logger.d(TAG, "update check interval not elapsed, skip")
        }
        return if ((!enabled && !forceCheck) || !allowedByInterval) {
            UpdateCheckResult.Skipped
        } else {
            performCheck(context)
        }
    }

    /** Dos entradas manuales comparten este método para unificar las escrituras de caché y la red esperada./Mapeo de errores persistentes. */
    suspend fun checkManually(context: Context): UpdateCheckResult = try {
        checkAndNotify(context, forceCheck = true)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: IOException) {
        Logger.w(TAG, "manual update check failed: ${error.message}", error)
        UpdateCheckResult.Failed(error.message ?: "network error")
    }

    private suspend fun performCheck(context: Context): UpdateCheckResult {
        // Tiempo récord de inspección(ya sea exitoso o no,Evite volver a intentarlo repetidamente cuando el error provoque llamadas excesivamente frecuentes a la API de GitHub.)
        settings.saveLastUpdateCheckTime(System.currentTimeMillis())
        val currentVersion = getCurrentVersionName(context)
        val outcome = classifyUpdateResult(currentVersion, checker.checkLatestRelease())
        return when (outcome) {
            is UpdateCheckResult.Failed -> {
                Logger.w(TAG, "checkLatestRelease failed: ${outcome.message}")
                outcome
            }
            UpdateCheckResult.Skipped -> outcome
            UpdateCheckResult.UpToDate -> {
                // Versión actual >= última versión,Borrar caché ReleaseInfo(Banner No volver a mostrar)
                settings.saveLatestReleaseInfo(null)
                Logger.i(TAG, "already up to date: current=$currentVersion")
                outcome
            }
            is UpdateCheckResult.NewVersion -> {
                val release = outcome.release
                Logger.i(TAG, "new version found: current=$currentVersion, latest=${release.tagName}")
                // caché ReleaseInfo,UI Representado a través de LatestReleaseInfoFlow Banner
                settings.saveLatestReleaseInfo(
                    AppJson.encodeToString(UpdateChecker.ReleaseInfo.serializer(), release),
                )
                notifyNewVersion(context, release)
                outcome
            }
        }
    }

    /**
     * Determina si han pasado más de 24 horas desde el último control.
     */
    private suspend fun shouldCheck(): Boolean {
        val lastTime = settings.lastUpdateCheckTimeFlow.firstSafe() ?: 0L
        if (lastTime <= 0L) return true
        val elapsed = System.currentTimeMillis() - lastTime
        return elapsed >= CHECK_INTERVAL_MILLIS
    }

    /**
     * mostrar"nueva versión encontrada"Notificación — Reutilice el canal CHANNEL_CHAT_COMPLETED de MuseNotificationManager.
     *
     * Título de la notificación:"nueva versión encontrada"
     * Texto de notificación:"<tagName> Publicado,Haga clic para ver detalles"
     * Haz clic para saltar MainActivity(El usuario hace clic en el Banner dentro de la aplicación para completar la descarga del APK./Ver detalles).
     */
    private fun notifyNewVersion(context: Context, release: UpdateChecker.ReleaseInfo) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val title = context.getString(R.string.update_notif_title)
        val text = context.getString(R.string.update_notif_text, release.tagName)
        val notif = NotificationCompat.Builder(context, MuseNotificationManager.CHANNEL_CHAT_COMPLETED)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(
                MuseNotificationManager(context).buildMainActivityPendingIntent(
                    MuseNotificationTarget.SettingsAbout,
                ),
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        resultOf { nm.notify(NOTIF_ID_NEW_VERSION, notif) }
            .onError { msg, _ -> Logger.w(TAG, "notify new version failed: $msg") }
    }

    companion object {
        private const val TAG = "UpdateNotifier"

        /** Notificación ID(Evite conflictos con el ID existente de MuseNotificationManager,Seleccione 9000 segmentos). */
        private const val NOTIF_ID_NEW_VERSION = 9001

        /** Verificar automáticamente el intervalo mínimo:24 horas. */
        private const val CHECK_INTERVAL_MILLIS = 24L * 60 * 60 * 1000

        /**
         * método de conveniencia:Comparación de versiones semánticas — confiado a [UpdateChecker.compareVersions].
         */
        fun compareVersions(current: String, latest: String): Int = UpdateChecker.compareVersions(current, latest)

        /**
         * v1.0.72: Leer la solicitud actual versionName(Promovido de privado a companion,Para que la interfaz de usuario llame estáticamente).
         * Devoluciones de errores de lectura "0",Asegúrese de que las comparaciones posteriores sean ejecutables.
         */
        fun getCurrentVersionName(context: Context): String {
            return resultOf {
                val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        android.content.pm.PackageManager.PackageInfoFlags.of(0),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
                packageInfo.versionName ?: "0"
            }.getOrNull() ?: "0"
        }

        /**
         * Página de descarga del sitio web oficial. — Todas las descargas dentro de la aplicación./Actualice las exportaciones para que apunten aquí.
         *
         * ¿Por qué "Descargar ahora" va primero al sitio web oficial en lugar de vincularse directamente al APK? El sitio web oficial utilizará la lista de recursos de la versión actual.
         * Se proporciona un botón correspondiente a la arquitectura según el modelo, y el usuario puede descargarlo directamente desde el enlace del activo de GitHub con un clic; juzgue la arquitectura usted mismo dentro de la aplicación
         * Por el contrario, es fácil seleccionar el paquete incorrecto (especialmente en máquinas mixtas de 32/64 bits). La página de descarga del sitio web oficial no almacena el paquete de instalación en sí.
         */
        const val OFFICIAL_DOWNLOAD_PAGE = "https://github.com/joselofarias-byte/Muse/releases/latest"

        /**
         * Construya un método utilizado para abrir la página de descarga del sitio web oficial. [Intent](ACTION_VIEW).
         * La persona que llama es responsable de startActivity/chooser.
         */
        fun buildOfficialDownloadIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(OFFICIAL_DOWNLOAD_PAGE))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/**
 * Flow.first() Embalaje conveniente — Retorno del fracaso null,Evite la propagación de excepciones ascendentes(Escenario simple para leer elementos de configuración.).
 * CancellationException Todavía tirando hacia arriba(La cancelación de rutina no se puede tragar).
 */
private suspend fun <T> Flow<T>.firstSafe(): T? = try {
    first()
} catch (t: Throwable) {
    if (t is kotlin.coroutines.cancellation.CancellationException) throw t
    Logger.w("UpdateNotifier", "Flow.first() failed: ${t.message}")
    null
}
