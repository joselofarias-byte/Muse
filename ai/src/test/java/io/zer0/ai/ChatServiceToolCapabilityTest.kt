package io.zer0.ai

import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelAbility
import io.zer0.ai.core.ModelVerification
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.ai.core.ToolDefinition
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatServiceToolCapabilityTest {

    private val tools = listOf(
        ToolDefinition(
            name = "echo",
            description = "Echo text",
            parametersJsonSchema = """{"type":"object","properties":{}}""",
        ),
    )

    private val mcpTools = listOf(
        ToolDefinition(
            name = "mcp_feishu_remote__search-doc",
            description = "Search a Feishu document",
            parametersJsonSchema = """{"type":"object","properties":{}}""",
        ),
    )

    @Test
    fun unknownRelayModelKeepsToolsEnabled() {
        val model = Model(
            id = "custom-relay-model",
            providerId = "relay",
            verification = ModelVerification.UNVERIFIED,
        )
        val config = ProviderConfig(
            id = "relay",
            displayName = "Relay",
            type = ProviderType.OPENAI,
            baseUrl = "https://relay.example.com/v1",
        )

        assertTrue(shouldSendTools(model, config, tools))
    }

    @Test
    fun explicitlyNonToolModelDropsTools() {
        // v2.6.1: “Explícitamente no soportado”Finalizado por la matriz de capacidades del proveedor (deepseek-reasoner No enviar herramientas),
        // en vez de“abilities No vacío y nada. TOOL”.
        val model = Model(
            id = "deepseek-reasoner",
            providerId = "deepseek",
            abilities = setOf(ModelAbility.REASONING),
        )
        val config = ProviderConfig(
            id = "deepseek",
            displayName = "DeepSeek",
            type = ProviderType.OPENAI,
            baseUrl = "https://api.deepseek.com/v1",
        )

        assertFalse(shouldSendTools(model, config, tools))
    }

    @Test
    fun partialCapabilityDeclarationStillSendsTools() {
        // v2.6.1 Regresión: el directorio de la nube solo escribe razonamiento pero omite el uso de herramientas (como deepseek-flash,
        // abilities=[REASONING]), no corte toda la cadena de herramientas —— No declarado ≠ No compatible.
        val model = Model(
            id = "deepseek-flash",
            providerId = "deepseek",
            abilities = setOf(ModelAbility.REASONING),
        )
        val config = ProviderConfig(
            id = "deepseek",
            displayName = "DeepSeek",
            type = ProviderType.OPENAI,
            baseUrl = "https://api.deepseek.com/v1",
        )

        assertTrue(shouldSendTools(model, config, tools))
    }

    @Test
    fun userEditedModelWithToolsAlwaysSends() {
        // v2.6.1: Las configuraciones editadas explícitamente por el usuario tienen la máxima prioridad —— El usuario abre la herramienta y la envía.
        val model = Model(
            id = "fictional-model-x",
            providerId = "relay",
            abilities = setOf(ModelAbility.TOOL),
            userEdited = true,
        )
        val config = ProviderConfig(
            id = "relay",
            displayName = "Relay",
            type = ProviderType.OPENAI,
            baseUrl = "https://relay.example.com/v1",
        )

        assertTrue(shouldSendTools(model, config, tools))
    }

    @Test
    fun userEditedModelWithoutToolsNeverSends() {
        // v2.6.1: El usuario edita explícitamente“solo razonamiento”Incluso si la matriz de capacidades lo admite de forma predeterminada, no se exceda en su autoridad.
        val model = Model(
            id = "fictional-model-y",
            providerId = "relay",
            abilities = setOf(ModelAbility.REASONING),
            userEdited = true,
        )
        val config = ProviderConfig(
            id = "relay",
            displayName = "Relay",
            type = ProviderType.OPENAI,
            baseUrl = "https://relay.example.com/v1",
        )

        assertFalse(shouldSendTools(model, config, tools))
    }

    @Test
    fun explicitlyBoundMcpToolsAreNotSilentlyDroppedByUnknownModelMetadata() {
        val model = Model(
            id = "custom-relay-model",
            providerId = "relay",
        )
        val config = ProviderConfig(
            id = "relay",
            displayName = "Relay",
            type = ProviderType.OPENAI,
            baseUrl = "https://relay.example.com/v1",
        )

        assertTrue(shouldSendTools(model, config, mcpTools))
    }

    @Test
    fun explicitlyNonToolModelStillDropsMcpTools() {
        // v2.6.1: “Explícitamente no soportado”Aún determinado por la matriz de competencias (deepseek-reasoner).
        val model = Model(
            id = "deepseek-reasoner",
            providerId = "relay",
            abilities = setOf(ModelAbility.REASONING),
        )
        val config = ProviderConfig(
            id = "relay",
            displayName = "Relay",
            type = ProviderType.OPENAI,
            baseUrl = "https://relay.example.com/v1",
        )

        assertFalse(shouldSendTools(model, config, mcpTools))
    }

    @Test
    fun emptyToolListIsNotSent() {
        val model = Model(
            id = "custom-relay-model",
            providerId = "relay",
            verification = ModelVerification.UNVERIFIED,
        )
        val config = ProviderConfig(
            id = "relay",
            displayName = "Relay",
            type = ProviderType.OPENAI,
            baseUrl = "https://relay.example.com/v1",
        )

        assertFalse(shouldSendTools(model, config, emptyList()))
    }
}
