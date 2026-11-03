package io.zer0.muse.tools.script

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prueba de puerta de capacidad de puenteo de complementos externos. */
class SkillBridgeTest {

    @Test
    fun networkBridgeIsRejectedWhenPluginHasNoNetworkCapability() = runBlocking {
        val result = SkillBridge.tryHandle(
            """{"__bridge__":true,"action":"http_get","params":{"url":"https://example.com"}}""",
            allowedActions = setOf("echo"),
        )

        assertTrue(result is SkillBridge.HandleResult.Failure)
        // v2.x: ALL_ACTIONS se utiliza de forma predeterminada; el conjunto de acciones restringidas se pasa explícitamente aquí y el mecanismo de verificación sigue siendo (message Se ha cambiado a una nueva copia).
        assertTrue((result as SkillBridge.HandleResult.Failure).message.contains("No en el conjunto de acciones de lanzamiento"))
    }

    @Test
    fun allActionsAllowsNetworkBridgeByDefault() = runBlocking {
        // v2.x(Versión Sandbox): Versión completa de forma predeterminada.http_get Ya no se requieren capacidades declarativas de complementos.
        // Nota: En realidad, no envíe solicitudes de red, solo verifique la puerta sin bloquear (url Si es ilegal, será bloqueado por la SSRF/verificación de protocolo en el siguiente nivel).
        val result = SkillBridge.tryHandle(
            """{"__bridge__":true,"action":"device_info","params":{}}""",
            allowedActions = SkillBridge.ALL_ACTIONS,
        )

        assertTrue(result is SkillBridge.HandleResult.Output)
    }

    @Test
    fun allActionsIncludesEveryBridgeAction() {
        // Esto evita que las acciones se eliminen accidentalmente del conjunto de versiones completo en el futuro.
        val expected = setOf(
            "echo", "http_get", "http_post", "fs_list", "fs_read", "fs_write", "fs_delete",
            "clipboard_read", "clipboard_write", "notify", "device_info",
        )
        assertTrue(SkillBridge.ALL_ACTIONS.containsAll(expected))
    }

    @Test
    fun echoBridgeRemainsAvailableForSandboxedPlugin() = runBlocking {
        val result = SkillBridge.tryHandle(
            """{"__bridge__":true,"action":"echo","params":{"value":"ok"}}""",
            allowedActions = setOf("echo"),
        )

        assertTrue(result is SkillBridge.HandleResult.Output)
        assertTrue((result as SkillBridge.HandleResult.Output).json.contains("ok"))
    }
}
