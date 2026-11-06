package io.zer0.muse.miniphone.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.zer0.muse.miniphone.api.MpProfile
import io.zer0.muse.miniphone.data.db.MINI_PHONE_DB_VERSION
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme

/**
 * mi pagina:Tarjeta de visita del usuario local + entrada + configuración del teléfono celular.
 *
 * El apodo y la firma provienen de [MpProfile];Si falta algún elemento dame una breve explicación,No dejes líneas en blanco.
 * Entrada tres artículos:mi circulo de amigos(Solo mira lo que publicas), borrar el historial de chat(Segunda confirmación), información de la versión.
 * Dos elementos en el área de configuración.:"Enviar automáticamente a Momentos" y "Caracteres de mensajes privados activos",El estado del interruptor lo activa el estado del shell.
 *
 * @param appVersionName Nombre de la versión del host;Una cadena vacía indica que el anfitrión no ha informado,En la información de versión solo se escribe la versión propia del módulo.
 */
@Composable
internal fun MePage(
    profile: MpProfile,
    appVersionName: String = "",
    autoMomentEnabled: Boolean = true,
    chatProactiveEnabled: Boolean = false,
    actions: MePageActions = MePageActions(),
    modifier: Modifier = Modifier,
) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val shape = RoundedCornerShape(dimens.cardRadius)
    // Borrar es una acción que cubre todas las sesiones.,Haga clic para confirmar
    var confirmingClear by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            // Si hay demasiadas tarjetas, no caben en una pantalla.:Desplazable a página completa,dos filas inferiores(claro/versión)no me despedirán
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.pageMargin, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.surface)
                .border(dimens.hairline, colors.divider, shape)
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiniPhoneAvatar(name = profile.nickname, size = dimens.avatarLarge)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = profile.nickname.ifBlank { EMPTY_NICKNAME },
                    style = typography.title.copy(color = colors.textPrimary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = profile.signature.orEmpty().ifBlank { EMPTY_SIGNATURE },
                    style = typography.caption.copy(color = colors.textSecondary),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(SectionGap))
        MeEntryRow(
            title = MY_MOMENTS_TITLE,
            description = MY_MOMENTS_DESCRIPTION,
            tag = MiniPhoneTags.MY_MOMENTS_ROW,
            onClick = actions.onOpenMyMoments,
        )
        Spacer(Modifier.height(SectionGap))
        MeSettingRow(
            title = AUTO_MOMENT_TITLE,
            description = AUTO_MOMENT_DESCRIPTION,
            checked = autoMomentEnabled,
            onCheckedChange = actions.onToggleAutoMoment,
            switchTag = MiniPhoneTags.AUTO_MOMENT_SWITCH,
        )
        Spacer(Modifier.height(SectionGap))
        MeSettingRow(
            title = CHAT_PROACTIVE_TITLE,
            description = CHAT_PROACTIVE_DESCRIPTION,
            checked = chatProactiveEnabled,
            onCheckedChange = actions.onToggleChatProactive,
            switchTag = MiniPhoneTags.CHAT_PROACTIVE_SWITCH,
        )
        Spacer(Modifier.height(SectionGap))
        MeEntryRow(
            title = CLEAR_CHATS_TITLE,
            description = CLEAR_CHATS_DESCRIPTION,
            tag = MiniPhoneTags.CLEAR_CHATS_ROW,
            titleColor = colors.unreadDot,
            onClick = { confirmingClear = true },
        )
        Spacer(Modifier.height(SectionGap))
        MeEntryRow(
            title = VERSION_TITLE,
            description = miniPhoneVersionLabel(dbVersion = MINI_PHONE_DB_VERSION, appVersion = appVersionName),
            tag = MiniPhoneTags.VERSION_ROW,
            labelTag = MiniPhoneTags.VERSION_LABEL,
            onClick = null,
        )
    }
    if (confirmingClear) {
        ClearChatsConfirmDialog(
            onDismiss = { confirmingClear = false },
            onConfirm = {
                actions.onClearConversations()
                confirmingClear = false
            },
        )
    }
}

/**
 * ¿Qué muestra la línea de información de la versión?
 *
 * La versión de la biblioteca propia del módulo está escrita en constantes.,La versión del host es el valor del tiempo de ejecución. —— Ninguna de las partes lo descartará aquí.,
 * Cuando el anfitrión no informe una versión, solo se conservará la primera mitad.,No agregue un número de versión falso.
 */
internal fun miniPhoneVersionLabel(dbVersion: Int, appVersion: String): String {
    val module = "pequeños datos celulares v$dbVersion"
    val app = appVersion.trim()
    return if (app.isEmpty()) module else "$module / Solicitud $app"
}

