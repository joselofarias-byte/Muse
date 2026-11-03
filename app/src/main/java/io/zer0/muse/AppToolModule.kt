package io.zer0.muse

import io.zer0.muse.tools.SessionPermissionStore
import io.zer0.muse.tools.ToolConfigStore
import io.zer0.muse.tools.ToolRegistry
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * P2-3 Deslocalización:ToolRegistry Es un módulo independiente con registro de herramientas, almacenamiento de permisos y registro automático del navegador.
 */
val appToolModule = module {

    // Phase 5-H: Registro de herramientas(Versión simplificada del marco MCP)
    // Phase 8.8: Pase en contexto para los servicios del sistema Portapapeles/Estadísticas de uso/Calendario
    single { ToolRegistry(androidContext(), get()) }

    // P2-6: BrowserManager automatización del navegador(Headless WebView,Llamado por herramientas de IA)
    // Nota:ToolRegistry con UI(Cápsula de estado del navegador)Compartir la misma instancia,AI Las operaciones se pueden ver en tiempo real.
    // v1.0.92: canal de mensajes(Puente de mensajería instantánea externa) — Administrador + Registro de herramienta de envío
    single { io.zer0.muse.channel.ChannelManager(androidContext()) }
    single { io.zer0.muse.tools.ChannelToolsRegistrar(get(), get()) }
    // v2.0: Respuesta automática del canal.(Mensajes entrantes → correr una ronda → Devolución de datos a la fuente)
    single { io.zer0.muse.channel.ChannelAutoReply(get(), get(), get(), androidContext(), get(), get(), get(), get()) }
    // v2.0: ClawBot(iLink)receptor de sondeo largo
    single { io.zer0.muse.channel.WeClawReceiver(get(), androidContext(), get()) }
    // v2.0.1: QQ Receptor de puerta de enlace WebSocket del robot(Red pública gratuita)
    single { io.zer0.muse.channel.QqReceiver(get(), androidContext(), get()) }
    // v2.0.1: Receptor de conexión larga Feishu(pbbp2 over WebSocket,Red pública gratuita)
    single { io.zer0.muse.channel.FeishuReceiver(get(), androidContext(), get()) }
    // v2.0: Telegram receptor de sondeo largo
    single { io.zer0.muse.channel.TelegramReceiver(get(), androidContext(), get()) }
    // v2.0: Receptor de transmisión DingTalk
    single { io.zer0.muse.channel.DingtalkReceiver(get(), androidContext(), get()) }
    // P1-3b Desterritorialización: texto/Registrador de herramientas de codificación(URL/Base64/picadillo/UUID/numero aleatorio,Extraído de ToolRegistry)
    single { io.zer0.muse.tools.EncodingToolsRegistrar(androidContext(), get()) }
    // v2.0: OAuth herramienta conectora(connector_list / call_connector)
    single { io.zer0.muse.connector.ConnectorStore(androidContext()) }
    single { io.zer0.muse.tools.ConnectorToolsRegistrar(get(), get()) }
    // P1-3b Desterritorialización: Registrador de herramientas básicas básicas(get_current_time/calculator/echo)
    single { io.zer0.muse.tools.CoreToolsRegistrar(androidContext(), get()) }
    // P1-3b Desterritorialización: Registrador de herramientas meteorológicas(get_weather)
    single { io.zer0.muse.tools.WeatherToolsRegistrar(androidContext(), get()) }
    // P1-3b Desterritorialización: Registrador de herramientas del portapapeles(clipboard_read/write)
    single { io.zer0.muse.tools.ClipboardToolsRegistrar(androidContext(), get()) }
    // P1-3b Desterritorialización: red/Registrador de herramientas de texto(ping/dns/Red públicaIP/json_pretty/Contraseña)
    single { io.zer0.muse.tools.NetworkTextToolsRegistrar(androidContext(), get()) }
    // P1-3b Desterritorialización: Programar registro de la herramienta de recordatorio(schedule/cancel/list_reminders)
    single { io.zer0.muse.tools.ReminderToolsRegistrar(androidContext(), get()) }
    // P1-3b Desterritorialización: Registrarse en la herramienta Calendario(calendar_today/add_calendar_event)
    single { io.zer0.muse.tools.CalendarToolsRegistrar(androidContext(), get()) }
    // P1-3b Desterritorialización: Registro de herramientas celulares(12 un)
    single { io.zer0.muse.tools.PhoneToolsRegistrar(androidContext(), get()) }
    // P1-3b Desterritorialización: sistema/Registrador de herramientas del dispositivo(24 un)
    single { io.zer0.muse.tools.SystemToolsRegistrar(androidContext(), get()) }
    // P1-3b Desterritorialización: Biblioteca de recursos + Registro de herramienta de grabación rápida
    single { io.zer0.muse.tools.ResourceToolsRegistrar(androidContext(), get()) }
    single { io.zer0.muse.tools.QuickNoteToolsRegistrar(androidContext(), get()) }
    // P1-3b Desterritorialización: Tareas programadas + registro de herramienta de traducción
    single { io.zer0.muse.tools.ScheduledTaskToolsRegistrar(androidContext(), get()) }
    single { io.zer0.muse.tools.TranslateToolsRegistrar(get()) }
    // P1-3b Desterritorialización: TTS Registrador de herramientas(speak_text)
    single { io.zer0.muse.tools.TtsToolsRegistrar(androidContext(), get()) }

    // v1.137: Almacenamiento rápido de registros,Para que las tareas de automatización y la interfaz de usuario compartan la misma instancia
    single { io.zer0.muse.tools.quicknote.QuickNoteStore(androidContext()) }

    // P3: Persistencia del modo de permiso de herramienta a nivel de sesión
    single { SessionPermissionStore(androidContext()) }

    // v1.0.20: Persistencia de la política de aprobación de una sola herramienta(DataStore)
    // suministro ToolsSettingsPage(koinInject)Comparte la misma instancia que ToolPermissionResolver
    // Reparar NoDefinitionFoundException: utilizado anteriormente por ToolsSettingsPage koinInject() tomar ToolConfigStore,
    // Pero no está registrado en Koin.,generar clics"Establecer aprobación o desaprobación para cada herramienta"La reorganización de redacción falla cuando el botón ingresa a la página
    single { ToolConfigStore(androidContext()) }

    // P2-6: BrowserManager automatización del navegador(Headless WebView,Llamado por herramientas de IA)
    // Nota:ToolRegistry con UI(Cápsula de estado del navegador)Compartir la misma instancia,AI Las operaciones se pueden ver en tiempo real.
    single { io.zer0.muse.tools.BrowserManager(androidContext()) }

    // P2-21: Registro del navegador — Avanzando hacia la participación global BrowserManager(Misma instancia que la cápsula ToolRegistry/UI),
    // Eliminar la bifurcación estatal de instancia dual de "registro a nivel de sesión versus singleton global"
    single { io.zer0.muse.tools.BrowserManagerRegistry(androidContext(), get()) }

    // v2.0.1: Herramienta de mercado de complementos(Búsqueda + Aprobación Instalación) — Comparta componentes de Market con la página de administración de complementos(ver AppKoinModule)
    single<io.zer0.muse.data.plugin.market.PluginMarketToolGateway> {
        io.zer0.muse.data.plugin.market.PluginMarketToolService(
            context = androidContext(),
            pluginManager = get(),
            marketSettings = get(),
            trustRoots = get(),
            repository = get(),
        )
    }
    single { io.zer0.muse.tools.PluginMarketToolsRegistrar(get(), get()) }
}
