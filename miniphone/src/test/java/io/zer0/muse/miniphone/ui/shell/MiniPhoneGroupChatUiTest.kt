package io.zer0.muse.miniphone.ui.shell

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.api.MpProfile
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cree una entrada grupal y una conversación grupal en la página de mensajes.
 *
 * Pruebe dos cosas por separado:¿Se pueden separar grupos y mensajes privados de un vistazo en la lista?(Nombre y etiqueta de vista previa),
 * y "+"Después de ingresar, seleccionar personas y completar esta cadena, regresará con la identificación seleccionada para crear un grupo y unirse al grupo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneGroupChatUiTest {

    @get:Rule
    val compose = createComposeRule()

    private var db: MiniPhoneDb? = null

    @After
    fun tearDown() {
        db?.close()
    }

    @Test
    fun groupThreadSitsInTheListWithItsTitleAndAPreviewMark() {
        val state = loadState(
            threads = listOf(
                thread(id = "t-group", characterId = "c1", at = NOW, preview = "Nos vemos por la tarde", isGroup = true, title = "Ayuan, Xiaoman, yo"),
                thread(id = "t-dm", characterId = "c1", at = NOW - 60_000L, preview = "¿Está ahí?"),
            ),
            characters = listOf(MpCharacter(id = "c1", name = "Ayuan")),
        )
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneShell(state = state)
            }
        }
        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.MESSAGES)).performClick()

        compose.onNodeWithTag(MiniPhoneTags.thread("t-group")).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneTags.thread("t-dm")).assertIsDisplayed()
        compose.onNodeWithText("Ayuan, Xiaoman, yo").assertIsDisplayed()
        compose.onNodeWithText("[grupo] Nos vemos por la tarde").assertIsDisplayed()
        compose.onNodeWithText("¿Está ahí?").assertIsDisplayed()
        assertEquals("[grupo] ¿Está ahí?", groupPreviewOf("¿Está ahí?"))
        assertEquals("Debería poder identificar el grupo incluso si no hay un último mensaje.", "[grupo]", groupPreviewOf(null))
    }

    @Test
    fun finishStaysDisabledUntilTwoPeopleAreSelectedAndThenCreatesTheGroup() {
        var created: List<String>? = null
        val sections = contactSectionsOf(
            listOf(
                MpCharacter(id = "c1", name = "Ayuan"),
                MpCharacter(id = "c2", name = "Xiaoman"),
                MpCharacter(id = "c3", name = "Awu"),
            ),
        )
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneShell(
                    state = ShellUiState(contactSections = sections),
                    chatContent = { threadId, _ ->
                        Text(text = "chat:$threadId", modifier = Modifier.testTag(FAKE_CHAT_TAG))
                    },
                    actions = MiniPhoneShellActions(
                        onCreateGroup = { ids ->
                            created = ids
                            "t-group"
                        },
                    ),
                )
            }
        }
        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.MESSAGES)).performClick()
        compose.onNodeWithTag(MiniPhoneTags.GROUP_CREATE_ENTRY).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneTags.GROUP_CREATE_ENTRY).performClick()

        compose.onNodeWithTag(MiniPhoneTags.GROUP_CREATE_PAGE).assertIsDisplayed()
        // no elijas a nadie:No puedo presionar Listo,No podré crear un grupo incluso si hago clic en él.
        compose.onNodeWithTag(MiniPhoneTags.GROUP_CREATE_DONE).assertIsNotEnabled()
        compose.onNodeWithTag(MiniPhoneTags.GROUP_CREATE_DONE).performClick()
        compose.waitForIdle()
        assertEquals("No puedes crear un grupo sin seleccionar personas", null, created)

        // Elige solo uno:Todavía no puedo presionarlo
        compose.onNodeWithTag(MiniPhoneTags.groupPick("c1")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(MiniPhoneTags.GROUP_CREATE_COUNT).assertTextEquals("1 persona seleccionada,Elige al menos 2 personas")
        compose.onNodeWithTag(MiniPhoneTags.GROUP_CREATE_DONE).assertIsNotEnabled()

        // Elige dos:Completo disponible,Haga clic para crear un grupo con estos dos ID y únase al grupo directamente
        compose.onNodeWithTag(MiniPhoneTags.groupPick("c2")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(MiniPhoneTags.GROUP_CREATE_DONE).assertIsEnabled()
        compose.onNodeWithTag(MiniPhoneTags.GROUP_CREATE_DONE).performClick()
        compose.waitForIdle()

        assertEquals(listOf("c1", "c2"), created)
        compose.onNodeWithTag(FAKE_CHAT_TAG).assertTextEquals("chat:t-group")
    }

    @Test
    fun pickedRowsShowTheirCheckMarkAndCanBeUnpicked() {
        val sections = contactSectionsOf(listOf(MpCharacter(id = "c1", name = "Ayuan"), MpCharacter(id = "c2", name = "Xiaoman")))
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                GroupCreatePage(
                    sections = sections,
                    selectedIds = setOf("c1"),
                    onToggle = { },
                    onBack = { },
                    onDone = { },
                )
            }
        }

        compose.onNodeWithTag(MiniPhoneTags.groupPickMark("c1"), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(MiniPhoneTags.GROUP_CREATE_COUNT).assertTextEquals("1 persona seleccionada,Elige al menos 2 personas")
    }

    /** Léelo de nuevo,Por cierto, verifique que el nombre del grupo y la etiqueta de vista previa hayan pasado de la entidad a la lista. */
    private fun loadState(threads: List<MpThreadEntity>, characters: List<MpCharacter>): ShellUiState {
        val database = newInMemoryMiniPhoneDb()
        db = database
        return runBlocking {
            threads.forEach { database.threadDao().upsert(it) }
            assembleShellState(
                threads = database.threadDao().observeThreads().first(),
                characters = characters,
                profile = MpProfile(nickname = "Ziqi"),
            )
        }
    }

    private fun thread(
        id: String,
        characterId: String,
        at: Long,
        preview: String,
        isGroup: Boolean = false,
        title: String = "",
    ): MpThreadEntity = MpThreadEntity(
        id = id,
        characterId = characterId,
        lastMessagePreview = preview,
        lastMessageAt = at,
        isGroup = isGroup,
        title = title,
    )

    private companion object {
        const val NOW: Long = 1_700_000_000_000L
        const val FAKE_CHAT_TAG: String = "mp-group-fake-chat"
    }
}
