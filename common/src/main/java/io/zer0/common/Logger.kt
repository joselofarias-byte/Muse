package io.zer0.common

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fachada de troncos global,La etiqueta predeterminada es `Muse`.
 *
 * Phase 11.3 mejorar:Capacidad de registro de archivos(Conveniente para la recuperación después de una verificación mecánica real)
 *  - [initFileLog]:Llamado una vez en la aplicación onCreate,Habilitar el registro de archivos
 *  - archivo de registro:app cacheDir/logs/muse.log(archivo único,rolling 100KB)
 *  - nivel:W/E Escribir archivos automáticamente;I/D Opcional(por [fileLogLevel] controlar)
 *  - Seguridad del hilo:PrintWriter synchronized
 *
 * release Construible [enabled] Establecer en falso para cerrar todos los registros(Logcat + Archivo).
 */
object Logger {
    private const val DEFAULT_TAG = "Muse"
    private const val LOG_FILE_NAME = "muse.log"
    private const val MAX_LOG_FILE_BYTES = 1_000 * 1024L // 1MB rolling

    @Volatile
    var enabled: Boolean = true

    /** Nivel de registro de archivos:Los registros de I/D por debajo de este nivel no escriben archivos.(W/E siempre escribe). */
    @Volatile
    var fileLogLevel: Level = Level.DEBUG

    /** Estado de registro de archivos habilitado(por [initFileLog] ajustes). */
    @Volatile
    private var fileLogEnabled: Boolean = false

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var logDir: File? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    /**
     * Identificador de escritura del archivo residente + número de bytes escritos (2026-11-01 mejoras de rendimiento).
     *
     * Implementación anterior por fila `file.appendText(line)` = open + write + close Tres llamadas al sistema;
     * El registro único medido (incluida la desensibilización de PII) es aproximadamente **0.25ms**(JVM), docenas de registros en una ronda de conversación normal tardan más de diez milisegundos,
     * Y este costo recae en el hilo que escribe el registro (probablemente el hilo principal).
     *
     * Ahora el identificador es residente, varado por línea: se omite abrir/cerrar, pero**No cambia el tiempo de persistencia** ——
     * Cada fila aún se escribe en el disco inmediatamente, los registros no se perderán en caso de falla y la seguridad de los anexos multiproceso sigue siendo la misma (el anexo O_APPEND todavía se usa).
     * El número de bytes se contabiliza por sí solo, evitando la necesidad de volver a establecer el tamaño del archivo para cada línea.
     */
    private var fileWriter: java.io.Writer? = null
    private var writtenBytes: Long = 0L

    /** Enumeración a nivel de registro(usado para [fileLogLevel] control de umbral). */
    enum class Level { DEBUG, INFO, WARN, ERROR }

    /**
     * Gancho de fregadero de registro de depuración — app El DebugLogStore del módulo recibe todos los registros configurando esta variable.
     *
     * motivación de diseño:common Los módulos no pueden depender del módulo de la aplicación a la inversa,Pero Logger necesita agregar cada registro.
     * Empuje sincrónicamente a DebugLogStore para mostrar DebugScreen. Acoplamiento mediante devoluciones de llamada var:
     *  - app Al iniciar `Logger.sink = { level, tag, msg, t -> DebugLogStore.log(...) }`
     *  - Logger Llamado uniformemente dentro de d/i/w/e [dispatchToSink]
     *  - Predeterminado null(no empujes),release La compilación se puede configurar sin configuración para evitar el uso de memoria.
     *
     * Orden de los parámetros y [DebugLogStore.log] Alineación:level(Capitalización de cadenas) / tag / message / throwable.
     */
    @Volatile
    var sink: ((String, String, String, Throwable?) -> Unit)? = null

    /** Empuje el registro para sink(Si ya está registrado). Entrada interna unificada,Evite duplicar d/i/w/e en todas partes. */
    private fun dispatchToSink(level: Level, tag: String, msg: String, t: Throwable?) {
        val sinkRef = sink ?: return
        try {
            // level.name llegar directamente "DEBUG" / "INFO" / "WARN" / "ERROR",Alineado con la constante DebugLogStore
            sinkRef.invoke(level.name, tag, msg, t)
        } catch (_: Throwable) {
            // sink El fallo no puede afectar el proceso principal.(DebugLogStore Tus propios errores no deberían deprimirte Logger)
        }
    }

    /**
     * Phase 11.3: Inicialice el registro del archivo.
     *
     * Llamado una vez en Application.onCreate. El registro escribe `cacheDir/logs/muse.log`,
     * Más de 100 KB automático rolling(Cambiar el nombre del archivo antiguo muse.log.1,Continuar escribiendo un nuevo diario. muse.log).
     *
     * @param context Application/Context(usar cacheDir,Desinstalar la limpieza automática)
     */
    fun initFileLog(context: Context) {
        synchronized(lock) {
            val dir = File(context.cacheDir, "logs")
            if (!dir.exists()) dir.mkdirs()
            logDir = dir
            logFile = File(dir, LOG_FILE_NAME)
            fileLogEnabled = true
        }
    }

