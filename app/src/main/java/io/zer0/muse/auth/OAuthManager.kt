package io.zer0.muse.auth

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Base64
import io.zer0.ai.core.OAuthConfig
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.web.applyProxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * P1-6: OAuth Administrador de inicio de sesión — Para proveedores que admiten OAuth(Como por ejemplo xAI)Obtenga automáticamente apiKey.
 *
 * Soporta dos procesos:
 *  1. **Device Flow**(RFC 8628):[OAuthConfig.deviceCodeUrl] Este proceso tendrá prioridad cuando no esté vacío.
 *     POST deviceCodeUrl tomar device_code + user_code + verification_uri,
 *     Pase [stateFlow] (AWAITING_USER) Inserte el código de usuario en la ventana emergente de la interfaz de usuario,
 *     Luego sondee tokenUrl hasta obtener access_token.
 *  2. **Authorization Code Flow + PKCE**:[OAuthConfig.deviceCodeUrl] Siga este proceso cuando esté vacío.
 *     Utilice Intent.ACTION_VIEW para abrir el navegador del sistema para [OAuthConfig.authorizeUrl],
 *     Pase Deep Link(`io.zer0.muse://oauth/callback`)recibir code,
 *     por [OAuthCallbackActivity] llamar [completeAuthorizationCodeFlow] devolución de llamada de finalización,
 *     Finalmente, use código + code_verifier para reemplazar access_token.
 *
 * máquina de estados(IDLE → AWAITING_USER/POLLING → SUCCESS/ERROR → IDLE)Pase [stateFlow] expuesto,
 * UI Se puede observar que genera ventanas emergentes/barras de progreso/mensajes de error.
 *
 * Modelo de roscado:E/S de red en Dispatchers.IO;Estado actualizado en Main(Simultaneidad resuelta por StateFlow).
 *
 * P2-11: Aislamiento de credenciales — inyectar [SecureCredentialStore] después,Automáticamente después de iniciar sesión correctamente TokenBundle
 * (access_token + refresh_token + expiresAt + scope)Escritura cifrada independiente SP(Aislado físicamente de las claves API ordinarias),
 * y proporcionar [getStoredToken] / [refreshTokenIfNeeded] Deje que la interfaz de usuario se reutilice directamente cuando el token no haya caducado.
 * Actualizar automáticamente cuando caduque,No es necesario que los usuarios vuelvan a iniciar sesión cada vez.
 */
object OAuthManager {

    private const val TAG = "OAuthManager"

    /** Codex El flujo de código del dispositivo utiliza el cuerpo JSON. */
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /** P2-11: Almacenamiento seguro de credenciales(por [init] inyectar,Saltar la persistencia cuando no se inyecta). */
    @Volatile
    private var secureStore: SecureCredentialStore? = null

    /**
     * P2-11: inyectar [SecureCredentialStore],MuseApp.onCreate lo llama una vez después de startKoin.
     *
     * Llamado antes de la inyección. launch* / getStoredToken / refreshTokenIfNeeded Funcionará en modo "sin persistencia"
     * (Iniciar sesión correctamente pero no realizar el pedido,Devuelve nulo/falla al actualizar),No chocará — Conveniente para pruebas unitarias y escenarios de inicio temprano.
     */
    fun init(secureStore: SecureCredentialStore) {
        this.secureStore = secureStore
    }

    /**
     * v2.6.2: Expone el almacén de credenciales inyectadas (nulo si no se inyecta).
     *
     * para iniciar la autocuración (Codex Escritura complementaria del encabezado de la cuenta) lee el id_token del usuario que inició sesión;
     * Las personas comunes y corrientes todavía van [getStoredToken] / [refreshTokenIfNeeded] El camino encapsulado.
     */
    fun secureStoreOrNull(): SecureCredentialStore? = secureStore

    /** P2-11: providerId → OAuthConfig caché,suministro [refreshTokenIfNeeded] Consulta tokenUrl/clientId, etc. */
    private val providerConfigs = mutableMapOf<String, OAuthConfig>()

    /** Prevent concurrent refresh-token rotation for the same provider. */
    private val refreshLocks = ConcurrentHashMap<String, Mutex>()

    /** token Venciendo la ventana de actualización anticipada(segundos),Evite los 401 dentro de períodos de tiempo acotados. */
    private const val REFRESH_BUFFER_SECONDS = 60L
    private const val AUTH_CODE_FLOW_TIMEOUT_MS = 5 * 60 * 1000L

    /** Codex Tiempo de espera del proceso del código del dispositivo(Aproximadamente 15 minutos según la CLI oficial). */
    private const val CODEX_DEVICE_TIMEOUT_MS = 15 * 60 * 1000L

    /** OAuth Estado actual del proceso,UI Pase [stateFlow] Observar. */
    sealed class State {
        /** gratis,No hay ningún flujo de OAuth en curso. */
        object IDLE : State()

        /**
         * Espere la acción del usuario.
         * - Device Flow:llevar [userCode] + [verificationUri],UI Se debe mostrar una ventana emergente al usuario.
         * - Auth Code Flow:[userCode] esta vacio,UI Debería indicar "El navegador está abierto".,Por favor complete la autorización."
         */
        @Suppress("ktlint:standard:class-naming")
        data class AWAITING_USER(val userCode: String = "", val verificationUri: String = "") : State()

        /** Device Flow Sondeando el punto final del token. */
        object POLLING : State()

        /** éxito,[apiKey] ¿Se intercambia el token de acceso? */
        data class SUCCESS(val apiKey: String, val idToken: String? = null) : State()

        /** falló,[message] para la descripción del error. */
        data class ERROR(val message: String) : State()
    }

    private val _stateFlow = MutableStateFlow<State>(State.IDLE)

