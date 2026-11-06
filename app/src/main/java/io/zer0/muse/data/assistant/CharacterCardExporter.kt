package io.zer0.muse.data.assistant

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * SillyTavern Exportador de tarjetas de personajes.
 *
 * Soporta dos salidas:
 *  - PNG: Escribir JSON base64 codificado PNG tEXt chunk (keyword="chara")
 *  - JSON: Escribe la tarjeta de personaje V2 directamente JSON
 *
 * PNG Política de exportación:
 *  - si [avatar] No vacío,Úselo como un vector PNG (compress Postinyección de bytes PNG tEXt)
 *  - si [avatar] esta vacio,Generar PNG transparente 1x1 como vector (Solo lleva metadatos,Sin vista)
 *
 * PNG chunk Implementación manual de inyección. (ver [PngChunkUtil]),No se agregan nuevas dependencias.
 *
 * Siga las convenciones de formato de tarjeta de personaje v2 de SillyTavern:PNG tEXt chunk y JSON dos operadores.
 */
object CharacterCardExporter {

    private const val TAG = "CharacterCardExporter"

    /**
     * Exporta como tarjetas de personajes PNG de SillyTavern.
     *
     * @param context Utilizado por ContentResolver para escribir destUri
     * @param assistant Asistente de fuente
     * @param avatar avatar opcional Bitmap;Si es nulo, utilice un PNG transparente de 1x1 como vector.
     * @param destUri objetivo URI (SAF CreateDocument Regresar)
     */
    suspend fun exportToPng(context: Context, assistant: AssistantEntity, avatar: Bitmap?, destUri: Uri): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val card = SillyTavernCardV2.fromAssistantEntity(assistant)
                val json = AppJson.encodeToString(SillyTavernCardV2.serializer(), card)
                // NO_WRAP: No insertar nuevas líneas, Segmento único de fragmento de texto garantizado text
                val base64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

                // Generar bytes vectoriales PNG (avatar o 1x1 transparente)
                val pngBytes = encodePngBytes(avatar)
                // inyectar tEXt chunk (Después de insertar en IHDR)
                val finalBytes = PngChunkUtil.writeTextChunk(pngBytes, "chara", base64)

                context.contentResolver.openOutputStream(destUri)?.use { os ->
                    os.write(finalBytes)
                    os.flush()
                } ?: error("No se puede escribir en el destino URI")
            }.onFailure { Logger.w(TAG, "exportToPng failed: ${it.message}") }
        }

    /**
     * Exportar como tarjeta de personaje JSON de SillyTavern (V2 estructura).
     */
    suspend fun exportToJson(context: Context, assistant: AssistantEntity, destUri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val card = SillyTavernCardV2.fromAssistantEntity(assistant)
            val json = AppJson.encodeToString(SillyTavernCardV2.serializer(), card)
            context.contentResolver.openOutputStream(destUri)?.use { os ->
                os.write(json.toByteArray(Charsets.UTF_8))
                os.flush()
            } ?: error("No se puede escribir en el destino URI")
        }.onFailure { Logger.w(TAG, "exportToJson failed: ${it.message}") }
    }

    // ── herramientas internas ──

    /** Codificar mapa de bits en bytes PNG;bitmap Devuelve un PNG transparente de 1x1 cuando es nulo. */
    private fun encodePngBytes(bitmap: Bitmap?): ByteArray {
        val bmp = bitmap ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }
}
