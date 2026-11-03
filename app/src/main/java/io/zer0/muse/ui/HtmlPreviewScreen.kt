package io.zer0.muse.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.LifecycleAwareWebView
import io.zer0.muse.ui.common.media.LifecycleAwareWebViewFactory
import io.zer0.muse.ui.common.navigation.MuseTopBar

/**
 * HTML Página de vista previa en pantalla completa — Represente el código HTML/SVG de la salida de LLM en un WebView.
 *
 * Implementación de vista previa de pantalla completa basada en WebView:
 *  - Título superior de MuseTopBar"HTML Vista previa" + botón de retroceso + "Abrir en el navegador" + "Copiar código fuente"
 *  - Carga intermedia de WebView html(loadDataWithBaseURL)
 *  - Habilite JavaScript con DOM storage,Zoom de soporte
 *  - Configure WebViewClient para evitar que salten enlaces externos(Solo lanzamiento about:blank)
 *
 * Instrucciones de seguridad:
 *  - HTML Contenido de la salida de LLM,En teoría puede contener scripts maliciosos.;Esto sólo se representa en WebView local.,
 *    No expone ninguna interfaz de host(addJavascriptInterface),E interceptar la navegación externa.;
 *  - Reutilizar [RichContentWebViewClient] estrategia de interceptación(solo lanzamiento about:blank),
 *    evitar clics <a> Salir de la página de vista previa;
 *  - A diferencia de RichContentCard,Esta página está habilitada. JS(El usuario hace clic activamente"Vista previa"Recién ingresado,
 *    Adecuado para demostración HTML interactiva/plantilla de correo electrónico/animación SVG).
 *
 * @param html Código fuente HTML a renderizar(SVG ha sido envuelto como completo por la persona que llama HTML)
 * @param onBack devolución de llamada
 */