    /** flujo de estado,UI Mire para generar ventanas emergentes/Progreso/Mensaje de error. */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /**
     * v2.6.0: Proveedor de configuración de proxy global.
     *
     * OAuthManager para object,No se puede inyectar Koin;Ensamblado por AppKoinModule al inicio
     * Proveedor para leer SettingsRepository.proxyConfigFlow —— token Intercambio/La actualización debe realizarse con
     * Coherencia global del agente,De lo contrario, el usuario proxy“La autorización del navegador es exitosa pero el intercambio de tokens de la aplicación falla”
     * (La región rechazó la conexión directa a auth.openai.com).
     */
    @Volatile
    private var proxyConfigProvider: (() -> io.zer0.muse.data.ProxyConfig)? = null

    fun installProxyConfigProvider(provider: () -> io.zer0.muse.data.ProxyConfig) {
        proxyConfigProvider = provider
    }

    /**
     * token para intercambio OkHttp client(breve tiempo de espera,OAuth Los procesos no deben bloquearse durante largos períodos de tiempo.).
     *
     * v2.6.0: Reconstruya en cada llamada para reflejar la configuración de proxy más reciente —— Los usuarios pueden modificar el agente después del inicio.,
     * El singleton perezoso reemplazará al primero.(puede estar vacío)La configuración del proxy es fija.OAuth Es operación de baja frecuencia.,
     * Los costos de reconstrucción son insignificantes.
     */
    private fun buildHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
        proxyConfigProvider?.invoke()?.let { cfg -> builder.applyProxy(cfg) }
        return builder.build()
    }

    /** Actualmente en progreso Auth Code Flow(usado para [completeAuthorizationCodeFlow] devolución de llamada de finalización). */
    private val pendingAuthCodeFlow = MutableStateFlow<PendingAuthCode?>(null)
    private val authCodeFlowLock = Any()

    /** Auth Code Flow contexto interno(state + code_verifier + señal de finalización). */
    internal data class PendingAuthCode(
        val state: String,
        val codeVerifier: String,
        val deferred: CompletableDeferred<String>,
    )

    /** Device Flow La respuesta del código_dispositivo(RFC 8628 §3.2). */
    @Serializable
    private data class DeviceCodeResponse(
        val device_code: String = "",
        val user_code: String = "",
        val verification_uri: String = "",
        val verification_uri_complete: String? = null,
        val expires_in: Long = 1800L,
        val interval: Long = 5L,
        val error: String? = null,
        val error_description: String? = null,
    )

    /** Token respuesta del punto final(Estándar OAuth 2.0 token response). */
    @Serializable
    @Suppress("FunctionParameterNaming")
    private data class TokenResponse(
        val access_token: String = "",
        val token_type: String = "Bearer",
        val expires_in: Long = 0L,
        val refresh_token: String? = null,
        val scope: String? = null,
        // v2.x(Codex): OpenID Connect El id_token contiene chatgpt_account_id y otros reclamos (requeridos en el encabezado de la solicitud).
        @SerialName("id_token")
        val idToken: String? = null,
        val error: String? = null,
        val error_description: String? = null,
    )

    /**
     * empezar Device Flow(RFC 8628).
     *
     * pasos:
     *  1. POST [OAuthConfig.deviceCodeUrl] tomar device_code + user_code + verification_uri
     *  2. Pase [stateFlow] empujar AWAITING_USER(userCode, verificationUri),UI Visualización de ventana emergente
     *  3. Encuesta por intervalo [OAuthConfig.tokenUrl],Hasta que obtengas access_token o timeout/falló
     *  4. empujar SUCCESS(apiKey),Regresar Result.success(access_token)
     *
     * presionar sobre el fracaso ERROR(message),Devolver resultado.fallo.
     *
     * P2-11: si [providerId] No vacío,Después de iniciar sesión correctamente, el TokenBundle se cifrará y escribirá automáticamente. [secureStore],
     *        suministro [getStoredToken] / [refreshTokenIfNeeded] Reutilización posterior.
     *
     * @param config OAuth Configuración(debe contener deviceCodeUrl,De lo contrario, no se devolverá)
     * @param providerId P2-11: proveedor ID(Una cadena vacía significa que no hay persistencia,Mantenga compatibles las llamadas tradicionales)
     * @return cuando tiene éxito Result.success(access_token);en el fracaso Result.failure(anormal)
     */
    suspend fun launchDeviceFlow(config: OAuthConfig, providerId: String = ""): Result<String> {
        // v2.6.0: Codex Proceso de código de dispositivo especial(no estándar RFC 8628)—— Implementar de forma independiente.
        if (config.codexDeviceAuth) {
            return launchCodexDeviceAuth(config, providerId)
        }
        val deviceCodeUrl = config.deviceCodeUrl
        if (deviceCodeUrl.isNullOrBlank()) {
            val msg = "Device Flow No disponible:deviceCodeUrl esta vacio"
            _stateFlow.value = State.ERROR(msg)
            return Result.failure(IllegalStateException(msg))
        }
        if (config.clientId.isBlank()) {
            val msg = "Device Flow No disponible:clientId esta vacio"
            _stateFlow.value = State.ERROR(msg)
            return Result.failure(IllegalStateException(msg))
        }
        // P2-11: Configuración de caché para la consulta de actualizaciónTokenIfNeeded
        if (providerId.isNotBlank()) {
            synchronized(providerConfigs) { providerConfigs[providerId] = config }
        }

        // 1. Solicitar device_code
        val deviceResp: Result<DeviceCodeResponse> = withContext(Dispatchers.IO) {
            try {
                val form = FormBody.Builder()
                    .add("client_id", config.clientId)
                    .apply { if (config.scope.isNotBlank()) add("scope", config.scope) }
                    .build()
                val request = Request.Builder()
                    .url(deviceCodeUrl)
                    // v2.6.0: GitHub El punto final del código del dispositivo regresa de forma predeterminada. form-urlencoded,
                    // Debe traer Accept: application/json antes de regresar JSON(En caso contrario, el informe de análisis
                    // "Expected start of the object '{', but had 'd'").
                    .header("Accept", "application/json")
                    .post(form)
                    .build()
                val response = buildHttpClient().newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        error("device_code Solicitud fallida: HTTP ${resp.code}${oauthErrorField(body)}")
                    }
                    io.zer0.common.AppJson.decodeFromString(
                        DeviceCodeResponse.serializer(),
                        resp.body?.string().orEmpty(),
                    )
                }
                Result.success(response)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure<DeviceCodeResponse>(error)
            }
        }
        if (deviceResp.isFailure) {
            val msg = "device_code Solicitar excepción: ${deviceResp.exceptionOrNull()?.message ?: "desconocido"}"
            Logger.w(TAG, msg)
            _stateFlow.value = State.ERROR(msg)
            return Result.failure(deviceResp.exceptionOrNull() ?: IllegalStateException(msg))
        }
        val dc = deviceResp.getOrThrow()
        if (dc.device_code.isBlank() || dc.user_code.isBlank()) {
            val msg = "device_code Faltan campos de respuesta: device_code/user_code esta vacio"
            Logger.w(TAG, msg)
            _stateFlow.value = State.ERROR(msg)
            return Result.failure(IllegalStateException(msg))
        }

        // 2. empujar AWAITING_USER,UI Visualización de ventana emergente user_code
        _stateFlow.value = State.AWAITING_USER(
            userCode = dc.user_code,
            verificationUri = dc.verification_uri_complete ?: dc.verification_uri,
        )

        // 3. Sondear el punto final del token
        _stateFlow.value = State.POLLING
        val intervalMs = (dc.interval.coerceAtLeast(1) * 1000L)
        val deadline = System.currentTimeMillis() + dc.expires_in.coerceAtLeast(1) * 1000L
        var currentInterval = intervalMs
        while (System.currentTimeMillis() < deadline) {
            delay(currentInterval)
            val tokenResp = pollToken(config, dc.device_code)
            // conseguir access_token
            if (tokenResp.access_token.isNotBlank() && tokenResp.error == null) {
                // P2-11: persistencia TokenBundle(refresh_token/expiresAt/scope almacenar juntos,
                // Para uso posterior de refrescoTokenIfNeeded)
                if (providerId.isNotBlank()) {
                    persistTokenBundle(providerId, tokenResp)
                }
                _stateFlow.value = State.SUCCESS(tokenResp.access_token, tokenResp.idToken)
                return Result.success(tokenResp.access_token)
            }
            when (tokenResp.error) {
                "authorization_pending" -> {
                    // El usuario no ha completado la autorización.,Continuar encuestando
                    currentInterval = intervalMs
                }
                "slow_down" -> {
                    // El servidor requiere desaceleración,interval aumentar 5s
                    currentInterval = (currentInterval + 5000L).coerceAtMost(60_000L)
                }
                "expired_token" -> {
                    val msg = "device_code Caducado,Reinicie el inicio de sesión de OAuth"
                    _stateFlow.value = State.ERROR(msg)
                    return Result.failure(IllegalStateException(msg))
                }
                "access_denied" -> {
                    val msg = "Solicitud de autorización rechazada por el usuario"
                    _stateFlow.value = State.ERROR(msg)
                    return Result.failure(IllegalStateException(msg))
                }
                null -> {
                    // No hay error pero no access_token,Continuar encuestando
                }
                else -> {
                    val msg = "OAuth token El sondeo falló: ${tokenResp.error} — ${tokenResp.error_description ?: "desconocido"}"
                    _stateFlow.value = State.ERROR(msg)
                    return Result.failure(IllegalStateException(msg))
                }
            }
        }
        val msg = "OAuth Tiempo de espera de inicio de sesión,Por favor inténtalo de nuevo"
        _stateFlow.value = State.ERROR(msg)
        return Result.failure(IllegalStateException(msg))
    }

    /**
     * v2.6.0: OpenAI Codex Inicio de sesión con código de dispositivo especial(oficial codex CLI `--device-auth` Mismo estilo).
     *
     * Diferencias con el estándar RFC 8628:
     *  1. El punto final del código del dispositivo no es /device/code,más bien /api/accounts/deviceauth/usercode(JSON body);
     *  2. Sondeo /api/accounts/deviceauth/token devuelto**No token**más bien authorization_code
     *     + PKCE code_verifier(dos etapas);
     *  3. Finalmente úselos en /oauth/token para cambiar el valor real token,redirect_uri Se corrigió la autenticación del dispositivo/devolución de llamada.
     *
     * valor:No es necesario realizar una devolución de llamada localhost:1455 durante todo el proceso —— Los usuarios ya no encontrarán
     * “La dirección de bucle invertido fue tragada por el proxy. → ERR_CONNECTION_TIMED_OUT”.
     */
    @Suppress("CyclomaticComplexMethod", "ReturnCount", "TooGenericExceptionCaught", "LongMethod")
    private suspend fun launchCodexDeviceAuth(config: OAuthConfig, providerId: String): Result<String> {
        if (config.clientId.isBlank()) {
            val msg = "Codex Iniciar sesión no disponible:clientId esta vacio"
            _stateFlow.value = State.ERROR(msg)
            return Result.failure(IllegalStateException(msg))
        }
        if (providerId.isNotBlank()) {
            synchronized(providerConfigs) { providerConfigs[providerId] = config }
        }

        // 1. Solicitar user_code(JSON body)
        val base = config.authorizeUrl.substringBefore("/oauth/authorize").trimEnd('/')
        val userCodeResp: CodexUserCodeResponse? = withContext(Dispatchers.IO) {
            resultOf {
                val body = """{"client_id":"${config.clientId}"}""".toRequestBody(JSON_MEDIA)
                val request = Request.Builder()
                    .url("$base/api/accounts/deviceauth/usercode")
                    .header("Content-Type", "application/json")
                    .header("originator", "muse")
                    .post(body)
                    .build()
                buildHttpClient().newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("usercode Solicitud fallida: HTTP ${resp.code}${oauthErrorField(text)}")
                    io.zer0.common.AppJson.decodeFromString(CodexUserCodeResponse.serializer(), text)
                }
            }.onError { msg, t -> Logger.w(TAG, "Codex usercode Solicitar excepción: $msg", t) }.getOrNull()
        }
        if (userCodeResp == null || userCodeResp.resolvedUserCode.isBlank() || userCodeResp.deviceAuthId.isBlank()) {
            val msg = "Codex Error al iniciar sesión:Código de dispositivo no obtenido"
            _stateFlow.value = State.ERROR(msg)
            return Result.failure(IllegalStateException(msg))
        }

        val deviceUrl = "$base/codex/device"
        _stateFlow.value = State.AWAITING_USER(userCodeResp.resolvedUserCode, deviceUrl)

        // 2. sondeo authorization_code(403/404 = Aún no autorizado)
        _stateFlow.value = State.POLLING
        val intervalMs = (userCodeResp.interval.coerceAtLeast(3) * 1000L)
        val deadline = System.currentTimeMillis() + CODEX_DEVICE_TIMEOUT_MS
        var codeResult: CodexAuthCode? = null
        while (System.currentTimeMillis() < deadline) {
            delay(intervalMs)
            val polled: CodexAuthCodeResponse? = withContext(Dispatchers.IO) {
                resultOf {
                    val body = """{"device_auth_id":"${userCodeResp.deviceAuthId}","user_code":"${userCodeResp.resolvedUserCode}"}"""
                        .toRequestBody(JSON_MEDIA)
                    val request = Request.Builder()
                        .url("$base/api/accounts/deviceauth/token")
                        .header("Content-Type", "application/json")
                        .header("originator", "muse")
                        .post(body)
                        .build()
                    buildHttpClient().newCall(request).execute().use { resp ->
                        if (resp.code == 403 || resp.code == 404) return@use null
                        if (!resp.isSuccessful) return@use null
                        val text = resp.body?.string().orEmpty()
                        io.zer0.common.AppJson.decodeFromString(CodexAuthCodeResponse.serializer(), text)
                    }
                }.onError { msg, t -> Logger.w(TAG, "Codex Excepción de sondeo de código de dispositivo: $msg", t) }.getOrNull()
            }
            if (polled != null && polled.authorizationCode.isNotBlank()) {
                codeResult = CodexAuthCode(polled.authorizationCode, polled.codeVerifier)
                break
            }
        }
        if (codeResult == null) {
            val msg = "Codex Autorización caducada o incompleta,Por favor inténtalo de nuevo"
            _stateFlow.value = State.ERROR(msg)
            return Result.failure(IllegalStateException(msg))
        }

        // 3. Reemplazar con código_autorización + verificador_código token
        _stateFlow.value = State.POLLING
        val tokenResp = withContext(Dispatchers.IO) {
            resultOf {
                val form = FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("code", codeResult.authorizationCode)
                    .add("redirect_uri", "$base/deviceauth/callback")
                    .add("client_id", config.clientId)
                    .add("code_verifier", codeResult.codeVerifier)
                    .build()
                val request = Request.Builder().url(config.tokenUrl).post(form).build()
                buildHttpClient().newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Logger.w(TAG, "Codex token Error en el intercambio: HTTP ${resp.code}${oauthErrorField(text)}")
                        return@use null
                    }
                    io.zer0.common.AppJson.decodeFromString(TokenResponse.serializer(), text)
                }
            }.onError { msg, t -> Logger.w(TAG, "Codex token Excepción de cambio: $msg", t) }.getOrNull()
        }
        if (tokenResp == null || tokenResp.access_token.isBlank()) {
            val msg = "Codex token Error en el intercambio"
            _stateFlow.value = State.ERROR(msg)
            return Result.failure(IllegalStateException(msg))
        }
        if (providerId.isNotBlank()) persistTokenBundle(providerId, tokenResp)
        _stateFlow.value = State.SUCCESS(tokenResp.access_token, tokenResp.idToken)
        return Result.success(tokenResp.access_token)
    }

    /** Codex Respuesta del código del dispositivo(oficial CLI: device_auth_id + user_code + interval). */
    @Serializable
    private data class CodexUserCodeResponse(
        @SerialName("device_auth_id") val deviceAuthId: String = "",
        @SerialName("user_code") val userCode: String = "",
        @SerialName("usercode") val usercodeAlias: String = "",
        val interval: Long = 5L,
    ) {
        /** user_code Compatible con ambos nombres de campo(Diferentes versiones oficiales). */
        val resolvedUserCode: String get() = userCode.ifBlank { usercodeAlias }
    }

    private data class CodexAuthCode(val authorizationCode: String, val codeVerifier: String)

    @Serializable
    private data class CodexAuthCodeResponse(
        @SerialName("authorization_code") val authorizationCode: String = "",
        @SerialName("code_verifier") val codeVerifier: String = "",
    )

    /**
     * empezar Authorization Code Flow + PKCE.
     *
     * pasos:
     *  1. generar PKCE code_verifier + code_challenge + state(CSRF)
     *  2. estructura authorizeUrl,Abra el navegador del sistema con Intent.ACTION_VIEW
     *  3. Pase [stateFlow] empujar AWAITING_USER(vacío userCode),UI Mensaje "El navegador está abierto"
     *  4. espera [completeAuthorizationCodeFlow] ser [OAuthCallbackActivity] llamar,
     *     conseguir code,Reemplazar con código + code_verifier access_token
     *  5. empujar SUCCESS(apiKey),Regresar Result.success(access_token)
     *
     * P2-11: si [providerId] No vacío,Después de iniciar sesión correctamente, el TokenBundle se cifrará y escribirá automáticamente. [secureStore].
     *
     * @param activity Contexto de actividad utilizado para startActivity
     * @param config OAuth Configuración
     * @param providerId P2-11: proveedor ID(Una cadena vacía significa que no hay persistencia,Mantenga compatibles las llamadas tradicionales)
     * @return cuando tiene éxito Result.success(access_token);en el fracaso Result.failure(anormal)
     */
    // v2.6.0: Agregar rama del servidor de devolución de llamada de bucle invertido; complejidad general 16/15, linealidad del proceso (preparación→Navegador→devolución de llamada→Reemplazo de token), la división interrumpirá la secuencia de autorización y estará exenta por punto de acuerdo con la práctica del proyecto.
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    suspend fun launchAuthorizationCodeFlow(activity: Activity, config: OAuthConfig, providerId: String = ""): Result<String> {
        if (config.clientId.isBlank()) {
            val msg = "Auth Code Flow No disponible:clientId esta vacio"
            _stateFlow.value = State.ERROR(msg)
            return Result.failure(IllegalStateException(msg))
        }
        // P2-11: Configuración de caché para la consulta de actualizaciónTokenIfNeeded
        if (providerId.isNotBlank()) {
            synchronized(providerConfigs) { providerConfigs[providerId] = config }
        }
        // 1. Generar par PKCE + estado; solo se permite un camarero de devolución de llamada de autorización del navegador en el mismo proceso.
        val pending = synchronized(authCodeFlowLock) {
            if (pendingAuthCodeFlow.value != null) {
                null
            } else {
                val codeVerifier = generateCodeVerifier()
                val state = generateRandomString(32)
                PendingAuthCode(state, codeVerifier, CompletableDeferred<String>()).also {
                    pendingAuthCodeFlow.value = it
                }
            }
        } ?: run {
            val msg = "Ya existe un proceso de autorización de OAuth esperando una devolución de llamada"
            _stateFlow.value = State.ERROR(msg)
            return Result.failure(IllegalStateException(msg))
        }
        val codeVerifier = pending.codeVerifier
        val codeChallenge = computeCodeChallenge(codeVerifier)
        val state = pending.state
        val deferred = pending.deferred

        // 2. estructura authorizeUrl
        val authUrl = buildAuthorizationUrl(config, codeChallenge, state)

        // 3. Abrir navegador
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { activity.startActivity(intent) }.onFailure {
            val msg = "No puedo abrir el navegador: ${it.message ?: "desconocido"}"
            _stateFlow.value = State.ERROR(msg)
            clearPendingAuthCodeFlow(pending)
            return Result.failure(it)
        }

        // 4. Esperar devolución de llamada —— Dos canales:
        //    a) Deep Link(Personalizar scheme,OAuthCallbackActivity → completeAuthorizationCodeFlow)
        //    b) Loopback HTTP(v2.6.0: redirectUri para http://localhost:<port>/… cuando,
        //       Codex CLI El mismo cliente público solo permite devoluciones de llamadas en bucle e inicia un servidor HTTP temporal para recibir)
        _stateFlow.value = State.AWAITING_USER()
        val loopbackServer = if (config.redirectUri.startsWith("http://localhost:") ||
            config.redirectUri.startsWith("http://127.0.0.1:")
        ) {
            runCatching { LoopbackCallbackServer.start(config.redirectUri, pending) }.getOrElse {
                Logger.e(TAG, "Loopback El servidor de devolución de llamada no pudo iniciarse", it)
                null
            }
        } else {
            null
        }
        val codeResult = awaitAuthorizationCode(deferred, AUTH_CODE_FLOW_TIMEOUT_MS)
        loopbackServer?.stop()
        if (codeResult.isFailure) {
            clearPendingAuthCodeFlow(pending)
            val error = codeResult.exceptionOrNull() ?: IllegalStateException("Error en la devolución de llamada de autorización")
            _stateFlow.value = State.ERROR(error.message ?: "Error en la devolución de llamada de autorización")
            return Result.failure(error)
        }
        val code = codeResult.getOrThrow()

        // 5. Reemplazar con código + code_verifier access_token
        _stateFlow.value = State.POLLING
        val tokenResp = exchangeCodeForToken(config, code, codeVerifier)
        clearPendingAuthCodeFlow(pending)
        if (tokenResp == null || tokenResp.access_token.isBlank()) {
            val msg = "OAuth token Error en el intercambio"
            _stateFlow.value = State.ERROR(msg)
            return Result.failure(IllegalStateException(msg))
        }
        // P2-11: persistencia TokenBundle
        if (providerId.isNotBlank()) {
            persistTokenBundle(providerId, tokenResp)
        }
        _stateFlow.value = State.SUCCESS(tokenResp.access_token, tokenResp.idToken)
        return Result.success(tokenResp.access_token)
    }

    /**
     * por [OAuthCallbackActivity] Llamado al recibir una devolución de llamada de Deep Link,Complete el flujo del código de autenticación.
     *
     * verificar state(CSRF protección),Analiza el código después de hacer coincidir y resuelve el diferido pendiente.
     *
     * @param code OAuth parámetro de código en devolución de llamada
     * @param state OAuth El parámetro de estado en la devolución de llamada.
     * @return true Indica coincidencia de estado,El proceso seguirá cambiando. token;false Indica que no hay ningún proceso pendiente o el estado no coincide
     */
    fun completeAuthorizationCodeFlow(code: String, state: String): Boolean {
        val pending = pendingAuthCodeFlow.value ?: run {
            Logger.w(TAG, "completeAuthorizationCodeFlow pero no pending Auth Code Flow")
            return false
        }
        if (state != pending.state) {
            Logger.w(TAG, "OAuth state no hay coincidencia: expected=${pending.state.take(4)}***, got=${state.take(4)}***")
            return false
        }
        if (code.isBlank()) {
            Logger.w(TAG, "OAuth el código de devolución de llamada está vacío")
            return false
        }
        return pending.deferred.complete(code)
    }

    /**
     * Espere la devolución de llamada de autorización pero no permita la corrupción/La falta de un enlace profundo bloquea permanentemente el flujo de OAuth.
     * La cancelación de la rutina externa todavía se propaga hacia arriba y no se convierte en un tiempo de espera normal.
     */
    internal suspend fun awaitAuthorizationCode(deferred: Deferred<String>, timeoutMs: Long): Result<String> {
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        return try {
            Result.success(withTimeout(timeoutMs) { deferred.await() })
        } catch (timeout: TimeoutCancellationException) {
            Result.failure(IllegalStateException("OAuth Tiempo de espera de devolución de llamada de autorización", timeout))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun clearPendingAuthCodeFlow(expected: PendingAuthCode) {
        synchronized(authCodeFlowLock) {
            if (pendingAuthCodeFlow.value === expected) pendingAuthCodeFlow.value = null
        }
    }

    /** Cancelar un proceso OAuth actualmente en curso(Se llama cuando el usuario cancela/sale activamente de la página.). */
    fun cancel() {
        pendingAuthCodeFlow.value?.deferred?.completeExceptionally(OAuthCancelledException())
        pendingAuthCodeFlow.value = null
        _stateFlow.value = State.IDLE
    }

    /** Restablecer estado a IDLE(UI Llamado después de cerrar la ventana emergente.). */
    fun resetState() {
        _stateFlow.value = State.IDLE
    }

    // ── P2-11: Relacionado con el aislamiento de credenciales API ──────────────────────────────────────

    /**
     * P2-11: Lea el token de OAuth persistente.
     *
     * UI Llamado al ingresar a ProviderEditPage,Si la devolución no está vacía y no ha caducado,Access_token se puede reutilizar directamente para completar el cuadro de entrada apiKey,
     * No es necesario que el usuario vuelva a iniciar sesión.
     *
     * @return almacenado [TokenBundle];Se devolverá el almacenamiento no inicializado/sin registro/error de descifrado. null
     */
    suspend fun getStoredToken(providerId: String): TokenBundle? {
        val store = secureStore ?: return null
        if (providerId.isBlank()) return null
        return store.getOAuthToken(providerId)
    }

    /**
     * P2-11: Eliminar el persistente OAuth token(revocar el acceso).
     *
     * UILlamada al botón "Revocar acceso",Borre el token de acceso/actualización almacenado localmente.
     * Nota:Esto sólo revoca el almacenamiento local.,El servidor no será notificado. invalidate token(Necesita llamar al punto final de revocación del servidor.,
     * La mayoría de los proveedores no exponen este punto final). La interfaz de usuario debe borrar el cuadro de entrada apiKey después de la revocación.
     */
    suspend fun revokeStoredToken(providerId: String) {
        val store = secureStore ?: return
        if (providerId.isBlank()) return
        store.deleteOAuthToken(providerId)
        synchronized(providerConfigs) { providerConfigs.remove(providerId) }
    }

    /**
     * P2-11: Actualización automática token(Si ha caducado o está a punto de caducar).
     *
     * proceso:
     *  1. Leer almacenado [TokenBundle]
     *  2. Si expira en es 0(No caducado/desconocido)o expiresAt > now + [REFRESH_BUFFER_SECONDS],Volver directamente a la actualidad access_token
     *  3. Si es así refresh_token,POST refresh_token grant Reemplazar por otros nuevos token;Actualización después del éxito [TokenBundle] y volver nuevo access_token
     *  4. No hay ningún token de actualización o la actualización falló,Regresar Result.failure(El usuario necesita iniciar sesión nuevamente)
     *
     * @param providerId proveedor ID
     * @param config retenido por la persona que llama [OAuthConfig](tan persistente [ProviderConfig.oauthConfig]);Prioridad utilizada cuando no está vacía,
     *   Evite la pérdida de memoria después de reiniciar la aplicación [providerConfigs] Error de actualización causado por caché vacío(B-24);Si está vacío, regresa a la memoria caché.
     * @return cuando tiene éxito Result.success(válido access_token);en el fracaso Result.failure(anormal)
     */
    suspend fun refreshTokenIfNeeded(providerId: String, config: OAuthConfig? = null): Result<String> {
        if (providerId.isBlank()) {
            return Result.failure(IllegalArgumentException("providerId esta vacio"))
        }
        val lock = refreshLocks.computeIfAbsent(providerId) { Mutex() }
        return lock.withLock { refreshTokenIfNeededLocked(providerId, config) }
    }

    private suspend fun refreshTokenIfNeededLocked(providerId: String, config: OAuthConfig? = null): Result<String> {
        val store = secureStore ?: run {
            return Result.failure(IllegalStateException("SecureCredentialStore no inicializado"))
        }
        val bundle = store.getOAuthToken(providerId)
            ?: return Result.failure(IllegalStateException("Ninguno almacenado OAuth token"))
        val now = System.currentTimeMillis()
        val bufferMs = REFRESH_BUFFER_SECONDS * 1000L
        // No caducado(Contiene expiresAt=0 desconocido)Volver directamente a la actualidad access_token
        if (bundle.expiresAt == 0L || bundle.expiresAt > now + bufferMs) {
            return Result.success(bundle.accessToken)
        }
        // Caducado pero no refresh_token,No se puede actualizar
        val refreshToken = bundle.refreshToken
        if (refreshToken.isNullOrBlank()) {
            return Result.failure(IllegalStateException("token Caducado y no refresh_token"))
        }
        // B-24: Dar prioridad al pasado por la persona que llama config(La configuración de persistencia aún se puede actualizar después del reinicio entre aplicaciones),
        // Solo recurra a la memoria caché si la persona que llama no lo pasa(providerConfigs solo en launch* proceso de llenado)
        val oauthConfig = config ?: synchronized(providerConfigs) { providerConfigs[providerId] }
            ?: return Result.failure(IllegalStateException("sin caché OAuthConfig,No se puede actualizar"))
        // llamar refresh_token grant
        val refreshResult = refreshAccessToken(oauthConfig, refreshToken)
        if (refreshResult == null || refreshResult.access_token.isBlank()) {
            // Error al actualizar,Borrar almacenamiento local(Forzar al usuario a iniciar sesión nuevamente)
            store.deleteOAuthToken(providerId)
            return Result.failure(IllegalStateException("refresh_token Caducado,Por favor inicia sesión nuevamente"))
        }
        // actualizar TokenBundle(refresh_token Algunos proveedores devuelven otros nuevos cada vez que actualizan,La pieza solo regresa por primera vez.;
        // Si la respuesta no incluye refresh_token,Mantener los valores antiguos para la próxima actualización)
        val newBundle = TokenBundle(
            accessToken = refreshResult.access_token,
            refreshToken = refreshResult.refresh_token ?: refreshToken,
            expiresAt = computeExpiresAt(refreshResult.expires_in),
            scope = refreshResult.scope ?: bundle.scope,
        )
        store.storeOAuthToken(providerId, newBundle)
        return Result.success(newBundle.accessToken)
    }

    /** P2-11: poner [TokenResponse] girar [TokenBundle] y escribe [secureStore]. */
    private suspend fun persistTokenBundle(providerId: String, resp: TokenResponse) {
        val store = secureStore ?: return
        val bundle = TokenBundle(
            accessToken = resp.access_token,
            refreshToken = resp.refresh_token,
            expiresAt = computeExpiresAt(resp.expires_in),
            scope = resp.scope.orEmpty(),
            // v2.x(Codex): guardar id_token —— El encabezado de solicitud ChatGPT-Account-ID se analiza desde aquí.
            idToken = resp.idToken,
        )
        store.storeOAuthToken(providerId, bundle)
    }

    /** P2-11: Reemplazar con refresco_token access_token(refresh_token grant). */
    private suspend fun refreshAccessToken(config: OAuthConfig, refreshToken: String): TokenResponse? {
        return withContext(Dispatchers.IO) {
            resultOf {
                val form = FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refreshToken)
                    .add("client_id", config.clientId)
                    .apply { config.clientSecret?.takeIf { it.isNotBlank() }?.let { add("client_secret", it) } }
                    .build()
                val request = Request.Builder().url(config.tokenUrl).post(form).build()
                buildHttpClient().newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        // C-25: El texto original del cuerpo de la respuesta puede reflejar parámetros sensibles.,Recuerde solo el código HTTP + campo de error insensibilizado
                        Logger.w(TAG, "refresh_token falló: HTTP ${resp.code}${oauthErrorField(body)}")
                        return@use null
                    }
                    io.zer0.common.AppJson.decodeFromString(
                        TokenResponse.serializer(),
                        body,
                    )
                }
            }.onError { msg, t -> Logger.w(TAG, "refresh_token Solicitar excepción: $msg", t) }.getOrNull()
        }
    }

    /** P2-11: Según expires_in(segundos)Calcular la marca de tiempo de vencimiento(epoch millis). */
    private fun computeExpiresAt(expiresInSeconds: Long): Long {
        if (expiresInSeconds <= 0L) return 0L
        return System.currentTimeMillis() + expiresInSeconds * 1000L
    }

    /**
     * C-25: Extraiga el campo de error del cuerpo de respuesta de error del intercambio de tokens para registrarlo.
     * No recuerdo el texto original del cuerpo de la respuesta. — El texto original puede reflejar parámetros confidenciales como code/redirect_uri.
     * El error de análisis devuelve una cadena vacía(Solo recuerda HTTP code).
     */
    private fun oauthErrorField(body: String): String = runCatching {
        val obj = io.zer0.common.AppJson.parseToJsonElement(body).jsonObject
        obj["error"]?.jsonPrimitive?.content?.let { ", error=$it" } ?: ""
    }.getOrDefault("")

    // ── herramientas internas ───────────────────────────────────────────────────────

    /** Sondear el punto final del token(Device Flow). */
    private suspend fun pollToken(config: OAuthConfig, deviceCode: String): TokenResponse {
        return withContext(Dispatchers.IO) {
            resultOf {
                val form = FormBody.Builder()
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    .add("device_code", deviceCode)
                    .add("client_id", config.clientId)
                    .apply { config.clientSecret?.takeIf { it.isNotBlank() }?.let { add("client_secret", it) } }
                    .build()
                val request = Request.Builder()
                    .url(config.tokenUrl)
                    // v2.6.0: GitHub El punto final del token requiere Aceptar: aplicación/json para devolver JSON.
                    .header("Accept", "application/json")
                    .post(form)
                    .build()
                buildHttpClient().newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    // HTTP 4xx También puede llevar un campo de error(authorization_pending espera),analizar por cuerpo
                    io.zer0.common.AppJson.decodeFromString(
                        TokenResponse.serializer(),
                        body,
                    )
                }
            }.onError { msg, t -> Logger.w(TAG, "OAuth token Excepción de solicitud de sondeo: $msg", t) }
                .getOrNull()
                ?: TokenResponse(error = "network_error", error_description = "Error en la solicitud de red")
        }
    }

    /**
     * Reemplace access_token con código + code_verifier.
     *
     * P2-11: Volver para completar [TokenResponse](Contiene refresh_token / expires_in / scope),
     * Para que las personas que llaman construyan [TokenBundle] Persistencia.
     */
    private suspend fun exchangeCodeForToken(config: OAuthConfig, code: String, codeVerifier: String): TokenResponse? {
        return withContext(Dispatchers.IO) {
            resultOf {
                val form = FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("code", code)
                    .add("redirect_uri", config.redirectUri)
                    .add("client_id", config.clientId)
                    .add("code_verifier", codeVerifier)
                    .apply { config.clientSecret?.takeIf { it.isNotBlank() }?.let { add("client_secret", it) } }
                    .build()
                val request = Request.Builder().url(config.tokenUrl).post(form).build()
                buildHttpClient().newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        // C-25: El texto original del cuerpo de la respuesta puede reflejar parámetros sensibles.,Recuerde solo el código HTTP + campo de error insensibilizado
                        Logger.w(TAG, "token Error en el intercambio: HTTP ${resp.code}${oauthErrorField(body)}")
                        return@use null
                    }
                    val token = io.zer0.common.AppJson.decodeFromString(
                        TokenResponse.serializer(),
                        body,
                    )
                    if (token.access_token.isBlank()) {
                        Logger.w(TAG, "token Respuesta Ninguna access_token: error=${token.error}, desc=${token.error_description}")
                        return@use null
                    }
                    token
                }
            }.onError { msg, t -> Logger.w(TAG, "token Excepción de solicitud de cambio: $msg", t) }.getOrNull()
        }
    }

    /** Autorización de construcción para flujo de código de autorización URL(Contiene PKCE + state). */
    private fun buildAuthorizationUrl(config: OAuthConfig, codeChallenge: String, state: String): String {
        val params = buildList {
            add("response_type" to "code")
            add("client_id" to config.clientId)
            add("redirect_uri" to config.redirectUri)
            add("code_challenge" to codeChallenge)
            add("code_challenge_method" to "S256")
            add("state" to state)
            if (config.scope.isNotBlank()) add("scope" to config.scope)
        }
        val query = params.joinToString("&") { (k, v) ->
            "$k=${Uri.encode(v)}"
        }
        val separator = if (config.authorizeUrl.contains("?")) "&" else "?"
        return config.authorizeUrl + separator + query
    }

    // ── PKCE Herramientas(RFC 7636,Equivalente a McpOAuthFlow pero implementado de forma independiente) ───────────

    private const val VERIFIER_LENGTH = 64
    private val VERIFIER_CHARS: CharArray = (
        ('A'..'Z').toList() + ('a'..'z').toList() + ('0'..'9').toList() +
            listOf('-', '.', '_', '~')
        ).toCharArray()

    private fun generateCodeVerifier(): String {
        val random = SecureRandom()
        val sb = StringBuilder(VERIFIER_LENGTH)
        repeat(VERIFIER_LENGTH) { sb.append(VERIFIER_CHARS[random.nextInt(VERIFIER_CHARS.size)]) }
        return sb.toString()
    }

    private fun computeCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        // RFC 7636: BASE64URL codificación,eliminar = relleno
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun generateRandomString(length: Int): String {
        val random = SecureRandom()
        val sb = StringBuilder(length)
        repeat(length) { sb.append(VERIFIER_CHARS[random.nextInt(VERIFIER_CHARS.size)]) }
        return sb.toString()
    }
}

