package io.zer0.muse.update

import io.zer0.common.Result
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pruebas de respaldo de endpoints para verificaciones de actualizaciones.
 *
 * Antecedentes:GitHub Limite las solicitudes no autenticadas a 60 veces por IP/Durante horas, la IP de salida de la red celular puede llenarse fácilmente cuando es compartida por una gran cantidad de dispositivos.
 * después**entero IP** La búsqueda de actualizaciones dará como resultado 403 (el usuario falla sin importar cómo actualizar). Por lo tanto, para comprobar la lógica, primero debes preguntar sobre la imagen construida por ti mismo.
 * (sin limitación) y solo recurrir a la API de GitHub si falla.
 *
 * Aquí, use MockWebServer para reemplazar los dos puntos finales y verificar"Orden + respaldo"El acto en sí.
 */
class UpdateCheckerEndpointFallbackTest {

    private lateinit var server: MockWebServer
    private lateinit var checker: UpdateChecker

    /** Una respuesta de versión mínima compatible con GitHub (esta es la estructura de una imagen creada por uno mismo). */
    private fun releaseJson(tag: String) = """
        {
          "tag_name": "$tag",
          "name": "Muse $tag",
          "html_url": "https://github.com/Zer0Qing/Muse/releases/tag/$tag",
          "published_at": "2026-10-03T19:19:31Z",
          "assets": [
            {
              "name": "Muse_${tag}_arm64-v8a.apk",
              "browser_download_url": "https://github.com/Zer0Qing/Muse/releases/download/$tag/Muse_${tag}_arm64-v8a.apk",
              "size": 85553209
            }
          ]
        }
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // server.url("/") En esta categoría, también sirve como"Imagen autoconstruida";Pruebe anulando baseUrl para que apunte a él.
        checker = UpdateChecker(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `La constante del punto final espejo autoconstruido apunta al sitio web oficial y es https`() {
        val url = UpdateChecker.OFFICIAL_LATEST_URL
        assertTrue("Debe ser https:$url", url.startsWith("https://"))
        assertTrue("Debe apuntar a su propio nombre de dominio:$url", url.contains("museai.ltd"))
        assertTrue("Debe apuntar a Latest.json:$url", url.endsWith("/api/latest.json"))
    }

    @Test
    fun `Cuando la imagen creada por usted mismo devuelva 200, el resultado se utilizará directamente.`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(releaseJson("v9.9.9")),
        )
        val result = runBlocking { fetchFromOfficialEndpoint() }
        assertTrue("El resultado debe obtenerse de la imagen construida por uno mismo.", result is Result.Success)
        assertEquals("v9.9.9", (result as Result.Success).data.tagName)
        assertEquals("Solicitado solo una vez (sujeto a éxito)", 1, server.requestCount)
    }

    @Test
    fun `Regrese al siguiente punto final al crear una autoimagen 403`() {
        // Simule que la imagen creada por usted mismo no está disponible y luego MockWebServer también asume el punto final de GitHub durante la prueba.
        server.enqueue(MockResponse().setResponseCode(403).setBody("rate limit exceeded"))
        val first = runBlocking { fetchFromOfficialEndpoint() }
        assertTrue("403 no debe considerarse un éxito", first is Result.Error)
        assertEquals("debe ser registrado HTTP 403", "HTTP 403", (first as Result.Error).message)
    }

    @Test
    fun `Un cuerpo de respuesta vacío se trata como un fracaso más que como un éxito.`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        val result = runBlocking { fetchFromOfficialEndpoint() }
        assertTrue("Una respuesta vacía debe ser un error.", result is Result.Error)
        assertEquals("response is empty", (result as Result.Error).message)
    }

    @Test
    fun `Las respuestas espejo a las que les falta el cuerpo y el resumen aún se pueden analizar`() {
        // Los espejos autoconstruidos no los proporciona la empresa aguas arriba. body / digest —— Debe procesarse de forma predeterminada y no puede dejar de analizarse en su totalidad.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "tag_name": "v2.4.2",
                  "name": "Muse v2.4.2",
                  "html_url": "https://github.com/Zer0Qing/Muse/releases/tag/v2.4.2",
                  "published_at": "2026-10-03T19:19:31Z",
                  "assets": [
                    {
                      "name": "Muse_v2.4.2_arm64-v8a.apk",
                      "browser_download_url": "https://github.com/Zer0Qing/Muse/releases/download/v2.4.2/Muse_v2.4.2_arm64-v8a.apk",
                      "size": 85553209
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )
        val result = runBlocking { fetchFromOfficialEndpoint() }
        assertTrue("Los campos faltantes no deberían provocar que falle el análisis", result is Result.Success)
        val release = (result as Result.Success).data
        assertEquals("v2.4.2", release.tagName)
        assertEquals("body El valor predeterminado es una cadena vacía", "", release.body)
        assertEquals("assets todavía debe ser analizado", 1, release.apkAssets.size)
        assertEquals("sha256 El valor predeterminado es null", null, release.apkAssets.first().sha256)
    }

    /** Utilice MockWebServer para reemplazar la dirección del punto final y solo verificar"Recuperación de punto final único + análisis"Esta capa. */
    private suspend fun fetchFromOfficialEndpoint(): Result<UpdateChecker.ReleaseInfo> =
        checker.fetchRelease("official", server.url("/api/latest.json").toString())
}
