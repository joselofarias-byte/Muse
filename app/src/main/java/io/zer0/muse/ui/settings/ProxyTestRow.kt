package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.ProxyConfig
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.theme.statusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * v2.6.9: Prueba de conexión de proxy.
 *
 * Por qué lo necesitas:
 * No hubo ningún comentario después de que se le asignara un agente antes. El usuario sólo puede volver a la conversación y "mandar un mensaje para intentarlo", y cuando falla
 * Al final, el agente falló,API Key No puedo decir si se trata de una intercepción incorrecta o ascendente.
 * Lo que es peor es que la configuración del proxy tiene la restricción de "requerir un reinicio para que surta efecto", lo que los usuarios del cliente anterior pueden detectar fácilmente.
 * Falso negativo y luego cambie un montón de configuraciones irrelevantes.
 *
 * Diseño:
 *  - usar**El valor en el formulario actual.**Ahora cree un cliente temporal para realizar pruebas, que no se ve afectado por la configuración guardada y las restricciones de reinicio.
 *  - La dirección de la sonda selecciona un punto final que es sensible al proxy y tiene una respuesta mínima (gstatic 204), no utilice ningún servicio que requiera autenticación
 *  - Distinga claramente entre "la conexión directa sin proxy tiene éxito pero el proxy falla", "tiempo de espera de conexión" y "falla de autenticación de proxy".
 */
@Composable
internal fun ProxyTestRow(proxyConfig: ProxyConfig) {
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<TestOutcome?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val localizedResources = androidx.compose.ui.platform.LocalResources.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MuseCapsuleButton(
                text = if (running) {
                    stringResource(R.string.proxy_test_running)
                } else {
                    stringResource(R.string.proxy_test_action)
                },
                onClick = {
                    if (!running) {
                        running = true
                        result = null
                        scope.launch {
                            result = withContext(Dispatchers.IO) {
                                runProbe(
                                    proxyConfig,
                                    ProbeTexts(
                                        invalidConfig = localizedResources.getString(R.string.proxy_test_invalid_config),
                                        connected = localizedResources.getString(R.string.proxy_test_connected),
                                        needAuth = localizedResources.getString(R.string.proxy_test_need_auth),
                                        reachableFailed = localizedResources.getString(R.string.proxy_test_reachable_failed),
                                        timeout = localizedResources.getString(R.string.proxy_test_timeout),
                                        dnsFailed = localizedResources.getString(R.string.proxy_test_dns_failed),
                                        connectFailed = localizedResources.getString(R.string.proxy_test_connect_failed),
                                        unexpected = localizedResources.getString(R.string.proxy_test_unexpected),
                                    ),
                                )
                            }
                            running = false
                        }
                    }
                },
                variant = IosCapsuleButtonVariant.Secondary,
                fillWidth = false,
            )
        }
        result?.let { outcome ->
            Text(
                text = outcome.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (outcome.ok) {
                    MaterialTheme.statusColors.success
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
            )
        }
    }
}

private data class TestOutcome(val ok: Boolean, val message: String)

/** v2.6.9: Recopilación de copywriting para detección. Utilice clases de datos para transportarlo y evitar la expansión del parámetro runProbe. */
private data class ProbeTexts(
    val invalidConfig: String,
    val connected: String,
    val needAuth: String,
    val reachableFailed: String,
    val timeout: String,
    val dnsFailed: String,
    val connectFailed: String,
    val unexpected: String,
)

/**
 * Punto final de la sonda:gstatic de generar_204.
 * Razones para elegirlo: se puede acceder a él desde cualquier red, el cuerpo de la respuesta está vacío, no se requiere autenticación y no se considerará tráfico abusivo.
 */
private const val TAG = "ProxyTestRow"

private const val PROBE_URL = "https://www.gstatic.com/generate_204"

private fun runProbe(config: ProxyConfig, texts: ProbeTexts): TestOutcome {
    if (!config.enabled || config.host.isBlank() || config.port !in 1..65535) {
        return TestOutcome(false, texts.invalidConfig)
    }
    return try {
        val builder = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
        // v2.6.9: Utilice directamente valores de formulario para ensamblar sin utilizar el ProxyProvider global.
        // La forma antigua de escribir es establecer temporalmente el estado global y luego restaurarlo. Durante la concurrencia, el cliente que envía la solicitud estará sesgado.——
        // El botón de prueba sólo debería afectar a su propia sonda.
        val address = java.net.InetSocketAddress.createUnresolved(config.host.trim(), config.port)
        val proxy = when (config.type.uppercase()) {
            "SOCKS", "SOCKS5" -> java.net.Proxy(java.net.Proxy.Type.SOCKS, address)
            else -> java.net.Proxy(java.net.Proxy.Type.HTTP, address)
        }
        builder.proxy(proxy)
        if (config.username.isNotBlank() && config.password.isNotBlank()) {
            val credential = okhttp3.Credentials.basic(config.username, config.password)
            builder.proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null) {
                    null
                } else {
                    response.request.newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build()
                }
            }
        }
        val client = builder.build()
        val started = System.currentTimeMillis()
        client.newCall(Request.Builder().url(PROBE_URL).get().build()).execute().use { resp ->
            val ms = System.currentTimeMillis() - started
            TestOutcome(
                ok = resp.code in 200..399,
                message = when {
                    resp.code in 200..399 -> "$texts.connected${ms}ms (HTTP ${resp.code})"
                    resp.code == 407 -> texts.needAuth
                    else -> "$texts.reachableFailed${resp.code}"
                },
            )
        }
    } catch (e: java.net.SocketTimeoutException) {
        Logger.w(TAG, "Tiempo de espera de la sonda del agente: ${e.message}")
        TestOutcome(false, "${texts.timeout} (${e.message ?: e::class.simpleName})")
    } catch (e: java.net.UnknownHostException) {
        Logger.w(TAG, "Error de DNS en la sonda de proxy: ${e.message}")
        TestOutcome(false, "${texts.dnsFailed} (${e.message ?: e::class.simpleName})")
    } catch (e: java.io.IOException) {
        Logger.w(TAG, "Error en la conexión de la sonda proxy: ${e.message}")
        TestOutcome(false, "${texts.connectFailed}${e.message ?: e::class.simpleName}")
    } catch (e: kotlinx.coroutines.CancellationException) {
        // v2.6.9(corrección de errores real): CancellationException La cadena de herencia de es
        //   IllegalStateException -> RuntimeException -> Exception
        // La frase original catch(RuntimeException) La cancelación de la rutina se considerará un error, lo que resultará en
        // La interfaz está cerrada pero el fondo aún se detecta y se pierde la semántica de cancelación. En el proyecto, M-OAI3 ha sido reemplazado por similar
        // El problema es reemplazar runCatching con resultOf, que debe volver a lanzarse explícitamente.
        // Nota:JVM La CancellationException de kotlinx y java.util.concurrent es la misma
        // tipo (typealias), por lo que sólo se necesita una rama.
        throw e
    } catch (e: IllegalArgumentException) {
        // v2.6.9: Capture solo tipos específicos. El punto donde se puede lanzar IllegalArgumentException en la sonda es
        // URI Construcción y análisis JSON (SerializationException es decir, sus subcategorías).
        Logger.w(TAG, "Excepción del parámetro de detección del agente: ${e.message}", e)
        TestOutcome(false, "${texts.unexpected}${e.message ?: e::class.simpleName}")
    }
}
