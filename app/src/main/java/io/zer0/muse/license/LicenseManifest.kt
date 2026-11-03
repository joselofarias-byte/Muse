package io.zer0.muse.license

import kotlinx.serialization.Serializable

/**
 * Phase 13: Modelo de datos con licencia de código abierto.
 *
 * de `assets/licenses/manifest.json` analizar,Los nombres de los campos corresponden a JSON uno a uno.
 * Nota:Todos los campos se conservan null-safe,Porque algunas dependencias pueden carecer de derechos de autor./enlace/notes.
 *
 * serialización:
 *  - Nombre del campo = JSON key(directamente emparejado,No es necesario @SerialName)
 *  - usar kotlinx.serialization(Introducido en la Fase 6,Koin / OkHttp se utiliza en este proyecto)
 */

/** Entradas de permisos para dependencias individuales. */
@Serializable
data class LicenseEntry(
    val groupId: String,
    val name: String,
    val version: String,
    val licenseId: String,
    // M9: copyright Cadena vacía predeterminada,Evite errores de deserialización cuando dependa parcialmente de campos de derechos de autor faltantes
    val copyright: String = "",
    val url: String = "",
    val notes: String = "",
) {
    /** Un título breve para mostrar la lista:`groupId:name` Truncar solo a la parte después de los dos puntos. */
    val displayName: String get() = name

    /** Número de versión utilizado para mostrar la lista. */
    val versionLabel: String get() = version

    /** Abreviatura de protocolo para visualización de lista(Tomado de licenseId,Como por ejemplo "apache-2.0" → "Apache 2.0"). */
    val licenseLabel: String get() = licenseId.toLicenseLabel()

    /** Proyecto de código abierto para saltar. URL(si está vacío,UI No mostrar el botón de enlace). */
    val hasUrl: Boolean get() = url.isNotBlank()
}

/** El contenedor de manifiesto de nivel superior. */
@Serializable
data class LicenseManifest(
    val schemaVersion: Int,
    val generatedAt: String,
    val appName: String,
    val appVersion: String,
    /** licenseId → assets ruta relativa interna(Como por ejemplo "licenses/apache-2.0.txt"). */
    val licenseTexts: Map<String, String>,
    val dependencies: List<LicenseEntry>,
) {
    /** Agrupar por ID de licencia,utilizado en la interfaz de usuario"Visualización agregada por protocolo". */
    fun groupedByLicense(): Map<String, List<LicenseEntry>> = dependencies.groupBy { it.licenseId }
        .toSortedMap(compareBy { it })

    /** total,UI arriba "Total N dependencias" Usar. */
    val totalCount: Int get() = dependencies.size
}

/** Transforme los identificadores de protocolo en etiquetas legibles por humanos. */
fun String.toLicenseLabel(): String = when (this) {
    "apache-2.0" -> "Apache 2.0"
    "mit" -> "MIT"
    "bsd-3-clause" -> "BSD 3-Clause"
    "bsd-2-clause" -> "BSD 2-Clause"
    "epl-1.0" -> "EPL 1.0"
    "lgpl-2.1" -> "LGPL 2.1"
    "lgpl-3.0" -> "LGPL 3.0"
    "gpl-2.0" -> "GPL 2.0"
    "gpl-3.0" -> "GPL 3.0"
    "agpl-3.0" -> "AGPL 3.0"
    "mpl-2.0" -> "MPL 2.0"
    "unlicense" -> "Unlicense"
    "cc0-1.0" -> "CC0 1.0"
    else -> this
}
