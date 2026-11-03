package io.zer0.muse.ui.common.media

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration

/**
 * P2-13: Definiciones de atajos de teclado de escritorio.
 *
 * solo en [WindowWidthClass.Expanded] y se habilita cuando se detecta un teclado físico.
 * usar `Modifier.onKeyEvent` Interceptar eventos de teclado de hardware.
 *
 * principios de diseño:
 *  - No destruye el InputBar existente `enterToSend` comportamiento(Los usuarios pueden cambiar la configuración"Entra para enviar"o"Shift+Entra para enviar")
 *  - Teclas de acceso directo globales(Ctrl+K / Ctrl+N / Ctrl+, / Esc)Interceptar en la raíz de MainActivity
 *  - Teclas de acceso directo locales(Enter Enviar / Ctrl+Shift+C Copiar respuesta)Intercepción dentro de ChatScreen
 *  - Las teclas de método abreviado solo funcionan en escenarios de teclado de hardware(El teclado virtual no se activa),Pase [rememberHasPhysicalKeyboard] puerta
 *
 * Convención de clave modificadora(Alinear los hábitos de las aplicaciones de escritorio):
 *  - Ctrl Modificar:Teclas de acceso directo de navegación global(Buscar/Nuevo/Configuración)
 *  - Shift Modificar:Produzca un comportamiento inverso con combinaciones de teclas básicas.(Shift+Enter = nueva línea,Enter = enviar)
 *  - Ctrl+Shift Modificar:Operaciones auxiliares(Copiar la última respuesta)
 *  - Sin modificación:Esc cerrar
 */
object DesktopShortcuts {
    /** Enter(Ninguno Shift): enviar mensaje — solo en `enterToSend=true` es manejado por el propio InputBar */
    val SEND_MESSAGE: Key = Key.Enter

    /** Shift+Enter: nueva línea(Anular el comportamiento enterToSend,Shift Decorar significa envolver en lugar de enviar.) */
    val NEWLINE: Key = Key.Enter

    /** Ctrl+K: Cuadro de búsqueda de enfoque(Si no hay un cuadro de búsqueda integrado, abra la página de búsqueda externa) */
    val FOCUS_SEARCH: Key = Key.K

    /** Ctrl+N: Nueva conversación */
    val NEW_CHAT: Key = Key.N

    /** Ctrl+,: Abrir configuración(Alinear VS Code/Slack y otros hábitos de aplicaciones de escritorio) */
    val OPEN_SETTINGS: Key = Key.Comma

    /** Ctrl+Shift+C: Copia la última respuesta de IA al portapapeles */
    val COPY_LAST_REPLY: Key = Key.C

    /** Esc: Cerrar el cuadro de diálogo/salir de la página actual(Equivalente al retorno del sistema) */
    val CLOSE: Key = Key.Escape
}

/**
 * Detecta si hay un teclado físico actualmente conectado.
 *
 * Pase [Configuration.keyboard] juez:
 *  - [Configuration.KEYBOARD_NOKEYS]:Sin teclado físico(Dispositivo táctil puro)
 *  - [Configuration.KEYBOARD_QWERTY] / [Configuration.KEYBOARD_12KEY]:Tiene un teclado fisico
 *
 * en ChromeOS / Android Desktop / En el escenario de teclado Bluetooth externo,`Configuration.keyboard`
 * seguirá `KEYBOARD_NOKEYS` cambiar a `KEYBOARD_QWERTY`,y [LocalConfiguration] En configuración
 * cambiar(Conectar y desconectar el teclado)Reorganizar automáticamente cuando,Por lo tanto `remember(configuration)` Esto garantiza la sincronización del estado.
 *
 * Regresar [State] en lugar de desnudo `Boolean`,Conveniente para `Modifier.onKeyEvent` El último valor de la cadena se lee bajo demanda.
 *
 * Nota:Android 11+ El sistema llama a la actividad. `onConfigurationChanged`,
 * Pero Compose `LocalConfiguration` Sólo después de reiniciar la actividad o `applyResourceConfigurations`
 * Actualización tras actualización. Suficiente para la mayoría de los escenarios;Si necesita una detección más precisa en tiempo real,Se puede combinar `WindowInsets`
 * de `ime` Estado,Pero está fuera del alcance de esta tarea.
 */
@Composable
fun rememberHasPhysicalKeyboard(): State<Boolean> {
    val configuration = LocalConfiguration.current
    return remember(configuration.keyboard) {
        mutableStateOf(configuration.keyboard != Configuration.KEYBOARD_NOKEYS)
    }
}

/**
 * P2-13: Las teclas de acceso directo del escritorio permiten juzgar.
 *
 * Las teclas de acceso directo se habilitan solo cuando se cumplen dos condiciones al mismo tiempo:
 *  1. Los anchos de las ventanas se clasifican como [WindowWidthClass.Expanded](Tableta horizontal/escritorio)
 *  2. Teclado físico detectado(Dispositivos no táctiles puros)
 *
 * En el escenario de pantalla vertical del teléfono celular + teclado virtual,Ninguna condición se cumple,Teclas de acceso directo desactivadas automáticamente,
 * No entrará en conflicto con la acción IME del teclado virtual.
 */
@Composable
fun rememberDesktopShortcutsEnabled(): Boolean {
    val widthClass = rememberWindowWidthClass()
    val hasKeyboard by rememberHasPhysicalKeyboard()
    return widthClass == WindowWidthClass.Expanded && hasKeyboard
}
