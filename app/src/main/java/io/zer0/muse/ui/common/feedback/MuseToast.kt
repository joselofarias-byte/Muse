package io.zer0.muse.ui.common.feedback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Componente de aviso elegante — Reemplaza a Toast nativo.
 *
 * Singleton global,Llama a cualquier lugar [show] puede ser activado,
 * por [MuseToastHost] Composable Observar y renderizar.
 *
 * Uso:
 * ```kotlin
 * MuseToast.show("copiado")           // pantalla corta(2s)
 * MuseToast.show("Guardado exitosamente", 3000)   // Duración personalizada
 * ```
 */
object MuseToast {

    // L-MT1: Utilice el valor único de MutableStateFlow para transportar la corriente toast,Sin cola interna.
    // llamadas continuas show() sobrescribirá directamente el anterior(no hagas cola),Se descarta la duración restante de visualización del antiguo brindis.
    // esto es intencional:toast para una respuesta instantánea y ligera,Evite el retraso en la cola, lo que provoca que los mensajes retrasen las operaciones del usuario.
    // Si necesita mostrar varios elementos en secuencia en el futuro,,En su lugar, se debe usar StateFlow de lista de canal + para implementar colas.
    private val _message = MutableStateFlow<ToastData?>(null)
    val message: StateFlow<ToastData?> = _message.asStateFlow()

    /**
     * Mostrar mensaje.
     * @param text Texto rápido
     * @param durationMillis Duración de la visualización(milisegundos),Predeterminado 2000
     */
    fun show(text: String, durationMillis: Long = 2000) {
        _message.value = ToastData(text = text, durationMillis = durationMillis)
    }

    /** Borrar mensaje actual(Se llama después de que la animación desaparece.). */
    internal fun clear() {
        _message.value = null
    }
}

/** Toast datos. */
data class ToastData(
    val text: String,
    val durationMillis: Long,
)
