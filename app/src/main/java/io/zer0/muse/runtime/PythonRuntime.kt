package io.zer0.muse.runtime

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.BuildConfig
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
 * v2.7 Tiempo de ejecución extendido (Python): Administrador de tiempo de ejecución integrado de Python Sandbox.
 *
 * ## Con el tiempo de ejecución de Nodo incorporado ([MuseRuntime]) relación
 *
 * ambos**isomorfismo**: El mismo conjunto de "la parte ejecutable va a jniLibs con el APK + la parte de datos va a los activos y se descomprime"
 * en capas, mismo conjunto W^X Estrategia de evitación, el mismo conjunto de mecanismos de captura de resultados y ejecución de subprocesos. Sólo hay tres diferencias:
 *
 *  1. Python ningún oficial `bin/python3` Archivo ejecutable, utilice código auxiliar escrito por usted mismo (`libmuse_python.so`,
 *     Consulte app/src/main/jni/muse_python.c) para alojar la entrada ejecutable;
 *  2. Python extensión C (lib-dynload .so) debe caer en el área de solo lectura para abrirse, por lo que cualquier
 *     jniLibs Distribuya, use un enlace simbólico para bloquear stdlib en tiempo de ejecución `lib-dynload` Directorio;
 *  3. Python nombre de la biblioteca (`libssl_python.so` etc.) se han distinguido por el paquete oficial y no es necesario cambiar el nombre de patchelf.
 *
 * ## Diseño del directorio (tiempo de ejecución)
 *
 * ```
 * filesDir/muse-python/
 *   ├── lib/python3.14/          ← stdlib(assets descomprimir)
 *   │     └── lib-dynload/       ← symlink Extensión C que apunta a NativeLibraryDir
 *   ├── home/                    ← HOME / directorio de trabajo
 *   ├── tmp/                     ← TMPDIR
 *   └── bin/                     ← python3 / pip Entrada de la junta
 * ```
 *
 * ## Correspondencia con NativeLibraryDir
 *
 * APK Durante la instalación, extraiga jniLibs a NativeLibraryDir, que contiene:
 *  - `libmuse_python.so`(stub ejecutable)
 *  - `libpython3.14.so` / `libpython3.so`
 *  - `libssl_python.so` / `libcrypto_python.so` / `libsqlite3_python.so`
 *  - 58 Extensiones C para cpython-314 (.so)
 */
@Suppress("TooManyFunctions") // Fachada en tiempo de ejecución: accesor de ruta + conjunto de entradas de ejecución, este es el diseño
object PythonRuntime {
    private const val TAG = "PythonRuntime"
    private const val OUTPUT_DRAIN_TIMEOUT_MS = 30_000L

    /** Python Versión de la serie (el nombre del directorio está sincronizado con el nombre de la biblioteca principal). */
    const val PYTHON_SERIES = "3.14"

    /** Número de versión completo (para visualización de autoprueba). */
    const val PYTHON_VERSION = "3.14.8"

    /** stub archivo ejecutable (jniLibs En su interior, el naming debe cumplir con lib*.so especificaciones). */
    const val PYTHON_STUB_LIB_NAME = "libmuse_python.so"

    /** Biblioteca principal del intérprete (stub cargado con dlopen). */
    const val PYTHON_LIB_NAME = "libpython3.14.so"

    /** assets paquete de mitad de tiempo de ejecución. */
    private const val ASSETS_ZIP = "muse-python-assets.zip"

    /** Marca de versión del paquete de datos (modificada sincrónicamente al actualizar los activos, lo que desencadena la redescompresión). */
    const val PYTHON_DATA_VERSION = "python-$PYTHON_VERSION-1"

    // ── camino ─────────────────────────────────────────────────────

    /** native Directorio de la biblioteca (el directorio donde se encuentra el .so extraído después de la instalación). */
    fun nativeLibDir(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir)

