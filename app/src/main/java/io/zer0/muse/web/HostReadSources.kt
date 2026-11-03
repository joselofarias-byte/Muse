package io.zer0.muse.web

/**
 * Host en el acuerdo**solo lectura**Una colección de fuentes de datos para el comando.
 *
 * Estas fuentes de datos son independientes entre sí (memoria/base de conocimientos/tareas programadas/estado del sistema), pero son sólo proyecciones de sólo lectura "para ver en la página web".
 * Y todos permiten sin ensamblar (cuando se desensambla, el comando regresa como de costumbre, pero devuelve una lista vacía). Recójalos en un parámetro,
 * Evite agregar un parámetro de construcción más a la puerta de enlace para cada tipo de datos leídos.
 */
internal data class HostReadSources(
    /** Recuerde la fuente de datos de los comandos de solo lectura; enviar una lista vacía cuando no esté ensamblada. */
    val memory: HostMemorySource = HostMemorySource.Empty,
    /** Fuente de datos para comandos de conocimiento de solo lectura; cuando no está ensamblado, se emite/knowledge_base_not_found una lista vacía. */
    val knowledge: HostKnowledgeSource = HostKnowledgeSource.Empty,
    /** Fuente de datos para comandos automatizados de solo lectura; envía una lista vacía cuando no está ensamblada. */
    val tasks: HostTaskSource = HostTaskSource.Empty,
    /** La fuente de datos del comando de solo lectura del estado del sistema; cuando no está ensamblado, se entrega el estado predeterminado completo. */
    val system: HostSystemStatusSource = HostSystemStatusSource.Empty,
) {
    companion object {
        /** Todo desmontado: sólo para pruebas y montaje mínimo utilizable. */
        val Empty = HostReadSources()
    }
}
