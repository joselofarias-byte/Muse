package io.zer0.muse.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.BuildConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LiteRuntimeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun requireLiteBuild() {
        assumeFalse(BuildConfig.EMBEDDED_RUNTIMES_ENABLED)
        context.applicationInfo.nativeLibraryDir = File(context.cacheDir, "lite-native-libs").apply { mkdirs() }.absolutePath
    }

    @Test
    fun `stale full edition files do not enable runtimes`() {
        val files = listOf(
            MuseRuntime.nodeBinary(context),
            MuseRuntime.npmCli(context),
            PythonRuntime.pythonBinary(context),
            File(PythonRuntime.stdlibDir(context), "os.py"),
        )
        try {
            files.forEach { file ->
                file.parentFile?.mkdirs()
                file.writeText("stale")
            }
            assertFalse(MuseRuntime.isReady(context))
            assertFalse(PythonRuntime.isReady(context))
        } finally {
            files.forEach { it.delete() }
        }
    }

    @Test
    fun `data initialization reports intentional omission`() = runBlocking {
        assertLiteFailure(MuseRuntime.ensureData(context).exceptionOrNull())
        assertLiteFailure(PythonRuntime.ensureData(context).exceptionOrNull())
    }

    @Test
    fun `execution reports intentional omission before starting a process`() = runBlocking {
        assertLiteFailure(runCatching { MuseRuntime.execNode(context, listOf("-v")) }.exceptionOrNull())
        assertLiteFailure(runCatching { PythonRuntime.execPython(context, listOf("-V")) }.exceptionOrNull())
    }

    private fun assertLiteFailure(error: Throwable?) {
        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("Muse Lite"))
    }
}
