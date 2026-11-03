package io.zer0.muse.automation.executors

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.automation.core.PermissionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Root actuador —— La tercera capa de automatización de la interfaz de usuario.
 *
 * herencia [ShellExecutor],Simplemente cambie el prefijo de comando de `sh` Reemplazar con `su`.
 * Habilidades adicionales:
 * - Acceda al directorio /data/data de otras aplicaciones
 * - Modificar archivos del sistema,iptables,kill cualquier proceso
 * - Instalación silenciosa/Desinstalar, otorgar permisos de tiempo de ejecución
 *
 * Detección: correr `su -c id`,Compruebe si la salida contiene "uid=0".
 * Todos los comandos en dispositivos no rooteados fallan silenciosamente.
 *
 * H-SEC-1: Todos los parámetros están verificados en la lista blanca.,Evite que la inyección rápida provoque una inyección de comando arbitraria.
 */
class RootExecutor(
    context: Context,
) : ShellExecutor(context) {

    override val level = PermissionLevel.ROOT

    override val shellPrefix: List<String> = listOf("su")

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        if (!isRooted()) return@withContext false
        val result = exec("id")
        result.isSuccess && result.getOrDefault("").contains("uid=0")
    }

    /** Verifique el dispositivo para ver si tiene el esquema raíz binario o del kernel.(Es posible que no tengas permiso para ejecutar). */
    private fun isRooted(): Boolean {
        return try {
            val paths = arrayOf(
                "/system/bin/su",
                "/system/xbin/su",
                "/sbin/su",
                "/system/sd/xbin/su",
                "/system/bin/failsafe/su",
                "/data/local/xbin/su",
                "/data/local/bin/su",
                "/data/local/su",
                "/su/bin/su",
            )
            // v2.0: KernelSU departamento(GKI/LKM)No exponer archivos su,sucompat interceptado por el kernel;
            // /data/adb Es el directorio de datos del esquema raíz.,como señal de preselección.
            paths.any { File(it).exists() } || File("/data/adb").exists() || runCatching {
                ProcessBuilder("which", "su").start().inputStream.bufferedReader().readText().isNotBlank()
            }.getOrDefault(false)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Solicitar explícitamente autorización de Root:Ejecutar una vez `su -c id`,root Gerente(Magisk/KernelSU espera)El cuadro de diálogo de autorización aparecerá en consecuencia.
     *
     * Sólo vale la pena solicitarlo si existe un binario su,De lo contrario regresa directamente [RootRequestFailure.NO_SU_BINARY],No bloquear UI;
     * El cuadro de diálogo de autorización puede permanecer desatendido durante mucho tiempo.,esperar más que [timeoutMs] Es decir, destruir el proceso hijo y devolverlo.
     * [RootRequestFailure.TIMEOUT]. Este método solo inicia la autorización.,No elevar privilegios silenciosamente,Tampoco almacena en caché los resultados. ——
     * El estado de autorización es siempre [isAvailable] Prevalecerá la detección real.
     *
     * @return ¿Lo entendiste? uid=0,y una razón legible en caso de falla
     */
    suspend fun requestRootAccess(timeoutMs: Long = ROOT_REQUEST_TIMEOUT_MS): RootRequestResult = withContext(Dispatchers.IO) {
        if (!isRooted()) {
            Logger.i(TAG, "root Solicitud de autorización omitida: dispositivo sin su binario")
            return@withContext RootRequestResult.failed(RootRequestFailure.NO_SU_BINARY)
        }
        val process = try {
            ProcessBuilder(rootProbeArgs(shellPrefix))
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            Logger.w(TAG, "root No se puede iniciar la solicitud de autorización: ${e.message}")
            return@withContext RootRequestResult.failed(classifyRootProbeException(e))
        }
        try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                // root La ventana emergente del administrador no ha sido procesada:Mata a los que esperan su,Evite que las solicitudes posteriores se pongan en cola y que la página de configuración se bloquee.
                runCatching { process.destroyForcibly() }
                Logger.w(TAG, "root Se agotó el tiempo de espera de la solicitud de autorización(${timeoutMs}ms)")
                return@withContext rootProbeTimeout()
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val result = evaluateRootProbe(process.exitValue(), output)
            Logger.i(TAG, "root Solicitud de autorización finalizada: granted=${result.granted} failure=${result.failure}")
            result
        } catch (e: Exception) {
            runCatching { process.destroyForcibly() }
            Logger.w(TAG, "root Excepción de solicitud de autorización: ${e.message}")
            RootRequestResult.failed(classifyRootProbeException(e))
        }
    }

    /** H-SEC-1: Verificación de la lista blanca de nombres de paquetes,Rechazar argumentos que contengan metacaracteres de shell. */
    private fun validatePackageName(name: String): Boolean = Regex("^[a-zA-Z][a-zA-Z0-9_.]*\$").matches(name)

    /** H-SEC-1: Verificación de la lista blanca de rutas,Denegar el recorrido de ruta y los caracteres de inyección de shell. */
    private fun validatePath(path: String): Boolean = Regex("^[/a-zA-Z0-9_.\\-]+$").matches(path)

    /**
     * Resolve an APK only from directories where this app stages APK files.
     * Canonicalization also prevents symlinks from escaping those directories.
     */
    private fun resolveAllowedApkPath(path: String): String? {
        if (!path.startsWith('/') || !validatePath(path)) return null
        if (path.split('/').any { it == "." || it == ".." }) return null
        if (!path.endsWith(".apk", ignoreCase = true)) return null
        val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        if (!validatePath(candidate.path)) return null
        val allowedRoots = buildList {
            add(context.cacheDir)
            add(context.filesDir)
            context.getExternalFilesDir(null)?.let(::add)
            // Standard adb/installer staging directory; root pm can read it directly.
            add(File("/data/local/tmp"))
        }.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        return candidate.path.takeIf { candidate.isWithinAny(allowedRoots) }
    }

    private fun File.isWithinAny(roots: List<File>): Boolean = roots.any { root ->
        path == root.path || path.startsWith(root.path + File.separator)
    }

    /** Instalación silenciosa APK(root No se requiere confirmación del usuario bajo pm install). */
    suspend fun installApk(apkPath: String): Boolean {
        val canonicalPath = resolveAllowedApkPath(apkPath)
        if (canonicalPath == null) {
            Logger.w(TAG, "installApk rechazar: Ruta APK ilegal o no permitida")
            return false
        }
        val result = exec("pm install -r $canonicalPath")
        return result.isSuccess && result.getOrDefault("").contains("Success", ignoreCase = true)
    }

    /** Desinstalar App(root abajo pm uninstall). */
    suspend fun uninstallApp(packageName: String): Boolean {
        if (!validatePackageName(packageName)) {
            Logger.w(TAG, "uninstallApp rechazar: Nombre de paquete ilegal $packageName")
            return false
        }
        return exec("pm uninstall $packageName").isSuccess
    }

    /** Conceder permisos de tiempo de ejecución(root abajo pm grant). */
    suspend fun grantPermission(packageName: String, permission: String): Boolean {
        if (!validatePackageName(packageName)) {
            Logger.w(TAG, "grantPermission rechazar: Nombre de paquete ilegal $packageName")
            return false
        }
        // permission El formato es como android.permission.SEND_SMS,Sólo se permiten letras/numeros/punto/subrayado
        if (!Regex("^[a-zA-Z][a-zA-Z0-9_.]*\$").matches(permission)) {
            Logger.w(TAG, "grantPermission rechazar: Nombre de permiso ilegal $permission")
            return false
        }
        return exec("pm grant $packageName $permission").isSuccess
    }

    /** Forzar la detención de la aplicación. */
    suspend fun forceStop(packageName: String): Boolean {
        if (!validatePackageName(packageName)) {
            Logger.w(TAG, "forceStop rechazar: Nombre de paquete ilegal $packageName")
            return false
        }
        return exec("am force-stop $packageName").isSuccess
    }

    /** Leer otros archivos del directorio privado de la aplicación(necesidad root). */
    suspend fun readAppFile(appPackage: String, relativePath: String): String? {
        if (!validatePackageName(appPackage)) {
            Logger.w(TAG, "readAppFile rechazar: Nombre de paquete ilegal $appPackage")
            return null
        }
        // relativePath No se permiten caminos absolutos ni cruces de caminos.
        if (relativePath.startsWith("/") || relativePath.contains("..")) {
            Logger.w(TAG, "readAppFile rechazar: Camino relativo ilegal $relativePath")
            return null
        }
        if (!validatePath(relativePath)) {
            Logger.w(TAG, "readAppFile rechazar: La ruta contiene caracteres ilegales $relativePath")
            return null
        }
        val path = "/data/data/$appPackage/$relativePath"
        val result = exec("cat $path")
        return if (result.isSuccess) result.getOrDefault("") else null
    }

    // ── Root-specific capabilities ──────────────────────────────────────

    /**
     * Read an Android settings value by name.
     * Supports global/secure/system namespace via prefix: `global:name`, `secure:name`, `system:name`.
     * Without prefix defaults to `secure:`.
     */
    /**
     * v2.x: Alternar interruptor de red inalámbrica(`svc wifi|data enable|disable`).
     *
     * Solo lista blanca wifi / data;Vuelven el resto de servicios null(prensa de llamada"No compatible"Proceso).
     * El comando es empalme fijo.,Sin superficie de inyección de entrada del usuario. Requiere raíz.
     */
    suspend fun svcToggle(service: String, enabled: Boolean): Boolean? {
        val svc = service.trim().lowercase()
        if (svc != "wifi" && svc != "data") {
            Logger.w(TAG, "svcToggle rechazar: Servicios no compatibles $service")
            return null
        }
        val action = if (enabled) "enable" else "disable"
        return exec("svc $svc $action").isSuccess
    }

    suspend fun settingsGet(name: String): String? {
        // v2.0.1: apoyo "namespace:name"(Como por ejemplo secure:location_mode);Ningún prefijo es seguro por defecto.
        val idx = name.indexOf(':')
        val nsPart = if (idx >= 0) name.substring(0, idx) else "secure"
        val keyPart = if (idx >= 0) name.substring(idx + 1) else name
        val safeNs = validateSettingsNamespace(nsPart)
        val safeName = validateSettingsName(keyPart)
        if (safeNs == null || safeName == null) {
            Logger.w(TAG, "settingsGet rechazar: Nombre de configuración ilegal $name")
            return null
        }
        val result = exec("settings get $safeNs$safeName")
        return if (result.isSuccess) result.getOrDefault(null) else null
    }

    /**
     * Write an Android settings value.
     * Format: `namespace:name` (e.g., `global:airplane_mode_on`).
     * Requires root. Falls back to `secure:` namespace if none specified.
     */
    suspend fun settingsPut(namespace: String, name: String, value: String): Boolean {
        val safeNs = validateSettingsNamespace(namespace)
        val safeName = validateSettingsName(name)
        if (safeNs == null || safeName == null) {
            Logger.w(TAG, "settingsPut rechazar: Parámetros ilegales ns=$namespace name=$name")
            return false
        }
        // v2.0.1: Sólo imprimible ASCII,y escapar con escapeDoubleQuoted,Evite la inyección de cáscara de raíz.
        val safeValue = value.filter { it.code in 32..126 }
        return exec("settings put $safeNs$safeName \"${escapeDoubleQuoted(safeValue)}\"").isSuccess
    }

    /**
     * Launch an activity via `am start` with optional extras.
     * Extras format: `key=value` pairs separated by `|`, e.g. `title=Hello|count=5`.
     */
    suspend fun amStart(packageName: String, className: String? = null, extras: String? = null): Boolean {
        if (!validatePackageName(packageName)) {
            Logger.w(TAG, "amStart rechazar: Nombre de paquete ilegal $packageName")
            return false
        }
        // v2.0.1: Los nombres de los componentes y los extras deben incluirse primero en la lista blanca.,Luego ingrese el comando de shell raíz,Prevenir la inyección de comandos.
        val safeClass = className?.trim()?.takeIf { it.isNotBlank() }?.let { cls ->
            val normalized = if (cls.startsWith(".")) "$packageName$cls" else cls
            if (!COMPONENT_NAME_REGEX.matches(normalized)) {
                Logger.w(TAG, "amStart rejected: invalid component name")
                return false
            }
            normalized
        }
        val safeExtras = extras?.takeIf { it.isNotBlank() }?.let { e ->
            e.split("|").mapNotNull { pair ->
                val eq = pair.indexOf('=')
                if (eq <= 0) return@mapNotNull null
                val key = pair.substring(0, eq).trim()
                val rawValue = pair.substring(eq + 1)
                if (key.isBlank() || rawValue.isBlank() || !EXTRA_KEY_REGEX.matches(key)) return@mapNotNull null
                "--es $key \"${escapeDoubleQuoted(rawValue)}\""
            }.joinToString(" ").takeIf { it.isNotEmpty() }
        }
        val cmd = buildString {
            append("am start -n $packageName")
            if (safeClass != null) append("/$safeClass")
            if (safeExtras != null) append(" $safeExtras")
        }
        return exec(cmd).isSuccess
    }

    /**
     * List installed packages, optionally filtered by a substring match on package name.
     */
    suspend fun listPackages(filter: String? = null): List<String> {
        // v2.0.1: Utilice los parámetros de filtrado nativos de los paquetes de lista pm en su lugar;Verificación de la lista blanca primero,Evite la inyección de cáscara de raíz.
        val safeFilter = filter?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (!PACKAGE_FILTER_REGEX.matches(it)) {
                Logger.w(TAG, "listPackages rejected: invalid filter")
                return emptyList()
            }
            it
        }
        val cmd = "pm list packages" + (safeFilter?.let { " $it" } ?: "")
        val result = exec(cmd)
        if (!result.isSuccess) return emptyList()
        return result.getOrDefault("").lineSequence()
            .mapNotNull { line -> Regex("package:(.+)").find(line)?.groupValues?.get(1) }
            .distinct()
            .sorted()
            .toList()
    }

    /** Tail the device logcat (last [lines] lines); the caller owns context-aware delivery. */
    suspend fun logcatTail(lines: Int = 100): String {
        val cmd = "logcat -d -t $lines"
        val result = exec(cmd)
        if (!result.isSuccess) {
            val detail = result.getOrDefault("")
            return "logcat failed: $detail"
        }
        return result.getOrDefault("")
    }

    /**
     * Inject raw text into the focused input field via `su input text`.
     * Safer than shell inputText because su context has different restrictions.
     */
    suspend fun inputInject(text: String): Boolean {
        if (text.isBlank()) return false
        // v2.0.1: Las cadenas entre comillas dobles deben tener caracteres de escape. \\ \" $ ` con saltos de línea,De lo contrario, se pueden inyectar comandos arbitrarios en el shell raíz.
        val result = exec("input text \"${escapeDoubleQuoted(text)}\"")
        if (result.isSuccess) return true
        // Fallback: write to clipboard then paste
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("auto", text))
            exec("input keyevent 279").isSuccess
        } catch (e: Exception) {
            Logger.w(TAG, "inputInject fallback failed: ${e.message}")
            false
        }
    }

    // ── v2.x Automatización Fase I:constructor de comandos(para ejecución en capas Shizuku→Root Reutilizar;null = Parámetro ilegal) ──

    /** `settings get <ns> <name>`;name apoyo "namespace:name",Seguro predeterminado. */
    internal fun buildSettingsGet(name: String): String? {
        val idx = name.indexOf(':')
        val nsPart = if (idx >= 0) name.substring(0, idx) else "secure"
        val keyPart = if (idx >= 0) name.substring(idx + 1) else name
        val safeNs = validateSettingsNamespace(nsPart) ?: return null
        val safeName = validateSettingsName(keyPart) ?: return null
        return "settings get $safeNs$safeName"
    }

    /** `settings put <ns> <name> "<value>"`(Los valores permanecen imprimibles solo en ASCII y con formato de escape). */
    internal fun buildSettingsPut(namespace: String, name: String, value: String): String? {
        val safeNs = validateSettingsNamespace(namespace) ?: return null
        val safeName = validateSettingsName(name) ?: return null
        val safeValue = value.filter { it.code in 32..126 }
        return "settings put $safeNs$safeName \"${escapeDoubleQuoted(safeValue)}\""
    }

    /** `svc <wifi|data> enable|disable`;Los servicios no compatibles devuelven nulo. */
    internal fun buildSvcToggle(service: String, enabled: Boolean): String? {
        val svc = service.trim().lowercase()
        if (svc != "wifi" && svc != "data") return null
        return "svc $svc ${if (enabled) "enable" else "disable"}"
    }

    /** `am start -n pkg/cls [--es k v ...]`;Los parámetros ilegales devuelven nulo. */
    internal fun buildAmStart(packageName: String, className: String? = null, extras: String? = null): String? {
        if (!validatePackageName(packageName)) return null
        val safeClass = className?.trim()?.takeIf { it.isNotBlank() }?.let { cls ->
            val normalized = if (cls.startsWith(".")) "$packageName$cls" else cls
            if (!COMPONENT_NAME_REGEX.matches(normalized)) return null
            normalized
        }
        val safeExtras = extras?.takeIf { it.isNotBlank() }?.let { e ->
            e.split("|").mapNotNull { pair ->
                val eq = pair.indexOf('=')
                if (eq <= 0) return@mapNotNull null
                val key = pair.substring(0, eq).trim()
                val rawValue = pair.substring(eq + 1)
                if (key.isBlank() || rawValue.isBlank() || !EXTRA_KEY_REGEX.matches(key)) return@mapNotNull null
                "--es $key \"${escapeDoubleQuoted(rawValue)}\""
            }.joinToString(" ").takeIf { it.isNotEmpty() }
        }
        return buildString {
            append("am start -n $packageName")
            if (safeClass != null) append("/$safeClass")
            if (safeExtras != null) append(" $safeExtras")
        }
    }

    /** `pm list packages [filter]`;El filtro ilegal devuelve nulo. */
    internal fun buildListPackages(filter: String? = null): String? {
        val safeFilter = filter?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (!PACKAGE_FILTER_REGEX.matches(it)) return null
            it
        }
        return "pm list packages" + (safeFilter?.let { " $it" } ?: "")
    }

    /** `logcat -d -t <lines>`. */
    internal fun buildLogcatTail(lines: Int): String = "logcat -d -t $lines"

    /** `input text "<escaped>"`;El texto vacío devuelve nulo. */
    internal fun buildInputInject(text: String): String? {
        if (text.isBlank()) return null
        return "input text \"${escapeDoubleQuoted(text)}\""
    }

    /** H-SEC: escape a value embedded inside a double-quoted shell string. */
    private fun escapeDoubleQuoted(value: String): String = buildString(value.length + 8) {
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '$' -> append('\\').append('$')
                '`' -> append('\\').append('`')
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(ch)
            }
        }
    }

    /** H-SEC: Only allow alphanumeric + underscore for settings keys. */
    private fun validateSettingsName(name: String): String? = name.takeIf { it.matches(Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")) }

    /** H-SEC: Only allow global/secure/system namespaces. */
    private fun validateSettingsNamespace(ns: String): String? = when (ns.lowercase()) {
        "global" -> "global "
        "system" -> "system "
        "secure", "" -> "secure "
        else -> null
    }

    companion object {
        private const val TAG = "RootExec"

        /** Root Límite de espera de solicitud de autorización:Necesita cubrir el tiempo en que el usuario hace clic manualmente en la ventana emergente del administrador raíz,Ríndete después del tiempo de espera. */
        private const val ROOT_REQUEST_TIMEOUT_MS = 30_000L

        /** H-SEC: Lista blanca de nombres de componentes(permitir $ para clases internas). */
        private val COMPONENT_NAME_REGEX = Regex("^[A-Za-z0-9_.$]+$")

        /** H-SEC: am start extras lista blanca de claves. */
        private val EXTRA_KEY_REGEX = Regex("^[A-Za-z0-9_.-]{1,64}$")

        /** H-SEC: pm list packages Filtrar la lista blanca de palabras. */
        private val PACKAGE_FILTER_REGEX = Regex("^[A-Za-z0-9_.-]{1,64}$")
    }
}
