package io.zer0.muse.miniphone.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.zer0.muse.miniphone.data.voice.MpVoiceCapture
import io.zer0.muse.miniphone.data.voice.MpVoiceRecorder
import io.zer0.muse.miniphone.ui.icons.MiniPhoneIcons
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme

/**
 * Campo de entrada inferior:Entrada de imagen + micrófono + entrada multilínea + envío.
 *
 * El cuadro de entrada puede tener hasta cuatro líneas de largo.,Si hay más, simplemente desplácese dentro del cuadro. —— La página de chat no se puede llenar con mensajes a media pantalla con una frase larga.
 * Cuando la entrada está vacía, el botón de enviar solo tiene una forma y se niega a ser presionado.:La devolución de llamada al hacer clic no se bloquea en absoluto,En lugar de presionarlo y no hacer nada.
 * La selección de imágenes en sí no se realiza en este componente.:Levante el selector del sistema al canal de resultados de actividad,Déjelo en manos de los superiores que lo tienen.
 *
 * La voz es un proceso único con puntos finales manuales:Haga clic en el micrófono para comenzar a grabar.,Haga clic en "Finalizar" nuevamente para detener y transcribir.,
 * El texto que aparece solo se agrega al final del cuadro de entrada.,No enviar automáticamente —— Si cometes un error, aún puedes corregirlo.,Una vez que lo envíes, no podrás retirarlo.
 * Durante la grabación, el cuadro de entrada se reemplaza con un mensaje de grabación.:Graba y escribe al mismo tiempo,Nadie puede decir dónde insertar ese párrafo después de la grabación.
 *
 * @param onTranscribeVoice Envíe el WAV completo al host para su transcripción;Volver cuando no se puede obtener el texto null
 * @param voiceInputEnabled ¿Puede el motor de reconocimiento actual convertir grabaciones en texto?;false El micrófono está atenuado.
 */
