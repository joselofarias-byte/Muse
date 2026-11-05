package io.zer0.muse.crash

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import io.zer0.common.Logger
import io.zer0.common.resultOf
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 8.10: Manejador de fallos global + recuperación de fallos en modo seguro.
 *
 * darse cuenta:
 *  1. **captura de accidente**: [Thread.setDefaultUncaughtExceptionHandler] Interceptar excepciones no detectadas
 *  2. **registro de fallos**: escribir `filesDir/crash/crash-yyyyMMdd-HHmmss.txt`(Contiene información de pila + dispositivo)
 *  3. **Safe Mode marca**: escribir después del accidente `safe_mode.flag` Archivo,Empezar la próxima vez [checkSafeMode] Regresar true
 *     v2.0+: escribe al mismo tiempo SharedPreferences(safe_mode_pending + crash_time + crash_trace),
 *     Tiempo de caída de persistencia y resumen de pila,Para visualización directa SafeModeScreen,No más lectura de archivos
 *  4. **Safe Mode comportamiento**: Detectado al inicio flag → Omitir la carga del módulo Koin/servidor web/tareas en segundo plano,
 *     Cargue solo una interfaz de usuario mínima para avisar al usuario"último accidente,Entró en modo seguro,Registro de fallos guardado"
 *  5. **flag limpiar**: Después de la confirmación del usuario [clearSafeModeFlag] Borrar flag,Comience normalmente la próxima vez
 *
 * diseño:
 *  - Los archivos de registro de fallos conservan las últimas 5 copias,Eliminar automáticamente los más antiguos
 *  - Safe Mode flag es un archivo normal(no DataStore),Evite no poder leer cuando falla la inicialización de DataStore
 *  - v2.0+: SP Capa de persistencia y marca de archivo de doble escritura,SP no depende de Koin,Se puede leer de forma independiente.
 *  - No se reinicia después del accidente App(Evite los bucles de choque),Finalice directamente para devolver el sistema al escritorio.
 *
 * Límite:
 *  - Detectar solo excepciones no controladas(OutOfMemoryError Las subclases de error también pueden capturar)
 *  - ANR No activado(Manejado por el mecanismo ANR del sistema.)
 *  - v1.56: Compose Las excepciones de representación se detectan mediante RuntimeExceptionHandler + logComposeException
 */
class MuseCrashHandler private constructor(private val appContext: Context) : Thread.UncaughtExceptionHandler {

    private val previousHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    /** Instalado como un controlador global de excepciones no detectadas. */
    fun install() {
        Thread.setDefaultUncaughtExceptionHandler(this)
        // v1.52: Limpiar registros de fallos antiguos al iniciar(Conservar las copias MAX_CRASH_LOGS más recientes),
        // Evite que el directorio crash/ crezca(Anteriormente solo se limpiaba en caso de falla,El inicio normal no se activa).
        resultOf {
            // v1.0.53: Limpiar los directorios externos + privados.(Nuevo diario escrito external,Los registros antiguos pueden permanecer en directorios privados)
            val dirs = listOfNotNull(
                appContext.getExternalFilesDir("crash"),
                File(appContext.filesDir, "crash"),
            )
            dirs.forEach { crashDir ->
                crashDir.listFiles()
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(MAX_CRASH_LOGS)
                    ?.forEach { it.delete() }
            }
        }
        Logger.i(TAG, "Crash handler installed")
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        Logger.e(TAG, "Uncaught exception on ${t.name}", e)
        // M9: Nota:Disco de sincronización de subprocesos bloqueado IO,OOM/Puede fallar dos veces cuando el disco está lleno.
        // resultOf El paquete garantiza que no se propague,Pero es posible que el registro se pierda. Esta es una compensación común para los CrashHandlers de Android
        resultOf {
            val crashText = writeCrashLog(t, e)
            // v2.0+: Escriba la capa de persistencia de SP al mismo tiempo,Traiga el tiempo de falla y el resumen de la pila,
            // SafeModeScreen Se puede leer directamente desde el SP después del inicio.,No se requieren dependencias IO
            markSafeMode(crashText)
            // Cola de informes de fallos:Poner en cola el texto de bloqueo no sensible,Si el usuario ha autorizado el siguiente inicio,
            // Una ventana emergente que pregunta si se debe informar(No reportado por defecto,Privacidad primero)
            enqueueCrashReport(appContext, crashText)
        }
        // entregar previous handler(Generalmente el sistema predeterminado,puede jugar"La aplicación ha sido detenida."cuadro de diálogo)
        previousHandler?.uncaughtException(t, e)
    }

