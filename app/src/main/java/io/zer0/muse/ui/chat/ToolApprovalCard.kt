package io.zer0.muse.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.tools.ToolApprovalPolicy
import io.zer0.muse.ui.PendingToolApproval
import io.zer0.muse.ui.SmartImage
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tool approval card.
 *
 * Shown when a tool call requires user approval before execution.
 * Displays tool name, argument preview, and approve/deny buttons.
 *
 * v1.0.20: Nuevo"siempre permitido"/"siempre rechace"botón — Haga clic para pasar [onPersistPolicy]
 * persistir en [io.zer0.muse.tools.ToolConfigStore],Las llamadas posteriores a esta herramienta seguirán directamente la estrategia correspondiente.,
 * No más tarjetas de aprobación.
 *
 * v1.x: Nuevo"Esta sesión permite"botón — entre"Aprobar esto"(soltero)con"siempre permitido"(duradero)El término medio entre.
 * Haga clic para pasar [onAllowThisSession] agregar herramientas [io.zer0.muse.tools.SessionPermissionStore]
 * almacenamiento en caché de permisos temporales a nivel de sesión(pura memoria,No persistente),Esta herramienta ya no mostrará la tarjeta de aprobación en esta sesión.,
 * Cambiar de sesión/Falla automáticamente después del arranque en frío. disparar simultáneamente [onApprove] Maneja esta llamada.
 *
 * v1.x: Herramientas que admiten imágenes de referencia como generate_image,Renderizar en tarjeta"Seleccionar imagen de referencia"botón —
 * El usuario selecciona una imagen local del álbum y luego la transfiere. data URI,Pase [onReferenceImageChange] escribir
 * [io.zer0.muse.ui.PendingToolApproval.referenceImageOverride];Aprobado por
 * ChatViewModel inyectar [io.zer0.muse.tools.ToolApprovalState.Approved.argOverrides],
 * ToolOrchestrator Fusionado en los parámetros de ejecución de la herramienta.LLM No se puede acceder al álbum de fotos local del usuario,
 * Por lo tanto, las imágenes de referencia para los dibujos se proporcionan principalmente desde esta entrada.
 *
 * v1.0.48: UI Refactorizar — Operación principal compacta de una sola fila(aprobar/rechazar/Más)+ Contraer operaciones secundarias,
 * Reemplace el diseño abultado original de tres filas de botones + casillas de verificación dobles;La combinación de colores cambió de tertiaryContainer a
 * surface + primary color de acento,La visión es más refrescante.
 */
