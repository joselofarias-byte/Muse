package io.zer0.muse.ui.qrcode

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.imageLoader
import coil.request.ImageRequest
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.icons.MuseIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v1.97: Ventana emergente para compartir códigos QR — Mostrar el código QR de configuración del Proveedor.
 *
 * Generar código QR Bitmap y mostrarlo en el centro,usuario rápido"contiene apiKey,Por favor comparte con precaución".
 *
 * @param content Cadena de código QR codificada(Generado por QrCodeGenerator.encodeProvider)
 * @param onDismiss Cerrar devolución de llamada
 */
@Composable
fun QrCodeShareDialog(content: String, onDismiss: () -> Unit) {
    var bitmap by remember(content) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(content) {
        bitmap = withContext(Dispatchers.Default) {
            QrCodeGenerator.generateQrBitmap(content, QrCodeGenerator.DEFAULT_QR_SIZE)
        }
    }
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.qr_share_title),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = stringResource(R.string.qr_share_cd),
                        modifier = Modifier.size(280.dp),
                    )
                } else {
                    Icon(
                        imageVector = MuseIcons.qrcode,
                        contentDescription = null,
                        modifier = Modifier.size(280.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
                Text(
                    text = stringResource(R.string.qr_share_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmText = stringResource(R.string.assistant_detail_done),
        onConfirm = onDismiss,
    )
}

/**
 * v1.97: Ventana emergente de escaneo de código QR — Seleccione una imagen del álbum para identificar el código QR.
 *
 * Llame al selector de imágenes del sistema,Después de seleccionar, use ML Kit para identificar el código QR.,
 * Devolución de llamada a onResult cuando tenga éxito y en formato de proveedor.
 *
 * @param onDismiss Cerrar devolución de llamada
 * @param onResult Devolución de llamada de escaneo exitoso,Devuelve el contenido original analizado del código QR
 */
@Composable
fun QrCodeScanDialog(onDismiss: () -> Unit, onResult: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) {
            onDismiss()
            return@rememberLauncherForActivityResult
        }
        scanning = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // v1.114: Utilice el singleton global de ImageLoader(coil.imageLoader atributos extendidos),
                    // Reemplazar cada vez new ImageLoader(context),Evite pasar por alto la memoria/Caché de disco.
                    // Añadir al mismo tiempo .size(1024) restricción,Evite la decodificación de imágenes grandes en resolución completa(Sólo 1024px son suficientes para escanear el código QR).
                    val bmp = context.imageLoader.execute(
                        ImageRequest.Builder(context).data(uri).size(1024).build(),
                    ).drawable?.toBitmap()
                    if (bmp != null) QrCodeScanner.scanFromBitmap(bmp) else null
                }.getOrNull()
            }
            scanning = false
            if (result != null) {
                onResult(result)
            } else {
                MuseToast.show(context.getString(R.string.qr_scan_failed))
                onDismiss()
            }
        }
    }

    LaunchedEffect(Unit) {
        pickImage.launch("image/*")
    }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.qr_scan_title),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = MuseIcons.photoLibrary,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = if (scanning) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                )
                Text(
                    text = if (scanning) {
                        stringResource(R.string.qr_scan_scanning)
                    } else {
                        stringResource(R.string.qr_scan_pick_image)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmText = stringResource(R.string.qr_scan_pick_button),
        onConfirm = { pickImage.launch("image/*") },
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
    )
}

/**coil de Drawable.toBitmap() Cambiado en bobina3 API,Aquí se utiliza escritura compatible.*/
private fun android.graphics.drawable.Drawable.toBitmap(): Bitmap {
    return if (this is android.graphics.drawable.BitmapDrawable) {
        bitmap
    } else {
        val width = if (intrinsicWidth > 0) intrinsicWidth else 1
        val height = if (intrinsicHeight > 0) intrinsicHeight else 1
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        bmp
    }
}
