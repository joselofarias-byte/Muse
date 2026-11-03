package io.zer0.common

/**
 * v2.6.7 fix (AUDIT F24): Implementación única de formato de tamaño de archivo.
 *
 * Antecedentes: antes del cambio, había 8 copias de cada implementación en todo el almacén. `formatFileSize` / `formatSize` / `formatBytes`,
 * Las diferencias incluyen: espacios ("93.1 MB" vs "93.1MB"), límite superior de unidad (a GB o TB),
 * Escritura de valor cero ("0 B" vs "0B"). Los mismos datos se muestran de forma diferente en diferentes páginas.
 * Percepción del usuario“Varias aplicaciones juntas”.
 *
 * El calibre está unificado como:
 *  - 1024 Base (misma semántica que la etiqueta KB/MB)
 *  - Un decimal, sin dejar parte entera `.0`
 *  - llega la unidad TB
 *  - Un espacio entre el número y la unidad.
 *  - 0 o visualización negativa "0 B"
 */
object FileSizeFormatter {

    private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

    /** Tamaño de archivo legible por humanos, p. "1.5 MB","512 KB","0 B". */
    fun format(bytes: Long, decimals: Int = 1): String {
        if (bytes <= 0L) return "0 B"
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < UNITS.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        // B Sin decimales; el resto conserva los decimales especificados pero elimina los ceros finales adicionales.
        if (unitIndex == 0) return "$bytes B"
        // Especifique explícitamente Locale.US: el punto decimal puede ser una coma en la configuración regional predeterminada (como de-DE 1,5),
        // dejará "1,5 MB" Este tipo de texto está en la página de configuración./Hay dos formas de escribir en el registro y no son fáciles de analizar.
        val rounded = String.format(java.util.Locale.US, "%.${decimals}f", value)
        val trimmed = if (rounded.contains('.')) rounded.trimEnd('0').trimEnd('.') else rounded
        return "$trimmed ${UNITS[unitIndex]}"
    }
}