    /** Ejecutable de código auxiliar de Python incorporado. */
    fun pythonBinary(ctx: Context): File = File(nativeLibDir(ctx), PYTHON_STUB_LIB_NAME)

    /** Biblioteca principal de intérpretes. */
    fun pythonLib(ctx: Context): File = File(nativeLibDir(ctx), PYTHON_LIB_NAME)

    /** Directorio raíz de datos en tiempo de ejecución (filesDir/muse-python). */
    fun runtimeDir(ctx: Context): File = File(ctx.filesDir, "muse-python")

    /** stdlib directorio raíz (…/lib/python3.14). */
    fun stdlibDir(ctx: Context): File = File(File(runtimeDir(ctx), "lib"), "python$PYTHON_SERIES")

    /** lib-dynload Directorio (stdlib dentro; apuntado por un enlace simbólico a NativeLibraryDir en tiempo de ejecución). */
    fun dynloadDir(ctx: Context): File = File(stdlibDir(ctx), "lib-dynload")

    /** Directorio INICIO de tiempo de ejecución (directorio de trabajo del proceso secundario). */
    fun homeDir(ctx: Context): File = File(runtimeDir(ctx), "home")

    /** Directorio temporal del proceso hijo (TMPDIR). */
    fun tmpDir(ctx: Context): File = File(runtimeDir(ctx), "tmp")

    /** Directorio bin en tiempo de ejecución (PATH Primera posición, reservada para enlace simbólico/shim). */
    fun binDir(ctx: Context): File = File(runtimeDir(ctx), "bin")

    /**
     * Paquete de certificados de CA fusionados (SSL_CERT_FILE señalarlo).
     *
     * ## por qué es necesario
     *
     * Android de `/system/etc/security/cacerts` Un archivo por certificado, el nombre del archivo es
     * **OpenSSL old hash**(MD5-based); mientras que OpenSSL 3.x es `SSL_CERT_DIR` abajo
     * prensa **new hash**(SHA1-based) buscar —— Los nombres de los archivos no coinciden, lo que da como resultado `SSL_CERT_DIR`
     * Al apuntar a este directorio `loaded certs: 0`,TLS periódico de apretón de manos CERTIFICATE_VERIFY_FAILED
     * (Confirmado por medición real). Fusionarse en un único paquete PEM usando SSL_CERT_FILE puede omitir el índice hash.
     */
    fun caBundleFile(ctx: Context): File = File(runtimeDir(ctx), "cacert.pem")

    /** Listo en tiempo de ejecución (stub El binario existe y stdlib está descomprimido). */
    fun isReady(ctx: Context): Boolean =
        BuildConfig.EMBEDDED_RUNTIMES_ENABLED && pythonBinary(ctx).isFile && File(stdlibDir(ctx), "os.py").isFile

    // ── Descompresión de datos ─────────────────────────────────────────────────

    private const val MARK_FILE = ".python-data-version"

