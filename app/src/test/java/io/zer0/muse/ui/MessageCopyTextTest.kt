package io.zer0.muse.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageCopyTextTest {

    @Test
    fun `copy copies body only and never appends reasoning`() {
        // Comentarios de los usuarios "Lo que obtienes cuando haces clic en copiar es pensar": cuando tanto el texto como el pensamiento están presentes, solo se copia el texto.
        // Si quieres pensar, hay un botón especial "Copiar pensamiento". No debes confiar en el botón copiar.
        assertEquals("answer", messageCopyText("answer", "thinking"))
    }

    @Test
    fun `copy body with internal markers is sanitized`() {
        assertEquals("answer", messageCopyText("answer", null))
    }

    @Test
    fun `copy falls back to reasoning for reasoning-only message`() {
        assertEquals("thinking", messageCopyText("", "thinking"))
    }
}
