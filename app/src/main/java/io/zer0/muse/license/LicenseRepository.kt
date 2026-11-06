package io.zer0.muse.license

import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Phase 13: Cargador de datos con licencia de código abierto.
 *
 * Responsabilidades:
 *  1. Cargar manifiesto de dependencia desde activos/licenses/manifest.json
 *  2. Cargue el texto completo del acuerdo desde activos bajo demanda(Como por ejemplo "licenses/apache-2.0.txt")
 *  3. Degradado a nulo cuando falla el análisis manifest,Evitar que la aplicación falle al iniciarse
 *
 * Rendimiento:
 *  - manifest caché en la memoria(Analizar solo una vez)
 *  - licenseTexts Carga diferida de texto bajo demanda(Apache 2.0 Texto completo ~9KB,Carga diferida sin bloqueo UI)
 *  - Utilice Dispatchers.IO sin bloquear el hilo principal
 */
class LicenseRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true // Compatible con futuras ampliaciones de campo
        isLenient = true
    }

    @Volatile
    private var cached: LicenseManifest? = null

    /** Cargar manifest,Retornar vacío en caso de falla manifest(Lista de dependencias vacía). */
    suspend fun loadManifest(): LicenseManifest {
        // L7: Regresar directamente cuando está en caché,Evite el cambio de hilo redundante
        cached?.let { return it }
        return withContext(Dispatchers.IO) {
            // doble verificación:Confirme nuevamente después de ingresar a IO,Evite el análisis repetido en escenarios concurrentes
            cached?.let { return@withContext it }
            val loaded = resultOf {
                val text = context.assets.open(MANIFEST_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
                json.decodeFromString(LicenseManifest.serializer(), text)
            }.onError { msg, t ->
                Logger.e("LicenseRepository", "loadManifest failed: $msg", t)
            }.getOrNull() ?: LicenseManifest(
                schemaVersion = 0,
                generatedAt = "",
                appName = "",
                appVersion = "",
                licenseTexts = emptyMap(),
                dependencies = emptyList(),
            )
            cached = loaded
            loaded
        }
    }

    /** Cargar el texto completo del acuerdo(bajo demanda),No encontrado devuelve nulo. */
    suspend fun loadLicenseText(licenseId: String): String? = withContext(Dispatchers.IO) {
        val manifest = loadManifest()
        val relPath = manifest.licenseTexts[licenseId] ?: return@withContext null
        resultOf {
            context.assets.open(relPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.onError { msg, t ->
            Logger.w("LicenseRepository", "loadLicenseText($licenseId) failed: $msg", t)
        }.getOrNull()
    }

    companion object {
        /** manifest.json Ruta relativa dentro de los activos. */
        const val MANIFEST_PATH = "licenses/manifest.json"
    }
}