    /**
     * Escriba el registro de fallos en filesDir/crash/crash-yyyyMMdd-HHmmss.txt.
     *
     * v2.0+: Devolver texto de bloqueo insensible,Para que las personas que llaman escriban en la capa de persistencia SharedPreferences,
     * SafeModeScreen Las impresiones se pueden leer directamente desde el SP.,Ya no es necesario leer archivos.
     */
    private fun writeCrashLog(t: Thread, e: Throwable): String {
        // v1.0.53: Prioridad externalFilesDir(adb/Administrador de archivos visible),reserva privada filesDir
        val externalCrashDir = appContext.getExternalFilesDir("crash")
        val crashDir = (externalCrashDir ?: File(appContext.filesDir, "crash")).apply { mkdirs() }
        val fmt = SimpleDateFormat(CRASH_TIME_FMT, Locale.US)
        val fileName = "crash-${fmt.format(Date())}.txt"
        val logFile = File(crashDir, fileName)

        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("===== muse crash log =====")
            pw.println("Time: ${Date()}")
            pw.println("Thread: ${t.name} (id=${t.id})")
            pw.println()
            pw.println("----- Device Info -----")
            pw.println("Brand: ${Build.BRAND}")
            pw.println("Model: ${Build.MODEL}")
            pw.println("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            pw.println("ABIs: ${Build.SUPPORTED_ABIS.joinToString(",")}")
            val runtime = Runtime.getRuntime()
            val maxMb = runtime.maxMemory() / (1024 * 1024)
            val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            pw.println("Heap: used ${usedMb}MB / max ${maxMb}MB")
            pw.println("OutOfMemory: ${e is OutOfMemoryError}")
            pw.println()
            pw.println("----- Stack Trace -----")
            e.printStackTrace(pw)
        }
        // v1.104: Escribir disco después de la desensibilización.(evitar API key / Bearer token / Authorization Encabezado filtrado al registro de fallos)
        val redacted = redactSensitive(sw.toString())
        logFile.writeText(redacted)

        // Limpiar registros de fallos antiguos(Guarde las últimas 5 copias)
        crashDir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_CRASH_LOGS)
            ?.forEach { it.delete() }

