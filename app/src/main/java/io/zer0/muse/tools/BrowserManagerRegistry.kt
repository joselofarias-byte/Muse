package io.zer0.muse.tools

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Registro de instancias del navegador(P2-21 Después de la fusión).
 *
 * La implementación original de "BrowserManager independiente para cada sesión" coexiste con el singleton global compartido por la cápsula ToolRegistry/UI.,
 * Causa doble instancia del navegador.:El bucle de la herramienta de sesión principal escribe en el registro privado, el subagente/Las tareas programadas están escritas en ToolRegistry
 * en el singleton global,El estado está bifurcado y la cápsula de la interfaz de usuario no lo puede observar. Ahora unificado como**Único a nivel mundial BrowserManager**:
 *  - [getForSession] Devolver instancia compartida,y registrar la sesión"Navegador ya utilizado"(Mostrar y ocultar la cápsula de la interfaz de usuario de Drive)
 *  - Todas las rutas de ejecución(bucle principal/subagente/tareas programadas)Presione el mismo estado de WebView,UI Visible en tiempo real
 *  - La eliminación de la sesión solo requiere [closeSession] Cancelar registro,La instancia compartida subyacente ya no se publica
 */
class BrowserManagerRegistry(
    private val context: Context,
    /** P2-21: Instancia de navegador única a nivel mundial(Compartido con la cápsula ToolRegistry/UI).null = autoconstruido(independiente/entorno de prueba). */
    private val sharedManager: BrowserManager? = null,
) {

    private val lock = Any()

    /** Ejemplos de navegadores reales utilizados:Compartir almacén completo,Evite las bifurcaciones estatales. */
    private val manager: BrowserManager = sharedManager ?: BrowserManager(context)

    // Colección de identificadores de sesión de navegadores usados.(Pantalla de cápsula de interfaz de usuario de conducción;WebView El Estado mismo es compartido globalmente.)
    private val _activeSessionIds = MutableStateFlow<Set<String>>(emptySet())

    /** Una colección de identificadores de sesión para navegadores usados. */
    val activeSessionIds: StateFlow<Set<String>> = _activeSessionIds.asStateFlow()

    /**
     * Obtener(o registrarse)Instancia del navegador para la sesión especificada — Devuelve la instancia compartida global y registra la sesión.
     *
     * P2-21: Ya no crear nuevos por sesión WebView;cualquier sesión/subagente/Llamada a la herramienta del navegador para tareas programadas
     * Ambos operan en la misma instancia.,Eliminar la bifurcación estatal de "nivel de sesión versus singleton global".
     */
    fun getForSession(sessionId: String): BrowserManager {
        synchronized(lock) {
            _activeSessionIds.value = _activeSessionIds.value + sessionId
            return manager
        }
    }

    /** Si la sesión se registra usando el navegador, se devuelve la instancia compartida(No registrado),En caso contrario nulo. Utilizado por la interfaz de usuario para determinar si la cápsula está visible u oculta. */
    fun getIfActive(sessionId: String?): BrowserManager? {
        if (sessionId == null) return null
        synchronized(lock) { return if (sessionId in _activeSessionIds.value) manager else null }
    }

    /** Cerrar sesión en el registro del navegador para una sesión específica(Llamado cuando se elimina la sesión.;No cerrar instancia compartida). */
    fun closeSession(sessionId: String) {
        synchronized(lock) {
            _activeSessionIds.value = _activeSessionIds.value - sessionId
        }
    }

    /** Borrar todos los registros de sesión(Salidas de proceso/Llamado al configurar la limpieza;Las instancias compartidas son administradas por el ciclo de vida de Koin.). */
    fun closeAll() {
        synchronized(lock) {
            _activeSessionIds.value = emptySet()
        }
    }
}
