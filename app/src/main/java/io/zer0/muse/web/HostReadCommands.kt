package io.zer0.muse.web

import io.zer0.ai.core.ProviderConfig
import io.zer0.common.AppJson
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.session.SessionEntity
import kotlinx.serialization.decodeFromString

/**
 * Proyección de carga útil para comandos de solo lectura.
 *
 * Sólo haga "datos de almacenamiento" → Mapeo del "Protocolo DTO",No leas el flujo, no toques el almacén:lista vacía/Sesión vacía aquí
 * Degenera en una matriz vacía o un evento con error,En lugar de lanzar una excepción. La rama de comando se transfiere aquí después de obtener los datos.
 */
internal object HostReadCommands {

    const val TYPE_ASSISTANTS = "assistants.list"
    const val TYPE_ASSISTANT_DETAIL = "assistants.detail"
    const val TYPE_PROVIDERS = "providers.list"
    const val TYPE_SESSION_DETAIL = "sessions.detail"

    /** El valor de error cuando la sesión no existe. */
    const val ERROR_SESSION_NOT_FOUND = "session_not_found"

    /** El valor de error si el ayudante no existe. */
    const val ERROR_ASSISTANT_NOT_FOUND = "assistant_not_found"

    /**
     * Límite máximo de caracteres para el resumen de caracteres.
     *
     * El bloque de caracteres es texto libre.,Es común que los usuarios escriban miles de palabras.;La página de detalles solo muestra las primeras líneas.,Toda la sección recibirá un recibo.
     * Gran soporte y sin espacio de visualización. Mantén solo el principio en lugar de mantener el principio y el final.:El diseño de personajes comienza desde la primera frase.(identidad → relación →
     * forma de hablar),Al juntar el principio y el final, se unirán dos párrafos no relacionados.,Al leerlo te sientes como una persona diferente.
     */
    private const val PERSONA_SUMMARY_LIMIT = 500

    /**
     * Asistente de un vistazo.
     *
     * El avatar utiliza la misma ruta multimedia que la imagen del mensaje: la ruta local del teléfono celular (`file://…/avatar/…`) no puede ser recuperado por el navegador,
     * Reemplazar con `/api/media/<token>`;`http(s)` Transmisión transparente tal como es;`android.resource://`, base64 en línea y tal
     * Si el navegador no puede convertir la dirección en una dirección legible, se devolverá nulo y la interfaz devolverá el primer carácter.
     */
    fun assistantsEvent(
        assistants: List<AssistantEntity>,
        defaultAssistantId: String?,
        requestId: String?,
        mediaLinks: HostMediaLinks,
    ): HostEvent = HostEvent(
        type = TYPE_ASSISTANTS,
        requestId = requestId,
        assistants = assistants.map { assistant ->
            HostAssistant(
                id = assistant.id,
                name = assistant.name,
                avatarUri = mediaLinks.fileOrHttpLink(assistant.avatarImageUrl),
                isDefault = assistant.id == defaultAssistantId,
            )
        },
    )

    /**
     * Detalles de un asistente individual.
     *
     * avatar con [assistantsEvent] El mismo conjunto de enrutamiento de medios.:Reemplace la ruta local con `/api/media/<token>`,
     * `http(s)` Transmisión transparente tal como está.,Si no se puede transferir, devuelve nulo.
     *
     * La palabra clave solo proporciona el número de palabras.([HostAssistantDetail.systemPromptLength]),El texto no se envía.;
     * Persona solo da el resumen truncado([HostAssistantDetail.personaSummary]),Diámetro de corte ver
     * [PERSONA_SUMMARY_LIMIT].
     */
    fun assistantDetailEvent(
        assistant: AssistantEntity?,
        requestId: String?,
        mediaLinks: HostMediaLinks,
    ): HostEvent {
        if (assistant == null) {
            return HostEvent(type = TYPE_ASSISTANT_DETAIL, requestId = requestId, error = ERROR_ASSISTANT_NOT_FOUND)
        }
        return HostEvent(
            type = TYPE_ASSISTANT_DETAIL,
            requestId = requestId,
            assistantDetail = HostAssistantDetail(
                id = assistant.id,
                name = assistant.name,
                avatarUri = mediaLinks.fileOrHttpLink(assistant.avatarImageUrl),
                description = assistant.summary,
                modelId = assistant.modelId,
                providerId = assistant.providerId,
                temperature = assistant.temperature,
                topP = assistant.topP,
                maxTokens = assistant.maxTokens,
                personaSummary = prefixOf(assistant.personaDefinition.trim(), PERSONA_SUMMARY_LIMIT),
                personaTruncated = assistant.personaDefinition.trim().length > PERSONA_SUMMARY_LIMIT,
                systemPromptLength = assistant.systemPrompt.length,
                toolCount = toolCountOf(assistant),
                contextMessageSize = assistant.contextMessageSize,
                reasoningLevel = assistant.reasoningLevel,
                enabled = assistant.enabled,
            ),
        )
    }

    /** amputación de cabeza,No cortes los pares de proxy(emoji espera). */
    private fun prefixOf(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val end = if (Character.isHighSurrogate(text[limit - 1])) limit - 1 else limit
        return text.substring(0, end)
    }

    /** El número de herramientas comprobadas.[AssistantEntity.toolIdsJson] Cuando está roto, cuenta como 0,No dejes que un trozo de JSON sucio abrume los detalles. */
    private fun toolCountOf(assistant: AssistantEntity): Int =
        runCatching { AppJson.decodeFromString<List<String>>(assistant.toolIdsJson) }.getOrDefault(emptyList()).size

    /**
     * Resumen de proveedores y modelos.
     *
     * filtrar [ProviderConfig.hiddenFromSettings]:Dichos proveedores internos no están incluidos originalmente en la lista de configuración de proveedores de la aplicación.,
     * Sólo verá duplicados en la página web.
     */
    fun providersEvent(
        providers: List<ProviderConfig>,
        selectedModelId: String?,
        requestId: String?,
    ): HostEvent = HostEvent(
        type = TYPE_PROVIDERS,
        requestId = requestId,
        selectedModelId = selectedModelId,
        providers = providers.filterNot { it.hiddenFromSettings }.map { provider ->
            HostProvider(
                id = provider.id,
                name = provider.displayName,
                enabled = provider.enabled,
                models = provider.models.map { model -> HostProviderModel(id = model.id, name = model.name) },
            )
        },
    )

    /** metainformación de la sesión;[session] Devuelve la banda si está vacía. [ERROR_SESSION_NOT_FOUND] evento de carga vacía. */
    fun sessionDetailEvent(
        session: SessionEntity?,
        messageCount: Int,
        requestId: String?,
    ): HostEvent {
        if (session == null) {
            return HostEvent(type = TYPE_SESSION_DETAIL, requestId = requestId, error = ERROR_SESSION_NOT_FOUND)
        }
        return HostEvent(
            type = TYPE_SESSION_DETAIL,
            requestId = requestId,
            sessionId = session.id,
            sessionDetail = HostSessionDetail(
                id = session.id,
                title = session.title,
                assistantId = session.assistantId,
                archived = session.archived,
                createdAt = session.createdAt,
                updatedAt = session.updatedAt,
                messageCount = messageCount,
            ),
        )
    }
}
