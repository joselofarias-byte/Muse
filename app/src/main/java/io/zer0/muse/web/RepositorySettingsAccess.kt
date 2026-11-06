package io.zer0.muse.web

import io.zer0.muse.data.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * [HostSettingsAccess] La implementación del repositorio de configuración.
 *
 * Leer el valor actual de cada flujo.;Las claves de escritura se distribuyen al método de guardado correspondiente. El mapeo de claves para setters se concentra aquí.,
 * Nuevo [HostSettingDefinitions] Los elementos grabables también deben proporcionar el método de aterrizaje aquí.,
 * De lo contrario, escribir arrojará [IllegalArgumentException](Prefiero informar un error,Tampoco descarte silenciosamente una escritura aceptada).
 */
internal class RepositorySettingsAccess(private val settings: SettingsRepository) : HostSettingsAccess {

    override suspend fun readValues(): HostSettingsValues {
        val chatPreferences = settings.chatPreferencesFlow.first()
        val mediaConfig = settings.mediaConfigFlow.first()
        val language = settings.languageFlow.first()
        val themeMode = settings.themeModeFlow.first()
        val fontSizeScale = settings.fontSizeScaleFlow.first()
        val liquidGlassMode = settings.liquidGlassModeFlow.first()
        val memoryEnabled = settings.memoryEnabledFlow.first()
        val notificationMemory = settings.notificationMemoryEnabledFlow.first()
        return HostSettingsValues(
            language = language,
            themeMode = themeMode,
            fontSizeScale = fontSizeScale,
            themeId = settings.themeIdFlow.first(),
            toggles = mapOf(
                HostSettingKeys.PII_GUARD to settings.piiGuardEnabledFlow.first(),
                HostSettingKeys.PROACTIVE_MESSAGE to settings.proactiveMessageConfigFlow.first().enabled,
                HostSettingKeys.KEEP_ALIVE to settings.keepAliveEnabledFlow.first(),
                HostSettingKeys.MINI_PHONE to settings.miniPhoneEnabledFlow.first(),
                HostSettingKeys.MEMORY to memoryEnabled,
                HostSettingKeys.SCHEDULE_WORK to settings.scheduleWorkEnabledFlow.first(),
                HostSettingKeys.PLUGINS to settings.pluginsGloballyEnabledFlow.first(),
                HostSettingKeys.DYNAMIC_COLOR to settings.dynamicColorFlow.first(),
                HostSettingKeys.HIGH_CONTRAST to settings.highContrastFlow.first(),
                HostSettingKeys.TOKEN_ESTIMATE to settings.tokenEstimateEnabledFlow.first(),
                HostSettingKeys.CHAT_ENTER_TO_SEND to chatPreferences.enterToSend,
                HostSettingKeys.CHAT_STREAM_RESPONSE to chatPreferences.streamResponse,
            ),
            raw = mapOf(
                "themeMode" to themeMode,
                "fontSizeScale" to fontSizeScale,
                "liquidGlassMode" to liquidGlassMode,
                "liquidGlassStrength" to settings.liquidGlassStrengthFlow.first().toString(),
                "dynamicColor" to settings.dynamicColorFlow.first().toString(),
                "highContrast" to settings.highContrastFlow.first().toString(),
                HostSettingKeys.PII_GUARD to settings.piiGuardEnabledFlow.first().toString(),
                HostSettingKeys.CHAT_ENTER_TO_SEND to chatPreferences.enterToSend.toString(),
                "chatPreferences.showToolCallDetails" to chatPreferences.showToolCallDetails.toString(),
                "chatPreferences.showTimestamp" to chatPreferences.showTimestamp.toString(),
                HostSettingKeys.CHAT_STREAM_RESPONSE to chatPreferences.streamResponse.toString(),
                "chatPreferences.forceReasoning" to chatPreferences.forceReasoning.toString(),
                "chatPreferences.reasoningLevel" to chatPreferences.forceReasoningLevel.name,
                "mediaConfig.ttsEngine" to mediaConfig.ttsEngine,
                "mediaConfig.ttsSpeechRate" to mediaConfig.ttsSpeechRate.toString(),
                "mediaConfig.ttsVoiceName" to mediaConfig.ttsVoiceName,
                HostSettingKeys.KEEP_ALIVE to settings.keepAliveEnabledFlow.first().toString(),
                HostSettingKeys.MINI_PHONE to settings.miniPhoneEnabledFlow.first().toString(),
                HostSettingKeys.TOKEN_ESTIMATE to settings.tokenEstimateEnabledFlow.first().toString(),
                HostSettingKeys.MEMORY to memoryEnabled.toString(),
                "notificationMemoryEnabled" to notificationMemory.toString(),
                "language" to language,
                "model.configured" to (settings.providerConfigFlow.first() != null).toString(),
            ),
        )
    }

