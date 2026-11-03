package io.zer0.muse.transformer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R-TEST-17: PromptTemplateLoader locale=null / Configuración regional desconocida/falta reserva de plantilla.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PromptTemplateLoaderTest {

    private val loader = PromptTemplateLoader(
        ApplicationProvider.getApplicationContext<Context>(),
    )

    @Test
    fun `locale null loads generic template instead of fallback`() {
        val text = loader.render("decision_tree", null, fallback = "fallback-value")
        assertTrue("generic La plantilla debe contener contenido de reglas de decisión.: $text", text.contains("reglas de decisión"))
        assertNotEquals("fallback-value", text)
    }

    @Test
    fun `zh locale prefers localized template`() {
        val zh = loader.render("decision_tree", "zh")
        val en = loader.render("decision_tree", "en")
        assertTrue("zh La plantilla debe contener reglas de decisión en chino.: $zh", zh.contains("reglas de decisión"))
        assertTrue("en La plantilla debe contener reglas de decisión en inglés.: $en", en.contains("Decision rules"))
        assertNotEquals("zh/en Las plantillas localizadas no deberían ser iguales", zh, en)
    }

    @Test
    fun `unknown locale falls back to english not chinese`() {
        // v2.6.0: Las configuraciones regionales no chinas vuelven a la versión en inglés,Evite que los usuarios internacionales obtengan palabras rápidas en chino.
        val zz = loader.render("decision_tree", "zz")
        assertTrue("La configuración regional desconocida debe recaer en la plantilla en inglés(Contiene Decision rules): $zz", zz.contains("Decision rules"))
        assertEquals("La configuración regional desconocida y en deben ser consistentes", loader.render("decision_tree", "en"), zz)
    }

    @Test
    fun `supported non-chinese locales fall back to english`() {
        // ja/ko/pt/ru no tienen plantilla propia y recurren al inglés.
        // es tiene plantilla española propia y no debe coincidir con la inglesa.
        val en = loader.render("tool_discipline", "en")
        for (loc in listOf("ja", "ko", "pt", "ru")) {
            assertEquals("$loc La disciplina de las herramientas inglesas debería retroceder", en, loader.render("tool_discipline", loc))
        }
        val es = loader.render("tool_discipline", "es")
        assertEquals("es debe usar la plantilla en español", loader.render("tool_discipline", "es"), es)
        assertNotEquals("es no debe coincidir con la plantilla en inglés", en, es)
    }

    @Test
    fun `chinese locale keeps chinese template`() {
        val zh = loader.render("tool_discipline", "zh")
        assertTrue("La configuración regional china debe adoptar la plantilla china.: $zh", zh.contains("Disciplina en el uso de herramientas"))
    }

    @Test
    fun `missing template returns fallback without crashing`() {
        assertEquals("fallback-value", loader.render("not_exist_template", null, fallback = "fallback-value"))
        assertEquals("fallback-value", loader.render("not_exist_template", "ja", fallback = "fallback-value"))
    }

    @Test
    fun `action discipline templates exist and differ by locale`() {
        val zh = loader.render("action_discipline", "zh")
        val en = loader.render("action_discipline", "en")
        assertTrue("zh La disciplina de acción debe contener palabras clave.: $zh", zh.contains("Disciplina de acción"))
        assertTrue("en La disciplina de acción debe contener palabras clave.: $en", en.contains("Action discipline"))
        assertNotEquals("zh/en La disciplina de acción no debe ser la misma.", zh, en)
    }

    @Test
    fun `delivery contract templates exist for all locales`() {
        assertTrue(loader.render("delivery_contract", "zh").contains("Contrato de entrega"))
        assertTrue(loader.render("delivery_contract", "en").contains("Delivery contract"))
        assertTrue(loader.render("delivery_contract", null).contains("Contrato de entrega"))
    }

    @Test
    fun `platform decl includes execution environment section`() {
        assertTrue("La declaración de la plataforma debe incluir un archivo Léame del entorno de ejecución.", loader.render("platform_decl", "zh").contains("entorno de ejecución"))
        assertTrue("La declaración de la plataforma en inglés debe incluir el entorno de ejecución.", loader.render("platform_decl", "en").contains("Execution environment"))
    }

    @Test
    fun `tool discipline declares three tier web priority`() {
        assertTrue("La disciplina de herramientas debe declarar prioridad de nivel tres", loader.render("tool_discipline", "zh").contains("Prioridad de nivel 3"))
        assertTrue("La disciplina de herramientas inglesas debería declarar prioridad de tercer nivel", loader.render("tool_discipline", "en").contains("Three-tier priority"))
    }

    /**
     * v2.6.0 (P2): Protector antideriva —— Cada plantilla registrada debe existir en dos versiones: zh y en,
     * Y los dos no son iguales (uno es chino y el otro es inglés). Esto evita cambios posteriores en una sola cara o que a nuevas plantillas les falte la versión en inglés.
     */
    @Test
    fun `every registered template has distinct zh and en versions`() {
        val missing = mutableListOf<String>()
        for (name in PromptTemplateLoader.TEMPLATE_NAMES) {
            val zh = loader.render(name, "zh", fallback = "")
            val en = loader.render(name, "en", fallback = "")
            if (zh.isBlank()) missing += "$name(zh)"
            if (en.isBlank()) missing += "$name(en)"
            if (zh.isNotBlank() && zh == en) missing += "$name(zh==en No verdaderamente internacionalizado)"
        }
        assertTrue("Falta la plantilla o el chino y el inglés son iguales: $missing", missing.isEmpty())
    }

    /**
     * v2.6.0 (P2): Las configuraciones regionales no chinas nunca obtienen plantillas chinas —— Antirrebote.
     * Represente cada plantilla con ja y confirme que el resultado no contiene "características de párrafo CJK chino" y es igual a en.
     */
    @Test
    fun `non-chinese locale never yields chinese template`() {
        val offenders = mutableListOf<String>()
        for (name in PromptTemplateLoader.TEMPLATE_NAMES) {
            val ja = loader.render(name, "ja", fallback = "")
            val en = loader.render(name, "en", fallback = "")
            if (ja.isNotBlank() && en.isNotBlank() && ja != en) offenders += name
        }
        assertTrue("Las siguientes plantillas no recurren al inglés para ja.: $offenders", offenders.isEmpty())
    }
}
