package io.zer0.muse.terminal

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.zer0.common.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * v2.2.1 Termux canal — Enviar comandos al usuario instalado Termux(Entorno Linux completo)Ejecute y obtenga los resultados.
 *
 * mecanismo(Alineado con el protocolo RUN_COMMAND oficial de Termux):
 *  - hacia `com.termux.app.RunCommandService` enviar action=`com.termux.RUN_COMMAND` intención;
 *  - Pase `com.termux.RUN_COMMAND_PENDING_INTENT` Obtener resultados(stdout/stderr/exitCode/errmsg);
 *  - `am startservice` El método no puede obtener el resultado.,Por lo tanto, se debe seguir la ruta del código.
 *
 * Frente del lado del usuario(No listo [describe] Dar orientación paso a paso):
 *  1. Instalación Termux;
 *  2. Configuración del sistema→Solicitud→Termux→Permisos adicionales,Conceder "Ejecutar comando Termux"(`com.termux.permission.RUN_COMMAND`);
 *  3. Termux dentro `~/.termux/termux.properties` escribir `allow-external-apps = true` y reiniciar Termux;
 *  4. sistema parcial(Android 10+)Es necesario permitir que Termux se inicie externamente(Como la opción "Aplicación asociada").
 *
 * Descripción:Los nombres de los campos del paquete de resultados corresponden a las constantes oficiales de Termux.;Analizar la compatibilidad defensiva(stdout/stderr/exitCode/err/errmsg,
 * Compatible con variantes exit_code/errCode),Los formatos desconocidos registrarán el conjunto de claves original para facilitar la resolución de problemas.
 */
class TermuxChannel private constructor(private val context: Context) {

    /** Disponibilidad de canales. */
    sealed class Availability {
        data object Ready : Availability()
        data object NotInstalled : Availability()
        data object PermissionMissing : Availability()
        data class NotConfigured(val detail: String?) : Availability()
    }