@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
@Composable
fun ToolApprovalCard(
    toolName: String,
    argumentsPreview: String,
    /**
     * Parámetros completos(La misma copia utilizada para revisión y aprobación.).
     *
     * [argumentsPreview] Sólo 200 caracteres,Al escribir texto de 4 KB, el usuario solo ve el comienzo;
     * Los parámetros completos se toman desde aquí.,En consecuencia, la tarjeta indica "No se muestran N caracteres" y admite la expansión para su visualización.
     * Cuando está vacío, se degrada a la vista previa únicamente.
     */
    argumentsFull: Map<String, Any?> = emptyMap(),
    onApprove: () -> Unit,
    onDeny: (reason: String) -> Unit,
    /**
     * v1.0.20: Devolución de llamada persistente de estrategia de herramienta única.
     *
     * "siempre permitido"Pasado cuando se hace clic en el botón [ToolApprovalPolicy.ALWAYS_ALLOW],
     * La persona que llama debe estar dentro de esta devolución de llamada.:
     *  1. llamar [io.zer0.muse.tools.ToolConfigStore.setPolicy] estrategia de persistencia
     *  2. Activa sincrónicamente onApprove/onDeny para manejar esta llamada
     */
    onPersistPolicy: (ToolApprovalPolicy) -> Unit = {},
    /**
     * v1.x: "Esta sesión permite"Devolución de llamada del botón — Agregue herramientas al nivel de sesión para permitir temporalmente el almacenamiento en caché.
     *
     * La persona que llama debe llamar dentro de esta devolución de llamada. [io.zer0.muse.tools.SessionPermissionStore.allowToolForSession]
     * Agregue el nombre de la herramienta a la memoria caché de la sesión actual;También se activa internamente mediante el botón. [onApprove] Maneja esta llamada.
     * Esta herramienta ya no mostrará la tarjeta de aprobación en esta sesión.,Cambiar de sesión/Falla automáticamente después del arranque en frío.
     */
    onAllowThisSession: () -> Unit = {},
    /**
     * v1.x: La imagen de referencia seleccionada por el usuario en la tarjeta de aprobación.(data URI,Como por ejemplo "data:image/jpeg;base64,...").
     * Mostrar vista previa en miniatura cuando no esté vacía;null Indica no seleccionado.
     */
    referenceImageOverride: String? = null,
    /**
     * v1.x: Consulte la devolución de llamada de cambio de selección de imagen.dataUri No vacío significa que el usuario seleccionó una nueva imagen.;null Indica claro.
     */
    onReferenceImageChange: (String?) -> Unit = {},
    /**
     * Approval deadline shown as a countdown. Must match the VM-side approval timeout so the
     * card cannot silently expire while the user still believes it is still waiting.
     */
    countdownSeconds: Int = DEFAULT_APPROVAL_COUNTDOWN_SECONDS,
    /**
     * CHAT-08: Si la cuenta regresiva está en pausa(doblado en "N El período de lectura del "Artículo pendiente de aprobación" es true).
     * La cuenta regresiva de la interfaz de usuario se congela cuando se pausa,Y la persona que llama pausa el tiempo de espera del lado VM sincrónicamente,Evite la desincronización de UI/VM.
     */
    countdownPaused: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var showDenyReason by remember { mutableStateOf(false) }
    var denyReason by remember { mutableStateOf("") }
    // Bandera de lectura de imagen de referencia.(Evite que los botones no respondan cuando las imágenes grandes bloqueen el hilo principal)
    var isLoadingRefImage by remember { mutableStateOf(false) }
    // v1.x: Estado plegado de operación secundaria(Permitido para esta sesión/Siempre permitido)
    var showMoreOptions by remember { mutableStateOf(false) }
    // Cuenta regresiva del tiempo de espera de aprobación:Alineado con el tiempo de espera de 30 segundos de VM,Después de la puesta a cero, la tarjeta será rechazada por el lado VM.
    // CHAT-08: countdownPaused congelación de la cuenta regresiva del tiempo(Doblar durante la lectura),Continuar después de la recuperación.
    var remainingSeconds by remember(toolName, countdownSeconds) {
        mutableStateOf(countdownSeconds.coerceAtLeast(0))
    }
    LaunchedEffect(toolName, countdownSeconds, countdownPaused) {
        while (remainingSeconds > 0) {
            if (countdownPaused) {
                kotlinx.coroutines.delay(500)
            } else {
                kotlinx.coroutines.delay(1_000)
                remainingSeconds -= 1
            }
        }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // v1.x: Selector de álbumes,Habilitado solo para herramientas que admiten dibujos de referencia.
    val supportsReferenceImage = toolName in REFERENCE_IMAGE_TOOL_NAMES
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        isLoadingRefImage = true
        scope.launch {
            // Lectura de imágenes grandes + codificación Base64 movida al hilo IO,evitar el bloqueo UI
            val dataUri = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = context.contentResolver
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    require(bytes != null && bytes.size <= MAX_REF_IMAGE_BYTES) {
                        context.getString(R.string.chat_image_too_large)
                    }
                    // inferencia mime;PickVisualMedia El URI de contenido devuelto generalmente tiene type
                    val mime = resolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: "image/png"
                    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    "data:$mime;base64,$base64"
                }
            }
            isLoadingRefImage = false
            dataUri.onSuccess { onReferenceImageChange(it) }
                .onFailure { e ->
                    MuseToast.show(context.getString(R.string.chat_ref_image_load_failed, e.message ?: ""))
                }
        }
    }

    // Phase 2: Unifique las primitivas del contenedor del proyecto. [MuseSurface],Mantenga las esquinas redondeadas de la Tarjeta original.(medium),
    // surface color de fondo,0dp Sombra con borde primario de 1dp,No cambia el tamaño visible.
    MuseSurface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surface,
        // CHAT-09: Estándares unificados para tarjetas dentro de mensajes. — Color base + trazo 1dp + esquinas redondeadas uniformes(Frontera del 45% anteriormente primaria)
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Header: Logotipo del icono de herramienta(Utilice iconos semánticos) + Título/Nombre de la herramienta
            val visualIcon = remember(toolName) { ToolCallVisuals.iconFor(toolName) }
            // I18N-01: Etiquetar recursos para caminar
            val displayName = remember(toolName) { ToolCallVisuals.labelFor(toolName, context.resources) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = visualIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                }
                Column {
                    Text(
                        text = stringResource(R.string.tool_approval_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.weight(1f))
                // Cuenta atrás y aviso de riesgo:La aprobación no esperará para siempre(30s rechazado automáticamente después).
                // CHAT-08: "Pausado" se muestra durante el período de pausa.,No más cuentas regresivas.
                if (countdownPaused) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = MuseIcons.pause,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.tool_approval_countdown_paused),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.tool_approval_countdown, remainingSeconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (remainingSeconds <= 10) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            // v2.x: Visualización de parámetros — Vista previa + mensaje de truncamiento claro + se puede expandir para ver la cantidad total.
            // El importe total se utiliza para revisión y evaluación. args,Pero las tarjetas solían obtener solo 200 vistas previas de personajes.:Al escribir texto de 4 KB
            // El usuario solo vio el comienzo y hizo clic en aprobar. Además de la vista previa, debes indicar claramente cuántos caracteres no se muestran.,
            // Y coloque los parámetros completos detrás de la entrada de expansión.(Después de la expansión, presione la tecla para bifurcarse en filas y desplazarse dentro del cuadro.).
            val argumentEntries = remember(argumentsPreview, argumentsFull) {
                approvalArgumentEntries(argumentsPreview, argumentsFull)
            }
            val rawArguments = remember(argumentEntries) {
                approvalRawJson(argumentEntries) ?: argumentsPreview
            }
            val approvalFilePath = remember(argumentEntries) { approvalValueOf(argumentEntries, APPROVAL_FILE_KEYS) }
            val approvalContent = remember(argumentEntries) { approvalValueOf(argumentEntries, APPROVAL_CONTENT_KEYS) }
            val extraArgs = remember(argumentEntries) { approvalExtraArguments(argumentEntries) }
            // resumen estructurado(Archivo/contenido)Hay dos secciones "Parámetros restantes" y "Detalles avanzados" solo cuando existe.
            val hasStructuredSummary = approvalFilePath != null || approvalContent != null
            var showRawDetails by remember(argumentsPreview) { mutableStateOf(false) }
            var showFullArguments by remember(argumentsPreview) { mutableStateOf(false) }
            val previewScroll = rememberScrollState()
            val rawScroll = rememberScrollState()

            // El texto de vista previa y "cuántos caracteres se omitieron" utilizan el mismo conjunto de números,Evite indicaciones y vistas previas, cada una de las cuales tiene algo que decir.
            val previewTotalChars = approvalContent?.length ?: rawArguments.length
            val previewText = when {
                approvalContent != null ->
                    if (showFullArguments) approvalContent else approvalContent.take(APPROVAL_CONTENT_PREVIEW_CHARS)
                showFullArguments -> rawArguments
                else -> argumentsPreview
            }
            val hiddenChars = hiddenCharacters(previewTotalChars, previewText.length)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (approvalFilePath != null) {
                    Text(
                        text = stringResource(
                            R.string.tool_approval_write_summary,
                            approvalFilePath.substringAfterLast('/'),
                            approvalContent?.length ?: 0,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (previewText.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MuseShapes.small)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .then(
                                if (showFullArguments) {
                                    Modifier.heightIn(max = APPROVAL_EXPANDED_MAX_HEIGHT).verticalScroll(previewScroll)
                                } else {
                                    Modifier
                                },
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = previewText,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = if (approvalContent != null) FontFamily.Default else FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (showFullArguments) {
                                Int.MAX_VALUE
                            } else if (approvalContent != null) {
                                APPROVAL_CONTENT_PREVIEW_LINES
                            } else {
                                APPROVAL_RAW_PREVIEW_LINES
                            },
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // Truncar mensajes y expandir entradas:No le digas al usuario "Truncado aquí",Ampliar la entrada equivale a no hacerlo.
                if (hiddenChars > 0) {
                    ApprovalArgumentsToggle(
                        label = stringResource(R.string.tool_approval_show_full_args) + " · " +
                            stringResource(R.string.tool_approval_args_hidden, hiddenChars),
                        expanded = false,
                        highlighted = true,
                        onClick = { showFullArguments = true },
                    )
                } else if (showFullArguments) {
                    ApprovalArgumentsToggle(
                        label = stringResource(R.string.tool_approval_hide_full_args),
                        expanded = true,
                        highlighted = true,
                        onClick = { showFullArguments = false },
                    )
                }
                // Los argumentos escalares restantes se enumeran k:v (hasta 6 líneas)
                extraArgs.forEach { (k, v) ->
                    Text(
                        text = "$k: $v",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Detalles avanzados:original completo JSON(Desplazable dentro del marco)
                if (hasStructuredSummary) {
                    ApprovalArgumentsToggle(
                        label = stringResource(R.string.tool_approval_advanced_details),
                        expanded = showRawDetails,
                        highlighted = false,
                        onClick = { showRawDetails = !showRawDetails },
                    )
                    if (showRawDetails) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MuseShapes.small)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                .heightIn(max = APPROVAL_EXPANDED_MAX_HEIGHT)
                                .verticalScroll(rawScroll)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = rawArguments,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // v1.x: Área de selección de imágenes de referencia(Solo se muestran herramientas que admiten imágenes de referencia como generate_image)
            // LLM No se puede acceder al álbum de fotos local del usuario,La imagen de referencia de la imagen original la proporciona esta entrada.
            if (supportsReferenceImage) {
                ReferenceImageSection(
                    referenceImageOverride = referenceImageOverride,
                    isLoading = isLoadingRefImage,
                    onPick = {
                        runCatching {
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        }.onFailure {
                            MuseToast.show(context.getString(R.string.chat_ref_image_load_failed, it.message ?: ""))
                        }
                    },
                    onClear = { onReferenceImageChange(null) },
                )
            }

            // Línea operativa principal:aprobar(enfatizar) + Negar y más(Contraer operaciones secundarias)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // aprobar — botón principal,máxima prioridad,Utilice relleno para dar énfasis
                MuseCapsuleButton(
                    text = stringResource(R.string.tool_approval_approve),
                    onClick = onApprove,
                    leadingIcon = MuseIcons.check,
                    fillWidth = false,
                )

                // rechazar — secundario,OutlinedButton
                MuseCapsuleButton(
                    text = if (showDenyReason) {
                        stringResource(R.string.tool_approval_confirm_deny)
                    } else {
                        stringResource(R.string.tool_approval_deny)
                    },
                    onClick = {
                        if (showDenyReason) {
                            onDeny(denyReason)
                        } else {
                            showDenyReason = true
                        }
                    },
                    variant = IosCapsuleButtonVariant.Secondary,
                    leadingIcon = MuseIcons.x,
                    fillWidth = false,
                )

                Spacer(Modifier.width(2.dp))

                // Más — doblar/Ampliar las operaciones secundarias
                MuseCapsuleButton(
                    text = stringResource(R.string.action_more),
                    onClick = { showMoreOptions = !showMoreOptions },
                    variant = IosCapsuleButtonVariant.Text,
                    fillWidth = false,
                )
            }

            // Cuadro de entrada del motivo del rechazo(Haga clic para rechazar y expandir)
            if (showDenyReason) {
                MuseTextField(
                    value = denyReason,
                    onValueChange = { denyReason = it },
                    label = { Text(stringResource(R.string.tool_approval_deny_reason)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // operaciones secundarias(área de plegado):Permitido para esta sesión/Siempre permitido
            AnimatedVisibility(
                visible = showMoreOptions,
                enter = MuseMotion.expandFadeEnter(),
                exit = MuseMotion.expandFadeExit(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MuseCapsuleButton(
                            text = stringResource(R.string.tool_approval_allow_this_session),
                            onClick = {
                                // Agregue herramientas al nivel de sesión para permitir temporalmente el almacenamiento en caché,y activar esta aprobación
                                onAllowThisSession()
                                onApprove()
                            },
                            variant = IosCapsuleButtonVariant.Secondary,
                            leadingIcon = MuseIcons.bookmark,
                            fillWidth = false,
                        )

                        MuseCapsuleButton(
                            text = stringResource(R.string.tool_approval_always_approve),
                            onClick = {
                                // Política ALWAYS_ALLOW persistente,y activar esta aprobación
                                onPersistPolicy(ToolApprovalPolicy.ALWAYS_ALLOW)
                                onApprove()
                            },
                            variant = IosCapsuleButtonVariant.Secondary,
                            leadingIcon = MuseIcons.shieldCheck,
                            fillWidth = false,
                        )
                    }
                }
            }
        }
    }
}

/**
 * CHAT-08: Varias tarjetas de aprobación se pliegan en "N Barra de resumen "Artículo pendiente de aprobación",Evite que se acumulen varias tarjetas y anulen el campo de entrada.
 *
 * El estado colapsado solo ocupa una línea.;Renderizar uno por uno después de la expansión. [ToolApprovalCard]. doblar(leer)Cada tarjeta durante el período.
 * Cuenta regresiva pausada([countdownPaused]),Funciona con suspensión lateral VM,Evite que la aprobación sea rechazada automáticamente en 30 segundos cuando los usuarios la lean.
 * El estado expandido lo mantiene la persona que llama.(Vinculado a la suspensión de VM).
 */
@Composable
fun PendingApprovalsSummary(
    approvals: List<PendingToolApproval>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onApprove: (PendingToolApproval) -> Unit,
    onDeny: (PendingToolApproval, String) -> Unit,
    onPersistPolicy: (PendingToolApproval, ToolApprovalPolicy) -> Unit,
    onAllowThisSession: (PendingToolApproval) -> Unit,
    onReferenceImageChange: (PendingToolApproval, String?) -> Unit,
    countdownPaused: Boolean,
    modifier: Modifier = Modifier,
) {
    // I18N-01: Etiquetar recursos para caminar
    val resources = LocalContext.current.resources
    // lambda No se puede hacer referencia directa al contexto componible dentro,La lista de etiquetas se calcula previamente en un ámbito componible
    val pendingLabels = approvals.joinToString(", ") { ToolCallVisuals.labelFor(it.toolName, resources) }
    Column(modifier = modifier.fillMaxWidth()) {
        MuseSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            shape = MuseShapes.medium,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpanded() }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MuseIcons.shieldCheck,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.tool_approval_pending_n, approvals.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = pendingLabels,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (countdownPaused) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = MuseIcons.pause,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.tool_approval_countdown_paused),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
                    contentDescription = stringResource(
                        if (expanded) R.string.action_collapse else R.string.action_expand,
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
            }
        }
        if (expanded) {
            approvals.forEach { approval ->
                ToolApprovalCard(
                    toolName = approval.toolName,
                    argumentsPreview = approval.argumentsPreview,
                    argumentsFull = approval.argumentsFull,
                    onApprove = { onApprove(approval) },
                    onDeny = { reason -> onDeny(approval, reason) },
                    onPersistPolicy = { policy -> onPersistPolicy(approval, policy) },
                    onAllowThisSession = { onAllowThisSession(approval) },
                    referenceImageOverride = approval.referenceImageOverride,
                    onReferenceImageChange = { dataUri -> onReferenceImageChange(approval, dataUri) },
                    countdownPaused = countdownPaused,
                )
            }
        }
    }
}

/**
 * v1.x: El área de selección de dibujo de referencia en la tarjeta de aprobación de herramienta.
 *
 * tres estados:
 *  - Imágenes no seleccionadas:mostrar"+ Imagen de referencia"botón,Haga clic en el disparador PickVisualMedia
 *  - leyendo:Mostrar indicador de progreso
 *  - Imagen seleccionada:Mostrar vista previa en miniatura(Con botón claro)
 */
@Composable
private fun ReferenceImageSection(referenceImageOverride: String?, isLoading: Boolean, onPick: () -> Unit, onClear: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.chat_ref_image_cd),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            isLoading -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MuseSpinner(
                        size = MuseIconSizes.iconSmall,
                    )
                    Text(
                        text = stringResource(R.string.chat_ref_image_cd),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            referenceImageOverride.isNullOrBlank() -> {
                MuseCapsuleButton(
                    text = stringResource(R.string.chat_ref_image_add),
                    onClick = onPick,
                    variant = IosCapsuleButtonVariant.Secondary,
                    leadingIcon = MuseIcons.image,
                    fillWidth = false,
                )
            }
            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 120.dp)
                        .clip(MuseShapes.small),
                ) {
                    SmartImage(
                        model = referenceImageOverride,
                        contentDescription = stringResource(R.string.chat_ref_image_cd),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    MuseTactileButton(
                        icon = MuseIcons.x,
                        onClick = onClear,
                        contentDescription = stringResource(R.string.tool_approval_deny),
                        tint = MaterialTheme.colorScheme.onSurface,
                        size = MuseIconSizes.touchTarget,
                        iconSize = MuseIconSizes.iconSmall,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .background(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                shape = CircleShape,
                            ),
                    )
                }
            }
        }
    }
}

