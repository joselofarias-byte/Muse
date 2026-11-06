package io.zer0.muse.ui.chat

import io.zer0.muse.R

/**
 * v1.97: Registro de comandos de barra diagonal.
 *
 * Al ingresar un comando que comienza con / en el cuadro de entrada,Analizado y ejecutado por ChatViewModel.executeSlashCommand.
 * La orden no pasa LLM,Procesado directamente del lado del cliente.
 */
enum class SlashCommand(
    val command: String,
    val descriptionResId: Int,
) {
    NEW("new", R.string.slash_command_new),
    COMPACT("compact", R.string.slash_command_compact),
    RESET("reset", R.string.slash_command_reset),
    PIN("pin", R.string.slash_command_pin),
    ARCHIVE("archive", R.string.slash_command_archive),
    ;

    companion object {
        /** Analizar comandos del texto de entrada. Devuelve nulo para indicar que no es un comando de barra diagonal. */
        fun parse(text: String): SlashCommand? {
            val trimmed = text.trim()
            if (!trimmed.startsWith("/")) return null
            val name = trimmed.removePrefix("/").split("\\s+".toRegex()).firstOrNull()?.lowercase() ?: return null
            return entries.firstOrNull { it.command == name }
        }

        /** Determinar si el texto es un comando de barra diagonal(Se utiliza para interceptar antes de enviar.). */
        fun isSlashCommand(text: String): Boolean = parse(text) != null

        /** Obtenga una lista de nombres de todos los comandos(para autocompletar UI). */
        fun allCommandNames(): List<String> = entries.map { "/${it.command}" }
    }
}
