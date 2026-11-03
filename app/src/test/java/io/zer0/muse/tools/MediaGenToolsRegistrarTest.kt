package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P2-23 Regresar:herramientas de generación de medios(fotos/vídeo/código qr)Se cambió a la barandilla del libro de registro después del registro durante el período de inicio.
 *
 * Antes de la migración, estas tres herramientas estaban compuestas por ChatViewModel.registerMediaTools() en"El usuario ha abierto la página de chat."Regístrate sólo cuando,
 * No se pueden encontrar en los enlaces de tareas programadas, chat grupal o subagente. ahora por [MediaGenToolsRegistrar] Cuando se inicia la aplicación
 * ([ToolRegistrarBootstrapper])Registrarse,Esta prueba no crea ChatViewModel ni inicia ningún UI,
 * Sólo verificar"Después del registro, se puede encontrar en ToolRegistry, el nivel de riesgo no se reduce y, de hecho, el actuador está cableado.".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaGenToolsRegistrarTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Construir el registro:Generar todas las dependencias relaxed mock(Esta prueba solo se preocupa por los resultados del registro.,No se realiza ninguna construcción real). */
    private fun buildRegistrar(registry: ToolRegistry): MediaGenToolsRegistrar = MediaGenToolsRegistrar(
        toolRegistry = registry,
        impl = MediaGenToolsImpl(
            context = context,
            settings = mockk(relaxed = true),
            imageService = mockk(relaxed = true),
            videoGenerationService = mockk(relaxed = true),
            voiceSynthesizer = mockk(relaxed = true),
            voiceMessageStore = mockk(relaxed = true),
        ),
    )

    @Test
    fun `registrar registers image video and qr tools with unchanged risk levels`() {
        val registry = ToolRegistry(context)
        buildRegistrar(registry)

        val defs = registry.listTools().associateBy { it.name }
        listOf("generate_image", "generate_video", "generate_qr_code").forEach { name ->
            assertNotNull("$name No registrado,El enlace de registro durante el período de inicio está roto.", defs[name])
        }
        // El nivel de riesgo debe ser el mismo que antes de la migración. ChatViewModel.registerMediaTools() completamente consistente:
        // imagen cruda/vídeo en bruto = HIGH(Consumir cuota externa y cargar descripción de usuario),código qr = SAFE(codificación local pura)
        assertEquals(ToolRiskLevel.HIGH, defs.getValue("generate_image").riskLevel)
        assertEquals(ToolRiskLevel.HIGH, defs.getValue("generate_video").riskLevel)
        assertEquals(ToolRiskLevel.SAFE, defs.getValue("generate_qr_code").riskLevel)
    }

    @Test
    fun `media tools stay in built-in tool ids so default assistants keep them`() {
        listOf("generate_image", "generate_video", "generate_qr_code").forEach { name ->
            assertTrue(
                "$name aquí no BUILT_IN_TOOL_IDS,La herramienta no será visible para el asistente predeterminado.",
                ToolRegistry.BUILT_IN_TOOL_IDS.contains(name),
            )
        }
    }

    @Test
    fun `registered executors are wired to the impl`() = runBlocking {
        val registry = ToolRegistry(context)
        buildRegistrar(registry)

        // No activa ninguna red/generar:La lambda registrada cuando faltan parámetros requeridos realmente debe llamarse(en lugar de registro vacío).
        val image = registry.execute("generate_image", emptyMap())
        assertTrue("generate_image Actuador no cableado: ${image.content}", image.isError)
        assertTrue(image.content.contains("prompt"))

        val video = registry.execute("generate_video", emptyMap())
        assertTrue("generate_video Actuador no cableado: ${video.content}", video.isError)
        assertTrue(video.content.contains("prompt"))

        val qr = registry.execute("generate_qr_code", emptyMap())
        assertTrue("generate_qr_code Actuador no cableado: ${qr.content}", qr.isError)
        assertTrue(qr.content.contains("content"))
    }

    @Test
    fun `params metadata survives the move`() {
        val registry = ToolRegistry(context)
        buildRegistrar(registry)

        val defs = registry.listTools().associateBy { it.name }
        val image = defs.getValue("generate_image")
        assertEquals(setOf("prompt"), image.required)
        // La clave de parámetro inyectada en el mapa de referencia local de la tarjeta de aprobación aún debe estar en la tabla de parámetros.(De lo contrario, la imagen da origen a la imagen./La edición de fotografías ha vuelto)
        assertTrue("generate_image Falta la lista de parámetros reference_image", image.parameters.containsKey("reference_image"))
        assertEquals("integer", image.parameterTypes["n"])
        assertEquals(setOf("prompt"), defs.getValue("generate_video").required)
        assertEquals(setOf("content"), defs.getValue("generate_qr_code").required)
    }
}
