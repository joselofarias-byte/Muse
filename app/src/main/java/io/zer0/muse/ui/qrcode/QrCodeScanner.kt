package io.zer0.muse.ui.qrcode

import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import io.zer0.common.Logger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * v1.97: Escáner de código QR — Utilice el escaneo de códigos de barras del kit ML para identificar códigos QR a partir de imágenes.
 *
 * Después de que el usuario selecciona una imagen del álbum,Utilice este escáner para identificar el contenido del código QR.
 * Admite formato QR_CODE,La cadena original se devuelve después del reconocimiento.
 *
 * Uso:
 * ```
 * val content = QrCodeScanner.scanFromBitmap(bitmap)
 * if (content != null && QrCodeGenerator.isProviderQr(content)) {
 *     val provider = QrCodeGenerator.parseProviderQr(content)
 * }
 * ```
 */
object QrCodeScanner {

    private const val TAG = "QrCodeScanner"

    /**
     * Escanee el código QR de Bitmap.
     *
     * @param bitmap Imágenes para ser reconocidas
     * @return El primer contenido de código QR reconocido;Sin resultado de reconocimiento o no devolución null
     */
    suspend fun scanFromBitmap(bitmap: Bitmap): String? {
        val image = InputImage.fromBitmap(bitmap, 0)
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        val scanner = BarcodeScanning.getClient(options)
        return suspendCancellableCoroutine { cont ->
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    val content = barcodes.firstOrNull()?.rawValue
                    scanner.close()
                    cont.resume(content)
                }
                .addOnFailureListener { e ->
                    Logger.w(TAG, "Error al escanear el código QR: ${e.message}")
                    scanner.close()
                    cont.resume(null)
                }
        }
    }
}
