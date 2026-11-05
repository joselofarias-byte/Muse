package io.zer0.muse.update

import io.zer0.common.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.72: Se actualizaron las pruebas de lógica central de verificación.
 *
 * Portada:
 *  - Comparación de versiones semánticas(con el prefijo v/versión multisegmento/longitudes variables)
 *  - ReleaseInfo serialización ida y vuelta(UI Banner dependencia de caché)
 *  - Verificación de confianza de URL de descarga + constantes de la página de descarga del sitio web oficial(Descargar/Actualizar migración de exportación)
 *  - "No más mensajes después de actualizar a la misma versión"lógica de decisión(compareVersions >= 0 Es decir, sin aviso)
 *
 * Nota:No depende de Internet,Prueba de lógica pura,CI Puede funcionar de forma estable.
 */
class UpdateCheckerVersionTest {

    // ── Comparación de versiones(UpdateChecker.compareVersions) ──

    @Test
    fun `current older than latest returns negative`() {
        assertTrue(UpdateChecker.compareVersions("1.0.70", "v1.0.71") < 0)
        assertTrue(UpdateChecker.compareVersions("1.0.70", "1.0.71") < 0)
        assertTrue(UpdateChecker.compareVersions("1.0.70", "1.1.0") < 0)
        assertTrue(UpdateChecker.compareVersions("1.0.70", "1.0.100") < 0)
    }

    @Test
    fun `current equal to latest returns zero`() {
        assertEquals(0, UpdateChecker.compareVersions("1.0.71", "v1.0.71"))
        assertEquals(0, UpdateChecker.compareVersions("v1.0.71", "v1.0.71"))
        assertEquals(0, UpdateChecker.compareVersions("1.0.71", "1.0.71"))
    }

    @Test
    fun `current newer than latest returns positive`() {
        assertTrue(UpdateChecker.compareVersions("1.0.72", "v1.0.71") > 0)
        assertTrue(UpdateChecker.compareVersions("1.1.0", "1.0.99") > 0)
    }

    @Test
    fun `unequal length versions compare by missing segments as zero`() {
        // "1.0" considerado como [1,0],con "1.0.1"([1,0,1]) comparar → 1.0 < 1.0.1
        assertTrue(UpdateChecker.compareVersions("1.0", "1.0.1") < 0)
        // "1.0.1" > "1.0"
        assertTrue(UpdateChecker.compareVersions("1.0.1", "1.0") > 0)
    }

    @Test
    fun `non numeric segments are ignored`() {
        // "1.0.70-beta" split('.') → ["1","0","70-beta"],Ignorar párrafos enteros no numéricos → [1,0]
        // con [1,0,70] comparar:Botón de segmento faltante 0 → 1.0.70-beta < v1.0.70
        assertTrue(UpdateChecker.compareVersions("1.0.70-beta", "v1.0.70") < 0)
        // Los campos numéricos puros aún se comparan normalmente
        assertTrue(UpdateChecker.compareVersions("1.0.70", "1.0.71") < 0)
    }

    // ── ReleaseInfo serialización ida y vuelta(UI Banner caché) ──

    @Test
    fun `release info round trips through json`() {
        val release = UpdateChecker.ReleaseInfo(
            tagName = "v1.0.72",
            name = "v1.0.72",
            body = "Corregir verificación de actualización",
            htmlUrl = "https://github.com/Zer0Qing/Muse/releases/tag/v1.0.72",
            publishedAt = 1_770_000_000_000L,
            apkAssets = listOf(
                UpdateChecker.ApkAsset(
                    name = "Muse_v1.0.72_arm64-v8a.apk",
                    downloadUrl = "https://objects.githubusercontent.com/muse.apk",
                    size = 42_000_000L,
                    sha256 = "a".repeat(64),
                ),
            ),
        )
        val json = AppJson.encodeToString(UpdateChecker.ReleaseInfo.serializer(), release)
        val decoded = AppJson.decodeFromString(UpdateChecker.ReleaseInfo.serializer(), json)

        assertEquals("v1.0.72", decoded.tagName)
        assertEquals(1_770_000_000_000L, decoded.publishedAt)
        assertEquals(1, decoded.apkAssets.size)
        assertEquals("Muse_v1.0.72_arm64-v8a.apk", decoded.apkAssets[0].name)
        assertEquals(42_000_000L, decoded.apkAssets[0].size)
        assertEquals("a".repeat(64), decoded.apkAssets[0].sha256)
    }

