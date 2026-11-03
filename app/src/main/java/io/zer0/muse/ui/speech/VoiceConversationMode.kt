@file:Suppress("FunctionNaming", "LongMethod")

package io.zer0.muse.ui.speech

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.ui.ChatViewModel
import io.zer0.muse.ui.common.form.MuseSelectionSheet
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.safeFraction
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge

/**
 * Máquina de estado del modo de conversación de voz(ASR Botón + modelo de bucle de reproducción automática TTS).
 *
 * Flujo de estado:
 *  - IDLE → LISTENING:El usuario hace clic en el botón principal.,Iniciar grabación ASR
 *  - LISTENING → THINKING:El usuario vuelve a hacer clic en el botón principal.("Esta frase se acabó"),Saca todo el texto reconocido y envíalo de una vez
 *  - THINKING → SPEAKING:AI Respuesta de transmisión completada,Ajusta automáticamente TtsManager para leer en voz alta
 *  - SPEAKING → LISTENING:TTS Leer en voz alta completado,Reanudar automáticamente la grabación(dialogo continuo)
 *  - cualquier estado → IDLE:El usuario hace clic en el botón cerrar,o interrupción del botón principal
 *
 * Puntos de diseño:
 *  - Depende totalmente del usuario decidir cuándo finaliza esta sección de entrada.:El botón principal es toggle(No sosteniendo),ASR Terminar solo sin someterse
 *  - TTS Debe hacer una pausa mientras se juega ASR(Evite que el micrófono capte el sonido del altavoz y provoque eco)
 *  - Liberar recursos ASR/TTS inmediatamente al salir,Evite grabar o leer en segundo plano
 *  - THINKING / SPEAKING Haga clic en el botón principal para interrumpir el estado actual.,conformarse a"Control con un clic"interacción
 */
enum class VoiceConversationState {
    /** gratis,Espere a que el usuario haga clic en el botón principal para iniciar la conversación. */
    IDLE,

    /** ASR Grabación,Visualización en tiempo real de texto transcrito y formas de onda. */
    LISTENING,

    /** Espere la respuesta de la IA,Mostrar animación de carga. */
    THINKING,

    /** TTS Leer en voz alta,Muestra el texto de respuesta de AI y el progreso de la reproducción. */
    SPEAKING,
}

/**
 * Modo de conversación de voz completa(Cobertura de pantalla completa).
 *
 * llamador(ChatScreen)usar [AnimatedVisibility] libro de paquete Composable,Se muestra cuando el usuario hace clic en la entrada de la conversación de voz.
 * pasar cuando está cerrado [onClose] La devolución de llamada notifica a la persona que llama que se esconde.
 *
 * @param onClose El usuario hace clic en el botón cerrar(X)devolución de llamada,La persona que llama debe ocultar esta página y llamar [ChatViewModel.stopVoiceConversation]
 * @param viewModel charlar ViewModel,Proporcionar flujo de estado y controlar la entrada.
 */
