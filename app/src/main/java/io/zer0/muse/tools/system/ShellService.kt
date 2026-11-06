package io.zer0.muse.tools.system

import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import io.zer0.common.Logger
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

private val SAFE_SHELL_VERB_REGEX = Regex("^[A-Za-z][A-Za-z0-9_-]*$")

/** Keep device-command diagnostics useful without persisting arguments or their sensitive values. */
internal fun sanitizeShellCommandForLog(command: String): String {
    val verb = command.trim().split(Regex("\\s+"), limit = 2).firstOrNull()
    return verb?.takeIf { SAFE_SHELL_VERB_REGEX.matches(it) } ?: "<unknown>"
}

/**
 * P3-3: Shizuku UserService — Un servicio que ejecuta comandos con privilegios de shell.
 *
 * El servicio pasa [Shizuku.bindUserService] Comenzó en un proceso separado gestionado por Shizuku.,
 * Heredado de Shizuku shell (ADB) permisos. El proceso principal se llama a través de AIDL IPC [execute].
 *
 * Nota:Shizuku UserService No Android [android.app.Service]. La propia clase de servicio debe
 * darse cuenta [android.os.IBinder], el AIDL Stub generado se hereda directamente aquí; de lo contrario bindUserService
 * No se obtendrá ninguna carpeta disponible y la persona que llama continuará esperando a que se agote el tiempo de espera de la conexión.
 *
 * ciclo de vida:
 *  - por [ShizukuAuthorizer] Vincular en la primera ejecución(bindUserService)
 *  - El servicio se ejecuta en un proceso separado.,Aislado del proceso principal,El bloqueo no afecta el proceso principal.
 *  - Shizuku Cuando se detiene el servicio,El proceso también se dará por terminado.
 *
 * seguro:
 *  - Sólo responde a llamadas de comando AIDL Shell,No procesado Intent
 *  - Ejecución de comandos sin filtrado de lista blanca(por la persona que llama [ToolPermissionResolver] Responsable de la verificación de límites estrictos.)
 *  - El servicio se ejecuta en el proceso Shizuku.,Incluso si se llama maliciosamente, solo puede realizar operaciones a nivel de shell.(no root)
 */
@Keep
class ShellService : IShellService.Stub() {

    companion object {
        private const val TAG = "ShellService"

        /** AIDL Separador de campo para valor de retorno(null char,shell no aparece en la salida). */
        private const val FIELD_SEPARATOR = '\u0000'

        /** Tiempo de espera de ejecución del comando ms. */
        private const val TIMEOUT_MS = 10_000L
        private const val OUTPUT_DRAIN_TIMEOUT_MS = 30_000L

        /** Legacy String transaction cap; interactive device-tool output uses executeToPipe. */
        private const val MAX_OUTPUT_BYTES = 2 * 1024 * 1024
    }

    private val streamExecutor = Executors.newCachedThreadPool()

    override fun execute(command: String): String {
        return try {
            Logger.d(TAG, "ejecutar comando verb=${sanitizeShellCommandForLog(command)}")
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            // stdout/stderr El drenaje debe continuar mientras se espera; de lo contrario uiautomator/screencap
            // Esperar un comando con una salida ligeramente mayor llenará la tubería y el proceso hijo nunca podrá esperar a salir y se considerará erróneamente que se ha agotado el tiempo de espera.
            val stdout: Future<String> = streamExecutor.submit(Callable { readStream(proc.inputStream) })
            val stderr: Future<String> = streamExecutor.submit(Callable { readStream(proc.errorStream) })
            val finished = proc.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                stdout.cancel(true)
                stderr.cancel(true)
                return "-1${FIELD_SEPARATOR}${FIELD_SEPARATOR}Tiempo de espera de ejecución(${TIMEOUT_MS / 1000}s)"
            }
            val out = stdout.get(1, TimeUnit.SECONDS)
            val err = stderr.get(1, TimeUnit.SECONDS)
            val code = proc.exitValue()
            buildString {
                append(code)
                append(FIELD_SEPARATOR)
                append(out)
                append(FIELD_SEPARATOR)
                append(err)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.e(TAG, "Shell La ejecución fue interrumpida: ${e.message}", e)
            "-1${FIELD_SEPARATOR}${FIELD_SEPARATOR}${e.message ?: "La ejecución fue interrumpida"}"
        } catch (e: IOException) {
            Logger.e(TAG, "Shell Ejecutar excepción IO: ${e.message}", e)
            "-1${FIELD_SEPARATOR}${FIELD_SEPARATOR}${e.message ?: "IO anormal"}"
        } catch (e: SecurityException) {
            Logger.e(TAG, "Shell Excepción de permiso de ejecución: ${e.message}", e)
            "-1${FIELD_SEPARATOR}${FIELD_SEPARATOR}${e.message ?: "Excepción de permiso de ejecución"}"
        } catch (e: IllegalArgumentException) {
            Logger.e(TAG, "Shell excepción de ejecución: ${e.message}", e)
            "-1${FIELD_SEPARATOR}${FIELD_SEPARATOR}${e.message ?: "excepción de ejecución"}"
        }
    }

