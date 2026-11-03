package io.zer0.muse.tools

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.util.Base64
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * P2-6 BrowserManager:Administrador de automatización del navegador.
 *
 * sostener un headless [WebView] Ejemplo,Permita que AI Agent complete la automatización del navegador mediante llamadas a herramientas:
 *  - [navigate] Cargar URL,Actualizar cuando esté completo [currentUrl] / [currentTitle] / [currentHtml]
 *  - [evaluateJs] Ejecutar cualquier cosa en la página actual. JS,Devuelve una cadena codificada en JSON
 *  - [click] / [type] / [extractText] / [extractAttribute] Operaciones comunes basadas en selectores CSS
 *  - [scrollToBottom] Pasa la página para cargar más contenido.(Escena de desplazamiento infinito)
 *  - [captureScreenshot] Representar el WebView actual como Base64 PNG
 *
 * Puntos de diseño(prensa [JsSandbox] Modo de zona de pruebas WebView):
 *  - WebView Debe ser creado y accedido en el hilo principal.,Utilizado por todas las funciones de suspensión. [Dispatchers.Main] cambiar
 *  - [evaluateJs] usar [suspendCancellableCoroutine] + `ValueCallback<String>` Puentear devoluciones de llamadas asincrónicas
 *  - Tiempo de espera de 10 segundos para todas las operaciones.([DEFAULT_TIMEOUT_MS]),usar [withTimeoutOrNull] El paquete evita bloqueos infinitos
 *  - restricciones de seguridad:Desactivar file access / content access(Evitar lecturas entre dominios file://),
 *    Pero permitir la carga de red.(El navegador necesita extraer la página remota.)
 *  - WebView Creado con applicationContext,headless correr(No adjunto a ningún árbol de vistas),
 *    Puede ser llamado directamente por herramientas de IA
 *
 * Relación con las herramientas de automatización del navegador:
 *
 * @param context Solicitud Context(Creado con applicationContext WebView,Evite fugas de actividad)
 */
class BrowserManager(private val context: Context) {

    companion object {
        private const val TAG = "BrowserManager"

        /** Tiempo de espera predeterminado para todas las operaciones asincrónicas de WebView(milisegundos). */
        private const val DEFAULT_TIMEOUT_MS = 10_000L

        /** Extraiga fragmentos JS del HTML de la página. */
        private const val GET_OUTER_HTML_JS =
            "(function(){ try { return document.documentElement.outerHTML; } catch(e){ return ''; } })();"
    }

    // ── StateFlows(Para que UI/AI observe el estado actual de la página)─────────────────────────────

    private val _currentUrl = MutableStateFlow("")

    /** URL de la página actual. */
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _currentTitle = MutableStateFlow("")

    /** Título de la página actual. */
    val currentTitle: StateFlow<String> = _currentTitle.asStateFlow()

    private val _currentHtml = MutableStateFlow("")

    /** La página actual está completa. HTML;El contenido extralargo se guarda mediante la ruta de relleno de la herramienta y se proporciona para lectura paginada. */
    val currentHtml: StateFlow<String> = _currentHtml.asStateFlow()

    private val _currentScreenshot = MutableStateFlow<String?>(null)

    /** Captura de pantalla de la página(Base64 PNG,Opcional). */
    val currentScreenshot: StateFlow<String?> = _currentScreenshot.asStateFlow()

    private val _isActive = MutableStateFlow(false)

    /** ¿Está el navegador en uso?(navigate después true,close después false).UI Muestre la cápsula de estado en consecuencia. */
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _isLoading = MutableStateFlow(false)

    /** ¿Se está cargando la página actual?(Animación para instrucciones de carga de cápsulas.). */
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _canGoBack = MutableStateFlow(false)

    /** Si se puede devolver la página actual(La barra de herramientas del visor aparece atenuada en consecuencia.). */
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    private val _canGoForward = MutableStateFlow(false)

    /** Si la página actual se puede avanzar. */
    val canGoForward: StateFlow<Boolean> = _canGoForward.asStateFlow()

    /** WebView Referencia única(Se debe acceder desde el hilo principal.).@Volatile Visibilidad garantizada. */
    @Volatile
    private var webViewRef: WebView? = null

    /**
     * P2-33: SSRF puerta de decisión — La devolución de llamada del hilo principal solo consume el caché,El análisis siempre se realiza en un hilo en segundo plano.
     * La primera resolución de un nombre de dominio frío ya no bloquea el hilo principal(anteriormente [navigate] Sincronizar en el hilo principal `getAllByName` La causa fundamental de la ANR).
     */
    private val ssrfGate = WebViewSsrfGate()

    // ── publico API ──────────────────────────────────────────────────────────

    /**
     * Navegar a [url]. Actualización después de que se complete la carga [currentUrl] / [currentTitle] / [currentHtml].
     *
     * @param url objetivo URL(Se puede omitir scheme,llenar automáticamente https://)
     * @return Result<Unit> — éxito o fracaso(tiempo de espera/anormal)
     */
    suspend fun navigate(url: String): Result<Unit> {
        val target = normalizeUrl(url)
        // C-3: SSRF protección — Denegar intranet/dirección de bucle invertido,Evite que el agente de IA sea inducido a acceder a los servicios de la intranet mediante una inyección rápida
        // about:blank / file:// No verificado por la SSRF(Sin solicitud de red)
        // B-32: WebView Sin exposición a la fijación de DNS(pinning)/Dirección del socket del período de conexión,No se pueden analizar los resultados como OkHttp
        // Congelar como objetivo de unión para eliminar por completo el análisis secundario;Las devoluciones de llamada aquí y abajo están en cada navegación./Revalidación de eventos de recursos,
        // Ya es la protección salto a salto más potente disponible en el modelo WebView(rebinding El cambio de IP también será bloqueado por eventos posteriores.).
        // P2-33: El análisis se movió dentro de Dispatchers.IO(WebViewSsrfGate → SsrfGuard.isBlockedAsync),
        // El hilo principal solo consume los resultados del juicio.;Primer resultado del análisis escribiendo caché,Devolución de llamada posterior(hilo principal)Un golpe directo.
        if (!target.startsWith("about:") && !target.startsWith("file:")) {
            if (ssrfGate.isBlockedAsync(target)) {
                Logger.w(TAG, "navigate Interceptado por guardias de la SSRF.: $target")
                return Result.failure(
                    java.lang.SecurityException("SSRF blocked: $target"),
                )
            }
        }
        return withContext(Dispatchers.Main) {
            try {
                val webView = ensureWebView()
                _isActive.value = true
                _isLoading.value = true

                // B-16b: tiempo de espera/Las ramas anormales deben restaurarse temporalmente client,Por lo tanto, el cliente original se menciona como captura externa conTimeoutOrNull,
                // De lo contrario, onPageFinished no se activará después del tiempo de espera.,El cliente temporal permanecerá y seguirá generando devoluciones de llamada huérfanas.
                val previousClient = webView.webViewClient
                // Reemplazar temporalmente la interceptación de WebViewClient onPageFinished;Utilice suspendCancellableCoroutine para esperar a que se complete
                val success = withTimeoutOrNull(DEFAULT_TIMEOUT_MS) {
                    suspendCancellableCoroutine { cont ->
                        webView.webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                if (isBlockedHttpUrl(url)) {
                                    Logger.w(TAG, "navigate Redirección interceptada por guardia de la SSRF: $url")
                                    view?.stopLoading()
                                    view?.webViewClient = previousClient
                                    if (cont.isActive) {
                                        cont.resumeWith(
                                            Result.failure(java.lang.SecurityException("SSRF blocked: $url")),
                                        )
                                    }
                                    return
                                }
                                _isLoading.value = true
                            }
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val requestUrl = request?.url?.toString()
                                if (isBlockedHttpUrl(requestUrl)) {
                                    Logger.w(TAG, "navigate URL Redirección interceptada por guardia de la SSRF: $requestUrl")
                                    return true
                                }
                                return false
                            }
                            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                val requestUrl = request?.url?.toString()
                                if (isBlockedHttpUrlOffMainThread(requestUrl)) {
                                    Logger.w(TAG, "navigate Solicitud de recursos interceptada por guardia de la SSRF: $requestUrl")
                                    return blockedResourceResponse()
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                _isLoading.value = false
                                _currentUrl.value = view?.url ?: url ?: target
                                _currentTitle.value = view?.title ?: ""
                                // Tirón asincrónico HTML,La navegación se considera completa incluso si falla
                                view?.evaluateJavascript(GET_OUTER_HTML_JS) { raw ->
                                    updateCurrentHtmlFromEvaluation(raw)
                                }
                                // restaurar original client,Evite la acumulación de interceptores de navegación posteriores.
                                view?.webViewClient = previousClient
                                if (cont.isActive) cont.resume(true)
                            }
                            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                super.onReceivedError(view, request, error)
                                _isLoading.value = false
                                if (request?.isForMainFrame != false && cont.isActive) {
                                    val code = error?.errorCode ?: -1
                                    val desc = error?.description?.toString() ?: "Error al cargar la página"
                                    view?.webViewClient = previousClient
                                    cont.resumeWith(Result.failure(java.io.IOException("Error al cargar la página($code): $desc")))
                                }
                            }
                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: WebResourceResponse?,
                            ) {
                                super.onReceivedHttpError(view, request, errorResponse)
                                _isLoading.value = false
                                if (request?.isForMainFrame == true && cont.isActive) {
                                    val code = errorResponse?.statusCode ?: -1
                                    val reason = errorResponse?.reasonPhrase ?: "HTTP error"
                                    view?.webViewClient = previousClient
                                    cont.resumeWith(Result.failure(java.io.IOException("HTTP $code: $reason")))
                                }
                            }
                        }
                        webView.loadUrl(target)
                    }
                }

                if (success == null) {
                    _isLoading.value = false
                    // B-16b: onPageFinished normalmente no se activa después del tiempo de espera,El cliente temporal restante seguirá llamando en segundo plano.
                    // estado de huérfano;Detener explícitamente la carga + restaurar al original client,Termine la carga en segundo plano y borre los interceptores temporales.
                    webView.stopLoading()
                    webView.webViewClient = previousClient
                    Logger.w(TAG, "navigate tiempo de espera: $target")
                    Result.failure(java.util.concurrent.TimeoutException("navigate tiempo de espera(${DEFAULT_TIMEOUT_MS}ms): $target"))
                } else {
                    Logger.i(TAG, "navigate éxito: $target → ${_currentUrl.value} | title=${_currentTitle.value.take(60)}")
                    Result.success(Unit)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // B-16b: Cancelación de rutina(CancellationException)Debe volverse a lanzar tal como está.,No puede considerarse una excepción ordinaria.,De lo contrario, se rompe la simultaneidad estructurada.
                throw e
            } catch (e: Exception) {
                _isLoading.value = false
                Logger.e(TAG, "navigate anormal: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Ejecute JavaScript arbitrario.
     *
     * @param script JS código(bloque de expresión o declaración)
     * @return Result<String> — Devuelve con éxito una cadena codificada en JSON(Como por ejemplo `"hello"` / `42` / `true` / `null`)
     */
    suspend fun evaluateJs(script: String): Result<String> = withContext(Dispatchers.Main) {
        try {
            val webView = ensureWebView()
            val raw = withTimeoutOrNull(DEFAULT_TIMEOUT_MS) {
                suspendCancellableCoroutine<String> { cont ->
                    webView.evaluateJavascript(script) { value ->
                        // withTimeoutOrNull La devolución de llamada aún puede activarse después de la cancelación.,Guardia con isActive
                        if (cont.isActive) cont.resume(value ?: "null")
                    }
                }
            } ?: run {
                Logger.w(TAG, "evaluateJs tiempo de espera(${DEFAULT_TIMEOUT_MS}ms): ${script.take(120)}")
                return@withContext Result.failure(
                    java.util.concurrent.TimeoutException("evaluateJs tiempo de espera(${DEFAULT_TIMEOUT_MS}ms)"),
                )
            }
            Result.success(raw)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // B-16b: La cancelación de la rutina debe volver a lanzarse tal como está.,No puede considerarse una excepción ordinaria.,De lo contrario, se rompe la simultaneidad estructurada.
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "evaluateJs anormal: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Haga clic en el primer elemento alcanzado por el selector CSS.
     * @return Result<Boolean> — true significa buscar y hacer clic,false Indica una falla
     */
    suspend fun click(selector: String): Result<Boolean> {
        val script = buildString {
            append("(function(){ try { var el = document.querySelector(")
            append(jsStringLiteral(selector))
            append("); if(!el) return false; el.click(); return true; } catch(e){ return false; } })();")
        }
        return evaluateJs(script).mapCatching { parseJsValue(it) == "true" }
    }

    /**
     * Ingrese texto en la primera entrada/área de texto alcanzada por el selector CSS.
     * Activa eventos de entrada + cambio para que sean compatibles con marcos como React/Vue.
     * @return Result<Boolean> — true significa buscar y entrar,false Indica una falla
     */
    suspend fun type(selector: String, text: String): Result<Boolean> {
        val script = buildString {
            append("(function(){ try { var el = document.querySelector(")
            append(jsStringLiteral(selector))
            append("); if(!el) return false; el.focus(); el.value = ")
            append(jsStringLiteral(text))
            append("; el.dispatchEvent(new Event('input',{bubbles:true}));")
            append(" el.dispatchEvent(new Event('change',{bubbles:true})); return true; } catch(e){ return false; } })();")
        }
        return evaluateJs(script).mapCatching { parseJsValue(it) == "true" }
    }

    /**
     * Extrae el contenido de texto del elemento afectado por un selector CSS.
     * @return Result<String> — El contenido del texto del elemento.;Devuelve una cadena vacía en caso de error
     */
    suspend fun extractText(selector: String): Result<String> {
        val script = buildString {
            append("(function(){ try { var el = document.querySelector(")
            append(jsStringLiteral(selector))
            append("); return el ? el.textContent : null; } catch(e){ return null; } })();")
        }
        return evaluateJs(script).mapCatching { parseJsValue(it) }
    }

    /**
     * Extrae las propiedades especificadas del elemento afectado por un selector CSS.
     * @return Result<String> — valor del atributo;Si no se acierta o el atributo no existe, se devolverá una cadena vacía.
     */
    suspend fun extractAttribute(selector: String, attribute: String): Result<String> {
        val script = buildString {
            append("(function(){ try { var el = document.querySelector(")
            append(jsStringLiteral(selector))
            append("); return el ? el.getAttribute(")
            append(jsStringLiteral(attribute))
            append(") : null; } catch(e){ return null; } })();")
        }
        return evaluateJs(script).mapCatching { parseJsValue(it) }
    }

    /**
     * Desplácese hasta el final de la página. Comúnmente utilizado en escenarios de desplazamiento infinito para cargar más contenido.
     * @return Result<Unit>
     */
    suspend fun scrollToBottom(): Result<Unit> {
        val result = evaluateJs(
            "(function(){ window.scrollTo(0, document.body.scrollHeight); return document.body.scrollHeight; })();",
        )
        return result.map { Unit }
    }

    /**
     * Captura de pantalla del contenido de renderizado actual de WebView,Devuelve un PNG codificado en Base64.
     * Actualizaciones sincrónicas [currentScreenshot].
     */
    suspend fun captureScreenshot(): Result<String> = withContext(Dispatchers.Main) {
        try {
            val webView = ensureWebView()
            val width = webView.width.coerceAtLeast(1)
            val height = webView.height.coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            webView.draw(canvas)
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 80, baos)
            bitmap.recycle()
            val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            _currentScreenshot.value = base64
            Result.success(base64)
        } catch (e: Exception) {
            Logger.e(TAG, "captureScreenshot anormal: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Inicialice explícitamente el WebView del navegador.
     *
     * estructura [BrowserManager] no se crea solo WebView,De esta forma, el estado de la sesión se puede procesar sin consumir recursos de renderizado.
     * Regístrese con anticipación.UI Este método se llama cuando se abre el visor del navegador.,Asegúrese de que el visor siempre tenga un WebView montable.
     * Debe llamarse en el hilo principal.
     *
     * @return Instancia interna de WebView
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun initialize(): WebView = ensureWebView()

    /**
     * Abre una página de presentación visible para el usuario.
     *
     * AI Cuando todavía no se ha llamado a browser_navigate,El espectador aún debe tener una pantalla de navegador y un estado claros. Sólo si actualmente no hay ninguna página
     * y no hay navegación continua. [about:blank],No sobrescriba la navegación ya iniciada por AI,También usado [navigate]
     * SSRF/tiempo de espera/Cargar semántica de devolución de llamada.
     */
    suspend fun showBlankPageIfNeeded(): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            ensureWebView()
            if (hasPage() || _isLoading.value) {
                Result.success(Unit)
            } else {
                navigate("about:blank")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "showBlankPageIfNeeded anormal: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Adjunte el WebView interno al contenedor especificado(UI para exhibición). Si WebView ya está colgado en otros contenedores,Quitar primero y luego volver a instalar.
     * Devolver directamente cuando ya esté en el mismo contenedor.(idempotente,evitar volver a dibujar). Debe llamarse en el hilo principal.
     *
     * @param parent objetivo ViewGroup(Contenedores como Compose AndroidView)
     * @return Instancia interna de WebView
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun attachToDisplay(parent: android.view.ViewGroup): WebView {
        val wv = ensureWebView()
        if (wv.parent === parent) return wv
        (wv.parent as? android.view.ViewGroup)?.removeView(wv)
        parent.addView(
            wv,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        wv.onResume()
        return wv
    }

    /** Actualizar la página actual(UI botón de la barra de herramientas). */
    fun reload() {
        val wv = webViewRef ?: return
        _isLoading.value = true
        wv.reload()
    }

    /** dar un paso atrás(UI Barra de herramientas). Devuelve falso si no hay elementos para retroceder en el historial. */
    fun goBack(): Boolean {
        val wv = webViewRef ?: return false
        if (!wv.canGoBack()) return false
        _isLoading.value = true
        wv.goBack()
        return true
    }

    /** un paso adelante(UI Barra de herramientas). Devuelve falso si no hay elementos reenviados en el historial. */
    fun goForward(): Boolean {
        val wv = webViewRef ?: return false
        if (!wv.canGoForward()) return false
        _isLoading.value = true
        wv.goForward()
        return true
    }

    /**
     * Borrar cookies + almacenamiento web + caché.
     *
     * Después de que el usuario navegue manualmente, el usuario iniciará sesión.(Compartido con IA WebView),Se requiere una salida explícita
     * Limpiar el estado de la sesión,Deje que los rastreos posteriores vuelvan al estado no registrado.
     */
    fun clearCookies() {
        runCatching {
            android.webkit.CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
            webViewRef?.clearCache(true)
            android.webkit.WebStorage.getInstance().deleteAllData()
        }.onFailure { e ->
            Logger.w(TAG, "Error al borrar las cookies: ${e.message}")
        }
    }

    /** Avanzar simultáneamente/Retroceder la disponibilidad al estado de la interfaz de usuario(WebView Llamado cuando la historia cambia.). */
    private fun syncNavState(view: WebView?) {
        _canGoBack.value = view?.canGoBack() == true
        _canGoForward.value = view?.canGoForward() == true
    }

    /**
     * Quitar del contenedor de exhibición WebView(Regreso al estado sin cabeza,Seguir siendo utilizado por herramientas de IA).
     * Debe llamarse en el hilo principal.
     *
     * Nota:no llamado onPause —— headless En este estado, las herramientas de IA todavía dependen de la ejecución de la página JS.
     * (evaluateJavascript/click/type espera),onPause JS se congelará, lo que provocará que fallen todas las llamadas a herramientas posteriores.
     */
    fun detachFromDisplay() {
        val wv = webViewRef ?: return
        (wv.parent as? android.view.ViewGroup)?.removeView(wv)
    }

    /** ¿Hay una página cargada?(Para que la interfaz de usuario determine si se muestra la cápsula). */
    fun hasPage(): Boolean = webViewRef != null && _currentUrl.value.isNotBlank()

    /**
     * Cerrar el navegador:Detener la carga + destruir WebView + borrar StateFlow.
     * Debe ser operado en el hilo principal. WebView;Las llamadas de hilos no principales se publicarán en el hilo principal.
     */
    fun close() {
        _isActive.value = false
        _isLoading.value = false
        val webView = webViewRef ?: return
        val destroyAction = {
            try {
                webView.stopLoading()
                webView.removeAllViews()
                (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                webView.destroy()
            } catch (e: Exception) {
                Logger.w(TAG, "close destroy anormal: ${e.message}")
            }
            webViewRef = null
            _currentUrl.value = ""
            _currentTitle.value = ""
            _currentHtml.value = ""
            _currentScreenshot.value = null
            _canGoBack.value = false
            _canGoForward.value = false
            Logger.i(TAG, "BrowserManager Cerrado")
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            destroyAction()
        } else {
            webView.post { destroyAction() }
        }
    }

    // ── implementación interna ──────────────────────────────────────────────────────────

    /**
     * crear/Obtenga el singleton de WebView. Debe llamarse en el hilo principal.
     * La primera llamada crea WebView + configura las restricciones de seguridad.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "BrowserManager WebView Debe ser creado y accedido en el hilo principal."
        }
        webViewRef?.let { return it }

        val wv = WebView(context.applicationContext).apply {
            // ── WebSettings:Habilite JS + funciones requeridas del navegador + restricciones de seguridad estrictas ──
            settings.javaScriptEnabled = true
            // estricta seguridad:Desactivar file/content access(Evitar lecturas entre dominios file://)
            settings.allowContentAccess = false
            settings.allowFileAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            // DOM storage(localStorage / sessionStorage)— Las páginas web modernas generalmente se basan en
            settings.domStorageEnabled = true
            // El navegador requiere carga de red.(Diferente de JsSandbox,La red está permitida aquí.)
            settings.blockNetworkLoads = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.loadsImagesAutomatically = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.mediaPlaybackRequiresUserGesture = true
            // Ventana gráfica y zoom
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false

            // ── WebViewClient:Supervisar la finalización de la carga de la página,actualizar StateFlow ──
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (isBlockedHttpUrl(url)) {
                        Logger.w(TAG, "Navegación de página bloqueada por guardia de la SSRF: $url")
                        view?.stopLoading()
                        return
                    }
                    _isLoading.value = true
                    _currentUrl.value = url ?: ""
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val requestUrl = request?.url?.toString()
                    if (isBlockedHttpUrl(requestUrl)) {
                        Logger.w(TAG, "URL Redirección interceptada por guardia de la SSRF: $requestUrl")
                        return true
                    }
                    return false
                }
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val requestUrl = request?.url?.toString()
                    if (isBlockedHttpUrlOffMainThread(requestUrl)) {
                        Logger.w(TAG, "Solicitud de recursos interceptada por guardia de la SSRF: $requestUrl")
                        return blockedResourceResponse()
                    }
                    return super.shouldInterceptRequest(view, request)
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    _isLoading.value = false
                    _currentUrl.value = view?.url ?: url ?: ""
                    _currentTitle.value = view?.title ?: ""
                    syncNavState(view)
                    view?.evaluateJavascript(GET_OUTER_HTML_JS) { raw ->
                        updateCurrentHtmlFromEvaluation(raw)
                    }
                }

                /**
                 * Cambios en la pila del historial(Contiene manual de usuario, haga clic en el enlace/hacia atrás y adelante)Sincronizar el estado de la barra de herramientas.
                 * La navegación manual del usuario y las operaciones de IA comparten lo mismo WebView,Después de la sincronización aquí, ambas partes ven la misma página.
                 */
                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    syncNavState(view)
                }

                /** ROM compatibles:El proceso de renderizado falla(WebView es inestable en algunas ROM)Destruir y reconstruir,Se restaurará automáticamente la próxima vez que se llame. */
                override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                    Logger.e(TAG, "WebView El proceso de renderizado falla: ${detail?.didCrash()}, Destruir y reconstruir")
                    _isLoading.value = false
                    view?.let { v ->
                        (v.parent as? android.view.ViewGroup)?.removeView(v)
                        runCatching { v.destroy() }
                    }
                    webViewRef = null
                    return true
                }
            }

            // ── WebChromeClient:Sincronizar el progreso del título ──
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    super.onReceivedTitle(view, title)
                    _currentTitle.value = title ?: ""
                }
            }
        }

        webViewRef = wv
        Logger.i(TAG, "BrowserManager WebView Inicializado")
        return wv
    }

    /**
     * WebView callbacks also observe redirects and subresources, so every HTTP(S)
     * URL must pass the same guard as the initial navigation. Non-network URLs
     * (including about:blank and file://) remain allowed by design.
     *
     * P2-33: Ruta de devolución de llamada del hilo principal — Solo consume caché SSRF,El host frío falla y se calienta en segundo plano,
     * Después de analizar y confirmar la seguridad, vuelva a intentar el salto bloqueado en el hilo principal.(Los nombres de dominio fríos ya no bloquean el hilo principal,La redirección tampoco quedará bloqueada permanentemente.).
     */
    private fun isBlockedHttpUrl(url: String?): Boolean {
        if (url == null || !isHttpUrl(url)) return false
        return ssrfGate.isBlockedNow(url) { webViewRef?.loadUrl(url) }
    }

    /**
     * [WebViewClient.shouldInterceptRequest] Devolución de llamada en el hilo de fondo de WebView,Permitir análisis sincrónico
     * (Los resultados del análisis se escriben en la memoria caché.,La devolución de llamada del hilo principal llega inmediatamente);Una ruta cerrada por falla que aún se degrada a un caché de solo lectura en la devolución de llamada del hilo principal.
     */
    private fun isBlockedHttpUrlOffMainThread(url: String?): Boolean {
        if (url == null || !isHttpUrl(url)) return false
        val onMainThread = Looper.myLooper() != null && Looper.myLooper() == Looper.getMainLooper()
        return if (onMainThread) ssrfGate.isBlockedNow(url) else ssrfGate.isBlockedBlocking(url)
    }

    private fun isHttpUrl(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    private fun blockedResourceResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "UTF-8",
        403,
        "Blocked by SSRF guard",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0)),
    )

    /** URL estandarizar:completo scheme,Las cadenas vacías se degradan a about:blank. */
    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return "about:blank"
        if (
            trimmed.startsWith("http://") ||
            trimmed.startsWith("https://") ||
            trimmed.startsWith("about:") ||
            trimmed.startsWith("file:")
        ) {
            return trimmed
        }
        return "https://$trimmed"
    }

    /**
     * Analice la cadena codificada en JSON devuelta por la devolución de llamada de evaluaJavascript.
     *
     * Reglas de valor de retorno de devolución de llamada:
     *  - null / "null" → cadena vacía
     *  - "\"hello\"" → hello(JSON Desempaquetado de cuerdas)
     *  - "42" / "true" → 42 / true(literal primitivo)
     *  - "{\"a\":1}" → {"a":1}(Texto JSON sin formato)
     */
    private fun parseJsValue(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return try {
            val element = AppJson.parseToJsonElement(raw)
            // cuerda/numeros/literal booleano → tomar content(JsonPrimitive Desenvolver cotizaciones automáticamente)
            // objeto/matriz → toString() Devuelve el texto JSON tal cual
            element.jsonPrimitive.contentOrNull ?: ""
        } catch (e: Exception) {
            // Literales no JSON(En teoría no sucederá,evaluateJavascript Devolver siempre valores codificados en JSON)
            raw
        }
    }

    internal fun updateCurrentHtmlFromEvaluation(raw: String?) {
        _currentHtml.value = parseJsValue(raw)
    }

    /**
     * Codifique la cadena Kotlin en un literal de cadena JS(Comillas dobles + estilo JSON de escape).
     * Utilice escape estilo JSON para evitar riesgos de inyección(seleccionador/El texto puede contener caracteres especiales.).
     */
    private fun jsStringLiteral(s: String): String {
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