@Composable
fun VoiceConversationMode(onClose: () -> Unit, viewModel: ChatViewModel) {
    val context = LocalContext.current
    val conversationState by viewModel.voiceConversationState.collectAsStateWithLifecycle()
    val transcript by viewModel.voiceConversationTranscript.collectAsStateWithLifecycle()
    val aiReply by viewModel.voiceConversationAiReply.collectAsStateWithLifecycle()
    val hint by viewModel.voiceConversationHint.collectAsStateWithLifecycle()
    val chatState by viewModel.state.collectAsStateWithLifecycle()
    val asrState = chatState.asrState
    // Estado del modo + estado de grabación real:Los dos juntos determinan si el botón principal es el micrófono de audio.(empezar)o parar(finalizar y enviar)
    val isRecording = conversationState == VoiceConversationState.LISTENING && asrState.isRecording
    val playbackState by viewModel.ttsPlaybackStateFlow.collectAsStateWithLifecycle()

    // Cambiar el estado de la hoja inferior de voz
    var showVoicePicker by remember { mutableStateOf(false) }

    // RECORD_AUDIO Solicitud de permiso:Si no estás autorizado al ingresar a la página, aplica,La primera ronda comenzará automáticamente después de la autorización. LISTENING
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.startVoiceConversation()
        } else {
            viewModel.reportError(context.getString(R.string.chat_err_mic_permission))
            onClose()
        }
    }

    // Verificar permisos al ingresar a la página:Autorizado para empezar directamente,Permiso de aplicación no autorizada
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.startVoiceConversation()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // El usuario hace clic para cerrar:Detenga ViewModel primero y luego vuelva a llamar
    val handleClose: () -> Unit = {
        viewModel.stopVoiceConversation()
        onClose()
    }

    // El usuario hace clic en el botón principal.:toggle(No sosteniendo)— LISTENING grabación→Finalizar y enviar,De lo contrario, comience a grabar;Otro estado→interrumpir
    val handleMainButtonClick: () -> Unit = { viewModel.toggleVoiceConversation() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.92f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // ── arriba:botón cerrar ───────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
                horizontalArrangement = Arrangement.End,
            ) {
                MuseTactileButton(
                    icon = MuseIcons.x,
                    onClick = handleClose,
                    contentDescription = stringResource(R.string.voice_conversation_close_cd),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    size = MuseIconSizes.touchTarget,
                    iconSize = MuseIconSizes.icon,
                )
            }

            // ── céntrico:Botón principal + texto de estado ─────────────────────────────────
            VoiceConversationMainButton(
                state = conversationState,
                isRecording = isRecording,
                onClick = handleMainButtonClick,
                modifier = Modifier.padding(vertical = MusePaddings.sectionGap),
            )

            // ── medio a bajo:Texto de aviso de estado + transcripción en tiempo real/AI Responder ───────────────────
            VoiceConversationStatusPanel(
                state = conversationState,
                transcript = transcript,
                aiReply = aiReply,
                hint = hint,
                amplitudes = asrState.amplitudes,
                playback = playbackState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = MusePaddings.screen),
            )

            // ── abajo:Alternar botón de voz ─────────────────────────────────────
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                shape = MuseShapes.pill,
                modifier = Modifier
                    .padding(bottom = MusePaddings.sectionGap)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { showVoicePicker = true },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    Icon(
                        imageVector = MuseIcons.microphone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                    Text(
                        text = stringResource(R.string.voice_conversation_change_voice),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }

    // Cambiar voz Bottom Sheet
    if (showVoicePicker) {
        MuseSelectionSheet(
            title = stringResource(R.string.voice_conversation_pick_voice_title),
            onDismissRequest = { showVoicePicker = false },
        ) {
            VoicePickerContent(
                voices = viewModel.getAvailableTtsVoices(),
                currentVoiceName = viewModel.currentTtsVoiceName(),
                onSelect = { name ->
                    viewModel.setTtsVoice(name)
                    showVoicePicker = false
                },
            )
        }
    }
}

/**
 * Botón principal de conversación de voz(ronda grande,Cambie de íconos y animaciones según el estado del modo + el estado de grabación real).
 *
 * - IDLE:Icono de micrófono,estático
 * - LISTENING Y grabando:Icono de parada + animación de pulso(Haga clic de nuevo = finalizar y enviar)
 * - LISTENING pero no grabado:Icono de micrófono(Haga clic = Empezar a grabar)
 * - THINKING:barra de progreso circular
 * - SPEAKING:Ícono de micrófono + animación de onda
 */
