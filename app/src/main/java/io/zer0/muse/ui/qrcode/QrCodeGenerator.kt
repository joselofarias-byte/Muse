package io.zer0.muse.ui.qrcode

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.zer0.ai.core.ProviderConfig
import io.zer0.common.AppJson
import io.zer0.common.Logger

/**
 * v1.97: herramienta de código QR — Provider Generación y análisis de códigos QR configurados.
 *
 * Formato:
 * ```
 * ai-provider:v1:<Base64(JSON(ProviderConfig))>
 * ```
 *
 * Uso:
 * ```
 * // Generar código QR
 * val bitmap = QrCodeGenerator.generateProviderQr(providerConfig, size = 600)
 *
 * // Analizar el contenido del código QR
 * val provider = QrCodeGenerator.parseProviderQr(qrContent)
 * ```
 *
 * Consejos de seguridad:El código QR contiene texto sin formato apiKey,Después de escanear, compártalo solo con destinatarios confiables.
 */
object QrCodeGenerator {

    private const val TAG = "QrCodeGenerator"

    /** Prefijo del código QR,Identifica la versión de configuración del proveedor. */
    private const val QR_PREFIX = "ai-provider:v1:"

    /** Tamaño de código QR predeterminado(Píxel). */
    const val DEFAULT_QR_SIZE = 600

    /**
     * Codifique ProviderConfig en una cadena de código QR.
     *
     * @param config Provider Configuración
     * @return en forma de "ai-provider:v1:<Base64>" cadena
     */
    fun encodeProvider(config: ProviderConfig): String {
        val json = AppJson.encodeToString(ProviderConfig.serializer(), config)
        val base64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return QR_PREFIX + base64
    }

    /**
     * Generar un mapa de bits de código QR de la configuración del proveedor.
     *
     * @param config Provider Configuración
     * @param size Tamaño del código QR(Píxel,Predeterminado 600)
     * @return Bitmap;Retorno del fracaso null
     */
    fun generateProviderQr(config: ProviderConfig, size: Int = DEFAULT_QR_SIZE): Bitmap? {
        val content = encodeProvider(config)
        return generateQrBitmap(content, size)
    }

    /**
     * Generar código QR Mapa de bits de texto arbitrario.
     *
     * @param content contenido del texto
     * @param size Tamaño del código QR(Píxel)
     * @return Bitmap;Retorno del fracaso null
     */
    fun generateQrBitmap(content: String, size: Int): Bitmap? {
        if (content.isEmpty()) return null
        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            val bitMatrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    pixels[offset + x] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }.also { result ->
                // La persona que llama debe recycle(Para obtener más información, consulte el punto de llamada generateQrBitmap en QrCodeDialogs.)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "No se pudo generar el código QR: ${e.message}", e)
            null
        }
    }

    /**
     * Analice la cadena del código QR en ProviderConfig.
     *
     * @param content Resultados del escaneo del código QR
     * @return ProviderConfig;Devuelto si el formato no coincide o falla el análisis null
     */
    fun parseProviderQr(content: String): ProviderConfig? {
        val trimmed = content.trim()
        if (!trimmed.startsWith(QR_PREFIX)) {
            Logger.w(TAG, "El prefijo del código QR no coincide,Expectativa '$QR_PREFIX'")
            return null
        }
        val base64 = trimmed.removePrefix(QR_PREFIX)
        return try {
            val json = String(Base64.decode(base64, Base64.DEFAULT), Charsets.UTF_8)
            AppJson.decodeFromString(ProviderConfig.serializer(), json)
        } catch (e: Exception) {
            Logger.w(TAG, "No se pudo analizar el código QR del proveedor", e)
            null
        }
    }

    /**
     * Determine si la cadena está en formato de código QR del proveedor.
     */
    fun isProviderQr(content: String): Boolean {
        return content.trim().startsWith(QR_PREFIX)
    }
}