/** Una línea en el área de configuración.:Título + descripción a la izquierda,Interruptor derecho. Las dos tarjetas de configuración tienen el mismo aspecto,Es solo que la redacción publicitaria es diferente de la devolución de llamada. */
@Composable
private fun MeSettingRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, switchTag: String) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val shape = RoundedCornerShape(dimens.cardRadius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(dimens.hairline, colors.divider, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = typography.title.copy(color = colors.textPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = description,
                style = typography.caption.copy(color = colors.textSecondary),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(switchTag),
        )
    }
}

/**
 * fila de entrada:título + descripción,Una flecha a la derecha apunta a la página siguiente.
 *
 * [onClick] Cuando es nulo, no se puede hacer clic en toda la línea.(La información de la versión es puramente para visualización.),No se dibujan más flechas en el lado derecho. ——
 * Dibujé una flecha pero no se mueve.,Más confuso que no dibujar nada.
 */
@Composable
private fun MeEntryRow(
    title: String,
    description: String,
    tag: String,
    onClick: (() -> Unit)?,
    titleColor: Color? = null,
    labelTag: String? = null,
) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val shape = RoundedCornerShape(dimens.cardRadius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(dimens.hairline, colors.divider, shape)
            .let { base -> if (onClick != null) base.clickable(onClick = onClick) else base }
            .testTag(tag)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = typography.title.copy(color = titleColor ?: colors.textPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = description,
                style = typography.caption.copy(color = colors.textSecondary),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = if (labelTag != null) Modifier.testTag(labelTag) else Modifier,
            )
        }
        if (onClick != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = CHEVRON_TEXT,
                style = typography.body.copy(color = colors.textSecondary),
            )
        }
    }
}

/** Confirmación antes de borrar el historial de chat:Deja en claro qué se eliminó, que no se puede restaurar y que tu círculo de amigos no se verá afectado. */
@Composable
private fun ClearChatsConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val shape = RoundedCornerShape(dimens.cardRadius)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .testTag(MiniPhoneTags.CLEAR_CHATS_DIALOG)
                .width(DialogWidth)
                .clip(shape)
                .background(colors.surface)
                .border(dimens.hairline, colors.divider, shape)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(
                text = CLEAR_CHATS_DIALOG_TITLE,
                style = typography.title.copy(color = colors.textPrimary),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = CLEAR_CHATS_DIALOG_HINT,
                style = typography.caption.copy(color = colors.textSecondary),
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = CANCEL_LABEL,
                    style = typography.body.copy(color = colors.textSecondary),
                    modifier = Modifier
                        .testTag(MiniPhoneTags.CLEAR_CHATS_CANCEL)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onDismiss)
                        .heightIn(min = DialogActionHeight)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = CLEAR_LABEL,
                    style = typography.body.copy(color = colors.unreadDot),
                    modifier = Modifier
                        .testTag(MiniPhoneTags.CLEAR_CHATS_CONFIRM)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onConfirm)
                        .heightIn(min = DialogActionHeight)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private const val EMPTY_NICKNAME: String = "Aún no hay apodo"
private const val EMPTY_SIGNATURE: String = "Esta persona aún no ha escrito su firma."
private const val AUTO_MOMENT_TITLE: String = "Publicar automáticamente en Momentos"
private const val AUTO_MOMENT_DESCRIPTION: String = "Después de desactivarlo, el personaje ya no publicará actualizaciones en segundo plano."
private const val CHAT_PROACTIVE_TITLE: String = "Rol activo mensaje privado"
private const val CHAT_PROACTIVE_DESCRIPTION: String = "El personaje ocasionalmente tomará la iniciativa de hablar contigo, sólo cuando 08:00–22:00 entre, cerrado por defecto"
private const val MY_MOMENTS_TITLE: String = "mi circulo de amigos"
private const val MY_MOMENTS_DESCRIPTION: String = "Mire solo las actualizaciones que ha publicado,También puedes enviarlo más tarde."
private const val CLEAR_CHATS_TITLE: String = "Borrar historial de chat"
private const val CLEAR_CHATS_DESCRIPTION: String = "Eliminar todas las conversaciones y mensajes,Los momentos no se ven afectados."
private const val VERSION_TITLE: String = "Información de versión"
private const val CLEAR_CHATS_DIALOG_TITLE: String = "Borrar historial de chat?"
private const val CLEAR_CHATS_DIALOG_HINT: String = "Se eliminarán todas las conversaciones y mensajes.,No se puede recuperar. Las actualizaciones de Momentos no cambiarán."
private const val CANCEL_LABEL: String = "Cancelar"
private const val CLEAR_LABEL: String = "Borrar"
private const val CHEVRON_TEXT: String = "›"

private val SectionGap = 12.dp
private val DialogWidth = 280.dp
private val DialogActionHeight = 36.dp
