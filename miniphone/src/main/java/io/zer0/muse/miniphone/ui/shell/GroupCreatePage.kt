package io.zer0.muse.miniphone.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.data.chat.MIN_GROUP_MEMBERS
import io.zer0.muse.miniphone.data.chat.canCreateGroup
import io.zer0.muse.miniphone.ui.icons.MiniPhoneIcons
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme

/**
 * Iniciar una página de chat grupal:enumerar todos los roles,Sólo cuando dos personas hayan sido enganchadas serán liberadas.
 *
 * Utilice la libreta de direcciones directamente para agrupar y ordenar [MiniPhoneContactSection](agrupación inicial,`#` Refinamiento),
 * Organizar otro conjunto aquí sólo hará que "la misma persona aparezca en diferentes posiciones en las dos páginas".
 *
 * Todo el estado viene de afuera.:Colección seleccionada con dos devoluciones de llamada. La página en sí no conoce la biblioteca.,Tampoco se construye grupo ——
 * Construir un grupo es [MiniPhoneShellViewModel] cosa,La única responsabilidad aquí es entregar "quien ha sido elegido".
 *
 * @param selectedIds Rol seleccionado id;La orden no participa en la sentencia.,El orden de creación del grupo está determinado por el orden de los clics.(ver concha)
 * @param onToggle Haga clic en una línea:Si no eliges, elígelo.,Cancelar una vez seleccionado
 * @param onDone Haga clic en "Listo";No hay suficientes opciones [MIN_GROUP_MEMBERS] No se puede presionar en este momento.,Entonces no vendrá aquí
 */
@Composable
internal fun GroupCreatePage(
    sections: List<MiniPhoneContactSection>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiniPhoneTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(MiniPhoneTags.GROUP_CREATE_PAGE)
            .background(colors.background),
    ) {
        GroupPickHeader(
            title = GROUP_CREATE_TITLE,
            hint = selectedHintOf(selectedIds.size),
            canFinish = canCreateGroup(selectedIds.size),
            tagPrefix = GROUP_CREATE_TAG_PREFIX,
            onBack = onBack,
            onDone = onDone,
        )
        GroupPickList(
            sections = sections,
            selectedIds = selectedIds,
            emptyTitle = EMPTY_CONTACTS_TITLE,
            emptyHint = EMPTY_CONTACTS_HINT,
            onToggle = onToggle,
        )
    }
}

/**
 * La barra superior de la página de selección.:devolución, título, finalización;En la línea debajo del título, escribe cuántas personas han sido seleccionadas.
 *
 * [tagPrefix] Derivar tres etiquetas de prueba(Devolución/Completa/Número de personas),Cada una de las dos páginas de selección tiene su propio prefijo.,
 * De un vistazo queda claro en qué página se hizo clic cuando se "completa" el punto de prueba.
 */
@Composable
internal fun GroupPickHeader(title: String, hint: String, canFinish: Boolean, tagPrefix: String, onBack: () -> Unit, onDone: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    Column(Modifier.fillMaxWidth().background(colors.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(dimens.pageHeaderHeight)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(GroupHeaderButtonSize)
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
                    .testTag("$tagPrefix-back"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MiniPhoneIcons.Back,
                    contentDescription = "Regresar",
                    tint = colors.textPrimary,
                    modifier = Modifier.size(GroupHeaderIconSize),
                )
            }
            Text(
                text = title,
                style = typography.title.copy(color = colors.textPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
            )
            Text(
                text = GROUP_PICK_DONE_LABEL,
                style = typography.caption.copy(
                    color = if (canFinish) colors.accent else colors.textSecondary,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(enabled = canFinish, onClick = onDone)
                    .testTag("$tagPrefix-done")
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        Text(
            text = hint,
            style = typography.timestamp.copy(color = colors.textSecondary),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("$tagPrefix-count")
                .padding(horizontal = dimens.pageMargin, vertical = 6.dp),
        )
        Box(Modifier.fillMaxWidth().height(dimens.hairline).background(colors.divider))
    }
}

/**
 * Lista agrupada de roles opcionales:Agrupación de la primera letra, fila completa en la que se puede hacer clic, selección del círculo derecho;Nadie tiene tiempo para dar el estado vacío.
 *
 * Crea un grupo y agrega miembros para compartir esta copia. —— Por tanto, el orden, agrupación y estilo de selección vistos por las dos entradas son completamente coherentes.
 *
 * @param emptyTitle Título cuando no hay caracteres opcionales
 * @param emptyHint Descripción de segunda línea en la misma pantalla.
 */
@Composable
internal fun GroupPickList(
    sections: List<MiniPhoneContactSection>,
    selectedIds: Set<String>,
    emptyTitle: String,
    emptyHint: String,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sections.isEmpty()) {
        GroupPickEmptyState(title = emptyTitle, hint = emptyHint, modifier = modifier)
        return
    }
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(bottom = 12.dp)) {
        sections.forEach { section ->
            item(key = "group-header-${section.key}") {
                GroupPickSectionHeader(key = section.key)
            }
            items(items = section.contacts, key = { "group-pick-${it.id}" }) { character ->
                GroupPickRow(
                    character = character,
                    selected = character.id in selectedIds,
                    onClick = { onToggle(character.id) },
                )
            }
        }
    }
}

/** Título del grupo:El mismo conjunto de letras pequeñas que la libreta de direcciones. */
@Composable
private fun GroupPickSectionHeader(key: String) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    Text(
        text = key,
        style = typography.timestamp.copy(color = colors.textSecondary, fontWeight = FontWeight.Medium),
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background)
            .padding(start = dimens.pageMargin, end = dimens.pageMargin, top = 10.dp, bottom = 4.dp),
    )
}

