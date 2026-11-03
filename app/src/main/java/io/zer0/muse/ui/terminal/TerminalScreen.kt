package io.zer0.muse.ui.terminal

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.terminal.TerminalSessionManager
import io.zer0.muse.terminal.TerminalSessionStore
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseTopBarIconButton
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.ArrayDeque

private const val TAG = "TerminalScreen"

/** Interruptor de sonda de diagnóstico de página final:El misterio de la pantalla negra ha sido resuelto,Desactivado de forma predeterminada;Establezca verdadero para volver a habilitarlo al solucionar problemas. */
private const val TERMINAL_PROBES_ENABLED = false

/**
 * v2.x fase terminal uno:Página de terminal de zona de pruebas de la aplicación.
 *
 * - mostrar:xterm.js(assets/terminal)Ejecutando en WebView,ANSI Soporte a todo color y UTF-8;
 * - sesión:[TerminalSessionStore] Singleton a nivel de proceso,La sesión no se cerrará después de abandonar la página.,Vuelve y continúa;
 * - entrada:Línea de entrada de composición inferior(IME amigable),Sesión de escritura después del eco local stdin;
 * - Seguimiento:PTY Después de conectar el motor,Los programas interactivos y las señales Ctrl-C están disponibles directamente en esta página.,UI No se requieren cambios.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val session = remember { TerminalSessionStore.get(context) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var webReady by remember { mutableStateOf(false) }
    // La salida cuando no está lista se pone en cola primero,ready Después de un solo lavado(Acceso al hilo principal,Sin problemas de concurrencia)
    val pendingJs = remember { ArrayDeque<String>() }

    fun pushToTerminal(bytes: ByteArray) {
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val js = "window.MuseTerm && window.MuseTerm.writeB64('$b64');"
        Logger.d(TAG, "push ${bytes.size}B (webReady=$webReady, queued=${pendingJs.size})")
        mainHandler.post {
            val wv = webViewRef
            if (wv != null && webReady) {
                wv.evaluateJavascript(js, null)
            } else {
                pendingJs.addLast(js)
            }
        }
    }

    fun pushText(text: String) = pushToTerminal(text.toByteArray(Charsets.UTF_8))

    // sonda de depuración:Vuelva a leer el verdadero estado de xterm en WebView(Tamaño/rangos/personaje/escribe contar/JS error)
    fun runProbe(wv: WebView, tag: String) {
        val probe =
            "(function(){try{var t=window.__t;var q=document.querySelector('.xterm');" +
                "var cell=(t&&t._core&&t._core._renderService)?t._core._renderService.dimensions.css.cell:null;" +
                "var te=document.getElementById('term');" +
                "return 'rows='+(t?t.rows:'?')+' cols='+(t?t.cols:'?')+' ih='+window.innerHeight" +
                "+' iw='+window.innerWidth+' termH='+(te?te.offsetHeight:'?')+' xtermH='+(q?q.offsetHeight:'?')" +
                "+' cellW='+(cell?cell.width:'?')+' cellH='+(cell?cell.height:'?')" +
                "+' pos='+(te?getComputedStyle(te).position:'?')+' ch='+(te?getComputedStyle(te).height:'?')" +
                "+' writes='+(window.__writes||0)+' err='+(window.__lastErr||'none')}catch(e){return 'PROBE_ERR:'+e}})()"
        wv.evaluateJavascript(probe) { r -> Logger.i(TAG, "probe($tag): $r") }
    }

    DisposableEffect(Unit) {
        session.setListener(
            object : TerminalSessionManager.Listener {
                override fun onOutput(bytes: ByteArray) = pushToTerminal(bytes)

                override fun onExit(code: Int) {
                    pushText("\r\n" + context.getString(R.string.terminal_session_exited, code) + "\r\n")
                }
            },
        )
        // v2.2.1: ensureStarted Incluyendo el inicio del proceso./Archivo IO,Pasar a la ejecución del hilo IO(Eliminar la alarma del hilo principal StrictMode)
        val startJob = scope.launch(Dispatchers.IO) { session.ensureStarted() }
        onDispose {
            startJob.cancel()
            session.setListener(null)
            // retención de sesión(Singleton a nivel de proceso),Reciclar solo WebView,evitar fugas;Ingrese al búfer de recarga la próxima vez
            webViewRef?.destroy()
            webViewRef = null
        }
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        // ── barra superior ─────────────────────────────────────────────
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = MusePaddings.tightGap, vertical = MusePaddings.tightGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MuseTopBarIconButton(
                icon = MuseIcons.arrowLeft,
                contentDescription = stringResource(R.string.terminal_back_cd),
                onClick = onBack,
                tint = MaterialTheme.colorScheme.onSurface,
                solid = false,
            )
            Icon(
                imageVector = MuseIcons.terminal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(MusePaddings.contentGap))
            Text(
                text = stringResource(R.string.terminal_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            MuseTopBarIconButton(
                icon = MuseIcons.trash,
                contentDescription = stringResource(R.string.terminal_clear_cd),
                onClick = {
                    webViewRef?.evaluateJavascript("window.MuseTerm && window.MuseTerm.clearTerm();", null)
                },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                solid = false,
            )
            MuseTopBarIconButton(
                icon = MuseIcons.refresh,
                contentDescription = stringResource(R.string.terminal_restart_cd),
                onClick = {
                    session.restart()
                    pushText("\r\n" + context.getString(R.string.terminal_session_restarted) + "\r\n")
                },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                solid = false,
            )
            if (session.isPty) {
                MuseTopBarIconButton(
                    icon = MuseIcons.power,
                    contentDescription = stringResource(R.string.terminal_interrupt_cd),
                    onClick = { session.sendInterrupt() },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    solid = false,
                )
            }
        }

        // ── pantalla terminal(xterm.js) ───────────────────────────────
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = true
                    settings.domStorageEnabled = true
                    isVerticalScrollBarEnabled = false
                    setBackgroundColor(android.graphics.Color.parseColor("#101014"))
                    // primer diseño/Recalcular filas y columnas terminales cuando cambia el tamaño(fit Puede medir 0 altura mientras se carga,causa rows=1)
                    addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                        val changed =
                            (right - left) != (oldRight - oldLeft) ||
                                (bottom - top) != (oldBottom - oldTop)
                        if (changed) {
                            evaluateJavascript(
                                "window.MuseTerm && window.MuseTerm.refit && window.MuseTerm.refit();",
                                null,
                            )
                        }
                    }
                    webViewClient =
                        object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                Logger.i(TAG, "WebView Cargando completada: $url")
                            }

                            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                Logger.w(TAG, "WebView Error de carga: ${error?.errorCode} ${error?.description} ${request?.url}")
                            }
                        }
                    webChromeClient =
                        object : WebChromeClient() {
                            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                                Logger.w(
                                    TAG,
                                    "WebView console[${consoleMessage.messageLevel()}]: ${consoleMessage.message()} " +
                                        "@${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}",
                                )
                                return true
                            }
                        }
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun onReady() {
                                mainHandler.post {
                                    webReady = true
                                    Logger.i(TAG, "WebView onReady llegar:pending=${pendingJs.size}")
                                    val wv = webViewRef
                                    if (wv != null) {
                                        while (pendingJs.isNotEmpty()) {
                                            wv.evaluateJavascript(pendingJs.removeFirst(), null)
                                        }
                                        if (TERMINAL_PROBES_ENABLED) {
                                            runProbe(wv, "onReady")
                                            val after3 = context.getString(R.string.terminal_probe_after_seconds, 3)
                                            val after8 = context.getString(R.string.terminal_probe_after_seconds, 8)
                                            mainHandler.postDelayed({ webViewRef?.let { runProbe(it, after3) } }, 3000)
                                            mainHandler.postDelayed({ webViewRef?.let { runProbe(it, after8) } }, 8000)
                                        }
                                    }
                                }
                            }

                            @JavascriptInterface
                            fun onResize(rows: Int, cols: Int) {
                                session.resize(rows, cols)
                            }
                        },
                        "MuseBridge",
                    )
                    loadUrl("file:///android_asset/terminal/terminal.html?t=" + System.currentTimeMillis())
                }.also { webViewRef = it }
            },
            modifier =
            Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        // ── línea de entrada ───────────────────────────────────────────
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = MusePaddings.screen)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "❯",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(MusePaddings.contentGap))
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (input.isEmpty()) {
                        Text(
                            text = stringResource(R.string.terminal_input_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    inner()
                },
            )
            MuseTopBarIconButton(
                icon = MuseIcons.send,
                contentDescription = stringResource(R.string.terminal_send_cd),
                onClick = {
                    val cmd = input.trimEnd()
                    if (cmd.isNotBlank()) {
                        // PTY El propio terminal repite la entrada y solicita;Eco local del motor de tubería
                        if (!session.isPty) pushText("❯ $cmd\r\n")
                        session.write(cmd + "\n")
                    }
                    input = ""
                },
                tint = MaterialTheme.colorScheme.primary,
                solid = false,
            )
        }
    }
}