/**
 * v1.x: Admite la selección de la colección de nombres de herramientas de dibujos de referencia locales en la tarjeta de aprobación(Consistente con ChatViewModel).
 * El nombre sólo se utiliza en este documento.,Por tanto privado.
 */
private val REFERENCE_IMAGE_TOOL_NAMES: Set<String> = setOf("generate_image")

/**
 * Los segundos de cuenta atrás se derivan de la constante de tiempo de espera del orquestador de aprobación.(ChatToolApprovalCoordinator.TOOL_APPROVAL_TIMEOUT_MS),
 * No guarde una copia separada del número aquí: Al escribir dos copias cada una, Cuando la interfaz llega al segundo 31, el fondo ya lo ha rechazado.,
 * Esos 30 segundos que ve el usuario son falsos.
 */
private val DEFAULT_APPROVAL_COUNTDOWN_SECONDS: Int = (ChatToolApprovalCoordinator.TOOL_APPROVAL_TIMEOUT_MS / 1_000L).toInt()

/** Tamaño máximo de imagen de referencia 5MB(InputBar.ImageGenParamsPanel Este valor también se utiliza en). */
private const val MAX_REF_IMAGE_BYTES = 5L * 1024 * 1024

/** Caracteres de vista previa de contenido:Límite superior al plegar(200 La vista previa JSON no puede contener un párrafo de texto,Relajemos esta sección por separado.). */
private const val APPROVAL_CONTENT_PREVIEW_CHARS = 300

