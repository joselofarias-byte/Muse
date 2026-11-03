package io.zer0.muse.data.session

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class SessionRepositorySessionRecallTest {

    private val sessionDao = mockk<SessionDao>(relaxed = true)
    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val database = mockk<MuseDb>(relaxed = true)
    private val ftsDao = mockk<MessageFtsDao>(relaxed = true)
    private lateinit var repository: SessionRepository

    @Before
    fun setUp() {
        every { database.messageFtsDao() } returns ftsDao
        MessageFtsRuntime.useFts5 = false
        repository = SessionRepository(
            sessionDao = sessionDao,
            messageDao = messageDao,
            database = database,
            context = ApplicationProvider.getApplicationContext(),
            messageImageStore = mockk<MessageImageStore>(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        MessageFtsRuntime.useFts5 = false
    }

    @Test
    fun `session recall uses the session-scoped FTS query and returns original content`() = runTest {
        coEvery {
            ftsDao.searchFtsInSession(any(), "session-a", 3)
        } returns listOf(
            MessageSearchJoin(
                messageId = "m1",
                sessionId = "session-a",
                content = "Detalles del texto histórico",
                role = "USER",
                createdAt = 1L,
                sessionTitle = "sesión A",
            ),
        )

        val results = repository.searchMessagesInSession("session-a", "historia", limit = 3)

        assertEquals(1, results.size)
        assertEquals("session-a", results.single().sessionId)
        assertEquals("Detalles del texto histórico", results.single().content)
        coVerify(exactly = 1) { ftsDao.searchFtsInSession(any(), "session-a", 3) }
    }

    @Test
    fun `blank or invalid session recall does not query storage`() = runTest {
        assertTrue(repository.searchMessagesInSession("", "historia").isEmpty())
        assertTrue(repository.searchMessagesInSession("session-a", " ").isEmpty())
        coVerify(exactly = 0) { ftsDao.searchFtsInSession(any(), any(), any()) }
    }
}