@Composable
internal fun ChatInputBar(
    onSend: (String) -> Unit,
    onPickImages: () -> Unit,
    modifier: Modifier = Modifier,
    onTranscribeVoice: suspend (ByteArray) -> String? = { null },
    voiceInputEnabled: Boolean = true,
) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    val canSend = text.isNotBlank()
    val submit = {
        if (canSend) {
            onSend(text)
            text = ""
        }
    }
    val recorder = remember(context) { MpVoiceRecorder(context.cacheDir) }
    val voice = rememberVoiceInput(
        recorder = recorder,
        onTranscribeVoice = onTranscribeVoice,
        voiceInputEnabled = voiceInputEnabled,
        onTranscript = { transcript -> text = appendTranscript(text, transcript) },
        onNotice = { notice -> context.toast(notice) },
    )
    val voiceState by voice.state.collectAsState()
    val askMic = rememberMicPermissionRequest(
        onGranted = voice::start,
        onDenied = { context.toast(MIC_PERMISSION_NOTICE) },
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(dimens.hairline)
                .background(colors.divider),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dimens.pageMargin, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            PickImageButton(onClick = onPickImages)
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(InputShape)
                    .background(colors.background),
            ) {
                when (val current = voiceState) {
                    is MpVoiceInputState.Recording -> RecordingStrip(
                        elapsedSeconds = current.elapsedSeconds,
                        onCancel = voice::cancel,
                    )
                    MpVoiceInputState.Transcribing -> TranscribingStrip()
                    MpVoiceInputState.Idle -> BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = typography.body.copy(color = colors.textPrimary),
                        cursorBrush = SolidColor(colors.accent),
                        maxLines = INPUT_MAX_LINES,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .testTag(MiniPhoneChatTags.INPUT_FIELD),
                        decorationBox = { innerTextField ->
                            if (text.isEmpty()) {
                                Text(
                                    text = INPUT_PLACEHOLDER,
                                    style = typography.body.copy(color = colors.textSecondary),
                                )
                            }
                            innerTextField()
                        },
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            MicButton(
                state = voiceState,
                enabled = voiceInputEnabled,
                onClick = {
                    when {
                        // El motor no admite entrada de grabación.:La clave es gris.,Solo explica una frase,No abra el cuadro de permiso y no inicie la recopilación.
                        !voiceInputEnabled -> context.toast(VOICE_UNSUPPORTED_NOTICE)
                        voiceState is MpVoiceInputState.Recording -> voice.stop()
                        voiceState == MpVoiceInputState.Transcribing -> Unit
                        else -> askMic()
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            SendButton(enabled = canSend && voiceState == MpVoiceInputState.Idle, onClick = submit)
        }
    }
}

/**
 * Pasar una grabación a la máquina de estados.,Y conecta el asunto de "la página ya no está":Deje de minar y elimine archivos temporales al salir de la página.
 *
 * Las tres devoluciones de llamada se recrearán en la composición.,Entonces tome el último valor y páselo a la máquina de estados. —— De lo contrario, la máquina de estados captura
 * Cuando se combina por primera vez `text`,El resultado del reconocimiento se escribirá en una línea de texto que haya sido modificado en otro lugar.
 */
@Composable
private fun rememberVoiceInput(
    recorder: MpVoiceCapture,
    onTranscribeVoice: suspend (ByteArray) -> String?,
    voiceInputEnabled: Boolean,
    onTranscript: (String) -> Unit,
    onNotice: (String) -> Unit,
): MpVoiceInputController {
    val scope = rememberCoroutineScope()
    val currentTranscribe = rememberUpdatedState(onTranscribeVoice)
    val currentTranscript = rememberUpdatedState(onTranscript)
    val currentNotice = rememberUpdatedState(onNotice)
    val currentEnabled = rememberUpdatedState(voiceInputEnabled)
    val controller = remember(recorder) {
        MpVoiceInputController(
            recorder = recorder,
            transcribe = { wav -> currentTranscribe.value(wav) },
            onTranscript = { transcript -> currentTranscript.value(transcript) },
            onEmptyTranscript = { currentNotice.value(EMPTY_TRANSCRIPT_NOTICE) },
            onStartFailed = { currentNotice.value(START_FAILED_NOTICE) },
            canRecord = { currentEnabled.value },
            scope = scope,
        )
    }
    DisposableEffect(controller) {
        onDispose { controller.release() }
    }
    return controller
}

/**
 * Permisos de micrófono:Si no te han enseñado, solo pregunta una vez.,Después de rechazar, solo indica que el cuadro de diálogo del sistema no volverá a aparecer.
 *
 * Reabrir el mismo cuadro de permisos una y otra vez es inútil y molesto. —— El usuario ha expresado su opinión.,Preguntar nuevamente solo bloqueará el campo de entrada.
 * El usuario luego lo libera en la configuración del sistema.,La próxima vez que hagas clic en el micrófono, la grabación comenzará directamente.
 */
@Composable
private fun rememberMicPermissionRequest(onGranted: () -> Unit, onDenied: () -> Unit): () -> Unit {
    val context = LocalContext.current
    var deniedOnce by rememberSaveable { mutableStateOf(false) }
    val currentGranted = rememberUpdatedState(onGranted)
    val currentDenied = rememberUpdatedState(onDenied)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            currentGranted.value()
        } else {
            deniedOnce = true
            currentDenied.value()
        }
    }
    return {
        when {
            context.hasRecordPermission() -> currentGranted.value()
            deniedOnce -> currentDenied.value()
            else -> launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

/** Entrada de imagen:Icono de álbum de fotos dibujado por uno mismo,Mismo tamaño que el botón enviar,Alinee los bordes inferiores. */
@Composable
private fun PickImageButton(onClick: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    Box(
        modifier = Modifier
            .size(SendButtonSize)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag(MiniPhoneChatTags.PICK_IMAGE),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MiniPhoneIcons.Image,
            contentDescription = PICK_IMAGE_DESCRIPTION,
            tint = colors.textSecondary,
            modifier = Modifier.size(SendIconSize),
        )
    }
}

/**
 * Botón de micrófono:inactivo es icono,Cambia a "Fin" durante la grabación,La transcripción está atenuada.
 *
 * Cambia las teclas en la grabación a cápsulas en lugar de seguir sacando el micrófono —— Coloque un micrófono en la misma posición y agregue "Fin",
 * El usuario tiene que pensar “qué pasará si lo presiono ahora” antes de atreverse a presionarlo.
 * Si el motor no admite la entrada de grabación, aparecerá atenuado.,Pero aún así vale la pena:Haga clic para dar una explicación.
 */
@Composable
private fun MicButton(state: MpVoiceInputState, enabled: Boolean, onClick: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    when {
        state is MpVoiceInputState.Recording -> Box(
            modifier = Modifier
                .height(SendButtonSize)
                .clip(CircleShape)
                .background(colors.accent)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp)
                .testTag(MiniPhoneChatTags.MIC_BUTTON),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = STOP_LABEL, style = typography.caption.copy(color = colors.onAccent))
        }
        state == MpVoiceInputState.Transcribing -> Box(
            modifier = Modifier
                .size(SendButtonSize)
                .clip(CircleShape)
                .semantics { disabled() }
                .testTag(MiniPhoneChatTags.MIC_BUTTON),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MiniPhoneIcons.Mic,
                contentDescription = MIC_DESCRIPTION,
                tint = colors.divider,
                modifier = Modifier.size(SendIconSize),
            )
        }
        else -> Box(
            modifier = Modifier
                .size(SendButtonSize)
                .clip(CircleShape)
                .clickable(onClick = onClick)
                .testTag(MiniPhoneChatTags.MIC_BUTTON),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MiniPhoneIcons.Mic,
                contentDescription = MIC_DESCRIPTION,
                tint = if (enabled) colors.textSecondary else colors.divider,
                modifier = Modifier.size(SendIconSize),
            )
        }
    }
}