@Composable
fun HtmlPreviewScreen(
    html: String,
    onBack: () -> Unit,
    /** v2.0.1: Título opcional (cuando se pasa el título del producto, se mostrará en la barra superior; si está vacío, volverá a \"HTML Vista previa\"). */
    title: String? = null,
) {
    val context = LocalContext.current
    // Almacenamiento en caché de referencias de WebView,DisposableEffect liberación media,evitar fugas
    val webViewRef = remember { arrayOfNulls<WebView>(1) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // v1.88 Reparación: WebView Ha logrado la conciencia del ciclo de vida.(LifecycleAwareWebView),
    // ON_PAUSE/ON_RESUME/ON_DESTROY Manejado automáticamente internamente por WebView(Contiene pauseTimers/resumeTimers).
    // Aquí solo se conservan la lógica de destrucción ascendente y de limpieza del almacenamiento DOM al salir de la combinación.
    DisposableEffect(lifecycleOwner) {
        onDispose {
            // Liberación desde abajo:Destruido al salir de la combinación. WebView(Escenas donde el anfitrión no es destruido.,Si la navegación regresa)
            webViewRef[0]?.also {
                // Dar de baja al observador del ciclo de vida,Evite la posterior destrucción repetida de ON_DESTROY de instancias ya destruidas
                if (it is LifecycleAwareWebView) {
                    lifecycleOwner.lifecycle.removeObserver(it)
                }
                it.destroy()
                webViewRef[0] = null
            }
            // limpiar DOM storage,Evite los residuos de acumulación de demostraciones SVG/HTML
            WebStorage.getInstance().deleteAllData()
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            MuseTopBar(
                title = title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.html_preview_title),
                onBack = onBack,
                actions = {
                    // v2.0.1: Actualizar (misma operación que el navegador)
                    MuseTactileButton(
                        icon = MuseIcons.refresh,
                        contentDescription = stringResource(R.string.browser_refresh),
                        onClick = { runCatching { webViewRef[0]?.reload() } },
                    )
                    // Abrir en el navegador:Codificar HTML como data URL,Utilice ACTION_VIEW para entregarlo al navegador del sistema.
                    MuseTactileButton(
                        icon = MuseIcons.browser,
                        contentDescription = stringResource(R.string.html_preview_open_in_browser_cd),
                        onClick = { openInExternalBrowser(context, html) },
                    )
                    // v1.0.47 P8-2: Descargar producto — Guardar en el directorio de Descargas
                    MuseTactileButton(
                        icon = MuseIcons.download,
                        contentDescription = stringResource(R.string.html_preview_download_cd),
                        onClick = { downloadHtml(context, html) },
                    )
                    // Copiar código fuente:Escribir en el portapapeles + brindar comentarios
                    MuseTactileButton(
                        icon = MuseIcons.copy,
                        contentDescription = stringResource(R.string.html_preview_copy_source_cd),
                        onClick = { copyHtmlToClipboard(context, html) },
                    )
                },
            )
            // WebView recipiente:Ocupar el espacio restante,Establece el color de fondo para evitar el destello blanco
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                AndroidView(
                    factory = { ctx ->
                        // v1.88 Reparación: Usar en su lugar LifecycleAwareWebViewFactory.create,
                        // vinculado automáticamente a lifecycleOwner,Pausar el temporizador JS en segundo plano/animación.
                        LifecycleAwareWebViewFactory.create(ctx, lifecycleOwner).apply {
                            // habilitar JavaScript(HTML demo / Las plantillas interactivas requieren)
                            settings.javaScriptEnabled = true
                            // habilitar DOM storage(localStorage / sessionStorage)
                            settings.domStorageEnabled = true
                            // Habilitar zoom(Controles de zoom integrados + dos dedos)
                            settings.setSupportZoom(true)
                            settings.builtInZoomControls = true
                            // Ocultar botón de zoom(Mantenga los gestos de pellizcar para hacer zoom),Evite las distracciones de la interfaz de usuario
                            settings.displayZoomControls = false
                            // Permitir que las metaetiquetas de la ventana gráfica surtan efecto(Responsivo HTML)
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            // seguro:Deshabilitar archivos/Acceso al contenido,Evitar lecturas entre dominios file://
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            // Requiere gestos del usuario para reproducir medios
                            settings.mediaPlaybackRequiresUserGesture = true
                            // color de fondo transparente,Integrar con Compose Surface
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            // Interceptar toda la navegación externa(solo lanzamiento about:blank / data:),prevenir <a> saltar
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val url = request?.url ?: return true
                                    val scheme = url.scheme?.lowercase() ?: return true
                                    // Sólo sobre: y datos: se permiten protocolos,Todos los demás serán interceptados.
                                    return scheme != "about" && scheme != "data"
                                }
                            }
                            webViewRef[0] = this
                            // Cargar con loadDataWithBaseURL,base URL Evite la anulación de ruta relativa para about:blank
                            loadDataWithBaseURL(
                                "about:blank",
                                html,
                                "text/html",
                                "UTF-8",
                                null,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * Codifique el código fuente HTML en data URL,Utilice ACTION_VIEW para abrirlo en el navegador del sistema.
 *
 * usar [android.util.Base64] Codificación para evitar caracteres especiales que rompan el análisis de URL de datos;
 * Algunos navegadores tienen restricciones de longitud de datos: URL(Chrome ~2MB),Se reduce a un mensaje Toast si es demasiado largo.
 */
private fun openInExternalBrowser(context: Context, html: String) {
    runCatching {
        val encoded = android.util.Base64.encodeToString(
            html.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )
        val dataUrl = "data:text/html;charset=UTF-8;base64,$encoded"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(dataUrl))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.onFailure { e ->
        MuseToast.show(
            context.getString(R.string.html_preview_open_in_browser_failed, e.message ?: ""),
        )
    }
}

/**
 * Copie el código fuente HTML al portapapeles,Toast comentario.
 */
private fun copyHtmlToClipboard(context: Context, html: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("html", html))
    MuseToast.show(context.getString(R.string.html_preview_source_copied))
}

/**
 * v1.0.47 P8-2: Descargue el HTML en el directorio de Descargas.
 *
 * Android 10+(API 29+)usar MediaStore.Downloads,Agregado automáticamente a la lista de descargas del sistema;
 * Las versiones anteriores escriben directamente en el directorio público Environment.DIRECTORY_DOWNLOADS.
 *
 * formato de nombre de archivo:muse_artifact_{Marca de tiempo}.html
 */
private fun downloadHtml(context: Context, html: String) {
    val fileName = "muse_artifact_${System.currentTimeMillis()}.html"
    runCatching {
        val bytes = html.toByteArray(Charsets.UTF_8)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+: MediaStore.Downloads
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/html")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore Error de inserción")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("No se puede abrir el flujo de salida")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            // Android 9 y abajo:Escribe directamente en el directorio público de Descargas.
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            java.io.File(dir, fileName).writeBytes(bytes)
        }
        MuseToast.show(context.getString(R.string.html_preview_download_success, fileName))
    }.onFailure { e ->
        MuseToast.show(context.getString(R.string.html_preview_download_failed, e.message ?: ""))
    }
}
