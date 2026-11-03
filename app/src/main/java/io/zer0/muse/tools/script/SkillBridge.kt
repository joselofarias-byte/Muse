package io.zer0.muse.tools.script

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.tools.TOOL_OUTPUTS_DIR
import io.zer0.muse.tools.ToolFailureText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * F-17 + v2.2.1 gran caja de arena:JS Actuador de puente (__bridge__).
 *
 * JS Las funciones pueden obtener capacidades controladas (red/archivo de zona de pruebas/portapapeles/Notificación/Información del dispositivo):
 * ```js
 * return { __bridge__: true, action: "http_get", params: { url: "..." } };
 * ```
 * Sólo se intercepta una solicitud de puente a la vez,Kotlin Consígalo del resultado de la ejecución del script y ejecute el correspondiente
 * Implementarlo de forma segura y luego devolver el resultado como valor de retorno de la habilidad (request-response modo).
 *
 * Preste atención a los límites: el puente es el canal que "permite que el script obtenga capacidades controladas" (archivo sandbox/portapapeles/notificaciones, etc.),
 * No es un proxy de red——La recuperación directa en el entorno sandbox no va aquí, por lo que no está protegida por el criterio SSRF de este archivo.
 *
 * Lista de acciones y umbral (v2.x Una vez que se lanza la zona de pruebas, ya no se requieren declaraciones detalladas):
 *  - `echo`: Eco tal como está (ruta de verificación)
 *  - `http_get` / `http_post`:SSRF Proteger HTTP (denegar intranet/dirección local)
 *  - `fs_list` / `fs_read` / `fs_write` / `fs_delete`: Operaciones de archivos en el directorio sandbox
 *    (Solo anfitrión [Host.fsRoot], ruta de verificación canónica para evitar el escape)
 *  - `clipboard_read` / `clipboard_write`
 *  - `notify`: Indicaciones en la aplicación
 *  - `device_info`:Información básica del dispositivo
 *
 * La persona que llama pasa por defecto [ALL_ACTIONS] Lanzamiento completo; la declaración de capacidad es sólo para demostración y ya no sirve como un umbral estricto.
 * persona que llama (skill Departamento de procesamiento de resultados de ejecución) se llama primero [tryHandle]: Si el valor de retorno no contiene `__bridge__`
 * se devuelve el campo [HandleResult.NotBridge](procesado como resultados normales); de lo contrario volver al producto de ejecución/Error.
 */
object SkillBridge {

    private const val TAG = "SkillBridge"

    /** v2.2.1: http_post Solicitar límite de cuerpo(256KB). */
    private const val MAX_REQUEST_BYTES = 256 * 1024

    /** B-2: Número máximo de saltos para seguir una redirección manualmente,Evite que la cadena de redireccionamiento se extienda indefinidamente. */
    private const val MAX_REDIRECTS = 5

    /** Conjunto mínimo de acciones permitidas de forma predeterminada (compatibilidad con versiones anteriores: solo echo + http_get). */
    val DEFAULT_ALLOWED_ACTIONS: Set<String> = setOf("echo", "http_get")

    /**
     * v2.x(Sandbox Unleashed): Todas las acciones del puente. La persona que llama lo pasa de forma predeterminada y ya no lo envía para su aprobación elemento por elemento de acuerdo con la declaración del manifiesto.
     *
     * El resultado final de la reserva no está ahí.“¿Está permitida la acción?”, y dentro de cada implementación de acción:
     *  - `fs_*` encerrado en el anfitrión [Host.fsRoot](Verificación canónica del camino para evitar fugas);
     *  - `http_*` Utilice protección SSRF (rechace la red interna)/dirección local).
     */
    val ALL_ACTIONS: Set<String> = setOf(
        "echo",
        "http_get",
        "http_post",
        "fs_list",
        "fs_read",
        "fs_write",
        "fs_delete",
        "clipboard_read",
        "clipboard_write",
        "notify",
        "device_info",
    )

    /**
     * v2.2.1: anfitrión del puente —— Proporcione el contexto del sistema y la raíz del espacio aislado necesarios para la acción.
     *
     * @param context portapapeles/Se requieren indicaciones y otras capacidades del sistema; escenas distintas a tales acciones pueden ser null
     * @param fsRoot `fs_*` La raíz de la zona de pruebas de la acción (p. ej. `workspace/plugins/<pluginId>`); La acción fs rechaza cuando está vacía.
     */
    class Host(val context: Context?, val fsRoot: File?)

