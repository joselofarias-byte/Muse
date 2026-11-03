package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.UserProfile
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v1.133: Reescribir la página de edición de retratos de usuario — Todos los campos están inyectados. system prompt,modelo real"saber"Usuario.
 *
 * objetivos de diseño:
 *  - 5 grupos:Título / Información básica / Antecedentes y experiencia / Preferencias de comunicación / Límites y tabúes
 *  - Agregar texto de descripción para cada campo,Permita que los usuarios comprendan qué completar y el impacto en la IA
 *  - Agregar explicación de InfoCard en la parte superior"La información completada se inyectará en la IA. system prompt,Deja que la IA te conozca mejor"
 *  - B7-09 Guardado antivibración:Actualización sincrónica del estado local.,Vuelva a escribir después de una pausa de 500 ms DataStore,Guardar al salir de la página
 *
 * Mapeo de campo(SystemPromptAssembler.buildUserProfileSection):
 *  - grupo de saludo:userNickName / assistantName
 *  - Información básica:bio / age / city / timezone
 *  - Antecedentes y experiencia:occupation / educationBackground / professionField / skills / interests
 *  - preferencias de comunicación:communicationStyle / responseLength / preferredTone / preferredLanguage
 *  - Límites y tabúes:avoidTopics
 */

/**
 * B7-09: Unidad de disco antivibración para la página de perfil de usuario.
 *
 * UI Todavía impulsado sincrónicamente por mutableStateOf local dentro de la página,Evite que se interrumpa la composición de IME;
 * Este solo es responsable de escribir el perfil más reciente después de una pausa de 500 ms. DataStore,Y guárdelo cuando la página se destruya.
 */