/** Número de líneas de vista previa de contenido:El límite superior cuando está plegado. */
private const val APPROVAL_CONTENT_PREVIEW_LINES = 8

/** Recuento de filas de vista previa JSON sin formato:El límite superior cuando está plegado. */
private const val APPROVAL_RAW_PREVIEW_LINES = 6

/** La altura máxima del cuadro de parámetros expandido.:Desplácese dentro del cuadro después de exceder,No empuja toda la tarjeta fuera de la pantalla. */
private val APPROVAL_EXPANDED_MAX_HEIGHT = 320.dp

/** La clave de parámetro se muestra como el \"archivo de destino\". */
private val APPROVAL_FILE_KEYS = listOf("path", "file_path", "filepath", "filename", "file")

/** Clave de parámetro mostrada como \"texto de contenido\". */
private val APPROVAL_CONTENT_KEYS = listOf("content", "text", "data", "message", "query", "url", "command")

/**
 * Número de caracteres que aún no se muestran fuera de la vista previa;<= 0 Indica que no hay truncamiento.
 *
 * La copia rápida y la vista previa comparten el mismo conjunto de números,No puede mostrar los primeros 300 caracteres y afirmar que los ha mostrado todos.
 */
internal fun hiddenCharacters(total: Int, shown: Int): Int = (total - shown).coerceAtLeast(0)

