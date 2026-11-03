package io.zer0.muse.data

import io.zer0.common.AppJson
import org.junit.Assert.assertEquals
import org.junit.Test

/** B8-01: Pruebas de campo de persistencia de configuración de mensajes activos. */
class ProactiveMessageConfigTest {

    @Test
    fun defaultNextTriggerAtIsZero() {
        assertEquals(0L, ProactiveMessageConfig().nextTriggerAt)
    }

    @Test
    fun roundTripKeepsNextTriggerAt() {
        val config = ProactiveMessageConfig(nextTriggerAt = 1_234_567_890L)
        val json = AppJson.encodeToString(ProactiveMessageConfig.serializer(), config)
        val decoded = AppJson.decodeFromString(ProactiveMessageConfig.serializer(), json)

        assertEquals(1_234_567_890L, decoded.nextTriggerAt)
    }

    // ── v1.0.72: Enviar campo de probabilidad ──

    @Test
    fun defaultSendProbabilityIsHundred() {
        // Valor predeterminado 100: la decisión se aprueba y se emite inmediatamente(El comportamiento es el mismo que antes de la versión 1.0.71.,Compatible con configuraciones antiguas)
        assertEquals(100, ProactiveMessageConfig().sendProbability)
    }

    @Test
    fun roundTripKeepsSendProbability() {
        val config = ProactiveMessageConfig(sendProbability = 50)
        val json = AppJson.encodeToString(ProactiveMessageConfig.serializer(), config)
        val decoded = AppJson.decodeFromString(ProactiveMessageConfig.serializer(), json)

        assertEquals(50, decoded.sendProbability)
    }

    @Test
    fun oldConfigWithoutProbabilityDecodesAsDefault() {
        // La versión anterior de JSON serializado no contiene el campo sendProbability → La deserialización debería ser la opción predeterminada. 100
        val oldJson = """{"enabled":true,"intervalMinutes":240,"lastTriggeredAt":0,"nextTriggerAt":0,"randomOffsetMinutes":60,"agentId":"","allowedHourStart":8,"allowedHourEnd":22,"agentOnly":true,"maxDailyMessages":3,"temperature":0.8}"""
        val decoded = AppJson.decodeFromString(ProactiveMessageConfig.serializer(), oldJson)

        assertEquals(100, decoded.sendProbability)
        assertEquals(true, decoded.enabled)
    }
}