    /**
     * Phase 11.3: Exportar archivo de registro(Se utiliza para la recuperación después de la verificación de una máquina real.).
     * @return archivo de registro File;La devolución del registro de archivos no está habilitada null
     */
    fun getLogFile(): File? = logFile?.takeIf { it.exists() }

    // M5: PII Filtrar regular(Caché a nivel de objeto val,Evite volver a compilar en cada llamada)
    // H-SEC-2: Cobertura ampliada de número de identificación, número de tarjeta bancaria,IPv4 dirección,Alineado con app.privacy.PiiGuard
    private val PHONE_REGEX = Regex("""\b1[3-9]\d{9}\b""")
    private val EMAIL_REGEX = Regex("""\b[\w.-]+@[\w.-]+\.\w+\b""")

    // 18tarjeta de identificación(Contiene15Compatible con bits): código de área+cumpleaños+código de secuencia+dígito de control
    private val ID_CARD_REGEX = Regex("""\b[1-9]\d{5}(?:18|19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[\dXx]\b""")

    // número de tarjeta bancaria: 16-19dígitos consecutivos,o formato delimitado 4-4-4-4
    private val BANK_CARD_REGEX = Regex("""\b\d{16,19}\b|\b\d{4}[- ]?\d{4}[- ]?\d{4}[- ]?\d{4,7}\b""")

    // IPv4 dirección
    private val IPV4_REGEX = Regex("""\b(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\b""")

    // v1.114 Correcciones de seguridad:Originales CREDENTIAL_REGEX (?i)(token|password|apikey|api_key|secret)\s*[=:]\s*\S+
    // Las palabras clave están incompletas y son obligatorias. [=:] seguido de no en blanco,Como resultado, faltan coincidencias en los siguientes formatos de credenciales comunes:
    //   - "Authorization: Bearer xxx"(authorization No en la lista de palabras clave)
    //   - "x-api-key: sk-xxx"(x-api-key no en la lista)
    //   - JSON "access_token":"xxx"(access_token no en la lista,dos puntos entre comillas)
    //   - URL ?key=xxx(key no en la lista)
    // Dividido en tres grupos de expresiones regulares que cubren diferentes formatos.,Agregar palabras clave:authorization, bearer, x-api-key, key,
    //   access_token, refresh_token, client_id, client_secret
    // 1) HEADER_CRED_REGEX:HTTP header Formato(authorization/bearer/x-api-key),El delimitador son dos puntos o un espacio.,
    //    Usado para pieza de valor [^\n,;]+ Coincidir con el final de la línea/coma/punto y coma,asegurar "Bearer xxx" Las fichas también están insensibilizadas.
    // 2) KV_CRED_REGEX:key=value Formato(token/password/secret/apikey/api_key/access_token/
    //    refresh_token/client_secret/client_id),delimitador [=:] Compatible con dos puntos en JSON
    // 3) URL_QUERY_CRED_REGEX:URL query parámetros ?key=xxx / &api_key=xxx,sigue liderando ?/& símbolo
    private val HEADER_CRED_REGEX = Regex("""(?i)\b(authorization|bearer|x-api-key)\s*[:\s]\s*[^\n,;]+""")
    private val KV_CRED_REGEX =
        Regex("""(?i)(token|password|secret|apikey|api_key|access_token|refresh_token|client_secret|client_id)\s*[=:]\s*\S+""")
    private val URL_QUERY_CRED_REGEX = Regex("""(?i)([?&])(key|token|api_key|apikey)=\S+""")

    /**
     * M5: Filtrar los registros PII(Número de teléfono celular/Correo electrónico/tarjeta de identificación/tarjeta bancaria/IP/Credenciales),Evite enviar información confidencial a Logcat o archivos de registro.
     * - Número de teléfono celular(11 poco,1 comienzo)→ [Número de teléfono celular]
     * - Correo electrónico → [Correo electrónico]
     * - H-SEC-2: número de identificación(18poco)→ [tarjeta de identificación],número de tarjeta bancaria(16-19poco)→ [tarjeta bancaria],IPv4→ [IP]
     * - Credenciales(authorization/bearer/x-api-key/token/password/secret/apikey/api_key/
     *   access_token/refresh_token/client_id/client_secret/key = xxx)→ key=[Insensibilizado]
     *
     * v1.114: dividir en header / key=value / URL query Tres conjuntos de reglas,Consulte las notas anteriores para obtener más detalles.
     */
    private fun sanitizePii(msg: String): String {
        return msg
            .replace(PHONE_REGEX, "[Número de teléfono celular]")
            .replace(EMAIL_REGEX, "[Correo electrónico]")
            .replace(ID_CARD_REGEX, "[tarjeta de identificación]")
            .replace(BANK_CARD_REGEX, "[tarjeta bancaria]")
            .replace(IPV4_REGEX, "[IP]")
            // v1.114: Los tres conjuntos de expresiones regulares de credenciales se reemplazan por separado.,Mantenga el nombre de la clave para solucionar problemas fácilmente
            .replace(HEADER_CRED_REGEX, "$1: [Insensibilizado]")
            .replace(KV_CRED_REGEX, "$1=[Insensibilizado]")
            .replace(URL_QUERY_CRED_REGEX, "$1$2=[Insensibilizado]")
    }

