package io.zer0.muse.update

import io.zer0.common.Result
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerSecurityTest {
    private val checker = UpdateChecker(OkHttpClient(), supportedAbis = { listOf("arm64-v8a") })

    private fun release(tag: String = "v2.4.2", assetTag: String = tag, name: String = "Muse_${tag}_arm64-v8a.apk", owner: String = "joselofarias-byte", extra: String = ""): String = """
        {"tag_name":"$tag", "html_url":"https://github.com/$owner/Muse/releases/tag/$tag",
         "assets":[{"name":"$name", "size":100,
         "browser_download_url":"https://github.com/$owner/Muse/releases/download/$assetTag/$name"}]$extra}
    """.trimIndent()

    private fun fetch(body: String): Result<UpdateChecker.ReleaseInfo> = MockWebServer().use { server ->
        server.start()
        server.enqueue(MockResponse().setBody(body))
        runBlocking { checker.fetchRelease("test", server.url("/latest").toString()) }
    }

    @Test
    fun `reject foreign release metadata and mismatched asset versions`() {
        assertTrue(fetch(release(owner = "Zer0Qing")) is Result.Error)
        assertTrue(fetch(release(assetTag = "v2.4.1")) is Result.Error)
        assertTrue(fetch(release(name = "Muse_v2.4.1_arm64-v8a.apk")) is Result.Error)
        assertTrue(fetch(release(tag = "invalid")) is Result.Error)
        assertTrue(fetch(release(extra = ",\"prerelease\":true")) is Result.Error)
        assertTrue(fetch(release(extra = ",\"draft\":true")) is Result.Error)
    }

    @Test
    fun `only explicitly compatible ABI is offered`() {
        assertTrue(fetch(release()) is Result.Success)
        assertTrue(fetch(release(name = "Muse_v2.4.2_x86_64.apk")) is Result.Error)
        assertTrue(fetch(release(name = "Muse_v2.4.2.apk")) is Result.Error)
    }

    @Test
    fun `cannot configure another repository`() {
        val error = runCatching { UpdateChecker(OkHttpClient(), repo = "Zer0Qing/Muse") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `API redirects do not escape to mirrors`() = MockWebServer().use { server ->
        server.start()
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/mirror")))
        server.enqueue(MockResponse().setBody(release()))
        val result = runBlocking { checker.fetchRelease("test", server.url("/latest").toString()) }
        assertTrue(result is Result.Error)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `reject ambiguous or unscoped download locations`() {
        val base = "https://github.com/joselofarias-byte/Muse/releases/download/v2.4.2/Muse.apk"
        assertTrue(UpdateChecker.isTrustedDownloadUrl(base))
        listOf(
            "$base?redirect=other",
            "$base#other",
            base.replace("Muse.apk", "..%2FMuse.apk"),
            base.replace("github.com/", "github.com:444/"),
            base.replace("github.com/", "user@github.com/"),
            base.replace("joselofarias-byte", "another-owner"),
            "https://objects.githubusercontent.com/Muse.apk",
            "https://museai.ltd/Muse.apk",
        ).forEach { assertFalse(it, UpdateChecker.isTrustedDownloadUrl(it)) }
    }
}
