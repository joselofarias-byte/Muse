package io.zer0.muse.data.quickmsg

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * QuickMessage almacén — Phase 8.5.
 */
class QuickMessageRepository(
    private val dao: QuickMessageDao,
) {
    // L-PID5: agregar distinctUntilChanged,Evite emisiones repetidas sin sentido desde aguas arriba que activen el rediseño de la interfaz de usuario.
    fun observeAll(): Flow<List<QuickMessageEntity>> = dao.observeAll().distinctUntilChanged()

    fun observeForAssistant(assistantId: String): Flow<List<QuickMessageEntity>> =
        dao.observeForAssistant(assistantId).distinctUntilChanged()

    /**
     * Observe los mensajes rápidos disponibles para el asistente actual.
     *
     * Los mensajes rápidos globales siempre están disponibles;Los mensajes de acceso directo a nivel de asistente se filtran por identificación cuando la lista de enlaces no está vacía.
     * La lista de enlaces vacía conserva la semántica histórica,Continuar mostrando todos los mensajes de acceso directo en el asistente,Evite que los datos antiguos desaparezcan repentinamente después de la actualización.
     */
    fun observeForAssistant(assistantId: String, boundIds: List<String>): Flow<List<QuickMessageEntity>> {
        val ids = boundIds.map(String::trim).filter(String::isNotBlank).toSet()
        return dao.observeAllEnabled()
            .map { messages ->
                messages.filter { message ->
                    message.scope == "global" ||
                        (
                            message.scope == "assistant" && message.assistantId == assistantId &&
                                (ids.isEmpty() || message.id in ids)
                            )
                }
            }
            .distinctUntilChanged()
    }

    suspend fun getByIdsEnabled(ids: List<String>): List<QuickMessageEntity> = if (ids.isEmpty()) emptyList() else dao.getByIdsEnabled(ids)

    suspend fun getById(id: String): QuickMessageEntity? = dao.getById(id)

    suspend fun upsert(entity: QuickMessageEntity) = dao.upsert(entity)

    suspend fun delete(id: String) = dao.deleteById(id)

    /**
     * Representar una plantilla de mensaje rápido: reemplazar {{input}} / {{clipboard}} / {{date}} variables.
     * - {{input}}: Contenido actual del cuadro de entrada(Si está vacío, reemplácelo con una cadena vacía.)
     * - {{clipboard}}: Contenido del portapapeles del sistema
     * - {{date}}: fecha actual(yyyy-MM-dd)
     *
     * Phase 8.5 Reparación: Utilice expresiones regulares para hacer coincidir todas las variables a la vez,Evite sustituciones secuenciales que causen errores en la entrada del usuario
     * `{{date}}` El marcador de posición se reemplaza dos veces. Implementación original `template.replace("{{input}}", input).replace("{{date}}", date)`
     * hará la plantilla="Traducción: {{input}}" + entrada del usuario="hoy es {{date}}" El error se convierte "Traducción: hoy es 2026-07-04".
     *
     * L-QM1: Regex permite espacios opcionales,partido {{ input }} / {{input }} / {{ input}} Variantes.
     *
     * L-QM2 compensaciones de diseño: La lógica de renderizado se coloca en el Repositorio en lugar del Renderer/Util independiente. Razón:
     *  - El renderizado depende del contexto del tiempo de ejecución(input/clipboard/date),Y solo las personas que llaman al Repositorio(ChatViewModel)
     *    mantener estos contextos;La Util independiente aún necesita pasar los mismos parámetros.,Las ganancias son limitadas.
     *  - Actualmente solo se usa en un lugar: QuickMessage,No se requiere reutilización;Si el PromptTemplate posterior también necesita renderizarse automáticamente,Luego dibuja el renderizador público.
     */
    fun renderTemplate(
        template: String,
        currentInput: String = "",
        clipboard: String = "",
        date: String = java.time.LocalDate.now().toString(),
    ): String {
        if (template.isEmpty()) return template
        val replacements = mapOf(
            "input" to currentInput,
            "clipboard" to clipboard,
            "date" to date,
        )
        // L-QM1: \s* Permitir espacios opcionales en ambos lados de los nombres de las variables.
        val pattern = Regex("""\{\{\s*(input|clipboard|date)\s*\}\}""")
        return pattern.replace(template) { match ->
            replacements[match.groupValues[1]] ?: match.value
        }
    }
}
