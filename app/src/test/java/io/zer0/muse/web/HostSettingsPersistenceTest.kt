package io.zer0.muse.web

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.SettingsRepositoryTestApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `settings.update` → Verdadero [SettingsRepository] → Vuelve a leerlo.
 *
 * frente [HostSettingsCommandsTest] Utilice el almacén falso para verificar la lógica de verificación, aquí utilice el DataStore real para verificar“¿Lo has escrito?”:
 * Después de pasar la verificación de tipo, la cadena debe traducirse a un valor reconocido por el almacén durante el paso de introducción, en lugar de simplemente almacenarse en la memoria.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SettingsRepositoryTestApplication::class)
class HostSettingsPersistenceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * Toda la clase de prueba comparte un repositorio.
     *
     * Crea una nueva para cada pregunta. `SettingsRepository` permitirá que varias instancias de DataStore hagan referencia al mismo archivo de preferencias al mismo tiempo.
     * Windows siguiente escritura/solo cambia el nombre `AccessDeniedException`(Y afectará otros casos de uso en la misma JVM que necesitan usar la biblioteca de configuración).
     */
    private val settings: SettingsRepository by lazy { SettingsRepository(context, mockk(relaxed = true)) }

    /** Cada pregunta recibió el mismo ejemplo (ver [settings] descripción). */
    private fun repository(): SettingsRepository = settings

    private fun commands(settings: SettingsRepository) = HostSettingsCommands(
        RepositorySettingsAccess(settings),
        HostSettingsStrings { name, _ -> name },
    )

    @Test
    fun `boolean write lands in the repository`() = runBlocking {
        val settings = repository()

        assertEquals(
            HostSettingsUpdateResult.Applied(HostSettingKeys.PII_GUARD),
            commands(settings).update(HostSettingKeys.PII_GUARD, "true"),
        )
        assertTrue("La escritura booleana debe iniciar sesión en la biblioteca.", settings.piiGuardEnabledFlow.first())

        commands(settings).update(HostSettingKeys.PII_GUARD, "false")
        assertFalse("Si vuelve a escribir falso, deberá sobrescribirlo.", settings.piiGuardEnabledFlow.first())
    }

    @Test
    fun `number write lands in the repository`() = runBlocking {
        val settings = repository()

        assertEquals(
            HostSettingsUpdateResult.Applied("liquidGlassStrength"),
            commands(settings).update("liquidGlassStrength", "70"),
        )
        assertEquals(70, settings.liquidGlassStrengthFlow.first())
    }

    @Test
    fun `enum write lands in the repository`() = runBlocking {
        val settings = repository()

        assertEquals(HostSettingsUpdateResult.Applied("themeMode"), commands(settings).update("themeMode", "dark"))
        assertEquals("dark", settings.themeModeFlow.first())

        commands(settings).update("fontSizeScale", "xlarge")
        assertEquals("xlarge", settings.fontSizeScaleFlow.first())

        commands(settings).update("language", "ja")
        assertEquals("ja", settings.languageFlow.first())
    }

    @Test
    fun `chat preference enum and booleans land in the repository`() = runBlocking {
        val settings = repository()

        assertEquals(
            HostSettingsUpdateResult.Applied("chatPreferences.reasoningLevel"),
            commands(settings).update("chatPreferences.reasoningLevel", "MEDIUM"),
        )
        commands(settings).update("chatPreferences.showTimestamp", "true")
        commands(settings).update("chatPreferences.forceReasoning", "true")

        val prefs = settings.chatPreferencesFlow.first()
        assertEquals(io.zer0.ai.core.ReasoningLevel.MEDIUM, prefs.forceReasoningLevel)
        assertTrue(prefs.showTimestamp)
        assertTrue(prefs.forceReasoning)
    }

    @Test
    fun `media config writes keep the rest of the config intact`() = runBlocking {
        val settings = repository()
        val before = settings.mediaConfigFlow.first()

        assertEquals(
            HostSettingsUpdateResult.Applied("mediaConfig.ttsEngine"),
            commands(settings).update("mediaConfig.ttsEngine", "openai"),
        )
        commands(settings).update("mediaConfig.ttsSpeechRate", "1.25")

        val after = settings.mediaConfigFlow.first()
        assertEquals("openai", after.ttsEngine)
        assertEquals(1.25, after.ttsSpeechRate.toDouble(), 1e-6)
        assertEquals("Cambiar un campo no puede restablecer fácilmente otros campos", before.ttsPitch.toDouble(), after.ttsPitch.toDouble(), 1e-6)
    }

    @Test
    fun `rejected writes leave the repository untouched`() = runBlocking {
        val settings = repository()
        val before = settings.themeModeFlow.first()

        assertTrue(commands(settings).update("themeMode", "purple") is HostSettingsUpdateResult.Rejected)
        assertTrue(commands(settings).update("liquidGlassStrength", "101") is HostSettingsUpdateResult.Rejected)
        assertTrue(commands(settings).update("notAKey", "true") is HostSettingsUpdateResult.Rejected)

        assertEquals("Las escrituras rechazadas no se pueden cambiar en la base de datos.", before, settings.themeModeFlow.first())
    }

    @Test
    fun `snapshot reflects what was just written`() = runBlocking {
        val settings = repository()
        val api = commands(settings)

        api.update("themeMode", "light")
        api.update("liquidGlassStrength", "35")
        api.update("mediaConfig.ttsEngine", "edge")

        val values = api.snapshot().values.associateBy { it.key }
        assertEquals("light", values.getValue("themeMode").value)
        assertEquals("35", values.getValue("liquidGlassStrength").value)
        assertEquals("edge", values.getValue("mediaConfig.ttsEngine").value)
        // El valor derivado sigue el almacén (el proveedor predeterminado puede haber hecho que isConfigured sea verdadero), en lugar de codificar un valor esperado.
        assertEquals(
            (settings.providerConfigFlow.first() != null).toString(),
            values.getValue("model.configured").value,
        )
    }
}