/** Grabación:Un punto rojo dice \"Reclutamiento\",La cantidad de segundos grabados se informará como \"¿Cuánto tiempo lleva grabado?\",A la derecha está la cancelación. */
@Composable
private fun RecordingStrip(elapsedSeconds: Int, onCancel: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(RecordingDotSize)
                .clip(CircleShape)
                .background(colors.unreadDot),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = voiceElapsedLabel(elapsedSeconds),
            style = typography.body.copy(color = colors.textPrimary),
            modifier = Modifier.testTag(MiniPhoneChatTags.RECORDING_SECONDS),
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = CANCEL_LABEL,
            style = typography.caption.copy(color = colors.textSecondary),
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onCancel)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag(MiniPhoneChatTags.CANCEL_RECORDING),
        )
    }
}

/** Transcribiendo:Explica dónde fue esta grabación.,No deje los campos de entrada vacíos. */
@Composable
private fun TranscribingStrip() {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = TRANSCRIBING_LABEL,
            style = typography.body.copy(color = colors.textSecondary),
            modifier = Modifier.testTag(MiniPhoneChatTags.VOICE_STATUS),
        )
    }
}

/** Enviar clave:Ocupando siempre el puesto,Cambie solo el color de fondo y el estado en el que se puede hacer clic,Evite que el campo de entrada tiemble con el texto. */
@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val press = if (enabled) Modifier.clickable(onClick = onClick) else Modifier.semantics { disabled() }
    Box(
        modifier = Modifier
            .size(SendButtonSize)
            .clip(CircleShape)
            .background(if (enabled) colors.accent else colors.divider)
            .then(press)
            .testTag(MiniPhoneChatTags.SEND_BUTTON),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MiniPhoneIcons.Send,
            contentDescription = SEND_DESCRIPTION,
            tint = if (enabled) colors.onAccent else colors.textSecondary,
            modifier = Modifier.size(SendIconSize),
        )
    }
}

private fun Context.hasRecordPermission(): Boolean =
    checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

/** Agregue el resultado del reconocimiento al final del cuadro de entrada;Si ya hay contenido en el cuadro, sepárelo con un espacio.,No cubre la mitad de las palabras escritas por el usuario. */
internal fun appendTranscript(current: String, addition: String): String {
    val extra = addition.trim()
    if (extra.isEmpty()) return current
    return if (current.isBlank()) extra else current.trimEnd() + " " + extra
}

private val InputShape = RoundedCornerShape(8.dp)
private val SendButtonSize = 38.dp
private val SendIconSize = 18.dp
private val RecordingDotSize = 8.dp

/** El cuadro de entrada puede tener hasta cuatro líneas de largo.,Si se alarga, desplácese dentro del cuadro. */
private const val INPUT_MAX_LINES: Int = 4

private const val INPUT_PLACEHOLDER: String = "di algo…"
private const val SEND_DESCRIPTION: String = "enviar"
private const val PICK_IMAGE_DESCRIPTION: String = "Seleccionar imagen"
private const val MIC_DESCRIPTION: String = "hacer una voz"
private const val STOP_LABEL: String = "fin"
private const val CANCEL_LABEL: String = "Cancelar"
private const val TRANSCRIBING_LABEL: String = "Transcribiendo…"
private const val EMPTY_TRANSCRIPT_NOTICE: String = "No te escuché claramente. Dilo de nuevo."
private const val START_FAILED_NOTICE: String = "Error al iniciar la grabación, verifique si el micrófono está ocupado por otras aplicaciones"
private const val MIC_PERMISSION_NOTICE: String = "Se requiere permiso de micrófono para hablar."
private const val VOICE_UNSUPPORTED_NOTICE: String = "El motor de reconocimiento de voz actual no admite entradas de grabación. Vaya a la configuración para seleccionar un motor que admita la transcripción."
