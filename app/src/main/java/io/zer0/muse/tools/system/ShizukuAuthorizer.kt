package io.zer0.muse.tools.system

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import io.zer0.common.Logger
import io.zer0.muse.BuildConfig
import io.zer0.muse.tools.TOOL_OUTPUTS_DIR
import io.zer0.muse.tools.ToolOutputCapture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * P3-3: Shizuku Autorizador — Integre rikk.shizuku:api SDK.
 *
 * Shizuku con privilegios de shell(ADB nivel)ejecutar comando,No es necesario root,Es un enrutador de tres canales.
 * El equilibrio entre seguridad y permisos(Más seguro que root,Mejor que una ejecución sin barreras shell).
 *
 * La ejecución del comando adopta el modo UserService(Shizuku v13 Plan recomendado):
 *  1. definición [IShellService] AIDL interfaz
 *  2. [ShellService] Se ejecuta en un proceso separado administrado por Shizuku.,Heredar permisos de shell
 *  3. [Shizuku.bindUserService] Servicios de encuadernación,Ejecutar comandos a través de AIDL IPC
 *
 * Responsabilidades:
 *  1. [isAvailable]: Shizuku ¿Está funcionando el servicio?(pingBinder)
 *  2. [checkPermission]: Si la solicitud está autorizada por Shizuku
 *  3. [requestPermission]: Aparece la solicitud de autorización de Shizuku(suspend,Esperar la acción del usuario)
 *  4. [isSuiBackendAvailable]: Sui Compatible con el servidor(root Los usuarios no necesitan instalar Shizuku,Sui Proporcionar la interfaz Shizuku como root)
 *  5. [execute]: Ejecutar comandos con privilegios de shell(Pase UserService)
 *
 * ciclo de vida:
 *  - Shizuku El usuario puede detener el servicio mientras se está ejecutando.,Antes de cada ejecución, debes [checkPermission] Vuelva a comprobar
 *  - UserService por primera vez [execute] Encuadernación perezosa,Reconectarse automáticamente después de la desconexión
 *  - Los oyentes de autorización se eliminan automáticamente una vez completada la solicitud.,evitar fugas
 */
