package io.zer0.muse.ui.common.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantEntity
import kotlin.math.roundToInt

/**
 * v0.25: Componente de renderizado unificado de avatar asistente.
 *
 * prioridad:① avatar de imagen(avatarImageUrl)→ ② Emoji avatar(avatarEmoji)→ ③ iniciales
 *
 * Uso:`AssistantAvatar(assistant = entity, avatarSize = 40.dp)`
 */
internal fun assistantAvatarImageRequest(context: android.content.Context, imageUrl: String, sizePx: Int): ImageRequest {
    val boundedSize = sizePx.coerceAtLeast(1)
    return ImageRequest.Builder(context)
        .data(imageUrl)
        // Role-card avatars can be multi-megapixel originals. Decode only what
        // the chat/list surface can display, otherwise one avatar can exhaust
        // the small Android heap before Compose reports the allocation failure.
        .size(boundedSize, boundedSize)
        .crossfade(true)
        .build()
}

@Composable
fun AssistantAvatar(
    assistant: AssistantEntity,
    modifier: Modifier = Modifier,
    // L-AA3: Nombre del parámetro size → avatarSize,Evite el sombreado con el mismo nombre que Modifier.size, lo que hace que la persona que llama/Confusión de referencias internas.
    avatarSize: androidx.compose.ui.unit.Dp = 40.dp,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val avatarSizePx = (avatarSize.value * density.density).roundToInt().coerceAtLeast(1)
    // stringResource Debe extraerse en la ubicación de llamada directa de @Composable,no puedo estar ahí semantics{} Para uso interno.
    val avatarCd = stringResource(R.string.common_avatar_cd, assistant.name)
    when {
        // ① avatar de imagen(Puede ser content:// URI o ruta de archivo)
        assistant.hasImageAvatar() -> {
            // v0.36 Optimización del rendimiento:caché ImageRequest,Evite reconstruir el objeto de solicitud Coil en cada reorganización.
            val model = remember(assistant.avatarImageUrl, avatarSizePx) {
                assistantAvatarImageRequest(ctx, assistant.avatarImageUrl, avatarSizePx)
            }
            // M-AA1: Utilice SubcomposeAsyncImage en su lugar AsyncImage,Manejar fallas de carga a través de la ranura de error.
            // La implementación anterior muestra una superficie en blanco. Fondo variante cuando falla la carga.;ahora recurrir a la letra inicial,
            // Asegúrate de que tu avatar siempre tenga contenido identificable(El avatar de contacto no se carga y devuelve la letra inicial).
            SubcomposeAsyncImage(
                model = model,
                contentDescription = assistant.name,
                modifier = modifier
                    .size(avatarSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop,
                loading = {
                    Box(
                        modifier = Modifier
                            .size(avatarSize)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                },
                error = {
                    // Cuando el usuario ha configurado explícitamente una imagen pero el archivo no es válido,Priorizar el recurso a Emoji/letras iniciales,
                    // En lugar de simplemente mostrar el bloque de color del tema,evitar“avatar desaparece”percepción.
                    if (assistant.avatarEmoji.isNotBlank()) {
                        Text(
                            text = assistant.avatarEmoji,
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    } else {
                        Text(
                            text = assistant.name.firstOrNull()?.toString()?.ifBlank { "A" } ?: "A",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }
        // ② Emoji avatar
        assistant.avatarEmoji.isNotBlank() -> {
            Box(
                modifier = modifier
                    .size(avatarSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    // M-AA2: Agregar semántica de descripción de contenido(mergeDescendants Fusión Text),
                    // TalkBack Leer en voz alta como"X avatar",La implementación anterior solo hablaba caracteres Emoji.,La semántica no está clara.
                    .semantics(mergeDescendants = true) {
                        contentDescription = avatarCd
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = assistant.avatarEmoji,
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        }
        // ③ iniciales
        else -> {
            Box(
                modifier = modifier
                    .size(avatarSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    // M-AA2: Agregar semántica de descripción de contenido(mergeDescendants Fusión Text),
                    // TalkBack Leer en voz alta como"X avatar".
                    .semantics(mergeDescendants = true) {
                        contentDescription = avatarCd
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = assistant.name.firstOrNull()?.toString()?.ifBlank { "A" } ?: "A",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
