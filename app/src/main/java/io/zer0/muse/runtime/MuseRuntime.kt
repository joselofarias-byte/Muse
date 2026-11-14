package io.zer0.muse.runtime

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.tools.TOOL_OUTPUTS_DIR
import io.zer0.muse.tools.ToolOutputCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream

/**
 * v2.x Tiempo de ejecución extendido (P0 Revisión): Administrador de tiempo de ejecución de sandbox integrado de Node.js.
 *
 * ## fondo
 * Muse Sandbox en la aplicación (script de habilidad / MCP stdio server / Terminal) requiere un tiempo de ejecución real
 * (Node.js / npm) se puede ejecutar"en el mercado"Ampliar el ecosistema. recibir Android 10+ W^X Límite
 * (targetSdk 29+ Los archivos en el directorio de datos de la aplicación no se pueden ejecutar) y se utiliza la siguiente arquitectura durante el tiempo de ejecución:
 *
 *  - **parte ejecutable**(node Programa principal + bibliotecas compartidas dependientes) se lanzan con el APK en jniLibs
 *    (extractNativeLibs=true, descomprimir en NativeLibraryDir durante la instalación),
 *    Este directorio no está protegido por W^X Limitación, puede ser directamente exec/dlopen;
 *  - **Parte de datos puros**(npm Los archivos JS) están empaquetados como activos/muse-runtime-assets.zip,
 *    Extraer a filesDir/muse-runtime/ cuando se use por primera vez;
 *  - Biblioteca compartida con lib*.so Distribución de convención de nomenclatura (libmuse_node.so / libssl.so / ...),
 *    SONAME/DT_NEEDED interno anulado,RPATH=$ORIGIN, carga autónoma.
 *
 * Utilice la entrada:[isReady] comprobar / [ensureData] Descomprimir / [execNode] Ejecutar.
 */
@Suppress("TooManyFunctions") // Fachada en tiempo de ejecución: accesor de ruta + conjunto de entradas de ejecución, este es el diseño
object MuseRuntime {
    private const val TAG = "MuseRuntime"
    private const val OUTPUT_DRAIN_TIMEOUT_MS = 30_000L

    /** Binario de nodo incorporado (jniLibs En su interior, el naming debe cumplir con lib*.so especificaciones). */
    const val NODE_LIB_NAME = "libmuse_node.so"

    /** assets paquete de mitad de tiempo de ejecución. */
    private const val ASSETS_ZIP = "muse-runtime-assets.zip"

    /** El nombre de la variable de entorno central de la CPU pasada al proceso hijo (la capa de empaquetado del script lo utilizará para proporcionar información cuando no se pueda obtener de forma nativa). */
    const val ENV_CPU_COUNT = "MUSE_CPU_COUNT"

    /** Marca de versión del paquete de datos (modificada sincrónicamente al actualizar los activos, lo que desencadena la redescompresión). */
    const val RUNTIME_DATA_VERSION = "npm-12.2.2"

    // ── camino ─────────────────────────────────────────────────────

    /** native Directorio de la biblioteca (el directorio donde se encuentra el .so extraído después de la instalación). */
    fun nativeLibDir(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir)

    /** Ejecutable de nodo incorporado. */
    fun nodeBinary(ctx: Context): File = File(nativeLibDir(ctx), NODE_LIB_NAME)

    /** Directorio raíz de datos en tiempo de ejecución (filesDir/muse-runtime). */
    fun runtimeDir(ctx: Context): File = File(ctx.filesDir, "muse-runtime")

    /** npm Directorio de datos. */
    fun npmDir(ctx: Context): File = File(runtimeDir(ctx), "npm")

    /** npm CLI entrada (JS archivo, ejecutado por nodo). */
    fun npmCli(ctx: Context): File = File(npmDir(ctx), "bin/npm-cli.js")

    /** Directorio INICIO de tiempo de ejecución (directorio de trabajo del proceso secundario). */
    fun homeDir(ctx: Context): File = File(runtimeDir(ctx), "home")

    /** Directorio temporal del proceso hijo (TMPDIR). */
    fun tmpDir(ctx: Context): File = File(runtimeDir(ctx), "tmp")

    /** Directorio bin en tiempo de ejecución (PATH Primera posición, reservada para enlace simbólico/shim). */
    fun binDir(ctx: Context): File = File(runtimeDir(ctx), "bin")

    /** Listo en tiempo de ejecución (node El binario existe y los datos npm están descomprimidos). */
    fun isReady(ctx: Context): Boolean = nodeBinary(ctx).isFile && npmCli(ctx).isFile

    // ── Descompresión de datos ─────────────────────────────────────────────────

    private const val MARK_FILE = ".runtime-data-version"

