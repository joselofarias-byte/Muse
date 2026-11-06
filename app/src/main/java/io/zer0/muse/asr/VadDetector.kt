package io.zer0.muse.asr

import android.os.SystemClock

/**
 * umbral de energía simple VAD(Voice Activity Detection)Detector de silencio.
 *
 * Principio:
 *  - Calcule la energía RMS de PCM para cada cuadro(normalización 0-1f,Reutilizar [AudioAmplitude.calculateRmsAmplitude])
 *  - inferior a [threshold] Tratar como marco silencioso,marco de habla considerado arriba
 *  - La duración acumulada de fotogramas silenciosos supera [silenceDurationMs] tiempo,[processFrame] Regresar true(Activar descarga/parada de capa superior)
 *  - Restablecer el temporizador de silencio cuando se detecta un cuadro de voz
 *
 * Propósito:
 *  - en lotes segmentados ASR(Whisper/Step/DashScope)Simular la segmentación de oraciones en:Súper umbral silencioso -> gatillo flush
 *  - En ASR en tiempo real:Disparador local opcional stop(El VAD del lado del servidor está listo para manejar,Generalmente no hay locales)
 *
 * Uso:
 * ```
 * val vad = VadDetector(threshold = 0.05f, silenceDurationMs = 1500L)
 * while (recording) {
 *     val read = recorder.read(buf, 0, buf.size)
 *     val amp = AudioAmplitude.calculateRmsAmplitude(buf, read)
 *     if (vad.processFrame(buf, read, amp)) {
 *         // Súper umbral silencioso,Activar descarga o parada
 *     }
 * }
 * ```
 *
 * Nota:Esta implementación es un método simple de umbral de energía.,No apto para entornos ruidosos complejos. Para más preciso VAD,puede ser considerado
 * WebRTC VAD o modelo Silero VAD(Necesidad de introducir una biblioteca nativa o ONNX Runtime).
 *
 * @param threshold Umbral de juicio de silencio(normalización RMS 0-1f,Predeterminado 0.05f ≈ -46dB)
 * @param silenceDurationMs La duración acumulada del disparo automático silencioso.(milisegundos,Predeterminado 1500ms)
 * @param minSpeechDurationMs Duración mínima de la voz(milisegundos,Predeterminado 300ms);
 *   Los segmentos de voz más cortos que este valor se consideran pulsos de ruido.,No restablecer el temporizador silencioso(Evite el ruido a corto plazo que interrumpa la acumulación de silencio.)
 */
class VadDetector(
    private val threshold: Float = DEFAULT_THRESHOLD,
    private val silenceDurationMs: Long = DEFAULT_SILENCE_DURATION_MS,
    private val minSpeechDurationMs: Long = DEFAULT_MIN_SPEECH_DURATION_MS,
) {
    /** Si está actualmente silenciado. */
    @Volatile private var inSilence: Boolean = true

    /** Iniciado por silencio elapsedRealtime(Grabar cuando está silenciado). */
    @Volatile private var silenceStartMs: Long = 0L

    /** El comienzo del segmento de discurso actual. elapsedRealtime(Se graba cuando aparece por primera vez el cuadro de voz.). */
    @Volatile private var speechStartMs: Long = 0L

    /** ¿Se ha reiniciado desde el último disparo?(Evite el disparo continuo). */
    @Volatile private var triggered: Boolean = false

    /**
     * Procesar un fotograma de audio PCM,Devuelve si se activa el evento de tiempo de espera de silencio.
     *
     * @param buffer PCM 16-bit bytes(Este método no recalcula RMS,Usar directamente [amplitude] Añadir ginseng)
     * @param readBytes Número real de bytes leídos(Este método no utiliza,Reserva de parámetros para una fácil expansión)
     * @param amplitude Amplitud RMS normalizada del cuadro actual(0-1f,por [AudioAmplitude.calculateRmsAmplitude] calcular)
     * @return true Indica que el silencio ha durado más de [silenceDurationMs],La capa superior debe activar el lavado/parada.;
     *         El mismo silencio sólo se activa una vez,Los fotogramas silenciosos posteriores no se devolverán repetidamente. true,hasta que se detecte un nuevo segmento de voz
     */
    fun processFrame(buffer: ByteArray, readBytes: Int, amplitude: Float): Boolean {
        val now = SystemClock.elapsedRealtime()
        val isSilent = amplitude < threshold

        if (isSilent) {
            if (!inSilence) {
                // Voz -> transición muda:Comienza el silencio de grabación
                inSilence = true
                silenceStartMs = now
                triggered = false
            }
            // silencio total:Se activa una vez cuando se excede el umbral por primera vez.
            if (!triggered && (now - silenceStartMs) >= silenceDurationMs) {
                triggered = true
                return true
            }
        } else {
            // marco de discurso
            if (inSilence) {
                // silenciar -> conversión de voz:Empezar a grabar voz
                inSilence = false
                speechStartMs = now
            }
            // La voz se considerará válida sólo si dura más de minSpeechDurationMs(Evite los pulsos de ruido restableciendo los indicadores de activación)
            if (triggered && (now - speechStartMs) >= minSpeechDurationMs) {
                triggered = false
            }
        }
        return false
    }

    /** restablecer estado(la próxima vez start() llamado antes). */
    fun reset() {
        inSilence = true
        silenceStartMs = 0L
        speechStartMs = 0L
        triggered = false
    }

    /** La duración acumulada actual del silencio.(milisegundos),Devuelve 0 si no es silencioso. */
    fun currentSilenceDurationMs(): Long {
        if (!inSilence) return 0L
        return SystemClock.elapsedRealtime() - silenceStartMs
    }

    companion object {
        /** Umbral de silencio predeterminado(normalización RMS,aprox. -46dB,Adecuado para fuente VOICE_COMMUNICATION). */
        const val DEFAULT_THRESHOLD = 0.05f

        /** Duración predeterminada del disparo automático silencioso:1500ms. */
        const val DEFAULT_SILENCE_DURATION_MS = 1_500L

        /** Duración mínima de voz predeterminada:300ms. */
        const val DEFAULT_MIN_SPEECH_DURATION_MS = 300L
    }
}