/** OAuth Excepciones utilizadas cuando se cancela el proceso(El usuario cancela/sale activamente de la página). */
private class OAuthCancelledException : Exception("OAuth Proceso cancelado")

/**
 * v2.x(Codex): Analice el reclamo especificado en la carga útil JWT (sin verificación de firma —— Leer solo la declaración,
 * No se utiliza para autenticación; la autenticación todavía la realiza el token de acceso del portador).
 *
 * @param jwt en forma de `header.payload.signature` La cadena JWT
 * @param claim El nombre del reclamo que se dará (p. ej. `chatgpt_account_id`)
 * @return claim valor de cadena;JWT ilegal/claim no existe retorno null
 */
@Suppress("ReturnCount")
internal fun parseJwtClaim(jwt: String?, claim: String): String? {
    val payload = parseJwtPayload(jwt) ?: return null
    val value = payload[claim] as? kotlinx.serialization.json.JsonPrimitive ?: return null
    return value.contentOrNull.takeIf { !it.isNullOrBlank() }
}

/**
 * v2.6.2(Codex Solución de inicio de sesión): Analice la carga útil de JWT en JsonObject (sin verificación de firma).
 *
 * Reparar la decodificación base64url —— Solo la implementación original. `-`/`_` Reemplazar con `+`/`/`, no añadido `=` padding,
 * Android `Base64.DEFAULT` La decodificación de cadenas cuya longitud no sea múltiplo de 4 fallará, lo que resultará en el análisis de la carga útil
 * Devuelve silenciosamente nulo (Codex account-id Por lo tanto nunca podrá obtenerse).
 */
