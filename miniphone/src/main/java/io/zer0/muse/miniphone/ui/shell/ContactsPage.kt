package io.zer0.muse.miniphone.ui.shell

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlinx.coroutines.launch

/**
 * Página de la libreta de direcciones:Agrupar por letra inicial,Índice alfabético a la derecha.
 *
 * La barra de índice salta directamente según la posición del título del grupo en la lista.(Sin techo de letras),La posición de salto es la primera línea del grupo.,
 * Con un nivel menos de estado, no existe el "clic pero no movido".
 *
 * @param onOpenContact Devolución de llamada al hacer clic en una línea determinada:Los parámetros son para el rol. id,El shell decide a qué sesión ingresar
 */
@Composable
internal fun ContactsPage(sections: List<MiniPhoneContactSection>, modifier: Modifier = Modifier, onOpenContact: (String) -> Unit = {}) {
    if (sections.isEmpty()) {
        ContactsEmptyState(modifier)
        return
    }
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    val headerIndices = remember(sections) { contactHeaderIndices(sections) }
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(end = IndexRailWidth),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            sections.forEach { section ->
                item(key = "header-${section.key}") {
                    ContactHeaderRow(key = section.key)
                }
                items(items = section.contacts, key = { "person-${it.id}" }) { character ->
                    ContactPersonRow(character = character, onClick = { onOpenContact(character.id) })
                }
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(IndexRailWidth)
                .testTag(MiniPhoneTags.CONTACT_INDEX),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            sections.forEach { section ->
                Text(
                    text = section.key,
                    style = typography.timestamp.copy(color = colors.accent, fontWeight = FontWeight.Medium),
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable {
                            scrollScope.launch { listState.scrollToItem(headerIndices[section.key] ?: 0) }
                        }
                        .padding(horizontal = 5.dp, vertical = 3.dp)
                        .testTag(MiniPhoneTags.contactIndex(section.key)),
                )
            }
        }
    }
}

/** Título del grupo:una línea de letra pequeña,El color de fondo es el mismo que el de la página.,No robes los nombres de las personas. */
@Composable
private fun ContactHeaderRow(key: String) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    Text(
        text = key,
        style = typography.timestamp.copy(color = colors.textSecondary, fontWeight = FontWeight.Medium),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MiniPhoneTags.contactHeader(key))
            .background(colors.background)
            .padding(start = dimens.pageMargin, end = dimens.pageMargin, top = 10.dp, bottom = 4.dp),
    )
}

/** Una línea en la libreta de direcciones:avatar + nombre,Cuando haya una oración sobre diseño de personajes, agregue una línea de caracteres pequeños. Comienza a chatear con solo un clic. */
@Composable
private fun ContactPersonRow(character: MpCharacter, onClick: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MiniPhoneTags.contact(character.id))
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
    }
}

/** Libreta de direcciones vacía. */
@Composable
private fun ContactsEmptyState(modifier: Modifier = Modifier) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(MiniPhoneTags.EMPTY_CONTACTS)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "La libreta de direcciones todavía está vacía.",
            style = typography.title.copy(color = colors.textPrimary),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Los contactos de su teléfono se pondrán en cola aquí por letra inicial.",
            style = typography.caption.copy(color = colors.textSecondary),
        )
    }
}

/** El subíndice del título de cada grupo en LazyColumn.:El título ocupa una línea.,Seguido por miembros del grupo. */
internal fun contactHeaderIndices(sections: List<MiniPhoneContactSection>): Map<String, Int> {
    val indices = mutableMapOf<String, Int>()
    var cursor = 0
    sections.forEach { section ->
        indices[section.key] = cursor
        cursor += 1 + section.contacts.size
    }
    return indices
}

private val IndexRailWidth = 22.dp
