package io.zer0.muse.ui.speech

import io.zer0.common.AppDispatchers
import io.zer0.common.Logger
import io.zer0.common.Result
import kotlinx.coroutines.withContext

/**
 * P2-9: Resumen de clonación de voz — Interfaz unificada de múltiples proveedores.
 *
 * con existente [CloudTtsService] desacoplamiento:CloudTtsService responsable"texto→Audio"síntesis,
 * Esta interfaz es responsable de"audio de muestra→Reutilizable voiceId"Gestión de clones(aumentar/columna/eliminar).
 *
 * Organizado por abstracción del proveedor,Distribuido por proveedor dentro de un único archivo;
 * Implementación actual [ElevenLabsVoiceCloningProvider],OpenVoice / Fish Audio, etc. se pueden ampliar en el futuro.
 *
 * Uso:
 * ```
 * val service: VoiceCloningService = koinInject()
 * service.cloneVoice("elevenlabs", "my-voice", sampleBase64)
 *     .onSuccess { voiceId -> /* guardar en MediaConfig.ttsVoice */ }
 *     .onError { msg, _ -> MuseToast.show(msg) }
 * ```
 */
interface VoiceCloningProvider {
    /** Provider identificador único(Como por ejemplo "elevenlabs"),con [VoiceCloningService] Corresponde a la clave de registro. */
    val providerId: String

    /**
     * Subir audio de muestra,Crear una voz clonada,Devolver ID de voz.
     *
     * @param name Nombre fonético legible por el usuario
     * @param sampleAudioBase64 codificación base64 de audio de muestra(Compatible con mp3/wav/m4a, etc.)
     * @return Regresar exitosamente a nuevo voiceId,Retorno del fracaso [Result.Error]
     */
    suspend fun cloneVoice(name: String, sampleAudioBase64: String): Result<String>

    /** Lista de voces clonadas. */
    suspend fun listClonedVoices(): Result<List<ClonedVoice>>

    /** Elimina la voz clonada. */
    suspend fun deleteVoice(voiceId: String): Result<Unit>
}

/**
 * Clona el modelo de datos de voz.
 *
 * @param voiceId Provider regresó voice id(Utilizado como referencia de composición TTS.)
 * @param name nombre legible por el usuario
 * @param createdAt Crear marca de tiempo(milisegundos),Devuelto por el proveedor(Sin reglas 0)
 * @param category Provider Etiquetas de clasificación(Predeterminado "cloned",Fácil de distinguir de la voz predeterminada)
 */
data class ClonedVoice(
    val voiceId: String,
    val name: String,
    val createdAt: Long,
    val category: String = "cloned",
)

/**
 * Servicio de clonación de voz — Distribuido a específicos [VoiceCloningProvider].
 *
 * Inyección durante la construcción providerId → Provider mapeo(Montado por Koin),
 * Todos los métodos están en [AppDispatchers.io] ejecutar en,Asegúrese de que el hilo de la interfaz de usuario no esté bloqueado.
 */
class VoiceCloningService(
    private val providers: Map<String, VoiceCloningProvider>,
) {
    companion object {
        private const val TAG = "VoiceCloningService"
    }

    /** Obtenga la lista de Proveedores registrados(Para el selector de proveedor de renderizado de UI). */
    fun availableProviders(): List<String> = providers.keys.toList()

    /**
     * Clona nuevas voces.
     *
     * @param providerId Identificación del proveedor de servicios(Debe estar registrado)
     * @param name Nombre de voz
     * @param sampleAudioBase64 audio de muestra base64
     */
    suspend fun cloneVoice(providerId: String, name: String, sampleAudioBase64: String): Result<String> = withContext(AppDispatchers.io) {
        val provider = providers[providerId]
        if (provider == null) {
            Logger.w(TAG, "desconocido voice cloning provider: $providerId")
            return@withContext Result.Error("Unknown provider: $providerId")
        }
        provider.cloneVoice(name, sampleAudioBase64)
    }

    /** Muestra voces clonadas para el proveedor especificado. */
    suspend fun listClonedVoices(providerId: String): Result<List<ClonedVoice>> = withContext(AppDispatchers.io) {
        val provider = providers[providerId]
        if (provider == null) {
            Logger.w(TAG, "desconocido voice cloning provider: $providerId")
            return@withContext Result.Error("Unknown provider: $providerId")
        }
        provider.listClonedVoices()
    }

    /** Elimina la voz clonada del proveedor especificado. */
    suspend fun deleteVoice(providerId: String, voiceId: String): Result<Unit> = withContext(AppDispatchers.io) {
        val provider = providers[providerId]
        if (provider == null) {
            Logger.w(TAG, "desconocido voice cloning provider: $providerId")
            return@withContext Result.Error("Unknown provider: $providerId")
        }
        provider.deleteVoice(voiceId)
    }
}
