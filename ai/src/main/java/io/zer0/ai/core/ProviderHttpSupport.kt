package io.zer0.ai.core

import io.zer0.ai.util.KeyRoulette
import io.zer0.common.AppJson
import io.zer0.common.resultOf
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * v1.80 (M3+M5): AI Provider HTTP Apoyo público.
 *
 * Centrarse en tres empresas Provider(OpenAI / Gemini / Anthropic)Lógica HTTP compartida para:
 *  - OkHttpClient construir(Configuración de tiempo de espera unificada,Eliminar tres códigos duros)
 *  - Lectura segura del cuerpo del error(eliminar `runCatching { resp.body.string() }.getOrDefault("")` Repetir)
 *  - HTTP Clasificación del código de estado(Admite reintento diferenciado/Consejos)
 *
 * v1.73 grabado M3(Se pueden extraer tres isomorfismos de manejo de excepciones del proveedor ProviderHttpSupport)y
 * M5(Ejecución del cuerpo del errorCapturando duplicados),Aquí viene el aterrizaje unificado.
 *
 * v1.0.1: Nuevo [keyRoulette] Singleton compartido + [effectiveApiKey] Métodos auxiliares,
 *  Admite rotación de múltiples teclas y degradación de limitación de corriente. Cada proveedor llama antes de construir la solicitud. [effectiveApiKey]
 *  Obtenga el actual que debe usarse key,429 Momento equivocado [keyRoulette].pickNext Cambie de clave e inténtelo de nuevo.
 */