class ShizukuAuthorizer(
    private val context: Context,
    /**
     * Sonda de bit de autorización inyectable(Ir por defecto Shizuku SDK checkSelfPermission).
     * Los puntos de inyección se proporcionan en pruebas individuales.,Fácil de bloquear "no autorizado" → execute Nunca active el servicio Shell"
     * camino de cortocircuito,No se requiere servicio Robolectric/Shizuku real.
     */
    private val checkPermissionProbe: (() -> Boolean)? = null,
) {

    companion object {
        private const val TAG = "ShizukuAuthorizer"

        /** Código de solicitud de autorización(arbitrario,Para la coincidencia de devolución de llamada de OnRequestPermissionResultListener). */
        private const val PERMISSION_REQUEST_CODE = 0xC3A3

        /** Sui(Magisk módulo)nombre del paquete,Proporciona una interfaz compatible con Shizuku. */
        private const val SUI_PACKAGE = "moe.shizuku.privileged.sui"

        /** UserService Tiempo de espera de enlace(milisegundos). */
        private const val SERVICE_BIND_TIMEOUT_MS = 3000L

        /** UserService Vincular el intervalo de sondeo(milisegundos). */
        private const val SERVICE_BIND_POLL_MS = 50L

        /** UserService Identificación estable para evitar errores de valoración de la identidad del servicio por nombre de clase después de R8/ofuscación. */
        private const val SHELL_SERVICE_TAG = "muse_shell"

        /** UserService versión del protocolo;AIDL/Forzar el reemplazo de procesos antiguos cuando cambia la implementación del servicio. */
        private const val SHELL_SERVICE_VERSION = 4

        /** R-SVC-03: La ventana emergente de autorización ha expirado.(milisegundos). */
        private const val PERMISSION_TIMEOUT_MS = 60_000L

        /** UserService Breve ventana de retroceso después de un fallo de encuadernación. */
        private const val BIND_FAILURE_BACKOFF_MS = 5_000L
    }

    /** Vincular agente de servicio de shell(puede ser null,significa no obligado). */
    @Volatile
    private var shellService: IShellService? = null

    /** R-SVC-03: Actualmente vinculado UserServiceArgs(unbind cuando sea necesario). */
    @Volatile
    private var boundArgs: Shizuku.UserServiceArgs? = null

    /** UserService Estado vinculante. */
    @Volatile
    private var serviceBound = false

    /** Serialice la vinculación/desvinculación para evitar que múltiples llamadas automáticas creen múltiples servicios de usuario al mismo tiempo. */
    private val serviceMutex = Mutex()

    // Realice un breve retroceso cuando el enlace falle para evitar reorganizar la página de configuración cada vez/La recuperación se bloqueará repetidamente durante 3 segundos y actualizará la pantalla.
    @Volatile
    private var lastBindFailureAt = 0L

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val proxy = service?.let(IShellService.Stub::asInterface)
            if (proxy == null) {
                shellService = null
                serviceBound = false
                Logger.e(TAG, "Shell UserService La devolución de llamada de conexión no devolvió un mensaje válido binder")
                return
            }
            shellService = proxy
            serviceBound = true
            lastBindFailureAt = 0L
            Logger.i(TAG, "Shell UserService Conectado")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            serviceBound = false
            boundArgs = null
            Logger.w(TAG, "Shell UserService Conexión perdida")
        }
    }

    /** Shizuku ¿Está funcionando el servicio?(Proceso iniciado + carpeta accesible). */
    fun isAvailable(): Boolean {
        return try {
            !Shizuku.isPreV11() && Shizuku.pingBinder()
        } catch (e: Throwable) {
            // Tolerancia a fallos necesaria:Shizuku Llamar al SDK cuando no está instalado puede generar una excepción.
            Logger.w(TAG, "Shizuku.pingBinder falló: ${e.message}")
            false
        }
    }

    /** Si la solicitud está autorizada por Shizuku(Necesito primero [isAvailable]). */
    fun checkPermission(): Boolean {
        checkPermissionProbe?.let { return it() }
        if (!isAvailable()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            Logger.w(TAG, "Shizuku.checkSelfPermission falló: ${e.message}")
            false
        }
    }

    /**
     * Solicitar autorización de Shizuku(suspend,Espere a que el usuario opere en la ventana emergente de autorización).
     * @return true Autorizado;false Servicio denegado o no disponible
     */
    suspend fun requestPermission(): Boolean {
        if (!isAvailable()) return false
        if (checkPermission()) return true
        return try {
            withTimeout(PERMISSION_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val listener = object : Shizuku.OnRequestPermissionResultListener {
                        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                            if (requestCode == PERMISSION_REQUEST_CODE) {
                                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                                // El oyente debe ser eliminado,Evite filtraciones y devoluciones de llamadas duplicadas
                                Shizuku.removeRequestPermissionResultListener(this)
                                if (cont.isActive) cont.resume(granted)
                            }
                        }
                    }
                    Shizuku.addRequestPermissionResultListener(listener)
                    cont.invokeOnCancellation {
                        Shizuku.removeRequestPermissionResultListener(listener)
                    }
                    try {
                        Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
                    } catch (e: Throwable) {
                        Shizuku.removeRequestPermissionResultListener(listener)
                        Logger.w(TAG, "Shizuku.requestPermission falló: ${e.message}")
                        if (cont.isActive) cont.resume(false)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Logger.w(TAG, "Shizuku Tiempo de espera de autorización")
            false
        }
    }

    /**
     * Sui Detección de compatibilidad de backend.
     *
     * Sui es un módulo Magisk,Proporciona una interfaz compatible con Shizuku como root,root Los usuarios no necesitan instalar Shizuku APK.
     * Método de detección:Sui Paquete instalado && root Disponible(Descripción Sui se ejecuta como root).
     */
    fun isSuiBackendAvailable(): Boolean {
        val suiInstalled = try {
            context.packageManager.getPackageInfo(SUI_PACKAGE, 0) != null
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        // Sui Requiere root para funcionar
        return suiInstalled && RootAuthorizer().checkPermission()
    }

    /**
     * Devuelve el verdadero estado de la habilidad de Shizuku que se puede mostrar en la página de configuración.
     * “Autorizado”Esto no significa que el UserService de Muse ya esté disponible, por lo que los resultados vinculantes se verifican por separado aquí.
     */
    suspend fun diagnose(): ShizukuStatus {
        val installed = ShizukuInstaller(context).isInstalled()
        val suiAvailable = if (!installed) isSuiBackendAvailable() else false
        if (!installed && !suiAvailable) {
            return ShizukuStatus(ShizukuState.NOT_INSTALLED, "No instalado Shizuku")
        }
        if (!isAvailable()) {
            return ShizukuStatus(
                ShizukuState.NOT_RUNNING,
                if (suiAvailable) "Sui Instalado,Pero el servicio Shizuku no está funcionando." else "Shizuku El servicio no está funcionando",
            )
        }
        if (!checkPermission()) return ShizukuStatus(ShizukuState.NOT_AUTHORIZED, "Aún no autorizado Muse")
        if (!ensureServiceBound()) {
            return ShizukuStatus(ShizukuState.USER_SERVICE_UNAVAILABLE, "Muse Shell El servicio no está disponible temporalmente")
        }
        return ShizukuStatus(ShizukuState.READY, "Shizuku Shell Listo")
    }

    /**
     * Ejecutar comandos con privilegios de shell(Necesita estar autorizado).
     *
     * darse cuenta:Vincular a través de Shizuku.bindUserService [ShellService],Ejecutado en un proceso con privilegios de shell.
     * Encuadernación diferida en la primera llamada,Reutilización posterior de servicios vinculados.
     *
     * @return [ShizukuExecResult] Contiene código de salida y resultados.
     */
    suspend fun execute(command: String): ShizukuExecResult {
        return withContext(Dispatchers.IO) {
            if (!checkPermission()) return@withContext ShizukuExecResult(-1, "", "Shizuku No autorizado")
            if (!ensureServiceBound()) return@withContext ShizukuExecResult(-1, "", "Shizuku shell service Error al vincular")
            val service = shellService ?: return@withContext ShizukuExecResult(-1, "", "Shizuku shell service No disponible")
            try {
                val raw = service.execute(command)
                parseExecResult(raw)
            } catch (e: android.os.RemoteException) {
                Logger.e(TAG, "Shizuku Falló la llamada remota: ${e.message}", e)
                // La falla de la llamada remota puede deberse a la falla del proceso de servicio.,Borra la referencia para que puedas volver a conectarte la próxima vez.
                release()
                ShizukuExecResult(-1, "", e.message ?: "Excepción de llamada remota")
            } catch (e: Throwable) {
                Logger.e(TAG, "Shizuku excepción de ejecución: ${e.message}", e)
                ShizukuExecResult(-1, "", e.message ?: "excepción de ejecución")
            }
        }
    }

    /** Stream device-tool output through a pipe into app-private storage, avoiding Binder String caps. */
    suspend fun executeForTool(command: String): ShizukuExecResult = withContext(Dispatchers.IO) {
        if (!checkPermission()) return@withContext ShizukuExecResult(-1, "", "Shizuku No autorizado")
        if (!ensureServiceBound()) {
            return@withContext ShizukuExecResult(-1, "", "Shizuku shell service Error al vincular")
        }
        val service = shellService ?: return@withContext ShizukuExecResult(-1, "", "Shizuku shell service No disponible")
        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (error: Exception) {
            return@withContext ShizukuExecResult(-1, "", "No se pudo crear la tubería de salida del shell: ${error.message}")
        }
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        val capture = ToolOutputCapture(File(context.filesDir, TOOL_OUTPUTS_DIR), "shizuku_command")
        val readerFailure = AtomicReference<Throwable?>(null)
        val reader =
            Thread(
                {
                    try {
                        ParcelFileDescriptor.AutoCloseInputStream(readEnd).use { input ->
                            InputStreamReader(input, Charsets.UTF_8).buffered().use { stream ->
                                val buffer = CharArray(8_192)
                                while (true) {
                                    val count = stream.read(buffer)
                                    if (count < 0) break
                                    capture.append(String(buffer, 0, count))
                                }
                            }
                        }
                    } catch (error: Throwable) {
                        readerFailure.set(error)
                    }
                },
                "muse-shizuku-output",
            )
        reader.start()

        var exitCode = -1
        var remoteFailure: Throwable? = null
        try {
            exitCode = service.executeToPipe(command, writeEnd)
        } catch (error: android.os.RemoteException) {
            Logger.e(TAG, "Shizuku Error en la transmisión de llamada remota: ${error.message}", error)
            remoteFailure = error
            release()
        } catch (error: Throwable) {
            Logger.e(TAG, "Shizuku Excepción de ejecución de transmisión: ${error.message}", error)
            remoteFailure = error
        } finally {
            runCatching { writeEnd.close() }
        }

        reader.join()
        val failure = readerFailure.get()
        if (failure != null) {
            remoteFailure = remoteFailure ?: failure
        }
        if (exitCode < 0 && remoteFailure == null) {
            remoteFailure = IllegalStateException("Shizuku Se agotó el tiempo de espera del comando o no se pudo ejecutar")
        }
        val output = if (reader.isAlive) {
            capture.close()
            ""
        } else {
            runCatching {
                capture.finish(
                    header = if (remoteFailure != null) {
                        "Shizuku El resultado no se pudo recibir en su totalidad; el siguiente contenido puede estar incompleto"
                    } else {
                        ""
                    },
                    emptyMessage = "(Sin salida)",
                )
            }
                .getOrElse {
                    capture.close()
                    ""
                }
        }
        val error = remoteFailure?.message
        ShizukuExecResult(
            exitCode = if (remoteFailure == null) exitCode else -1,
            stdout = output,
            stderr = error.orEmpty(),
        )
    }

    // ── herramientas internas ──────────────────────────────────────────────────────────────

    /** R-SVC-03: Desvincular UserService y limpiar referencias(App Salir/Llamado cuando el canal está cerrado.). */
    fun release() {
        val args = boundArgs
        if (args != null) {
            try {
                Shizuku.unbindUserService(args, serviceConnection, true)
            } catch (e: Throwable) {
                Logger.w(TAG, "unbindUserService falló: ${e.message}")
            }
        }
        shellService = null
        serviceBound = false
        boundArgs = null
    }

    /**
     * Asegúrese de que UserService esté vinculado.
     * - Atado:Regresar directamente true
     * - no obligado:llamar [Shizuku.bindUserService],Sondeo esperando conexión(hasta 3 segundos)
     */
    private suspend fun ensureServiceBound(): Boolean = serviceMutex.withLock {
        if (shellService != null && serviceBound) return@withLock true
        val now = System.currentTimeMillis()
        if (now - lastBindFailureAt < BIND_FAILURE_BACKOFF_MS) {
            return@withLock false
        }
        return@withLock try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, ShellService::class.java.name),
            ).processNameSuffix("shell")
                .tag(SHELL_SERVICE_TAG)
                .version(SHELL_SERVICE_VERSION)
                .debuggable(BuildConfig.DEBUG)
                // No retener a los residentes desvinculados de las ataduras actuales UserService,Evite reutilizar procesos AIDL antiguos después de la actualización.
                .daemon(false)
            boundArgs = args
            // Shizuku La devolución de llamada de conexión y Android ServiceConnection se registran en el hilo principal.,
            // Evite que ciertas ROM no se envíen onServiceConnected cuando se vinculan desde el subproceso IO.
            withContext(Dispatchers.Main.immediate) {
                Shizuku.bindUserService(args, serviceConnection)
            }
            // R-SVC-03: Reemplazar con sondeo de tiempo de espera de rutina Thread.sleep,Evite bloquear el hilo de llamadas.
            val connected = withTimeoutOrNull(SERVICE_BIND_TIMEOUT_MS) {
                while (shellService == null) {
                    delay(SERVICE_BIND_POLL_MS)
                }
                true
            } == true
            if (!connected || shellService == null) {
                Logger.e(TAG, "bindUserService falló: UserService Tiempo de espera de conexión")
                lastBindFailureAt = System.currentTimeMillis()
                clearFailedBinding(args)
                false
            } else {
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.e(TAG, "bindUserService falló: ${e.message}", e)
            lastBindFailureAt = System.currentTimeMillis()
            boundArgs?.let(::clearFailedBinding)
            false
        }
    }

    /**
     * Compruebe si Shell UserService está realmente disponible.
     *
     * `checkPermission()` Sólo significa que el bit de autorización de Shizuku está CONCEDIDO; el nuevo registro ha demostrado que el bit de autorización es true
     * Todavía es posible que se agote el tiempo de espera durante la fase bindUserService, por lo que la página de configuración y el estado de la capacidad no pueden simplemente mirar el bit de autorización.
     */
    suspend fun checkReady(): Boolean {
        if (!checkPermission()) return false
        return ensureServiceBound()
    }

    /** Cancele la conexión pendiente cuando el enlace falle para evitar la superposición de devoluciones de llamada antiguas en la siguiente ejecución. */
    private fun clearFailedBinding(args: Shizuku.UserServiceArgs) {
        runCatching { Shizuku.unbindUserService(args, serviceConnection, true) }
            .onFailure { Logger.w(TAG, "Error en la limpieza Error en el enlace del servicio de usuario: ${it.message}") }
        if (boundArgs === args) {
            boundArgs = null
            shellService = null
            serviceBound = false
        }
    }

    /** Analizar lo que devuelve UserService "exitCode\u0000stdout\u0000stderr" Cadena de formato. */
    private fun parseExecResult(raw: String): ShizukuExecResult {
        val parts = raw.split('\u0000', limit = 3)
        val exitCode = parts.getOrNull(0)?.toIntOrNull() ?: -1
        val stdout = parts.getOrNull(1) ?: ""
        val stderr = parts.getOrNull(2) ?: ""
        return ShizukuExecResult(exitCode, stdout, stderr)
    }

    /** Shizuku La fase de disponibilidad real del servicio. */
    enum class ShizukuState {
        NOT_INSTALLED,
        NOT_RUNNING,
        NOT_AUTHORIZED,
        USER_SERVICE_UNAVAILABLE,
        READY,
    }

    data class ShizukuStatus(
        val state: ShizukuState,
        val message: String,
    ) {
        val isReady: Boolean get() = state == ShizukuState.READY
    }

    /** Shizuku Resultado de la ejecución del comando. */
    data class ShizukuExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val isSuccess: Boolean get() = exitCode == 0
    }
}
