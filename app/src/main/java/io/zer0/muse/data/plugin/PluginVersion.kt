package io.zer0.muse.data.plugin

/**
 * Número de versión del complemento (versión semántica,SemVer 2.0 reglas de precedencia).
 *
 * Se utiliza para tres cosas:
 *  - Negarse a instalar paquetes anteriores a la versión instalada (protección de degradación, reversión y entrada explícita);
 *  - Comparar manifiestos `minAppVersion` con la versión actual de la aplicación;
 *  - Juicio de coherencia de versiones entre directorios y paquetes, y búsqueda de objetivos de reversión.
 *
 * Crear metadatos (`+build`) no participa en la comparación y cumple con la especificación SemVer.
 */
data class PluginVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** Logotipo de prelanzamiento (`1.0.0-rc.1` en `rc`, `1`); vacío indica la versión oficial. */
    val prerelease: List<String> = emptyList(),
) : Comparable<PluginVersion> {

    override fun compareTo(other: PluginVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        return comparePrerelease(prerelease, other.prerelease)
    }

    /** Versión oficial (sin designación previa al lanzamiento). */
    val isStable: Boolean get() = prerelease.isEmpty()

    override fun toString(): String = buildString {
        append(major).append('.').append(minor).append('.').append(patch)
        if (prerelease.isNotEmpty()) append('-').append(prerelease.joinToString("."))
    }

    companion object {
        /**
         * Analizar la versión semántica; devuelve nulo si es ilegal (la persona que llama rechaza en lugar de adivinar una versión).
         *
         * Utilice el mismo conjunto de formas que la validación de directorio:`Señor.veces.revisión` + Opcional `-prelanzamiento` + Opcional `+construir`.
         */
        private val PATTERN =
            Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")

        fun parse(raw: String): PluginVersion? {
            val matched = PATTERN.matchEntire(raw.trim()) ?: return null
            val major = matched.groupValues[1].toIntOrNull() ?: return null
            val minor = matched.groupValues[2].toIntOrNull() ?: return null
            val patch = matched.groupValues[3].toIntOrNull() ?: return null
            val prerelease = matched.groupValues[4]
                .takeIf { it.isNotEmpty() }
                ?.split('.')
                .orEmpty()
            // Los identificadores numéricos previos al lanzamiento no permiten ceros a la izquierda (SemVer Standard), cualquier versión ilegal se considerará ilegal.
            if (prerelease.any { it.length > 1 && it.startsWith("0") && it.all(Char::isDigit) }) return null
            return PluginVersion(major, minor, patch, prerelease)
        }
    }
}

private fun comparePrerelease(first: List<String>, second: List<String>): Int {
    // Las versiones con una designación previa al lanzamiento tienen una prioridad más baja (1.0.0-rc < 1.0.0).
    if (first.isEmpty() && second.isEmpty()) return 0
    if (first.isEmpty()) return 1
    if (second.isEmpty()) return -1
    for (index in 0 until maxOf(first.size, second.size)) {
        val left = first.getOrNull(index) ?: return -1
        val right = second.getOrNull(index) ?: return 1
        val leftNumber = left.toIntOrNull()
        val rightNumber = right.toIntOrNull()
        val result = when {
            leftNumber != null && rightNumber != null -> compareValues(leftNumber, rightNumber)
            leftNumber != null -> -1 // Los identificadores numéricos tienen menor prioridad que los identificadores de letras
            rightNumber != null -> 1
            else -> left.compareTo(right)
        }
        if (result != 0) return result
    }
    return 0
}
