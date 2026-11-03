package io.zer0.muse.terminal

import android.content.Context
import io.zer0.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * v2.x terminales:administrador de sesión —— sesión de shell residente,Transmisión de salida de regreso a la interfaz de usuario.
 *
 * bimotor:
 *  - **PTY motor**(Primera elección,[Pty] native cuando esté listo):terminal de autenticidad,forkpty Implementación autocompilada;
 *    programas interactivos,Ctrl-C Soporte completo para señales y tamaños de ventanas.;
 *  - **motor de tubería**(Ten todo en cuenta,Cuando no hay biblioteca nativa):`/system/bin/sh` + ProcessBuilder,
 *    Cero dependencias nativas,Los comandos básicos están disponibles.
 *
 * Modelo de roscado:La salida se lee en la rutina IO;devolución de llamada [Listener] La persona que llama determina el hilo.(UI Cortar el hilo principal por sí solo.).
 */
class TerminalSessionManager(private val appContext: Context) {

    interface Listener {
        fun onOutput(bytes: ByteArray)
        fun onExit(code: Int)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var masterFd: Int = -1
    private var childPid: Int = -1
    private var readerJob: Job? = null

    /** Suprimir la devolución de llamada onExit cuando se cancela activamente una sesión(Evite informes falsos de salida del proceso por parte de la interfaz de usuario durante el reinicio). */
    @Volatile
    private var suppressExit = false

    @Volatile
    private var listener: Listener? = null

    /** Si actualmente está funcionando con el motor PTY(Determinar si la interfaz de usuario se repite localmente y si se debe proporcionar Ctrl-C). */
    val isPty: Boolean
        @Synchronized get() = masterFd >= 0

    /** Si la sesión se está ejecutando. */
    val isRunning: Boolean
        @Synchronized get() = if (masterFd >= 0) true else process?.isAlive == true

    fun setListener(l: Listener?) {
        listener = l
    }

    /** Iniciar sesión si no se está ejecutando(idempotente). */
    @Synchronized
    fun ensureStarted() {
        if (isRunning) return
        startLocked()
    }

    /** Reiniciar sesión(Separe primero la sesión anterior,Iniciar una nueva sesión). */
    @Synchronized
    fun restart() {
        killLocked()
        startLocked()
    }

    /** Escribir texto a la sesión(El comando debe tener su propio salto de línea.;PTY Lo siguiente se hace eco de la terminal.,No hacer eco localmente). */
    fun write(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val fd = masterFd
        val proc = process
        scope.launch {
            runCatching {
                if (fd >= 0) {
                    Pty.write(fd, bytes, 0, bytes.size)
                } else if (proc != null) {
                    proc.outputStream.write(bytes)
                    proc.outputStream.flush()
                }
            }.onFailure { Logger.w(TAG, "stdin Error de escritura: ${it.message}") }
        }
    }

    /** v2.x PTY:enviar Ctrl-C(SIGINT)al grupo de procesos de primer plano;Devoluciones del motor del oleoducto false(No disponible). */
    fun sendInterrupt(): Boolean {
        val pid = childPid
        if (masterFd < 0 || pid <= 0) return false
        return runCatching {
            android.os.Process.sendSignal(-pid, SIGNAL_SIGINT)
            true
        }.onFailure { Logger.w(TAG, "SIGINT Envío fallido: ${it.message}") }.getOrDefault(false)
    }

    /** Sincronizar el tamaño de la ventana del terminal(PTY motor;Dependencia del diseño del programa de pantalla completa). */
    fun resize(rows: Int, cols: Int) {
        val fd = masterFd
        if (fd >= 0) {
            runCatching { Pty.setWindowSize(fd, rows, cols) }
        }
    }

    // ── arranque del motor ─────────────────────────────────────────────

    @Synchronized
    private fun startLocked() {
        suppressExit = false
        if (Pty.available && startPtyLocked()) return
        startPipeLocked()
    }

