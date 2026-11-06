@file:Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod")

package io.zer0.muse

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import io.zer0.common.Logger
import io.zer0.muse.crash.MuseCrashHandler
import io.zer0.muse.data.AppearanceSettingsStore
import io.zer0.muse.data.ChatPreferences
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantCardImportBridge
import io.zer0.muse.intent.ShareIntentHandler
import io.zer0.muse.ui.ChatViewModel
import io.zer0.muse.ui.MuseRoutes
import io.zer0.muse.ui.SafeModeScreen
import io.zer0.muse.ui.common.feedback.MuseToastHost
import io.zer0.muse.ui.common.media.DesktopShortcuts
import io.zer0.muse.ui.common.media.rememberDesktopShortcutsEnabled
import io.zer0.muse.ui.navigation.AssistantsRoute
import io.zer0.muse.ui.navigation.ChatDetailRoute
import io.zer0.muse.ui.navigation.HomeRoute
import io.zer0.muse.ui.navigation.KnowledgeBaseManageRoute
import io.zer0.muse.ui.navigation.KnowledgeRoute
import io.zer0.muse.ui.navigation.MuseTransitions
import io.zer0.muse.ui.navigation.QuickNoteRoute
import io.zer0.muse.ui.navigation.QuickNotesRoute
import io.zer0.muse.ui.navigation.ScheduledTaskRoute
import io.zer0.muse.ui.navigation.ScheduledTasksRoute
import io.zer0.muse.ui.navigation.SettingsAboutRoute
import io.zer0.muse.ui.navigation.SettingsAgentRoute
import io.zer0.muse.ui.navigation.SettingsCloudBackupRoute
import io.zer0.muse.ui.navigation.SettingsDataRoute
import io.zer0.muse.ui.navigation.SettingsMemoryRoute
import io.zer0.muse.ui.navigation.SettingsModelRoute
import io.zer0.muse.ui.navigation.SettingsRoute
import io.zer0.muse.ui.navigation.TranslateRoute
import io.zer0.muse.ui.navigation.assistantNavGraph
import io.zer0.muse.ui.navigation.chatNavGraph
import io.zer0.muse.ui.navigation.settingsNavGraph
import io.zer0.muse.ui.navigation.toolsNavGraph
import io.zer0.muse.ui.onboarding.OnboardingScreen
import io.zer0.muse.ui.quicknotes.QuickCaptureEdgeOverlay
import io.zer0.muse.ui.quicknotes.QuickCaptureOverlayService
import io.zer0.muse.ui.quicknotes.QuickNotesViewModel
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MuseTheme
import io.zer0.muse.ui.theme.loadCustomFontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.compose.koinInject
import org.koin.core.context.GlobalContext

/**
 * Actividad solo de la aplicación.
 *
 * Responsabilidades:
 *  - Instalación SplashScreen(warm-paper fondo + muse logo)
 *  - Solicitar permisos de tiempo de ejecución(POST_NOTIFICATIONS / Grabación/Almacenamiento)
 *  - Registrarse NavHost,Reúna todas las rutas de la página.(ver [io.zer0.muse.ui.MuseRoutes])
 *  - manejar compartir/Deep Link Intent(poner texto externo/Fotos entregadas a ChatViewModel)
 *  - Escuchar tema/Cambio de tamaño de fuente,aplicar a MuseTheme
 *
 * Animación de transición de página:Página de inicio utilizada entre pestañas fade,Deslízate hacia la derecha en la página de detalles/Desliza hacia la izquierda(transición de empuje deslizante).
 */

class MainActivity : ComponentActivity() {

    /** Phase 8.10: Compartir/Deep Link Procesador(SAF Análisis requerido Context). */
    private val shareIntentHandler: ShareIntentHandler by lazy {
        ShareIntentHandler(applicationContext)
    }

    /** Phase 8.10: El resultado del procesamiento de la intención actual que se va a consumir.(Observado por NavGraph). */
    private var pendingShareResult by mutableStateOf<ShareIntentHandler.ShareResult>(
        ShareIntentHandler.ShareResult.None,
    )