    /**
     * Streams merged command output through a file descriptor so large results do not cross Binder
     * as a String transaction. The app process drains the pipe directly into its private output file.
     */
    override fun executeToPipe(command: String, output: ParcelFileDescriptor): Int {
        ParcelFileDescriptor.AutoCloseOutputStream(output).use { sink ->
            var process: Process? = null
            var outputCopy: Future<*>? = null
            return try {
                Logger.d(TAG, "Ejecución de comandos de transmisión verb=${sanitizeShellCommandForLog(command)}")
                val running = ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true)
                    .start()
                process = running
                val copy = streamExecutor.submit(
                    Callable {
                        running.inputStream.use { input -> copyShellOutput(input, sink) }
                    },
                )
                outputCopy = copy
                val finished = running.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (!finished) {
                    running.destroyForcibly()
                    running.waitFor(3, TimeUnit.SECONDS)
                }
                val drained = runCatching { copy.get(OUTPUT_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.isSuccess
                if (!drained) {
                    copy.cancel(true)
                    sink.write("\n[El flujo de salida no pudo cerrarse, la lectura finalizó; la salida actual puede estar incompleta]".toByteArray(Charsets.UTF_8))
                    -1
                } else if (!finished) {
                    sink.write("\n[tiempo de espera] Tiempo de espera de ejecución de comando(${TIMEOUT_MS / 1000}s),terminado".toByteArray(Charsets.UTF_8))
                    -1
                } else {
                    running.exitValue()
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                process?.destroyForcibly()
                outputCopy?.cancel(true)
                -1
            } catch (error: Exception) {
                process?.destroyForcibly()
                outputCopy?.cancel(true)
                Logger.w(TAG, "Shell pipe La ejecución falló: ${error.javaClass.simpleName}")
                -1
            }
        }
    }

    /**
     * Leer un flujo de salida de comando, reteniendo como máximo [MAX_OUTPUT_BYTES] Bytes.
     *
     * Nota: Este método está reservado por bytes.**cabeza**, la cola que supere el límite será descartada. Salida del comando (informes de error, casos de prueba fallidos,
     * El estado de salida está en las últimas líneas) Por favor vaya `ToolOutputCapture`, que conserva la cola por caracteres y puede dar referencias para lectura segmentada;
     * No utilice este método para recibir la salida del comando; de lo contrario, la conclusión se cortará aquí primero.
     */
    private fun readStream(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var retained = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (retained < MAX_OUTPUT_BYTES) {
                val take = minOf(read, MAX_OUTPUT_BYTES - retained)
                output.write(buffer, 0, take)
                retained += take
            }
        }
        val text = output.toString(Charsets.UTF_8.name())
        return if (retained >= MAX_OUTPUT_BYTES) "$text\n[La salida está truncada]" else text
    }

    /** Shizuku desagregar/La transacción retenida llamada durante el reemplazo de la versión debe finalizar el proceso de Servicio de Usuario. */
    override fun destroy() {
        Logger.i(TAG, "Shell UserService destroy")
        streamExecutor.shutdownNow()
        System.exit(0)
    }

    /** Salida activa opcional de la transacción en el lado de la aplicación. */
    override fun exit() = destroy()
}

internal fun copyShellOutput(input: InputStream, output: OutputStream) {
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return
        output.write(buffer, 0, count)
    }
}
