package io.zer0.muse.ui.account

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import io.zer0.muse.R
import io.zer0.muse.data.AvatarStorage
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.huge
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v2.x: Centro de cuentas — Página de edición de perfil local(Sin iniciar sesión/Registrarse UI).
 *
 * principios de diseño:
 *  - Sin dependencias de servicios en la nube,Todos los datos almacenados localmente(DataStore)
 *  - Los usuarios pueden elegir libremente su avatar(fotos del album)y apodo
 *  - Persistencia de URI de avatar,El apodo vacío vuelve al nombre predeterminado
 *  - Efectivo inmediatamente después de guardar,AccountCard Espere a que la interfaz de usuario que se suscribe a accountStateFlow se actualice automáticamente.
 *
 * UI estructura:
 *  - arriba:MuseTopBar("Editar perfil" + Regresar)
 *  - sujeto:gran avatar(Haga clic para reemplazar) + Mensaje de avatar + cuadro de entrada de apodo + botón Guardar
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    onBack: () -> Unit,
    /** v2.x: Haga clic para ingresar a la configuración migrada"datos personales"(Retrato de usuario)Página. */
    onOpenUserProfile: () -> Unit,
) {
    val settings: SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val accountState by settings.accountStateFlow.collectAsStateWithLifecycle(
        initialValue = io.zer0.muse.data.AccountState(),
    )

    // estado de edición local — Inicializado con el estado de la cuenta actual al ingresar a la página.
    var editName by remember(accountState.userName) { mutableStateOf(accountState.userName) }
    var editAvatarUri by remember(accountState.avatarUri) { mutableStateOf(accountState.avatarUri) }
    var saving by remember { mutableStateOf(false) }
    var avatarSaving by remember { mutableStateOf(false) }

    // selección de avatar launcher — Photo Picker,Seleccionar solo imágenes
    val pickAvatarLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                avatarSaving = true
                try {
                    val persistentUri = AvatarStorage.persist(context, uri.toString())
                    if (persistentUri.isNullOrBlank()) {
                        MuseToast.show(
                            context.getString(R.string.assistant_detail_avatar_save_failed),
                            3000,
                        )
                    } else {
                        // Una vez completada la selección, el pedido se realizará en la información de la cuenta inmediatamente.,Incluso si el usuario abandona la página o el proceso finaliza,
                        // No perderás tu avatar porque no has hecho clic en el botón Guardar en la parte inferior.
                        val profile = settings.getUserProfile()
                        settings.saveUserProfile(profile.copy(avatarUri = persistentUri))
                        editAvatarUri = persistentUri
                    }
                } finally {
                    avatarSaving = false
                }
            }
        }
    }

    val initialName = accountState.userName
    val initialAvatar = accountState.avatarUri
    val hasChanges = editName.trim() != initialName.trim() || editAvatarUri != initialAvatar
    val canSave = hasChanges && editName.isNotBlank() && !saving && !avatarSaving

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.account_edit_profile),
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = MusePaddings.screen),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))

            // ── avatar(Haga clic para reemplazar) ──
            AvatarPicker(
                userName = editName,
                avatarUri = editAvatarUri,
                onClick = {
                    pickAvatarLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
            )
            Text(
                text = stringResource(R.string.account_change_avatar_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))

            // ── Entrada de apodo ──
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.account_name_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                )
                MuseTextField(
                    value = editName,
                    onValueChange = { editName = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            text = stringResource(R.string.account_name_placeholder),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Done,
                    ),
                )
            }

            Spacer(Modifier.weight(1f))

            // ── Entrada de datos personales(Migrar desde la configuración) ──
            UserProfileEntryCard(
                onClick = onOpenUserProfile,
                modifier = Modifier.fillMaxWidth(),
            )

            // ── botón guardar ──
            SaveButton(
                enabled = canSave,
                saving = saving || avatarSaving,
                onClick = {
                    scope.launch {
                        saving = true
                        val profile = settings.getUserProfile()
                        // v1.0.74 fix: El permiso de URI del contenido de Avatar deja de ser válido después de reiniciar(Marcador de posición gris),
                        // Al guardar, cópielo a un directorio privado y guarde la ruta local.
                        val persistentUri = AvatarStorage.persist(context, editAvatarUri)
                        val avatarWasChanged = editAvatarUri != profile.avatarUri
                        if (avatarWasChanged && !editAvatarUri.isNullOrBlank() && persistentUri.isNullOrBlank()) {
                            saving = false
                            MuseToast.show(
                                context.getString(R.string.assistant_detail_avatar_save_failed),
                                3000,
                            )
                            return@launch
                        }
                        settings.saveUserProfile(
                            profile.copy(
                                userNickName = editName.trim(),
                                avatarUri = persistentUri ?: profile.avatarUri,
                            ),
                        )
                        saving = false
                        onBack()
                    }
                },
            )

            Spacer(Modifier.height(20.dp))
        }
    }
}
// ── Componente de selección de avatar ──────────────────────────────────────────

