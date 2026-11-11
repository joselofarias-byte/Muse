package io.zer0.muse.knowledge

import io.zer0.muse.knowledge.domain.knowledgeOperation
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class KnowledgeOperationTest {
    @Test
    fun `successful value is preserved`() {
        val value = Any()
        assertSame(value, knowledgeOperation { value }.getOrThrow())
    }

    @Test
    fun `successful null is not a failure`() {
        val result = knowledgeOperation { null }
        assertTrue(result.isSuccess)
        assertNull(result.getOrThrow())
    }

    @Test
    fun `ordinary exception is exposed as the original failure`() {
        val failure = IOException("storage unavailable")
        val result = knowledgeOperation { throw failure }
        assertTrue(result.isFailure)
        assertSame(failure, result.exceptionOrNull())
    }

    @Test
    fun `cancellation propagates unchanged`() {
        val failure = CancellationException("cancelled")
        assertSame(failure, assertThrows(CancellationException::class.java) { knowledgeOperation { throw failure } })
    }

    @Test
    fun `assertion error propagates unchanged`() {
        val failure = AssertionError("invariant")
        assertSame(failure, assertThrows(AssertionError::class.java) { knowledgeOperation { throw failure } })
    }

    @Test
    fun `linkage error propagates unchanged`() {
        val failure = LinkageError("missing implementation")
        assertSame(failure, assertThrows(LinkageError::class.java) { knowledgeOperation { throw failure } })
    }
}
