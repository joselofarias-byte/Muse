package io.zer0.muse.data.routing

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UtilityModelHealthTest {

    private val now = 1_800_000_000_000L
    private val provider = "test-provider"
    private val touched = mutableListOf<String>()

    @After
    fun clearTouched() {
        touched.forEach { UtilityModelHealth.clear(provider, it) }
    }

    @Test
    fun `records failures per binding`() {
        record("model-a", "HTTP 401")

        val failure = UtilityModelHealth.lastFor(provider, "model-a", now)

        assertNotNull(failure)
        assertEquals("model-a", failure?.modelId)
        assertEquals("HTTP 401", failure?.message)
    }

    @Test
    fun `ignores blank model ids`() {
        UtilityModelHealth.record(provider, "", "boom", now)

        assertNull(UtilityModelHealth.lastFor(provider, "", now))
    }

    @Test
    fun `expires failures after one day`() {
        record("model-b", "timeout")

        assertNotNull(UtilityModelHealth.lastFor(provider, "model-b", now))
        assertNull(UtilityModelHealth.lastFor(provider, "model-b", now + 25 * 60 * 60 * 1000L))
    }

    @Test
    fun `keeps only the latest failure per binding`() {
        record("model-c", "first", now)
        record("model-c", "second", now + 1_000L)

        assertEquals("second", UtilityModelHealth.lastFor(provider, "model-c", now + 1_000L)?.message)
    }

    @Test
    fun `clears only the selected binding`() {
        record("model-d", "boom")
        record("model-e", "boom")
        UtilityModelHealth.clear(provider, "model-d")

        assertNull(UtilityModelHealth.lastFor(provider, "model-d", now))
        assertNotNull(UtilityModelHealth.lastFor(provider, "model-e", now))
    }

    @Test
    fun `limits error details to 200 characters`() {
        record("model-f", "x".repeat(300))

        assertEquals(200, UtilityModelHealth.lastFor(provider, "model-f", now)?.message?.length)
    }

    private fun record(modelId: String, message: String, at: Long = now) {
        if (modelId !in touched) touched += modelId
        UtilityModelHealth.record(provider, modelId, message, at)
    }
}
