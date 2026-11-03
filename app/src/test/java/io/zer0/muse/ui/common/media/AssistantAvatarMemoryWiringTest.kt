package io.zer0.muse.ui.common.media

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class AssistantAvatarMemoryWiringTest {

    @Test
    fun `assistant avatar constrains Coil decode to rendered size`() {
        val source =
            listOf(
                Path.of("src/main/java/io/zer0/muse/ui/common/media/AssistantAvatar.kt"),
                Path.of("app/src/main/java/io/zer0/muse/ui/common/media/AssistantAvatar.kt"),
            )
                .firstOrNull(Files::exists)
                ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
                ?: error("AssistantAvatar.kt not found")

        assertTrue("Las solicitudes de avatar deben pasar por dimensiones acotadas helper", source.contains("assistantAvatarImageRequest"))
        assertTrue("La solicitud de avatar debe pasar el tamaño de renderizado y no se puede decodificar de acuerdo con la imagen original.", source.contains(".size(boundedSize, boundedSize)"))
        assertTrue("El tamaño de renderizado debe convertirse de las restricciones de dp reales a píxeles", source.contains("density.density"))
    }
}
