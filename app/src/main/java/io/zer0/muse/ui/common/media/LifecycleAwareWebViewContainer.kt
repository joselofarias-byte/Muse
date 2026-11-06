package io.zer0.muse.ui.common.media

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView

/**
 * v2.6.6: mantener“último cargado HTML”recipiente pequeño.
 *
 * El propósito de utilizar una capa de paquete de clases es permitir `remember { ... }` Capacidad para preservar el estado mutable en todas las reorganizaciones.
 * (El recuerdo directo de un valor de cadena se restablecerá mediante cambios de parámetros durante la reorganización y no se podrá utilizar“Ya sea para cambiar”sentencia).
 */
private class LastLoadedHtml {
    var value: String? = null
}

/**
 * Contenedor componible WebView compatible con el ciclo de vida.
 *
 * v1.88 Correcciones de limitaciones conocidas: Usar directamente en proyectos alternativos. `AndroidView { WebView(it) }` modo,
 * unificado como [LifecycleAwareWebView],Procesamiento automático:
 *
 *  - crear [LifecycleAwareWebView] y regístrate para [LocalLifecycleOwner];
 *  - ON_PAUSE / ON_RESUME Pausa automáticamente cuando/Restaurar temporizadores y animaciones JS(pausa [WebView.pauseTimers] /
 *    [WebView.onPause] / restaurar [WebView.resumeTimers] / [WebView.onResume]);
 *  - ON_DESTROY Destruya WebView y cancele el registro del observador cuando;
 *  - Composable Al salir del grupo(Escenas donde el anfitrión no es destruido.)Ten todo en cuenta destroy + removeObserver,Evite fugas.
 *
 * La persona que llama solo debe prestar atención a la configuración empresarial.:
 *  - [htmlContent] + [baseUrl]:llamada automática [WebView.loadDataWithBaseURL] Cargar;
 *  - [javaScriptEnabled] / [domStorageEnabled]:Interruptores de configuración comunes;
 *  - [webViewClient]:Interceptación de navegación personalizada(No interceptado por defecto);
 *  - [onWebViewCreated]:Después de la creación, realice configuraciones adicionales o personalice la lógica de carga;
 *  - [onPageFinished]:Devolución de llamada cuando se completa la carga de la página.
 *
 * valor predeterminado seguro(Configuración común que cubre todos los puntos de uso de WebView en el proyecto original.):
 *  - Deshabilitar archivos/Acceso al contenido(Evitar lecturas entre dominios file://);
 *  - Requiere gestos del usuario para reproducir medios;
 *  - fondo transparente(puede ser [transparentBackground] cerrar).
 *
 * @param modifier Compose modificador
 * @param htmlContent Contenido HTML a cargar;No cargar automáticamente cuando sea nulo(por la persona que llama en [onWebViewCreated] cargando en)
 * @param baseUrl Al cargar HTML base URL;`file:///android_asset/` Se utiliza para cargar recursos de activos locales.,
 *                null significa ninguno base URL
 * @param mimeType tipo de contenido,Predeterminado text/html
 * @param encoding codificación,Predeterminado UTF-8
 * @param historyUrl Historia URL,normalmente null
 * @param javaScriptEnabled Ya sea para habilitar JavaScript,Predeterminado false(SVG/HTML Tarjeta inhabilitada,fórmula/Gráfico habilitado)
 * @param domStorageEnabled Ya sea para habilitar DOM storage,Predeterminado false
 * @param transparentBackground Si hacer que el fondo sea transparente,Predeterminado true
 * @param webViewClient Personalizar WebViewClient;Si no se aprueba, se utilizará el valor predeterminado. WebViewClient(No interceptar la navegación)
 * @param onWebViewCreated WebView Devolución de llamada después de la creación,La persona que llama puede realizar configuraciones adicionales o cargas personalizadas aquí
 * @param onWebViewReleased WebView Devolución de llamada antes de la destrucción(Correspondiente a AndroidView onRelease),
 *        La persona que llama puede limpiar la referencia a WebView aquí,Evite retener instancias destruidas
 * @param onPageFinished Devolución de llamada completa de carga de página
 */
