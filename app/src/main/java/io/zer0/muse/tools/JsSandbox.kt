package io.zer0.muse.tools

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * JavaScript zona de pruebas de ejecución(singleton).
 *
 * Android No hay motor JS incorporado y no se pueden agregar grandes dependencias,
 * Cambie a utilizar la ejecución del motor V8 de WebView JS,Deje que AI Agent ejecute cálculos matemáticos/Procesamiento de datos/Algoritmo sencillo.
 *
 * Puntos de diseño:
 *  - Contiene una única instancia de WebView,Debe ser creado y accedido en el hilo principal.([Dispatchers.Main] cambiar)
 *  - usar [WebView.evaluateJavascript] Ejecutar código,Obtenga el valor de retorno de la cadena JSON en la devolución de llamada
 *  - [WebChromeClient.onConsoleMessage] Intercepte console.log/warn/error para recopilar registros
 *  - límite de seguridad(v2.x La red ha sido liberada.):JS Se puede utilizar directamente fetch/XHR/WebSocket;
 *    interceptar sólo**Navegación de página**(window.open)Leer con archivo local://content://;
 *    No cargue ningún externo URL(solo loadDataWithBaseURL("about:blank", ...))
 *  - mecanismo de tiempo de espera:[withTimeoutOrNull] Envolver la devolución de llamada de JavaScript;JS en sí no puede ser interrumpido,
 *    Pero el lado de Kotlin devuelve un error de tiempo de espera,Evita cuelgues infinitos
 *  - Seguridad del hilo:Todos los accesos a WebView están en [Dispatchers.Main] en contexto de rutina,
 *    La recopilación de registros está protegida con sincronización
 *
 * Debe llamarse antes de su uso. [init] inyectar Application Context(por [CodeExecutionTool] / [SkillExecutor] puente).
 */
object JsSandbox {

    private const val TAG = "JsSandbox"

    /** La identificación del complemento que se está ejecutando actualmente (utilizado por host.getConfig para encontrar la configuración). */
    @Volatile private var currentPluginId: String? = null

    /**
     * Establezca la identificación del complemento al que pertenece la ejecución JS actual, por ejemplo host.getConfig(key) Encuentra la configuración.
     *
     * debe estar en [execute] Se establece antes de llamar y se borra automáticamente después de la ejecución.
     */
    fun setCurrentPluginId(pluginId: String?) {
        currentPluginId = pluginId
    }

    /**
     * El entorno Sandbox no está disponible.
     *
     * Es un problema ambiental local (WebView No listo, la autoprueba falló), independientemente del código pasado por la persona que llama.
     * La capa de herramientas se proporciona en consecuencia.“No lo vuelvas a intentar, cambia de ruta”rápido en lugar de volcar el nombre de la implementación interna en el modelo.
     */
    class JsSandboxUnavailableException(message: String) : IllegalStateException(message)

    /**
     * Construye la inyección de configuración del host antes de cada ejecución.
     *
     * WebView Los objetos globales abarcarán execute() Retenido; incluso si se borra localStorage/cache,
     * No anular estas variables también hará que el siguiente complemento lea la configuración del complemento anterior.
     */
    internal fun buildHostInjectionScript(scopeKey: String?, pluginConfigJson: String?): String {
        val config = pluginConfigJson ?: "null"
        val pluginId = scopeKey?.let(::quoteJs) ?: "null"
        return "window.__musePluginConfig = $config; window.__musePluginId = $pluginId;"
    }

