package io.zer0.muse.web

import java.util.Locale

/** El tipo de protocolo del elemento de configuración, en forma de cadena, se envía a la página web. `type`. */
internal enum class HostSettingType(val wireName: String) {
    BOOLEAN("boolean"),
    NUMBER("number"),
    ENUM("enum"),
    TEXT("text"),
}

/**
 * Definición completa de una configuración: clave, tipo, capacidad de escritura, candidatos de enumeración (valor id → Nombre para mostrar cadena nombre del recurso), rango de valores.
 *
 * Problema ([HostSettingValue]) y la lista blanca de escritura se derivan de esta definición, y no se mantiene ninguna tabla de claves separada:
 * Si agrega un elemento nuevo, cámbielo solo aquí y la lista blanca, la verificación y la instantánea seguirán automáticamente.
 */
internal data class HostSettingDefinition(
    val key: String,
    val type: HostSettingType,
    /**
     * Ya sea para aceptar escrituras.false Indica que es solo para visualización (valores derivados como el nombre del timbre actual y si el modelo ha sido configurado),
     * Escribirle será rechazado explícitamente en lugar de descartado silenciosamente.
     */
    val writable: Boolean = true,
    /** enum Candidato: valor id → El nombre del recurso de cadena del nombre para mostrar. */
    val options: List<Pair<String, String>> = emptyList(),
    /** number límite inferior/límite superior/Paso (con límites). */
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    /** text la longitud máxima;0 No indica límite. */
    val maxLength: Int = 0,
) {

    /** Estandarizar cadenas enviadas externamente según este tipo; en caso de falla, proporcione un motivo que se pueda mostrar directamente. */
    fun parse(raw: String?): HostSettingParseResult {
        val text = raw?.trim().orEmpty()
        return when (type) {
            HostSettingType.BOOLEAN -> when (text.lowercase(Locale.ROOT)) {
                "true" -> HostSettingParseResult.Ok("true")
                "false" -> HostSettingParseResult.Ok("false")
                else -> HostSettingParseResult.Rejected("settings.update rejected: '$key' expects true/false")
            }

            HostSettingType.NUMBER -> parseNumber(text)
            HostSettingType.ENUM -> {
                val hit = options.firstOrNull { it.first == text }
                if (hit == null) {
                    val allowed = options.joinToString(", ") { it.first }
                    HostSettingParseResult.Rejected("settings.update rejected: '$key' expects one of: $allowed")
                } else {
                    HostSettingParseResult.Ok(hit.first)
                }
            }

            HostSettingType.TEXT -> when {
                maxLength > 0 && text.length > maxLength -> HostSettingParseResult.Rejected(
                    "settings.update rejected: '$key' is longer than $maxLength characters",
                )
                else -> HostSettingParseResult.Ok(text)
            }
        }
    }

    private fun parseNumber(text: String): HostSettingParseResult {
        val number = text.toDoubleOrNull()
            ?: return HostSettingParseResult.Rejected("settings.update rejected: '$key' expects a number")
        if (!number.isFinite()) {
            return HostSettingParseResult.Rejected("settings.update rejected: '$key' expects a finite number")
        }
        if ((min != null && number < min) || (max != null && number > max)) {
            return HostSettingParseResult.Rejected(
                "settings.update rejected: '$key' out of range [${format(min)}, ${format(max)}]",
            )
        }
        return HostSettingParseResult.Ok(canonicalNumber(text, number))
    }

    /** Escribir usando texto decimal: elimine el punto decimal cuando el valor sea un número entero en el rango de enteros para evitar que 55 se convierta en 55,0. */
    private fun canonicalNumber(text: String, number: Double): String {
        val integralRange = isIntegral(min) && isIntegral(max)
        return if (integralRange && number % 1.0 == 0.0) number.toInt().toString() else text
    }

    private fun isIntegral(bound: Double?): Boolean = bound == null || bound % 1.0 == 0.0

    private fun format(bound: Double?): String = when {
        bound == null -> "-"
        bound % 1.0 == 0.0 -> bound.toInt().toString()
        else -> bound.toString()
    }
}

/** [HostSettingDefinition.parse] resultado. */
internal sealed interface HostSettingParseResult {
    /** [canonical] Es una cadena estandarizada por tipo y entregada al almacén para su escritura tal como está. */
    data class Ok(val canonical: String) : HostSettingParseResult

    data class Rejected(val reason: String) : HostSettingParseResult
}