@Composable
private fun VoiceConversationMainButton(
    state: VoiceConversationState,
    isRecording: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val error = MaterialTheme.colorScheme.error

    // animación de pulso(LISTENING Y cuando la grabación está en curso, el botón principal acerca y aleja el zoom.,Simular la sensación de respirar.);reduced-motion Siguiente visualización estática.
    val pulseScale = if (state == VoiceConversationState.LISTENING && isRecording && !MuseMotion.isReducedMotion()) {
        val transition = rememberInfiniteTransition(label = "voiceMainPulse")
        val animatedScale by transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.1f,
            animationSpec = infiniteRepeatable(
                animation = MuseMotion.tween(MuseAnimation.LOOP_SLOW_MS, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "voiceMainPulseScale",
        )
        animatedScale
    } else {
        1f
    }

    // v1.0.92: Comentarios de prensa — El zoom de 0,97 se siente consistente con los botones emergentes
    val mainButtonInteraction = remember { MutableInteractionSource() }
    val isMainPressed by mainButtonInteraction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isMainPressed) 0.96f else 1f,
        animationSpec = MuseMotion.tween(MuseAnimation.FAST_MS),
        label = "voiceMainPress",
    )

    // Color de fondo del botón principal:LISTENING Y utilizado en la grabación. error(rojo,Aviso durante la grabación),Otros usos primary
    val buttonColor = when {
        state == VoiceConversationState.LISTENING && isRecording -> error
        else -> primary
    }

    val stateCd = stateAccessibilityLabel(state, isRecording)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(160.dp)
                .scale(pulseScale * pressScale)
                .clip(CircleShape)
                .background(buttonColor)
                .clickable(
                    interactionSource = mainButtonInteraction,
                    indication = null,
                    onClick = onClick,
                )
                .semantics { contentDescription = stateCd },
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                VoiceConversationState.IDLE -> Icon(
                    imageVector = MuseIcons.microphone,
                    contentDescription = null,
                    tint = onPrimary,
                    modifier = Modifier.size(72.dp),
                )
                VoiceConversationState.LISTENING -> Icon(
                    imageVector = if (isRecording) MuseIcons.stop else MuseIcons.microphone,
                    contentDescription = null,
                    tint = onPrimary,
                    modifier = Modifier.size(72.dp),
                )
                VoiceConversationState.THINKING -> MuseSpinner(
                    size = 72.dp,
                    strokeWidth = 4.dp,
                    color = onPrimary,
                )
                VoiceConversationState.SPEAKING -> Icon(
                    imageVector = MuseIcons.volume,
                    contentDescription = null,
                    tint = onPrimary,
                    modifier = Modifier.size(72.dp),
                )
            }
        }
        Spacer(Modifier.height(MusePaddings.contentGap))
        Text(
            text = stateStatusLabel(state, isRecording),
            style = MaterialTheme.typography.titleMedium,
            color = onPrimary,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * panel de estado:IDLE Mostrar indicaciones,LISTENING Mostrar transcrito,THINKING Mostrar carga,SPEAKING Muestra las respuestas y el progreso de la IA.
 */
@Composable
private fun VoiceConversationStatusPanel(
    state: VoiceConversationState,
    transcript: String,
    aiReply: String,
    hint: String?,
    amplitudes: List<Float>,
    playback: PlaybackState,
    modifier: Modifier = Modifier,
) {
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val onPrimaryMedium = onPrimary.copy(alpha = 0.7f)

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            VoiceConversationState.IDLE -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.voice_conversation_idle_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = onPrimaryMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(MusePaddings.contentGap))
                    Text(
                        text = stringResource(R.string.voice_conversation_idle_tap_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = onPrimaryMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            VoiceConversationState.LISTENING -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!hint.isNullOrBlank()) {
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodyLarge,
                            color = onPrimary,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(MusePaddings.contentGap))
                    }
                    Text(
                        text = stringResource(R.string.voice_conversation_listening),
                        style = MaterialTheme.typography.labelLarge,
                        color = onPrimaryMedium,
                    )
                    Spacer(Modifier.height(MusePaddings.contentGap))
                    Text(
                        text = stringResource(R.string.voice_conversation_listening_finish_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = onPrimaryMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(MusePaddings.contentGap))
                    // Animación de olas(amplitud normalizada → altura de la barra vertical)
                    if (amplitudes.isNotEmpty()) {
                        ListeningWaveform(amplitudes = amplitudes)
                        Spacer(Modifier.height(MusePaddings.contentGap))
                    }
                    if (transcript.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.15f),
                            shape = MuseShapes.semiLarge,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = transcript,
                                style = MaterialTheme.typography.bodyLarge,
                                color = onPrimary,
                                modifier = Modifier.padding(MusePaddings.cardInner),
                                textAlign = TextAlign.Center,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            VoiceConversationState.THINKING -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MuseSpinner(
                        size = 36.dp,
                        strokeWidth = 3.dp,
                        color = onPrimary,
                    )
                    Spacer(Modifier.height(MusePaddings.contentGap))
                    Text(
                        text = stringResource(R.string.voice_conversation_thinking),
                        style = MaterialTheme.typography.bodyLarge,
                        color = onPrimaryMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            VoiceConversationState.SPEAKING -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.voice_conversation_speaking),
                        style = MaterialTheme.typography.labelLarge,
                        color = onPrimaryMedium,
                    )
                    Spacer(Modifier.height(MusePaddings.contentGap))
                    if (aiReply.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.15f),
                            shape = MuseShapes.semiLarge,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = aiReply,
                                style = MaterialTheme.typography.bodyMedium,
                                color = onPrimary,
                                modifier = Modifier.padding(MusePaddings.cardInner),
                                textAlign = TextAlign.Start,
                                maxLines = 12,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    // Progreso de la reproducción(Progreso de fragmentación + tiempo dentro del segmento)
                    if (playback.totalChunks > 0) {
                        Spacer(Modifier.height(MusePaddings.contentGap))
                        val progress = if (playback.durationMs > 0) {
                            (playback.positionMs.toFloat() / playback.durationMs).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        val progressLabel = if (playback.totalChunks > 1) {
                            "${playback.currentChunkIndex + 1}/${playback.totalChunks}"
                        } else {
                            "${(progress * 100).toInt()}%"
                        }
                        Text(
                            text = progressLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = onPrimaryMedium,
                        )
                    }
                }
            }
        }
    }
}