/**
 * v2.x: datos personales(Retrato de usuario)Tarjeta de entrada.
 *
 * Cambiar la página de configuración original"Retrato de usuario/datos personales"Funciones migradas a la página de cuenta,
 * Haga clic para ingresar a la página completa de edición de retratos de usuarios.
 */
@Composable
private fun UserProfileEntryCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MuseIcons.edit,
                        contentDescription = stringResource(R.string.account_user_profile_title),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.account_user_profile_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.account_user_profile_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Logotipo grande de edición de avatar + cámara en la esquina inferior derecha.
 *
 * @param userName Se utiliza para tomar el marcador de posición de la letra inicial cuando no hay avatar.
 * @param avatarUri avatar URI(null Usar marcador de posición de letra inicial)
 * @param onClick Haga clic en el avatar para activar(Abrir álbum de fotos)
 */
@Composable
private fun AvatarPicker(userName: String, avatarUri: String?, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(120.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.BottomEnd,
    ) {
        // sujeto avatar
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(120.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                if (!avatarUri.isNullOrBlank()) {
                    SubcomposeAsyncImage(
                        model = remember(avatarUri) { AvatarStorage.imageModel(avatarUri) },
                        contentDescription = stringResource(R.string.account_avatar),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        error = {
                            Text(
                                text = userName.take(1).ifBlank { "M" },
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        },
                    )
                } else {
                    Text(
                        text = userName.take(1).ifBlank { "M" },
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        // logotipo de edición de cámara(esquina inferior derecha)
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.inverseSurface,
            modifier = Modifier.size(36.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MuseIcons.camera,
                    contentDescription = stringResource(R.string.account_change_avatar),
                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
// ── botón guardar ──────────────────────────────────────────

@Composable
private fun SaveButton(enabled: Boolean, saving: Boolean, onClick: () -> Unit) {
    Surface(
        shape = MuseShapes.huge,
        color = if (enabled) {
            MaterialTheme.colorScheme.inverseSurface
        } else {
            MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.4f)
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            if (saving) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MuseSpinner(
                        size = 20.dp,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = stringResource(R.string.account_loading),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.account_save),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        }
    }
}

// ── Tarjeta de cuenta en la parte superior de la página de configuración ──────────────────────────────────────

/**
 * Tarjeta de cuenta en la parte superior de la página de configuración — Avatar a la izquierda + Apodo a la derecha + "Editar perfil" Consejos.
 *
 * v2.x: Eliminar inicio de sesión/Concepto de registro,Muestra el perfil local ya sea que haya iniciado sesión o no.
 * Haga clic para entrar [AccountScreen] Editar avatar y apodo.
 */
@Composable
fun AccountCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val settings: SettingsRepository = koinInject()
    val accountState by settings.accountStateFlow.collectAsStateWithLifecycle(
        initialValue = io.zer0.muse.data.AccountState(),
    )

    Surface(
        onClick = onClick,
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // avatar(48dp)
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp),
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (!accountState.avatarUri.isNullOrBlank()) {
                        SubcomposeAsyncImage(
                            model = remember(accountState.avatarUri) {
                                AvatarStorage.imageModel(accountState.avatarUri.orEmpty())
                            },
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                            error = {
                                Text(
                                    text = accountState.userName.take(1).ifBlank { "M" },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            },
                        )
                    } else {
                        Text(
                            text = accountState.userName.take(1).ifBlank { "M" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = accountState.userName.ifBlank { stringResource(R.string.account_default_user) },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.account_edit_profile),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