abstract class ProviderHttpSupport(
    protected val config: ProviderConfig,
) : Provider {

    /**
     * Compartir OkHttpClient — Configuración de tiempo de espera unificada para todos los Proveedores.
     *
     * v1.80 (M-HTTP1): En su lugar, haga referencia al singleton global en el objeto complementario. [sharedHttpClient],
     * Evite crear uno para cada subclase ProviderHttpSupport al crear una instancia. OkHttpClient(Desperdicio de recursos del grupo de conexiones).
     *
     * Si se requiere personalización adicional para las subclases(Tiempos de espera cortos como Gemini VertexAi client),
     * Disponible `httpClient.newBuilder().xxx().build()` derivado,Grupo de conexiones compartido.
     */
    protected val httpClient: OkHttpClient get() = sharedHttpClient

    /**
     * v1.0.1: La clave API utilizada por la instancia del proveedor actual.
     *
     * Pase [keyRoulette] de [ProviderConfig.apiKey](Puede contener varias comas separadas key)en
     * Seleccionar por política LRU. Llamado antes de cada solicitud.,Asegúrese de compartir la carga cuando utilice varias claves.
     *
     * En un escenario clave único, regrese directamente al original key,Sin gastos generales de rendimiento.
     *
     * 429 Cuando la corriente es limitada,Provider debe ser ajustado [switchToNextKey] Cambie a la siguiente clave e inténtelo de nuevo,
     * En lugar de volver a intentarlo con la misma clave.
     */
    // B-27: @Volatile — currentApiKey En el hilo de devolución de llamada cuando se gira con keyRoulette/Solicitar hilo para escribir,inicializado en la construcción,
    //     La lectura y escritura de subprocesos múltiples requieren volátil para garantizar la visibilidad,Evite leer claves semiinicializadas no publicadas.
    @Volatile
    protected var currentApiKey: String = config.apiKey
        private set

    init {
        // T0.2-G1: Verifique si baseUrl apunta a la intranet durante la inicialización(Exención de LLM local,Sólo alarma)
        warnIfBaseUrlLooksInternal(config.resolvedBaseUrl())
    }

    /**
     * v1.0.1: Obtenga la clave API actual que se debe utilizar.
     *
     * Escenario clave única(config.apiKey sin coma/nueva línea)Devuelve directamente el valor original después de recortar key,Omita la lógica LRU.
     *
     * T1.2 Reparación:Los escenarios de múltiples claves se vuelven a ejecutar cada vez que se llaman. LRU pick,No más golpes"caché"La sucursal regresa directamente
     * [currentApiKey] —— De lo contrario quedará bloqueado [switchToNextKey] / [markKeyFailed] rotado solo
     * key en,Se pierde el significado de rotación de LRU y omisión de listas negras.Provider Construyendo una solicitud header / query param
     * Este método se llama cuando
     */
    protected fun effectiveApiKey(): String {
        if (!hasMultipleKeys()) {
            val single = config.apiKey.trim()
            requireHeaderSafeApiKey(single)
            return single
        }
        currentApiKey = keyRoulette.pick(config.id, config.apiKey)
        requireHeaderSafeApiKey(currentApiKey)
        return currentApiKey
    }

    /**
     * v2.6.1: Verifique el juego de caracteres de la clave API antes de escribir el encabezado de la solicitud.
     *
     * HTTP header Sólo se acepta ASCII. Los caracteres chinos y otros caracteres no ASCII se mezclan en la clave.(común en“Conecte el título desde la interfaz china
     * Copiar y pegar juntos”,Si el primer carácter es“cien”)tiempo,OkHttp Se lanzará antes de enviar la solicitud.
     * `Unexpected char 0x767e at 7 in Authorization value`,Dificultad en el posicionamiento.
     * Deténgase aquí con anticipación e informe un motivo legible.,Solicita al usuario que verifique el principio y el final de la clave y el contenido pegado.
     */
    private fun requireHeaderSafeApiKey(key: String) {
        if (key.isEmpty()) return
        val offending = key.firstOrNull { it.code !in 0x21..0x7E }
        if (offending != null) {
            val position = key.indexOf(offending) + 1
            val hex = offending.code.toString(16).uppercase().padStart(4, '0')
            throw ProviderException(
                ProviderError.InvalidRequest(
                    displayMessage = "API Key Contiene caracteres ilegales(No. $position poco:'$offending',U+$hex)." +
                        "La clave sólo puede ser caracteres visibles ASCII.,Comprueba si se incluyen caracteres chinos o espacios.(Se usa comúnmente cuando se pega junto con el título.).",
                ),
            )
        }
    }

    /**
     * v1.0.18: Obtenga el actual que debe usarse API key,Con funda de respaldo de modelo gratuita.
     *
     * en [effectiveApiKey] Añade una capa a lo básico. fallback:
     *  - El usuario ha completado apiKey(soltero/mucho key)→ usuario de retorno key,fallback No efectivo
     *  - El usuario no completó apiKey + baseUrl y accedió a Siliconflow + modelId en la lista blanca
     *    → Regresar [FreeModelConfig.FALLBACK_API_KEY](CI/CD inyectar)
     *  - escenas restantes → Devolver cadena vacía(Haga que la persona que llama presione [ProviderConfig.allowMissingApiKey] Proceso)
     *
     * @param modelId El modelo solicitado actualmente. id(Utilizado para la verificación de la lista blanca,Cadena vacía significa desconocido)
     */
    protected fun resolveEffectiveApiKey(modelId: String = ""): String {
        val userKey = effectiveApiKey()
        if (userKey.isNotBlank()) return userKey
        // v1.0.18: modelo libre fallback(host + modelId Doble verificación de lista blanca)
        return FreeModelConfig.resolveApiKey(
            providerId = config.id,
            baseUrl = config.resolvedBaseUrl(),
            modelId = modelId,
            userApiKey = userKey,
            hiddenFromSettings = config.hiddenFromSettings,
        ) ?: ""
    }

    /**
     * v1.0.1: 429 Cambie a la siguiente tecla cuando la corriente sea limitada.
     *
     * Agregue la clave actual a la lista negra suave(60s),Luego seleccione la siguiente clave.
     * si solo hay uno key,Volver al original key(El proveedor utiliza un retroceso exponencial para volver a intentarlo.).
     *
     * @return true Indica un cambio exitoso al nuevo key(Provider Debería intentarlo de nuevo inmediatamente.);
     *         false Indica que solo hay una clave o que sigue siendo la misma después del cambio. key(Provider Debería tomar un retroceso exponencial)
     */
    protected fun switchToNextKey(): Boolean {
        if (!hasMultipleKeys()) {
            return false
        }
        val previous = currentApiKey
        currentApiKey = keyRoulette.pickNext(config.id, config.apiKey, previous)
        return currentApiKey != previous
    }

    /**
     * v1.0.1: Marcar la clave actual como fallida(Como la autenticación 401 falló).
     *
     * con [switchToNextKey] la diferencia:
     *  - [markKeyFailed] por errores irrecuperables(401/403/key Inválido),hardBlock=true,
     *    5 excluir completamente el key
     *  - [switchToNextKey] para estrangulamiento recuperable(429),lista negra suave 60s
     *
     * @return true Indica que hay otras claves disponibles.(Provider Deberías volver a intentarlo después de cambiar.);
     *         false Indica que solo una clave o todas las claves han fallado
     */
    protected fun markKeyFailed(hardBlock: Boolean = true): Boolean {
        if (!hasMultipleKeys()) {
            return false
        }
        val failedKey = currentApiKey
        keyRoulette.markFailed(config.id, failedKey, hardBlock = hardBlock)
        currentApiKey = "" // restablecer,EffectiveApiKey se volverá a seleccionar la próxima vez
        val newKey = effectiveApiKey()
        return newKey != failedKey
    }

    /**
     * T0.2-G1: Realice una verificación SSRF ligera en baseUrl antes de realizar una solicitud(Sin resolución DNS,Sólo coinciden los literales del host).
     *
     * golpear RFC1918 / 169.254 / 0.0.0.0/8 / ULA fc00::/7 Al esperar la dirección de intranet:
     *  - Ollama exención: host para 127.0.0.1 / localhost / ::1,o baseUrl contiene "ollama" o puerto 11434 → Sólo inicia sesión
     *  - el resto → Tenga en cuenta que Logger.w le pregunta al usuario que baseUrl puede apuntar a la intranet.(Uso normal del servicio LLM local,Sin bloqueo forzado)
     *
     * No bloquear llamadas: Provider baseUrl Completado manualmente por el usuario,Local Ollama/llama.cpp/LM Studio es un escenario de uso legal.
     * solo hazlo**Alerta visible temprana**,Verdadera superficie de ataque de la SSRF(Descarga de imágenes de referencia,MCP server, herramientas URL)han sido
     * SsrfGuard.isBlocked en el sitio de la llamada fuerza una denegación.
     */
    protected fun warnIfBaseUrlLooksInternal(baseUrl: String) {
        val host = try {
            java.net.URI(baseUrl).host
        } catch (_: Exception) {
            null
        }
        if (host == null) return

        val hostLower = host.lowercase()
        // 127.0.0.1 / localhost / ::1 — Bucle invertido puro
        val isLoopback = hostLower in setOf("127.0.0.1", "localhost", "::1")
        if (isLoopback) return

        // Ollama / Exención de LLM local: URL literal contiene "ollama" o puerto 11434
        val lower = baseUrl.lowercase()
        if (lower.contains("ollama") || ":11434" in lower) return

        // literal RFC1918 / link-local / 0.0.0.0/8 / ULA comprobar(Ninguno DNS)
        val isLiteralInternal = when {
            hostLower.startsWith("10.") || hostLower.startsWith("192.168.") -> true
            hostLower.startsWith("169.254.") -> true
            hostLower.startsWith("0.") -> true
            // 172.16.0.0/12 → 172.16.x ~ 172.31.x
            hostLower.startsWith("172.") &&
                hostLower.removePrefix("172.").split('.').firstOrNull()?.toIntOrNull() in 16..31 -> true
            // IPv6 ULA fc00::/7
            hostLower.startsWith("fc") || hostLower.startsWith("fd") -> true
            else -> false
        }
        if (isLiteralInternal) {
            io.zer0.common.Logger.w(
                "ProviderHttpSupport",
                "baseUrl Señale la dirección de intranet(host=$host),Si no es un LLM local, verifique la configuración.: $baseUrl",
            )
        }
    }

    private fun hasMultipleKeys(): Boolean = config.apiKey.contains(',') || config.apiKey.contains('\n')

    companion object {
        /** Longitud máxima de interceptación del cuerpo del error(Evite que las páginas de error HTML de gran tamaño abrumen el registro/UI). */
        const val ERROR_BODY_MAX_LEN = 500

        /**
         * v1.0.1: Singleton de KeyRoulette compartido globalmente.
         *
         * Todas las subclases de ProviderHttpSupport comparten la misma instancia,Asegúrese de que el estado de rotación de múltiples claves sea globalmente consistente.
         * Pase [keyRoulette] Acceso a la propiedad.
         */
        private val sharedKeyRoulette: KeyRoulette by lazy { KeyRoulette() }

        /** v1.0.1: Accesor KeyRoulette expuesto para uso de subclases. */
        protected val keyRoulette: KeyRoulette get() = sharedKeyRoulette

        /**
         * Singleton OkHttpClient compartido globalmente(M-HTTP1).
         * Todas las subclases de ProviderHttpSupport comparten la misma instancia,Grupo de conexiones compartidas y configuración de tiempo de espera.
         * Subclases disponibles `sharedHttpClient.newBuilder().xxx().build()` Derivar configuraciones diferenciadas.
         *
         * v1.135 Optimización del rendimiento:
         *  - Expanda el grupo de conexiones a 10 conexiones inactivas,keep-alive 5 minutos,Reducir el número de múltiples proveedores/solicitudes simultáneas
         *    Restablecimiento de la conexión y sobrecarga del protocolo de enlace TLS.
         *  - Habilitar explícitamente la negociación HTTP/2 + HTTP/1.1,Reduzca la latencia para puntos finales habilitados para HTTP/2.
         *  - habilitar gzip(OkHttp Habilitado por defecto),La intención se preserva aquí explícitamente.
         */
        private val sharedHttpClient: OkHttpClient by lazy {
            // v2.6.9(comentarios de los usuarios): Aplicar proxy global.
            // Este cliente es la entrada común para una gran cantidad de solicitudes de omisión, como llamadas visuales/incrustadas/de herramientas.
            // El proxy no se ha aplicado antes, lo que da como resultado que el usuario esté configurado con un proxy, pero estas solicitudes aún están conectadas directamente.
            io.zer0.ai.core.ProxyProvider.applyTo(OkHttpClient.Builder())
                .connectTimeout(ProviderHttpDefaults.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(ProviderHttpDefaults.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                .writeTimeout(ProviderHttpDefaults.WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
                .callTimeout(ProviderHttpDefaults.CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
                .connectionPool(
                    ConnectionPool(
                        maxIdleConnections = 10,
                        keepAliveDuration = 5,
                        timeUnit = TimeUnit.MINUTES,
                    ),
                )
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                // T0.2-G1: Deshabilite OkHttp para que no siga automáticamente redirecciones 3xx,Impedir la configuración del usuario provider baseUrl
                // Saltar a intranet vía 302/Dirección de metadatos de la nube(El objetivo de redireccionamiento no puede realizar la verificación SSRF en la capa de cliente compartido)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }

        /**
         * lectura segura Response body,Devuelve una cadena vacía en caso de error.
         * Reemplazar los duplicados de cada empresa. `runCatching { resp.body.string() }.getOrDefault("")`.
         * capturar solo IOException,No se trague CancellationException / OOM.
         * v1.97: Agregar captura IllegalStateException — EventSourceListener/WebSocketListener
         * El cuerpo de la respuesta en la devolución de llamada ha sido enmarcado. stripped,La lectura arroja IllegalStateException.
         */
        fun readBodySafely(response: Response): String = try {
            response.body?.string() ?: ""
        } catch (e: java.io.IOException) {
            ""
        } catch (e: IllegalStateException) {
            ""
        }

        /**
         * Lea con seguridad el cuerpo de la respuesta y trunquelo a [maxLen],Evite cuerpos de error de gran tamaño.
         */
        fun readBodyCapped(response: Response, maxLen: Int = ERROR_BODY_MAX_LEN): String {
            val body = readBodySafely(response)
            return if (body.length > maxLen) body.take(maxLen) else body
        }

        /**
         * v2.6.8: Describe la respuesta JSON.**forma estructural**, utilizado para el diagnóstico“200 pero el contenido está vacío”.
         *
         * Solo se generan el nombre y el tipo de clave, no se generan valores. —— La respuesta puede contener texto/clave/información personal del usuario,
         * No se puede escribir en registros o mensajes de error.
         *
         * Salida típica:`{choices:[0 items], model:string, usage:object}`
         * los usuarios ven `choices:[0 items]` Se puede juzgar que es el resultado vacío proporcionado por el flujo ascendente, en lugar de que el análisis de la aplicación esté roto.
         */
        fun describeJsonShape(raw: String, maxKeys: Int = 8): String = when {
            raw.isBlank() -> "<empty body>"
            else -> shapeOf(raw, maxKeys)
        }

        private fun shapeOf(raw: String, maxKeys: Int): String {
            val root = resultOf { AppJson.parseToJsonElement(raw) }.getOrNull()
            val obj = root as? kotlinx.serialization.json.JsonObject
            return when {
                // No JSON (común: el relé devuelve una página de error HTML)
                root == null -> {
                    val head = raw.trimStart().take(60).lowercase()
                    if (head.startsWith("<!doctype") || head.startsWith("<html")) {
                        "<html page, not json>"
                    } else {
                        "<non-json body, ${raw.length} chars>"
                    }
                }
                obj == null -> "<json ${root::class.simpleName}>"
                else -> obj.entries.take(maxKeys).joinToString(", ", prefix = "{", postfix = "}") { (key, value) ->
                    val desc = when (value) {
                        is kotlinx.serialization.json.JsonArray -> "[${value.size} items]"
                        is kotlinx.serialization.json.JsonObject -> "object"
                        is kotlinx.serialization.json.JsonPrimitive ->
                            if (value.isString) "string(${value.content.length})" else value.content.take(12)
                        else -> "?"
                    }
                    "$key:$desc"
                }
            }
        }

        /**
         * B-03: Lectura del cuerpo de respuesta de transmisión con límite de tamaño superior — contentLength desconocido(chunked)O será eficaz aunque no se declare.
         *
         * Bloque acumulado por bloque,exceder [maxBytes] es decir, interrumpir(Descartar el resto),Evite la omisión de respuestas fragmentadas
         * contentLength Compruebe que toda la respuesta grande se lea en la memoria.
         *
         * @return Pair<Leer texto(la mayoría maxBytes), Si excede el límite>
         */
        fun readBodyCappedStreaming(response: Response, maxBytes: Int): Pair<String, Boolean> {
            val body = response.body ?: return "" to false
            val sb = StringBuilder()
            var kept = 0
            var over = false
            try {
                body.byteStream().use { input ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (kept + n > maxBytes) {
                            val take = maxBytes - kept
                            if (take > 0) sb.append(String(buf, 0, take, Charsets.UTF_8))
                            over = true
                            break
                        }
                        sb.append(String(buf, 0, n, Charsets.UTF_8))
                        kept += n
                    }
                }
            } catch (_: java.io.IOException) {
                // La interrupción de lectura regresa según el contenido leído.,Sobrelímite presionado por la persona que llama/Procesamiento de respuestas cortas
            }
            return sb.toString() to over
        }

        /**
         * Devuelve una clasificación de error legible por humanos según el código de estado HTTP,Se utiliza para indicaciones diferenciadas./Vuelva a intentar la decisión.
         * Devuelve nulo para indicar que no se requiere una clasificación especial(Errores comerciales como 400/404).
         */
        fun classifyHttpCode(code: Int): String? = when (code) {
            401 -> "authentication failed"
            402 -> "insufficient quota"
            403 -> "permission denied"
            408 -> "request timeout"
            413 -> "request too large"
            429 -> "rate limited"
            in 500..599 -> "server error"
            else -> null
        }

        /**
         * Cree mensajes de error HTTP estandarizados(Resumen del cuerpo con clasificación de código de estado + truncado).
         * El parseErrorMessage de cada proveedor puede agregar su propia estructura de error analizando sobre esta base.
         */
        fun buildHttpErrorMessage(prefix: String, code: Int, body: String): String {
            val category = classifyHttpCode(code)
            return buildString {
                append(prefix).append("HTTP ").append(code)
                category?.let { append(" [").append(it).append("]") }
                ProviderError.safeErrorDetail(body)?.let { capped ->
                    append(": ").append(capped)
                }
            }
        }

        /**
         * v1.0.16: Limpie todas las conexiones inactivas en el grupo de conexiones compartidas.
         *
         * Se llama cuando la aplicación vuelve al primer plano desde el fondo.,Es posible que el sistema haya eliminado el descarte./El enrutador está apagado idle socket,
         * Evite que la siguiente solicitud reutilice la conexión caducada, lo que provocará que falle el primer HTTP.(El rendimiento es que después de cambiar al fondo, se informa un error.).
         * Las conexiones activas en uso no se ven afectadas.
         */
        fun evictIdleConnections() {
            runCatching {
                sharedHttpClient.connectionPool.evictAll()
            }.onFailure {
                io.zer0.common.Logger.w("ProviderHttpSupport", "evictIdleConnections falló", it)
            }
        }
    }
}
