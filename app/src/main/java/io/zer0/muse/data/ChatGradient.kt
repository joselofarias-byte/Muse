package io.zer0.muse.data

import kotlinx.serialization.Serializable

/**
 * E3: Configuración de fondo degradado dinámico de chat.
 *
 * Degradado lineal de dos colores(arriba a la izquierda→abajo a la derecha);Long Guardar valores de color ARGB(Como por ejemplo 0xFF1A2980),
 * UI Para capa [Long.toInt] girar [androidx.compose.ui.graphics.Color].
 * null Indica que no se utilizan gradientes(Solo color de fondo predeterminado).
 */
@Serializable
data class ChatGradient(
    val startColorArgb: Long,
    val endColorArgb: Long,
)
