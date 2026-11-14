package io.zer0.muse.web

import android.content.Context
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.BuildConfig
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.notification.MuseNotificationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 8.11: Servidor web integrado(Ktor Netty + JWT + mDNS,Opcional HTTPS).
 *
 * Función:
 *  - Expuesto dentro de LAN REST API,Permitir sesiones y configuraciones de navegación de PC/otros dispositivos
 *  - JWT Autenticación(Contraseña → HMAC-SHA256 firma token,24h Periodo de validez)
 *  - mDNS Registrarse(Los mismos dispositivos Wi-Fi se pueden descubrir a través de Bonjour/Avahi)
 *  - La notificación permanente muestra el estado de ejecución(Haga clic para regresar App)
 *
 * P2-13:WebServer PIN seguro
 *  - Genera 6 dígitos al inicio PIN(aleatorio),Se utiliza para la verificación de acceso por primera vez en el lado web.
 *  - Web pase final `POST /api/auth/pin-login` Enviar PIN,Emitido después de la verificación por parte del servidor. JWT
 *  - JWT sólo a través de Cookie(`token`)con cuerpo de respuesta de inicio de sesión(Authorization cabeza)Problema,No es necesario volver a ingresar para solicitudes posteriores.
 *  - C-29/C-11: No más query param(`?token=`)cabello nuevo JWT(query Entrará en el historial del navegador./Referer/registro de acceso);
 *    La autenticación JWT para rutas protegidas solo lee el encabezado de Autorización y `token` Cookie,Completamente eliminado `?token=` leer ruta
 *    (La interfaz de usuario web del lado del servidor se basa completamente en httpOnly Cookie,`?token=` Los residuos sólo provocarán fugas simbólicas.,Ya no es compatible)
 *
 * enrutamiento:
 *  - `GET  /api/health` — chequeo de salud(No se requiere autenticación;LAN Campo de versión de convergencia/tiempo de actividad cuando es externo)
 *  - `POST /api/auth/login` — cambio de contraseña JWT(No se requiere autenticación)
 *  - `POST /api/auth/pin-login` — PIN Cambiar JWT(No se requiere autenticación,ajustes Cookie)
 *  - `POST /api/auth/logout` — Cerrar sesión,Caducado `token` Cookie(A-10,No se requiere autenticación)
 *  - `GET  /api/sessions` — Lista de conversación(JWT Autenticación)
 *  - `GET  /api/sessions/{id}/messages` — Lista de mensajes de conversación(JWT Autenticación)
 *  - `GET  /api/settings` — Resumen de configuración(Provider Nombre/Número de modelos,Desensibilización clave)(JWT Autenticación)
 *  - `GET  /api/media/{token}` — imagen del mensaje/Voz(JWT Autenticación,solo lectura,Envíe únicamente archivos que hayan sido registrados y todavía estén en el directorio permitido)
 *
 * Selección de motor:
 *  - CIO(Ktor motor nativo)— puro Kotlin, Sin dependencias de reflexión, apoyo HTTPS/WSS, Para acceso cifrado LAN
 *  - Antes de cambiar, era CIO(No compatible HTTPS,Se informará un error directamente al inicio.);Mantener la dependencia del CIO sólo para evitar ampliar el impacto.
 *
 * v2.x: Opcional HTTPS(certificado autofirmado)
 *  - [WebServerConfig.httpsEnabled] Configurado por el CIO después de ser encendido `sslConnector`,WebSocket Caminar sincrónicamente wss
 *  - Certificado por [WebServerTls] Primero generado y persistido hasta filesDir,Reiniciar multiplexación
 *  - Configuración de cookies de autenticación cuando HTTPS está habilitado secure=true;Certificado SHA-256 huella digital expuesta a través de registros y estado
 *
 * seguro:
 *  - Enlace predeterminado `127.0.0.1`;Vincular después de habilitar el acceso LAN `0.0.0.0`(Todas las tarjetas de red)
 *  - R-SEC-03: JWT Utilice una clave de firma aleatoria independiente,Separado de la contraseña del usuario
 *  - Todas las rutas protegidas requieren `Authorization: Bearer <token>` cabeza
 *  - Cuando la contraseña está vacía, se genera y persiste automáticamente una contraseña aleatoria de 8 dígitos.(Cuando se habilita por primera vez)
 *  - PIN Genere automáticamente un PIN aleatorio de 6 dígitos cuando esté vacío y consérvelo(Cuando se habilita por primera vez)
 */