    /**
     * Puente de salida HTTP privada.DNS El análisis, la verificación de la dirección de conexión y la redirección salto a salto se completan en esta salida.
     * No reutilice otras salidas de red y evite generalizar las políticas de DNS al tráfico de chat, complementos o WebView.
     */
    private val bridgeHttpClient: SkillBridgeHttpClient by lazy { SkillBridgeHttpClient() }

    /**
     * Clase de sellado del resultado del procesamiento del puente.
     *
     *  - [NotBridge]:El valor de retorno no contiene `__bridge__`, la persona que llama continúa procesando de acuerdo con los resultados normales del script.
     *  - [Output]: La solicitud de puente se ha ejecutado y se genera la cadena JSON resultante.
     *  - [Failure]: Falló el análisis o la ejecución de la solicitud de puente (incluido"action No compatible")
     */
    sealed class HandleResult {
        object NotBridge : HandleResult()
        data class Output(val json: String) : HandleResult()
        data class Failure(val message: String) : HandleResult()
    }

    /**
     * Intente manejar el valor de retorno de ejecución de un script de habilidad.
     *
     * @param valueJson Cadena JSON del valor de retorno del script (SkillEngineResult.Success.valueJson)
     * @param allowedActions Conjunto de acciones de liberación (v2.x Transmisión predeterminada [ALL_ACTIONS] Lanzamiento completo)
     * @param host anfitrión del puente (fs/Requerido para acciones como portapapeles)
     * @return ver [HandleResult]
     */
    suspend fun tryHandle(valueJson: String, allowedActions: Set<String> = DEFAULT_ALLOWED_ACTIONS, host: Host? = null): HandleResult {
        val obj = runCatching {
            AppJson.parseToJsonElement(valueJson) as? JsonObject
        }.getOrNull() ?: return HandleResult.NotBridge
        val isBridge = (obj["__bridge__"] as? JsonPrimitive)?.booleanOrNull == true
        if (!isBridge) return HandleResult.NotBridge

        val action = (obj["action"] as? JsonPrimitive)?.contentOrNull
            ?: return HandleResult.Failure("__bridge__ falta el campo de acción")
        if (action !in allowedActions) {
            return HandleResult.Failure("__bridge__ action '$action' No en el conjunto de acciones de lanzamiento")
        }
        val params = obj["params"] as? JsonObject ?: buildJsonObject { }
        return when (action) {
            "echo" -> HandleResult.Output(params.toString())
            "http_get" -> withContext(Dispatchers.IO) { execHttpGet(params, host) }
            "http_post" -> withContext(Dispatchers.IO) { execHttpPost(params, host) }
            "fs_list" -> withContext(Dispatchers.IO) { execFsList(host, params) }
            "fs_read" -> withContext(Dispatchers.IO) { execFsRead(host, params) }
            "fs_write" -> withContext(Dispatchers.IO) { execFsWrite(host, params) }
            "fs_delete" -> withContext(Dispatchers.IO) { execFsDelete(host, params) }
            "clipboard_read" -> execClipboardRead(host)
            "clipboard_write" -> execClipboardWrite(host, params)
            "notify" -> execNotify(host, params)
            "device_info" -> HandleResult.Output(deviceInfoJson())
            else -> HandleResult.Failure("__bridge__ action '$action' No compatible")
        }
    }

    // ── HTTP ─────────────────────────────────────────────────────────────