    /** Solicitud de permiso de notificación launcher(Android 13+ Permiso de tiempo de ejecución POST_NOTIFICATIONS requerido). */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Logger.i("MainActivity", "POST_NOTIFICATIONS granted=$granted")
        }

    /**
     * v1.102: Ajuste el contexto con la preferencia de idioma almacenada en adjuntoBaseContext.
     *
     * Compose de [androidx.compose.ui.res.stringResource] La lectura es de la actividad. Resources,
     * Su configuración está determinada por baseContext.[androidx.appcompat.app.AppCompatDelegate.setApplicationLocales]
     * en ComponentActivity(no AppCompatActivity)Backport no tiene efecto en Android 12 y versiones anteriores,
     * Entonces aquí usamos manual. [android.content.Context.createConfigurationContext] Anular la configuración.
     *
     * [recreate] Se creará una nueva instancia de Actividad y se llamará nuevamente attachBaseContext,
     * Leer automáticamente la última preferencia de idioma,No es necesario tratarlo en Compose(Evita el bucle infinito anterior.).
     *
     * runBlocking Leer DataStore: solo iniciar/recreate una vez cada vez,DataStore Tiene memoria caché,< 2ms.
     */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(wrapWithLanguage(newBase))
    }

    private fun wrapWithLanguage(base: android.content.Context): android.content.Context {
        // Koin Iniciado en Application.onCreate,attachBaseContext ejecutar después,Disponible en este momento.
        val settings = GlobalContext.getOrNull()?.get<SettingsRepository>() ?: return base
        // v1.131: Utilice SharedPreferences para sincronizar el caché en su lugar,Elimine el riesgo ANR de la ejecución del hilo principal, bloqueando la lectura del almacén de datos.
        // Los valores históricos de DataStore se migran de forma asincrónica a SP,El primer arranque en frío puede volver brevemente a los valores predeterminados.,
        // Pero será correcto la próxima vez que lo inicies.,aceptable(Los usuarios no perciben la diferencia en el cambio de idioma).
        val lang = settings.getLanguageSync()
        // I4: El análisis del lenguaje converge a AppLocales,system/Regreso desconocido null(Sigue el sistema).
        val locale = io.zer0.muse.util.AppLocales.parse(lang) ?: return base
        // v1.133: Copie la configuración actual y sobrescríbala locale,Evite modificar la configuración global.
        // También establecido explícitamente layoutDirection,Evite ciertos escenarios de límites RTL/LTR.
        val config = android.content.res.Configuration(base.resources.configuration)
        config.setLocale(locale)
        // v1.133: setLayoutDirection Ten todo en cuenta,setLocale no está vinculado en algunas ROM layoutDirection
        config.setLayoutDirection(locale)
        // v1.133: Cree un nuevo contexto con createConfigurationContext.
        // Llamadas adicionales a resources.updateConfiguration para averiguarlo,Parcialmente hecho en China ROM(MIUI/EMUI)si
        // createConfigurationContext La aplicación local es inestable.,Se requiere UpdateConfiguration para forzar una actualización.
        val newContext = base.createConfigurationContext(config)
        try {
            val wrappedResources = newContext.resources
            wrappedResources.updateConfiguration(config, wrappedResources.displayMetrics)
        } catch (e: Exception) {
            // updateConfiguration Algunas versiones nuevas están marcadas como obsoletas pero aún están disponibles,tolerancia a fallos ignorada
        }
        return newContext
    }

    // v2.6.2 fix(comentarios de los usuarios): Los colores claros y oscuros del sistema aparecen instantáneamente.
    // Nota: no puedo leer LocalConfiguration.current.uiMode —— RuntimeLocaleProvider usará
    // `LocalConfiguration provides config` Sobrescríbalo (esa configuración es una instantánea del idioma,
    // Solo se reconstruye cuando cambia el idioma y el sistema no se actualizará si cambia entre colores oscuros y claros).
    // Aquí, los cambios de uiMode del sistema se monitorean directamente y se desacoplan de la capa de configuración de Compose.
    //
    // v2.6.2 hotfix(Comentarios de los usuarios "Se bloquea al abrir"): debe by lazy —— Los inicializadores de propiedad ordinarios estarían en
    // Activity Ejecutado durante la construcción, es posible que adjuntoBaseContext no se complete en ese momento.
    // applicationContext Es posible que no esté disponible, lo que hace que onCreate falle antes.
    private val systemDarkState by lazy {
        androidx.compose.runtime.mutableStateOf(isSystemNightMode(this))
    }

    // v2.6.2 fix: uiMode Cambiar (sistema de color oscuro y claro)/Modo nocturno) estado de actualización.
    // Manifest Los configChanges contienen uiMode,Activity Sin reconstruir, Compose debe ser notificado manualmente.
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        systemDarkState.value =
            (newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    // Activity Iniciar inicialización(barra del sistema/modo seguro/Permisos de notificación/fuente/Temas, etc.)es una estructura de agregación inherente a nivel de pantalla,
    // El número de filas supera ligeramente el umbral de detekt LongMethod 1,La inversión dividida daña la legibilidad
    @Suppress("LongMethod")
    override fun onCreate(savedInstanceState: Bundle?) {
        // v1.7: El sistema debe instalarse antes de super.onCreate SplashScreen
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Desactivar el autocompletar del sistema globalmente: las islas de entrada de chat se considerarán erróneamente campos que se pueden completar mediante el autocompletar.
        // Entonces, el sistema dibuja una fila de sugerencias de relleno encima del área de entrada y muestra burbujas para cubrir el texto del mensaje.
        // Esta aplicación no tiene ninguna cuenta./Contraseña/Semántica del formulario de dirección, sin pérdida de funcionalidad después del cierre.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            window.decorView.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }

        // v1.7: Controlar la duración de la visualización de SplashScreen,Una vez completada la inicialización de la aplicación, espere 1,2 segundos y luego salga.
        var splashReady by mutableStateOf(false)
        splashScreen.setKeepOnScreenCondition { !splashReady }

        // Solicitar permiso de tiempo de ejecución POST_NOTIFICATIONS(Android 13+ requerido,De lo contrario no se mostrará la notificación.)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        // Phase 8.10: Proceso Safe Mode(último accidente)→ Saltar arranque completo,Mostrar sólo mensajes de fallo
        if (MuseCrashHandler.checkSafeMode(applicationContext)) {
            Logger.w("MainActivity", "Safe mode active — last crash detected")
            splashReady = true
            setContent {
                MuseTheme(darkTheme = isSystemInDarkTheme()) {
                    SafeModeScreen()
                }
            }
            return
        }
        // Phase 8.10: inicio de procesamiento Intent(Compartir/Deep Link/widgets de escritorio)
        // Phase 12: El inicio rápido del widget de escritorio tiene prioridad sobre el uso compartido/Deep Link analizar
        // M4: shareIntentHandler.handle() ha sido cambiado a suspend,Procesamiento asincrónico en corrutinas,Evite bloquear el hilo principal.
        // Launcher Los atajos tienen la máxima prioridad:No entra en conflicto con el widget/compartir(Basado en action,en vez de extra/data)
        val shortcutResult = shortcutActionResult(intent)
        val widgetResult = widgetActionResult(intent)
        when {
            shortcutResult != null -> pendingShareResult = shortcutResult
            widgetResult != null -> pendingShareResult = widgetResult
            else -> lifecycleScope.launch {
                pendingShareResult = shareIntentHandler.handle(intent)
            }
        }
        // v1.91-hotfix: control defensivo — Koin Puede ser porque el proceso no se ha reiniciado(Por ejemplo, SafeModeScreen borrado flag
        // Después de terminar, Affinity no mata el proceso.,o el propio startKoin falla)y no inicializado.
        // En este momento by inject() activará SynchronizedLazyImpl.getValue → GlobalContext.get()
        // → colapsar "KoinApplication has not been started".
        // Tome la ruta del Modo seguro cuando se detecte que Koin no está iniciado,Evite fallas y guíe a los usuarios hacia la recuperación.
        if (GlobalContext.getOrNull() == null) {
            Logger.e("MainActivity", "Koin not started — showing SafeModeScreen (process not restarted or startKoin failed)")
            splashReady = true
            setContent {
                MuseTheme(darkTheme = isSystemInDarkTheme()) {
                    SafeModeScreen()
                }
            }
            return
        }
        val settings: SettingsRepository by inject()
        // v1.102: locale La aplicación es manejada por adjuntoBaseContext + wrapWithLanguage(Cubre todas las versiones de Android),
        // Ya no se llama a onCreate applyLanguage(setApplicationLocales en ComponentActivity
        // Android 12 y los siguientes backports no tienen efecto).

        setContent {
            val startupReady by (application as MuseApp).startupReady.collectAsStateWithLifecycle()
            if (!startupReady) {
                MuseTheme(darkTheme = isSystemInDarkTheme()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                // I4: Cambio de idioma en caliente — Recopilar flujos de idiomas,Anulación de recursos de Compose a través de RuntimeLocaleProvider,
                // Cambiar idioma solo reestructurar UI,no reconstruir Activity(El lenguaje inicial de arranque en frío todavía está garantizado por adjuntoBaseContext).
                val language by settings.languageFlow.collectAsStateWithLifecycle(initialValue = "system")
                RuntimeLocaleProvider(lang = language) {
                    // v2.6.2 fix(comentarios de los usuarios): Sistema de colores claros y oscuros. —— no leer LocalConfiguration
                    // (Será sobrescrito por la instantánea del idioma de RuntimeLocaleProvider), use la capa Actividad en su lugar
                    // Escuche systemDarkState de onConfigurationChanged.
                    val systemDark = systemDarkState.value
                    val themeMode by settings.themeModeFlow.collectAsStateWithLifecycle(initialValue = "system")
                    val darkTheme = when (themeMode) {
                        "light" -> false
                        "dark" -> true
                        else -> systemDark
                    }
                    // Reparación:initialValue Cambiar a "mono" Consistente con el valor predeterminado de SettingsRepository.themeIdFlow,
                    // Evite renderizar el tema Warm_paper en el primer fotograma durante el inicio en frío y luego cambiar a mono, lo que provocará que el tema parpadee.
                    val themeId by settings.themeIdFlow.collectAsStateWithLifecycle(initialValue = AppearanceSettingsStore.DEFAULT_THEME_ID)
                    // v1.0.92: El tema independiente del modo oscuro ha estado desconectado — Cadena vacía fija(Sigue la versión oscura del tema claro.),No más lectura de valores de acciones
                    // v1.65: Interruptor de selección de color dinámico(Android 12+,El código ya está listo.,Los parámetros no se han pasado antes y nunca estarán disponibles.)
                    val dynamicColor by settings.dynamicColorFlow.collectAsStateWithLifecycle(initialValue = false)
                    val fontSizeScale by settings.fontSizeScaleFlow.collectAsStateWithLifecycle(initialValue = "medium")
                    // v1.97 gap7: Lista de temas definidos por el usuario — Generar según el color de la semilla. ColorScheme,
                    // Se utiliza como capa alternativa entre colores dinámicos y temas predeterminados en MuseTheme.
                    val customThemes by settings.customThemesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
                    // H5: Cambio de tema de alto contraste(Mejorar las perspectivas/contraste de fondo,Para usuarios con baja visión)
                    val highContrast by settings.highContrastFlow.collectAsStateWithLifecycle(initialValue = false)
                    // E2: Fuente de texto personalizada — camino→FontFamily Cargar caché en recordar(Sincronice el disco IO para evitar duplicaciones)
                    val customFontPath by settings.customFontPathFlow.collectAsStateWithLifecycle(initialValue = null)
                    val bodyFontFamily by remember(customFontPath) { mutableStateOf(loadCustomFontFamily(customFontPath)) }
                    // I4: Los recursos de redacción han sido cubiertos por RuntimeLocaleProvider después del cambio de idioma en caliente,
                    // ya no es necesario recreate;La inicialización del arranque en frío todavía está garantizada por adjuntoBaseContext.
                    // v2.5.0: Instantánea global de la configuración del vidrio líquido —— Todas las superficies suspendidas de aplicación.(isla dinglan/entra a la isla/botón/Burbujas)consumo.
                    val glassModeRaw by settings.liquidGlassModeFlow.collectAsStateWithLifecycle(initialValue = "off")
                    val glassStrength by settings.liquidGlassStrengthFlow.collectAsStateWithLifecycle(initialValue = 55)
                    val glassConfig = io.zer0.muse.ui.theme.LiquidGlassConfig(
                        style = io.zer0.muse.ui.theme.LiquidGlassConfig.styleFrom(glassModeRaw),
                        strength = glassStrength / 100f,
                        mode = io.zer0.muse.ui.theme.LiquidGlassConfig.modeFrom(glassModeRaw),
                    )
                    // v2.6: La fuente de fondo de cristal se coloca dentro de cada página.(Ver ChatScreen solo incluye imágenes de fondo/gradiente),
                    // Sólo la configuración se entrega globalmente. Si la capa global cubre todo NavGraph,se formará con componentes internos de vidrio
                    // recursión de renderizado → RenderThread desbordamiento de pila(SIGSEGV).
                    androidx.compose.runtime.CompositionLocalProvider(
                        io.zer0.muse.ui.theme.LocalLiquidGlass provides glassConfig,
                        io.zer0.muse.ui.theme.LocalLayerBackdrop provides null,
                        io.zer0.muse.ui.theme.LocalWaterGlassState provides null,
                    ) {
                        MuseTheme(
                            darkTheme = darkTheme,
                            themeId = themeId,
                            darkThemeId = "",
                            fontSizeScale = fontSizeScale,
                            dynamicColor = dynamicColor,
                            customThemes = customThemes,
                            bodyFontFamily = bodyFontFamily,
                            highContrast = highContrast,
                        ) {
                            // v1.56: Compose Excepción de representación causada por MuseCrashHandler(Thread.UncaughtExceptionHandler)Ten todo en cuenta,
                            // logComposeException El método está listo,Se accederá a él después de que se proporcione la API RuntimeExceptionHandler en futuras versiones de Compose.
                            Box(modifier = Modifier.fillMaxSize()) {
                                // v2.6: La capa de fondo de cristal se ha movido dentro de cada página.(Por ejemplo, ChatScreen solo incluye imágenes de fondo./gradiente),
                                // La capa global ya no se registra como fondo.
                                // Razón:Si la capa global cubre todo NavGraph,Creará recursividad en el renderizado con componentes internos de vidrio. → Desbordamiento de pila.
                                MuseNavGraph(
                                    pendingShareResult = pendingShareResult,
                                    onPendingIntentConsumed = {
                                        pendingShareResult = ShareIntentHandler.ShareResult.None
                                    },
                                    onSplashReady = { splashReady = true },
                                )
                                MuseToastHost()
                            }
                        }
                    } // CompositionLocalProvider(interruptor de vidrio líquido)
                } // RuntimeLocaleProvider
            }
        }
    }

    /** Phase 8.10: Proceso onNewIntent(La aplicación está en segundo plano.,nueva acción/Deep Link llegar). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // P2-10(informe de auditoría): La implementación anterior no se ha ajustado. setIntent(), llevando a getIntent() Siempre devuelve la intención anterior al inicio.
        // Esta es la razón por la cual "no hay respuesta al hacer clic en Agregar proveedor" después de ingresar a la página a través de un enlace profundo. —— Los intentos de lectura posteriores obtienen datos antiguos.
        setIntent(intent)
        // M5: shareIntentHandler.handle() ha sido cambiado a suspend,Procesamiento asincrónico en corrutinas,Evite bloquear el hilo principal.
        // Launcher Los atajos tienen la máxima prioridad:No entra en conflicto con el widget/compartir
        val shortcutResult = shortcutActionResult(intent)
        val widgetResult = widgetActionResult(intent)
        when {
            shortcutResult != null -> pendingShareResult = shortcutResult
            widgetResult != null -> pendingShareResult = widgetResult
            else -> lifecycleScope.launch {
                pendingShareResult = shareIntentHandler.handle(intent)
            }
        }
    }

    /**
     * Launcher Atajo de pulsación larga:Determine si proviene de shorts.xml según Intent.action.
     *
     * shortcuts.xml Después de hacer clic en el acceso directo estático en,El sistema iniciará MainActivity con la acción declarada en la intención.
     * Aquí la acción se asigna a [ShareIntentHandler.ShareResult],Reutilizar la canalización pendienteShareResult existente,
     * Consumido por LaunchedEffect en MuseNavGraph y navegado a la página correspondiente.
     */
    private fun shortcutActionResult(intent: Intent?): ShareIntentHandler.ShareResult? {
        val action = intent?.action ?: return null
        return when (action) {
            ShareIntentHandler.ACTION_NEW_CHAT -> ShareIntentHandler.ShareResult.NewSession()
            ShareIntentHandler.ACTION_TRANSLATE -> ShareIntentHandler.ShareResult.OpenTranslate
            ShareIntentHandler.ACTION_VOICE_INPUT -> ShareIntentHandler.ShareResult.StartVoiceInput
            ShareIntentHandler.ACTION_SETTINGS -> ShareIntentHandler.ShareResult.OpenSettings
            // v1.0.18: Acceso directo a grabación rápida
            ShareIntentHandler.ACTION_QUICK_NOTES -> ShareIntentHandler.ShareResult.OpenQuickNotes
            else -> null
        }
    }

    /** Phase 12: Inicio rápido del widget de escritorio — Consulte widget_action adicional. */
    private fun widgetActionResult(intent: Intent?): ShareIntentHandler.ShareResult? = ShareIntentHandler.parseWidgetAction(intent)
}

/**
 * v0.22 reescribir: Navegación principal — Estructura de la pestaña superior.
 *
 * Cambios de arquitectura:
 *  - Quitar Drawer(La barra lateral es todo bug)
 *  - La página de inicio cambió a [HomeScreen](startDestination = HOME)
 *  - HomeScreen Pestaña cápsula superior: Sesión / Agent
 *  - avatar de la esquina superior izquierda → Centro de configuración
 *  - ajustes/asistente/Memoria, etc. salta a través del enrutamiento(slide-in animación,transición de empuje deslizante)
 *
 * Lista de rutas:
 *  - HOME (pagina de inicio): Navegación de la pestaña superior(Lista de conversaciones + Chat de agente)
 *  - CHAT_DETAIL: Página de detalles del chat(Reservar,Acceso directo mediante enrutamiento)
 *  - SETTINGS: página de configuración
 *  - ASSISTANTS: Asistente de dirección
 *  - MEMORY: sistema de memoria
 *  - FAVORITES / LOREBOOKS / QUICK_MESSAGES / PROMPT_INJECTIONS / SKILLS: subpágina
 */
@Composable
private fun MuseNavGraph(
    pendingShareResult: ShareIntentHandler.ShareResult = ShareIntentHandler.ShareResult.None,
    onPendingIntentConsumed: () -> Unit = {},
    /** v1.7: Devolución de llamada lista para SplashScreen pasada desde afuera,NavGraph Activación retrasada una vez completada la inicialización. */
    onSplashReady: () -> Unit = {},
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val sharedViewModel: ChatViewModel = koinInject()
    val quickNotesViewModel: QuickNotesViewModel = koinInject()
    val settings: SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()

    // P2-17: SYSTEM(Predeterminado)ASR Utilice el iniciador de la intención de reconocimiento de voz del sistema.
    // El atajo de voz se implementó previamente en SISTEMA llamando a la ruta API de transmisión que estaba vacía.,Silencioso y sin respuesta;
    // Esto es responsable de hacer aparecer el cuadro de diálogo de identificación del sistema.,Y rellene el texto reconocido en el cuadro de entrada.
    val systemSpeechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val text = io.zer0.muse.ui.speech.SpeechInput.parseResult(result.resultCode, result.data?.extras)
        if (!text.isNullOrBlank()) {
            sharedViewModel.updateInput(text)
        }
    }

    // v1.7: Provider lista(Originalmente utilizado para determinar el primer arranque.,v1.131 El arranque ha sido eliminado.,Reservado para otros usos)
    val providers by settings.providersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val chatPreferences by settings.chatPreferencesFlow.collectAsStateWithLifecycle(
        initialValue = ChatPreferences(),
    )

    // H2: settings puerta de carga — Espere a que los proveedoresFlow emitan por primera vez antes de volver a ensamblarlo. NavHost,
    // Evite una lista vacía en el primer cuadro que haga que NavHost se combine con el estado incorrecto.NavHost Leer solo en la primera combinación startDestination
    var providersLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        settings.providersFlow.first()
        providersLoaded = true
    }
    val settingsReady = providersLoaded

    // bota de arranque:Mostrar página de inicio en el primer inicio,Una vez completado, ingrese a la interfaz principal.
    // Compatible con usuarios antiguos:Omitir el inicio completo si el proveedor ya está configurado
    val onboardingShown by settings.onboardingShownFlow.collectAsStateWithLifecycle(initialValue = true)
    var onboardingCompleted by rememberSaveable { mutableStateOf(false) }
    val showOnboarding = settingsReady && !onboardingShown && !onboardingCompleted
    val quickCaptureEnabled by settings.quickCaptureEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val quickCaptureOverlayEnabled by settings.quickCaptureOverlayEnabledFlow.collectAsStateWithLifecycle(initialValue = false)

    // v1.7: System SplashScreen está controlado por keepOnScreenCondition de MainActivity,
    // Esto solo es responsable de liberar la condición 1.2 segundos después de que se inicializa NavHost.
    var splashDelayDone by remember { mutableStateOf(false) }
    val splashDelayMillis = MuseMotion.duration(1200)
    LaunchedEffect(splashDelayMillis) {
        delay(splashDelayMillis.toLong())
        splashDelayDone = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // P2-13: Teclas de acceso directo globales de escritorio — Ctrl+, Abrir configuración,Esc Salir de la página actual(popBackStack)
        // Solo tiene efecto bajo teclado físico + ventana expandida;Esc en la página raíz(HOME)Regresar en popBackStack false,
        // no saldrá App,La semántica de la clave de retorno del sistema es consistente
        val desktopShortcutsEnabled = rememberDesktopShortcutsEnabled()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onKeyEvent { event ->
                    if (!desktopShortcutsEnabled) return@onKeyEvent false
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when {
                        // Ctrl+,: Abrir configuración(Alinear VS Code/Slack y otros hábitos de aplicaciones de escritorio)
                        event.key == DesktopShortcuts.OPEN_SETTINGS && event.isCtrlPressed -> {
                            navController.navigate(SettingsRoute)
                            true
                        }
                        // Esc: Salir de la página actual(Equivalente a la clave de retorno del sistema)
                        event.key == DesktopShortcuts.CLOSE -> {
                            navController.popBackStack()
                            true
                        }
                        else -> false
                    }
                },
        ) {
            // v1.x: Se eliminó la función de bloqueo de aplicaciones.(decisiones de producto),Ya no te suscribes a la aplicaciónPin/configuración biométrica
            LaunchedEffect(splashDelayDone, settingsReady) {
                if (splashDelayDone && settingsReady) {
                    onSplashReady()
                }
            }

            LaunchedEffect(
                settingsReady,
                showOnboarding,
                quickCaptureEnabled,
                quickCaptureOverlayEnabled,
            ) {
                val shouldRunOverlay = settingsReady &&
                    !showOnboarding &&
                    quickCaptureEnabled &&
                    quickCaptureOverlayEnabled &&
                    android.provider.Settings.canDrawOverlays(context)
                if (shouldRunOverlay) {
                    QuickCaptureOverlayService.start(context)
                } else if (!quickCaptureEnabled || !quickCaptureOverlayEnabled || showOnboarding) {
                    QuickCaptureOverlayService.stop(context)
                }
            }

            // v1.27: Ya no depende de la reconstrucción del estado de inicio de sesión NavHost,Utilice el mapa de navegación fijo directamente
            // H2: settings Combinar después de completar la carga NavHost,Asegúrese de que startDestination utilice real providers/onboardingShown
            if (settingsReady) {
                // Página de inicio:Cuando se inicia por primera vez y el arranque no se ha completado,Guía de visualización de cobertura de pantalla completa
                if (showOnboarding) {
                    OnboardingScreen(
                        onComplete = { onboardingCompleted = true },
                    )
                } else {
                    // Phase 8.10: compartir el consumo/Deep Link resultado
                    LaunchedEffect(pendingShareResult) {
                        val result = pendingShareResult

                        // v2.6.7: Portal unificado para entrada de voz —— Launcher Accesos directos y widgets de escritorio
                        // "Quick Voice Input" comparte este artículo para evitar escribir una copia en cada lugar y derivar comportamientos.
                        fun launchVoiceInput() {
                            // P2-17: SYSTEM(Predeterminado)/Modo de archivo sin implementación de ASR de transmisión,Las llamadas directas serán silenciosas y no responderán.;
                            // Cambiar al sistema de reconocimiento de voz Intent(SpeechInput),Reconocer el cuadro de entrada de relleno de texto.
                            if (sharedViewModel.shouldUseApiRecording()) {
                                sharedViewModel.startStreamingAsr()
                            } else if (io.zer0.muse.ui.speech.SpeechInput.isAvailable(context)) {
                                systemSpeechLauncher.launch(
                                    io.zer0.muse.ui.speech.SpeechInput.createIntent(
                                        context.getString(R.string.settings_asr_provider_system),
                                    ),
                                )
                            } else {
                                // Dar un mensaje claro cuando no haya ningún reconocedor de voz disponible,No más silencio
                                io.zer0.muse.ui.common.feedback.MuseToast.show(
                                    context.getString(R.string.asr_system_unavailable),
                                )
                            }
                        }
                        when (result) {
                            is ShareIntentHandler.ShareResult.PrefillText -> {
                                sharedViewModel.updateInput(result.text)
                                navController.navigate(HomeRoute) {
                                    popUpTo(HomeRoute) { inclusive = false }
                                    launchSingleTop = true
                                }
                            }
                            is ShareIntentHandler.ShareResult.OpenSession -> {
                                navController.navigate(ChatDetailRoute) {
                                    popUpTo(HomeRoute) { inclusive = false }
                                    launchSingleTop = true
                                }
                                sharedViewModel.openSessionFromNotification(result.sessionId)
                            }
                            is ShareIntentHandler.ShareResult.NewSession -> {
                                // Si el widget tiene un asistente, úsalo,De lo contrario, utilice el asistente predeterminado en la configuración.
                                sharedViewModel.createNewSession(assistantIdOverride = result.assistantId)
                                navController.navigate(HomeRoute) {
                                    popUpTo(HomeRoute) { inclusive = false }
                                    launchSingleTop = true
                                }
                                // v2.6.7: El widget "Entrada rápida de voz" abre el micrófono inmediatamente después de crear una nueva sesión.
                                // Anteriormente, enviaba la misma acción que el botón "Nueva conversación". Al hacer clic en él solo se abrió la conversación.
                                // No actives la voz en absoluto —— No es digno de su nombre.
                                if (result.startVoice) launchVoiceInput()
                            }
                            is ShareIntentHandler.ShareResult.OpenAssistants -> {
                                navController.navigate(AssistantsRoute)
                            }
                            is ShareIntentHandler.ShareResult.ImportAssistantCard -> {
                                // v2.x: Importación del paquete de personajes — URI Déjelo en el cuadro de diálogo de vista previa de importación de la página del asistente para su consumo.
                                AssistantCardImportBridge.offer(result.uri)
                                navController.navigate(AssistantsRoute)
                            }
                            is ShareIntentHandler.ShareResult.OpenSettings -> {
                                navController.navigate(SettingsRoute)
                            }
                            is ShareIntentHandler.ShareResult.OpenChats -> {
                                navController.navigate(HomeRoute) {
                                    popUpTo(HomeRoute) {
                                        inclusive = true
                                    }
                                    launchSingleTop = true
                                }
                            }
                            is ShareIntentHandler.ShareResult.OpenScheduledTasks -> {
                                navController.navigate(ScheduledTasksRoute)
                            }
                            is ShareIntentHandler.ShareResult.OpenScheduledTask -> {
                                navController.navigate(ScheduledTaskRoute(result.taskId))
                            }
                            is ShareIntentHandler.ShareResult.OpenQuickNote -> {
                                navController.navigate(QuickNoteRoute(result.noteId))
                            }
                            is ShareIntentHandler.ShareResult.OpenChat -> {
                                navController.navigate(ChatDetailRoute)
                            }
                            is ShareIntentHandler.ShareResult.OpenHome -> {
                                navController.navigate(HomeRoute) {
                                    popUpTo(HomeRoute) { inclusive = false }
                                    launchSingleTop = true
                                }
                            }
                            is ShareIntentHandler.ShareResult.OpenSettingsData -> {
                                navController.navigate(SettingsDataRoute)
                            }
                            is ShareIntentHandler.ShareResult.OpenCloudBackup -> {
                                navController.navigate(SettingsCloudBackupRoute)
                            }
                            is ShareIntentHandler.ShareResult.OpenSettingsAbout -> {
                                navController.navigate(SettingsAboutRoute)
                            }
                            is ShareIntentHandler.ShareResult.OpenSettingsAgent -> {
                                navController.navigate(SettingsAgentRoute)
                            }
                            is ShareIntentHandler.ShareResult.OpenSettingsModel -> {
                                navController.navigate(SettingsModelRoute)
                            }
                            is ShareIntentHandler.ShareResult.OpenMemory -> {
                                navController.navigate(SettingsMemoryRoute)
                            }
                            is ShareIntentHandler.ShareResult.OpenKnowledge -> {
                                navController.navigate(KnowledgeRoute)
                            }
                            is ShareIntentHandler.ShareResult.OpenKnowledgeBases -> {
                                navController.navigate(KnowledgeBaseManageRoute)
                            }
                            // Launcher atajo:Abrir página de traducción
                            is ShareIntentHandler.ShareResult.OpenTranslate -> {
                                navController.navigate(TranslateRoute)
                            }
                            // Launcher atajo:Vaya a la página de inicio y active la entrada de voz
                            is ShareIntentHandler.ShareResult.StartVoiceInput -> {
                                navController.navigate(HomeRoute) {
                                    popUpTo(HomeRoute) { inclusive = false }
                                    launchSingleTop = true
                                }
                                launchVoiceInput()
                            }
                            // v1.0.18: Launcher atajo:Abra la página de registro rápido
                            is ShareIntentHandler.ShareResult.OpenQuickNotes -> {
                                navController.navigate(QuickNotesRoute)
                            }
                            ShareIntentHandler.ShareResult.None -> Unit
                        }
                        // Notificación única de consumo/Deep Link evento para garantizar que los clics continuos en notificaciones similares también puedan redirigirse.
                        if (result != ShareIntentHandler.ShareResult.None) {
                            onPendingIntentConsumed()
                        }
                    }

                    // v1.131: Se ha eliminado el primer arranque.,Ir directamente a la página de inicio
                    val startDestination = HomeRoute

                    // Regrese a la devolución de llamada de Navigation Compose que viene con NavHost:
                    // La pila de navegación revierte la subpágina. Cuando la página raíz no tiene una pila de reversión, se entrega a la Actividad para que salga de forma predeterminada.
                    // BackHandler a nivel de página,Dialog y Popup todavía se consumen primero según la prioridad interna de Compose.

                    NavHost(
                        navController = navController,
                        startDestination = startDestination,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        // La transición predeterminada reutiliza el mismo conjunto de ayudas con cada sub-NavGraph para evitar la confusión entre el gráfico raíz y el gráfico secundario.
                        // duración/Desplazamiento/Desvanecerse la deriva semántica.
                        enterTransition = { MuseTransitions.horizontalPushEnter() },
                        exitTransition = { MuseTransitions.horizontalPushExit() },
                        popEnterTransition = { MuseTransitions.horizontalPopEnter() },
                        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
                    ) {
                        // P0-3: NavGraph Extracción de subgrafos — 66 Una división componible en 4 archivos de dominio
                        chatNavGraph(
                            navController = navController,
                            sharedViewModel = sharedViewModel,
                        )
                        assistantNavGraph(
                            navController = navController,
                            sharedViewModel = sharedViewModel,
                        )
                        settingsNavGraph(
                            navController = navController,
                            sharedViewModel = sharedViewModel,
                        )
                        toolsNavGraph(
                            navController = navController,
                            sharedViewModel = sharedViewModel,
                        )
                    }
                } // cerrar else(showOnboarding) rama
            }

            // Entrada de alta frecuencia:Desliza el dedo hacia la izquierda desde el borde derecho de cualquier página en Muse,Evoca un registro rápido del panel deslizante.
            // No solicitar permiso de ventana flotante,Tampoco sobrescribe la página de inicio.,Evite conflictos con los gestos del sistema.
            QuickCaptureEdgeOverlay(
                enabled = settingsReady &&
                    !showOnboarding &&
                    quickCaptureEnabled &&
                    !quickCaptureOverlayEnabled,
                viewModel = quickNotesViewModel,
            )
        } // Cerrar la capa interior P2-13 onKeyEvent Box
    }
}