@Suppress("LongParameterList")
class WebServer(
    private val settings: SettingsRepository,
    private val sessionRepo: SessionRepository,
    private val notificationManager: MuseNotificationManager,
    private val mdnsService: MdnsService,
    private val context: Context,
    private val chatViewModel: io.zer0.muse.ui.ChatViewModel,
    generationManager: io.zer0.muse.schedule.ChatGenerationManager,
    private val chatService: io.zer0.ai.ChatService,
    private val assistantRepository: io.zer0.muse.data.assistant.AssistantRepository,
    /** v2.0: webhook Clave de canal requerida para la verificación de firma(QQ Bot Secret;Actualización perezosa). */
    private val channelManager: io.zer0.muse.channel.ChannelManager,
    /** Base de datos del asistente maestro(situación general `facts.db`):La fuente principal del grupo de memoria para memorizar comandos de solo lectura. */
    private val factStore: io.zer0.memory.fact.FactStore,
    /** Proveedor de subbiblioteca:La memoria del subasistente es `facts_<id>.db`,El mismo conjunto de enrutamiento que el lado de escritura. */
    private val factDbProvider: io.zer0.memory.fact.FactDbProvider,
    /** Tres tablas en la base de conocimientos. DAO(Fuente de datos para comandos de conocimiento de solo lectura). */
    private val knowledgeBaseDao: io.zer0.muse.data.knowledge.KnowledgeBaseDao,
    private val knowledgeDocDao: io.zer0.muse.data.knowledge.KnowledgeDocDao,
    private val knowledgeChunkDao: io.zer0.muse.data.knowledge.KnowledgeChunkDao,
    /** embedding servicio:Leer solo su instantánea del backend en tiempo de ejecución(Base de conocimientos de un vistazo embeddingBackend). */
    private val embeddingService: io.zer0.muse.rag.EmbeddingService,
    /** Dos horarios para tareas programadas DAO(Fuente de datos para comandos automatizados de solo lectura). */
    private val scheduledTaskDao: io.zer0.muse.data.schedule.ScheduledTaskDao,
    private val scheduledTaskExecutionDao: io.zer0.muse.data.schedule.ScheduledTaskExecutionDao,
    /** Fuente de datos para comandos de solo lectura del estado del sistema(Volumen/Copia de seguridad/Capacidad/Batería). */
    private val systemStatusSource: HostSystemStatusSource = HostSystemStatusSource.Empty,
) {
    private val hostMediaStore = HostMediaStore(
        imageDir = File(context.filesDir, IMAGE_MEDIA_DIR),
        voiceDir = File(context.filesDir, VOICE_MEDIA_DIR),
        avatarDir = File(context.filesDir, AVATAR_MEDIA_DIR),
    )

    private val hostMediaLinks = HostMediaLinks(hostMediaStore)

    /** Fuente de datos de memoria para comandos de solo lectura:Comparte el enrutamiento de la base de datos y la recuperación de texto completo con la página de memoria. */
    private val hostMemorySource: HostMemorySource = FactHostMemorySource(factStore, factDbProvider)

    /** Fuente de datos para comandos de conocimiento de solo lectura:El mismo lote de tablas que la página de la base de conocimientos de la aplicación. */
    private val hostKnowledgeSource: HostKnowledgeSource =
        DaoHostKnowledgeSource(knowledgeBaseDao, knowledgeDocDao, knowledgeChunkDao, embeddingService)

    /** Fuente de datos para comandos automatizados de solo lectura:La misma consulta que la página de tareas programadas de la aplicación. */
    private val hostTaskSource: HostTaskSource = DaoHostTaskSource(scheduledTaskDao, scheduledTaskExecutionDao)

    private val hostWebSocketGateway = HostWebSocketGateway(
        chatViewModel = chatViewModel,
        generationManager = generationManager,
        sessionRepo = sessionRepo,
        assistantRepo = assistantRepository,
        settingsRepo = settings,
        // Configurar la redacción publicitaria para que se analice según el idioma de la aplicación:createConfigurationContext Sólo se pueden leer directorios como valores-ja.
        settingsStrings = AndroidHostSettingsStrings(context),
        mediaLinks = hostMediaLinks,
        readSources = HostReadSources(
            memory = hostMemorySource,
            knowledge = hostKnowledgeSource,
            tasks = hostTaskSource,
            system = systemStatusSource,
        ),
    )

    /** v2.x: A2A Procesador de protocolo estándar (Agent Card + JSON-RPC). Coexiste con el protocolo privado del Host. */
    private val a2aHandler = io.zer0.muse.web.a2a.A2aHandler(
        chatService = chatService,
        sessionRepo = sessionRepo,
        assistantRepository = assistantRepository,
        appVersion = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull()?.toString() ?: "1.0.0",
    )

    @Volatile
    private var server: EmbeddedServer<*, *>? = null

    /** Serialice \"guardar configuración + iniciar y detener\" para evitar reiniciar repetidamente los servicios y desconectar las conexiones establecidas cuando la interfaz escribe configuraciones continuamente. */
    private val applyConfigMutex = Mutex()
    private val lifecycleMutex = Mutex()
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning
    private val _lastError = MutableStateFlow<String?>(null)

    /** Motivo diagnosticable del fallo de arranque más reciente; borrado después de un arranque exitoso. */
    val lastError: StateFlow<String?> = _lastError

    @Volatile
    private var currentPort: Int = 0

    @Volatile
    private var currentPassword: String = ""

    @Volatile
    private var currentPin: String = ""

    /** v2.x: ¿Está actualmente habilitado? HTTPS(Determinar el atributo seguro y la visualización del estado de las cookies de autenticación.). */
    @Volatile
    private var currentHttpsEnabled: Boolean = false

    /** v2.x: Certificado autofirmado HTTPS actual Huella digital SHA-256(Sólo tiene valor si HTTPS está habilitado). */
    @Volatile
    private var currentFingerprint: String? = null

    /** Marca de tiempo de inicio(cálculo del tiempo de actividad para /api/health). */
    @Volatile
    private var startedAt: Long = 0L

    /**
     * Inicie el servidor web.
     * @return true Comenzó con éxito,false El inicio falló(Ocupación del puerto/anomalía de configuración)
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        try {
            lifecycleMutex.withLock {
                startLocked()
            }
        } catch (t: kotlin.coroutines.cancellation.CancellationException) {
            throw t
        } catch (t: Exception) {
            // El límite de inicio debe reducir uniformemente la ocupación de puertos, la decodificación de configuración y los errores de inicialización del motor a un estado visible, y no puede permitir que la rutina de disparar y olvidar falle silenciosamente.
            recordStartFailure(t.message ?: "Falló la configuración o la inicialización del motor", t)
            false
        }
    }

    /**
     * Guarde la configuración y aplíquela inmediatamente.
     *
     * La página de configuración originalmente solo se escribió en el DataStore y el servicio debe esperar hasta la próxima vez que se cree realmente el proceso de la Aplicación antes de leerla.
     * propenso a aparecer“La página muestra la dirección, pero 127.0.0.1 aún es inaccesible”estado de funcionamiento falso.
     */
    suspend fun saveAndApplyConfig(config: WebServerConfig): Boolean = applyConfigMutex.withLock {
        val previous = settings.webServerConfigFlow.first()
        settings.saveWebServerConfig(config)
        if (!config.enabled) {
            stop()
            return@withLock true
        }
        // Sólo cuando los campos que afectan el enlace cambian es necesario reiniciar. No reinicie al cambiar PIN/contraseña/otros elementos no vinculantes:
        // Al reiniciar se cortarán todos los WebSockets establecidos y la página web informará "no se puede conectar" en ese momento.
        val bindingChanged = previous.enabled != config.enabled ||
            previous.port != config.port ||
            previous.allowLan != config.allowLan ||
            previous.httpsEnabled != config.httpsEnabled
        if (!bindingChanged && server != null) {
            currentPin = config.pin
            currentPassword = config.password
            return@withLock true
        }
        stop()
        check(start()) {
            "WebServer El inicio falló: ${lastError.value ?: "error desconocido"}"
        }
        return@withLock true
    }

    private suspend fun startLocked(): Boolean {
        if (server != null) {
            Logger.w(TAG, "WebServer Ya corriendo,Saltar inicios repetidos")
            return true
        }
        _lastError.value = null
        val config = settings.webServerConfigFlow.first()
        val port = config.port.coerceIn(MIN_PORT, MAX_PORT)
        var password = config.password
        // primer comienzo: Genere contraseñas aleatorias y consérvelas,Hazlo visible para los usuarios en la página de configuración.
        if (password.isBlank()) {
            password = WebServerConfig.generateRandomPassword()
        }
        // P2-13: Primera compilación de arranque de 6 bits PIN,Después de la persistencia, hágalo visible para los usuarios en la página de configuración.
        var pin = config.pin
        if (pin.isBlank()) {
            pin = WebServerConfig.generateRandomPin()
        }
        // R-SEC-03: Clave de firma JWT independiente,Iniciar generación y persistencia por primera vez.
        var jwtSecret = config.jwtSecret
        if (jwtSecret.isBlank()) {
            jwtSecret = WebServerConfig.generateRandomJwtSecret()
        }
        if (password != config.password || pin != config.pin || jwtSecret != config.jwtSecret) {
            settings.saveWebServerConfig(config.copy(password = password, pin = pin, jwtSecret = jwtSecret))
        }

        // M11: startedAt en it.start() Asignado antes,Evite que museRoutes capture 0 y cause errores en el cálculo del tiempo de actividad
        startedAt = System.currentTimeMillis()
        // M4: runCatching Cambiar a resultOf;M14: withContext(Dispatchers.IO) Asegúrese de que la lectura de la configuración no esté en el hilo principal
        return resultOf {
            val bindHost = WebServer.bindHost(config.allowLan)
            val listenPort = port
            // v2.x: HTTPS — Preparar certificado autofirmado cuando esté habilitado(primera generación,Reutilizar filesDir más tarde keystore)
            val tls = if (config.httpsEnabled) {
                WebServerTls.loadOrCreate(context).also { material ->
                    Logger.i(TAG, "WebServer HTTPS Habilitado,Certificado autofirmado de huella dactilar SHA-256: ${material.fingerprint}")
                }
            } else {
                null
            }
            // KTOR-7298: Ktor 3.0.x En la versión de lanzamiento ofuscada de R8,engine factory de
            // ServiceLoader La detección obtendrá múltiples candidatos,interno single() tirar
            // `IllegalArgumentException: Array has more than one element`(issue #4),
            // WebServer Nunca te levantes.
            // Doble seguro:① actualizar a Ktor 3.1.3(Solución oficial KTOR-7298);
            //         ② proguard-rules.pro Mantenga las clases relacionadas con ServiceLoader en META-INF/servicios.
            val candidate = embeddedServer(
                CIO,
                configure = webServerEngineConfiguration(tls, bindHost, listenPort),
            ) {
                configureSecurity(jwtSecret)
                install(WebSockets)
                configureSerialization()
                configureCors(config.allowLan)
                configureStatusPages()
                museRoutes(password, jwtSecret, pin, sessionRepo, settings, config.allowLan)
            }
            candidate.start(wait = false)
            server = candidate
            currentPort = port
            currentPassword = password
            currentPin = pin
            currentHttpsEnabled = config.httpsEnabled
            currentFingerprint = tls?.fingerprint
            _isRunning.value = true
            mdnsService.register(port)
            notificationManager.updateWebServerStatus(port, true)
            // H4: Ya no se muestran contraseñas en texto claro/PIN,Evite registros que revelen credenciales confidenciales
            Logger.i(TAG, "WebServer iniciado: $bindHost:$port (${if (config.httpsEnabled) "HTTPS" else "HTTP"})")
            true
        }.onError { msg, t ->
            recordStartFailure(t?.message?.takeIf { it.isNotBlank() } ?: msg, t)
        }.getOrNull() ?: false
    }

    private fun recordStartFailure(detail: String, throwable: Throwable?) {
        _lastError.value = detail
        Logger.e(TAG, "WebServer El inicio falló: $detail", throwable)
        server = null
        _isRunning.value = false
        currentPort = 0
        currentPassword = ""
        currentPin = ""
        currentHttpsEnabled = false
        currentFingerprint = null
        startedAt = 0L
    }

    /** Detener el servidor web(idempotente,No funciona cuando no está en funcionamiento).M13: Cambiar a suspend,bloqueado it.stop() Cambie al hilo IO. */
    suspend fun stop() = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            stopLocked()
        }
    }

    private suspend fun stopLocked() {
        server?.let {
            // M5: runCatching Cambiar a resultOf;M13: it.stop() es una operación de bloqueo,usar withContext(Dispatchers.IO) paquete
            resultOf { withContext(Dispatchers.IO) { it.stop(GRACE_PERIOD_MS, TIMEOUT_PERIOD_MS) } }
            server = null
        }
        if (_isRunning.value) {
            _isRunning.value = false
            mdnsService.unregister()
            notificationManager.updateWebServerStatus(currentPort, false)
            Logger.i(TAG, "WebServer Detenido")
        }
        currentPort = 0
        currentPassword = ""
        currentPin = ""
        currentHttpsEnabled = false
        currentFingerprint = null
        startedAt = 0L
    }

    /** Obtener información del estado de ejecución actual(Para visualización de interfaz de usuario).M9: password/pin Desensibilización,Devuelve sólo cadena booleana + enmascarada. */
    fun status(): Status {
        return Status(
            isRunning = _isRunning.value,
            port = currentPort,
            hasPassword = currentPassword.isNotBlank(),
            hasPin = currentPin.isNotBlank(),
            passwordMasked = maskSecret(currentPassword),
            pinMasked = maskSecret(currentPin),
            startedAt = startedAt,
            lastError = _lastError.value,
            httpsEnabled = currentHttpsEnabled,
            fingerprint = currentFingerprint,
        )
    }

    /** Tratamiento de desensibilización:Mantener los 2 primeros,Para otros fines * Reemplazar. La cadena vacía se devuelve sin cambios. */
    private fun maskSecret(secret: String): String {
        if (secret.isBlank()) return ""
        if (secret.length <= 2) return "*".repeat(secret.length)
        return secret.take(2) + "*".repeat(secret.length - 2)
    }

    // ── Ktor Configuración(Application función de extensión) ──────────────────────────────────

    /**
     * JWT Configuración de autenticación.
     * P2-13: Pase `authHeader` Extracción de tokens personalizados,Soporta dos fuentes:
     *  1. `Authorization: Bearer <token>` cabeza(comportamiento original)
     *  2. `token` Cookie(PIN/La contraseña la guarda automáticamente el navegador después de iniciar sesión)
     *  C-11: par eliminado `?token=` query param de lectura — La interfaz de usuario web del lado del servidor se basa completamente en httpOnly Cookie,
     *  `?token=` Restante simplemente hará que el JWT entre en el historial del navegador./registro de acceso/Referer,No se realizan más lecturas compatibles.
     *
     * usar `bearer` + Verificación manual HMAC sin ktor `jwt()` provider(Por razones, ver
     * [installHostAuthProvider]): Este último cargará los excluidos al momento de recibir el bono. `com.auth0.jwk`,
     * Convierta la solicitud que debería ser 401 en 500,`/ws` El apretón de manos también se volvió "incapaz de conectarse".
     */
    private fun Application.configureSecurity(jwtSecret: String) {
        val algorithm = Algorithm.HMAC256(jwtSecret)
        install(Authentication) {
            installHostAuthProvider(
                providerName = AUTH_JWT_NAME,
                realm = REALM,
                algorithm = algorithm,
                cookieName = TOKEN_COOKIE_NAME,
            )
        }
    }

    /** JSON Configuración de serialización(Reutilizar AppJson,Ignorar campos desconocidos). */
    private fun Application.configureSerialization() {
        install(ContentNegotiation) {
            json(AppJson)
        }
    }

    /**
     * CORS configuración.
     * R-SEC-03: Solo se permiten fuentes nativas de forma predeterminada;Se permite cualquier origen solo después de que se habilite el acceso a la LAN.
     */
    private fun Application.configureCors(allowLan: Boolean) {
        install(CORS) {
            // Límite de seguridad:anyHost() Solo aplicable al modelo de confianza LAN. Cuando se expone a la red pública, es necesario configurar la lista blanca de Origin.
            if (allowLan) {
                // Límite de seguridad:anyHost() Se aplica solo al modelo de confianza LAN
                anyHost()
            } else {
                allowHost("localhost", schemes = listOf("http", "https"))
                allowHost("127.0.0.1", schemes = listOf("http", "https"))
            }
            allowMethod(io.ktor.http.HttpMethod.Get)
            allowMethod(io.ktor.http.HttpMethod.Post)
            allowMethod(io.ktor.http.HttpMethod.Options)
            allowHeader(io.ktor.http.HttpHeaders.ContentType)
            allowHeader(io.ktor.http.HttpHeaders.Authorization)
        }
    }

    /** Manejo de excepciones globales(Devolver respuesta de error JSON,Evite el texto plano predeterminado de Ktor). */
    private fun Application.configureStatusPages() {
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                if (cause is kotlin.coroutines.cancellation.CancellationException) throw cause
                Logger.e(TAG, "WebServer excepción no detectada: ${cause.message}")
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse("internal_error", cause.message ?: context.getString(R.string.webserver_unknown_error)),
                )
            }
        }
    }

    // ── enrutamiento ──────────────────────────────────────────────────────────────

    /**
     * Todas las definiciones de rutas API.
     * P2-13: Nuevo `POST /api/auth/pin-login`,Acepta 6 personas PIN,Emitido después de pasar la verificación. JWT,
     * y enviado a través de cookies,Web Las solicitudes posteriores del cliente no requieren PIN.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun Application.museRoutes(
        password: String,
        jwtSecret: String,
        pin: String,
        sessionRepo: SessionRepository,
        settings: SettingsRepository,
        allowLan: Boolean,
    ) {
        val algorithm = Algorithm.HMAC256(jwtSecret)

        routing {
            installChannelWebhookRoutes(context, channelManager)

            // chequeo de salud(No se requiere autenticación,Para la detección de clientes mDNS)
            // C-18: Habilitar acceso LAN(bind 0.0.0.0)Campo de retorno de convergencia — número de versión/El tiempo de ejecución pertenece a la información precisa de la huella digital.,
            //   Al mismo tiempo, puede ser detectado por un tercero dentro de la red de área local.,Sólo en esta máquina/Regresar en modo de depuración no LAN;El estado de salud en sí está siempre disponible.
            get("/api/health") {
                // M11: cotización directa this@WebServer.startedAt,Evite las variables locales en start() capturado antes 0
                val health = if (allowLan) {
                    HealthResponse(status = "ok")
                } else {
                    HealthResponse(
                        status = "ok",
                        version = BuildConfig.VERSION_NAME,
                        uptime = System.currentTimeMillis() - this@WebServer.startedAt,
                    )
                }
                call.respond(health)
            }

            // Cerrar sesión: A-10 JS no puede eliminar la cookie httpOnly del front-end,Debe ser borrado por la Set-Cookie caducada del lado del servidor.
            // No se requiere autenticación(Al iniciar sesión, el navegador muestra automáticamente Cookie,Llamar sin iniciar sesión también devuelve un éxito idempotente.),No participa en la limitación de tarifas.
            post("/api/auth/logout") {
                call.expireTokenCookie()
                call.respond(LogoutResponse(loggedOut = true))
            }

            // Iniciar sesión: cambio de contraseña JWT
            post("/api/auth/login") {
                // H5: límite de tasa — Hasta 5 intentos fallidos cada 30 segundos por IP
                val clientIp = call.request.local.remoteHost
                if (!checkRateLimit(clientIp)) {
                    val remaining = getRateLimitRemainingSeconds(clientIp)
                    call.respond(
                        HttpStatusCode.TooManyRequests,
                        ErrorResponse("rate_limited", context.getString(R.string.webserver_rate_limited_remaining, remaining)),
                    )
                    return@post
                }
                val req = resultOf { call.receive<LoginRequest>() }
                    .onError { msg, _ ->
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("bad_request", context.getString(R.string.webserver_bad_request, msg)),
                        )
                    }.getOrNull()
                if (req == null) return@post
                if (!MessageDigest.isEqual(req.password.toByteArray(Charsets.UTF_8), password.toByteArray(Charsets.UTF_8))) {
                    recordFailedAttempt(clientIp)
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorResponse("auth_failed", context.getString(R.string.webserver_auth_failed)),
                    )
                    return@post
                }
                clearAttempts(clientIp)
                val token = issueJwt(algorithm)
                // C-29: El inicio de sesión con contraseña también se emite a través de Set-Cookie JWT,Igual que el inicio de sesión con PIN,Evite la entrada de token en la consulta/URL.
                //   El cuerpo de la respuesta aún devuelve el token para clientes que no son navegadores.(como guión)tomar;Web Elimine la cookie httpOnly.
                call.appendTokenCookie(token)
                call.respond(LoginResponse(token = token, expiresIn = TOKEN_TTL_MS / 1000))
            }

            // P2-13: PIN Iniciar sesión — 6 cambio de PIN JWT,Establecer simultáneamente Cookie
            post("/api/auth/pin-login") {
                // H5: límite de tasa — Hasta 5 intentos fallidos cada 30 segundos por IP
                val clientIp = call.request.local.remoteHost
                if (!checkRateLimit(clientIp)) {
                    val remaining = getRateLimitRemainingSeconds(clientIp)
                    call.respond(
                        HttpStatusCode.TooManyRequests,
                        ErrorResponse("rate_limited", context.getString(R.string.webserver_rate_limited_remaining, remaining)),
                    )
                    return@post
                }
                val req = resultOf { call.receive<PinLoginRequest>() }
                    .onError { msg, _ ->
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("bad_request", context.getString(R.string.webserver_bad_request, msg)),
                        )
                    }.getOrNull()
                if (req == null) return@post
                if (
                    !pin.matches(PIN_REGEX) ||
                    !MessageDigest.isEqual(
                        req.pin.toByteArray(Charsets.UTF_8),
                        pin.toByteArray(Charsets.UTF_8),
                    )
                ) {
                    recordFailedAttempt(clientIp)
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("auth_failed", context.getString(R.string.webserver_pin_error)))
                    return@post
                }
                clearAttempts(clientIp)
                val token = issueJwt(algorithm)
                // C-29: token Enviado únicamente a través de cookies,No entrar query/URL;Web El navegador del cliente lo transporta automáticamente con solicitudes posteriores.
                call.appendTokenCookie(token)
                call.respond(LoginResponse(token = token, expiresIn = TOKEN_TTL_MS / 1000))
            }

            // Host Mode: El navegador sólo es responsable de la visualización,Android ChatViewModel Responsable de generación, herramientas, memoria y persistencia.
            // WebSocket El protocolo de enlace reutiliza la autenticación de autorización/galleta JWT y no abre canales en tiempo real no autenticados.
            authenticate(AUTH_JWT_NAME) {
                webSocket("/ws") {
                    // El protocolo de enlace ha pasado la autenticación para llegar aquí; escriba la fuente y si se trae la cookie,
                    // La próxima vez que "la página web no se pueda conectar", podrá saber directamente en el registro si se trata de una falla de autenticación o de una falla de red.
                    Logger.i(
                        TAG,
                        "/ws Creado: remote=${call.request.origin.remoteHost} " +
                            "cookie=${if (call.request.cookies[TOKEN_COOKIE_NAME] != null) "si" else "Ninguno"}",
                    )
                    hostWebSocketGateway.serve(this)
                }

                // medios de noticias(fotos/Voz):Mismo bloque de autenticación que /ws,solo lectura,token Regístrese al enviar un mensaje.
                installHostMediaRoutes(hostMediaStore)

                // Las siguientes rutas REST requieren autenticación JWT
                // Lista de conversación
                get("/api/sessions") {
                    // M12: Flow.first() Agregar tiempo de espera,Evite bloquear solicitudes durante largos periodos de tiempo
                    val sessions = withTimeout(5_000L) { sessionRepo.observeSessions().first() }
                    call.respond(sessions.map { it.toDto() })
                }

                // Lista de mensajes de conversación
                get("/api/sessions/{id}/messages") {
                    val id = call.parameters["id"]
                    if (id.isNullOrBlank()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("bad_request", context.getString(R.string.webserver_missing_session_id)),
                        )
                        return@get
                    }
                    // M12: Flow.first() Agregar tiempo de espera
                    val messages = withTimeout(5_000L) { sessionRepo.observeMessages(id).first() }
                    call.respond(messages.map { it.toDto() })
                }

                // Resumen de configuración(Provider Nombre + número de modelos,Desensibilización clave)
                get("/api/settings") {
                    // M12: Flow.first() Agregar tiempo de espera
                    val providers = withTimeout(5_000L) { settings.providersFlow.first() }
                    val activeId = withTimeout(5_000L) { settings.activeProviderIdFlow.first() }
                    val modelId = withTimeout(5_000L) { settings.selectedModelIdFlow.first() }
                    call.respond(
                        SettingsSummary(
                            providers = providers.map { it.toSummaryDto() },
                            activeProviderId = activeId,
                            selectedModelId = modelId,
                        ),
                    )
                }

                // v1.120: POST /api/send Decisiones de diseño de endpoints — No implementado.
                //   WebServer Orientar la navegación de solo lectura(sesión/noticias/ajustes),No se admite el inicio de mensajes nuevos desde la web.
                //   Razón:Para enviar un mensaje, debe llamar a la lógica ChatViewModel.sendMessage,Implica respuesta de transmisión de LLM +
                //   SessionRepository Biblioteca de escritura + programación de herramientas,La cadena de dependencia es pesada,Y el lado web carece de una solución de reenvío SSE de transmisión.,
                //   Forzar la implementación puede introducir fácilmente nuevos errores. Si necesitas enviar un mensaje vía Web,Se recomienda diseñar el canal WebSocket por separado en el futuro.
            }

            // React Host UI prioridad;APK Recurra a la página de diagnóstico integrada al crear el producto sin web.
            get("/assets/{path...}") {
                val path = call.parameters.getAll("path")?.joinToString("/").orEmpty()
                if (!servePackagedAsset(call, "muse-web/assets/$path")) {
                    call.respond(HttpStatusCode.NotFound)
                }
            }
            get("/sw.js") {
                if (!servePackagedAsset(call, "muse-web/sw.js")) call.respond(HttpStatusCode.NotFound)
            }
            get("/manifest.webmanifest") {
                if (!servePackagedAsset(call, "muse-web/manifest.webmanifest")) call.respond(HttpStatusCode.NotFound)
            }
            get("/icon.svg") {
                if (!servePackagedAsset(call, "muse-web/icon.svg")) call.respond(HttpStatusCode.NotFound)
            }
            // iOS No reconocer iconos SVG:PWA La compilación también producirá archivos PNG, a los que se debe poder acceder cuando el escritorio esté montado en la pared; de lo contrario, serán reemplazados por capturas de pantalla.
            get("/apple-touch-icon.png") {
                if (!servePackagedAsset(call, "muse-web/apple-touch-icon.png")) call.respond(HttpStatusCode.NotFound)
            }
            get("/icon-192.png") {
                if (!servePackagedAsset(call, "muse-web/icon-192.png")) call.respond(HttpStatusCode.NotFound)
            }
            get("/icon-512.png") {
                if (!servePackagedAsset(call, "muse-web/icon-512.png")) call.respond(HttpStatusCode.NotFound)
            }
            get("/") {
                // L1: Agregar encabezado de política de referencia,Evite que el parámetro de consulta JWT se filtre a terceros a través del encabezado Referer
                call.response.headers.append("Referrer-Policy", "no-referrer")
                if (!servePackagedAsset(call, "muse-web/index.html")) {
                    call.respondText(WebServerUi.indexHtml(context), ContentType.Text.Html)
                }
            }

            // ── v2.x: A2A Protocolo estándar (Agent-to-Agent)──
            // Agent Card: Metadatos públicos, insensibles, no se requiere autenticación (para descubrimiento de agentes externos).
            get(io.zer0.muse.web.a2a.AGENT_CARD_PATH) {
                val base = "${if (currentHttpsEnabled) "https" else "http"}://${call.request.local.localHost}:$currentPort"
                call.respond(a2aHandler.agentCard(base))
            }
            // JSON-RPC Punto final: enviar mensaje/Las tareas de consulta requieren autenticación (igual que /ws JWT).
            authenticate(AUTH_JWT_NAME) {
                post(io.zer0.muse.web.a2a.A2A_PATH) {
                    val raw = runCatching { call.receiveText() }.getOrNull().orEmpty()
                    call.respond(handleA2aJsonRpc(raw))
                }
            }
        }
    }

    /** Emitido con validez de 24h JWT,Comparte la misma clave de firma que el inicio de sesión con contraseña(password → HMAC-SHA256). */
    @Suppress("ReturnCount")
    private suspend fun servePackagedAsset(call: ApplicationCall, assetPath: String): Boolean {
        if (assetPath.contains("..") || assetPath.startsWith("/") || assetPath.contains("\\")) return false
        val bytes = resultOf { context.assets.open(assetPath).use { it.readBytes() } }.getOrNull() ?: return false
        val contentType = when {
            assetPath.endsWith(".html", ignoreCase = true) -> ContentType.Text.Html
            assetPath.endsWith(".js", ignoreCase = true) -> ContentType.Application.JavaScript
            assetPath.endsWith(".css", ignoreCase = true) -> ContentType.Text.CSS
            assetPath.endsWith(".json", ignoreCase = true) -> ContentType.Application.Json
            assetPath.endsWith(".svg", ignoreCase = true) -> ContentType.Image.SVG
            assetPath.endsWith(".png", ignoreCase = true) -> ContentType.Image.PNG
            assetPath.endsWith(".webmanifest", ignoreCase = true) -> ContentType.parse("application/manifest+json")
            else -> ContentType.Application.OctetStream
        }
        call.respondBytes(bytes, contentType)
        return true
    }

    /**
     * v2.x: Manejar solicitudes A2A JSON-RPC.
     *
     * Métodos admitidos:SendMessage / GetTask / CancelTask.
     * Devuelve una cadena JSON (escribe la respuesta directamente para evitar el procesamiento adicional de JsonElement por parte del serializador Ktor).
     */
    @Suppress("TooGenericExceptionCaught", "CyclomaticComplexMethod", "ReturnCount")
    private suspend fun handleA2aJsonRpc(raw: String): String {
        val decision = io.zer0.muse.web.a2a.A2aJsonRpc.classify(raw)
        decision.errorCode?.let { code ->
            return a2aError(decision.envelopeId, code, decision.errorMessage ?: "invalid JSON-RPC request")
        }
        val method = decision.method ?: return a2aError(
            decision.envelopeId,
            io.zer0.muse.web.a2a.A2aErrors.INVALID_REQUEST,
            "missing method",
        )
        val idJson = decision.envelopeId ?: kotlinx.serialization.json.JsonNull
        return try {
            when (method) {
                "SendMessage", "message/send" -> {
                    val params = decision.params?.let {
                        runCatching { AppJson.decodeFromJsonElement(io.zer0.muse.web.a2a.MessageSendParams.serializer(), it) }
                            .getOrNull()
                    } ?: return a2aError(idJson, io.zer0.muse.web.a2a.A2aErrors.INVALID_PARAMS, "missing or invalid params")
                    val task = a2aHandler.sendMessage(params)
                    a2aResult(idJson, task)
                }
                "GetTask", "tasks/get" -> {
                    val taskId = io.zer0.muse.web.a2a.A2aJsonRpc.taskIdFrom(decision.params, decision.envelopeId)
                        ?: return a2aError(idJson, io.zer0.muse.web.a2a.A2aErrors.INVALID_PARAMS, "missing task id")
                    val task = a2aHandler.getTask(taskId)
                        ?: return a2aError(idJson, io.zer0.muse.web.a2a.A2aErrors.TASK_NOT_FOUND, "task not found")
                    a2aResult(idJson, task)
                }
                "CancelTask", "tasks/cancel" -> {
                    val taskId = io.zer0.muse.web.a2a.A2aJsonRpc.taskIdFrom(decision.params, decision.envelopeId)
                        ?: return a2aError(idJson, io.zer0.muse.web.a2a.A2aErrors.INVALID_PARAMS, "missing task id")
                    val task = a2aHandler.cancelTask(taskId)
                        ?: return a2aError(idJson, io.zer0.muse.web.a2a.A2aErrors.TASK_NOT_FOUND, "task not found or not cancelable")
                    a2aResult(idJson, task)
                }
                else -> a2aError(idJson, io.zer0.muse.web.a2a.A2aErrors.METHOD_NOT_FOUND, "method not found: $method")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w("WebServer", "A2A Error de procesamiento: ${e.message}", e)
            a2aError(idJson, io.zer0.muse.web.a2a.A2aErrors.INTERNAL_ERROR, e.message ?: "internal error")
        }
    }

    private fun a2aResult(id: kotlinx.serialization.json.JsonElement, result: io.zer0.muse.web.a2a.A2aTask): String =
        AppJson.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            kotlinx.serialization.json.buildJsonObject {
                put("jsonrpc", kotlinx.serialization.json.JsonPrimitive("2.0"))
                put("id", id)
                put("result", AppJson.encodeToJsonElement(io.zer0.muse.web.a2a.A2aTask.serializer(), result))
            },
        )

    private fun a2aError(id: kotlinx.serialization.json.JsonElement?, code: Int, message: String): String = AppJson.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        kotlinx.serialization.json.buildJsonObject {
            put("jsonrpc", kotlinx.serialization.json.JsonPrimitive("2.0"))
            put("id", id ?: kotlinx.serialization.json.JsonNull)
            put(
                "error",
                kotlinx.serialization.json.buildJsonObject {
                    put("code", kotlinx.serialization.json.JsonPrimitive(code))
                    put("message", kotlinx.serialization.json.JsonPrimitive(message))
                },
            )
        },
    )

    private fun issueJwt(algorithm: Algorithm): String {
        return JWT.create()
            .withSubject(CLAIM_SUB_VALUE)
            .withIssuedAt(Date())
            .withExpiresAt(Date(System.currentTimeMillis() + TOKEN_TTL_MS))
            .sign(algorithm)
    }

    /**
     * C-29: Enviado mediante cookie httpOnly JWT(No entrar query/URL,Evite entrar en el historial del navegador/Referer/registro de acceso).
     * v2.x: Habilitar la configuración HTTPS secure=true(Devolución de datos solo a través de TLS);De lo contrario mantener false(JWT Solo modelo de confianza LAN).
     * A-10/C-11: Ktor 3 Ningún parámetro independiente del mismo sitio,Escrito mediante extensiones `SameSite=Lax`,
     *   Mitigar el riesgo de que las solicitudes entre sitios transporten cookies en escenarios CSRF(El inicio de sesión solo se solicita a través del mismo origen del navegador.).
     */
    private fun ApplicationCall.appendTokenCookie(token: String) {
        response.cookies.append(
            name = TOKEN_COOKIE_NAME,
            value = token,
            maxAge = TOKEN_TTL_MS / 1000,
            path = "/",
            httpOnly = true,
            secure = currentHttpsEnabled,
            extensions = mapOf("SameSite" to "Lax"),
        )
    }

    /**
     * A-10: Caduca al cerrar sesión `token` Cookie(Set-Cookie: token=; Max-Age=0; Path=/),
     * porque debería Cookie httpOnly=true,El navegador JS no puede leer/Eliminar,El comando de caducidad debe ser emitido por el servidor.
     * SameSite La propiedad no tiene significado práctico en las cookies caducadas,Pero manténgala coherente con la cookie de inicio de sesión en caso de diferencias de análisis.
     */
    private fun ApplicationCall.expireTokenCookie() {
        response.cookies.append(
            name = TOKEN_COOKIE_NAME,
            value = "",
            maxAge = 0L,
            path = "/",
            httpOnly = true,
            secure = currentHttpsEnabled,
            extensions = mapOf("SameSite" to "Lax"),
        )
    }

    // ── DTO Convertir ──────────────────────────────────────────────────────────

    private fun SessionEntity.toDto(): SessionDto = SessionDto(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastMessagePreview = lastMessagePreview,
        assistantId = assistantId,
        pinned = pinned,
    )

    private fun UIMessage.toDto(): MessageDto = MessageDto(
        id = id.toString(),
        role = role.name,
        content = content,
        reasoning = reasoning,
        modelId = modelId,
        createdAt = createdAt,
    )

    private fun io.zer0.ai.core.ProviderConfig.toSummaryDto(): ProviderSummaryDto = ProviderSummaryDto(
        id = id,
        name = displayName,
        type = type.name,
        modelCount = models.size,
        enabled = enabled,
    )

    // ── DTO clase de datos ────────────────────────────────────────────────────────

    data class Status(
        val isRunning: Boolean,
        val port: Int,
        val hasPassword: Boolean,
        val hasPin: Boolean,
        val passwordMasked: String,
        val pinMasked: String,
        val startedAt: Long,
        val lastError: String? = null,
        /** v2.x: Si actualmente se está ejecutando a través de HTTPS. */
        val httpsEnabled: Boolean = false,
        /** v2.x: HTTPS Certificado autofirmado de huella dactilar SHA-256(Cuando HTTPS no está habilitado null). */
        val fingerprint: String? = null,
    )

    @Serializable
    data class HealthResponse(val status: String, val version: String? = null, val uptime: Long? = null)

    @Serializable
    data class LoginRequest(val password: String)

    /** P2-13: PIN Cuerpo de solicitud de inicio de sesión(6 dígitos PIN). */
    @Serializable
    data class PinLoginRequest(val pin: String)

    @Serializable
    data class LoginResponse(val token: String, val expiresIn: Long)

    /** A-10: Respuesta de cierre de sesión(La interfaz usa esto para confirmar que la cookie se ha borrado.). */
    @Serializable
    data class LogoutResponse(val loggedOut: Boolean)

    @Serializable
    data class SessionDto(
        val id: String,
        val title: String,
        val createdAt: Long,
        val updatedAt: Long,
        val lastMessagePreview: String,
        val assistantId: String,
        val pinned: Boolean,
    )

    @Serializable
    data class MessageDto(
        val id: String,
        val role: String,
        val content: String,
        val reasoning: String?,
        val modelId: String?,
        val createdAt: Long,
    )

    @Serializable
    data class ProviderSummaryDto(
        val id: String,
        val name: String,
        val type: String,
        val modelCount: Int,
        val enabled: Boolean,
    )

    @Serializable
    data class SettingsSummary(
        val providers: List<ProviderSummaryDto>,
        val activeProviderId: String?,
        val selectedModelId: String?,
    )

    @Serializable
    data class ErrorResponse(val error: String, val message: String)

    companion object {
        private const val TAG = "WebServer"
        private const val LAN_BIND_HOST = "0.0.0.0"
        private const val LOCAL_BIND_HOST = "127.0.0.1"

        /** R-SEC-03: El valor predeterminado es solo local,Solo vincule todas las tarjetas de red después de habilitar explícitamente el acceso a la LAN. */
        fun bindHost(allowLan: Boolean): String = if (allowLan) LAN_BIND_HOST else LOCAL_BIND_HOST
        private const val MIN_PORT = 1024
        private const val MAX_PORT = 65535
        private const val GRACE_PERIOD_MS = 1000L
        private const val TIMEOUT_PERIOD_MS = 2000L
        private const val AUTH_JWT_NAME = "auth-jwt"

        /** Nombre del directorio donde se colocan las imágenes de los mensajes,con [io.zer0.muse.data.session.MessageImageStore] El directorio de almacenamiento es consistente. */
        private const val IMAGE_MEDIA_DIR = "muse_images"

        /** Nombre del directorio del archivo de voz,con [io.zer0.muse.ui.speech.VoiceMessageStore] El directorio de almacenamiento es consistente. */
        private const val VOICE_MEDIA_DIR = "voice_messages"

        /** Nombre del directorio del avatar del asistente,con [io.zer0.muse.data.AvatarStorage] El directorio de almacenamiento es consistente. */
        private const val AVATAR_MEDIA_DIR = "avatar"
        private const val REALM = "muse-web"

        /** con [HOST_AUTH_SUBJECT] El mismo valor: emisión y verificación deben ser consistentes. */
        private const val CLAIM_SUB_VALUE = HOST_AUTH_SUBJECT
        private const val TOKEN_TTL_MS = 24L * 60 * 60 * 1000 // 24 horas

        // P2-13: Web Relacionado con la seguridad del PIN del terminal
        /** PIN/Nombre de cookie emitido después de iniciar sesión con contraseña,Web El navegador del cliente lo llevará automáticamente. */
        private const val TOKEN_COOKIE_NAME = HOST_AUTH_COOKIE

        /** 6 Código de verificación PIN de dígitos. */
        private val PIN_REGEX = Regex("^\\d{6}$")

        // H5: Límite de tasa de inicio de sesión — Hasta 5 intentos fallidos cada 30 segundos por IP,Regresar más allá del límite 429
        private const val RATE_LIMIT_WINDOW_MS = 30_000L
        private const val RATE_LIMIT_MAX_FAILURES = 5
        private val loginAttempts = ConcurrentHashMap<String, AttemptTracker>()

        /** rastreador de límite de tasa:Registre el número de fallas y el tiempo del primer intento. */
        private class AttemptTracker(var count: Int, var firstAttemptAt: Long)

        /** Compruebe si se excede el límite de tarifa(Devuelve verdadero para permitir la solicitud.). */
        private fun checkRateLimit(ip: String): Boolean {
            val now = System.currentTimeMillis()
            // Limpiar el vencimiento más allá de la ventana de los 30 entry,Evite las pérdidas de memoria causadas por la acumulación permanente de direcciones IP que nunca han iniciado sesión correctamente
            loginAttempts.entries.removeIf { (_, attempt) ->
                now - attempt.firstAttemptAt > RATE_LIMIT_WINDOW_MS
            }
            val tracker = loginAttempts[ip] ?: return true
            synchronized(tracker) {
                if (WebServerAuthPolicy.isWindowExpired(now, tracker.firstAttemptAt, RATE_LIMIT_WINDOW_MS)) {
                    tracker.count = 0
                    tracker.firstAttemptAt = now
                    return true
                }
                return !WebServerAuthPolicy.isRateLimited(
                    now,
                    tracker.firstAttemptAt,
                    tracker.count,
                    RATE_LIMIT_WINDOW_MS,
                    RATE_LIMIT_MAX_FAILURES,
                )
            }
        }

        /** Registra un intento fallido. */
        private fun recordFailedAttempt(ip: String) {
            val now = System.currentTimeMillis()
            val tracker = loginAttempts.getOrPut(ip) { AttemptTracker(0, now) }
            synchronized(tracker) {
                if (WebServerAuthPolicy.isWindowExpired(now, tracker.firstAttemptAt, RATE_LIMIT_WINDOW_MS)) {
                    tracker.count = 1
                    tracker.firstAttemptAt = now
                } else {
                    tracker.count++
                }
            }
        }

        /** Borrar el registro de fallas de la IP especificada(Llamado cuando el inicio de sesión es exitoso). */
        private fun clearAttempts(ip: String) {
            loginAttempts.remove(ip)
        }

        /** Devuelve los segundos de bloqueo restantes para la IP actual(Volver desbloqueado 0). */
        private fun getRateLimitRemainingSeconds(ip: String): Long {
            val now = System.currentTimeMillis()
            val tracker = loginAttempts[ip] ?: return 0L
            synchronized(tracker) {
                return WebServerAuthPolicy.remainingSeconds(now, tracker.firstAttemptAt, RATE_LIMIT_WINDOW_MS)
            }
        }
    }
}
