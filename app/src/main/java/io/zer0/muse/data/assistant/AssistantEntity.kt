package io.zer0.muse.data.assistant

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Assistant entidad(Phase 8.2 H6).
 *
 * Escritura e implementación independientes.(35+ campo).
 * Un asistente representa un"AI Personalidad",Hay un indicador/modelo/herramienta/memoria del sistema independiente.
 * Diferentes escenarios pueden usar diferentes Assistant(asistente de escritura/asistente de código/Asistente de traducción, etc.).
 *
 * Phase 8.5 Reparación: Todos los campos NO NULOS más @ColumnInfo(defaultValue=...) Alineado con MIGRATION_1_2 SQL,
 * Evite fallas causadas por una discrepancia en el valor predeterminado esperado/encontrado durante la validación del esquema de la sala.
 *
 * L-GC7 Reparación: Agregue la anotación @Immutable. Todos los campos son val y el tipo es tipo primitivo inmutable(String/Int/Float/Boolean/Long),
 * Satisface el contrato inmutable Compose. hacer List<AssistantEntity> tratado como stable,
 * evitar remember(message.senderId, assistants) Recálculo innecesario debido a cambios en la instancia de la Lista.
 */
@Immutable
@Serializable
@Entity(tableName = "assistants")
data class AssistantEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** U-26: Estado del asistente habilitado(1=habilitar,0=desactivar);Ocultar de la lista de candidatos cuando esté desactivado,Cada consumidor filtra según demanda. */
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    @ColumnInfo(defaultValue = "0") val sortIndex: Int = 0,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),

    // ── Configuración del modelo ──
    val modelId: String? = null,
    val providerId: String? = null,
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    @ColumnInfo(defaultValue = "20") val contextMessageSize: Int = 20,
    @ColumnInfo(defaultValue = "AUTO") val reasoningLevel: String = "AUTO",

    // ── Prompt ──
    @ColumnInfo(defaultValue = "") val systemPrompt: String = "",
    @ColumnInfo(defaultValue = "") val messageTemplate: String = "",
    @ColumnInfo(defaultValue = "[]") val presetMessagesJson: String = "[]",

    /**
     * v2.6.2(comentarios de los usuarios): sección de definición de personalidad —— Bloques de personajes editables.
     *
     * con [systemPrompt] separar:systemPrompt Es una palabra de aviso completamente personalizada (los usuarios avanzados se hacen cargo de todo el párrafo),
     * Este campo es el contenido estructurado del párrafo "Definición de personalidad" y se escribirá en el párrafo de personalidad del mensaje del sistema.
     * Ante los anclajes identitarios. Déjelo en blanco para no inyectar.
     */
    @ColumnInfo(defaultValue = "") val personaDefinition: String = "",

    /**
     * v2.6.2(comentarios de los usuarios): Restricciones del proceso de pensamiento personalizado —— La composición de la cadena de pensamiento del asistente de restricciones fuertes.
     *
     * El mensaje del sistema se insertará como un párrafo separado (después del párrafo de personalidad), por ejemplo:
     * "Primero replanteemos el problema del usuario. → Enumere 2-3 posibles hipótesis → Excluir uno por uno → dar una conclusión".
     * Déjelo en blanco para no inyectar (el modelo piensa por defecto).
     */
    @ColumnInfo(defaultValue = "") val thinkingChain: String = "",

    // ── Herramientas ──
    @ColumnInfo(defaultValue = "[]") val toolIdsJson: String = "[]",
    @ColumnInfo(defaultValue = "[]") val mcpServerIdsJson: String = "[]",
    @ColumnInfo(defaultValue = "1") val streamOutput: Boolean = true,

    // ── memoria ──
    @ColumnInfo(defaultValue = "1") val memoryEnabled: Boolean = true,
    // Keep the SQL default aligned with the existing Room schema; Room inserts use this safer Kotlin default.
    @ColumnInfo(defaultValue = "1") val useGlobalMemory: Boolean = false,
    @ColumnInfo(defaultValue = "1") val enableRecentChatsReference: Boolean = true,

    // ── recordatorio ──
    @ColumnInfo(defaultValue = "1") val enableTimeReminder: Boolean = true,

    // ── avatar ──
    // Conclusión de la revisión del punto 9 de eliminación de materia muerta(2026-09-20):backgroundUrl / backgroundOpacity /
    // useGradientBackground No hay ningún extremo del consumidor en las tres columnas.(El asistente del editor de imágenes de fondo hace tiempo que se eliminó),Pero la eliminación debe realizarse en
    // minSdk 26 de SQLite(3.18,Ninguno DROP COLUMN)Reconstruya la tabla de asistentes y agréguela nuevamente. DB
    // Migración de versión;El beneficio son solo tres columnas menos con valores predeterminados.,El riesgo radica en la ruta de actualización del dispositivo,Por lo tanto con
    // SessionEntity.isLocked presione lo mismo“columna reservada”Proceso:Sólo marca esto,Las eliminaciones ya no se migrarán.
    @ColumnInfo(defaultValue = "") val backgroundUrl: String = "",
    @ColumnInfo(defaultValue = "1.0") val backgroundOpacity: Float = 1.0f,
    @ColumnInfo(defaultValue = "0") val useGradientBackground: Boolean = false,
    @ColumnInfo(defaultValue = "") val avatarEmoji: String = "",
    @ColumnInfo(defaultValue = "") val avatarImageUrl: String = "",

    // ── etiqueta ──
    @ColumnInfo(defaultValue = "[]") val tagsJson: String = "[]",

    // ── v1.200: Etiquetas de capacidad multiagente(JSON matriz,Como por ejemplo ["code","write","research"]) ──
    @ColumnInfo(defaultValue = "[]") val capabilitiesJson: String = "[]",

    // ── Expandir(Implementación de la fase posterior) ──
    @ColumnInfo(defaultValue = "[]") val quickMessageIdsJson: String = "[]",
    @ColumnInfo(defaultValue = "[]") val lorebookIdsJson: String = "[]",
    @ColumnInfo(defaultValue = "[]") val modeInjectionIdsJson: String = "[]",
    @ColumnInfo(defaultValue = "[]") val skillIdsJson: String = "[]",

    // v1.0.53: modelo de herramienta(Un modelo ligero utilizado por rondas de invocación de herramientas.,per-assistant;null Indica que el mundo toolModelId)
    @ColumnInfo(defaultValue = "") val toolModelId: String = "",

    // ── Solicitar ──
    @ColumnInfo(defaultValue = "{}") val customHeadersJson: String = "{}",
    @ColumnInfo(defaultValue = "{}") val customBodiesJson: String = "{}",

    // v1.97: Reglas de reemplazo regulares(JSON matriz,Asistente de tiendaLista Regex)
    @ColumnInfo(defaultValue = "[]") val regexRulesJson: String = "[]",

    // ── v1.133 RAG Anulaciones de vinculación y configuración ──
    /** v1.133: Lista de ID de la base de conocimientos vinculados a este asistente(JSON matriz). matriz vacía = No vinculado a ninguna KB(No inyectado RAG). */
    @ColumnInfo(defaultValue = "[]") val knowledgeBaseIdsJson: String = "[]",
    /**
     * v2.4.6: La "base de conocimientos" vinculada a este asistente+Ámbito de búsqueda de carpeta(JSON matriz),Elementos de la forma "kbId::folderPath".
     *
     * visión del mundo:base de conocimientos = raíz,carpeta = subdirectorio,Una carpeta es un dominio de búsqueda independiente.,No se crucen.
     *  - matriz vacía = heredar [knowledgeBaseIdsJson] Toda la biblioteca encuadernada.(Migración cero de datos antiguos).
     *  - elemento "kbId::" (camino vacío) = Vincular toda la base de conocimientos;
     *  - elemento "kbId::contrato" = Vincula solo aquellos bajo esta base de conocimiento. `contrato` carpeta(Contiene subdirectorios).
     */
    @ColumnInfo(defaultValue = "[]") val knowledgeFolderScopesJson: String = "[]",
    /**
     * v1.133: RAG Anulación de configuración(JSON serializado RagConfigOverride,puede ser null).
     * Fusionarse con RagConfig global cuando no esté vacío(Los campos con el mismo nombre tienen prioridad sobre la anulación).
     * Ejemplo:{"topK":5,"threshold":0.4,"mmrLambda":0.5}
     */
    @ColumnInfo(defaultValue = "NULL") val ragConfigOverride: String? = null,

    // ── v1.107 Campos estadísticos redundantes(Evite la agregación completa de tablas,Repository Mantenimiento de doble escritura) ──
    /** El número total de mensajes enviados por este Asistente(Leer la página de estadísticas directamente,Evite GROUP BY escaneo completo de la tabla). */
    @ColumnInfo(defaultValue = "0") val messageCount: Int = 0,
    /** Última marca de tiempo utilizada(ordenar/Estadísticas"Activo recientemente"usar). */
    @ColumnInfo(defaultValue = "0") val lastUsedAt: Long = 0,

    // ── v1.0.19: Assistant Finalización del campo ──
    // Nota: tags La agrupación ya está contenida en el campo tagsJson de arriba.(JSON matriz),Las definiciones no se repiten aquí.
    /** Introducción de una frase(Se utiliza para la lista de chat grupal y la visualización de subtítulos de la tarjeta de asistente.). */
    @ColumnInfo(defaultValue = "") val summary: String = "",
    /** Reemplace el nombre del modelo con el nombre del asistente en la interfaz de chat,false Se muestra el nombre del modelo. */
    @ColumnInfo(defaultValue = "0") val useAssistantName: Boolean = false,
    /** Si permitir unirse al chat grupal(Predeterminado true,Después del cierre, el asistente no aparecerá en la lista de selección de miembros del chat grupal.). */
    @ColumnInfo(defaultValue = "1") val allowGroupChat: Boolean = true,
) {
    /** Juicio conveniente:Si se debe utilizar un avatar con imagen. */
    fun hasImageAvatar(): Boolean = avatarImageUrl.isNotBlank()

    /** Juicio conveniente:Si una imagen de fondo está configurada(Columna reservada correspondiente backgroundUrl). */
    fun hasBackground(): Boolean = backgroundUrl.isNotBlank()
}
