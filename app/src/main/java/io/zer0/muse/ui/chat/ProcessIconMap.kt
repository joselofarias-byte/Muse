package io.zer0.muse.ui.chat

import androidx.compose.ui.graphics.vector.ImageVector
import io.zer0.muse.ui.common.icons.MuseIcons

/**
 * Iconos de pasos del proceso: infiere "lo que se está haciendo en este paso" según el nombre de la herramienta.
 *
 * Dividir en varios grupos de funciones pequeñas en lugar de una grande when —— Decenas en una sola función `||` Empujará directamente la complejidad ciclomática a través del umbral.
 * Además, después de la agrupación, "qué categoría se juzga primero" se escribe en la secuencia de llamada, que es más fácil de leer que ocultarla en una expresión larga.
 *
 * El orden de llamada es la prioridad:
 *  - La clase de agente es lo primero; de lo contrario, agent_discuss caerá en la rama "delegación"
 *  - Las clases de comando vienen antes que las clases de archivos,exec_command También está la palabra leída en él.
 *
 * Si nada coincide, devuelve la llave inglesa: es mejor ser mediocre que adivinar el movimiento equivocado.
 */
internal fun processIconFor(name: String): ImageVector {
    val n = name.lowercase()
    return agentIcon(n) ?: shellIcon(n) ?: fileIcon(n) ?: netIcon(n) ?: dataIcon(n) ?: MuseIcons.wrench
}

/** Pensar, discutir, delegar —— Tiene que ver con \"quién lo hace\". */
private fun agentIcon(n: String): ImageVector? = when {
    n.contains("pensar") || n.contains("think") || n.contains("reasoning") -> MuseIcons.brain
    n.contains("discuss") || n.contains("council") -> MuseIcons.users
    n.contains("subagent") || n.contains("delegate") || n.contains("agent") -> MuseIcons.robot
    else -> null
}

/** Comandos y búsqueda —— Está relacionado con \"cómo encontrarlo\". */
private fun shellIcon(n: String): ImageVector? = when {
    n.contains("exec_command") || n.contains("run_command") || n.contains("termux") -> MuseIcons.terminal
    n.contains("shell") || n.contains("terminal") || n.contains("adb") -> MuseIcons.terminal
    n.contains("search") || n.contains("grep") || n.contains("find") -> MuseIcons.search
    else -> null
}

/** leer y escribir —— Tiene algo que ver con \"mover archivos\". */
private fun fileIcon(n: String): ImageVector? = when {
    n.contains("read") || n.contains("cat") || n.contains("list_files") -> MuseIcons.bookOpen
    n.contains("open_file") || n.contains("girar") || n.contains("leer") -> MuseIcons.bookOpen
    n.contains("write") || n.contains("edit") || n.contains("patch") -> MuseIcons.edit
    n.contains("create") || n.contains("save") || n.contains("Poner lápiz sobre papel") -> MuseIcons.edit
    else -> null
}

/** red —— Relacionado con \"dentro y fuera de la red\". */
private fun netIcon(n: String): ImageVector? = when {
    n.contains("browser") || n.contains("navigate") -> MuseIcons.browser
    n.contains("fetch") || n.contains("http") || n.contains("curl") -> MuseIcons.globe
    n.contains("download") || n.contains("upload") -> MuseIcons.cloudDownload
    else -> null
}

/** memoria, medios, datos —— Tiene que ver con \"lo que se toca\". */
private fun dataIcon(n: String): ImageVector? = when {
    n.contains("memory") || n.contains("remember") || n.contains("recall") -> MuseIcons.brain
    n.contains("image") || n.contains("photo") || n.contains("video") || n.contains("sticker") -> MuseIcons.image
    n.contains("db") || n.contains("sql") || n.contains("database") -> MuseIcons.database
    else -> null
}
