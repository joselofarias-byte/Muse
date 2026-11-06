package io.zer0.muse.tools

import io.zer0.muse.ui.SsrfGuard
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P2-33: WebView Puerta de decisión de la SSRF utilizada — Elimine completamente el "DNS sincronizado" del hilo de devolución de llamada.
 *
 * WebView de [android.webkit.WebViewClient] Las devoluciones de llamada se dividen en dos tipos de hilos.:
 *  - [android.webkit.WebViewClient.onPageStarted] / [android.webkit.WebViewClient.shouldOverrideUrlLoading]
 *    en**hilo principal**devolución de llamada,La devolución de llamada debe devolver el resultado del juicio de forma sincrónica. — Iniciar DNS aquí es la causa principal del ANR de la primera pantalla del nombre de dominio frío.;
 *  - [android.webkit.WebViewClient.shouldInterceptRequest] Devolución de llamada en el hilo de fondo de WebView,Se puede analizar sincrónicamente.
 *
 * Por lo tanto:
 *  - [isBlockedNow] Seguridad del hilo principal:solo consume [SsrfGuard] caché;señorita(anfitrión frío)Cuando se cierre por falla, intercepte inmediatamente,
 *    Y analiza y completa el caché en el hilo de fondo.([prewarm]);Opcional [onVerified] Después de analizar y confirmar la seguridad, regrese al hilo principal y vuelva a intentar el salto.,
 *    Por lo tanto, los nombres de dominio fríos no bloquearán permanentemente los redireccionamientos legales.,No bloqueará el hilo principal.;
 *  - [isBlockedBlocking] Sólo devoluciones de llamada que ya están en hilos en segundo plano.(shouldInterceptRequest)uso;
 *  - [isBlockedAsync] para suspender llamadas(navigate marco principal)Esperando resultados del análisis en segundo plano — El primer análisis también se escribe en la memoria caché.
 *
 * La semántica de seguridad permanece sin cambios:Los resultados del análisis son estrictamente consistentes con los resultados del juicio.,Nunca "libérelo primero y luego vuelva a analizarlo";Si no se puede confirmar, será rechazado.
 */
internal class WebViewSsrfGate(
    /** Alcance de la corrutina de calentamiento en segundo plano(producir:IO + SupervisorJob;Las pruebas se pueden inyectar en ámbitos controlados.). */
    private val prewarmScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** El hilo de devolución de llamada al que debe regresar el reintento de devolución de llamada(producir:hilo principal;prueba:Unconfined Conveniente para afirmaciones sincrónicas). */
    private val callbackDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val cachedVerdict: (String) -> Boolean? = SsrfGuard::cachedVerdictOrNull,
    private val resolveAsync: suspend (String) -> Boolean = SsrfGuard::isBlockedAsync,
    private val resolveBlocking: (String) -> Boolean = SsrfGuard::isBlocked,
) {

    /**
     * decisión pendiente:El análisis ocurre en [Dispatchers.IO](La primera llamada que llega al caché también se escribe aquí en el caché.).
     * suministro [BrowserManager.navigate] Espere la determinación antes de cargar el marco principal — El hilo principal solo consume los resultados.
     */
    suspend fun isBlockedAsync(url: String): Boolean = resolveAsync(url)

    /**
     * Decisión de sincronización segura del hilo principal:Solo consume caché,El DNS no se inicia.
     *
     * anfitrión frío(pérdida de caché)Juzgado conservadoramente como intercepción,Y calentarse de fondo;Si el análisis confirma que es seguro y proporciona [onVerified],
     * luego en [callbackDispatcher] Ejecutar acción de reintento en(solo una vez,Visitas de caché posteriores en el mismo host).
     *
     * @return true Indica que esta vez debería ser interceptado.
     */
    fun isBlockedNow(url: String, onVerified: (() -> Unit)? = null): Boolean {
        cachedVerdict(url)?.let { return it }
        prewarmScope.launch {
            if (!resolveAsync(url) && onVerified != null) {
                withContext(callbackDispatcher) { onVerified() }
            }
        }
        return true
    }

    /** Determinación de bloqueo de devolución de llamada de subproceso en segundo plano(shouldInterceptRequest);Los resultados del análisis se almacenan en caché para que el hilo principal los reutilice. */
    fun isBlockedBlocking(url: String): Boolean = resolveBlocking(url)
}
