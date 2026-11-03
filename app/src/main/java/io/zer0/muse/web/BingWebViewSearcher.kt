package io.zer0.muse.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * v1.0.81: Búsqueda de Bing basada en WebView de Android sin cabeza.
 *
 * por qué es necesario:
 *  Cargue la página de resultados de Bing usando un navegador real,
 *  Espere a que JS se procese antes de inyectar el script de extracción,En lugar de utilizar OkHttp para capturar HTML estático. Este último está en la red doméstica./Subir hacia atrás/
 *  Desempeño inestable bajo la redirección regional(bueno y malo"dibujar cartas"fenómeno).
 *
 *  Esta categoría es [BingProvider] Error de rastreo HTTP/Qué hacer cuando te interceptan,Sin reemplazo HTTP(HTTP Rápido).
 *  Utiliza applicationContext para crear una interfaz sin interfaz. WebView,Carga fuera de pantalla cn.bing.com,
 *  Inyectar en la página y extraer JS,Obtenga resultados estructurados.
 *
 * Puntos de diseño:
 *  - Instancia de WebView independiente,no con [io.zer0.muse.tools.BrowserManager](Herramientas de automatización del navegador)Compartir,
 *    Evitar que los usuarios utilicen las herramientas del navegador simultáneamente/cookie interferir el uno con el otro;
 *  - WebView Debe crearse en el hilo principal./visita,Todas las operaciones están cortadas. [Dispatchers.Main];
 *  - Reutilizar el mismo para cada búsqueda WebView(Elimine la sobrecarga de inicializar Chromium repetidamente),Pero usa la independencia WebViewClient
 *    Bloquear carga única,Restaurar después de completar;
 *  - Detectar código de verificación/Página de consentimiento(captcha/verify/consent),Hit regresa vacío y deja que el nivel superior pase al siguiente motor.;
 *  - tiempo de espera fijo,Evite que WebView se atasque y ralentice las búsquedas.
 *
 * Seguridad del hilo:Todos los métodos públicos cambian al hilo principal internamente.;Se puede llamar externamente desde cualquier hilo.
 */
class BingWebViewSearcher(private val appContext: Context) {

    companion object {
        private const val TAG = "BingWebView"

        /** Tiempo de espera de búsqueda única(Contiene carga de página + renderizado JS + extracción). */
        private const val SEARCH_TIMEOUT_MS = 20_000L

        /** Escritorio Chrome UA,Obtenga la estructura li.b_algo del escritorio. */
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /** Bing Cookie de resultados en chino de escritorio. */
        private const val BING_COOKIE =
            "SRCHHPGUSR=NRSLT=10;SRCHLANG=zh-CN;HIDTTONS=1;ADLT=MODERATE; SRCHLANG=zh-CN; _SS=mlock=1"
    }

    @Volatile
    private var webViewRef: WebView? = null