    override suspend fun write(key: String, value: String) {
        when {
            key.startsWith(CHAT_PREFIX) -> writeChatPreference(key, value)
            key.startsWith(MEDIA_PREFIX) -> writeMediaConfig(key, value)
            else -> writeScalar(key, value)
        }
    }

    /** Términos escalares como Apariencia/General/Memoria/Privacidad. */
    private suspend fun writeScalar(key: String, value: String) {
        val flag = value == "true"
        when (key) {
            "themeMode" -> settings.saveThemeMode(value)
            "fontSizeScale" -> settings.saveFontSizeScale(value)
            "liquidGlassMode" -> settings.saveLiquidGlassMode(value)
            "liquidGlassStrength" -> value.toIntOrNull()?.let { settings.saveLiquidGlassStrength(it) }
            "dynamicColor" -> settings.saveDynamicColor(flag)
            "highContrast" -> settings.saveHighContrast(flag)
            HostSettingKeys.PII_GUARD -> settings.savePiiGuardEnabled(flag)
            HostSettingKeys.KEEP_ALIVE -> settings.saveKeepAliveEnabled(flag)
            HostSettingKeys.MINI_PHONE -> settings.saveMiniPhoneEnabled(flag)
            HostSettingKeys.TOKEN_ESTIMATE -> settings.saveTokenEstimateEnabled(flag)
            HostSettingKeys.MEMORY -> settings.saveMemoryEnabled(flag)
            "notificationMemoryEnabled" -> settings.saveNotificationMemoryEnabled(flag)
            "language" -> settings.saveLanguage(value)
            else -> throw IllegalArgumentException("setting '$key' has no repository writer")
        }
    }

    /** chatPreferences.* —— Una actualización funcional solo cambia un campo,Evite la reescritura completa sobrescribiendo modificaciones simultáneas. */
    private suspend fun writeChatPreference(key: String, value: String) {
        settings.updateChatPreferences { prefs ->
            when (key) {
                HostSettingKeys.CHAT_ENTER_TO_SEND -> prefs.copy(enterToSend = value == "true")
                HostSettingKeys.CHAT_STREAM_RESPONSE -> prefs.copy(streamResponse = value == "true")
                "chatPreferences.showToolCallDetails" -> prefs.copy(showToolCallDetails = value == "true")
                "chatPreferences.showTimestamp" -> prefs.copy(showTimestamp = value == "true")
                "chatPreferences.forceReasoning" -> prefs.copy(forceReasoning = value == "true")
                "chatPreferences.reasoningLevel" -> prefs.copy(
                    forceReasoningLevel = io.zer0.ai.core.ReasoningLevel.valueOf(value),
                )
                else -> throw IllegalArgumentException("setting '$key' has no repository writer")
            }
        }
    }

    /** mediaConfig.* —— La configuración de medios es el objeto completo.,Lea el valor actual, cambie un campo y vuelva a escribirlo. */
    private suspend fun writeMediaConfig(key: String, value: String) {
        val current = settings.mediaConfigFlow.first()
        val updated = when (key) {
            "mediaConfig.ttsEngine" -> current.copy(ttsEngine = value)
            "mediaConfig.ttsSpeechRate" -> value.toFloatOrNull()?.let { current.copy(ttsSpeechRate = it) }
            else -> throw IllegalArgumentException("setting '$key' has no repository writer")
        }
        updated?.let { settings.saveMediaConfig(it) }
    }

    private companion object {
        const val CHAT_PREFIX = "chatPreferences."
        const val MEDIA_PREFIX = "mediaConfig."
    }
}
