package io.zer0.muse.data.assistant

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * v2.x: Puente de importación de paquetes de caracteres externos.
 *
 * Recibido de VER intención .muse-assistant URI(Haga clic en el archivo del paquete de personajes fuera de la aplicación.)pasó a
 * Página del asistente,Vaciar después del consumo mediante el cuadro de diálogo de vista previa de importación.
 *
 * Utilizado bajo arquitectura de actividad única de proceso único [StateFlow]:No importa"No en la página del asistente cuando se recibió"Aún así
 * "Recibido nuevamente en la página del asistente."Dos tiempos,Se puede comprar en el lado de red de la página del asistente.
 */
object AssistantCardImportBridge {

    private val _pendingUri = MutableStateFlow<Uri?>(null)

    /** Paquete de personajes a importar URI(null = Nada pendiente). */
    val pendingUri: StateFlow<Uri?> = _pendingUri

    /** Registros a importar URI(Llamado por el controlador de intención de MainActivity). */
    fun offer(uri: Uri) {
        _pendingUri.value = uri
    }

    /** consumir y aclarar(Llamado por AssistantScreen). */
    fun consume() {
        _pendingUri.value = null
    }
}