    /**
     * Utilice WebView para cargar la página de búsqueda de Bing y extraer los resultados.
     *
     * @param query palabra de consulta(texto original,Codificación de URL interna)
     * @param maxResults Número máximo de artículos devueltos
     * @return Resultados de la búsqueda;interceptado/tiempo de espera/El error de análisis devuelve una lista vacía(No lanzar excepción,desde arriba fallback)
     */
    suspend fun search(query: String, maxResults: Int): List<WebSearchResult> = withContext(Dispatchers.Main) {
        val wv = ensureWebView()
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = buildString {
            append("https://cn.bing.com/search?q=").append(encoded)
            append("&mkt=zh-CN&setlang=zh-CN&FORM=Z9FD1")
        }

        val outcome = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
            loadAndExtract(wv, url, maxResults)
        }
        when {
            outcome == null -> {
                Logger.w(TAG, "search tiempo de espera(${SEARCH_TIMEOUT_MS}ms): $query")
                emptyList()
            }
            outcome.blocked -> {
                Logger.w(TAG, "Bing devuelve el código de verificación/Bloquear página,Renunciar a WebView: $query")
                emptyList()
            }
            else -> outcome.results
        }
    }

    /** Cargar [url] e inyectar la extracción después de renderizar la página JS,Devolver resultados estructurados. */
    private suspend fun loadAndExtract(wv: WebView, url: String, maxResults: Int): ExtractOutcome = suspendCancellableCoroutine { cont ->
        val previousClient = wv.webViewClient
        var resumed = false
        // Número de intentos de sondeo e intervalo:Algunos resultados de Bing son renderizado asincrónico JS,Una espera fija de 600 ms a veces no es suficiente.
        // onPageFinished Luego intenta extraer cada 250 ms.,conseguir ≥1 Los resultados pueden alcanzar el límite superior(aprox. 3s)Ese es el final.
        val maxAttempts = 12
        var attempt = 0
        val pollIntervalMs = 250L
        val watchdogDelayMs = 5_000L
        var pollingStarted = false

        lateinit var pollRunnable: Runnable
        lateinit var watchdogRunnable: Runnable

        fun startPolling(reason: String) {
            if (pollingStarted || resumed) return
            pollingStarted = true
            Logger.i(TAG, "start extraction polling: reason=$reason url=${wv.url ?: ""}")
            wv.post(pollRunnable)
        }

        fun finishOnce(outcome: ExtractOutcome) {
            if (resumed) return
            resumed = true
            wv.removeCallbacks(pollRunnable)
            wv.removeCallbacks(watchdogRunnable)
            wv.webViewClient = previousClient
            if (cont.isActive) cont.resume(outcome)
        }

        pollRunnable = Runnable {
            try {
                val script = buildExtractionScript(maxResults)
                wv.evaluateJavascript(script) { raw ->
                    val outcome = parseExtraction(raw)
                    Logger.i(TAG, "extract attempt=${attempt + 1}: url=${wv.url ?: ""}, blocked=${outcome.blocked}, results=${outcome.results.size}, raw=${raw?.take(180)}")
                    attempt++
                    // Finaliza cuando se obtiene el resultado, se intercepta o se alcanza el número máximo de intentos.;De lo contrario, continúe con la encuesta.
                    if (outcome.blocked || outcome.results.isNotEmpty() || attempt >= maxAttempts) {
                        finishOnce(outcome)
                    } else {
                        wv.postDelayed(pollRunnable, pollIntervalMs)
                    }
                }
            } catch (e: Exception) {
                Logger.w(TAG, "evaluateJavascript anormal: ${e.message}")
                finishOnce(ExtractOutcome(false, emptyList()))
            }
        }

        // algunos emuladores/La página del entorno web nunca activa onPageFinished, pero el DOM ya es legible.
        // watchdog Ejecutar la extracción directamente en el punto sin“Evento de carga de página completa”como única condición de éxito.
        watchdogRunnable = Runnable { startPolling("watchdog-${watchdogDelayMs}ms") }

        wv.webViewClient = object : WebViewClient() {
            private var finished = false

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Logger.i(TAG, "onPageStarted: ${url ?: ""}")
                // Bing Es común completar la página una vez y luego pasarla. rdr=1 Redirigir. La primera versión estará en la página del medio.
                // EvaluarJavascript se inicia, lo que provoca que los subprocesos de procesamiento posteriores se bloqueen y, finalmente, se agoten durante 20 segundos.
                // Después de descubrir el nuevo documento principal, cancele el sondeo anterior y espere a que onPageFinished de la página final antes de recuperarlo.
                if (finished && !resumed) {
                    finished = false
                    // Clave: pollingStarted se ha establecido en verdadero en la primera página; debe restablecerse después de la redirección.
                    // De lo contrario, onPageFinished eventualmente será bloqueado por la protección idempotente de startPolling, y toda la búsqueda tendrá que esperar hasta 20 segundos para que expire el tiempo de espera.
                    pollingStarted = false
                    wv.removeCallbacks(pollRunnable)
                    wv.removeCallbacks(watchdogRunnable)
                    Logger.i(TAG, "Se redirige el documento principal, se restablece el estado de extracción y se espera la página final antes de la extracción.")
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Logger.i(TAG, "onPageFinished: ${view?.url ?: url ?: ""}")
                if (finished) return
                finished = true
                // Deje de cargar los recursos restantes una vez completada la página para evitar la redirección continua de evaluaJavascript/subrecurso
                // Ocupa el hilo de renderizado. El documento principal DOM ya es legible y no es necesario seguir esperando imágenes y scripts de seguimiento para extraer los resultados de la búsqueda.
                view?.stopLoading()
                startPolling("page-finished")
            }

            @Deprecated("Deprecated in API 23")
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                // API antigua: solo falla en errores del marco principal,Errores de subrecurso ignorados
                Logger.w(TAG, "onReceivedError(legacy): code=$errorCode desc=$description url=$failingUrl")
                if (!resumed) {
                    finishOnce(ExtractOutcome(false, emptyList()))
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    Logger.w(TAG, "Error al cargar la página: code=${error?.errorCode} desc=${error?.description} url=${request.url}")
                }
                if (request?.isForMainFrame == true && !resumed) {
                    finishOnce(ExtractOutcome(false, emptyList()))
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                if (request?.isForMainFrame == true) {
                    Logger.w(TAG, "HTTP error: code=${errorResponse?.statusCode} reason=${errorResponse?.reasonPhrase} url=${request.url}")
                }
                if (request?.isForMainFrame == true && !resumed) {
                    finishOnce(ExtractOutcome(false, emptyList()))
                }
            }
        }

        cont.invokeOnCancellation {
            wv.stopLoading()
            wv.removeCallbacks(pollRunnable)
            wv.webViewClient = previousClient
        }

        Logger.i(TAG, "loadUrl: $url")
        // No depende de onPageStarted: no se devolverá la llamada a ningún evento de página cuando fallen algunas redes MuMu/WebView.
        // Pero evaluarJavascript aún puede leer una página de error o parte del DOM. Reserve una cita con el perro guardián ahora,
        // Extraiga activamente cuando se alcance el punto para evitar esperar un tiempo de espera total de 20 segundos.
        wv.postDelayed(watchdogRunnable, watchdogDelayMs)
        wv.loadUrl(url)
    }

    /**
     * Construir extracción de página JS,Analizando la estructura de los resultados de Bing en el escritorio:
     *  - elegir li.b_algo,Consigue el título de h2 a/enlace,.b_caption p Resumen de etc
     *  - Limpiar saltos de rastreo de bing /ck/a
     *  - Detectar código de verificación/Página de consentimiento
     * Devuelve una cadena JSON: {blocked: bool, results: [{title,url,snippet}]}
     */
    private fun buildExtractionScript(max: Int): String {
        // JS Ejecutar en el contexto de la página,Devolver cadena JSON(evaluateJavascript Estará codificado en JSON.)
        return """
        (function(){
          function textOf(el){ return (el && (el.innerText || el.textContent) || '').replace(/\s+/g,' ').trim(); }
          function firstText(root, selectors){
            for (var i=0;i<selectors.length;i++){
              var el = root.querySelector(selectors[i]);
              var t = textOf(el); if (t) return t;
            }
            return '';
          }
          function firstAnchor(root, selectors){
            for (var i=0;i<selectors.length;i++){
              var el = root.querySelector(selectors[i]);
              if (el && el.href) return el;
            }
            return null;
          }
          function cleanUrl(raw){
            if(!raw) return '';
            var url;
            try { url = new URL(raw, location.href); } catch(e){ return ''; }
            if (!/^https?:$/.test(url.protocol)) return '';
            return url.href;
          }
          function hasCaptcha(){
            var bodyText = textOf(document.body).toLowerCase();
            var href = (location.href || '').toLowerCase();
            return href.indexOf('/sorry/') >= 0 ||
              href.indexOf('captcha') >= 0 ||
              bodyText.indexOf('verify you are human') >= 0 ||
              bodyText.indexOf('detected unusual traffic') >= 0 ||
              bodyText.indexOf('please verify') >= 0;
          }
          function hasConsent(){
            var bodyText = textOf(document.body).toLowerCase();
            return bodyText.indexOf('consent') >= 0 && bodyText.indexOf('privacy') >= 0
              && document.querySelectorAll('li.b_algo').length === 0;
          }
          if (hasCaptcha() || hasConsent()) {
            return JSON.stringify({blocked:true, results:[]});
          }
          var items = Array.prototype.slice.call(document.querySelectorAll('li.b_algo'));
          var results = [];
          for (var i=0;i<items.length && results.length < $max;i++){
            var item = items[i];
            var cls = item.className || '';
            if (cls.indexOf('b_algo_default') >= 0 || cls.indexOf('b_card') >= 0 ||
                cls.indexOf('b_ans') >= 0 || cls.indexOf('b_ad') >= 0) continue;
            var anchor = firstAnchor(item, ['h2 a','a']);
            if(!anchor) continue;
            var title = textOf(anchor);
            var url = cleanUrl(anchor.href);
            if(!title || !url) continue;
            var snippet = firstText(item, [
              '.b_caption p','.b_algoSlug','.b_caption .b_paractl',
              '.b_lineclamp2','.b_lineclamp3','.b_lineclamp4',
              '.b_focusTextLarge','.b_caption','.b_dList'
            ]);
            if(snippet.length > 300) snippet = snippet.substring(0,300) + '…';
            results.push({title:title, url:url, snippet:snippet});
          }
          return JSON.stringify({blocked:false, results:results});
        })();
        """.trimIndent()
    }

    /** Analizar evaluar el valor de retorno de Javascript(Es una cadena codificada en JSON.). */
    private fun parseExtraction(raw: String?): ExtractOutcome {
        if (raw.isNullOrBlank() || raw == "null") {
            return ExtractOutcome(false, emptyList())
        }
        return try {
            // evaluateJavascript Devuelve un valor codificado en JSON,Las cadenas tendrán comillas externas.,Analizado dos veces aquí
            val outer = AppJsonLenient.parseToJsonElement(raw)
            val inner = if (outer is kotlinx.serialization.json.JsonPrimitive) {
                AppJsonLenient.parseToJsonElement(outer.content)
            } else {
                outer
            }
            val obj = inner as? kotlinx.serialization.json.JsonObject
                ?: return ExtractOutcome(false, emptyList())
            val blocked = (obj["blocked"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
            val arr = obj["results"] as? kotlinx.serialization.json.JsonArray
                ?: kotlinx.serialization.json.JsonArray(emptyList())
            val results = arr.mapNotNull { item ->
                val o = item as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val title = (o["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                val url = (o["url"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                val snippet = (o["snippet"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                if (title.isBlank() || url.isBlank()) {
                    null
                } else {
                    WebSearchResult(title = title, url = url, snippet = snippet, source = "Bing")
                }
            }
            ExtractOutcome(blocked, results)
        } catch (e: Exception) {
            Logger.w(TAG, "No se pudieron analizar los resultados de la extracción: ${e.message}; raw=${raw.take(200)}")
            ExtractOutcome(false, emptyList())
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "BingWebViewSearcher WebView Se debe acceder desde el hilo principal."
        }
        webViewRef?.let { return it }

        val wv = WebView(appContext.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.userAgentString = DESKTOP_UA
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.mediaPlaybackRequiresUserGesture = true
            // restricciones de seguridad
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false

            webViewClient = object : WebViewClient() {
                /** ROM compatibles:Destruido cuando el proceso de renderizado falla,La siguiente búsqueda se reconstruirá automáticamente. */
                override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                    Logger.e(TAG, "WebView El proceso de renderizado falla: ${detail?.didCrash()},Destruir y reconstruir")
                    webViewRef = null
                    view?.let { v ->
                        runCatching { (v.parent as? android.view.ViewGroup)?.removeView(v) }
                        runCatching { v.destroy() }
                    }
                    return true
                }
            }
        }
        // Configurar Bing cookie(Idioma/Búsqueda Segura/Número de resultados)
        runCatching {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            BING_COOKIE.split(";").forEach { c ->
                val trimmed = c.trim()
                if (trimmed.isNotEmpty()) {
                    cookieManager.setCookie("https://cn.bing.com", "$trimmed; domain=.bing.com; path=/")
                }
            }
            CookieManager.getInstance().flush()
        }.onFailure { Logger.w(TAG, "No se pudo configurar la cookie: ${it.message}") }

        webViewRef = wv
        Logger.i(TAG, "headless WebView Inicializado")
        return wv
    }

    /** Liberar recursos de WebView(Se puede cambiar en la configuración/Se llama cuando hay poca memoria.). */
    fun destroy() {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        webViewRef?.let { wv ->
            runCatching {
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.removeAllViews()
                (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                wv.destroy()
            }
        }
        webViewRef = null
    }

    private data class ExtractOutcome(val blocked: Boolean, val results: List<WebSearchResult>)

    // Instancia Json independiente(Ignorar claves desconocidas,análisis relajado)
    private val AppJsonLenient = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
}