/**
 * v2.6.2: Lea si el sistema actual está en modo nocturno.
 *
 * Utilice los recursos de la aplicación (configuración del sistema), no confíe en Actividad o Redactar
 * LocalConfiguration(Este último será reemplazado por una superposición para el cambio de idioma).
 */
private fun isSystemNightMode(context: android.content.Context): Boolean {
    // v2.6.2 hotfix: Para evitar que applicationContext no esté disponible en la etapa inicial de la Actividad,
    // Si no se puede leer, trátelo como un color claro y nunca genere una excepción (de lo contrario, fallará al iniciar y no se podrá ver la página de falla).
    val appContext = runCatching { context.applicationContext }.getOrNull() ?: context
    val uiMode = runCatching { appContext.resources.configuration.uiMode }.getOrNull()
        ?: return false
    return (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
}

/**
 * I4: Cambio en caliente del idioma en tiempo de ejecución — Anular Compose [LocalContext]/[LocalConfiguration],
 * Reorganizar solo cuando el idioma cambie UI,No reconstruir la actividad.
 *
 * Basado en la configuración del sistema a nivel de aplicación(attachBaseContext Solo incluye recursos base de actividad,
 * applicationContext Siempre configuración del sistema),Así que no importa a qué idioma vuelvas a cambiar "system" se puede restaurar correctamente.
 * stringResource Leer directamente en Compose 1.7 LocalContext.current.resources,Por lo tanto cubrir LocalContext
 * para el nuevo local ContextWrapper(applicationContext paso a través,Servicios del sistema/launcher no están afectados;
 * Dependiente de la identidad de la actividad as? Activity El número de puntos de llamada es limitado y todos están cubiertos.).
 * ROM Ten todo en cuenta: Consistente con wrapWithLanguage,La configuración de actualización adicional fuerza la actualización local.
 */
@Composable
private fun RuntimeLocaleProvider(lang: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    // Pasar explícitamente el host del resultado de la actividad al anular LocalContext,De lo contrario, la página de chat
    // rememberLauncherForActivityResult Se bloquea directamente al ingresar a ChatScreen.
    val activityResultRegistryOwner = checkNotNull(LocalActivityResultRegistryOwner.current) {
        "MainActivity requires an ActivityResultRegistryOwner"
    }
    val appContext = context.applicationContext
    val locale = remember(lang) { io.zer0.muse.util.AppLocales.parse(lang) }
    val config = remember(lang) {
        val base = Configuration(appContext.resources.configuration)
        if (locale != null) {
            base.setLocale(locale)
            base.setLayoutDirection(locale)
        }
        base
    }
    val localizedContext = remember(lang) {
        val wrapped = appContext.createConfigurationContext(config)
        try {
            wrapped.resources.updateConfiguration(config, wrapped.resources.displayMetrics)
        } catch (e: Exception) {
            // updateConfiguration Algunas versiones nuevas están marcadas como obsoletas pero aún están disponibles,tolerancia a fallos ignorada
        }
        // v2.7: Envuelva una capa para que NEW_TASK se agregue automáticamente cuando este contexto que no es de Actividad inicie la Actividad.
        //   De lo contrario, el elemento "Procesar texto" del menú de selección Redactar texto (y otros iniciados desde LocalContext
        //   Activity El punto de llamada) generará AndroidRuntimeException y fallará (prueba real del usuario).
        //   El comportamiento del lenguaje permanece sin cambios (base Aún con la configuración regional de destino).
        io.zer0.muse.util.LocaleContextWrapper.wrap(wrapped)
    }
    CompositionLocalProvider(
        LocalActivityResultRegistryOwner provides activityResultRegistryOwner,
        LocalConfiguration provides config,
        LocalContext provides localizedContext,
        content = content,
    )
}