    /**
     * Desempaquete stdlib de los activos (idempotente; se omite cuando se presiona la etiqueta de versión).
     */
    suspend fun ensureData(ctx: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            requireEnabled()
            val rt = runtimeDir(ctx)
            val mark = File(rt, MARK_FILE)
            val installed = runCatching { mark.readText().trim() }.getOrNull()
            if (installed == PYTHON_DATA_VERSION && File(stdlibDir(ctx), "os.py").isFile) {
                setupDynloadLinks(ctx)
                ensureCaBundle(ctx)
                return@runCatching
            }

            Logger.i(TAG, "Desempaquetar datos de tiempo de ejecución de Python ($installed -> $PYTHON_DATA_VERSION)")
            val libRoot = File(rt, "lib")
            libRoot.deleteRecursively()
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
            mark.writeText(PYTHON_DATA_VERSION)
            setupDynloadLinks(ctx)
            ensureCaBundle(ctx)
            Logger.i(TAG, "Python Descompresión de datos en tiempo de ejecución completada")
        }.onFailure { Logger.w(TAG, "Python La descompresión de datos falló durante el tiempo de ejecución: ${it.message}", it) }
    }

    /**
     * Apunte el directorio lib-dynload de stdlib a la extensión C en NativeLibraryDir.
     *
     * ## por qué symlink
     *
     * Python prensa `sys.path` Para buscar extensiones C, la ubicación estándar es `<stdlib>/lib-dynload`.
     * Pero Android 10+ no permite dlopen a .so en el directorio de datos de la aplicación (W^X), las extensiones sólo se pueden colocar en
     * APK Instalar el descomprimido**solo lectura** nativeLibraryDir. Por lo tanto, lib-dynload se construye aquí
     * Puntero a NativeLibraryDir symlink —— Python Encuentre la extensión por ruta como de costumbre,
     * dlopen El archivo real está en la zona de solo lectura permitida.
     *
     * Cree extensiones adicionales para cada extensión al mismo tiempo `_ssl.so`(sin sufijo ABI) enlace simbólico, pieza compatible
     * Ruta para buscar por nombre corto.
     */
    private fun setupDynloadLinks(ctx: Context) {
        val nativeDir = nativeLibDir(ctx)
        val dynload = dynloadDir(ctx)

        // 1) lib-dynload Directorio en su conjunto → nativeLibraryDir
        //    Primero borre el directorio de marcadores de posición en los activos y luego cree el enlace simbólico.
        if (dynload.exists() && !isSymlink(dynload)) {
            dynload.deleteRecursively()
        }
        if (!dynload.exists()) {
            dynload.parentFile?.mkdirs()
            runCatching {
                android.system.Os.symlink(nativeDir.absolutePath, dynload.absolutePath)
            }.onFailure {
                // Equipo/Qué hacer cuando el sistema de archivos no admite enlaces simbólicos: se degrada al copiar (algunas extensiones pueden fallar al abrir,
                // Pero las capacidades puras de Python todavía están disponibles).
                Logger.w(TAG, "lib-dynload symlink Replicación degradada y fallida: ${it.message}")
                runCatching {
                    dynload.mkdirs()
                    nativeDir.listFiles { f -> f.isFile && f.name.contains(".cpython-") }
                        ?.forEach { f -> f.copyTo(File(dynload, f.name), overwrite = true) }
                }
            }
        }
    }

    private fun isSymlink(file: File): Boolean = runCatching {
        val attr = android.system.Os.lstat(file.absolutePath)
        android.system.OsConstants.S_ISLNK(attr.st_mode)
    }.getOrDefault(false)

    /**
     * Localice el directorio de certificados de CA del sistema.
     *
     * Android No existe una tienda de CA predeterminada de Python,`ssl` El módulo informará que no puede encontrar la raíz de confianza.
     * CERTIFICATE_VERIFY_FAILED. La CA del sistema se encuentra en ambas rutas (esta última es para Android 14+
     * Conscrypt apex), el nombre del archivo es la forma hash de asunto de OpenSSL y se puede leer directamente con SSL_CERT_DIR.
     */
    private fun resolveCaCertDir(): String? = CA_CERT_DIRS.firstOrNull { File(it).isDirectory }

    private val CA_CERT_DIRS = listOf(
        "/apex/com.android.conscrypt/cacerts",
        "/system/etc/security/cacerts",
    )

    /**
     * Generar paquetes de CA fusionados (idempotentes).
     * Concatenar y escribir todos los archivos de certificados en el directorio CA del sistema según PEM [caBundleFile].
     * Se genera una vez cuando se llama por primera vez; omitido si ya existe y no está vacío.
     */
    suspend fun ensureCaBundle(ctx: Context): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val bundle = caBundleFile(ctx)
            if (bundle.isFile && bundle.length() > 0) return@runCatching bundle

            val dir = resolveCaCertDir() ?: error("Directorio de CA del sistema no encontrado")
            bundle.parentFile?.mkdirs()
            bundle.outputStream().bufferedWriter().use { out ->
                File(dir).listFiles { f -> f.isFile }?.sortedBy { it.name }?.forEach { cert ->
                    val text = runCatching { cert.readText() }.getOrNull() ?: return@forEach
                    if (!text.contains("BEGIN CERTIFICATE")) return@forEach
                    out.write(text.trim())
                    out.write("\n")
                }
            }
            if (bundle.length() == 0L) error("El directorio de CA del sistema no tiene certificados legibles")
            Logger.i(TAG, "CA bundle Generado: ${bundle.length()} bytes")
            bundle
        }.onFailure { Logger.w(TAG, "CA bundle Error de compilación: ${it.message}") }
    }

    // ── pip(Paquete de terceros) ────────────────────────────────────────

    /**
     * Asegúrese de que pip esté disponible (idempotente).
     *
     * python.org El paquete de Android viene con `ensurepip`(Incluye rueda de pip, ver
     * stdlib/ensurepip/_bundled/pip-*.whl), pero no está instalado de forma predeterminada. Ejecutado cuando se llamó por primera vez
     * `python -m ensurepip`, instale pip en el directorio de ejecución.
     *
     * Listo para usar después de la instalación. `python -m pip install <nombre del paquete>` Instalar paquetes de terceros:
     * Paquete Python puro (py3-none-any wheel) se puede utilizar directamente; Los paquetes con extensiones C requieren
     * Android rueda de plataforma (PyPI Algunos proyectos han sido publicados por cibuildwheel).
     */
    suspend fun ensurePip(ctx: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ensureData(ctx).getOrThrow()
            if (isPipReady(ctx)) return@runCatching

            Logger.i(TAG, "instalar pipa (ensurepip)…")
            val r = execPython(ctx, listOf("-m", "ensurepip", "--upgrade"), timeoutMs = 180_000)
            check(r.exitCode == 0) {
                "ensurepip falló(exit=${r.exitCode}): ${(r.stderr.ifBlank { r.stdout }).take(300)}"
            }
            Logger.i(TAG, "pip Instalación completada")
        }.onFailure { Logger.w(TAG, "pip La instalación falló: ${it.message}") }
    }

    /** pip ¿Está listo? */
    fun isPipReady(ctx: Context): Boolean = File(sitePackagesDir(ctx), "pip").isDirectory ||
        File(sitePackagesDir(ctx), "pip").isFile

    /** site-packages Directorio (donde se instalan los paquetes de terceros). */
    fun sitePackagesDir(ctx: Context): File = File(stdlibDir(ctx), "site-packages")

    // ── entorno de proceso hijo ───────────────────────────────────────────────

    /**
     * Construir un entorno de subproceso controlado.
     *
     * Variables clave:
     *  - PYTHONHOME: Directorio raíz del tiempo de ejecución;Python Haga clic aquí `lib/pythonX.Y` Encuentra la biblioteca estándar
     *  - PYTHONPATH:C Ampliar ruta de búsqueda —— Apunte a NativeLibraryDir(58 las extensiones siguen jniLibs
     *    distribución, es la única área de solo lectura que se garantiza que se puede abrir), además del bit de compatibilidad lib-dynload de stdlib
     *  - HOME/TMPDIR: directorio de datos de tiempo de ejecución (Android no hay valor predeterminado disponible)
     *  - PATH: contenedor de tiempo de ejecución + ruta del sistema
     *  - LD_LIBRARY_PATH:native directorio de la biblioteca (libpython Localización con bibliotecas dependientes como libssl_python)
     */
    fun buildEnv(ctx: Context, extra: Map<String, String> = emptyMap()): Map<String, String> {
        listOf(homeDir(ctx), tmpDir(ctx), binDir(ctx)).forEach { it.mkdirs() }
        return buildMap {
            put("HOME", homeDir(ctx).absolutePath)
            put("TMPDIR", tmpDir(ctx).absolutePath)
            put("PATH", "${binDir(ctx).absolutePath}:/system/bin:/system/xbin")
            put("LD_LIBRARY_PATH", nativeLibDir(ctx).absolutePath)
            put("SHELL", "/system/bin/sh")
            put("LANG", "C.UTF-8")
            // Python Localice la biblioteca estándar:PYTHONHOME Busque en lib/pythonX.Y
            put("PYTHONHOME", runtimeDir(ctx).absolutePath)
            // C Extensiones:nativeLibraryDir es la única fuente confiable de dlopen (zona de solo lectura)
            put("PYTHONPATH", dynloadDir(ctx).absolutePath + ":" + nativeLibDir(ctx).absolutePath)
            put("PYTHONDONTWRITEBYTECODE", "1")
            put("PYTHONUNBUFFERED", "1")
            put("PYTHONIOENCODING", "utf-8")
            // Android No existe un almacén de CA predeterminado en Python.
            // Priorizar el uso del paquete de combinación (SSL_CERT_FILE); degradar a SSL_CERT_DIR cuando no está presente.
            val bundle = caBundleFile(ctx)
            if (bundle.isFile) {
                put("SSL_CERT_FILE", bundle.absolutePath)
            } else {
                resolveCaCertDir()?.let { put("SSL_CERT_DIR", it) }
            }
            put("MUSE_PYTHON_BIN", pythonBinary(ctx).absolutePath)
            putAll(extra)
        }
    }

    // ── ejecución de proceso hijo ───────────────────────────────────────────────

    /** resultado de la ejecución del proceso hijo (con [MuseRuntime.ExecResult] alineación). */
    data class ExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean = false,
        val stdoutFile: File? = null,
        val stderrFile: File? = null,
        val captureError: String? = null,
    )

    /** Ejecute el Python incorporado (args adjunto después de la ruta del código auxiliar). */
    suspend fun execPython(
        ctx: Context,
        args: List<String>,
        timeoutMs: Long = 30_000L,
        workdir: File? = homeDir(ctx),
        env: Map<String, String> = emptyMap(),
    ): ExecResult {
        requireEnabled()
        return exec(
            ctx = ctx,
            cmd = listOf(pythonBinary(ctx).absolutePath) + args,
            timeoutMs = timeoutMs,
            workdir = workdir,
            env = env,
        )
    }

    private fun requireEnabled() {
        check(BuildConfig.EMBEDDED_RUNTIMES_ENABLED) {
            "Python integrado no está disponible en Muse Lite. Use la edición completa para esta función."
        }
    }

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
        val outCapture = ToolOutputCapture(outputDirectory, "python_stdout")
        val errCapture = ToolOutputCapture(outputDirectory, "python_stderr")
        val captureFailure = AtomicReference<Throwable?>(null)
        val tOut = outputReaderThread("muse-python-stdout", process.inputStream, outCapture, captureFailure)
        val tErr = outputReaderThread("muse-python-stderr", process.errorStream, errCapture, captureFailure)
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
                    outCapture.finish(header = "[stdout Salida no recibida completamente]", emptyMessage = "")
                }.getOrElse {
                    outCapture.close()
                    outCapture.savedFile?.let { "[stdout Archivo de salida parcial: ${it.absolutePath}]" }.orEmpty()
                }
            val partialStderr =
                runCatching {
                    errCapture.finish(header = "[stderr Salida no recibida completamente]", emptyMessage = "")
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

    @Suppress("TooGenericExceptionCaught") // Mismo tipo que MuseRuntime: el hilo de lectura debe capturar Throwable; de lo contrario, los errores (como OOM) se perderán silenciosamente.
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
            // Mismo tipo que MuseRuntime.outputReaderThread: el hilo de lectura debe capturar Throwable,
            // De lo contrario, los errores (como OOM) se perderán silenciosamente y la capa superior juzgará erróneamente que la salida está completa.
            failure.compareAndSet(null, error)
        }
    }, threadName)
}
