package io.zer0.muse.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Establezca el bloqueo de regresión de estructura para la lista de entradas.
 *
 * Las 14 categorías en la página de inicio, las entradas y el orden de cada categoría, y la integridad de los 48 elementos globales están escritos en [SettingsCatalog] adentro,
 * La página de inicio / página de categoría / índice de búsqueda obtienen datos de esta lista. Aquí afirmamos directamente la lista en sí:
 * Cambiar categorías, cambiar el orden, pegar un elemento en dos categorías, agregar un elemento y omitir un elemento hará que la siguiente aserción falle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsGroupLayoutTest {

    /** 14 Categorías, en orden de arriba a abajo en la página de inicio; las entradas de cada categoría están en orden de arriba a abajo en la página de categorías. */
    private val expectedLayout: List<Pair<SettingsGroupKind, List<SettingsEntryId>>> = listOf(
        SettingsGroupKind.ASSISTANTS to listOf(
            SettingsEntryId.USER_PROFILE,
            SettingsEntryId.ASSISTANTS,
            SettingsEntryId.ASSISTANT_RESOURCES,
            SettingsEntryId.AGENT,
        ),
        SettingsGroupKind.CHAT to listOf(
            SettingsEntryId.CHAT,
            SettingsEntryId.STICKERS,
            SettingsEntryId.QUICK_NOTES,
        ),
        SettingsGroupKind.MODELS to listOf(
            SettingsEntryId.PROVIDER,
            SettingsEntryId.TASK_ROUTING,
            SettingsEntryId.PROVIDER_PLUGINS,
        ),
        SettingsGroupKind.TOOLS to listOf(
            SettingsEntryId.TOOLS,
            SettingsEntryId.PERMISSION_WIZARD,
            SettingsEntryId.WEB_SEARCH,
        ),
        SettingsGroupKind.VOICE to listOf(
            SettingsEntryId.TTS,
            SettingsEntryId.ASR,
            SettingsEntryId.VOICE_CLONING,
        ),
        SettingsGroupKind.IMAGE to listOf(
            SettingsEntryId.IMAGE_VIDEO_GEN,
            SettingsEntryId.VISION,
            SettingsEntryId.TRANSLATE,
        ),
        SettingsGroupKind.MEMORY to listOf(
            SettingsEntryId.MEMORY,
            SettingsEntryId.RAG,
        ),
        SettingsGroupKind.CONNECTION to listOf(
            SettingsEntryId.CONNECTION_CENTER,
            SettingsEntryId.CHANNEL,
            SettingsEntryId.WEB_ACCESS,
            SettingsEntryId.MCP,
            SettingsEntryId.PROXY,
        ),
        SettingsGroupKind.AUTOMATION to listOf(
            SettingsEntryId.PROACTIVE,
            SettingsEntryId.SCHEDULED_TASKS,
            SettingsEntryId.NOTIFICATION_LISTENER,
            SettingsEntryId.UI_AUTOMATION,
            SettingsEntryId.KEEP_ALIVE,
        ),
        SettingsGroupKind.APPEARANCE to listOf(
            SettingsEntryId.APPEARANCE,
            SettingsEntryId.MINIPHONE,
        ),
        SettingsGroupKind.DATA_STORAGE to listOf(
            SettingsEntryId.DATA_MANAGEMENT,
            SettingsEntryId.WORKSPACE,
            SettingsEntryId.BACKUP,
            SettingsEntryId.DATA_IMPORT,
            SettingsEntryId.ARCHIVED_CHATS,
        ),
        SettingsGroupKind.PRIVACY_SECURITY to listOf(
            SettingsEntryId.PII_GUARD,
            SettingsEntryId.SHARE,
        ),
        SettingsGroupKind.ABOUT to listOf(
            SettingsEntryId.TUTORIAL,
            SettingsEntryId.ABOUT,
            SettingsEntryId.CHECK_UPDATE,
        ),
        SettingsGroupKind.DEVELOPER to listOf(
            SettingsEntryId.STATS,
            SettingsEntryId.EXPERIMENTS,
            SettingsEntryId.DEBUG_LOG,
            SettingsEntryId.CRASH_REPORT,
            SettingsEntryId.AUDIT_LOG,
        ),
    )

    @Test
    fun `home lists exactly fourteen categories in the planned order`() {
        assertEquals(
            "La clasificación y el orden de la página de inicio no coinciden con el plan.:\nreal = ${SettingsCatalog.groups.map { it.kind }}\nExpectativa = ${expectedLayout.map { it.first }}",
            expectedLayout.map { it.first },
            SettingsCatalog.groups.map { it.kind },
        )
    }

    @Test
    fun `each group holds the planned entries in order`() {
        expectedLayout.forEach { (kind, expected) ->
            assertEquals(
                "「$kind\"Los artículos y el orden no son consistentes con el plan.",
                expected,
                SettingsCatalog.group(kind).entries.map { it.id },
            )
        }
    }

    @Test
    fun `each group holds the planned number of entries`() {
        assertEquals(
            "El número de entradas en cada categoría no coincide con el plan.:\nreal = ${SettingsCatalog.groups.map { it.entries.size }}\nExpectativa = $EXPECTED_GROUP_SIZES",
            EXPECTED_GROUP_SIZES,
            SettingsCatalog.groups.map { it.entries.size },
        )
    }

    @Test
    fun `all entries appear exactly once`() {
        val ids = SettingsCatalog.entries.map { it.id }
        val duplicates = ids.groupBy { it }.filterValues { it.size > 1 }.keys

        assertEquals("El número total de entradas debe ser 48, pero el número real ${ids.size} artículo: $ids", EXPECTED_ENTRY_COUNT, ids.size)
        assertTrue("La misma entrada aparece en varias categorías.: $duplicates", duplicates.isEmpty())
        assertEquals(
            "La lista debe cubrir todos los ID de artículos y no se puede agregar ni omitir.",
            SettingsEntryId.entries.toSet(),
            ids.toSet(),
        )
    }

    @Test
    fun `category titles use their own keys and do not repeat entry titles`() {
        val groupTitleRes = SettingsCatalog.groups.map { it.titleRes }
        assertEquals("14 Cada categoría debe tener su propia clave de título.", SETTINGS_GROUP_COUNT, groupTitleRes.distinct().size)

        val entryTitleRes = SettingsCatalog.entries.map { it.titleRes }
        val shared = groupTitleRes.filter { it in entryTitleRes }
        assertTrue("El título de la categoría y el título del elemento comparten una clave de recurso.: $shared", shared.isEmpty())
    }

    @Test
    fun `about and developer are separate single card categories`() {
        listOf(
            SettingsGroupKind.ABOUT to listOf(
                SettingsEntryId.TUTORIAL,
                SettingsEntryId.ABOUT,
                SettingsEntryId.CHECK_UPDATE,
            ),
            SettingsGroupKind.DEVELOPER to listOf(
                SettingsEntryId.STATS,
                SettingsEntryId.EXPERIMENTS,
                SettingsEntryId.DEBUG_LOG,
                SettingsEntryId.CRASH_REPORT,
                SettingsEntryId.AUDIT_LOG,
            ),
        ).forEach { (kind, expected) ->
            val group = SettingsCatalog.group(kind)
            assertEquals("「$kind\"Debería haber una sola tarjeta", 1, group.sections.size)
            assertEquals("「$kind\"Los artículos y el orden no son consistentes con el plan.", expected, group.entries.map { it.id })
            assertNull("「$kind\"No deberías volver a aplicar títulos de partición\".", group.sections.single().labelRes)
        }
    }

    @Test
    fun `entries that are switches stay inside their category page`() {
        val toggles = SettingsCatalog.entries.filter { it.action is SettingsEntryAction.Toggle }
        assertFalse("La lista debe mantener la entrada del interruptor (PII guardia)", toggles.isEmpty())
        assertEquals(
            listOf(SettingsEntryId.PII_GUARD),
            toggles.map { it.id },
        )
        val owner = SettingsCatalog.groups.single { group ->
            group.entries.any { it.id == SettingsEntryId.PII_GUARD }
        }
        assertEquals("La entrada del interruptor debe colgarse en la categoría de primer nivel a la que pertenece.", SettingsGroupKind.PRIVACY_SECURITY, owner.kind)
    }

    private companion object {
        const val SETTINGS_GROUP_COUNT = 14
        const val EXPECTED_ENTRY_COUNT = 48

        /** El número de entradas correspondientes al diseño esperado anterior se utiliza para bloquear categorías individuales que estén distribuidas uniformemente y no sean demasiado grandes. */
        val EXPECTED_GROUP_SIZES: List<Int> = listOf(4, 3, 3, 3, 3, 3, 2, 5, 5, 2, 5, 2, 3, 5)
    }
}
