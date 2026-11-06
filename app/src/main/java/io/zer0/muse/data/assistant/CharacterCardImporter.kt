package io.zer0.muse.data.assistant

import android.content.Context
import android.net.Uri
import android.util.Base64
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream

/**
 * SillyTavern Importador de tarjetas de personajes.
 *
 * Soporta dos fuentes:
 *  - PNG fotos: tEXt chunk (keyword="chara") Almacenar codificado en base64 JSON
 *  - JSON Archivo: Analizado directamente en tarjetas de personajes V1/V2
 *
 * PNG chunk Analizar implementación manual (ver [PngChunkUtil]),No se introducen bibliotecas PNG de terceros.
 *
 * V1/V2 reconocimiento automático:JSON La capa superior contiene "spec" Los campos son tratados como V2,De lo contrario, se analiza según la estructura plana V1.
 *
 * No hay nuevas dependencias,Simplemente use android.util.Base64 + ContentResolver.
 *
 * Siga las convenciones de formato de tarjeta de personaje v2 de SillyTavern:PNG tEXt chunk y JSON dos operadores.
 */
object CharacterCardImporter {

    private const val TAG = "CharacterCardImporter"

    // Restricción en fila única 20MB,Evite que archivos grandes maliciosos causen OOM (Las tarjetas de personajes generalmente < 5MB)
    private const val MAX_FILE_BYTES = 20L * 1024 * 1024

    /**
     * Importe tarjetas de personajes desde metadatos de imágenes PNG.
     *
     * PNG tEXt chunk Formato: length(4) + "tEXt"(4) + keyword + 0x00 + text + CRC(4)
     * SillyTavern Acuerdo keyword="chara", text=base64(JSON).
     *
     * @return Devuelve con éxito los datos de la tarjeta de personaje. [AssistantEntity] (id esta vacio, La persona que llama necesita generar una identificación y upsert);
     *         Retorno del fracaso Result.failure
     */
    suspend fun importFromPng(context: Context, uri: Uri): Result<AssistantEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = readUriWithLimit(context, uri) ?: error("No se puede leer el archivo")
            val base64Text = PngChunkUtil.readTextChunk(bytes, "chara")
                ?: error("PNG metadatos de chara no encontrados en (Tarjetas de personajes que no son de SillyTavern)")
            val json = String(Base64.decode(base64Text, Base64.DEFAULT), Charsets.UTF_8)
            val card = parseCardJson(json)
            card.toAssistantEntity()
        }.onFailure { Logger.w(TAG, "importFromPng failed: ${it.message}") }
    }

    /**
     * Importar tarjetas de personajes desde un archivo JSON (V1 Disponible en construcción plana o envuelta V2).
     */
    suspend fun importFromJson(context: Context, uri: Uri): Result<AssistantEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = readUriWithLimit(context, uri) ?: error("No se puede leer el archivo")
            val json = String(bytes, Charsets.UTF_8)
            val card = parseCardJson(json)
            card.toAssistantEntity()
        }.onFailure { Logger.w(TAG, "importFromJson failed: ${it.message}") }
    }

    /**
     * Reconoce formatos e importa automáticamente.
     *
     * estrategia de despacho:
     *  - MIME Contiene "png" → ir [importFromPng]
     *  - MIME Contiene "json" → ir [importFromJson]
     *  - MIME desconocido → Pruebe primero PNG, Inténtalo de nuevo después del fracaso. JSON (Compatible con fuentes donde MIME no está anotado correctamente)
     */
    suspend fun importAuto(context: Context, uri: Uri): Result<AssistantEntity> = withContext(Dispatchers.IO) {
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: ""
        when {
            mime.contains("png", ignoreCase = true) -> importFromPng(context, uri)
            mime.contains("json", ignoreCase = true) -> importFromJson(context, uri)
            else -> {
                // MIME desconocido: Pruebe primero PNG, Repliegue ante el fracaso JSON
                val pngResult = importFromPng(context, uri)
                if (pngResult.isSuccess) pngResult else importFromJson(context, uri)
            }
        }
    }

    // ── herramientas internas ──

    /** Leer todos los bytes de URI,exceder [MAX_FILE_BYTES] rechazar,Devuelve nulo. */
    private fun readUriWithLimit(context: Context, uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8 * 1024)
                var read: Int
                var total = 0L
                while (input.read(buf).also { read = it } > 0) {
                    total += read
                    if (total > MAX_FILE_BYTES) {
                        Logger.w(TAG, "El archivo excede ${MAX_FILE_BYTES / 1024 / 1024}MB límite superior, Importación rechazada")
                        return null
                    }
                    out.write(buf, 0, read)
                }
                out.toByteArray()
            }
        } catch (e: Exception) {
            Logger.w(TAG, "readUri failed: ${e.message}")
            null
        }
    }

    /** Analizar JSON en tarjetas V2;Reconoce automáticamente la estructura plana V1 y la envuelve en V2. */
    private fun parseCardJson(json: String): SillyTavernCardV2 {
        val element = AppJson.parseToJsonElement(json)
        val obj = element.jsonObject
        return if (obj["spec"] != null) {
            // V2: El nivel superior contiene el campo de especificaciones.,Decodificar directamente
            AppJson.decodeFromString(SillyTavernCardV2.serializer(), json)
        } else {
            // V1: estructura plana,Trate el campo plano como datos y envuélvalo en V2
            val data = AppJson.decodeFromString(SillyTavernCardData.serializer(), json)
            SillyTavernCardV2(data = data)
        }
    }
}