internal fun parseJwtPayload(jwt: String?): kotlinx.serialization.json.JsonObject? {
    val segment = jwt?.split(".")?.takeIf { it.size >= 2 }?.get(1)
    val json = segment?.let { seg ->
        runCatching {
            val normalized = seg.replace('-', '+').replace('_', '/')
            // base64url Sin relleno, rellene en múltiplos de 4 antes de decodificar.
            // Utilice java.util.Base64(API 26+, consistente con minSdk) en lugar de android.util.Base64,
            // Haga que esta función sea puramente comprobable por JVM (no se requiere Robolectric).
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            java.util.Base64.getDecoder().decode(padded).decodeToString()
        }.getOrNull()
    }
    return json?.let {
        runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(it)
        }.getOrNull() as? kotlinx.serialization.json.JsonObject
    }
}

/**
 * v2.6.2(Codex Solución de inicio de sesión): Analice el ID de la cuenta ChatGPT del id_token de OpenAI.
 *
 * OpenAI Anidar el reclamo en el espacio de nombres de la URL `https://api.openai.com/auth` abajo,
 * En lugar del nivel superior:
 * ```json
 * { "https://api.openai.com/auth": { "chatgpt_account_id": "..." } }
 * ```
 * implementación comunitaria (Ruby / LangChain) utilizado uniformemente `dig('https://api.openai.com/auth',
 * 'chatgpt_account_id')` Toma valor. La implementación original solo leía el reclamo de nivel superior, que siempre era nulo.
 * causa `ChatGPT-Account-ID` encabezado nunca escrito —— Codex Puntos finales todos 403.
 *
 * Orden de compatibilidad: espacios de nombres anidados → Nivel superior (versión antigua)/Algunas cuentas pueden estar directamente vinculadas al nivel superior).
 */
internal fun parseChatGptAccountId(jwt: String?): String? {
    val payload = parseJwtPayload(jwt) ?: return null
    val authNs = payload["https://api.openai.com/auth"] as? kotlinx.serialization.json.JsonObject
    val nested = (authNs?.get("chatgpt_account_id") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    val topLevel = (payload["chatgpt_account_id"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    return (nested ?: topLevel)?.takeIf { it.isNotBlank() }
}
