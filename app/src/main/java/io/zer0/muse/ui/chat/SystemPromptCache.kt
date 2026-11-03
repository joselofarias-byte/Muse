package io.zer0.muse.ui.chat

/**
 * v1.x: system prompt / Un contenedor de estado compartido para la caché de tokens de contexto.
 *
 * oso cachedSystemPrompt(Texto de aviso del sistema dinámico),cachedStaticSystemPrompt(instantánea estática),
 * cachedStaticSnapshotKey(La instantánea caduca key),suministro buildSystemPromptForStream / updateContextTokenCount
 * y compartir y reutilizar rutas de streaming.ChatViewModel Mantener las referencias existentes sin cambios mediante la delegación getter/setter,
 * Allane el camino para mover buildSystemPromptForStream a ChatGenerationController.
 */
internal class SystemPromptCache {
    /** v0.45: Texto de aviso del sistema en caché(Evite reconstruir cada 50 caracteres durante la transmisión). */
    var cachedSystemPrompt: String = ""

    /** Instantánea del mensaje del sistema estático(Reutilización continua de mensajes dentro de la misma sesión,Solo agregue tiempo dinámico section). */
    var cachedStaticSystemPrompt: String = ""

    /** La instantánea estática caduca key(assistant/settings/chatPreferences Activar reconstrucción ante el cambio). */
    var cachedStaticSnapshotKey: String = ""
}