    /** Ejecute http_get:SSRF protección(Contiene verificación de redirección salto a salto) + Salida completa al disco. */
    private fun execHttpGet(params: JsonObject, host: Host?): HandleResult {
        val url = (params["url"] as? JsonPrimitive)?.contentOrNull
            ?: return HandleResult.Failure("http_get Parámetro de URL faltante")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return HandleResult.Failure("http_get url Solo admite protocolo http/https")
        }
        val outputDirectory = host?.context?.let { File(it.filesDir, TOOL_OUTPUTS_DIR) }
            ?: return HandleResult.Failure("http_get Falta el contexto de almacenamiento de respuesta completo")
        return execHttpGetWithRedirects(url, outputDirectory)
    }

    /** v2.2.1: Ejecute http_post (sin seguir redirecciones,fail-closed). */
    private fun execHttpPost(params: JsonObject, host: Host?): HandleResult {
        val url = (params["url"] as? JsonPrimitive)?.contentOrNull
            ?: return HandleResult.Failure("http_post Parámetro de URL faltante")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return HandleResult.Failure("http_post url Solo admite protocolo http/https")
        }
        val outputDirectory = host?.context?.let { File(it.filesDir, TOOL_OUTPUTS_DIR) }
            ?: return HandleResult.Failure("http_post Falta el contexto de almacenamiento de respuesta completo")
        val body = (params["body"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (body.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) {
            return HandleResult.Failure("http_post El cuerpo de la solicitud supera el límite superior.(${MAX_REQUEST_BYTES / 1024}KB)")
        }
        val contentType = (params["content_type"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: "text/plain; charset=utf-8"
        return try {
            val result = bridgeHttpClient.postComplete(url, body, contentType, outputDirectory)
            HandleResult.Output(
                buildJsonObject {
                    put("status", JsonPrimitive(result.status))
                    put("body", JsonPrimitive(result.body))
                }.toString(),
            )
        } catch (e: Exception) {
            Logger.w(TAG, "http_post Puente falló: ${e.message}")
            val message = if (e is SkillBridgeHttpClient.PinnedAddressException) {
                "http_post Denegar el acceso a la intranet/Dirección de red no pública: ${e.url}"
            } else {
                // v2.2.1: Con dirección y etapa de falla.(tiempo de espera/analizar/TLS)
                ToolFailureText.httpFailure("http_post", url, e)
            }
            HandleResult.Failure(message)
        }
    }

    /**
     * Siga manualmente la ejecución de http_get redirigido.
     *
     * DNS El análisis y la verificación del nivel de conexión se realizan mediante [SkillBridgeHttpClient] Completo; corrige la misma copia verificada para cada salto
     * listas de direcciones para evitar“Primero verifique getAllByName y luego el sistema lo vuelva a analizar.”TOCTOU. Los redireccionamientos todavía son manejados por el cliente.
     * Deshabilite el seguimiento automático y la verificación salto a salto. Si algún salto falla, se cierra por fallas.
     */
    @Suppress("TooGenericExceptionCaught") // Las anomalías subyacentes de la red se convierten uniformemente en mensajes de error visibles para el usuario.
    private fun execHttpGetWithRedirects(startUrl: String, outputDirectory: File): HandleResult {
        return try {
            val result = bridgeHttpClient.getComplete(startUrl, outputDirectory, MAX_REDIRECTS)
            HandleResult.Output(
                buildJsonObject {
                    put("status", JsonPrimitive(result.status))
                    put("body", JsonPrimitive(result.body))
                }.toString(),
            )
        } catch (e: Exception) {
            Logger.w(TAG, "http_get Puente falló: ${e.message}")
            val message = if (e is SkillBridgeHttpClient.PinnedAddressException) {
                "http_get Denegar el acceso a la intranet/Dirección de red no pública: ${e.url}"
            } else {
                // v2.2.1: Con dirección y etapa de falla.(tiempo de espera/analizar/TLS)
                ToolFailureText.httpFailure("http_get", startUrl, e)
            }
            HandleResult.Failure(message)
        }
    }

    // ── archivo de zona de pruebas ──────────────────────────────────────────────────────────

    private fun fsRootOf(host: Host?): File = host?.fsRoot ?: throw SkillBridgeFs.BridgeFsException("La acción actual no proporciona un directorio de zona de pruebas")

    private fun execFsList(host: Host?, params: JsonObject): HandleResult {
        return try {
            val root = fsRootOf(host)
            val path = paramString(params, "path")
            val entries = SkillBridgeFs.list(root, path)
            HandleResult.Output(
                buildJsonObject {
                    put("path", JsonPrimitive(path.ifEmpty { "." }))
                    put(
                        "entries",
                        buildJsonArray {
                            entries.forEach { entry ->
                                add(
                                    buildJsonObject {
                                        put("name", JsonPrimitive(entry.name))
                                        put("dir", JsonPrimitive(entry.dir))
                                        put("size", JsonPrimitive(entry.size))
                                    },
                                )
                            }
                        },
                    )
                }.toString(),
            )
        } catch (e: Exception) {
            fsFailure("fs_list", e)
        }
    }

    private fun execFsRead(host: Host?, params: JsonObject): HandleResult {
        return try {
            val root = fsRootOf(host)
            val path = paramString(params, "path")
            if (path.isEmpty()) return HandleResult.Failure("fs_read parámetro de ruta faltante")
            val content = SkillBridgeFs.read(
                root = root,
                path = path,
                offsetChars = paramString(params, "offset_chars").toIntOrNull(),
                lengthChars = paramString(params, "length_chars").toIntOrNull(),
            )
            HandleResult.Output(
                buildJsonObject {
                    put("path", JsonPrimitive(path))
                    put("content", JsonPrimitive(content))
                }.toString(),
            )
        } catch (e: Exception) {
            fsFailure("fs_read", e)
        }
    }

    private fun execFsWrite(host: Host?, params: JsonObject): HandleResult {
        return try {
            val root = fsRootOf(host)
            val path = paramString(params, "path")
            if (path.isEmpty()) return HandleResult.Failure("fs_write parámetro de ruta faltante")
            val content = paramString(params, "content")
            val written = SkillBridgeFs.write(root, path, content)
            HandleResult.Output(
                buildJsonObject {
                    put("path", JsonPrimitive(path))
                    put("written_bytes", JsonPrimitive(written))
                }.toString(),
            )
        } catch (e: Exception) {
            fsFailure("fs_write", e)
        }
    }

    private fun execFsDelete(host: Host?, params: JsonObject): HandleResult {
        return try {
            val root = fsRootOf(host)
            val path = paramString(params, "path")
            if (path.isEmpty()) return HandleResult.Failure("fs_delete parámetro de ruta faltante")
            SkillBridgeFs.delete(root, path)
            HandleResult.Output(
                buildJsonObject {
                    put("path", JsonPrimitive(path))
                    put("deleted", JsonPrimitive(true))
                }.toString(),
            )
        } catch (e: Exception) {
            fsFailure("fs_delete", e)
        }
    }

    private fun fsFailure(action: String, e: Exception): HandleResult = HandleResult.Failure(
        if (e is SkillBridgeFs.BridgeFsException) {
            "$action rechazado: ${e.message}"
        } else {
            "$action falló: ${e.message ?: "error desconocido"}"
        },
    )

    // ── Capacidades del sistema ──────────────────────────────────────────────────────────

    private suspend fun execClipboardRead(host: Host?): HandleResult {
        val context = host?.context ?: return HandleResult.Failure("Falta el host del puente Context,No se puede leer el portapapeles")
        return withContext(Dispatchers.Main) {
            try {
                val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return@withContext HandleResult.Failure("El servicio de portapapeles no está disponible")
                val text = manager.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                HandleResult.Output(buildJsonObject { put("text", JsonPrimitive(text)) }.toString())
            } catch (e: Exception) {
                HandleResult.Failure("clipboard_read falló: ${e.message ?: "error desconocido"}")
            }
        }
    }

    private suspend fun execClipboardWrite(host: Host?, params: JsonObject): HandleResult {
        val context = host?.context ?: return HandleResult.Failure("Falta el host del puente Context,No se puede escribir en el portapapeles")
        val text = paramString(params, "text")
        return withContext(Dispatchers.Main) {
            try {
                val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return@withContext HandleResult.Failure("El servicio de portapapeles no está disponible")
                manager.setPrimaryClip(ClipData.newPlainText("muse-skill", text))
                HandleResult.Output("""{"ok":true}""")
            } catch (e: Exception) {
                HandleResult.Failure("clipboard_write falló: ${e.message ?: "error desconocido"}")
            }
        }
    }

    private suspend fun execNotify(host: Host?, params: JsonObject): HandleResult {
        val context = host?.context ?: return HandleResult.Failure("Falta el host del puente Context,No se puede enviar el recordatorio")
        val title = paramString(params, "title")
        val body = paramString(params, "body").ifBlank { title }
        if (body.isBlank()) return HandleResult.Failure("notify desaparecido title/body")
        return withContext(Dispatchers.Main) {
            try {
                android.widget.Toast.makeText(context, body, android.widget.Toast.LENGTH_SHORT).show()
                HandleResult.Output("""{"ok":true}""")
            } catch (e: Exception) {
                HandleResult.Failure("notify falló: ${e.message ?: "error desconocido"}")
            }
        }
    }

    private fun deviceInfoJson(): String = buildJsonObject {
        put("brand", JsonPrimitive(Build.BRAND))
        put("model", JsonPrimitive(Build.MODEL))
        put("android", JsonPrimitive(Build.VERSION.RELEASE))
        put("sdk", JsonPrimitive(Build.VERSION.SDK_INT))
    }.toString()

    private fun paramString(params: JsonObject, key: String): String = (params[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
}
