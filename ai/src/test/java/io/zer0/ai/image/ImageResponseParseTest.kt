package io.zer0.ai.image

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v1.0.75: Análisis de respuesta gráfica — Filtrar campos nulos JSON.
 * Agnes/OpenAI En caso de fallo del canal, b64_json puede ser JSON null,JsonNull.content Regresar "null" cuerda,
 * La vieja lógica toma si no es un lanzamiento en blanco. → deletrear data:image/png;base64,null Imagen falsa.
 */
class ImageResponseParseTest {

    private lateinit var agnes: AgnesImageProvider

    @Before
    fun setUp() {
        agnes = AgnesImageProvider(OkHttpClient())
    }

    @Test
    fun `agnes parses b64_json null as no result`() {
        val body = """{"data":[{"b64_json":null}]}"""
        val images = agnes.parseResponseImages(body)
        assertTrue("JSON null b64 debe ser filtrado", images.isEmpty())
    }

    @Test
    fun `agnes parses url null as no result`() {
        val body = """{"data":[{"url":null}]}"""
        val images = agnes.parseResponseImages(body)
        assertTrue("JSON null url debe ser filtrado", images.isEmpty())
    }

    @Test
    fun `agnes parses valid b64`() {
        val body = """{"data":[{"b64_json":"aGVsbG8="}]}"""
        val images = agnes.parseResponseImages(body)
        assertEquals(1, images.size)
        assertEquals("aGVsbG8=", images[0].base64)
    }

    @Test
    fun `agnes parses valid url`() {
        val body = """{"data":[{"url":"https://example.com/img.png"}]}"""
        val images = agnes.parseResponseImages(body)
        assertEquals(1, images.size)
        assertEquals("https://example.com/img.png", images[0].url)
    }

    @Test
    fun `agnes filters empty string fields`() {
        val body = """{"data":[{"b64_json":"","url":""}]}"""
        val images = agnes.parseResponseImages(body)
        assertTrue("Los campos de cadena vacíos deben filtrarse", images.isEmpty())
    }

    @Test
    fun `agnes literal null string is not a valid image`() {
        // El canal ocasionalmente devuelve literal "null" cuerda(no JSON null)
        val body = """{"data":[{"b64_json":"null"}]}"""
        val images = agnes.parseResponseImages(body)
        assertTrue("El nulo literal debe filtrarse", images.isEmpty())
    }

    @Test
    fun `agnes filters non-string scalars`() {
        // B-01: numeros/escalar booleano — JsonPrimitive.content Volver como está "123"/"true",
        // La vieja lógica no es un lanzamiento en blanco. → deletrear data:image/png;base64,123 foto falsa
        val numberBody = """{"data":[{"b64_json":123}]}"""
        assertTrue("El número b64 debe filtrarse", agnes.parseResponseImages(numberBody).isEmpty())
        val boolBody = """{"data":[{"url":true}]}"""
        assertTrue("La URL booleana debe filtrarse.", agnes.parseResponseImages(boolBody).isEmpty())
    }

    @Test
    fun `agnes filters invalid url prefixes`() {
        // B-01: url debe ser http(s) o datos:imagen/prefijo
        val bareBody = """{"data":[{"url":"example.com/img.png"}]}"""
        assertTrue("Las URL desnudas deben filtrarse", agnes.parseResponseImages(bareBody).isEmpty())
        val fileBody = """{"data":[{"url":"/sdcard/img.png"}]}"""
        assertTrue("La URL de la ruta del archivo debe filtrarse", agnes.parseResponseImages(fileBody).isEmpty())
        val dataUriBody = """{"data":[{"url":"data:image/png;base64,aGk="}]}"""
        assertEquals("data:image/png;base64,aGk=", agnes.parseResponseImages(dataUriBody).single().url)
    }

    @Test
    fun `agnes skips invalid items but keeps valid ones`() {
        val body = """{"data":[{"b64_json":null},{"url":"https://example.com/a.png"}]}"""
        val images = agnes.parseResponseImages(body)
        assertEquals(1, images.size)
        assertEquals("https://example.com/a.png", images[0].url)
    }
}
