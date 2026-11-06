package io.zer0.muse.ui.artifact

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.zer0.muse.R
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.ui.HtmlPreviewScreen
import io.zer0.muse.ui.common.feedback.MuseToast

/**
 * v2.0.1: El producto abre el host. — contenido rico (HTML/SVG/Chart/Mermaid) toma la forma de un navegador de pantalla completa,
 * El resto (código/Texto) visor emergente. La página de chat y el centro de productos comparten el mismo flujo.
 */
@Composable
fun ArtifactOpenHost(artifact: ArtifactEntity?, onDismiss: () -> Unit) {
    if (artifact == null) return
    val context = LocalContext.current
    val richLang = richPreviewLanguage(artifact.type, artifact.language)
    if (richLang != null) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            HtmlPreviewScreen(
                html = artifact.content,
                title = artifact.title.ifBlank { null },
                onBack = onDismiss,
            )
        }
    } else {
        ArtifactViewerDialog(
            artifact = artifact,
            onDismiss = onDismiss,
            onCopy = { text -> copyArtifactText(context, text) },
        )
    }
}

/** Copie el texto del producto al portapapeles + brinde comentarios (página de chat compartida con el centro de productos). */
internal fun copyArtifactText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Muse Artifact", text))
    MuseToast.show(context.getString(R.string.chat_copied_toast))
}
