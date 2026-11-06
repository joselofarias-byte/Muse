// La detección de habilidades es"haz tu mejor esfuerzo"Semántica: cualquier excepción debe degradarse a"Este artículo no está disponible", en lugar de dejar que todo el párrafo system prompt
// La inyección falla (en cuyo caso el modelo pierde por completo el reconocimiento del dispositivo).detekt Las alarmas de captura generales se seleccionan deliberadamente aquí y los motivos se registran junto con la pila.
@file:Suppress("TooGenericExceptionCaught")

package io.zer0.muse.automation.capability

import io.zer0.common.Logger
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.hook.PromptContext
import io.zer0.muse.hook.SystemPromptComposeHook
import kotlinx.coroutines.CancellationException

/**
 * Gancho de inyección de archivos de capacidad del dispositivo.
 *
 * Después de que el sistema indique que el ensamblaje está completo, coloque [DeviceCapabilityProfile](canales actualmente disponibles + límites de capacidad)
 * Agregue el procedimiento de decisión de operación al final del mensaje del sistema.
 *
 * ## Puntos de diseño
 *
 * - **Prioridad de detección, almacenamiento en caché**: Intente actualizar el estado del canal cada vez que reúna la palabra de aviso (`refreshPermissions()`
 *   Si tiene Mutex y cada canal no se detecta, se degradará a no disponible y el enlace de diálogo principal no se descompondrá). Volver cuando falla la actualización
 *   [AutomationManager.permissionState] El último resultado nunca perderá el archivo completo debido a una detección fallida.
 * - **Cualquier detección es un mejor esfuerzo.**: Si la detección de una sola habilidad genera una excepción, solo el elemento se registrará como no disponible y los demás elementos no se verán afectados.
 *   ni tirarlo hacia arriba (ver [probe]). Las excepciones de cancelación se lanzan como de costumbre y la cancelación de rutina no se acepta.
 * - **El subagente no se inyecta.**:subagent Es una subsesión aislada y no es necesario repetir todo el procedimiento del dispositivo (guardar tokens y evitar
 *   El subagente cree erróneamente que puede operar el dispositivo a voluntad).
 */
class DeviceCapabilityHook(
    private val automationManager: AutomationManager,
    /**
     * Detección de disponibilidad de pantalla virtual. No disponible de forma predeterminada; Las sondas reales se inyectan mediante el método de ensamblaje (evitando dependencias inversas del paquete de capacidades).
     * vdisplay realizado). Cuando es nulo, el archivo se marca como no disponible.
     */
    private val virtualDisplayProbe: suspend () -> Boolean = { false },
    /** Detección de disponibilidad del tiempo de ejecución del nodo incorporada ([io.zer0.muse.runtime.MuseRuntime.isReady]). */
    private val nodeRuntimeProbe: (() -> Boolean)? = null,
) : SystemPromptComposeHook {

    override val id: String = "device_capability_profile"

    /**
     * priority=80: mayor que WorldBookHook(60), coloque el archivo del dispositivo lo más cerca posible del comienzo de las indicaciones del sistema,
     * Y la inyección se completa antes de la clase de truncamiento del piso Hook.
     */
    override val priority: Int = 80

    override suspend fun afterComposeSystemPrompt(context: PromptContext): String {
        if (context.forSubagent) return ""
        return try {
            val profile = detectProfile()
            buildString {
                append(profile.renderForModel())
                appendLine()
                appendLine()
                append(profile.renderOperatingGuide())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Falló la inyección del archivo de capacidad: ${e.message}", e)
            ""
        }
    }

    /**
     * Detecte el archivo de habilidad actual: primero actualice el estado del canal de tres capas y luego superponga la pantalla virtual/Node Dos habilidades independientes.
     *
     * Utilice el estado en caché cuando falle la actualización (`permissionState.value`) para garantizar que el archivo siempre tenga una instantánea utilizable.
     */
    private suspend fun detectProfile(): DeviceCapabilityProfile {
        val state = try {
            automationManager.refreshPermissions()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Error al actualizar el permiso, usando el estado en caché: ${e.message}")
            automationManager.permissionState.value
        }

        return DeviceCapabilityProfile.fromFlags(
            accessibility = state.accessibilityEnabled,
            shizuku = state.shellEnabled,
            root = state.rootEnabled,
            virtualDisplay = probe("virtualDisplay", false) { virtualDisplayProbe() },
            nodeRuntime = probe("nodeRuntime", false) { nodeRuntimeProbe?.invoke() ?: false },
            details = mapOf(
                "shizukuState" to state.shizukuState.name,
                "shizukuMessage" to state.shizukuMessage,
            ),
        )
    }

    /**
     * Detección de mejor esfuerzo: las excepciones solo se degradan a [fallback], sin interrumpir el montaje de todo el expediente.
     *
     * La excepción de tragar es**deliberadamente**——El comportamiento correcto del fallo de detección de capacidad es"Este artículo está marcado como no disponible.", en lugar de dejar que todo el párrafo
     * system prompt La inyección falla (en cuyo caso el modelo pierde por completo el reconocimiento del dispositivo). El motivo se ha escrito en el registro con una pila.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun probe(name: String, fallback: Boolean, block: suspend () -> Boolean): Boolean = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(TAG, "$name Si la detección falla, se registrará como no disponible.: ${e.message}", e)
        fallback
    }

    private companion object {
        const val TAG = "DeviceCapabilityHook"
    }
}