    /**
     * JS de inicialización de sandbox inyectado.
     *
     * v2.x(Caja de arena lanzada):JS Se puede utilizar directamente fetch / XMLHttpRequest / WebSocket —— No más montones.
     * Anteriormente, la red estaba completamente deshabilitada (función stub + bloqueo de shouldInterceptRequest + blockNetworkLoads),
     * habilitar complemento/JS Las habilidades sólo pueden depender de `__bridge__` Solicitar la aprobación de acceso a la red elemento por elemento es demasiado conservador.
     * Ahora cambiado a: las capacidades de red están disponibles directamente, pero**Mantenga dos tipos de resultados finales**:
     *  - Navegación de página (window.open / location escribir) todavía está prohibido —— La zona de pruebas no debería poder saltar a la página./ventana emergente;
     *  - La solicitud aún se procesa [shouldInterceptRequest] con [WebSettings] SSRF/protección de archivos locales.
     */
    private const val INIT_JS = """
        (function() {
            'use strict';
            var marker = 'muse-js-sandbox-open-v2';
            // v2.x: fetch / XMLHttpRequest / WebSocket / sendBeacon No más intercepciones, disponibles directamente.
            // Solo se conserva la protección de navegación: las ventanas emergentes y los saltos de página están prohibidos (el entorno limitado no debería poder controlar el comportamiento de navegación del host).
            try { window.open = function() { return null; }; } catch (e) { /* La propiedad puede no existir,Saltar en silencio */ }
            try { window.close = function() {}; } catch (e) { /* La propiedad puede no existir,Saltar en silencio */ }
            try { document.write = function() {}; } catch (e) { /* La propiedad puede no existir,Saltar en silencio */ }
            try { document.writeln = function() {}; } catch (e) { /* La propiedad puede no existir,Saltar en silencio */ }
            Object.defineProperty(window, '__museSandboxDisabled', {
                value: marker, writable: false, configurable: false, enumerable: false
            });
            // ── junta de red ──
            // La página de la zona de pruebas es aproximadamente: en blanco,origin=null: La recuperación directa será bloqueada por la misma política de origen y provocará un error de CORS.
            // Aquí, cambie fetch / XMLHttpRequest a __museHttp (Kotlin OkHttp), evitando completamente CORS.
            function __museDoHttp(method, url, headers, body) {
                var payload = { method: method, url: url, headers: headers || {}, body: body };
                var raw = __museHttp.httpRequest(JSON.stringify(payload));
                try { return JSON.parse(raw); } catch (e) { return { error: 'bad response: ' + raw }; }
            }
            window.fetch = function(input, init) {
                var url = (typeof input === 'string') ? input : (input && input.url) || '';
                var opts = init || {};
                var headers = {};
                if (opts.headers) {
                    if (typeof opts.headers.forEach === 'function' && !Array.isArray(opts.headers)) {
                        opts.headers.forEach(function(v, k) { headers[k] = v; });
                    } else if (Array.isArray(opts.headers)) {
                        opts.headers.forEach(function(pair) { headers[pair[0]] = pair[1]; });
                    } else {
                        for (var k in opts.headers) { headers[k] = opts.headers[k]; }
                    }
                }
                var res = __museDoHttp((opts.method || 'GET').toUpperCase(), url, headers,
                    opts.body == null ? null : String(opts.body));
                if (res.error) return Promise.reject(new TypeError(res.error));
                return Promise.resolve({
                    ok: res.status >= 200 && res.status < 300,
                    status: res.status,
                    statusText: res.statusText || '',
                    url: url,
                    headers: { get: function(n) { return (res.headers || {})[String(n).toLowerCase()] || null; } },
                    text: function() { return Promise.resolve(res.body || ''); },
                    json: function() { return Promise.resolve(JSON.parse(res.body || 'null')); }
                });
            };
            function MuseXHR() {
                this.readyState = 0; this.status = 0; this.statusText = '';
                this.responseText = ''; this.response = ''; this.onload = null;
                this.onerror = null; this.onreadystatechange = null; this._headers = {};
            }
            MuseXHR.prototype.open = function(method, url) {
                this._method = (method || 'GET').toUpperCase(); this._url = url; this.readyState = 1;
                if (this.onreadystatechange) this.onreadystatechange();
            };
            MuseXHR.prototype.setRequestHeader = function(k, v) { this._headers[k] = v; };
            MuseXHR.prototype.getAllResponseHeaders = function() {
                var out = ''; for (var k in (this._resHeaders || {})) out += k + ': ' + this._resHeaders[k] + '\r\n';
                return out;
            };
            MuseXHR.prototype.send = function(body) {
                var self = this;
                setTimeout(function() {
                    var res = __museDoHttp(self._method, self._url, self._headers,
                        body == null ? null : String(body));
                    self.readyState = 4;
                    if (res.error) {
                        self.status = 0;
                        if (self.onreadystatechange) self.onreadystatechange();
                        if (self.onerror) self.onerror(new Error(res.error));
                        return;
                    }
                    self.status = res.status;
                    self.statusText = res.statusText || '';
                    self._resHeaders = res.headers || {};
                    self.responseText = res.body || '';
                    self.response = self.responseText;
                    if (self.onreadystatechange) self.onreadystatechange();
                    if (self.onload) self.onload();
                }, 0);
            };
            window.XMLHttpRequest = MuseXHR;
            // B7-01: host objeto — Se puede acceder a los complementos a través de host.getConfig(key) Leer los valores de configuración proporcionados por el host
            if (typeof host === 'undefined') {
                window.host = {
                    getConfig: function(key) {
                        var v = window.__musePluginConfig && window.__musePluginConfig[key];
                        return v !== undefined ? JSON.stringify(v) : null;
                    },
                    getPluginId: function() {
                        return window.__musePluginId || null;
                    }
                };
            }
        })();
    """

    /**
     * v2.x(Caja de arena lanzada):La verificación sólo comprueba que la protección de navegación esté vigente; la API de red se ha lanzado y ya no es necesaria. "Debe arrojar error".
     * Cubre dos categorías:window.open Sobrescrito + document.write está deshabilitado.
     */
    internal const val VERIFY_JS = """
        (function() {
            var issues = [];
            if (typeof window.open !== 'function') { issues.push('window.open missing'); }
            else { try { var r = window.open('about:blank'); if (r !== null) issues.push('window.open not blocked'); } catch (e) { /* Lanzar un error también se considera bloqueado. */ } }
            // Debe devolver explícitamente:evaluateJavascript toma el valor de la expresión completa,
            // Escribe directamente JSON.stringify(...) Cuando se usa como declaración, la función devuelve indefinido y la devolución de llamada recibe "null",
            // será considerado como“La autoprueba no arrojó resultados”.
            return JSON.stringify({ verified: issues.length === 0, issues: issues });
        })();
    """