/**
 * LISTENING Animación de forma de onda en estado(Representar el historial de amplitud reciente como barras verticales).
 *
 * La amplitud ya es un valor normalizado de 0-1f.(Reportado por ASR),Asignado directamente a la altura de la barra vertical.
 * Renderizado con translucidez de color primario,Forma un eco visual con el color rojo del botón principal de la grabación.
 */
@Composable
private fun ListeningWaveform(amplitudes: List<Float>) {
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    Row(
        modifier = Modifier.height(28.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        amplitudes.forEach { amp ->
            // defensa contra el colapso: coerceIn Imparable NaN,Usar en su lugar safeFraction(ver FractionSafety.kt)
            val fraction = amp.safeFraction(min = 0.05f, fallback = 0.05f)
            val animatedHeight by animateFloatAsState(
                targetValue = fraction,
                animationSpec = MuseMotion.tween(MuseAnimation.FAST_MS),
                label = "voiceWave",
            )
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight(animatedHeight)
                    .clip(CircleShape)
                    .background(onPrimary.copy(alpha = 0.8f)),
            )
        }
    }
}

/**
 * Cambiar el contenido de la hoja inferior de voz:Listar los sonidos disponibles TTS del sistema,Haga clic para cambiar.
 */
@Composable
private fun VoicePickerContent(voices: List<android.speech.tts.Voice>, currentVoiceName: String, onSelect: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = MusePaddings.sectionGap),
    ) {
        if (voices.isEmpty()) {
            Text(
                text = stringResource(R.string.voice_conversation_no_voices),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = MusePaddings.sectionGap),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
            ) {
                items(voices, key = { it.name }) { voice ->
                    val isSelected = voice.name == currentVoiceName
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(voice.name) }
                            .padding(vertical = MusePaddings.contentGap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = voice.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Text(
                                text = voice.locale?.displayName ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (isSelected) {
                            Icon(
                                imageVector = MuseIcons.check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(MuseIconSizes.iconSmall),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Etiqueta de accesibilidad del botón principal (cambia con el estado de grabación: Grabación = Finalizar y enviar, sin grabación = iniciar la grabación). */
@Composable
private fun stateAccessibilityLabel(state: VoiceConversationState, isRecording: Boolean): String = when (state) {
    VoiceConversationState.IDLE -> stringResource(R.string.voice_conversation_cd_idle)
    VoiceConversationState.LISTENING -> if (isRecording) {
        stringResource(R.string.voice_conversation_cd_listening)
    } else {
        stringResource(R.string.voice_conversation_cd_listening_waiting)
    }
    VoiceConversationState.THINKING -> stringResource(R.string.voice_conversation_cd_thinking)
    VoiceConversationState.SPEAKING -> stringResource(R.string.voice_conversation_cd_speaking)
}

/** Texto de estado debajo del botón principal. */
@Composable
private fun stateStatusLabel(state: VoiceConversationState, isRecording: Boolean): String = when (state) {
    VoiceConversationState.IDLE -> stringResource(R.string.voice_conversation_state_idle)
    VoiceConversationState.LISTENING -> if (isRecording) {
        stringResource(R.string.voice_conversation_state_listening)
    } else {
        stringResource(R.string.voice_conversation_state_listening_idle)
    }
    VoiceConversationState.THINKING -> stringResource(R.string.voice_conversation_state_thinking)
    VoiceConversationState.SPEAKING -> stringResource(R.string.voice_conversation_state_speaking)
}
