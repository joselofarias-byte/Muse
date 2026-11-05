package io.zer0.muse.tools

import io.mockk.every
import io.mockk.mockk
import io.zer0.muse.channel.ChannelManager
import io.zer0.muse.channel.mayExecuteChannelToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChannelToolsRiskTest {
    @Test
    fun `registered relay requires approval and cannot run in unattended channel`() {
        val definitions = mutableListOf<ToolRegistry.ToolDef>()
        val registry = mockk<ToolRegistry>(relaxed = true)
        every { registry.register(capture(definitions), any()) } returns Unit

        ChannelToolsRegistrar(registry, mockk<ChannelManager>(relaxed = true)).registerAll()

        val relay = definitions.single { it.name == ChannelToolsRegistrar.TOOL_RELAY_CHANNEL }
        assertEquals(ToolRiskLevel.HIGH, relay.riskLevel)
        assertFalse(mayExecuteChannelToolCall(relay.name, setOf(relay.name), relay.riskLevel))
    }
}