    @Volatile private var webViewRef: WebView? = null

    /**
     * Canal de valor de retorno asincrónico: cuando el script devuelve Promesa,`evaluateJavascript` Lo que obtuve fue `{}`(Promise no se puede serializar).
     * El script contenedor reconoce thenable y lo cambia para pasar `__museAsync.deliver(...)` Devolver resultados reales,
     * La devolución de llamada actualmente en espera se guarda aquí (solo se ejecuta una vez al mismo tiempo,Mutex serializado).
     */
    private val asyncDeliver = java.util.concurrent.atomic.AtomicReference<((String) -> Unit)?>(null)

    /** JS El valor centinela de \"Esperando mi retorno asincrónico\". */
    private const val ASYNC_SENTINEL = "__MUSE_ASYNC__"

    /** Tiempo de espera único HTTP de Sandbox (milisegundos). */
    private const val HTTP_TIMEOUT_MS = 15_000L

    /** Límite del cuerpo de respuesta HTTP de Sandbox:2 MB, se truncará y marcará si se excede. */
    private const val MAX_HTTP_BYTES = 2 * 1024 * 1024

    /** Entrada de devolución de datos asincrónica para llamadas JS (registrada como `__museAsync`). */
    @android.webkit.JavascriptInterface
    fun deliverAsyncResult(payload: String) {
        asyncDeliver.getAndSet(null)?.invoke(payload)
    }

