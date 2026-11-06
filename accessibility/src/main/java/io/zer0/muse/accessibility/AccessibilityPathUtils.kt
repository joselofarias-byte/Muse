package io.zer0.muse.accessibility

/** camino accesible/Función pura de estrategia de texto,Para módulos de accesibilidad y aplicaciones para compartir y facilitar pruebas individuales. */
object AccessibilityPathUtils {

    /**
     * Analizar la cadena de ruta del nodo.
     *
     * El camino legal parece "0.1.2",La raíz debe ser 0;La entrada ilegal devuelve una lista vacía.
     */
    fun parseNodePath(path: String): List<Int> {
        if (path.isBlank()) return emptyList()
        val parts = path.split('.')
        val indices = parts.map { it.toIntOrNull() ?: return emptyList() }
        if (indices.first() != 0) return emptyList()
        return indices
    }

    /** El campo de contraseña conserva los metadatos de control para el posicionamiento./entrada,Pero nunca proporciones valores o descripciones a modelos automatizados. */
    fun textForModel(value: CharSequence?, isPassword: Boolean): String? =
        if (isPassword) null else value?.toString()?.takeIf { it.isNotBlank() }

    /** Escapar de nuevas líneas en el texto/carácter de tabulación/barra invertida/corchetes,Salida de línea única garantizada. */
    fun escapeText(text: String): String = text.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t").replace("]", "\\]")
}