/**
 * Entrada de parámetros:Utilice la cantidad total primero args(Estructurado sin perder jerarquía),Si falta, recurra a la vista previa de los resultados del análisis JSON.
 *
 * JSON a menudo no se puede analizar cuando se trunca la vista previa(cola faltante),Esto es lo que los parámetros de gran volumen sólo podían observar en el pasado.
 * El motivo del personaje de escape original.;"Archivo + número de caracteres + texto" sólo se puede proporcionar cuando todos los parámetros están presentes.
 */
internal fun approvalArgumentEntries(preview: String, full: Map<String, Any?>): List<Pair<String, String>> {
    if (full.isNotEmpty()) {
        return full.entries.map { (key, value) -> key to (value?.toString() ?: "") }
    }
    val obj = runCatching { org.json.JSONObject(preview) }.getOrNull() ?: return emptyList()
    return obj.keys().asSequence().map { key -> key to obj.optString(key) }.toList()
}

/** Las entradas están escritas para que sean legibles. JSON;Devuelve cuando no hay entradas. null(La persona que llama vuelve a la vista previa original.). */
internal fun approvalRawJson(entries: List<Pair<String, String>>): String? {
    if (entries.isEmpty()) return null
    val obj = org.json.JSONObject()
    entries.forEach { (key, value) -> runCatching { obj.put(key, value) } }
    return obj.toString(2)
}

