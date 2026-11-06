package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EncodingToolsRegistrarTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun register_registersSevenEncodingTools() {
        val registry = ToolRegistry(context)
        EncodingToolsRegistrar(context, registry)
        val names = registry.listTools().map { it.name }
        listOf("url_encode", "url_decode", "base64_encode", "base64_decode", "hash_text", "generate_uuid", "random_number")
            .forEach { assertTrue("missing $it", it in names) }
    }

    @Test
    fun execute_urlEncodeWorks() = runBlocking {
        val registry = ToolRegistry(context)
        EncodingToolsRegistrar(context, registry)
        val outcome = registry.execute("url_encode", mapOf("text" to "Hola world"))
        assertTrue(!outcome.isError)
        assertTrue(outcome.content.contains("Hola+world"))
    }

    @Test
    fun execute_randomNumberCoercesIntegerArgs() = runBlocking {
        val registry = ToolRegistry(context)
        EncodingToolsRegistrar(context, registry)
        // cuerda "1" / "3" Aún se puede ejecutar después de que ToolArgValidator lo fuerce.,El resultado está en [1,3]
        val outcome = registry.execute("random_number", mapOf("min" to "1", "max" to "3"))
        assertTrue(!outcome.isError)
        assertTrue(outcome.content.contains(":"))
    }

    @Test
    fun execute_hashTextSupportsSha256() = runBlocking {
        val registry = ToolRegistry(context)
        EncodingToolsRegistrar(context, registry)
        val outcome = registry.execute("hash_text", mapOf("text" to "abc", "algorithm" to "SHA-256"))
        assertTrue(!outcome.isError)
        assertTrue(outcome.content.contains("ba7816bf8f01cfea"))
    }
}
