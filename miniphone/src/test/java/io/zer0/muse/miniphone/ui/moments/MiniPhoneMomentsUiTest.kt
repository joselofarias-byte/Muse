package io.zer0.muse.miniphone.ui.moments

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import io.zer0.muse.miniphone.ui.chat.MiniPhoneChatTags
import io.zer0.muse.miniphone.ui.chat.MiniPhoneGalleryViewer
import io.zer0.muse.miniphone.ui.chat.galleryCounterOf
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
 * Representación de la verificación de la página del círculo de amigos:El estado vacío, cómo organizar la cuadrícula de nueve cuadrados y similares cambian con los clics.
 *
 * La afirmación sólo se reduce a "¿no puedes verlo/dónde está colocado/si el número es correcto?",No mires los colores y los píxeles.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneMomentsUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun emptyTimelineShowsEmptyStateUnderTheCover() {
        render(MomentsUiState(isLoaded = true))

        compose.onNodeWithTag(MiniPhoneMomentsTags.COVER).assertExists()
        compose.onNodeWithTag(MiniPhoneMomentsTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithText("Aún no hay actualizaciones").assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneMomentsTags.commentInput("m1")).assertDoesNotExist()
    }

    @Test
    fun cardRendersAuthorContentAndImages() {
        render(
            MomentsUiState(
                moments = listOf(momentUi(content = "Las nubes están muy bajas hoy.", images = listOf("content://img/0"))),
                isLoaded = true,
            ),
        )

        compose.onNodeWithTag(MiniPhoneMomentsTags.moment("m1")).assertExists()
        compose.onNodeWithTag(MiniPhoneMomentsTags.content("m1"), useUnmergedTree = true)
            .assertTextEquals("Las nubes están muy bajas hoy.")
        compose.onNodeWithTag(MiniPhoneMomentsTags.image("m1", 0)).assertExists()
    }

    @Test
    fun likeCountFollowsTheClick() {
        compose.setContent {
            var state by remember { mutableStateOf(MomentsUiState(moments = listOf(momentUi()), isLoaded = true)) }
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneMomentsScreen(
                    state = state,
                    onBack = {},
                    actions = MomentActions(
                        onToggleLike = { id ->
                            state = state.copy(
                                moments = state.moments.map { row ->
                                    if (row.id == id) row.copy(likes = listOf("Ziqi"), likedByMe = true) else row
                                },
                            )
                        },
                    ),
                )
            }
        }

        compose.onNodeWithTag(MiniPhoneMomentsTags.likeCount("m1"), useUnmergedTree = true).assertTextEquals("0")
        compose.onNodeWithTag(MiniPhoneMomentsTags.likeButton("m1")).performClick()
        compose.onNodeWithTag(MiniPhoneMomentsTags.likeCount("m1"), useUnmergedTree = true).assertTextEquals("1")
        compose.onNodeWithTag(MiniPhoneMomentsTags.likers("m1"), useUnmergedTree = true).assertTextEquals("❤️ Ziqi")
    }

    @Test
    fun nineImagesWrapIntoThreeColumns() {
        render(MomentsUiState(moments = listOf(momentUi(images = nineImages())), isLoaded = true))

        val bounds = (0 until 9).map { index ->
            compose.onNodeWithTag(MiniPhoneMomentsTags.image("m1", index)).getBoundsInRoot()
        }

        // Tres imágenes en la primera fila.:mismo top,left a la derecha
        assertEquals(bounds[0].top.value, bounds[1].top.value, TOLERANCE)
        assertEquals(bounds[1].top.value, bounds[2].top.value, TOLERANCE)
        assertTrue("El segundo debe estar a la derecha del primero.", bounds[1].left > bounds[0].left)
        assertTrue("El tercero debe estar a la derecha del segundo.", bounds[2].left > bounds[1].left)
        // El cuarto se mueve a la siguiente línea.,Y se fue alineado con el primero.
        assertTrue("El cuarto debe cambiarse a una nueva línea.", bounds[3].top > bounds[0].top)
        assertEquals(bounds[0].left.value, bounds[3].left.value, TOLERANCE)
    }

    @Test
    fun threeImagesSitInASingleRow() {
        render(MomentsUiState(moments = listOf(momentUi(images = nineImages().take(3))), isLoaded = true))

        val bounds = (0 until 3).map { index ->
            compose.onNodeWithTag(MiniPhoneMomentsTags.image("m1", index)).getBoundsInRoot()
        }

        assertEquals(bounds[0].top.value, bounds[1].top.value, TOLERANCE)
        assertEquals(bounds[1].top.value, bounds[2].top.value, TOLERANCE)
        assertTrue(bounds[2].left > bounds[1].left)
    }

    @Test
    fun publishScreenHandsOverTheOnlySelfSwitch() {
        var published: Triple<String, Int, Boolean>? = null
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneMomentsScreen(
                    state = MomentsUiState(isLoaded = true),
                    onBack = {},
                    actions = MomentActions(
                        onPublish = { content, images, onlySelf ->
                            published = Triple(content, images.size, onlySelf)
                        },
                    ),
                )
            }
        }

        compose.onNodeWithTag(MiniPhoneMomentsTags.COMPOSE_ENTRY).performClick()
        compose.onNodeWithTag(MiniPhoneMomentsTags.PUBLISH_SCREEN).assertExists()
        compose.onNodeWithTag(MiniPhoneMomentsTags.PUBLISH_VISIBLE_SELF).performClick()
        compose.onNodeWithTag(MiniPhoneMomentsTags.PUBLISH_INPUT).performTextInput("solo para ti")
        compose.onNodeWithTag(MiniPhoneMomentsTags.PUBLISH_CONFIRM).performClick()

        assertEquals(Triple("solo para ti", 0, true), published)
    }

    @Test
    fun gridColumnsFollowTheLayoutRule() {
        assertEquals("Dar una imagen grande uno por uno", 1, momentGridColumns(1))
        assertEquals("Dos hojas y dos columnas.", 2, momentGridColumns(2))
        assertEquals("Tres cartas por línea", 3, momentGridColumns(3))
        assertEquals("cuatro 2×2", 2, momentGridColumns(4))
        assertEquals("Nueve cartas en tres columnas.", 3, momentGridColumns(9))
    }

    @Test
    fun likersLineFoldsLongLists() {
        assertEquals("❤️ A Yuan, Xiao Man, Lao Chen", likersLineOf(listOf("Ayuan", "Xiaoman", "Lao Chen")))
        assertEquals("solo hay uno a quien le importa", "❤️ Ayuan", likersLineOf(listOf("Ayuan")))
        assertEquals("Si a nadie le gusta esta línea, no existirá tal cosa.", "", likersLineOf(emptyList()))

        val twelve = (1..12).map { index -> "gente$index" }
        assertEquals("más de diez personas:Primeros nueve + 12 más", "❤️ " + twelve.take(9).joinToString(",") + ", esperando a 12 personas", likersLineOf(twelve))
        assertFalse("La décima persona no debería aparecer.", likersLineOf(twelve).contains("gente10"))
    }

    @Test
    fun likersRendersAsOneLine() {
        render(MomentsUiState(moments = listOf(momentUi(likes = listOf("Ayuan", "Xiaoman", "Lao Chen"))), isLoaded = true))

        compose.onNodeWithTag(MiniPhoneMomentsTags.likers("m1"), useUnmergedTree = true)
            .assertTextEquals("❤️ A Yuan, Xiao Man, Lao Chen")
    }

    @Test
    fun ownCommentOffersDeleteAndTheConfirmNamesTheCascade() {
        var deleted: Pair<String, String>? = null
        render(
            MomentsUiState(moments = listOf(momentUi(comments = listOf(commentUi(id = "k1", mine = true)))), isLoaded = true),
            MomentActions(onDeleteComment = { momentId, commentId -> deleted = momentId to commentId }),
        )

        compose.onNodeWithTag(MiniPhoneMomentsTags.commentRow("k1")).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag(MiniPhoneMomentsTags.COMMENT_MENU_ACTION).assertIsDisplayed()

        compose.onNodeWithTag(MiniPhoneMomentsTags.COMMENT_MENU_ACTION).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(MiniPhoneMomentsTags.COMMENT_DELETE_DIALOG).assertIsDisplayed()
        compose.onNodeWithText("Este comentario y las respuestas a continuación serán eliminados.").assertIsDisplayed()
        assertEquals("No borrar antes de confirmar", null, deleted)

        compose.onNodeWithTag(MiniPhoneMomentsTags.COMMENT_DELETE_CONFIRM).performClick()
        compose.waitForIdle()

        assertEquals("m1" to "k1", deleted)
    }

    @Test
    fun someoneElsesCommentHasNoDeleteEntry() {
        var deletes = 0
        render(
            MomentsUiState(moments = listOf(momentUi(comments = listOf(commentUi(id = "k1", mine = false)))), isLoaded = true),
            MomentActions(onDeleteComment = { _, _ -> deletes += 1 }),
        )

        compose.onNodeWithTag(MiniPhoneMomentsTags.commentRow("k1")).performTouchInput { longClick() }
        compose.waitForIdle()

        compose.onNodeWithTag(MiniPhoneMomentsTags.COMMENT_MENU_ACTION).assertDoesNotExist()
        compose.onNodeWithTag(MiniPhoneMomentsTags.replyRow("k2")).performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals("No hay ninguna acción de eliminación para los comentarios de otras personas.", 0, deletes)
    }

    @Test
    fun galleryOpensAtTheTappedImageAndSwipesBack() {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneGalleryViewer(uris = nineImages(), initialIndex = 2, onDismiss = {})
            }
        }

        compose.onNodeWithTag(MiniPhoneChatTags.IMAGE_GALLERY_COUNTER, useUnmergedTree = true).assertTextEquals("3 / 9")

        compose.onNodeWithTag(MiniPhoneChatTags.IMAGE_GALLERY).performTouchInput { swipeRight() }
        compose.waitForIdle()

        compose.onNodeWithTag(MiniPhoneChatTags.IMAGE_GALLERY_COUNTER, useUnmergedTree = true).assertTextEquals("2 / 9")
    }

    @Test
    fun galleryCounterTextIsOneBased() {
        assertEquals("3 / 9", galleryCounterOf(pageIndex = 2, pageCount = 9))
        assertEquals("1 / 1", galleryCounterOf(pageIndex = 0, pageCount = 1))
    }

    @Test
    fun coverCarriesSelfAvatarAndNickname() {
        render(MomentsUiState(selfName = "Ziqi", selfAvatarUri = "content://avatar/me", isLoaded = true))

        compose.onNodeWithTag(MiniPhoneMomentsTags.SELF_NAME, useUnmergedTree = true).assertTextEquals("Ziqi")
        compose.onNodeWithTag(MiniPhoneMomentsTags.SELF_AVATAR).assertExists()
    }

    private fun commentUi(id: String, mine: Boolean): MomentCommentUi = MomentCommentUi(
        id = id,
        senderName = if (mine) "Ziqi" else "ali",
        content = if (mine) "dije" else "lo que dijo",
        createdAt = CREATED_AT,
        isMine = mine,
        replies = listOf(
            MomentReplyUi(
                id = "k2",
                senderName = "ali",
                replyToName = null,
                content = "decirte algo",
                createdAt = CREATED_AT,
                isMine = false,
            ),
        ),
    )

    private fun render(state: MomentsUiState, actions: MomentActions = MomentActions()) {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneMomentsScreen(state = state, onBack = {}, actions = actions)
            }
        }
    }

    private fun nineImages(): List<String> = (0 until 9).map { index -> "content://img/$index" }

    private companion object {
        const val TOLERANCE: Float = 0.5f
    }
}

/** Una animación para renderizar. */
internal fun momentUi(
    id: String = "m1",
    content: String = "Las nubes están muy bajas hoy.",
    images: List<String> = emptyList(),
    likes: List<String> = emptyList(),
    likedByMe: Boolean = false,
    isMine: Boolean = false,
    comments: List<MomentCommentUi> = emptyList(),
) = MomentUi(
    id = id,
    authorId = "c1",
    authorName = "ali",
    authorAvatarUri = null,
    content = content,
    images = images,
    createdAt = CREATED_AT,
    isMine = isMine,
    likes = likes,
    likedByMe = likedByMe,
    comments = comments,
)

private const val CREATED_AT: Long = 1_700_000_000_000L
