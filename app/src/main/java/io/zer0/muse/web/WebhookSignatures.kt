package io.zer0.muse.web

/**
 * v2.0: webhook herramienta de firma — Basado en [Ed25519](Implementación pura de Kotlin).
 *
 * Se utiliza para devolución de llamada de plataforma abierta QQ:
 *  - [signChallenge]: op=13 Verificación de dirección de devolución de llamada(Firmar event_ts + Plain_token);
 *  - [verifyWebhook]: Verificación a prueba de manipulaciones mediante eventos(Verificar marca de tiempo + firma del cuerpo).
 *
 * La clave es la que está en la configuración del canal. Bot Secret(APP Secret).
 */
internal object WebhookSignatures {

    /** op=13 respuesta al desafío:Devuelve la firma hexadecimal. */
    fun signChallenge(secret: String, eventTs: String, plainToken: String): String {
        val seed = Ed25519.seedFromSecret(secret)
        val signature = Ed25519.sign(seed, (eventTs + plainToken).toByteArray(Charsets.UTF_8))
        return signature.joinToString("") { "%02x".format(it) }
    }

    /** Verificación de firma push de eventos:Verifique que la firma hexadecimal sea válida para la marca de tiempo + cuerpo. */
    fun verifyWebhook(secret: String, timestamp: String, body: String, signatureHex: String): Boolean {
        if (secret.isBlank() || timestamp.isBlank() || signatureHex.isBlank()) return false
        if (signatureHex.length != 128) return false
        val signature = runCatching {
            ByteArray(signatureHex.length / 2) { i ->
                signatureHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull() ?: return false
        if (signature.size != 64) return false
        val publicKey = Ed25519.seedToPublicKey(Ed25519.seedFromSecret(secret))
        return Ed25519.verify(publicKey, (timestamp + body).toByteArray(Charsets.UTF_8), signature)
    }
}