// v2.6.7: Hay 13 parámetros. Este es el punto de empaquetado unificado de WebView y los parámetros son nativos. WebSettings /
// WebViewClient espejo; la división en objetos de configuración hará que se reescriban los más de 20 puntos de llamada.
// Los rendimientos no coinciden con los riesgos. Aquí se afirma que el diseño es así (mismo calibre que ToolRegistrarBootstrapper).
@Suppress("LongParameterList")
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LifecycleAwareWebViewContainer(
    modifier: Modifier = Modifier,
    htmlContent: String? = null,
    baseUrl: String? = "file:///android_asset/",
    mimeType: String = "text/html",
    encoding: String = "UTF-8",
    historyUrl: String? = null,
    javaScriptEnabled: Boolean = false,
    domStorageEnabled: Boolean = false,
    transparentBackground: Boolean = true,
    webViewClient: WebViewClient? = null,
    onWebViewCreated: ((WebView) -> Unit)? = null,
    onWebViewReleased: ((WebView) -> Unit)? = null,
    onPageFinished: ((WebView, String) -> Unit)? = null,
    /**
     * v2.6.7: Si se debe permitir que WebView se desplace por sí solo.
     *
     * Las tarjetas de chat en línea siempre son falsas: la altura está determinada por el contenido y el desplazamiento interno
     * Aparece doble desplazamiento (uno en la tarjeta + otro en la lista de chat) con barras de desplazamiento redundantes.
     * Pase verdadero para escenas que realmente requieren desplazamiento, como la vista previa en pantalla completa.
     */
    scrollable: Boolean = false,
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    // v2.6.6: Recuerde el último HTML cargado para evitar repetir loadDataWithBaseURL cuando el contenido no haya cambiado.
    // Recuerde debe usarse para conservarlo en todas las reorganizaciones; de lo contrario, se restablecerá cada vez que se reorganice. → Vuelve a recargar cada cuadro.
    val lastLoadedHtmlRef = remember { LastLoadedHtml() }

    // Analizar el final usado WebViewClient:
    //  - Si lo proporciona la persona que llama, úselo directamente.;
    //  - De lo contrario si es necesario onPageFinished,Envolver una devolución de llamada de reenvío de cliente predeterminada;
    //  - De lo contrario, utilice el WebViewClient más simple.
    val effectiveClient = remember(webViewClient, onPageFinished) {
        when {
            webViewClient != null -> webViewClient
            onPageFinished != null -> object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    onPageFinished.invoke(view, url)
                }
            }
            else -> WebViewClient()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // Cree LifecycleAwareWebView a través de fábrica y vincúlelo automáticamente al actual LifecycleOwner
            LifecycleAwareWebViewFactory.create(ctx, lifecycleOwner).apply {
                settings.javaScriptEnabled = javaScriptEnabled
                settings.domStorageEnabled = domStorageEnabled
                // valor predeterminado seguro:Deshabilitar archivos/Acceso al contenido,Requiere gestos del usuario para reproducir medios
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mediaPlaybackRequiresUserGesture = true
                // v2.6.6 Refuerzo de seguridad(Camino común para todas las tarjetas.):
                //  - Archivos prohibidos/Lectura de URL de contenido entre dominios(También se han desactivado los escenarios que contienen file:// para acceder a los activos.);
                //  - Deshabilitar ventanas emergentes JS(window.alert/confirm/prompt)—— Se puede abusar de las tarjetas producidas con modelos;
                //  - Se prohíbe la navegación por contenidos mixtos y protocolos externos.;
                //  - Ubicación geográfica prohibida/Capacidades no esenciales como bases de datos.
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setGeolocationEnabled(false)
                settings.databaseEnabled = false
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                // v2.6.7: El desplazamiento y las barras de desplazamiento de WebView están deshabilitados de forma predeterminada.
                //
                // La altura de la tarjeta ha sido determinada por el contenido (la capa exterior establece la altura a la altura del contenido real),
                // Si aún se puede desplazar el WebView, aparecerá“Otra ventana desplazable en la tarjeta.”:
                // No sólo tiene una barra de desplazamiento vertical adicional, sino que también consume el gesto de deslizamiento de la lista de mensajes exterior.
                // Las escenas que requieren desplazamiento (vista previa en pantalla completa) se pasan explícitamente scrollable = true.
                if (!scrollable) {
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = android.view.View.OVER_SCROLL_NEVER
                    isNestedScrollingEnabled = false
                }
                if (transparentBackground) {
                    setBackgroundColor(Color.TRANSPARENT)
                }
                this.webViewClient = effectiveClient
                // Configuración personalizada de la persona que llama(Configuraciones personalizadas/lógica de carga personalizada, etc.)
                onWebViewCreated?.invoke(this)
                // Cargar contenido HTML(htmlContent Cuando es nulo, lo carga la persona que llama en onWebViewCreated)
                if (htmlContent != null) {
                    lastLoadedHtmlRef.value = htmlContent
                    loadDataWithBaseURL(baseUrl, htmlContent, mimeType, encoding, historyUrl)
                }
            }
        },
        update = { webView ->
            // v2.6.6: No recargar cuando el contenido permanece sin cambios —— Cada vez que se reorganiza la lógica original. loadDataWithBaseURL,
            // La transmisión de renderizado progresivo hará que cada token recargue todo el WebView(atascado).
            // Actualice solo cuando el HTML realmente cambie.
            if (htmlContent != null && htmlContent != lastLoadedHtmlRef.value) {
                lastLoadedHtmlRef.value = htmlContent
                webView.loadDataWithBaseURL(baseUrl, htmlContent, mimeType, encoding, historyUrl)
            }
        },
        onRelease = { webview ->
            // Composable Llamado al salir de una combinación.(Escenas donde el anfitrión no es destruido.,Como el cambio de renderizado condicional):
            //  - Notificar a la persona que llama para que limpie la referencia a WebView(antes de destruir);
            //  - Dar de baja al observador del ciclo de vida,Evite la posterior destrucción repetida de ON_DESTROY de instancias ya destruidas;
            //  - Destruir completamente libera recursos nativos.
            onWebViewReleased?.invoke(webview)
            if (webview is LifecycleAwareWebView) {
                lifecycleOwner.lifecycle.removeObserver(webview)
            }
            webview.destroy()
        },
    )
}