/**
 * Todas las configuraciones entregables/escribibles.
 *
 * El orden de la lista es el orden de representación en la página del terminal web; la clasificación depende del prefijo clave (`chatPreferences.` / `mediaConfig.`)
 * Reflejado con el nombre constante, ya no es una copia separada.“Clasificación → clave”de cartografía.
 *
 * Los valores derivados sólo se pueden leer pero no escribir:`model.configured`(si el modelo ha sido configurado) con
 * `mediaConfig.ttsVoiceName`(El nombre del tono TTS actual solo se muestra en la página web). todavía aparecen en
 * `values` , porque la página web necesita mostrarlos; Escribir la lista blanca, naturalmente, no los incluye.
 */
internal object HostSettingDefinitions {

    /** Apariencia: Modo temático. */
    private val THEME_MODE = HostSettingDefinition(
        key = "themeMode",
        type = HostSettingType.ENUM,
        options = listOf(
            "system" to "settings_theme_follow_system",
            "light" to "settings_theme_light",
            "dark" to "settings_theme_dark",
        ),
    )

    /** Apariencia: Archivo de tamaño de fuente. */
    private val FONT_SIZE_SCALE = HostSettingDefinition(
        key = "fontSizeScale",
        type = HostSettingType.ENUM,
        options = listOf(
            "small" to "settings_theme_font_small",
            "medium" to "settings_theme_font_medium",
            "large" to "settings_theme_font_large",
            "xlarge" to "settings_theme_font_xlarge",
        ),
    )

    /** Aspecto: Modo vidrio líquido. */
    private val LIQUID_GLASS_MODE = HostSettingDefinition(
        key = "liquidGlassMode",
        type = HostSettingType.ENUM,
        options = listOf(
            "off" to "settings_glass_off",
            "water" to "settings_glass_water",
            "frost" to "settings_glass_frost",
        ),
    )

    /** Apariencia: Resistencia del vidrio (0..100 Porcentaje entero). */
    private val LIQUID_GLASS_STRENGTH = HostSettingDefinition(
        key = "liquidGlassStrength",
        type = HostSettingType.NUMBER,
        min = 0.0,
        max = 100.0,
        step = 5.0,
    )

    private val DYNAMIC_COLOR = HostSettingDefinition(key = "dynamicColor", type = HostSettingType.BOOLEAN)

    private val HIGH_CONTRAST = HostSettingDefinition(key = "highContrast", type = HostSettingType.BOOLEAN)

    /** Chat: Tecla Enviar (presione Enter para enviar directamente/Intro y avance de línea). */
    private val CHAT_ENTER_TO_SEND = HostSettingDefinition(
        key = HostSettingKeys.CHAT_ENTER_TO_SEND,
        type = HostSettingType.BOOLEAN,
    )

    private val CHAT_SHOW_TOOL_CALLS = HostSettingDefinition(
        key = "chatPreferences.showToolCallDetails",
        type = HostSettingType.BOOLEAN,
    )

    private val CHAT_SHOW_TIMESTAMP = HostSettingDefinition(
        key = "chatPreferences.showTimestamp",
        type = HostSettingType.BOOLEAN,
    )

    private val CHAT_STREAM_RESPONSE = HostSettingDefinition(
        key = HostSettingKeys.CHAT_STREAM_RESPONSE,
        type = HostSettingType.BOOLEAN,
    )

    /** Chat: Obligado a volver a pensar (la intensidad del pensamiento solo surte efecto después de activarlo). */
    private val CHAT_FORCE_REASONING = HostSettingDefinition(
        key = "chatPreferences.forceReasoning",
        type = HostSettingType.BOOLEAN,
    )

    /** Charla: Piensa en la intensidad. */
    private val CHAT_REASONING_LEVEL = HostSettingDefinition(
        key = "chatPreferences.reasoningLevel",
        type = HostSettingType.ENUM,
        options = listOf(
            "OFF" to "reasoning_level_off",
            "AUTO" to "reasoning_level_auto",
            "LOW" to "reasoning_level_low",
            "MEDIUM" to "reasoning_level_medium",
            "HIGH" to "reasoning_level_high",
            "XHIGH" to "reasoning_level_xhigh",
        ),
    )

    /** Voz:TTS motor. El candidato es el mismo que el menú del motor de la aplicación ([io.zer0.muse.ui.speech.TtsManager.CLOUD_TTS_ENGINES]). */
    private val TTS_ENGINE = HostSettingDefinition(
        key = "mediaConfig.ttsEngine",
        type = HostSettingType.ENUM,
        options = listOf(
            "system" to "settings_media_tts_engine_system",
            "openai" to "settings_media_tts_engine_openai",
            "minimax" to "settings_media_tts_engine_minimax",
            "edge" to "settings_media_tts_engine_edge",
            "gemini" to "settings_media_tts_engine_gemini",
            "dashscope" to "settings_media_tts_engine_dashscope",
            "fish" to "settings_media_tts_engine_fish",
            "elevenlabs" to "settings_media_tts_engine_elevenlabs",
            "groq" to "settings_media_tts_engine_groq",
            "qwen" to "settings_media_tts_engine_qwen",
            "step" to "settings_media_tts_engine_step",
            "xai" to "settings_media_tts_engine_xai",
        ),
    )