private class UserProfileSaveSink(
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var saveJob: Job? = null
    private var latest: UserProfile = UserProfile()

    fun schedule(value: UserProfile) {
        latest = value
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(500)
            settings.saveUserProfile(value)
        }
    }

    fun dispose() {
        saveJob?.cancel()
        val value = latest
        if (value != UserProfile()) {
            // La página ha salido,No se puede utilizar el nivel de página scope;Utilice un alcance de IO independiente para realizar la escritura final
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                settings.saveUserProfile(value)
            }
        }
        scope.cancel()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileEditPage(onBack: () -> Unit) {
    val settings: SettingsRepository = koinInject()
    val saveSink = remember { UserProfileSaveSink(settings) }

    // v1.0.27 Solucionar error de IME: sincronizar la retención con mutableStateOf local profile,evitar produceState + scope.launch
    // Destrucción asincrónica de ida y vuelta IME composing text (Comentarios de los usuarios"Al escribir una coma, el cursor salta al frente de la coma").
    // antigua implementación:profile Recopilado asincrónicamente de userProfileFlow → update Iniciar lectura de rutina DB → transform → escribir DB →
    //        flow emit → produceState actualizar → TextField value cambiar → IME composing Confusión.
    // nueva implementación:Actualización sincrónica del estado local. → Escritura asincrónica DB;Sincronizar con local cuando cambia el flujo externo(Si el contenido es realmente diferente).
    var profile by remember { mutableStateOf(UserProfile()) }
    LaunchedEffect(Unit) {
        settings.userProfileFlow.collect { incoming ->
            // Sincronizar sólo si el valor de la base de datos no es coherente con el local(Evite la interrupción de la reorganización causada por el eco después de escribir IME)
            if (incoming != profile) profile = incoming
        }
    }

    DisposableEffect(Unit) {
        onDispose { saveSink.dispose() }
    }

    // Actualizar sincrónicamente el estado local + reescritura anti-vibración DataStore
    fun update(transform: (UserProfile) -> UserProfile) {
        val next = transform(profile)
        profile = next // Actualizaciones sincrónicas,IME Ver nuevo ahora value
        saveSink.schedule(next)
    }

    SettingsSubPageScaffold(title = stringResource(R.string.settings_user_profile_title), onBack = onBack) {
        // Arriba InfoCard: Descripción de la información que se inyectará en la IA system prompt
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                Icon(
                    imageVector = MuseIcons.info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.settings_user_profile_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── Grupo 1: Configuración del saludo(máxima prioridad,Impacto en cómo la IA se llama a sí misma y se dirige a los usuarios)──
        item {
            CardGroup(title = { Text(stringResource(R.string.settings_user_profile_appellation)) }) {
                item(
                    headlineContent = {
                        MuseTextField(
                            value = profile.assistantName ?: "",
                            onValueChange = { v -> update { it.copy(assistantName = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_assistant_name)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_assistant_name_hint)) },
                            supportingText = { Text(stringResource(R.string.settings_user_profile_assistant_name_desc)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
                item(
                    headlineContent = {
                        MuseTextField(
                            value = profile.userNickName ?: "",
                            onValueChange = { v -> update { it.copy(userNickName = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_your_name)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_your_name_hint)) },
                            supportingText = { Text(stringResource(R.string.settings_user_profile_your_name_desc)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
            }
        }

        // ── Grupo 2: Información básica(Permitir que la IA tenga una comprensión general de los usuarios)──
        item {
            CardGroup(title = { Text(stringResource(R.string.settings_user_profile_basic_info)) }) {
                // v1.133: Perfil(texto largo,Un párrafo de introducción gratuita.)
                item(
                    headlineContent = {
                        MuseTextField(
                            value = profile.bio ?: "",
                            onValueChange = { v -> update { it.copy(bio = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_bio)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_bio_hint)) },
                            supportingText = { Text(stringResource(R.string.settings_user_profile_bio_desc)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                        )
                    },
                )
                item(
                    headlineContent = {
                        MuseTextField(
                            value = profile.age ?: "",
                            onValueChange = { v -> update { it.copy(age = v.filter { c -> c.isDigit() }.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_age)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
                item(
                    headlineContent = {
                        MuseTextField(
                            value = profile.city ?: "",
                            onValueChange = { v -> update { it.copy(city = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_city)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
                // v1.133: zona horaria
                item(
                    headlineContent = {
                        MuseTextField(
                            value = profile.timezone ?: "",
                            onValueChange = { v -> update { it.copy(timezone = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_timezone)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_timezone_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
            }
        }

        // ── Grupo 3: Antecedentes y experiencia ──
        item {
            CardGroup(title = { Text(stringResource(R.string.settings_user_profile_background)) }) {
                item(
                    headlineContent = {
                        MuseTextField(
                            value = profile.occupation ?: "",
                            onValueChange = { v -> update { it.copy(occupation = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_occupation)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
                // v1.133: Antecedentes educativos
                item(
                    headlineContent = {
                        MuseTextField(
                            value = profile.educationBackground ?: "",
                            onValueChange = { v -> update { it.copy(educationBackground = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_education)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_education_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
                item(
                    headlineContent = {
                        MuseTextField(
                            value = profile.professionField ?: "",
                            onValueChange = { v -> update { it.copy(professionField = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_profession_field)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_profession_field_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
                // v1.133: Experiencia en habilidades
                item(
                    headlineContent = {
                        MuseTextField(
                            value = profile.skills ?: "",
                            onValueChange = { v -> update { it.copy(skills = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_skills)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_skills_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
                item(
                    headlineContent = {
                        MuseTextField(
                            value = profile.interests ?: "",
                            onValueChange = { v -> update { it.copy(interests = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_interests_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                        )
                    },
                )
            }
        }

        // ── Grupo 4: Preferencias de comunicación(Influir en el estilo de respuesta de la IA)──
        item {
            CardGroup(title = { Text(stringResource(R.string.settings_user_profile_communication)) }) {
                // v1.133: estilo de comunicación
                item(
                    headlineContent = {
                        MuseTextField(
                            value = profile.communicationStyle ?: "",
                            onValueChange = { v -> update { it.copy(communicationStyle = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_comm_style)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_comm_style_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
                // v1.133: Preferencia de longitud de respuesta
                item(
                    headlineContent = {
                        MuseTextField(
                            value = profile.responseLength ?: "",
                            onValueChange = { v -> update { it.copy(responseLength = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_resp_length)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_resp_length_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
                // v1.133: tono preferido
                item(
                    headlineContent = {
                        MuseTextField(
                            value = profile.preferredTone ?: "",
                            onValueChange = { v -> update { it.copy(preferredTone = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_pref_tone)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_pref_tone_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
                // v1.133: Idioma de respuesta preferido
                item(
                    headlineContent = {
                        MuseTextField(
                            value = profile.preferredLanguage ?: "",
                            onValueChange = { v -> update { it.copy(preferredLanguage = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_pref_lang)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_pref_lang_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
            }
        }

        // ── Grupo 5: Límites y Tabúes ──
        item {
            CardGroup(title = { Text(stringResource(R.string.settings_user_profile_boundaries)) }) {
                // v1.133: Temas tabú
                item(
                    headlineContent = {
                        MuseTextField(
                            value = profile.avoidTopics ?: "",
                            onValueChange = { v -> update { it.copy(avoidTopics = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_avoid_topics)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_avoid_topics_hint)) },
                            supportingText = { Text(stringResource(R.string.settings_user_profile_avoid_topics_desc)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                        )
                    },
                )
            }
        }

        // Deja la parte inferior en blanco
        item { Spacer(Modifier.height(MusePaddings.screen)) }
    }
}
