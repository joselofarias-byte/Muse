package io.zer0.muse.miniphone.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageType
import io.zer0.muse.miniphone.ui.shell.MiniPhoneTags
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tres diferencias entre las interfaces de chat grupal:Escriba el nombre del grupo y la cantidad de personas en la columna superior, escriba quién lo dijo junto a la burbuja de roles y haga clic en la columna superior para ver los miembros.
 *
 * La afirmación recae en "¿Es correcto el texto/existe esa línea/es estable el orden?",El estado es alimentado directamente por la prueba.
 * La afirmación inversa en el lado de los mensajes privados es igualmente importante.:Si los nombres extras también aparecen en 1:1,Toda esta pieza se convierte en ruido.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneGroupChatUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun groupTopBarShowsTheTitleWithTheMemberCount() {
        render(
            groupState(
                message("m1", characterId = "c1", content = "en"),
            ),
        )

        compose.onNodeWithTag(MiniPhoneChatTags.PEER_NAME, useUnmergedTree = true).assertTextEquals("Ayuan, Xiaoman, yo")
        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_MEMBER_COUNT, useUnmergedTree = true).assertTextEquals("(3)")
    }

    @Test
    fun characterBubblesInAGroupSayWhoIsTalking() {
        render(
            groupState(
                message("m1", characterId = "c2", content = "Xiaoman está aquí"),
                message("m2", characterId = "c1", content = "Ayuan también está ahí", createdAt = TWO_MINUTES_MS),
            ),
        )

        compose.onNodeWithTag(MiniPhoneChatTags.senderName("m1"), useUnmergedTree = true).assertTextEquals("Xiaoman")
        compose.onNodeWithTag(MiniPhoneChatTags.senderName("m2"), useUnmergedTree = true).assertTextEquals("Ayuan")
    }

    @Test
    fun ownMessageInAGroupCarriesNoSpeakerName() {
        render(groupState(message("mine", characterId = "", content = "Buenos dias a todos", sender = MpActorType.USER)))

        compose.onNodeWithTag(MiniPhoneChatTags.senderName("mine"), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(MiniPhoneChatTags.bubble("mine")).assertIsDisplayed()
    }

    @Test
    fun directMessagesStayWithoutSpeakerNames() {
        render(
            ChatUiState(
                threadId = "t1",
                characterId = "c1",
                peerName = "Ayuan",
                messages = listOf(message("m1", characterId = "c1", content = "en")),
            ),
        )

        compose.onNodeWithTag(MiniPhoneChatTags.PEER_NAME, useUnmergedTree = true).assertTextEquals("Ayuan")
        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_MEMBER_COUNT).assertDoesNotExist()
        compose.onNodeWithTag(MiniPhoneChatTags.senderName("m1"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun tappingTheTopBarListsEveryMemberInJoinOrder() {
        render(groupState(message("m1", characterId = "c1", content = "en")))

        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_TOP_BAR).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_MEMBERS_PAGE).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_MEMBERS_COUNT).assertTextEquals("(3)")
        val tops = MEMBER_IDS.map { id ->
            compose.onNodeWithTag(MiniPhoneChatTags.member(id)).getBoundsInRoot().top
        }
        assertEquals("Los miembros presionan para unirse al grupo de arriba a abajo.", tops.sorted(), tops)
        MEMBER_IDS.forEach { id ->
            compose.onNodeWithTag(MiniPhoneChatTags.member(id)).assertIsDisplayed()
        }

        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_MEMBERS_BACK).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_MEMBERS_PAGE).assertDoesNotExist()
        compose.onNodeWithTag(MiniPhoneChatTags.MESSAGE_LIST).assertIsDisplayed()
    }

    @Test
    fun typingRowNamesTheCharacterWhoIsAboutToSpeak() {
        render(
            groupState(message("m1", characterId = "c1", content = "en"))
                .copy(isGenerating = true, typingName = "Xiaoman", typingAvatarUri = null),
        )

        compose.onNodeWithTag(MiniPhoneChatTags.TYPING).assertExists()
        compose.onNodeWithTag(MiniPhoneChatTags.TYPING_NAME, useUnmergedTree = true).assertTextEquals("Xiaoman")
    }

    @Test
    fun directChatTypingRowStaysNameless() {
        render(
            ChatUiState(threadId = "t1", characterId = "c1", peerName = "Ayuan", isGenerating = true),
        )

        compose.onNodeWithTag(MiniPhoneChatTags.TYPING).assertExists()
        compose.onNodeWithTag(MiniPhoneChatTags.TYPING_NAME, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun memberCountIncludesThePhoneOwner() {
        val state = groupState()
        assertEquals("Dos personajes + yo", 3, state.memberCount)
        assertEquals(0, ChatUiState(peerName = "Ayuan").memberCount)
    }

    @Test
    fun membersPageRenamesTheGroupAndRefusesABlankName() {
        var renamed: String? = null
        renderMembers(actions = GroupAdminActions(onRename = { title -> renamed = title }))

        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_TITLE_ROW).performClick()
        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_TITLE_EDIT_DIALOG).assertExists()

        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_TITLE_EDIT_FIELD).performTextClearance()
        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_TITLE_EDIT_SAVE).assertIsNotEnabled()
        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_TITLE_EDIT_SAVE).performClick()
        compose.waitForIdle()
        assertEquals("No se enviará el nombre vacío", null, renamed)

        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_TITLE_EDIT_FIELD).performTextInput("equipo de fin de semana")
        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_TITLE_EDIT_SAVE).performClick()
        compose.waitForIdle()

        assertEquals("equipo de fin de semana", renamed)
    }

    @Test
    fun addMemberPageListsOnlyPeopleWhoAreNotInTheGroup() {
        var added: List<String>? = null
        renderMembers(
            characters = FIVE_CHARACTERS,
            actions = GroupAdminActions(onAddMembers = { ids -> added = ids }),
        )

        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_ADD_ENTRY).performClick()
        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_ADD_PAGE).assertExists()

        compose.onNodeWithTag(MiniPhoneTags.groupPick("c1")).assertDoesNotExist()
        compose.onNodeWithTag(MiniPhoneTags.groupPick("c2")).assertDoesNotExist()
        compose.onNodeWithTag(MiniPhoneTags.groupPick("c3")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_ADD_DONE).performClick()
        compose.waitForIdle()

        assertEquals(listOf("c3"), added)
    }

    @Test
    fun removingAMemberNeedsConfirmation() {
        var removed: String? = null
        renderMembers(actions = GroupAdminActions(onRemoveMember = { id -> removed = id }))

        compose.onNodeWithTag(MiniPhoneChatTags.member("c2")).performTouchInput { longClick() }
        compose.onNodeWithTag(MiniPhoneChatTags.MEMBER_MENU_REMOVE).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneChatTags.MEMBER_MENU_REMOVE).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(MiniPhoneChatTags.MEMBER_REMOVE_DIALOG).assertIsDisplayed()
        compose.onNodeWithText("Lo que dijo antes permanecerá en el grupo después de ser eliminado.").assertExists()
        assertEquals("No te mudes hasta que se confirme", null, removed)

        compose.onNodeWithTag(MiniPhoneChatTags.MEMBER_REMOVE_CONFIRM).performClick()
        compose.waitForIdle()

        assertEquals("c2", removed)
    }

    @Test
    fun theLastMemberHasNoRemoveEntry() {
        renderMembers(members = listOf(MEMBERS.first()))

        compose.onNodeWithTag(MiniPhoneChatTags.member("c1")).performTouchInput { longClick() }
        compose.waitForIdle()

        compose.onNodeWithTag(MiniPhoneChatTags.MEMBER_MENU_REMOVE).assertDoesNotExist()
        assertTrue("No hay ninguna acción que tomar cuando solo queda una persona.", groupMemberActionsFor(1).isEmpty())
        assertEquals(1, groupMemberActionsFor(2).size)
    }

    @Test
    fun disbandingAsksFirstAndSaysItCannotBeUndone() {
        var disbanded = false
        renderMembers(actions = GroupAdminActions(onDisband = { disbanded = true }))

        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_DISBAND_ENTRY).performClick()
        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_DISBAND_DIALOG).assertExists()
        assertTrue(
            "La copia debe indicar que no es recuperable.",
            compose.onAllNodesWithText("Todo el grupo y todos los mensajes que contiene se eliminarán de este teléfono.,No se puede recuperar.").fetchSemanticsNodes().isNotEmpty(),
        )
        assertFalse("No disuelto antes de la confirmación", disbanded)

        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_DISBAND_CONFIRM).performClick()
        compose.waitForIdle()

        assertTrue(disbanded)
    }

    @Test
    fun privateChatsHaveNoMemberManagementEntry() {
        render(ChatUiState(threadId = "t1", characterId = "c1", peerName = "Ayuan"))

        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_TOP_BAR).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_MEMBERS_PAGE).assertDoesNotExist()
        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_ADD_ENTRY).assertDoesNotExist()
        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_DISBAND_ENTRY).assertDoesNotExist()
    }

    private fun renderMembers(
        members: List<ChatMemberUi> = MEMBERS,
        characters: List<MpCharacter> = emptyList(),
        actions: GroupAdminActions = GroupAdminActions(),
    ) {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                GroupMembersPage(
                    members = members,
                    title = "Ayuan, Xiaoman, yo",
                    characters = characters,
                    onBack = {},
                    actions = actions,
                )
            }
        }
    }

    private fun render(state: ChatUiState) {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneChatScreen(state = state, onBack = {}, onLoadOlder = {})
            }
        }
    }

    private fun groupState(vararg messages: ChatMessageUi): ChatUiState = ChatUiState(
        threadId = "t-group",
        characterId = "c1",
        isGroup = true,
        groupTitle = "Ayuan, Xiaoman, yo",
        members = MEMBERS,
        selfName = "Ziqi",
        messages = messages.toList(),
    )

    private fun message(
        id: String,
        characterId: String,
        content: String,
        createdAt: Long = MINUTE_MS,
        sender: MpActorType = MpActorType.CHARACTER,
    ): ChatMessageUi = ChatMessageUi(
        id = id,
        characterId = characterId,
        sender = sender,
        type = MpMessageType.TEXT,
        content = content,
        createdAt = createdAt,
    )

    private companion object {
        const val MINUTE_MS: Long = 60_000L
        const val TWO_MINUTES_MS: Long = 120_000L
        val MEMBERS: List<ChatMemberUi> = listOf(
            ChatMemberUi(characterId = "c1", name = "Ayuan"),
            ChatMemberUi(characterId = "c2", name = "Xiaoman"),
        )
        val MEMBER_IDS: List<String> = MEMBERS.map { it.characterId }

        val FIVE_CHARACTERS: List<MpCharacter> = listOf(
            MpCharacter(id = "c1", name = "Ayuan"),
            MpCharacter(id = "c2", name = "Xiaoman"),
            MpCharacter(id = "c3", name = "Awu"),
            MpCharacter(id = "c4", name = "azen"),
            MpCharacter(id = "c5", name = "Ah Chi"),
        )
    }
}