    /** Resultado de ejecución único.error No vacío indica falla a nivel de canal(El inicio falló/tiempo de espera/Servidor errmsg). */
    data class ExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val error: String?,
    )

    private val pending = ConcurrentHashMap<Int, CompletableDeferred<TermuxResult>>()
    private val nextRequestId = AtomicInteger(0)
    private val receiverLock = Any()

    @Volatile
    private var receiverRegistered = false

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent == null) return
            val requestId = intent.getIntExtra(EXTRA_REQUEST_ID, -1)
            // Los extras en la banda de transmisión se repetirán en el registro tal como están, pero los resultados sin identificadores solo se perderán:
            // Los resultados sin propietario son peores que perder uno (lanzar los resultados de otra persona a la persona que llama).
            val deferred = pending.remove(requestId) ?: return
            // Termux 0.118.x Fijo "result" Como clave del paquete de resultados, el nombre largo solo está reservado por motivos de compatibilidad.
            val bundle = intent.getBundleExtra(EXTRA_RESULT_BUNDLE)
                ?: intent.getBundleExtra(EXTRA_RESULT_BUNDLE_TERMUX)
            if (bundle == null) {
                Logger.w(TAG, "Termux falta el resultado result bundle,extras=${intent.extras?.keySet()}")
                deferred.complete(TermuxResult(-1, "", "", "Formato de resultado anormal(desaparecido result bundle)"))
                return
            }
            val entries = HashMap<String, Any?>()
            for (key in bundle.keySet()) {
                @Suppress("DEPRECATION")
                entries[key] = bundle.get(key)
            }
            deferred.complete(parseResultEntries(entries))
        }
    }

    /** Comprobación estática rápida(No ejecutar el comando). */
    fun availability(): Availability {
        if (!isInstalled()) return Availability.NotInstalled
        if (!hasPermission()) return Availability.PermissionMissing
        return Availability.Ready
    }

    fun isInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(PKG, 0)
    }.isSuccess

    fun hasPermission(): Boolean = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * Detección completa de enlaces:Ejecutar realmente un comando de etiqueta de eco,Verifique configuraciones como permitir aplicaciones externas.
     * que [availability] lento(Primero debe iniciar el servicio Termux),Para visualización de estado/Uso de solución de problemas.
     */
    suspend fun probe(): Availability {
        val quick = availability()
        if (quick !is Availability.Ready) return quick
        val result = exec("echo __MUSE_TERMUX_OK__", timeoutMs = 15_000L)
        return when {
            result.error != null -> Availability.NotConfigured(result.error)
            result.stdout.contains("__MUSE_TERMUX_OK__") -> Availability.Ready
            else -> Availability.NotConfigured("Detectar anomalías de salida(exit=${result.exitCode})")
        }
    }

    /**
     * Ejecute el comando y espere los resultados.
     * @param command a `bash -c` Comando completo ejecutado
     * @param timeoutMs Esperar al límite superior(Incluyendo el tiempo de cola lateral de Termux)
     * @param workdir directorio de trabajo(Predeterminado Termux home)
     */
    suspend fun exec(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS, workdir: String? = null): ExecResult {
        if (!isInstalled()) return ExecResult(-1, "", "", "No instalado Termux")
        if (!hasPermission()) return ExecResult(-1, "", "", "Falta el permiso de llamada de Termux")
        ensureReceiver()
        val requestId = nextRequestId.incrementAndGet()
        val deferred = CompletableDeferred<TermuxResult>()
        pending[requestId] = deferred
        try {
            context.startService(buildRunCommandIntent(command, workdir, requestId))
        } catch (e: Exception) {
            pending.remove(requestId)
            Logger.w(TAG, "No se pudo iniciar el servicio Termux: ${e.message}")
            val raw = e.message ?: e.javaClass.simpleName
            // Android 8+ Restricciones de inicio del servicio en segundo plano:App El sistema rechazará el inicio de servicios externos en segundo plano.
            val hint = if (raw.contains("Not allowed to start service") || raw.contains("background")) {
                "(Android Restricciones de fondo:Mantén la aplicación en primer plano.,O abra \"Configuración→Agent→Backend y confiabilidad→Sigue ejecutándose en segundo plano\")"
            } else {
                ""
            }
            return ExecResult(-1, "", "", "No se pudo iniciar el servicio Termux: $raw$hint")
        }
        val result = try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(requestId)
        }
        if (result == null) {
            return ExecResult(-1, "", "", "Tiempo de espera esperando resultado(${timeoutMs / 1000}s):Termux Es posible que el sistema impida que se inicie en segundo plano o no responda.")
        }
        return ExecResult(result.exitCode, result.stdout, result.stderr, result.error)
    }

    /**
     * Registre un receptor de transmisión para recibir resultados de Termux.
     *
     * v2.6.13 Solución: se debe utilizar RECEIVER_EXPORTED.
     *
     * La implementación anterior declaraba RECEIVER_NOT_EXPORTED, mientras que la transmisión del resultado era **Termux esa aplicación** enviado de vuelta
     * (diferente fluido, diferente proceso). Los receptores no exportados solo aceptan transmisiones desde la misma aplicación, por lo que la respuesta de Termux
     * El sistema lo descarta silenciosamente cada vez.[exec] Espera por siempre hasta que se agote el tiempo —— Lo que ve el usuario es“Autorizado pero completamente inutilizable.”,
     * Y todas las comprobaciones previas (instaladas/autorizadas/permitir aplicaciones externas) son normales.
     *
     * El límite de seguridad se mantiene mediante dos restricciones:
     *  1. La transmisión del resultado debe tomar [EXTRA_REQUEST_ID], y el id debe estar en la tabla pendiente de este proceso——
     *     Las aplicaciones externas no pueden adivinar la identificación incrementada automáticamente y no pueden falsificar identificaciones desconocidas.“resultado”;
     *  2. El contenido recibido solo se genera como una cadena, sin llamadas de reflexión, ejecución ni ortografía de ruta.
     *
     * ¿Por qué no puedo cambiar a la versión del sistema? flags = 0:
     * [ContextCompat.registerReceiver] en flags **No contiene RECEIVER_EXPORTED ni
     * RECEIVER_NOT_EXPORTED** tirar incondicionalmente `IllegalArgumentException`, con `Build.VERSION.SDK_INT`
     * irrelevante (implementación antigua `SDK_INT >= TIRAMISU` Se consideró que Android 12 y versiones anteriores deberían tomar la rama 0, por lo que en
     * vivo V1990A / Android 10 melodía anterior [exec] Simplemente colapsar). Exportar o no no tiene nada que ver con la versión del sistema. Es el único requisito estricto.
     * Así que pase RECEIVER_EXPORTED aquí constantemente y no haga bifurcaciones por versión.
     */
    private fun ensureReceiver() {
        if (receiverRegistered) return
        synchronized(receiverLock) {
            if (receiverRegistered) return
            ContextCompat.registerReceiver(
                context,
                resultReceiver,
                IntentFilter(ACTION_RESULT),
                ContextCompat.RECEIVER_EXPORTED,
            )
            receiverRegistered = true
        }
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    internal fun buildRunCommandIntent(command: String, workdir: String?, requestId: Int): Intent {
        // requestId Debe adjuntarse al Intent básico de PendingIntent y no se puede colocar solo en el Intent del comando:
        // Termux Al regresar, solo se repondrá el paquete de resultados como complemento y no se recuperará el extra personalizado de la persona que llama.
        // Por lo tanto, el extremo receptor nunca puede encontrar la tabla pendiente presionando -1, y el resultado se descarta silenciosamente y solo puede esperar el tiempo de espera.
        val resultIntent = Intent(ACTION_RESULT)
            .setPackage(context.packageName)
            .putExtra(EXTRA_REQUEST_ID, requestId)
        val mutability = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestId,
            resultIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutability,
        )
        return Intent().apply {
            setClassName(PKG, SERVICE_CLASS)
            action = ACTION_RUN
            putExtra(EXTRA_PATH, BASH_PATH)
            putExtra(EXTRA_ARGUMENTS, arrayOf("-c", command))
            putExtra(EXTRA_WORKDIR, workdir ?: HOME_DIR)
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_SESSION_ACTION, "0")
            putExtra(EXTRA_PENDING_INTENT, pendingIntent)
            putExtra(EXTRA_REQUEST_ID, requestId)
        }
    }

    companion object {
        private const val TAG = "TermuxChannel"

        const val PKG = "com.termux"
        const val SERVICE_CLASS = "com.termux.app.RunCommandService"
        const val ACTION_RUN = "com.termux.RUN_COMMAND"
        const val PERMISSION = "com.termux.permission.RUN_COMMAND"
        const val BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"
        const val HOME_DIR = "/data/data/com.termux/files/home"

        private const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
        private const val EXTRA_RESULT_BUNDLE = "com.termux.RUN_COMMAND_RESULT_BUNDLE"

        /** Termux 0.118.x La clave del paquete de resultados real utilizada (TermuxPluginUtils Escritura fija). */
        private const val EXTRA_RESULT_BUNDLE_TERMUX = "result"
        private const val EXTRA_REQUEST_ID = "io.zer0.muse.TERMUX_REQUEST_ID"
        private const val ACTION_RESULT = "io.zer0.muse.action.TERMUX_RESULT"

        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val MAX_TIMEOUT_MS = 600_000L

        @Volatile
        private var instance: TermuxChannel? = null

        fun get(context: Context): TermuxChannel = instance ?: synchronized(this) {
            instance ?: TermuxChannel(context.applicationContext).also { instance = it }
        }

        /** Estado intermedio del resultado de la ejecución. */
        data class TermuxResult(
            val exitCode: Int,
            val stdout: String,
            val stderr: String,
            val error: String?,
        )

        /**
         * Campos de resultados del análisis defensivo(Se puede probar individualmente):Compatible con código de salida/código_salida,err/errCode con valores de cadena.
         */
        internal fun parseResultEntries(entries: Map<String, Any?>): TermuxResult {
            val stdout = (entries["stdout"] as? String).orEmpty()
            val stderr = (entries["stderr"] as? String).orEmpty()
            val exitCode = when (val v = entries["exitCode"] ?: entries["exit_code"]) {
                is Number -> v.toInt()
                is String -> v.toIntOrNull() ?: -1
                else -> -1
            }
            val errCode = when (val v = entries["err"] ?: entries["errCode"]) {
                is Number -> v.toInt()
                is String -> v.toIntOrNull()
                else -> null
            }
            val errmsg = (entries["errmsg"] as? String)?.takeIf { it.isNotBlank() }
            // Termux El código de éxito es Errno.ERRNO_SUCCESS = -1,ResultData.getErrCode() Devuelve -1 si no hay errores.
            // tomar “!= 0” La sentencia escribirá cada éxito como "Termux Código de error de servicio -1", el canal parece no estar disponible permanentemente.
            // Sólo los números positivos son verdaderos errores;0 Dáselo a la versión antigua./Otras implementaciones actúan como códigos de éxito.
            val error = errmsg ?: if (errCode != null && errCode > 0) "Termux Código de error de servicio $errCode" else null
            return TermuxResult(exitCode, stdout, stderr, error)
        }

        /** Orientado al usuario/Copia introductoria del modelo(Chino codificado,El mismo calibre que device_shell y otras indicaciones en tiempo de ejecución). */
        fun describe(availability: Availability): String = when (availability) {
            is Availability.Ready -> "Termux canal listo"
            is Availability.NotInstalled ->
                "Termux canal no disponible:Termux no está instalado. Por favor instale primero Termux(tienda de aplicaciones / F-Droid),Después de la instalación, regrese a Configuración→Detección de herramientas."
            is Availability.PermissionMissing ->
                "Termux El canal espera autorización:Por favor vaya a la configuración del sistema→Solicitud→Termux→Permisos adicionales,Marque \"Ejecutar comando Termux\"" +
                    "(Run commands in Termux environment),Luego inténtalo de nuevo."
            is Availability.NotConfigured ->
                "Termux Canal a configurar:Por favor ejecute en Termux " +
                    "`echo 'allow-external-apps = true' >> ~/.termux/termux.properties` y reiniciar Termux;" +
                    "sistema parcial(Android 10+)También debe permitir que Termux se inicie externamente(Como la opción \"Aplicación asociada\")." +
                    "\núltimo error:${availability.detail ?: "desconocido"}"
        }
    }
}