    @Test
    fun `download asset accepts only trusted https fork release URLs`() {
        val trusted = UpdateChecker.ApkAsset(
            name = "Muse.apk",
            downloadUrl = "https://github.com/joselofarias-byte/Muse/releases/download/v1/Muse.apk",
            size = 1L,
            sha256 = "b".repeat(64),
        )
        val wrongScheme = trusted.copy(downloadUrl = trusted.downloadUrl.replace("https://", "http://"))
        val wrongHost = trusted.copy(downloadUrl = "https://example.com/releases/Muse.apk")
        val wrongExtension = trusted.copy(downloadUrl = trusted.downloadUrl.replace(".apk", ".zip"))
        val upstreamAsset = trusted.copy(downloadUrl = "https://github.com/Zer0Qing/Muse/releases/download/v1/Muse.apk")
        val unscopedAsset = trusted.copy(downloadUrl = "https://objects.githubusercontent.com/releases/Muse.apk")
        val wrongHash = trusted.copy(sha256 = "not-a-sha256")

        assertTrue(UpdateChecker.isTrustedApkAsset(trusted))
        assertTrue(!UpdateChecker.isTrustedApkAsset(wrongScheme))
        assertTrue(!UpdateChecker.isTrustedApkAsset(wrongHost))
        assertTrue(!UpdateChecker.isTrustedApkAsset(wrongExtension))
        assertTrue(!UpdateChecker.isTrustedApkAsset(wrongHash))
        assertTrue(!UpdateChecker.isTrustedApkAsset(upstreamAsset))
        assertTrue(!UpdateChecker.isTrustedApkAsset(unscopedAsset))
    }

    // ── Banner mostrar juicio(No más mensajes después de actualizar a la misma versión) ──

    @Test
    fun `official download page points to fork releases`() {
        assertEquals("https://github.com/joselofarias-byte/Muse/releases/latest", UpdateNotifier.OFFICIAL_DOWNLOAD_PAGE)
    }

    @Test
    fun `untrusted download url is rejected`() {
        // La verificación de confianza de la capa de resolución de activos aún rechaza direcciones HTTPS oficiales que no son de GitHub.
        assertTrue(!UpdateChecker.isTrustedDownloadUrl("https://example.com/malicious.apk"))
        assertTrue(!UpdateChecker.isTrustedDownloadUrl("http://github.com/app.apk"))
        assertTrue(!UpdateChecker.isTrustedDownloadUrl("https://github.com/Zer0Qing/Muse/releases/download/v1/a.apk"))
    }

    @Test
    fun `banner should not show when current equals cached latest`() {
        // HomeScreen juicio:compareVersions(current, cached.tagName) < 0 solo mostrar Banner
        val current = "1.0.71"
        val cached = "v1.0.71"
        assertTrue("No debería mostrarse después de actualizar a la misma versión. Banner", UpdateChecker.compareVersions(current, cached) >= 0)
    }

    @Test
    fun `banner should show when current older than cached`() {
        val current = "1.0.70"
        val cached = "v1.0.71"
        assertTrue("Debe mostrarse cuando existe una nueva versión. Banner", UpdateChecker.compareVersions(current, cached) < 0)
    }

    @Test
    fun `manual update result distinguishes new version from current version`() {
        val release = UpdateChecker.ReleaseInfo(
            tagName = "v2.4.0",
            name = "Muse v2.4.0",
            body = "",
            htmlUrl = "https://github.com/Zer0Qing/Muse/releases/tag/v2.4.0",
            publishedAt = 0L,
            apkAssets = emptyList(),
        )
        assertEquals(
            UpdateCheckResult.NewVersion(release),
            classifyUpdateResult("2.3.1", io.zer0.common.Result.Success(release)),
        )
        assertEquals(
            UpdateCheckResult.UpToDate,
            classifyUpdateResult("2.4.0", io.zer0.common.Result.Success(release)),
        )
    }

    @Test
    fun `manual update failure remains failure instead of up to date`() {
        assertEquals(
            UpdateCheckResult.Failed("network unavailable"),
            classifyUpdateResult("2.3.1", io.zer0.common.Result.Error("network unavailable")),
        )
    }
}