    /** Voz: velocidad de transmisión de voz (0.5..2.0 veces,1.0 es normal). */
    private val TTS_SPEECH_RATE = HostSettingDefinition(
        key = "mediaConfig.ttsSpeechRate",
        type = HostSettingType.NUMBER,
        min = 0.5,
        max = 2.0,
        step = 0.1,
    )

    /** Voz: nombre de voz actual, solo se muestra. */
    private val TTS_VOICE_NAME = HostSettingDefinition(
        key = "mediaConfig.ttsVoiceName",
        type = HostSettingType.TEXT,
        writable = false,
    )

    private val PII_GUARD = HostSettingDefinition(key = HostSettingKeys.PII_GUARD, type = HostSettingType.BOOLEAN)

    private val KEEP_ALIVE = HostSettingDefinition(key = HostSettingKeys.KEEP_ALIVE, type = HostSettingType.BOOLEAN)

    private val MINI_PHONE = HostSettingDefinition(key = HostSettingKeys.MINI_PHONE, type = HostSettingType.BOOLEAN)

    private val TOKEN_ESTIMATE = HostSettingDefinition(
        key = HostSettingKeys.TOKEN_ESTIMATE,
        type = HostSettingType.BOOLEAN,
    )

    /** Memoria: Interruptor maestro. */
    private val MEMORY_ENABLED = HostSettingDefinition(key = HostSettingKeys.MEMORY, type = HostSettingType.BOOLEAN)

    /**
     * Memoria: almacena automáticamente el contenido de las notificaciones en la memoria.
     *
     * App No existe un interruptor separado llamado "Guardado automático", este es el único controlado por el usuario.“Almacenamiento automático”campo verdadero
     * (La recuperación automática de la memoria se ejecuta automáticamente según la ronda de diálogo, sin cambio).
     */
    private val NOTIFICATION_MEMORY = HostSettingDefinition(
        key = "notificationMemoryEnabled",
        type = HostSettingType.BOOLEAN,
    )

    /** General: idioma de la interfaz. Los candidatos coinciden con el selector de idioma de la aplicación (Sistema de seguimiento/chino/inglés/japonés/coreano/ruso). */
    private val LANGUAGE = HostSettingDefinition(
        key = "language",
        type = HostSettingType.ENUM,
        options = listOf(
            "system" to "settings_theme_follow_system",
            "es" to "settings_theme_language_es",
            "en" to "settings_theme_language_en",
            "pt-rBR" to "settings_theme_language_pt",
            "ja" to "settings_theme_language_ja",
            "ko" to "settings_theme_language_ko",
            "ru" to "settings_theme_language_ru",
        ),
    )

    /** General: si el modelo ha sido configurado, valores derivados, solo se muestran. */
    private val MODEL_CONFIGURED = HostSettingDefinition(
        key = "model.configured",
        type = HostSettingType.BOOLEAN,
        writable = false,
    )

    /** El orden de todos los elementos de valor es el orden de representación de las páginas del terminal web. */
    val all: List<HostSettingDefinition> = listOf(
        THEME_MODE,
        FONT_SIZE_SCALE,
        LIQUID_GLASS_MODE,
        LIQUID_GLASS_STRENGTH,
        DYNAMIC_COLOR,
        HIGH_CONTRAST,
        PII_GUARD,
        CHAT_ENTER_TO_SEND,
        CHAT_SHOW_TOOL_CALLS,
        CHAT_SHOW_TIMESTAMP,
        CHAT_STREAM_RESPONSE,
        CHAT_FORCE_REASONING,
        CHAT_REASONING_LEVEL,
        TTS_ENGINE,
        TTS_SPEECH_RATE,
        TTS_VOICE_NAME,
        MEMORY_ENABLED,
        NOTIFICATION_MEMORY,
        LANGUAGE,
        MODEL_CONFIGURED,
    )

    /** Escribir en la lista blanca: desde [all] derivado de, con `values` Misma fuente, no se mantiene ninguna tabla de claves separada. */
    val writableKeys: List<String> = all.filter { it.writable }.map { it.key }

    private val byKey: Map<String, HostSettingDefinition> = all.associateBy { it.key }

    fun find(key: String?): HostSettingDefinition? = key?.trim()?.takeIf { it.isNotEmpty() }?.let(byKey::get)
}
