package io.zer0.muse.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.7 Retorno:[LocaleContextWrapper] Es necesario evitar que la Actividad falle cuando se inicia en un contexto que no es de Actividad.
 *
 * ## accidente recurrente
 *
 * RuntimeLocaleProvider usar `applicationContext.createConfigurationContext()` Portada
 * Compose El LocalContext (para cambio de idioma en caliente). El contexto no está activo;Compose selección de texto
 * El elemento del menú "Procesando texto" (ProcessText) de eso `startActivity` sin traer
 * FLAG_ACTIVITY_NEW_TASK → AndroidRuntimeException Crash (probado por usuarios).
 *
 * ## comportamiento bloqueado
 *
 * Después del embalaje `startActivity` FLAG_ACTIVITY_NEW_TASK se agregará automáticamente (escena que no es de actividad);
 * Activity La escena permanece como está; el empaquetado idempotente no duplica el anidamiento;resources La base todavía se transmite de forma transparente.
 *
 * Nota: No requerido `Robolectric.buildActivity()`——Pasará por el ciclo de vida completo de la Actividad,
 * Provoca un bloqueo en la biblioteca SQLite nativa de Robolectric en Windows (no relacionado con esta lógica).
 * Aquí, la "base registrada" se utiliza para observar directamente la intención realmente emitida por el contenedor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocaleContextWrapperTest {

    private val appContext: Context get() = ApplicationProvider.getApplicationContext()

    /** Contexto base registrado: captura la intención realmente enviada por el contenedor. */
    private class RecordingContext(base: Context) : ContextWrapper(base) {
        var captured: Intent? = null

        override fun startActivity(intent: Intent) {
            captured = intent
        }

        override fun startActivity(intent: Intent, options: Bundle?) {
            captured = intent
        }
    }

    /**
     * Base de actividad registrada: escrita como actividad, pero capturada en lugar de iniciada realmente.
     *
     * Utilice simulacro en lugar de `class X : Activity()`——La construcción de este último desencadena la inicialización de la actividad.
     * En Robolectric/Windows, la biblioteca SQLite nativa fallará (no relacionado con esta lógica).
     */
    private fun recordingActivity(): Activity {
        val captured = arrayOfNulls<Intent>(1)
        val activity = mockk<Activity>(relaxed = true)
        every { activity.startActivity(any()) } answers {
            captured[0] = firstArg()
        }
        every { activity.startActivity(any(), any()) } answers {
            captured[0] = firstArg()
        }
        lastCaptured = captured
        return activity
    }

    private var lastCaptured: Array<Intent?>? = null

    @Test
    fun `Compensación al iniciar una actividad en un contexto que no es de actividad NEW_TASK`() {
        val base = RecordingContext(appContext)
        val wrapped = LocaleContextWrapper.wrap(base)

        wrapped.startActivity(Intent(Intent.ACTION_VIEW))

        val flags = base.captured?.flags ?: 0
        assertTrue(
            "El inicio del contexto sin actividad debe traer FLAG_ACTIVITY_NEW_TASK",
            flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
    }

    @Test
    fun `Los intents con NEW_TASK no se reescribirán`() {
        val base = RecordingContext(appContext)
        val wrapped = LocaleContextWrapper.wrap(base)

        wrapped.startActivity(Intent(Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        assertEquals(
            "Los intents marcados deben pasarse tal cual",
            Intent.FLAG_ACTIVITY_NEW_TASK,
            (base.captured?.flags ?: 0) and Intent.FLAG_ACTIVITY_NEW_TASK,
        )
    }

    @Test
    fun `Activity La escena no agrega indicadores adicionales (mantiene la semántica de salto existente)`() {
        val activity = recordingActivity()
        val wrapped = LocaleContextWrapper.wrap(activity)

        wrapped.startActivity(Intent(Intent.ACTION_VIEW))

        assertEquals(
            "Activity context no debe ser compensado NEW_TASK",
            0,
            (lastCaptured?.get(0)?.flags ?: 0) and Intent.FLAG_ACTIVITY_NEW_TASK,
        )
    }

    @Test
    fun `El contenedor repetido es idempotente`() {
        val once = LocaleContextWrapper.wrap(appContext)
        val twice = LocaleContextWrapper.wrap(once)
        assertTrue("El ajuste repetido debería devolver la misma instancia", once === twice)
    }

    @Test
    fun `resources Todavía de ser empaquetado locale context`() {
        // Las capacidades lingüísticas no se ven afectadas por el embalaje:getResources Se debe pasar a base
        val wrapped = LocaleContextWrapper.wrap(appContext)
        assertEquals(
            "resources La configuración de la base debe transmitirse de forma transparente.",
            appContext.resources.configuration.locales,
            wrapped.resources.configuration.locales,
        )
        assertNotEquals("Sigue siendo una instancia de ContextWrapper después de envolver", appContext, wrapped)
    }
}