    private fun startPtyLocked(): Boolean {
        val workDir = File(appContext.filesDir, "workspace").apply { mkdirs() }
        val pidArray = IntArray(1)
        val fd = runCatching {
            // P1-B: Agregue el contenedor de tiempo de ejecución a la RUTA del terminal e inyecte la corrección ejecutiva(Mapeo de nombres + shebang; saltar cadena vacía cuando el tiempo de ejecución no está listo)
            val (extraPath, preloadLib) = runtimeInjection(appContext)
            Pty.createSubprocess(
                SHELL_PATH,
                workDir.absolutePath,
                pidArray,
                extraPath = extraPath,
                preloadLib = preloadLib,
            )
        }.getOrElse {
            Logger.w(TAG, "PTY Fallo en el arranque del motor: ${it.message}")
            -1
        }
        if (fd < 0) return false
        masterFd = fd
        childPid = pidArray[0]
        Logger.i(TAG, "PTY inicio de sesión: pid=$childPid fd=$fd")
        readerJob = scope.launch {
            val buffer = ByteArray(8192)
            try {
                while (isActive) {
                    val n = Pty.read(fd, buffer, 0, buffer.size)
                    if (n <= 0) break
                    listener?.onOutput(buffer.copyOf(n))
                }
            } catch (t: Throwable) {
                Logger.d(TAG, "PTY El ciclo de lectura finaliza: ${t.message}")
            }
            val code = runCatching { Pty.waitFor(childPid) }.getOrDefault(-1)
            if (!suppressExit) listener?.onExit(code)
        }
        return true
    }

    private fun startPipeLocked() {
        val workDir = File(appContext.filesDir, "workspace").apply { mkdirs() }
        val started = runCatching {
            ProcessBuilder(SHELL_PATH)
                .directory(workDir)
                .redirectErrorStream(true)
                .apply {
                    environment()["TERM"] = "xterm-256color"
                    environment()["HOME"] = workDir.absolutePath
                    val (extraPath, preloadLib) = runtimeInjection(appContext)
                    val pathPrefix = if (extraPath.isNotEmpty()) "$extraPath:" else ""
                    environment()["PATH"] = "$pathPrefix/system/bin:/system/xbin:$workDir/bin"
                    if (preloadLib.isNotEmpty()) environment()["LD_PRELOAD"] = preloadLib
                }
                .start()
        }
        started.onFailure {
            Logger.w(TAG, "Error al iniciar la sesión de canalización: ${it.message}")
            listener?.onExit(-1)
        }
        val proc = started.getOrNull() ?: return
        process = proc
        Logger.i(TAG, "Comienza la sesión de canalización(PTY No disponible)")
        readerJob = scope.launch {
            val buffer = ByteArray(8192)
            try {
                val input = proc.inputStream
                while (isActive) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    listener?.onOutput(buffer.copyOf(n))
                }
            } catch (e: Exception) {
                Logger.d(TAG, "El ciclo de lectura finaliza: ${e.message}")
            }
            val code = runCatching { proc.exitValue() }.getOrDefault(-1)
            if (!suppressExit) listener?.onExit(code)
        }
    }

    @Synchronized
    private fun killLocked() {
        suppressExit = true
        readerJob?.cancel()
        readerJob = null
        if (masterFd >= 0) {
            runCatching { Pty.closeFd(masterFd) }
            masterFd = -1
        }
        if (childPid > 0) {
            runCatching { android.os.Process.sendSignal(childPid, SIGNAL_SIGKILL) }
            childPid = -1
        }
        process?.let { proc ->
            runCatching { proc.destroy() }
            runCatching { proc.destroyForcibly() }
        }
        process = null
    }

    companion object {
        private const val TAG = "TerminalSession"
        private const val SHELL_PATH = "/system/bin/sh"
        private const val SIGNAL_SIGINT = 2
        private const val SIGNAL_SIGKILL = 9

        /** P1-B: Inyección de tiempo de ejecución de terminal — extraPath=tiempo de ejecución bin, preloadLib=exec Junta;Es una cadena vacía cuando no está lista. */
        private fun runtimeInjection(appContext: Context): Pair<String, String> {
            val bin = File(appContext.filesDir, "muse-runtime/bin")
            val shim = File(appContext.applicationInfo.nativeLibraryDir, "libmuse_exec.so")
            val extraPath = if (bin.isDirectory) bin.absolutePath else ""
            val preloadLib = if (shim.isFile) shim.absolutePath else ""
            return extraPath to preloadLib
        }
    }
}

/**
 * Titular singleton a nivel de proceso:La sesión de terminal sobrevive en todas las páginas(App dentro del periodo de supervivencia),
 * La destrucción de página solo se desvincula listener,No mates la sesión.
 */
object TerminalSessionStore {

    @Volatile
    private var manager: TerminalSessionManager? = null

    fun get(context: Context): TerminalSessionManager {
        manager?.let { return it }
        return synchronized(this) {
            manager ?: TerminalSessionManager(context.applicationContext).also { manager = it }
        }
    }
}