/** Una fila de roles opcionales:Avatar + nombre + círculo de selección. Se puede hacer clic en toda la línea,No es necesario que hagas clic en ese círculo. */
@Composable
private fun GroupPickRow(character: MpCharacter, selected: Boolean, onClick: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MiniPhoneTags.groupPick(character.id))
            .clickable(onClick = onClick)
            .padding(horizontal = dimens.pageMargin, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiniPhoneAvatar(name = character.name, size = dimens.avatarList)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = character.name,
                style = typography.body.copy(color = colors.textPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val tagline = character.tagline.orEmpty()
            if (tagline.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = tagline,
                    style = typography.caption.copy(color = colors.textSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        SelectionMark(selected = selected, characterId = character.id)
    }
}

/** círculo de selección:Cuando esté seleccionado, complete el color de acento y coloque una marca de verificación,Si no se selecciona, sólo se dibujará un borde claro. */
@Composable
private fun SelectionMark(selected: Boolean, characterId: String) {
    val colors = MiniPhoneTheme.colors
    val shape = CircleShape
    Box(
        modifier = Modifier
            .size(SelectionMarkSize)
            .clip(shape)
            .background(if (selected) colors.accent else colors.background)
            .border(1.dp, if (selected) colors.accent else colors.divider, shape)
            .testTag(MiniPhoneTags.groupPickMark(characterId)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = MiniPhoneIcons.Check,
                contentDescription = null,
                tint = colors.onAccent,
                modifier = Modifier.size(SelectionCheckSize),
            )
        }
    }
}

/** Ni un solo carácter opcional:explica por qué,En lugar de dejarlo en blanco. */
@Composable
private fun GroupPickEmptyState(title: String, hint: String, modifier: Modifier = Modifier) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(MiniPhoneTags.EMPTY_GROUP_CREATE)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = title, style = typography.title.copy(color = colors.textPrimary))
        Spacer(Modifier.height(6.dp))
        Text(text = hint, style = typography.caption.copy(color = colors.textSecondary))
    }
}

/** La fila de personas seleccionadas.:Cuando sean menos de dos personas, por cierto, explica el umbral. */
internal fun selectedHintOf(selectedCount: Int): String = if (selectedCount < MIN_GROUP_MEMBERS) {
    "Seleccionado $selectedCount gente,Elige al menos $MIN_GROUP_MEMBERS gente"
} else {
    "Seleccionado $selectedCount gente"
}

private const val GROUP_CREATE_TITLE: String = "Iniciar un chat grupal"
private const val GROUP_PICK_DONE_LABEL: String = "Completo"
private const val EMPTY_CONTACTS_TITLE: String = "La libreta de direcciones todavía está vacía."
private const val EMPTY_CONTACTS_HINT: String = "Primero hay roles,Sólo puedo permitirme un grupo"

/** Prefijo de etiqueta para la página de creación de grupos:Los valores derivados son las etiquetas acordadas externamente. */
private const val GROUP_CREATE_TAG_PREFIX: String = "mp-group-create"

private val GroupHeaderButtonSize = 40.dp
private val GroupHeaderIconSize = 20.dp
private val SelectionMarkSize = 22.dp
private val SelectionCheckSize = 14.dp