        return redacted
    }

    /**
     * Escriba el archivo safe_mode.flag + capa de persistencia SharedPreferences.
     *
     * v2.0+: Basado en la bandera del archivo original,Escritura sincrónica SP:
     *  - safe_mode_pending = true
     *  - crash_time = hora actual(yyyy-MM-dd HH:mm:ss Formato,Para visualización de interfaz de usuario)
     *  - crash_trace = Resumen de la pila de fallos(Truncar a [MAX_SP_TRACE_LENGTH] personaje,Evite que SP escriba cadenas muy grandes)
     *
     * Estrategia de doble escritura:La bandera del archivo es una ruta históricamente compatible.,SP Es una nueva capa de datos estructurados.
     * SafeModeScreen leer primero SP(estructurado),Archivo de lectura alternativa(Escenarios para actualizar versiones antiguas sin SP).
     */
    private fun markSafeMode(crashTrace: String = "") {
        File(appContext.filesDir, SAFE_MODE_FLAG).writeText("1")
        val now = SimpleDateFormat(CRASH_TIME_DISPLAY_FMT, Locale.US).format(Date())
        writeSafeModeSp(appContext, now, crashTrace.take(MAX_SP_TRACE_LENGTH))
    }

    companion object {
        private const val TAG = "MuseCrashHandler"
        private const val SAFE_MODE_FLAG = "safe_mode.flag"
        private const val MAX_CRASH_LOGS = 5

        // L4-1: Cadena de formato de marca de tiempo del registro de fallas
        private const val CRASH_TIME_FMT = "yyyyMMdd-HHmmss"

        // v2.0+: SP Formato de visualización del tiempo de caída medio(formato legible,Para que SafeModeScreen se muestre directamente)
        private const val CRASH_TIME_DISPLAY_FMT = "yyyy-MM-dd HH:mm:ss"

        // v2.0+: SP nombre de archivo — Archivo SP independiente,Evite conflictos con otros SP de módulo,y no depende de Koin
        private const val SAFE_MODE_SP = "muse_safe_mode"

        // v2.0+: SP campo key — Sea coherente con las especificaciones de la misión.(safe_mode_pending + crash_time + crash_trace)
        private const val SP_KEY_PENDING = "safe_mode_pending"
        private const val SP_KEY_CRASH_TIME = "crash_time"
        private const val SP_KEY_CRASH_TRACE = "crash_trace"

        // v2.0+: SP crash_trace Longitud máxima del campo(Evite que SP escriba cadenas muy grandes causadas por ANR)
        private const val MAX_SP_TRACE_LENGTH = 8000

        // Nombre del archivo SP de la cola de informes de fallos — Separado del SP modo_seguro,No se afecten unos a otros,y no depende de Koin
        private const val CRASH_QUEUE_SP = "muse_crash_queue"

        // campo de cola key — Sea coherente con las especificaciones de la misión.(queue_crash_reports)
        private const val SP_KEY_QUEUE = "queue_crash_reports"

        // límite de cola — más allá de descartar los más antiguos,Evitar la acumulación de SP provocando ANR
        private const val MAX_QUEUE_SIZE = 10

        /** Obtenga modo seguro exclusivo SharedPreferences(no depende de Koin,Se puede leer temprano al inicio). */
        private fun safeModeSp(appContext: Context): SharedPreferences = appContext.getSharedPreferences(SAFE_MODE_SP, Context.MODE_PRIVATE)

        /** Obtenga una cola de informes de fallas dedicada SharedPreferences(no depende de Koin,Se puede leer temprano al inicio). */
        private fun crashQueueSp(appContext: Context): SharedPreferences = appContext.getSharedPreferences(CRASH_QUEUE_SP, Context.MODE_PRIVATE)

        /**
         * Leer la cola que se informará desde SP — conjunto de cadenas separadas por nuevas líneas(Evite introducir dependencias JSON).
         *
         * Cada registro es un texto de bloqueo insensibilizado.(Contiene encabezado de información del dispositivo + pila),usar [QUEUE_DELIMITER] Separado.
         * Devuelve una lista vacía cuando falla el análisis(Compatible con versiones anteriores sin este escenario de daño de campo/SP).
         */
        private fun readQueueFromSp(sp: SharedPreferences): List<String> {
            val raw = sp.getString(SP_KEY_QUEUE, null) ?: return emptyList()
            if (raw.isEmpty()) return emptyList()
            return raw.split(QUEUE_DELIMITER).filter { it.isNotEmpty() }
        }

        /** Codificar cola como cadena de almacenamiento SP(usar [QUEUE_DELIMITER] empalme). */
        private fun encodeQueue(queue: List<String>): String = queue.joinToString(QUEUE_DELIMITER)

        // Separador de entrada de cola — Utilice una secuencia de caracteres invisibles de ancho cero,Evite conflictos con el texto de la pila de fallos
        private const val QUEUE_DELIMITER = "\u0000\u0001<CRASH_ENTRY>\u0001\u0000"

        /**
         * Ponga en cola el texto del fallo que se informará a la cola.
         *
         * tiempo de llamada:
         *  - [uncaughtException]:Los subprocesos bloqueados se ponen en cola sincrónicamente
         *  - [logComposeException]:Compose Cola de excepción de renderizado
         *
         * diseño:
         *  - límite de cola [MAX_QUEUE_SIZE] Artículo,más allá de descartar los más antiguos(FIFO)
         *  - usar commit() Colocación sincrónica,Asegúrese de que los datos no se pierdan cuando el proceso finalice después de un bloqueo
         *  - Almacenar sólo texto de bloqueo no sensible,La información del dispositivo se pasa al generar informes [buildStandardMetadata] Retirar ahora
         *  - Cola separada del archivo SP de modo seguro,No se afecten unos a otros
         *
         * Tiempo de presentación de informes: cuando el usuario hace clic en "Informar inmediatamente", se ajustará desde la página de configuración. [io.zer0.muse.crash.CrashReporterFactory.submit],
         * Exportación reportada arreglada (servidor de desarrollador) → En caso de no regresar al correo electrónico del desarrollador), el usuario no necesita ni proporciona ninguna configuración.
         */
        fun enqueueCrashReport(appContext: Context, crashText: String) {
            val sp = crashQueueSp(appContext)
            val queue = readQueueFromSp(sp).toMutableList()
            queue.add(crashText.take(MAX_SP_TRACE_LENGTH))
            // Descartar el más antiguo cuando se exceda el límite superior(encabezado de lista)
            while (queue.size > MAX_QUEUE_SIZE) queue.removeAt(0)
            // commit() Colocación sincrónica:Un proceso puede finalizar inmediatamente después de un accidente kill,apply() Es posible que se pierdan las escrituras asincrónicas
            sp.edit().putString(SP_KEY_QUEUE, encodeQueue(queue)).commit()
        }

        /**
         * Lea la lista de accidentes que se informarán(Según el orden de incorporación a la cola.,el mayor primero).
         *
         * La persona que llama debe recorrer la lista e informar los elementos uno por uno después de la autorización del usuario.,Ajustar después de completar [clearCrashQueue] Vacío.
         */
        fun getPendingCrashReports(appContext: Context): List<String> = readQueueFromSp(crashQueueSp(appContext))

        /** ¿Hay algún accidente que reportar?(chequeo rapido,Evite recorrer toda la lista al iniciar). */
        fun hasPendingCrashReports(appContext: Context): Boolean = readQueueFromSp(crashQueueSp(appContext)).isNotEmpty()

        /** Limpiar la cola para ser reportado(Se llama cuando el usuario ha informado o ha elegido no informar). */
        fun clearCrashQueue(appContext: Context) {
            crashQueueSp(appContext).edit().clear().commit()
        }

        /** Escribir una capa de persistencia en modo seguro para SharedPreferences(Contiene el tiempo de falla y el resumen de la pila.). */
        private fun writeSafeModeSp(appContext: Context, crashTime: String, crashTrace: String) {
            // usar commit() Colocación sincrónica:Después de un accidente el proceso puede ser inmediatamente kill,apply() Es posible que no se complete la escritura en disco asincrónica,
            // Como resultado, no se podrá leer la próxima vez que se inicie. safe_mode_pending,Los choques repetidos forman un ciclo de choques.
            safeModeSp(appContext).edit()
                .putBoolean(SP_KEY_PENDING, true)
                .putString(SP_KEY_CRASH_TIME, crashTime)
                .putString(SP_KEY_CRASH_TRACE, crashTrace)
                .commit()
        }

        /**
         * v1.104: Desensibilización del registro de fallos — Usar regular API key / Bearer token / Authorization encabezado reemplazado con ***.
         *
         * La pila de fallos puede contener solicitudes OkHttp URL(Contiene el parámetro de consulta apiKey)o excepción personalizada message
         * (Contiene el encabezado de autorización). Estas cadenas confidenciales pueden filtrar credenciales si se envían como correos electrónicos zip.
         * Cubrir patrones comunes:
         *  - `apiKey=xxx` / `api_key=xxx`(URL parámetros de consulta)
         *  - `Authorization: Bearer xxx` / `Authorization: xxx`(HTTP cabeza)
         *  - `Bearer xxx`(desnudo token)
         *  - `sk-` / `sk-ant-` comienzo OpenAI/Anthropic key
         */
        private val REDACT_PATTERNS = listOf(
            // URL parámetros de consulta apiKey=xxx / api_key=xxx
            Regex("""(?i)(api[_-]?key=)[^&\s"'<>]+"""),
            // Authorization: Bearer xxx / Authorization: xxx
            Regex("""(?i)(authorization\s*:\s*)(bearer\s+)?[^\s\r\n<>]+"""),
            // desnudo Bearer xxx
            Regex("""(?i)(bearer\s+)[A-Za-z0-9\-_.=]+"""),
            // OpenAI sk- / Anthropic sk-ant- comienzo key(Al menos 20 caracteres)
            Regex("""\bsk-(?:ant-)?[A-Za-z0-9\-_]{20,}"""),
        )

        fun redactSensitive(text: String): String {
            var result = text
            for (p in REDACT_PATTERNS) {
                result = p.replace(result) { mr ->
                    val g1 = mr.groupValues.getOrNull(1)
                    if (g1.isNullOrEmpty()) "***" else "$g1***"
                }
            }
            return result
        }

        /** Instalar el controlador de fallos global(debería estar en [android.app.Application.onCreate] llamada más temprana). */
        fun install(appContext: Context) {
            MuseCrashHandler(appContext).install()
        }

        /**
         * Comprobar si se debe realizar la entrada Safe Mode(último accidente).
         *
         * v2.0+: También verifique la bandera del archivo y SP safe_mode_pending,Devuelve verdadero si alguno de ellos existe.
         * Verifique dos veces para garantizar la versión anterior(escribir solo archivo)El estado del Modo seguro aún se reconoce después de la actualización.
         */
        fun checkSafeMode(appContext: Context): Boolean = File(appContext.filesDir, SAFE_MODE_FLAG).exists() ||
            safeModeSp(appContext).getBoolean(SP_KEY_PENDING, false)

        /**
         * Borrar indicador de modo seguro(Llamado después de la confirmación del usuario.,Comience normalmente la próxima vez).
         *
         * v2.0+: Borre la bandera del archivo y la capa de persistencia del SP al mismo tiempo,Evite que cualquier residuo provoque el ingreso repetido al Modo seguro.
         *
         * P0 Reparación:Utilizado anteriormente para borrar SP apply() Colocación asincrónica,Inmediatamente después [android.os.Process.killProcess]
         * finalizará el proceso inmediatamente,Hace que el SP muera antes de que se complete la escritura. Empezar la próxima vez [checkSafeMode] leer
         * safe_mode_pending todavía true,Ingrese nuevamente al modo seguro,forma"Continúe comenzando normalmente → Ingrese nuevamente al modo seguro"ciclo.
         * Además, el archivo de registro de fallos no se limpia.,[SafeModeScreen] lectura alternativa [readLatestCrashLog] Todavía mostrando la pila antigua.
         *
         * Estrategia de reparación:
         *  1. SP Usar en su lugar commit() Colocación sincrónica,Asegúrese de que los datos persistan antes de killProcess
         *  2. Eliminar sincrónicamente archivos de registro de fallas,Evite iniciar SafeModeScreen la próxima vez readLatestCrashLog
         *     La reversión muestra la pila de fallas anterior(Incluso si el SP está autorizado,Los restos de archivos aún permiten a los usuarios ver mensajes de fallos antiguos)
         */
        fun clearSafeModeFlag(appContext: Context) {
            File(appContext.filesDir, SAFE_MODE_FLAG).delete()
            // commit() Colocación sincrónica:Seguimiento inmediato killProcess,apply() Es posible que las escrituras asincrónicas no se completen,
            // Como resultado, safe_mode_pending seguirá estando la próxima vez que se inicie el SP. true,Ingrese al modo seguro repetidamente
            safeModeSp(appContext).edit().clear().commit()
            // Borrar archivos de registro de fallos sincrónicamente:SafeModeScreen Cuando el SP no tiene crash_trace, volverá a leer el archivo.,
            // Si no se realiza la limpieza, la pila de fallos anterior seguirá mostrándose en el siguiente inicio.
            listCrashLogs(appContext).forEach { it.delete() }
        }

        /**
         * v1.91-hotfix: Marcado activo Safe Mode(Se utiliza para escenarios sin fallas, como fallas de startKoin.).
         *
         * Cuando startKoin lanza una excepción,MuseCrashHandler La excepción uncaughtException no se activará
         * (Porque la excepción es detectada por resultOf),Debe marcarse manualmente mediante este método. Safe Mode,
         * Asegúrese de iniciar en modo seguro la próxima vez en lugar de repetir el bloqueo.
         *
         * v2.0+: Escritura sincrónica en la capa de persistencia de SP,Registre el tiempo de colisión y solicite información(El seguimiento del escenario sin fallo es la copia inmediata).
         */
        fun markSafeMode(appContext: Context) {
            File(appContext.filesDir, SAFE_MODE_FLAG).writeText("1")
            val now = SimpleDateFormat(CRASH_TIME_DISPLAY_FMT, Locale.US).format(Date())
            // escenario sin accidente(startKoin Fracaso, etc),trace Copia del mensaje de escritura de campo,
            // Deje que SafeModeScreen diferencie entre pantallas
            writeSafeModeSp(appContext, now, "Koin El inicio falló(Excepción durante la fase de inicialización de la aplicación,Fallo del hilo no principal)")
        }

        /**
         * v2.0+: Leer la hora del último accidente(yyyy-MM-dd HH:mm:ss Formato).
         *
         * fuente de datos:SharedPreferences capa de persistencia,Accidente causado por [markSafeMode] Escribir.
         * Si no hay valor en SP(Escenarios de actualización de versiones antiguas),Regresar null,La persona que llama puede recurrir al mtime del archivo de registro de fallos.
         */
        fun getCrashTime(appContext: Context): String? = safeModeSp(appContext).getString(SP_KEY_CRASH_TIME, null)

        /**
         * v2.0+: Lea el último resumen de la pila de fallos(Texto completo del registro de fallos después de la desensibilización).
         *
         * fuente de datos:SharedPreferences capa de persistencia,Truncado a [MAX_SP_TRACE_LENGTH] personajes.
         * Si no hay valor en SP,Regresar null,La persona que llama debe recurrir a [readLatestCrashLog] Lea el archivo.
         */
        fun getCrashTrace(appContext: Context): String? = safeModeSp(appContext).getString(SP_KEY_CRASH_TRACE, null)

        /**
         * Listar todos los archivos de registro de fallos(Orden descendente por tiempo).
         *
         * v2.x: Escanear también directorios externos(externalFilesDir/crash,La ubicación real del registro de fallos actual.)
         * con directorios privados(filesDir/crash,Escenarios en los que permanecen versiones antiguas o el almacenamiento externo no está disponible),
         * Fusionar después de eliminar duplicados por nombre de archivo — Directorio privado previamente de solo lectura,Panel de registro de causas de fallos/La exportación está vacía.
         */
        fun listCrashLogs(appContext: Context): List<File> {
            val dirs = listOfNotNull(appContext.getExternalFilesDir("crash"), File(appContext.filesDir, "crash"))
            return dirs
                .flatMap { dir -> dir.listFiles()?.toList() ?: emptyList() }
                .distinctBy { it.name }
                .sortedByDescending { it.lastModified() }
        }

        /** Lea el contenido del último registro de fallos(Se utiliza para la visualización de la interfaz de usuario en modo seguro). */
        fun readLatestCrashLog(appContext: Context): String? {
            val logs = listCrashLogs(appContext)
            return logs.firstOrNull()?.readText()
        }

        /**
         * v1.95: Empaquetar todos los registros de fallos + información del dispositivo en zip,Envíe archivos adjuntos de correo electrónico con un solo clic.
         *
         * zip Contiene device_info.txt(Equipo/Resumen de versión)+ Cada archivo de registro de fallos.
         * ubicado en cacheDir(Expuesto a través de FileProvider),Devuelve nulo si no hay registros.
         */
        fun packageCrashLogsToZip(appContext: Context): File? {
            val logs = listCrashLogs(appContext)
            val zipFile = File(appContext.cacheDir, "muse_crash_logs.zip")
            // Generado independientemente de si hay un registro. zip(Contiene al menos información del dispositivo.),Facilitar la retroalimentación de los usuarios
            zipFile.outputStream().use { fos ->
                java.util.zip.ZipOutputStream(fos).use { zos ->
                    val deviceInfo = buildString {
                        appendLine("===== muse crash report =====")
                        appendLine("Time: ${Date()}")
                        appendLine("Brand: ${Build.BRAND}")
                        appendLine("Model: ${Build.MODEL}")
                        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                        appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString(",")}")
                    }
                    zos.putNextEntry(java.util.zip.ZipEntry("device_info.txt"))
                    zos.write(deviceInfo.toByteArray())
                    zos.closeEntry()
                    logs.forEach { log ->
                        zos.putNextEntry(java.util.zip.ZipEntry(log.name))
                        zos.write(log.readBytes())
                        zos.closeEntry()
                    }
                }
            }
            return zipFile.takeIf { logs.isNotEmpty() }
        }

        /**
         * v1.56: Registro de excepciones de representación de redacción(No mates el proceso,Solo escribe registros + etiquetas Safe Mode).
         *
         * Compose El RuntimeExceptionHandler no funciona Thread.setDefaultUncaughtExceptionHandler,
         * Debe registrarse manualmente mediante este método. Los usuarios pueden ver el registro de fallos en Modo seguro en el próximo inicio.
         *
         * v2.0+: Escritura sincrónica en la capa de persistencia de SP(Contiene el tiempo de falla y el resumen de la pila.),Alineado con la ruta de excepción no capturada.
         */
        fun logComposeException(appContext: Context, throwable: Throwable) {
            Logger.e(TAG, "Compose runtime exception", throwable)
            resultOf {
                val crashDir = File(appContext.filesDir, "crash").apply { mkdirs() }
                val fmt = SimpleDateFormat(CRASH_TIME_FMT, Locale.US)
                val fileName = "crash-compose-${fmt.format(Date())}.txt"
                val logFile = File(crashDir, fileName)

                val sw = StringWriter()
                PrintWriter(sw).use { pw ->
                    pw.println("===== muse compose crash log =====")
                    pw.println("Time: ${Date()}")
                    pw.println("Thread: ${Thread.currentThread().name} (id=${Thread.currentThread().id})")
                    pw.println()
                    pw.println("----- Device Info -----")
                    pw.println("Brand: ${Build.BRAND}")
                    pw.println("Model: ${Build.MODEL}")
                    pw.println("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    pw.println("ABIs: ${Build.SUPPORTED_ABIS.joinToString(",")}")
                    pw.println()
                    pw.println("----- Stack Trace -----")
                    throwable.printStackTrace(pw)
                }
                // v1.104: Escribir disco después de la desensibilización.
                val redacted = redactSensitive(sw.toString())
                logFile.writeText(redacted)

                // Limpiar registros de fallos antiguos
                crashDir.listFiles()
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(MAX_CRASH_LOGS)
                    ?.forEach { it.delete() }

                // marca Safe Mode,Avisar al usuario en el próximo inicio
                // v2.0+: Bandera de archivo + doble escritura SP(SP Contiene el tiempo de falla y el resumen de la pila.)
                File(appContext.filesDir, SAFE_MODE_FLAG).writeText("1")
                val now = SimpleDateFormat(CRASH_TIME_DISPLAY_FMT, Locale.US).format(Date())
                writeSafeModeSp(appContext, now, redacted.take(MAX_SP_TRACE_LENGTH))

                // Cola de informes de fallos:Alinear con la ruta de excepción no detectada,Excepciones de representación en cola de composición
                enqueueCrashReport(appContext, redacted)
            }
        }
    }
}
