package io.zer0.muse.data.groupchat

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Entidad de chat grupal.
 *
 * Un chat grupal contiene varios miembros del Agente,Después de que el usuario envía un mensaje, [GroupChatScheduler] La serie hace que cada agente se turne para hablar.
 * - [memberIdsJson] almacenamiento List<String> Serialización JSON de(Assistant id lista)
 * - [teamId] Asociación opcional de equipos en MultiAgentConfig id
 *
 * @param id Solo chat grupal id
 * @param name Nombre del chat grupal
 * @param description Descripción del chat grupal/Propósito
 * @param memberIdsJson Cadena JSON de la lista de ID de asistente de miembro
 * @param teamId Equipos asociados id(Opcional)
 * @param createdAt Crear marca de tiempo
 * @param updatedAt Última marca de tiempo actualizada
 */
@Serializable
@Entity(tableName = "group_chats")
data class GroupChatEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(defaultValue = "") val description: String = "",
    val memberIdsJson: String,
    val teamId: String? = null,
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    // ── v1.107 campos redundantes(Evite las listas de chat grupal JOIN messages,Repository Mantenimiento de doble escritura) ──
    /** Vista previa del último mensaje(Visualización de lista,Truncado a 50 palabras). */
    @ColumnInfo(defaultValue = "") val lastMessagePreview: String = "",
    /** El número total de mensajes de chat grupal. */
    @ColumnInfo(defaultValue = "0") val messageCount: Int = 0,
    /** Marca de tiempo de la última actividad(para clasificar,igual al último mensaje createdAt). */
    @ColumnInfo(defaultValue = "0") val lastActivityAt: Long = 0,
    // ── v2.x Modo de discusión de chat grupal ──
    /**
     * modo de discusión:
     * - "round_robin": rotación en serie(Predeterminado,compatible con versiones anteriores)— Después de que el usuario envía un mensaje, cada IA ​​hablará durante una ronda por turno.
     * - "auto": discusión libre — AI diálogo continuo automático entre,alcanzar [autoMaxRounds] o detenerse cuando todos PASEN
     * - "debate": modo debate — cadena fija:Preguntas de usuario → A dar un plan → B encontrar lagunas → C mejorar,Cada ronda debe responder a la persona anterior.
     * - "host": Modo moderador — por [hostId] La IA designada analiza el problema y envía tareas dinámicamente a otros miembros.
     */
    @ColumnInfo(defaultValue = "round_robin") val discussionMode: String = "round_robin",
    /** Auto modo:AI Número máximo de rondas de diálogo consecutivas entre(cada ronda = Todos los miembros hablan una vez.). */
    @ColumnInfo(defaultValue = "5") val autoMaxRounds: Int = 5,
    /** Modo moderador:Se desempeñó como moderador del grupo. assistant id,null Recurrir a round_robin. */
    @ColumnInfo(name = "host_id", defaultValue = "NULL") val hostId: String? = null,
    // ── v2.x Gestión del contexto del chat grupal ──
    /**
     * Documentos compartidos en grupo(JSON cuerda,Predeterminado "[]").
     *
     * serializado a [List<GroupSharedDoc>],Visible para todos los miembros del grupo,Se inyectará en cada agente. system prompt
     * como conocimiento previo compartido([Documento compartido en grupo] Párrafo).
     *
     * Propósito:Materiales compartidos dentro de los chats grupales.(Como actas de reuniones, documentos de requisitos, materiales de referencia.),Dejemos que todas las IA discutan basándose en el mismo material.
     */
    @ColumnInfo(name = "shared_docs_json", defaultValue = "[]") val sharedDocsJson: String = "[]",
    /**
     * AI Contexto exclusivo(JSON cuerda,Predeterminado "{}").
     *
     * serializado a `Map<String, String>`(key = assistantId,value = El texto contextual privado de la IA).
     * Sólo el agente correspondiente es visible,Inyectar en el indicador del sistema([Su contexto exclusivo] Párrafo),
     * No puede ser visto por otros miembros del grupo.,con [whisperTargetId] Los susurros juntos constituyen"contexto privado"Capacidad.
     *
     * Propósito:Inyecte configuraciones de roles, limitaciones privadas, preferencias no reveladas, etc. en IA específicas.
     */
    @ColumnInfo(name = "member_private_context_json", defaultValue = "{}") val memberPrivateContextJson: String = "{}",
    /** v2.x: Etiqueta de archivo de chat grupal.trueLos chats grupales están ocultos de la lista principal y se pueden ver y restaurar en la lista de archivos */
    @ColumnInfo(defaultValue = "0") val isArchived: Boolean = false,

    // ── v2.x Rango de longitud del discurso ──
    /**
     * Rango de longitud del discurso:
     * - "brief": corto — lluvia de ideas/saludos,Explique claramente en una o dos oraciones.
     * - "standard": Estándar(Predeterminado)
     * - "detailed": detallado — Revisión/Discusión en profundidad,completamente expandido
     */
    @ColumnInfo(defaultValue = "standard") val replyLengthMode: String = "standard",

    // ── v2.x miembro observador ──
    /**
     * Lista de identificaciones de miembros observadores(JSON cuerda,Predeterminado "[]").
     *
     * Los observadores no participan en la rotación predeterminada.(Mirar y discutir en silencio.),pero permanecer en la lista de miembros;lista vacía = Ningún observador.
     */
    @ColumnInfo(name = "observer_ids_json", defaultValue = "[]") val observerIdsJson: String = "[]",
)

/**
 * v2.x: Estructura de datos de documentos compartidos del chat grupal.
 *
 * - [id] Documento único id(para eliminación/actualizar)
 * - [title] Título del documento(Para visualización y recuperación)
 * - [content] Texto del documento(inyectar en agent system prompt)
 * - [addedAt] Agregar marca de tiempo
 *
 * Serializado a JSON y almacenado en [GroupChatEntity.sharedDocsJson] en el campo.
 */
@Serializable
data class GroupSharedDoc(
    val id: String,
    val title: String,
    val content: String,
    val addedAt: Long = System.currentTimeMillis(),
)
