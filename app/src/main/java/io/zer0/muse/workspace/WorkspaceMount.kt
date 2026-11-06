package io.zer0.muse.workspace

import kotlinx.serialization.Serializable

/**
 * El tipo de fuente a montar.
 *
 * - [LOCAL]: Se puede utilizar directamente [java.io.File] Directorios a los que se accede (directorios privados de aplicaciones, directorios compartidos legibles por aplicaciones).
 * - [SAF]: Directorio autorizado a través del selector de directorios del sistema, al que se accede con un URI persistente, sin pasar por las restricciones de almacenamiento de la partición.
 */
@Serializable
enum class MountProvider {
    LOCAL,
    SAF,
}

/**
 * Una capacidad de montaje.
 *
 * La capacidad utiliza enumeración en lugar de un interruptor booleano: el mismo montaje puede ser "legible pero no escribible",
 * También pueden ser "capaces de leer y escribir pero no aparecer en la lista", independientes entre sí.
 */
@Serializable
enum class MountCapability {
    /** Permite enumerar el contenido del directorio. */
    LIST,

    /** Permite leer el contenido de los archivos y realizar búsquedas. */
    READ,

    /** Permite escribir, agregar, eliminar, mover y crear directorios. */
    WRITE,
}

/**
 * Un directorio autorizado para su uso por la IA.
 *
 * El espacio de trabajo ya no es una caja de arena fija, sino un conjunto de soportes. Cada montura tiene su propio bit de capacidad,
 * utilizado en el camino `id:/ruta relativa` Señala la montura específica; sin prefijo, cae al montaje predeterminado. [MAIN_ID].
 *
 * @param id El ID de montaje se utiliza como prefijo en herramientas y rutas. Sólo se permiten letras minúsculas, números, guiones bajos y guiones.
 * @param label El nombre para mostrar puede contener caracteres chinos y solo se usa para la interfaz.
 * @param provider Método de acceso, decide utilizar [java.io.File] O un URI persistente.
 * @param rootPath [MountProvider.LOCAL] camino absoluto.
 * @param treeUri [MountProvider.SAF] La cadena URI del directorio de persistencia en ese momento.
 * @param capabilities Una colección de habilidades, el elemento faltante significa rechazo.
 * @param system No se permite eliminar ni cambiar el montaje integrado del sistema (espacio de trabajo predeterminado) a solo lectura.
 * @param createdAt Marca de tiempo de creación (milisegundos).
 * @param lastUsedAt Última marca de tiempo utilizada (milisegundos), utilizada para ordenar la interfaz.
 */
@Serializable
data class WorkspaceMount(
    val id: String,
    val label: String,
    val provider: MountProvider,
    val rootPath: String? = null,
    val treeUri: String? = null,
    val capabilities: Set<MountCapability> = DEFAULT_CAPABILITIES,
    val system: Boolean = false,
    val createdAt: Long = 0L,
    val lastUsedAt: Long = 0L,
) {
    /** Si se permite la lista de directorios. */
    val canList: Boolean get() = MountCapability.LIST in capabilities

    /** Ya sea para permitir la lectura y la búsqueda. */
    val canRead: Boolean get() = MountCapability.READ in capabilities

    /** Si se permiten operaciones de escritura. */
    val canWrite: Boolean get() = MountCapability.WRITE in capabilities

    /** Montaje de solo lectura: se puede ver pero no modificar. */
    val isReadOnly: Boolean get() = !canWrite

    companion object {
        /** El ID fijo del montaje predeterminado. */
        const val MAIN_ID: String = "main"

        /** Capacidades completas: listables, legibles y grabables. */
        val DEFAULT_CAPABILITIES: Set<MountCapability> = setOf(MountCapability.LIST, MountCapability.READ, MountCapability.WRITE)

        /** Capacidades de solo lectura: enumerables, legibles. */
        val READ_ONLY_CAPABILITIES: Set<MountCapability> = setOf(MountCapability.LIST, MountCapability.READ)

        /** Legalidad del logotipo: comience con una letra minúscula, permita letras minúsculas, números, guiones bajos y guiones. */
        private val ID_PATTERN = Regex("^[a-z][a-z0-9_-]{0,31}$")

        /** Determinar si un identificador cumple con las reglas de nomenclatura. */
        fun isValidId(id: String): Boolean = ID_PATTERN.matches(id)
    }
}