    /**
     * Desempaquete los datos de tiempo de ejecución de los activos (idempotente; omítalo si se alcanza la etiqueta de versión).
     */
    suspend fun ensureData(ctx: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val rt = runtimeDir(ctx)
            val mark = File(rt, MARK_FILE)
            val installed = runCatching { mark.readText().trim() }.getOrNull()
            if (installed == RUNTIME_DATA_VERSION && npmCli(ctx).isFile) {
                setupBinLinks(ctx)
                return@runCatching
            }

            Logger.i(TAG, "Desempaquetar datos de tiempo de ejecución ($installed -> $RUNTIME_DATA_VERSION)")
            npmDir(ctx).deleteRecursively()
            ctx.assets.open(ASSETS_ZIP).use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val target = File(rt, entry.name)
                        val rootPath = rt.canonicalPath + File.separator
                        if (!target.canonicalPath.startsWith(rootPath)) {
                            throw SecurityException("zip Entrada fuera de límites: ${entry.name}")
                        }
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().buffered().use { out -> zip.copyTo(out) }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            mark.writeText(RUNTIME_DATA_VERSION)
            setupBinLinks(ctx)
            Logger.i(TAG, "Descompresión de datos en tiempo de ejecución completada")
        }.onFailure { Logger.w(TAG, "La descompresión de datos falló durante el tiempo de ejecución: ${it.message}", it) }
    }

    /**
     * P1-B: Prepare la entrada del contenedor de tiempo de ejecución (symlink: node / npm / npx).
     *
     * Se utiliza para búsquedas de RUTAS de terminales y procesos secundarios.npm/npx El objetivo es un archivo JS (completa primero el bit ejecutable,
     * Deje pasar la determinación de búsqueda del shell); la ejecución real es reescrita por el ajuste ejecutivo al nodo por shebang.
     */
    private fun setupBinLinks(ctx: Context) {
        val bin = binDir(ctx).apply { mkdirs() }
        val npmBin = File(npmDir(ctx), "bin")
        listOf("npm-cli.js", "npx-cli.js").forEach { name ->
            File(npmBin, name).takeIf { it.isFile }?.setExecutable(true, false)
        }

        fun relink(name: String, target: File) {
            if (!target.isFile) return
            val link = File(bin, name)
            // lstat Si existe (incluidos los enlaces rotos), elimínelo primero y luego compílelo para asegurarse de que apunte al objetivo más reciente.
            runCatching { android.system.Os.lstat(link.absolutePath) }
                .onSuccess { runCatching { link.delete() } }
            runCatching { android.system.Os.symlink(target.absolutePath, link.absolutePath) }
                .onFailure { Logger.w(TAG, "bin symlink La creación falló($name): ${it.message}") }
        }
        relink("node", nodeBinary(ctx))
        relink("npm", File(npmBin, "npm-cli.js"))
        relink("npx", File(npmBin, "npx-cli.js"))
    }

    // ── entorno de proceso hijo ───────────────────────────────────────────────

    /**
     * Construir un entorno de subproceso controlado.
     *
     * Variables clave:
     *  - HOME/TMPDIR: Apunta al directorio de datos en tiempo de ejecución (Android no hay valor predeterminado disponible)
     *  - PATH: Bandeja de tiempo de ejecución (reservada)+ ruta del sistema
     *  - LD_LIBRARY_PATH:native Directorio de bibliotecas (doble seguro; biblioteca RPATH=$ORIGIN cubierto)
     *  - SHELL:/system/bin/sh(Android sin /bin/sh)
     *  - MUSE_CPU_COUNT:Java La cantidad de núcleos de CPU que se ven en el lateral (libuv no puede obtenerlo en algunos dispositivos, por lo que se deja en la capa de script)
     */
    fun buildEnv(ctx: Context, extra: Map<String, String> = emptyMap()): Map<String, String> {
        listOf(homeDir(ctx), tmpDir(ctx), binDir(ctx)).forEach { it.mkdirs() }
        return buildMap {
            put("HOME", homeDir(ctx).absolutePath)
            put("TMPDIR", tmpDir(ctx).absolutePath)
            put("PATH", "${binDir(ctx).absolutePath}:/system/bin:/system/xbin")
            put("LD_LIBRARY_PATH", nativeLibDir(ctx).absolutePath)
            put("SHELL", "/system/bin/sh")
            put("TERM", "xterm-256color")
            // P1-B: exec Shims (asignación de nombres + análisis de shebang; se omite automáticamente si faltan archivos)
            File(nativeLibDir(ctx), "libmuse_exec.so").takeIf { it.isFile }?.let {
                put("LD_PRELOAD", it.absolutePath)
            }
            put("MUSE_NODE_BIN", nodeBinary(ctx).absolutePath)
            put("MUSE_NPM_CLI", npmCli(ctx).absolutePath)
            put("MUSE_NPX_CLI", File(npmDir(ctx), "bin/npx-cli.js").absolutePath)
            put(ENV_CPU_COUNT, childCpuCount().toString())
            putAll(extra)
        }
    }

    /** La cantidad de núcleos de CPU disponibles para el proceso hijo; si no se puede obtener, se tratará como 1 para evitar que el flujo descendente obtenga 0 y luego se divida por cero o degenere. */
    internal fun childCpuCount(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    // ── ejecución de proceso hijo ───────────────────────────────────────────────

    /** El resultado de la ejecución del proceso hijo. */
    data class ExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean = false,
        val stdoutFile: File? = null,
        val stderrFile: File? = null,
        val captureError: String? = null,
    )

    /** Ejecute el nodo incorporado (args Se adjunta después de la ruta del nodo). */
    suspend fun execNode(
        ctx: Context,
        args: List<String>,
        timeoutMs: Long = 30_000L,
        workdir: File? = homeDir(ctx),
        env: Map<String, String> = emptyMap(),
    ): ExecResult = exec(
        ctx = ctx,
        cmd = listOf(nodeBinary(ctx).absolutePath) + args,
        timeoutMs = timeoutMs,
        workdir = workdir,
        env = env,
    )

    /** Ejecución de subproceso genérico (entorno de herencia claro, uso [buildEnv] entorno construido). */
    suspend fun exec(
        ctx: Context,
        cmd: List<String>,
        timeoutMs: Long = 30_000L,
        workdir: File? = null,
        env: Map<String, String> = emptyMap(),
    ): ExecResult = withContext(Dispatchers.IO) {
        val pb = ProcessBuilder(cmd)
        workdir?.let {
            it.mkdirs()
            pb.directory(it)
        }
        pb.environment().clear()
        pb.environment().putAll(buildEnv(ctx, env))

        val process = pb.start()
        val outputDirectory = File(ctx.filesDir, TOOL_OUTPUTS_DIR)
        val outCapture = ToolOutputCapture(outputDirectory, "node_stdout")
        val errCapture = ToolOutputCapture(outputDirectory, "node_stderr")
        val captureFailure = AtomicReference<Throwable?>(null)
        val tOut = outputReaderThread("muse-node-stdout", process.inputStream, outCapture, captureFailure)
        val tErr = outputReaderThread("muse-node-stderr", process.errorStream, errCapture, captureFailure)
        tOut.start()
        tErr.start()

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(3, TimeUnit.SECONDS)
        }
        tOut.join(OUTPUT_DRAIN_TIMEOUT_MS)
        tErr.join(OUTPUT_DRAIN_TIMEOUT_MS)
        if (tOut.isAlive) {
            runCatching { process.inputStream.close() }
            tOut.join(1_000)
            captureFailure.compareAndSet(null, IOException("stdout El flujo de salida no se drenó por completo."))
        }
        if (tErr.isAlive) {
            runCatching { process.errorStream.close() }
            tErr.join(1_000)
            captureFailure.compareAndSet(null, IOException("stderr El flujo de salida no se drenó por completo."))
        }

        val outputFailure = captureFailure.get()
        if (outputFailure != null) {
            val partialStdout =
                runCatching {
                    outCapture.finish(
                        header = "[stdout Salida no recibida completamente]",
                        emptyMessage = "",
                    )
                }.getOrElse {
                    outCapture.close()
                    outCapture.savedFile?.let { "[stdout Archivo de salida parcial: ${it.absolutePath}]" }.orEmpty()
                }
            val partialStderr =
                runCatching {
                    errCapture.finish(
                        header = "[stderr Salida no recibida completamente]",
                        emptyMessage = "",
                    )
                }.getOrElse {
                    errCapture.close()
                    errCapture.savedFile?.let { "[stderr Archivo de salida parcial: ${it.absolutePath}]" }.orEmpty()
                }
            return@withContext ExecResult(
                exitCode = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1,
                stdout = partialStdout,
                stderr = partialStderr,
                timedOut = !finished,
                stdoutFile = outCapture.savedFile,
                stderrFile = errCapture.savedFile,
                captureError = outputFailure.message ?: outputFailure.javaClass.simpleName,
            )
        }

        val stdout = outCapture.finish(header = "", emptyMessage = "")
        val stderr = errCapture.finish(header = "", emptyMessage = "")

        ExecResult(
            exitCode = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1,
            stdout = stdout,
            stderr = stderr,
            timedOut = !finished,
            stdoutFile = outCapture.savedFile,
            stderrFile = errCapture.savedFile,
        )
    }

    private fun outputReaderThread(
        threadName: String,
        input: InputStream,
        capture: ToolOutputCapture,
        failure: AtomicReference<Throwable?>,
    ): Thread = Thread({
        try {
            InputStreamReader(input, Charsets.UTF_8).buffered().use { reader ->
                val buffer = CharArray(8_192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    capture.append(String(buffer, 0, count))
                }
            }
        } catch (error: Throwable) {
            failure.compareAndSet(null, error)
        }
    }, threadName)
}
