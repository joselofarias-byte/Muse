package io.zer0.muse.data.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConversationTreeSnapshotStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun user(content: String, group: String, index: Int, count: Int, at: Long) = UIMessage(
        id = Uuid.random(),
        role = MessageRole.USER,
        content = content,
        createdAt = at,
        variantGroupId = group,
        variantIndex = index,
        variantCount = count,
    )

    private fun assistant(content: String, group: String, parent: String, index: Int, count: Int, at: Long) = UIMessage(
        id = Uuid.random(),
        role = MessageRole.ASSISTANT,
        content = content,
        createdAt = at,
        variantGroupId = group,
        variantIndex = index,
        variantCount = count,
        parentGroupId = parent,
    )

    @Test
    fun snapshot_roundTripsSelection() = runBlocking {
        val store = ConversationTreeSnapshotStore(context)
        val u1 = user("hacer una preguntaA", "ug1", 0, 2, 100)
        val a1a = assistant("responder1", "ag1", u1.id.toString(), 0, 2, 101)
        val a1b = assistant("responder2", "ag1", u1.id.toString(), 1, 2, 103)
        val u2 = user("hacer una preguntaAcambiar", "ug1", 1, 2, 102)
        val messages = listOf(u1, a1a, a1b, u2)

        val initial = ConversationTree.build(messages)
        val selected = initial
            .selectUserVariant(initial.userNodes.first().userId, 0)
            .selectAssistantVariant(u1.id.toString(), "ag1", 0)
        store.save("session-test", selected)

        val loaded = store.load("session-test")
        assertNotNull(loaded)
        val rebuilt = ConversationTree.build(messages, loaded)

        assertEquals("hacer una preguntaA", rebuilt.selectedUserVariant?.content)
        assertEquals("responder1", rebuilt.displayMessages.last().content)
    }

    @Test
    fun snapshot_preserves_selected_user_and_assistant_variants_after_rebuild() = runBlocking {
        val store = ConversationTreeSnapshotStore(context)
        val u1 = user("hacer una preguntaA", "ug2", 0, 2, 100)
        val u2 = user("hacer una preguntaAcambiar", "ug2", 1, 2, 102)
        val a1 = assistant("responderA1", "ag2", u1.id.toString(), 0, 2, 101)
        val a2 = assistant("responderA2", "ag2", u1.id.toString(), 1, 2, 103)
        val b1 = assistant("responderB1", "bg2", u2.id.toString(), 0, 2, 104)
        val b2 = assistant("responderB2", "bg2", u2.id.toString(), 1, 2, 105)
        val messages = listOf(u1, a1, a2, u2, b1, b2)

        val initial = ConversationTree.build(messages)
        val selected = initial
            .selectUserVariant(initial.userNodes.first().userId, 1)
            .selectAssistantVariant(u2.id.toString(), "bg2", 1)
        store.save("session-selected-variants", selected)

        val loaded = store.load("session-selected-variants")
        assertNotNull(loaded)
        val rebuilt = ConversationTree.build(messages, loaded)

        assertEquals("hacer una preguntaAcambiar", rebuilt.selectedUserVariant?.content)
        assertEquals("responderB2", rebuilt.displayMessages.last().content)
        assertEquals(1, rebuilt.selectedUserNode?.selectIndex)
        assertEquals(
            1,
            rebuilt.selectedUserNode?.currentVariant?.assistantNodes?.firstOrNull()?.selectIndex,
        )
    }
}
