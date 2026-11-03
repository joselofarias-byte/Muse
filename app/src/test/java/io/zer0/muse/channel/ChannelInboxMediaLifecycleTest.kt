package io.zer0.muse.channel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong

/**
 * El ciclo de vida de las imágenes base64 en la bandeja de entrada.
 *
 * La bandeja de entrada es**Colocación de toda la mesa**(`channel_inbox.json`), cada vez que registra/recibe/completa la distribución, el máximo
 * [ChannelInbox.MAX_PENDING_ITEMS] Los registros se vuelven a serializar. La imagen base64 es el único campo grande en este enlace ——
 * Una vez que permanece en la entrada del historial, este byte debe moverse nuevamente para cada transición de estado posterior.
 *
 * Momento correcto: la imagen se ha copiado en ChannelConversationStore cuando se construye el contexto de respuesta automática.
 * entonces**estado final**(Completado/ignorado) debe borrarse; inacabado (PENDING / PROCESSING / BLOCKED)
 * debe ser retenido —— También se utiliza para reintentos, recuperación y revisión manual.
 */
class ChannelInboxMediaLifecycleTest {

    private lateinit var directory: File
    private lateinit var inboxFile: File
    private lateinit var ledgerFile: File
    private lateinit var journal: ChannelInboxJournal
    private val nowMillis = AtomicLong(1_800_000_000_000L)

    /** 1KB La imagen falsa es base64 (lo suficientemente grande como para notarse en el texto del archivo). */
    private val imageBase64 = "iVBORw0KGgo" + "A".repeat(1_000)

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("inbox-media-test").toFile()
        inboxFile = File(directory, "inbox.json")
        ledgerFile = File(directory, "events.json")
        journal = ChannelInboxJournal(nowMillis = { nowMillis.get() })
        journal.attach(inboxFile, ledgerFile)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }


    /** Todos los fragmentos + el texto del archivo único antiguo (se puede afirmar antes y después de la migración). */
    private fun persistedText(): String {
        val dir = File(directory, ChannelInbox.INBOX_DIR_NAME)
        val shards = dir.listFiles().orEmpty().filter { it.isFile }
        val legacy = inboxFile.takeIf { it.exists() }?.let { listOf(it) }.orEmpty()
        return (shards + legacy).joinToString(separator = "") { it.readText() }
    }
    private fun source(eventId: String) = ChannelInbox.Source(
        platform = "QQ",
        from = "user",
        channelId = "qq-a",
        eventId = eventId,
    )

    private fun imageInbound(eventId: String) = ChannelInbox.Inbound(
        platform = "QQ",
        from = "user",
        summary = "Mensaje con imagen",
        mediaKind = "image",
        mediaBase64 = imageBase64,
        sourceChannelId = "qq-a",
        sourceEventId = eventId,
        dispatchId = "dispatch-$eventId",
        dispatchState = ChannelInbox.DispatchState.PENDING,
    )

    @Test
    fun `completed_dispatch_drops_image_bytes_from_inbox`() {
        journal.record(source("e1"), imageInbound("e1"))
        val claimed = journal.claimNextPending()!!
        assertEquals("El avatar aún debe conservarse durante la distribución./Bytes de imagen utilizados para construir solicitudes visuales.", imageBase64, claimed.mediaBase64)

        journal.completeDispatch(claimed.dispatchId, ignored = false)

        val stored = journal.snapshot().single()
        assertEquals(ChannelInbox.DispatchState.COMPLETED, stored.dispatchState)
        assertEquals("Una vez completado, la bandeja de entrada ya no debe contener bytes de imagen.", "", stored.mediaBase64)
        assertTrue(
            "Este base64 ya no debería aparecer en el archivo del disco (de lo contrario, se moverá repetidamente cada vez que se migre el estado)",
            !persistedText().contains("A".repeat(200)),
        )
    }

    @Test
    fun `ignored_dispatch_also_drops_image_bytes`() {
        journal.record(source("e2"), imageInbound("e2"))
        val claimed = journal.claimNextPending()!!
        journal.completeDispatch(claimed.dispatchId, ignored = true)
        assertEquals("", journal.snapshot().single().mediaBase64)
    }

    @Test
    fun `blocked_dispatch_keeps_image_bytes_for_diagnosis`() {
        journal.record(source("e3"), imageInbound("e3"))
        val claimed = journal.claimNextPending()!!
        journal.blockDispatch(claimed.dispatchId, "agent_checkpoint_unreadable")

        val stored = journal.snapshot().single()
        assertEquals(ChannelInbox.DispatchState.BLOCKED, stored.dispatchState)
        assertEquals("Es posible que también necesites mirar esta imagen para una revisión manual.BLOCKED debe ser retenido", imageBase64, stored.mediaBase64)
    }

    @Test
    fun `pending_retry_keeps_image_bytes`() {
        journal.record(source("e4"), imageInbound("e4"))
        val claimed = journal.claimNextPending()!!
        journal.retryDispatch(claimed.dispatchId)

        val stored = journal.snapshot().single()
        assertEquals(ChannelInbox.DispatchState.PENDING, stored.dispatchState)
        assertEquals("Reintentar también requiere reconstruir la solicitud, que debe conservarse", imageBase64, stored.mediaBase64)
    }

    @Test
    fun `legacy_completed_entry_is_stripped_on_restore`() {
        // Simule la ubicación de versiones antiguas: las entradas del historial contienen base64.
        val legacy = ChannelInbox.Inbound(
            platform = "QQ",
            from = "user",
            summary = "fotos antiguas",
            mediaKind = "image",
            mediaBase64 = imageBase64,
            dispatchId = "old-1",
            dispatchState = ChannelInbox.DispatchState.COMPLETED,
        )
        inboxFile.writeText(
            kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(ChannelInbox.Inbound.serializer()),
                listOf(legacy),
            ),
        )

        val restored = ChannelInboxJournal(nowMillis = { nowMillis.get() })
        restored.attach(inboxFile, ledgerFile)

        assertEquals("", restored.snapshot().single().mediaBase64)
        assertTrue(
            "Los resultados de la reducción deben reescribirse inmediatamente después de la recuperación para evitar tener que moverlos nuevamente la próxima vez.",
            !persistedText().contains("A".repeat(200)),
        )
    }
}