/** Obtenga el primer valor no nulo según la prioridad clave. */
internal fun approvalValueOf(entries: List<Pair<String, String>>, keys: List<String>): String? =
    keys.firstNotNullOfOrNull { key ->
        entries.firstOrNull { it.first == key }?.second?.takeIf { it.isNotBlank() && it != "null" }
    }

/** Parámetros restantes además del resumen(Máximo 6,Los valores individuales que superen los 100 caracteres se truncarán primero.,Vea la vista ampliada para conocer el valor completo). */
internal fun approvalExtraArguments(entries: List<Pair<String, String>>): List<Pair<String, String>> {
    val summaryKeys = (APPROVAL_FILE_KEYS + APPROVAL_CONTENT_KEYS).toSet()
    return entries
        .filterNot { it.first in summaryKeys }
        .filter { it.second.isNotBlank() && it.second != "null" }
        .take(6)
        .map { (key, value) -> key to (if (value.length > 100) value.take(100) + "…" else value) }
}

/**
 * Aprobación de ampliación de tarjeta/cerrar la entrada:chevron + redacción,Se puede hacer clic en toda la fila.
 *
 * [highlighted] Se utiliza para truncar indicaciones y expandir entradas.(primary Color,Dígale al usuario que hay algo que no ha visto aquí.),
 * Utilice colores débiles para puntos secundarios como "Detalles avanzados".
 */
@Composable
private fun ApprovalArgumentsToggle(
    label: String,
    expanded: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = if (expanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
            contentDescription = null,
            modifier = Modifier.size(MuseIconSizes.iconSmall),
            tint = tint,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}