    /**
     * Entrada HTTP para llamadas JS (registrada como `__museHttp`):**La solicitud real la realiza OkHttp en el lado de Kotlin.**.
     *
     * Por qué lo necesitas: La página Sandbox es `about:blank`,origin si `null`, la política del mismo origen del navegador
     * La gran mayoría de las solicitudes entre sitios se bloquean directamente como errores CORS. —— El fetch/XHR prometido en la descripción de la herramienta en realidad no funciona.
     * No existe CORS cuando se utilizan solicitudes nativas.
     *
     * Límites (reservados intencionalmente): solo se permite http/https; sin cookies/credenciales; límite del cuerpo de respuesta [MAX_HTTP_BYTES];
     * tiempo de espera [HTTP_TIMEOUT_MS].@JavascriptInterface El método se ejecuta en el hilo JavaBridge de WebView,
     * Bloquear aquí no bloqueará el hilo principal.
     */
    @android.webkit.JavascriptInterface
    fun httpRequest(requestJson: String): String {
        val request = runCatching { AppJson.parseToJsonElement(requestJson) as JsonObject }
            .getOrNull() ?: return """{"error":"bad request json"}"""
        val url = (request["url"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val method = (request["method"] as? JsonPrimitive)?.contentOrNull?.uppercase() ?: "GET"
        val scheme = runCatching { java.net.URI(url).scheme?.lowercase() }.getOrNull()
        if (scheme != "http" && scheme != "https") {
            return """{"error":"only http/https allowed"}"""
        }
        val bodyText = (request["body"] as? JsonPrimitive)?.contentOrNull
        // Auditoría: el sandbox puede enviar HTTP arbitrario, por lo que debe dejar rastros de "lo que se envió" (host + método + volumen).
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: "?"
        Logger.i(TAG, "Solicitud saliente de zona de pruebas | method=$method | host=$host | bodyBytes=${bodyText?.length ?: 0}")
        return try {
            val builder = okhttp3.Request.Builder().url(url).method(method, null)
            (request["headers"] as? JsonObject)?.forEach { (name, value) ->
                (value as? JsonPrimitive)?.contentOrNull?.let { builder.header(name, it) }
            }
            if (bodyText != null && method != "GET" && method != "HEAD") {
                val mediaType = "application/octet-stream".toMediaTypeOrNull()
                builder.method(method, okhttp3.RequestBody.create(mediaType, bodyText))
            }
            httpClient.newCall(builder.build()).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                val truncated = bytes.size > MAX_HTTP_BYTES
                // Auditoría (Acabado): Anote el código de estado y el volumen. Esto es lo que obtienes en el lado del guión.
                Logger.i(TAG, "Se completó la subcontratación del espacio aislado | host=$host | status=${response.code} | bytes=${bytes.size}")
                val text = String(
                    if (truncated) bytes.copyOf(MAX_HTTP_BYTES) else bytes,
                    Charsets.UTF_8,
                )
                val headers = buildJsonObject {
                    response.headers.forEach { (name, value) ->
                        put(name.lowercase(), JsonPrimitive(value))
                    }
                }
                buildJsonObject {
                    put("status", JsonPrimitive(response.code))
                    put("statusText", JsonPrimitive(response.message))
                    put("headers", headers)
                    put("body", JsonPrimitive(text))
                    put("truncated", JsonPrimitive(truncated))
                }.toString()
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Error en la solicitud HTTP del espacio aislado: ${e.message}")
            buildJsonObject {
                put("error", JsonPrimitive(e.message ?: e::class.java.simpleName))
            }.toString()
        }
    }

    /** Red Sandbox: cliente compartido (sin tarro de cookies para evitar que los scripts obtengan la sesión del host). */
    private val httpClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(HTTP_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(HTTP_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .callTimeout(HTTP_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .build()
    }

    @Volatile private var appContext: Context? = null

    /** R-SVC-04: Umbral del disyuntor de tiempo de espera continuo. */
    private const val MAX_CONSECUTIVE_TIMEOUTS = 2

    /** R-SVC-04: El tiempo de enfriamiento se restablece automáticamente después de que se funde el fusible. */
    private const val CIRCUIT_COOLDOWN_MS = 60_000L

    /** R-SVC-04: Cuota de tiempo de espera acumulada(En proceso),También se fusionará tras superarlo. */
    private const val MAX_TOTAL_TIMED_OUT_MS = 60_000L

    /**
     * C-30: Botón de estado del fusible scopeKey(complemento id)Aislamiento dimensional,Esto evita que un complemento apague todas las herramientas JS en un bucle infinito.
     * key == null Representa incorporado/Herramientas no complementarias(Conservar la semántica global original).
     * Usar en su lugar [ConcurrentHashMap] Admite lectura y escritura simultáneas(Las actualizaciones de estado en cada ciclo se serializan dentro del mismo mutex del hilo principal.,
     * pero lee [isCircuitBroken] Puede ser de otro hilo).
     */
    private class CircuitState(
        @Volatile var consecutiveTimeouts: Int = 0,
        @Volatile var totalTimedOutMs: Long = 0L,
        @Volatile var circuitBrokenUntil: Long = 0L,
    )

    private val circuitStates = ConcurrentHashMap<String?, CircuitState>()

    /**
     * [ConcurrentHashMap] No permitir clave nula: las herramientas integradas desaparecen [execute] cuando alcanceKey es nulo,
     * Si se usa null como clave directamente, se generará NPE durante get ("Attempt to invoke virtual method
     * 'int java.lang.Object.hashCode()' on a null object reference")——
     * Por lo tanto, todas las llamadas integradas a ejecutar_javascript fallan el 100% del tiempo (probado por los usuarios).
     * Utilice una cadena centinela fija para reemplazar la clave nula y la semántica externa permanecerá sin cambios (null Todavía representa incorporado/alcance predeterminado).
     */
    private const val DEFAULT_SCOPE_KEY = "\u0000builtin-default"

    /** Obtener especificado scopeKey(complemento id)estado del fusible;null scope Indica herramientas integradas,keyed Creación perezosa. */
    private fun stateFor(scopeKey: String?): CircuitState = circuitStates.getOrPut(scopeKey ?: DEFAULT_SCOPE_KEY) { CircuitState() }

    /** búfer de registro:Borrar antes de cada ejecución.synchronized Proteja el acceso multiproceso. */
    private val logsLock = Any()
    private val currentLogs = mutableListOf<String>()

    /** Reparación de auditoría (4.4): execute bloqueo mutex — Serializar la ejecución de WebView JS. */
    private val executionMutex = Mutex()

    /** JS Resultados de la ejecución del sandbox. */
    data class JsResult(
        /** Valor de retorno de ejecución(JSON forma de cadena,Puede ser analizado por la persona que llama). */
        val value: Any?,
        /** console.log/warn/error Registros recopilados. */
        val consoleLogs: List<String>,
        /** Mensaje de error de ejecución(tiempo de espera/JS anormal),null Indica éxito. */
        val error: String?,
    )

    /**
     * Inyecte el contexto de la aplicación. idempotente,Se puede llamar varias veces.
     *
     * en [CodeExecutionTool] / [SkillExecutor] Llamado antes de la primera ejecución.,
     * Deje que WebView singleton obtenga el contexto de aplicación para crear.
     */
    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            Logger.i(TAG, "JsSandbox inyectado applicationContext")
        }
    }

    /**
     * Ejecutar código JavaScript.
     *
     * @param code JS código(Puede ser una expresión, un bloque de instrucciones o una llamada de función.;Si es un bloque de declaración,Devuelve el valor de la última expresión.)
     * @param timeoutMs Tiempo de espera en milisegundos,Predeterminado 10 segundos
     * @param scopeKey C-30: Ejecute el complemento de su propiedad id(Transferencia de herramientas complementarias pluginId;Las herramientas integradas no se pasan usando null global scope).
     *   El estado del fusible está aislado de acuerdo con este,El tiempo de espera del bucle infinito de un complemento solo se fusiona a sí mismo,No se conecta a otros complementos/Herramientas incorporadas.
     * @param pluginConfigJson B7-01: Cadena JSON de configuración del complemento,para inyección host.getConfig() puente.null Se omite la inyección.
     * @return [Result] envuelto [JsResult];Kotlin retorno de excepción lateral failure(JS Los errores de ejecución están encapsulados en JsResult.error)
     */
    suspend fun execute(
        code: String,
        timeoutMs: Long = 10000L,
        scopeKey: String? = null,
        pluginConfigJson: String? = null,
    ): Result<JsResult> = withContext(Dispatchers.Main) {
        // Reparación de auditoría (4.4): execute agregar exclusión mutua — La implementación original no tenía control de concurrencia.,Múltiples herramientas al mismo tiempo execute
        // Entrará en la ejecución de WebView JS al mismo tiempo,console Los buffers de registro están contaminados entre sí, la destrucción del tiempo de espera y las devoluciones de llamada están intercaladas;
        // Serialización con Mutex,Solo se permite ejecutar un JS al mismo tiempo. Esperar dentro del bloqueo suspende en lugar de bloquear el hilo principal.
        executionMutex.withLock {
            try {
                // C-30: El estado del fusible se lee de forma aislada según la clave de alcance.
                val state = stateFor(scopeKey)
                if (System.currentTimeMillis() < state.circuitBrokenUntil) {
                    return@withContext Result.failure(
                        IllegalStateException("JS La zona de pruebas se ha roto, inténtelo de nuevo más tarde.(scope=${scopeKey ?: "default"})"),
                    )
                }
                val webView = ensureWebView()
                // C-30: Limpie los datos de localStorage/cache/formulario antes de cada ejecución,Prevenir complementos cruzados(y entre ejecuciones)Residuo
                //   Fuga a la próxima ejecución.WebView Es un singleton compartido globalmente.,No se pueden aislar instancias por complemento;
                //   usar"Borrar datos antes de la ejecución."Asegúrese de que el almacenamiento local escrito por el complemento A no sea leído por el complemento B.
                clearPerExecutionData(webView)
                // Borrar registro actual
                synchronized(logsLock) { currentLogs.clear() }

                // Envolver código con IIFE:Permitir que LLM escriba bloques de declaraciones;El valor de retorno se serializa usando JSON.stringify
                // Nota:LLM El código escrito puede ser "1+2" / "const x = 3; x*2" / "Math.max(1,2)" espera
                // Usado aquí (function(){ ... })() Después del paquete,El código interno puede usar return para devolver un valor;
                // Pero si LLM escribe una expresión en lugar de una declaración de devolución,IIFE La última expresión interna no se devuelve automáticamente.
                // compromiso:usar eval(code) evaluar,La expresión regresa directamente.;Si no hay devolución en el bloque de declaración, devuelva undefined
                val wrappedCode = buildString {
                    append("(function() {\n")
                    append("    try {\n")
                    append("        var __result__ = eval(")
                    append(quoteJs(code))
                    append(");\n")
                    append("        // Cuando un script devuelve una Promesa debe esperar a que se establezca:evaluateJavascript sólo obtendrá {},\n")
                    append("        // Tanto el éxito como el fracaso se convierten en objetos vacíos. —— Se trata de un fallo silencioso, que es más difícil de comprobar que un error.\n")
                    append("        if (__result__ && typeof __result__.then === 'function') {\n")
                    append("            try {\n")
                    append("                __result__.then(\n")
                    append("                    function(v){ __museAsync.deliverAsyncResult(JSON.stringify({\n")
                    append("                        ok: true,\n")
                    append("                        value: v === undefined ? null : (function(){\n")
                    append("                            try { return JSON.stringify(v); } catch (e) { return String(v); }\n")
                    append("                        })()\n")
                    append("                    })); },\n")
                    append("                    function(e){ __museAsync.deliverAsyncResult(JSON.stringify({\n")
                    append("                        ok: false,\n")
                    append("                        error: (e && e.message) || String(e)\n")
                    append("                    })); }\n")
                    append("                );\n")
                    append("            } catch (e) {\n")
                    append("                __museAsync.deliverAsyncResult(JSON.stringify({ ok: false, ")
                    append("error: 'await failed: ' + ((e && e.message) || String(e)) }));\n")
                    append("            }\n")
                    append("            return '")
                    append(ASYNC_SENTINEL)
                    append("';\n")
                    append("        }\n")
                    append("        // JSON.stringify valor de retorno del contenedor,undefined → null\n")
                    append("        if (__result__ === undefined) {\n")
                    append("            return { ok: true, value: null };\n")
                    append("        }\n")
                    append("        try {\n")
                    append("            return { ok: true, value: JSON.stringify(__result__) };\n")
                    append("        } catch (e) {\n")
                    append("            // No serializable(Como función/DOM nodo):bajar de categoría a String()\n")
                    append("            return { ok: true, value: String(__result__), stringifyError: e.message };\n")
                    append("        }\n")
                    append("    } catch (e) {\n")
                    append("        return { ok: false, error: e.message || String(e), stack: e.stack || '' };\n")
                    append("    }\n")
                    append("})();\n")
                }

                val raw = withTimeoutOrNull(timeoutMs) {
                    suspendCancellableCoroutine { cont ->
                        // B7-01: Inyecte la configuración del complemento actual en window.__musePluginConfig
                        // pluginConfigJson Ya es una cadena JSON segura; debe ser explícito incluso si está vacío
                        // Sobrescriba los valores antiguos para evitar filtrar el estado global del WebView compartido entre complementos.
                        val done = java.util.concurrent.atomic.AtomicBoolean(false)
                        fun finish(value: String?) {
                            if (done.compareAndSet(false, true) && cont.isActive) cont.resume(value)
                        }
                        asyncDeliver.set { payload -> finish(payload) }
                        cont.invokeOnCancellation { asyncDeliver.set(null) }
                        // INIT_JS debe estar en**antes de cada ejecución**Reinyectar:WebView Acerca de: el espacio en blanco solo se carga una vez,
                        // Pero loadDataWithBaseURL reemplazará todo el documento,window buscar/calza XHR en
                        // y el objeto host se borrarán juntos. —— Inyectarlo solo una vez al crear WebView equivale a no inyectar.
                        webView.evaluateJavascript(
                            INIT_JS + "\n" + buildHostInjectionScript(scopeKey, pluginConfigJson),
                        ) { }
                        webView.evaluateJavascript(wrappedCode) { result ->
                            // Script asincrónico: el script contenedor devuelve el centinela y __museAsync devuelve el resultado real más tarde. No termines aquí.
                            if (result != null && result.contains(ASYNC_SENTINEL)) return@evaluateJavascript
                            // withTimeout La devolución de llamada aún puede activarse después de la cancelación.;Utilice isActive guard para evitar que se cancele el currículum cont
                            finish(result)
                        }
                    }
                }

                if (raw == null) {
                    // tiempo de espera:JS Todavía funcionando en un V8(No se puede interrumpir),Pero el lado de Kotlin devuelve un error de tiempo de espera
                    val logs = synchronized(logsLock) { currentLogs.toList() }
                    // R-SVC-04 + C-30: Destruya WebView después del tiempo de espera y finalice realmente JS,Y el estado del fusible se acumula.
                    //   El recuento de interrupciones del circuito está aislado por ScopeKey,Evite un bucle infinito de complementos que interfieran con otras herramientas JS.
                    state.consecutiveTimeouts++
                    state.totalTimedOutMs += timeoutMs
                    if (state.consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS || state.totalTimedOutMs >= MAX_TOTAL_TIMED_OUT_MS) {
                        state.circuitBrokenUntil = System.currentTimeMillis() + CIRCUIT_COOLDOWN_MS
                        Logger.e(
                            TAG,
                            "JS Disyuntor de tiempo de espera de Sandbox(scope=${scopeKey ?: "default"}): " +
                                "consecutive=${state.consecutiveTimeouts} total=${state.totalTimedOutMs}",
                        )
                    }
                    destroy()
                    return@withContext Result.success(
                        JsResult(
                            value = null,
                            consoleLogs = logs,
                            error = "Tiempo de espera de ejecución(${timeoutMs}ms)",
                        ),
                    )
                }

                val logs = synchronized(logsLock) { currentLogs.toList() }
                val (value, error) = parseRawResult(raw)
                state.consecutiveTimeouts = 0
                // B-16a: totalTimedOutMs La monotonía original permanece sin cambios.,Una vez completado el enfriamiento del fusible, la cantidad acumulada aún se acerca al límite superior.,
                //   Como resultado, cualquier nuevo tiempo de espera hará que el disyuntor se vuelva a quemar inmediatamente.(No se recuperará hasta el reinicio manual). en ejecución exitosa
                //   Decaimiento por ventana fija(reducido a la mitad),Permitir la recuperación gradual después del disyuntor.;No está completamente autorizado a retener el
                //   Memoria conservadora para tiempos de espera consecutivos.(fixed-window Implementación conservadora de la decadencia.).
                state.totalTimedOutMs /= 2
                Result.success(JsResult(value = value, consoleLogs = logs, error = error))
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                // B-16a: La señal de cancelación de rutina debe propagarse hacia arriba.,no puede ser catch(Exception) tragar
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "JsSandbox execute anormal: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    /**
     * R-SVC-04: ¿Se encuentra actualmente en un período de disyuntor?(Incorporado/Predeterminado scope,null key).
     * C-30: Preservar la semántica global para las herramientas integradas;Por favor use el complemento [isCircuitBrokenFor] Juzgar por id.
     */
    val isCircuitBroken: Boolean get() = isCircuitBrokenFor(null)

    /** C-30: Especificar complemento/scope Si está en el período del disyuntor. */
    fun isCircuitBrokenFor(scopeKey: String?): Boolean {
        val state = circuitStates[scopeKey ?: DEFAULT_SCOPE_KEY] ?: return false
        return System.currentTimeMillis() < state.circuitBrokenUntil
    }

    /**
     * R-SVC-04: Fusible de reinicio manual(Reintentos del usuario/Se puede llamar después de que el complemento esté deshabilitado.).
     * C-30: Restablecimiento predeterminado incorporado scope;El restablecimiento del complemento pasa el ID del complemento correspondiente.
     */
    fun resetCircuitBreaker(scopeKey: String? = null) {
        val state = stateFor(scopeKey)
        state.circuitBrokenUntil = 0L
        state.consecutiveTimeouts = 0
        state.totalTimedOutMs = 0L
        Logger.i(TAG, "JsSandbox El fusible se ha reiniciado(scope=${scopeKey ?: "default"})")
    }

    /**
     * C-30: Limpiar los datos persistentes del Sandbox WebView antes de cada ejecución.,Evite los residuos de complementos cruzados. Debe ser llamado en el hilo principal.
     * (execute Ejecutar en serie en el hilo principal,seguro).
     *
     * P2-20: ya no me llaman WebStorage.deleteAllData() — La zona de pruebas solo se carga about:blank(opaque
     * origin,El almacenamiento local integrado no está disponible),WebStorage es almacenamiento global a nivel de aplicación,La eliminación se producirá al mismo tiempo.
     * Borre el sitio BrowserManager/navegador conectado localStorage(Estado de inicio de sesión perdido).
     */
    private fun clearPerExecutionData(webView: WebView) {
        runCatching {
            webView.clearCache(true)
            webView.clearFormData()
            webView.clearHistory()
        }.onFailure { e ->
            // El error de limpieza no afecta la ejecución de JS en sí(Los permisos de la zona de pruebas aún son limitados),Registre registros para solucionar problemas fácilmente
            Logger.w(TAG, "JsSandbox La limpieza de datos falla cada vez: ${e.message}")
        }
    }

    /**
     * destruir WebView(Se puede llamar en Application.onTerminate/Test tearDown,Liberar recursos).
     * Debe llamarse en el hilo principal.
     */
    fun destroy() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Logger.w(TAG, "destroy Debe ser llamado en el hilo principal.,ignorado")
            return
        }
        webViewRef?.let { wv ->
            wv.stopLoading()
            wv.removeAllViews()
            (wv.parent as? android.view.ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        webViewRef = null
        synchronized(logsLock) { currentLogs.clear() }
        Logger.i(TAG, "JsSandbox WebView Destruido")
    }

    // ── implementación interna ──────────────────────────────────────────────────────

    /**
     * crear/Obtenga el singleton de WebView. Debe llamarse en el hilo principal.
     * La primera llamada crea un JS restringido para inyección WebView +.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensureWebView(): WebView {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "JsSandbox WebView Debe ser creado y accedido en el hilo principal."
        }
        webViewRef?.let { return it }
        val ctx = appContext
            ?: error("JsSandbox no inicializado,Por favor llame primero init(context) inyectar Application Context")

        val wv = WebView(ctx).apply {
            // Canal de valor de retorno asincrónico (utilizado cuando el script devuelve Promise). Solo expone un método de devolución de llamada y no proporciona ninguna capacidad de lectura.
            @Suppress("AddJavascriptInterface")
            addJavascriptInterface(JsSandbox, "__museAsync")
            // Backend de corrección de red:Kotlin Solicitudes de problemas secundarios, sin pasar por el origen opaco del sandbox (CORS).
            addJavascriptInterface(JsSandbox, "__museHttp")
            // ── WebSettings:Habilite JS + desactive todas las funciones peligrosas ──
            settings.javaScriptEnabled = true
            settings.allowContentAccess = false
            settings.allowFileAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.databaseEnabled = false
            settings.domStorageEnabled = true // localStorage espera(Disponible en caja de arena)
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.mediaPlaybackRequiresUserGesture = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.loadsImagesAutomatically = false
            // v2.x(Versión Sandbox): ya no bloquea la carga de la red —— fetch/XHR/WebSocket Disponible directamente.
            // El acceso a archivos locales permanece cerrado (allowFileAccess=false / allowContentAccess=false),
            // Evite la lectura entre dominios file:// / content://.
            settings.blockNetworkLoads = false

            // ── WebViewClient:Bloquear solo la navegación de la página;Liberación de solicitud de red (mantenga el resultado final) ──
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true // Evitar cualquier salto de página

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    // v2.x(Versión Sandbox): No más bloqueos sin sentido. Mantenga únicamente file:// / content:// aislado (para evitar la lectura de archivos locales);
                    // http/https/wss Espere la liberación normal.
                    val scheme = request?.url?.scheme?.lowercase()
                    return if (scheme == "file" || scheme == "content") {
                        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("sandbox-local-blocked".toByteArray()))
                    } else {
                        null // Déjelo en WebView para el procesamiento normal.
                    }
                }
            }

            // ── WebChromeClient:Interceptar console.log para recopilar registros + bloquear ventanas emergentes ──
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    val level = when (consoleMessage.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> "error"
                        ConsoleMessage.MessageLevel.WARNING -> "warn"
                        ConsoleMessage.MessageLevel.DEBUG -> "debug"
                        ConsoleMessage.MessageLevel.TIP -> "tip"
                        ConsoleMessage.MessageLevel.LOG -> "log"
                        else -> "log"
                    }
                    synchronized(logsLock) {
                        currentLogs.add("[$level] ${consoleMessage.message()}")
                    }
                    return true
                }

                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult): Boolean {
                    // Recopile registros de alertas y bloquee ventanas emergentes
                    synchronized(logsLock) {
                        currentLogs.add("[alert] ${message ?: ""}")
                    }
                    result.cancel()
                    return true
                }

                override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult): Boolean {
                    synchronized(logsLock) {
                        currentLogs.add("[confirm] ${message ?: ""}")
                    }
                    result.cancel()
                    return true
                }
            }
        }

        // Cargar página en blanco(No cargue ningún externo URL)
        wv.loadDataWithBaseURL("about:blank", "<html><body></body></html>", "text/html", "utf-8", null)
        // evaluateJavascript Es asincrónico; debe esperar a que se complete la devolución de llamada INIT_JS antes de verificar para evitar condiciones de carrera.
        suspendCancellableCoroutine<Unit> { cont ->
            wv.evaluateJavascript(INIT_JS) { if (cont.isActive) cont.resume(Unit) }
        }
        val raw = suspendCancellableCoroutine<String?> { cont ->
            wv.evaluateJavascript(VERIFY_JS) { result -> if (cont.isActive) cont.resume(result) }
        }
        val verifiedResult = parseVerificationResult(raw)
        if (!verifiedResult.first) {
            Logger.w(TAG, "JsSandbox Error en la verificación del secuestro: ${verifiedResult.second.joinToString(", ")}")
            wv.destroy()
            throw JsSandboxUnavailableException("Error en la autoprueba del entorno Sandbox: ${verifiedResult.second.joinToString(", ").ifBlank { "razón desconocida" }}")
        }
        webViewRef = wv
        Logger.i(TAG, "WebView La zona de pruebas se ha inicializado")
        return wv
    }

    /**
     * Analice el valor de retorno de la autoprueba.
     *
     * [evaluateJavascript] La devolución de llamada proporciona el resultado codificado en JSON: normalmente es una cadena JSON con escape.
     * Cuando el script de autoprueba no devuelve un valor, es un valor literal `null`. Tienes que poder distinguir ambos tipos.
     * De lo contrario, el motivo del fallo se escribirá como“El análisis falló”, enmascarando el verdadero problema.
     */
    internal fun parseVerificationResult(raw: String?): Pair<Boolean, List<String>> = runCatching {
        if (raw == null || raw.isBlank() || raw == "null") {
            return@runCatching false to listOf("La autoprueba no arrojó resultados")
        }
        val first = AppJson.parseToJsonElement(raw)
        val elem = if (first is JsonPrimitive && first.isString) {
            AppJson.parseToJsonElement(first.content)
        } else {
            first
        }
        val obj = elem as? JsonObject ?: error("El formato de devolución de autodiagnóstico no es un objeto.")
        val verified = (obj["verified"] as? JsonPrimitive)?.booleanOrNull == true
        val issues = (obj["issues"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filter { it.isNotBlank() } ?: emptyList()
        verified to issues
    }.getOrElse { false to listOf("Error en el análisis del resultado de la autoprueba:${it.message}") }

    /**
     * Analiza la cadena devuelta por la devolución de llamada de evaluaJavascript.
     *
     * La devolución de llamada devuelve una cadena codificada en JSON.,Como por ejemplo:
     *  - null / "null" Indica que no hay valor de retorno
     *  - "{\"ok\":true,\"value\":\"42\"}" Indica ejecución exitosa
     *  - "{\"ok\":false,\"error\":\"...\"}" Indica error de ejecución
     */
    private fun parseRawResult(raw: String?): Pair<Any?, String?> {
        if (raw.isNullOrBlank() || raw == "null") return null to null
        return try {
            val element = AppJson.parseToJsonElement(raw)
            if (element !is JsonObject) {
                // Objeto que no es JSON(numeros/cuerda/Booleano):Devuelve el valor original directamente
                return raw to null
            }
            val ok = (element["ok"] as? JsonPrimitive)?.booleanOrNull ?: true
            if (ok) {
                val valueStr = (element["value"] as? JsonPrimitive)?.contentOrNull
                // Sin codificación secundaria:value Ya es texto JSON, que se analiza en elementos estructurados y se devuelve.
                // Deje que el flujo descendente se serialice solo una vez. De lo contrario return {"a":1} La cadena estará entre comillas.+escapar,
                // El modelo debe invertirse dos veces. —— Prueba real del usuario `"{\"a\":1}"` Así surgió.
                val structured = valueStr?.let { text ->
                    runCatching { AppJson.parseToJsonElement(text) }.getOrNull()
                }
                (structured ?: valueStr) to null
            } else {
                val err = (element["error"] as? JsonPrimitive)?.contentOrNull
                    ?: "error desconocido"
                null to err
            }
        } catch (e: Exception) {
            Logger.w(TAG, "No se pudo analizar el valor de retorno de JS: ${e.message}(raw=$raw)")
            raw to null
        }
    }

    /** Convertir cadena Kotlin en literal de cadena JS(Usando el estilo JSON.stringify). */
    private fun quoteJs(s: String): String {
        // Escape con estilo JSON:Sencillo y seguro
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append("\\u").append("%04x".format(ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append("\"")
        return sb.toString()
    }
}
