package io.zer0.muse.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * B6-02: Una paleta especial para pieles emocionales.
 *
 * Administre de forma centralizada los valores de color de los efectos de la piel del estado de ánimo y la superposición de pantalla completa para evitar colores desnudos dispersos en los archivos de la interfaz de usuario.
 * Estos colores son parte del protocolo de apariencia de estado de ánimo y no pertenecen a los colores semánticos del Material, por lo que se mantienen por separado en el dominio del tema.
 */
object MoodSkinColors {

    /** glow Color de resaltado de efecto especial. */
    val glow = Color(0xFFFFB74D)

    /** whisper Los efectos especiales debilitan el color. */
    val whisper = Color(0xFF9E9E9E)

    /** red Color de acento de efecto especial. */
    val red = Color(0xFFE53935)

    /** shake Colores de efectos especiales. */
    val shake = Color(0xFF8E24AA)

    /** blur Colores de efectos especiales. */
    val blur = Color(0xFFBDBDBD)

    /** glitch Colores de efectos especiales. */
    val glitch = Color(0xFF00ACC1)

    /** Degradado de pantalla completa predeterminado. */
    val defaultOverlay = listOf(
        Color(0x33333333),
        Color(0x22111111),
        Color(0x11000000),
    )

    /** Tome el degradado de pantalla completa por nombre de moodSkin. */
    val overlays: Map<String, List<Color>> = mapOf(
        "rage" to listOf(Color(0x66111111), Color(0x55B71C1C), Color(0x22111111)),
        "rage2" to listOf(Color(0x66FF3D00), Color(0x33B71C1C), Color(0x22111111)),
        "desire" to listOf(Color(0x66B71C1C), Color(0x33FF6F91), Color(0x22111111)),
        "vuoto" to listOf(Color(0x66616A6B), Color(0x334A4A4A), Color(0x22111111)),
        "moonlight" to listOf(Color(0x660B1D4D), Color(0x33E1E9FF), Color(0x22111111)),
    )
}