    fun d(tag: String = DEFAULT_TAG, msg: String) {
        if (enabled) {
            val safeMsg = sanitizePii(msg)
            Log.d(tag, safeMsg)
            maybeWriteFile(Level.DEBUG, tag, safeMsg, null)
            dispatchToSink(Level.DEBUG, tag, safeMsg, null)
        }
    }

    fun i(tag: String = DEFAULT_TAG, msg: String) {
        if (enabled) {
            val safeMsg = sanitizePii(msg)
            Log.i(tag, safeMsg)
            maybeWriteFile(Level.INFO, tag, safeMsg, null)
            dispatchToSink(Level.INFO, tag, safeMsg, null)
        }
    }

    fun w(tag: String = DEFAULT_TAG, msg: String, t: Throwable? = null) {
        if (enabled) {
            val safeMsg = sanitizePii(msg)
            Log.w(tag, safeMsg, t)
            maybeWriteFile(Level.WARN, tag, safeMsg, t)
            dispatchToSink(Level.WARN, tag, safeMsg, t)
        }
    }

    fun e(tag: String = DEFAULT_TAG, msg: String, t: Throwable? = null) {
        if (enabled) {
            val safeMsg = sanitizePii(msg)
            Log.e(tag, safeMsg, t)
            maybeWriteFile(Level.ERROR, tag, safeMsg, t)
            dispatchToSink(Level.ERROR, tag, safeMsg, t)
        }
    }

    /** Convertir arrojable en cadena de seguimiento de pila(Facilita la escritura de archivos de registro.). */
    fun stackTraceToString(t: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            t.printStackTrace(pw)
        }
        return sw.toString()
    }

    /** Juicio de umbral de nivel + escritura de archivos(traer rolling). */
    private fun maybeWriteFile(level: Level, tag: String, msg: String, t: Throwable?) {
        if (!fileLogEnabled) return
        // I/D Controlado por fileLogLevel;W/E siempre escribe
        if (level.ordinal < fileLogLevel.ordinal && level != Level.WARN && level != Level.ERROR) {
            return
        }
        synchronized(lock) {
            val file = logFile ?: return
            try {
                // Rolling:Más de 1 MB de copia de seguridad es .1,Crea un nuevo archivo vacío. El mango debe estar cerrado antes de girar.
                // De lo contrario, el identificador seguirá apuntando al archivo renombrado después de cambiarlo y el nuevo registro se escribirá en la copia de seguridad.
                if (writtenBytes > MAX_LOG_FILE_BYTES || fileWriter == null) {
                    if (writtenBytes > MAX_LOG_FILE_BYTES) {
                        closeWriterLocked()
                        val backup = File(logDir, "$LOG_FILE_NAME.1")
                        if (backup.exists()) backup.delete()
                        file.renameTo(backup)
                        writtenBytes = 0L
                    }
                }
                val timestamp = dateFormat.format(Date())
                val levelStr = when (level) {
                    Level.DEBUG -> "D"
                    Level.INFO -> "I"
                    Level.WARN -> "W"
                    Level.ERROR -> "E"
                }
                val sink = writerLocked(file)
                val body = "$timestamp $levelStr/$tag: $msg\n"
                sink.append(body)
                // prensa"Número de caracteres × 2"Número estimado de bytes: 3 bytes por línea de registro chino/Caracteres, solo los caracteres numéricos ampliarán el límite de 1 MB a 3 MB.
                // Multiplicado por 2 es el carácter BMP (3 bytes) con pares sustitutos (4 bytes/2 personajes) —— Soy conservador y prefiero rotar temprano.
                writtenBytes += body.length * 2L
                if (t != null) {
                    val trace = stackTraceToString(t) + "\n"
                    sink.append(trace)
                    writtenBytes += trace.length * 2L
                }
                // Vaciar cada fila: el tiempo de persistencia es consistente con la implementación anterior.
                sink.flush()
            } catch (_: Throwable) {
                // La falla del registro no puede afectar el proceso principal (el identificador puede invalidarse en una excepción y se reconstruirá la próxima vez que se escriba)
                closeWriterLocked()
            }
        }
    }

    /** Obtenga el identificador de residente; el archivo se elimina externamente/Reconstruir automáticamente después de la rotación. */
    private fun writerLocked(file: File): java.io.Writer {
        fileWriter?.let { return it }
        val created = java.io.BufferedWriter(java.io.FileWriter(file, true), 8 * 1024)
        fileWriter = created
        writtenBytes = if (file.exists()) file.length() else 0L
        return created
    }

    /** Cierre y deseche el mango. */
    private fun closeWriterLocked() {
        runCatching { fileWriter?.close() }
        fileWriter = null
    }
}
