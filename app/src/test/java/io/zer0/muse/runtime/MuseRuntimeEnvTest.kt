package io.zer0.muse.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Entorno de proceso hijo:CPU El número central debe inyectarse explícitamente; de ​​lo contrario, el nodo integrado solo puede confiar en libuv para adivinarlo. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MuseRuntimeEnvTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `buildEnv injects positive cpu count`() {
        context.applicationInfo.nativeLibraryDir = File(context.cacheDir, "native-libs").absolutePath
        val env = MuseRuntime.buildEnv(context)
        val raw = env[MuseRuntime.ENV_CPU_COUNT]
        assertNotNull("buildEnv debe ser inyectado MUSE_CPU_COUNT", raw)
        val value = requireNotNull(raw).toInt()
        assertTrue("CPU El número de auditoría debe ser positivo y el número real $value", value > 0)
        assertEquals("Debe ser coherente con la cantidad de procesadores disponibles en el lado de Java.", MuseRuntime.childCpuCount().toString(), raw)
    }

    @Test
    fun `child cpu count never below one`() {
        assertTrue(Runtime.getRuntime().availableProcessors() >= 1)
        assertTrue(MuseRuntime.childCpuCount() >= 1)
    }
}
