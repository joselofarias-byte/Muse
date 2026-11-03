package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserManagerInstrumentedTest {

    @Test
    fun navigateAndEvaluateOnDevice() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = BrowserManager(context)
        try {
            withTimeout(30_000) {
                val blank = manager.navigate("about:blank")
                assertTrue("about:blank Error de navegación: ${blank.exceptionOrNull()}", blank.isSuccess)

                val js = manager.evaluateJs("1+1")
                assertTrue("evaluateJs falló: ${js.exceptionOrNull()}", js.isSuccess)
                assertEquals("2", js.getOrNull())

                val web = manager.navigate("file:///android_asset/test_browser_page.html")
                assertTrue("Error en la navegación de la página de prueba local: ${web.exceptionOrNull()}", web.isSuccess)

                var html = ""
                repeat(25) {
                    html = manager.currentHtml.value
                    if (html.contains("Muse Local Test")) return@repeat
                    delay(200)
                }
                assertTrue("Falta el HTML de la página de prueba local: $html", html.contains("Muse Local Test"))
            }
        } finally {
            manager.close()
        }
    }

    @Test
    fun explicitInitializationShowsBlankPage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = BrowserManager(context)
        try {
            withTimeout(30_000) {
                val initialized = withContext(Dispatchers.Main) {
                    runCatching { manager.initialize() }
                }
                assertTrue("La inicialización explícita falló: ${initialized.exceptionOrNull()}", initialized.isSuccess)

                val shown = manager.showBlankPageIfNeeded()
                assertTrue("Error al mostrar la página en blanco: ${shown.exceptionOrNull()}", shown.isSuccess)
                assertEquals("about:blank", manager.currentUrl.value)
                assertTrue("Una página en blanco debería activar el navegador.", manager.isActive.value)

                // Las llamadas repetidas cuando ya existe una página no deberían redirigir la navegación ni cambiar el estado actual.
                val repeated = manager.showBlankPageIfNeeded()
                assertTrue("No se pudo mostrar la página en blanco repetidamente: ${repeated.exceptionOrNull()}", repeated.isSuccess)
                assertEquals("about:blank", manager.currentUrl.value)
            }
        } finally {
            manager.close()
        }
    }

    @Test
    fun browserToolsWorkThroughToolRegistry() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val registry = ToolRegistry(context)
        val names = registry.listTools().map { it.name }
        for (name in listOf(
            "browser_navigate",
            "browser_click",
            "browser_type",
            "browser_extract",
            "browser_scroll_bottom",
            "browser_get_html",
        )) {
            assertTrue("ToolRegistry desaparecido $name", name in names)
        }

        val nav = registry.executeFromJson(
            "browser_navigate",
            """{"url": "file:///android_asset/test_browser_page.html"}""",
        )
        assertTrue("browser_navigate Resultados anormales: $nav", nav.contains("\"success\":true"))

        var htmlResult = ""
        repeat(25) {
            htmlResult = registry.executeFromJson("browser_get_html", "{}")
            if (htmlResult.contains("Muse Local Test")) return@repeat
            delay(200)
        }
        assertTrue("browser_get_html Resultados anormales: $htmlResult", htmlResult.contains("Muse Local Test"))
    }
}
