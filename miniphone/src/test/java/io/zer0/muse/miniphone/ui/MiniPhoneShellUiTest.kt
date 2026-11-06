package io.zer0.muse.miniphone.ui

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.api.MpProfile
import io.zer0.muse.miniphone.data.db.MINI_PHONE_DB_VERSION
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import io.zer0.muse.miniphone.ui.shell.MessagesPage
import io.zer0.muse.miniphone.ui.shell.MiniPhoneApps
import io.zer0.muse.miniphone.ui.shell.MiniPhoneShell
import io.zer0.muse.miniphone.ui.shell.MiniPhoneShellActions
import io.zer0.muse.miniphone.ui.shell.MiniPhoneTab
import io.zer0.muse.miniphone.ui.shell.MiniPhoneTags
import io.zer0.muse.miniphone.ui.shell.MiniPhoneThreadUi
import io.zer0.muse.miniphone.ui.shell.ShellUiState
import io.zer0.muse.miniphone.ui.shell.ThreadMenuAction
import io.zer0.muse.miniphone.ui.shell.ThreadMenuActions
import io.zer0.muse.miniphone.ui.shell.assembleShellState
import io.zer0.muse.miniphone.ui.shell.contactSectionsOf
import io.zer0.muse.miniphone.ui.shell.miniPhoneVersionLabel
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import io.zer0.muse.miniphone.ui.theme.darkMiniPhoneColors
import io.zer0.muse.miniphone.ui.theme.lightMiniPhoneColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verificación de renderizado de shell y cuatro páginas.
 *
 * La afirmación sólo recae en "si es visible o no/si el orden es correcto/si los números son correctos":Cada caso de uso se pagina desde el escritorio.,
 * Presione la pestaña inferior nuevamente para cambiar de página.,Por cierto, esto demuestra que cambiar de página realmente cambia el contenido.,En lugar de cuatro páginas apiladas una encima de la otra.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneShellUiTest {

    @get:Rule
    val compose = createComposeRule()

    private var db: MiniPhoneDb? = null

    @After
    fun tearDown() {
        db?.close()
    }

    @Test
    fun tabBarSwitchesBetweenFourPages() {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneShell(state = ShellUiState(profile = MeProfile))
            }
        }

        // Iniciar en el escritorio:cuadrícula de iconos en,El título de la página no está ahí.
        compose.onNodeWithTag(launcherTag(MiniPhoneTab.MESSAGES)).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneTags.PAGE_TITLE).assertDoesNotExist()

        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.MESSAGES)).performClick()
        compose.onNodeWithTag(MiniPhoneTags.PAGE_TITLE).assertTextEquals("noticias")
        compose.onNodeWithTag(MiniPhoneTags.EMPTY_THREADS).assertExists()

        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.CONTACTS)).performClick()
        compose.onNodeWithTag(MiniPhoneTags.PAGE_TITLE).assertTextEquals("libreta de direcciones")
        compose.onNodeWithTag(MiniPhoneTags.EMPTY_CONTACTS).assertExists()
        compose.onNodeWithTag(MiniPhoneTags.EMPTY_THREADS).assertDoesNotExist()

        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.DISCOVER)).performClick()
        compose.onNodeWithTag(MiniPhoneTags.PAGE_TITLE).assertTextEquals("descubrir")
        compose.onNodeWithTag(MiniPhoneTags.MOMENTS_ENTRY).assertExists()
        compose.onNodeWithTag(MiniPhoneTags.EMPTY_CONTACTS).assertDoesNotExist()

        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.ME)).performClick()
        compose.onNodeWithTag(MiniPhoneTags.PAGE_TITLE).assertTextEquals("yo")
        compose.onNodeWithTag(MiniPhoneTags.MOMENTS_ENTRY).assertDoesNotExist()
        compose.onNodeWithText(MeProfile.nickname).assertExists()
    }

    @Test
    fun autoMomentSwitchIsOnMePageAndReportsToggling() {
        var toggled: Boolean? = null
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneShell(
                    state = ShellUiState(profile = MeProfile, autoMomentEnabled = true),
                    actions = MiniPhoneShellActions(onToggleAutoMoment = { toggled = it }),
                )
            }
        }

        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.ME)).performClick()
        compose.onNodeWithText("Publicar automáticamente en Momentos").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneTags.AUTO_MOMENT_SWITCH).performScrollTo().performClick()

        // Haga clic para "apagar":El estado está en ViewModel.,Aquí solo verificamos que la devolución de llamada pasada sea false
        assertEquals(false, toggled)
    }

    @Test
    fun chatProactiveSwitchIsOnMePageAndReportsToggling() {
        var toggled: Boolean? = null
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneShell(
                    state = ShellUiState(profile = MeProfile, chatProactiveEnabled = false),
                    actions = MiniPhoneShellActions(onToggleChatProactive = { toggled = it }),
                )
            }
        }

        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.ME)).performClick()
        compose.onNodeWithText("Rol activo mensaje privado").performScrollTo().assertIsDisplayed()
        // Desactivado predeterminado:La copia de la descripción debe indicar claramente "solo durante el día, cerrado de forma predeterminada".
        compose.onNodeWithText("El personaje ocasionalmente tomará la iniciativa de hablar contigo, sólo cuando 08:00–22:00 entre, cerrado por defecto").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneTags.CHAT_PROACTIVE_SWITCH).performScrollTo().performClick()

        // Haga clic para "abrir":El estado está en ViewModel.,Aquí solo verificamos que la devolución de llamada pasada sea true
        assertEquals(true, toggled)
    }

    @Test
    fun emptyThreadListShowsEmptyState() {
        openMessages(ShellUiState())

        compose.onNodeWithTag(MiniPhoneTags.EMPTY_THREADS).assertIsDisplayed()
        compose.onNodeWithText("Aún no hay chat").assertIsDisplayed()
        compose.onNodeWithText("Ve a la libreta de direcciones y busca un personaje para comenzar.").assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneTags.thread("t1")).assertDoesNotExist()
    }

    @Test
    fun emptyThreadStateButtonJumpsToContacts() {
        openMessages(ShellUiState())

        compose.onNodeWithTag(MiniPhoneTags.EMPTY_THREADS_ACTION).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneTags.EMPTY_THREADS_ACTION).performClick()

        // Haga clic para "cambiar a la libreta de direcciones":La página del mensaje se retira junto con su estado vacío.
        compose.onNodeWithTag(MiniPhoneTags.PAGE_TITLE).assertTextEquals("libreta de direcciones")
        compose.onNodeWithTag(MiniPhoneTags.EMPTY_CONTACTS).assertExists()
        compose.onNodeWithTag(MiniPhoneTags.EMPTY_THREADS).assertDoesNotExist()
    }

    @Test
    fun emptyThreadStateButtonReportsExactlyOneClick() {
        var clicks = 0
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MessagesPage(threads = emptyList(), onGoContacts = { clicks += 1 })
            }
        }

        compose.onNodeWithTag(MiniPhoneTags.EMPTY_THREADS_ACTION).performClick()
        compose.waitForIdle()

        assertEquals(1, clicks)
    }

    @Test
    fun tappingContactRowAsksForAThreadAndOpensIt() {
        val asked = mutableListOf<String>()
        val sections = contactSectionsOf(listOf(MpCharacter(id = "c1", name = "ali", tagline = "vivir abajo")))
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneShell(
                    state = ShellUiState(contactSections = sections),
                    chatContent = { threadId, _ ->
                        Text(text = "chat:$threadId", modifier = Modifier.testTag(FAKE_CHAT_TAG))
                    },
                    onOpenCharacterChat = { characterId ->
                        asked += characterId
                        "t-new"
                    },
                )
            }
        }

        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.CONTACTS)).performClick()
        compose.onNodeWithTag(MiniPhoneTags.contact("c1")).performClick()
        compose.waitForIdle()

        assertEquals(listOf("c1"), asked)
        compose.onNodeWithTag(FAKE_CHAT_TAG).assertTextEquals("chat:t-new")
    }

    @Test
    fun threadsRenderInLastMessageOrderWithUnreadBadge() {
        val now = System.currentTimeMillis()
        val state = loadState(
            threads = listOf(
                thread(id = "t-old", characterId = "c-old", preview = "en", at = now - 86_400_000L),
                thread(id = "t-new", characterId = "c-new", preview = "¿Quieres comer fideos esta noche?", at = now - 1_000L, unread = 2),
            ),
            characters = listOf(
                MpCharacter(id = "c-old", name = "viejos amigos"),
                MpCharacter(id = "c-new", name = "nuevos amigos"),
            ),
        )

        openMessages(state)

        val newerTop = compose.onNodeWithTag(MiniPhoneTags.thread("t-new")).getBoundsInRoot().top
        val olderTop = compose.onNodeWithTag(MiniPhoneTags.thread("t-old")).getBoundsInRoot().top
        assertTrue("Las conversaciones con los últimos mensajes actualizados deben clasificarse en la parte superior", newerTop < olderTop)

        compose.onNodeWithTag(MiniPhoneTags.unread("t-new"), useUnmergedTree = true).assertTextEquals("2")
        compose.onNodeWithTag(MiniPhoneTags.unread("t-old"), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("¿Quieres comer fideos esta noche?").assertExists()
        compose.onNodeWithText("nuevos amigos").assertExists()
    }

    @Test
    fun contactsAreGroupedByInitialInAlphabeticalOrder() {
        val sections = contactSectionsOf(
            listOf(
                MpCharacter(id = "c1", name = "ali", tagline = "vivir abajo"),
                MpCharacter(id = "c2", name = "Cara"),
                MpCharacter(id = "c3", name = "bob"),
                MpCharacter(id = "c4", name = "Zoe"),
            ),
        )
        assertEquals(listOf("B", "C", "Z", "#"), sections.map { it.key })

        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneShell(state = ShellUiState(contactSections = sections))
            }
        }
        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.CONTACTS)).performClick()

        val tops = listOf("B", "C", "Z", "#").map { key ->
            compose.onNodeWithTag(MiniPhoneTags.contactHeader(key)).getBoundsInRoot().top
        }
        assertEquals("El título del grupo debe ser B→C→Z→# hacia abajo", tops.sorted(), tops)
        compose.onNodeWithTag(MiniPhoneTags.CONTACT_INDEX).assertExists()
        compose.onNodeWithText("Cara").assertExists()

        // Haga clic en la barra de índice a la derecha para saltar al grupo chino,Verifique que la barra de índice realmente controle la lista
        compose.onNodeWithTag(MiniPhoneTags.contactIndex("#")).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("ali").assertExists()
    }

    @Test
    fun profileNicknameAndSignatureAreRendered() {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneShell(state = ShellUiState(profile = MeProfile))
            }
        }
        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.ME)).performClick()

        compose.onNodeWithText(MeProfile.nickname).assertIsDisplayed()
        compose.onNodeWithText(requireNotNull(MeProfile.signature)).assertIsDisplayed()
    }

    @Test
    fun darkThemePassesDarkPaletteDown() {
        assertEquals(Color(0xFFF5F3EF), lightMiniPhoneColors().background)
        assertEquals(Color(0xFF12110F), darkMiniPhoneColors().background)
        assertEquals(Color(0xFF24211D), darkMiniPhoneColors().surface)
        assertEquals(Color(0xFF7C9B85), darkMiniPhoneColors().accent)

        var captured: Color? = null
        compose.setContent {
            MiniPhoneTheme(darkTheme = true) {
                captured = MiniPhoneTheme.colors.background
                MiniPhoneShell(state = ShellUiState())
            }
        }
        compose.waitForIdle()

        assertEquals(Color(0xFF12110F), captured)
    }

    @Test
    fun longPressThreadMenuPinsAndDeletesOnlyAfterConfirmation() {
        val pinned = mutableListOf<Pair<String, Boolean>>()
        val deleted = mutableListOf<String>()
        var markedRead = 0
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MessagesPage(
                    threads = listOf(threadUi(unread = 2)),
                    actions = ThreadMenuActions(
                        onTogglePin = { threadId, value -> pinned += threadId to value },
                        onMarkRead = { markedRead += 1 },
                        onDelete = { deleted += it },
                    ),
                )
            }
        }

        compose.onNodeWithTag(MiniPhoneTags.thread("t1")).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.onNodeWithTag(MiniPhoneTags.THREAD_MENU).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneTags.threadMenuAction(ThreadMenuAction.MARK_READ)).assertIsDisplayed()

        compose.onNodeWithTag(MiniPhoneTags.threadMenuAction(ThreadMenuAction.PIN)).performClick()

        assertEquals("Orientación en la parte superior", listOf("t1" to true), pinned)

        // La eliminación requiere doble confirmación:Al hacer clic solo aparece el cuadro de confirmación.,Eliminado solo después de la confirmación.
        compose.onNodeWithTag(MiniPhoneTags.thread("t1")).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.onNodeWithTag(MiniPhoneTags.threadMenuAction(ThreadMenuAction.DELETE)).performClick()
        compose.onNodeWithTag(MiniPhoneTags.THREAD_DELETE_DIALOG).assertIsDisplayed()
        assertEquals("No borrar antes de confirmar", emptyList<String>(), deleted)

        compose.onNodeWithTag(MiniPhoneTags.THREAD_DELETE_CONFIRM).performClick()

        assertEquals(listOf("t1"), deleted)
        assertEquals("Sin puntos, sin marcas", 0, markedRead)
    }

    @Test
    fun longPressThreadMenuHidesRowsThatWouldDoNothing() {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MessagesPage(threads = listOf(threadUi(unread = 0, pinned = false)))
            }
        }

        compose.onNodeWithTag(MiniPhoneTags.thread("t1")).performSemanticsAction(SemanticsActions.OnLongClick)

        // No leído es 0 y no se marca como leído.;Si no lo fijas en la parte superior, no lo pongas "Desanclarlo"
        compose.onNodeWithTag(MiniPhoneTags.threadMenuAction(ThreadMenuAction.MARK_READ)).assertDoesNotExist()
        compose.onNodeWithTag(MiniPhoneTags.threadMenuAction(ThreadMenuAction.UNPIN)).assertDoesNotExist()
        compose.onNodeWithTag(MiniPhoneTags.threadMenuAction(ThreadMenuAction.PIN)).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneTags.threadMenuAction(ThreadMenuAction.DELETE)).assertIsDisplayed()
    }

    @Test
    fun mePageOpensMyMomentsAndShowsBothVersions() {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneShell(
                    state = ShellUiState(profile = MeProfile, appVersionName = "2.6.12"),
                    momentsContent = { onlyMine, _ ->
                        Text(
                            text = if (onlyMine) MINE_ONLY_CONTENT else ALL_CONTENT,
                            modifier = Modifier.testTag(FAKE_CHAT_TAG),
                        )
                    },
                )
            }
        }

        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.ME)).performClick()
        compose.onNodeWithTag(MiniPhoneTags.VERSION_LABEL).performScrollTo()
            .assertTextEquals("pequeños datos celulares v${MINI_PHONE_DB_VERSION} / Solicitud 2.6.12")

        compose.onNodeWithTag(MiniPhoneTags.MY_MOMENTS_ROW).performScrollTo().performClick()

        // Lo que entré fue "Mírate sólo a ti mismo":Las dos entradas no comparten línea de tiempo.
        compose.onNodeWithTag(FAKE_CHAT_TAG).assertTextEquals(MINE_ONLY_CONTENT)
    }

    @Test
    fun clearingChatsNeedsConfirmationBeforeReportingTheAction() {
        var cleared = 0
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneShell(
                    state = ShellUiState(profile = MeProfile),
                    actions = MiniPhoneShellActions(onClearConversations = { cleared += 1 }),
                )
            }
        }

        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.ME)).performClick()
        compose.onNodeWithTag(MiniPhoneTags.CLEAR_CHATS_ROW).performScrollTo().performClick()
        compose.onNodeWithTag(MiniPhoneTags.CLEAR_CHATS_DIALOG).assertIsDisplayed()

        assertEquals("Haga clic en la entrada y haga clic para confirmar.", 0, cleared)

        compose.onNodeWithTag(MiniPhoneTags.CLEAR_CHATS_CONFIRM).performClick()

        assertEquals(1, cleared)
    }

    @Test
    fun versionLabelDropsTheHostPartWhenTheHostReportsNothing() {
        assertEquals("pequeños datos celulares v$MINI_PHONE_DB_VERSION", miniPhoneVersionLabel(MINI_PHONE_DB_VERSION, ""))
        assertEquals("pequeños datos celulares v$MINI_PHONE_DB_VERSION", miniPhoneVersionLabel(MINI_PHONE_DB_VERSION, "  "))
        assertEquals(
            "pequeños datos celulares v$MINI_PHONE_DB_VERSION / Solicitud 2.6.12",
            miniPhoneVersionLabel(MINI_PHONE_DB_VERSION, "2.6.12"),
        )
    }

    /** conversación de una línea:De forma predeterminada, hay dos elementos no leídos.,Fijados y no leídos están fijados por parámetros,Es conveniente determinar qué líneas deberían aparecer en el menú. */
    private fun threadUi(unread: Int = 2, pinned: Boolean = false): MiniPhoneThreadUi = MiniPhoneThreadUi(
        id = "t1",
        name = "ali",
        preview = "¿Está ahí?",
        lastMessageAt = 1_000L,
        unreadCount = unread,
        pinned = pinned,
    )

    /** Cambie a la página de mensajes desde el escritorio. */
    private fun openMessages(state: ShellUiState) {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneShell(state = state)
            }
        }
        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.MESSAGES)).performClick()
    }

    /** Léelo de nuevo:Por cierto, verifique que el pedido del DAO efectivamente se comunique a la interfaz. */
    private fun loadState(threads: List<MpThreadEntity>, characters: List<MpCharacter>): ShellUiState {
        val database = newInMemoryMiniPhoneDb()
        db = database
        return runBlocking {
            threads.forEach { database.threadDao().upsert(it) }
            assembleShellState(
                threads = database.threadDao().observeThreads().first(),
                characters = characters,
                profile = MeProfile,
            )
        }
    }

    private fun thread(id: String, characterId: String, preview: String, at: Long, unread: Int = 0): MpThreadEntity = MpThreadEntity(
        id = id,
        characterId = characterId,
        unreadCount = unread,
        lastMessagePreview = preview,
        lastMessageAt = at,
    )

    private fun launcherTag(tab: MiniPhoneTab): String = MiniPhoneTags.launcher(MiniPhoneApps.launcher.first { it.tab == tab })

    private companion object {
        val MeProfile = MpProfile(nickname = "Ziqi", signature = "tómate tu tiempo,relativamente rápido")
        const val FAKE_CHAT_TAG: String = "mp-shell-fake-chat"
        const val MINE_ONLY_CONTENT: String = "mine-only"
        const val ALL_CONTENT: String = "all-moments"
    }
}
