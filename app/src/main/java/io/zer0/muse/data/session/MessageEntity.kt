package io.zer0.muse.data.session

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * entidad de mensaje(persistente [io.zer0.ai.core.UIMessage]).
 *
 * Phase 5-I: Agregue @Serializable para exportar copias de seguridad/Importar.
 * Phase 8.3: agregar [favorite] El campo admite la recopilación de mensajes.
 * Phase 8.4: agregar [citationUrlsJson] Los campos admiten la representación de referencias de UrlCitation.
 * Phase 8.5 Reparación: @ColumnInfo(defaultValue=...) Alineado con MIGRATION_2_3/3_4 SQL.
 * Phase 8.6: agregar [imageBase64Json] El campo admite la entrada de imágenes multimodal(USER)Trazar la salida con Gemini(ASSISTANT).
 */
@Serializable
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sessionId"),
        // M-SESS5: role Índice de una sola columna,acelerar WHERE role='ASSISTANT'/'USER' Espere consultas sin prefijo sessionId
        Index("role"),
        Index(value = ["sessionId", "createdAt"]),
        Index(value = ["sessionId", "createdAt", "role"]),
        // v92: seq índice de secuencia monótona — Clave principal de clasificación de mensajes dentro de la sesión,Anulaciones que se pueden actualizar createdAt
        // Nota: name Debe coincidir exactamente con CREATE INDEX de MIGRATION_91_92(idx_messages_sessionId_seq),
        // De lo contrario, la verificación del esquema de la sala de máquinas real falla.(Migration didn't properly handle: messages).
        Index(value = ["sessionId", "seq"], name = "idx_messages_sessionId_seq"),
        // v94: Nueva orden de confirmación; secuencia antigua reservada para compatibilidad con rutas de lectura antiguas.
        Index(value = ["sessionId", "commitSeq"], name = "idx_messages_sessionId_commitSeq"),
        Index(value = ["parentMessageId"], name = "idx_messages_parentMessageId"),
        Index(value = ["parentGroupId"], name = "idx_messages_parentGroupId"),
    ],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val reasoning: String? = null,
    /** B5-03: Firma de múltiples rondas de pensamiento.(Anthropic signature / OpenAI reasoning item id). */
    @ColumnInfo(defaultValue = "NULL") val thinkingSignature: String? = null,
    /** B5-03: OpenAI Responses reasoning item contenido_cifrado. */
    @ColumnInfo(defaultValue = "NULL") val thinkingEncryptedContent: String? = null,
    val modelId: String? = null,
    val createdAt: Long,
    /**
     * v92: Secuencia monótonamente creciente dentro de la sesión — Clave principal estable para ordenar mensajes.
     *
     * fondo: Dependencias previamente ordenadas [createdAt],Pero createAt se actualiza en streaming./Interrumpir la recuperación/Al retransmitir
     * puede ser renovado(REPLACE Cubrir toda la fila),Conduciendo al trastorno de sincronización de mensajes(La primera respuesta es la última y se superpone.).
     * seq Asignado en la primera inserción(dentro de la sesión max+1),Mantenga el valor anterior al actualizar con la misma identificación,
     * No cambia con REPLACE,es una clave de clasificación estable. Rellene por ID de fila al migrar datos antiguos.
     */
    @ColumnInfo(defaultValue = "0") val seq: Long = 0,
    /** v94: Nuevo orden de confirmación del enlace de conversación; Los mensajes antiguos permanecen con 0 o valor de reposición de auditoría de migración. */
    @ColumnInfo(defaultValue = "0") val commitSeq: Long = 0,
    /** v94: Referencia explícita del mensaje principal; El antiguo parentGroupId permanece. */
    @ColumnInfo(defaultValue = "NULL") val parentMessageId: String? = null,
    @ColumnInfo(defaultValue = "[]") val imageUrlsJson: String = "[]",
    /**
     * Referencia de audio del mensaje de voz ([io.zer0.muse.ui.speech.VoiceMessageStore] dentro `file://` camino).
     *
     * NULL = Mensajes no vocales.Provider Ignore esta columna al enviar solicitudes, la voz es solo una representación del mensaje.
     */
    @ColumnInfo(defaultValue = "NULL") val voiceUri: String? = null,
    /** Duración de la voz (milisegundos);0 = Desconocido (falló la detección de datos antiguos o de duración). */
    @ColumnInfo(defaultValue = "0") val voiceDurationMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val favorite: Boolean = false,
    /**
     * v1.104 U7: Etiquetas de grupos favoritos(Definido por el usuario,Como por ejemplo"inspiración"/"fragmento de código").
     *
     * solo en [favorite]=true significativo;NULL Indica no agrupado.
     * Por pantalla de favoritos Mantenga presionada la tarjeta para que aparezca"Configurar grupos"Escritura de diálogo,DAO usar [MessageDao.setMessageFavoriteTag].
     */
    @ColumnInfo(defaultValue = "NULL") val favoriteTag: String? = null,
    @ColumnInfo(defaultValue = "[]") val citationUrlsJson: String = "[]",
    /** v1.133: Lista de referencias de la base de conocimientos(JSON serialización [io.zer0.ai.core.RagCitation]). */
    @ColumnInfo(defaultValue = "[]") val ragCitationsJson: String = "[]",
    /** Phase 8.6: Lista de imágenes locales base64(JSON matriz,Ninguno dato: prefijo). */
    @ColumnInfo(defaultValue = "[]") val imageBase64Json: String = "[]",
    /**
     * Reparación de auditoría (S-02): Resultados de generación de vídeo URL(ASSISTANT noticias,generate_video escribir).
     *
     * Anteriormente videoFileUri sólo existía en la memoria UIMessage,MessageEntity Sin columna correspondiente,
     * Reiniciar/El vídeo se pierde permanentemente después de cortar la página.v88 Migrar columnas complementarias,toUIMessage/toEntity Ida y vuelta.
     */
    @ColumnInfo(defaultValue = "NULL") val videoFileUri: String? = null,
    /** v1.43: Lista de identificadores de artefactos asociados con el mensaje(JSON matriz). */
    @ColumnInfo(defaultValue = "[]") val artifactIdsJson: String = "[]",
    /**
     * v1.103: MOOD bloquear(LLM Salida manuscrita interna antes del texto principal.).
     *
     * Eliminado del contenido por MoodTagTransformer `<mood>...</mood>` Depositar más tarde.
     * Este campo faltaba en MessageEntity antes,sesión de tarea/Agent charlar(Usar tabla de mensajes)Al realizar el pedido
     * mood descartado silenciosamente,Cortar páginas/Las tarjetas MOOD desaparecen después de recargarlas. mesa de chat grupal(group_chat_messages)
     * Ya hay una columna de humor.,Esta vez se completa la tabla de mensajes.
     */

    /** B6-03/B6-02: Logotipo de piel emocional(con <moodfx> Correspondencia de etiquetas,Predeterminado null). */
    @ColumnInfo(defaultValue = "NULL") val moodSkin: String? = null,
    @ColumnInfo(defaultValue = "NULL") val mood: String? = null,
    /** v1.103: bloque de autorreflexión(`<reflection>...</reflection>`),Completado al mismo tiempo que el estado de ánimo. */
    @ColumnInfo(defaultValue = "NULL") val reflection: String? = null,
    /** v1.107 Redundancia: content longitud(base de control de integridad,Detectar truncamiento de almacenamiento/dañado). */
    @ColumnInfo(defaultValue = "0") val contentLength: Int = 0,
    /** v2.0: Marca de tiempo de eliminación temporal(null = no eliminado). */
    @ColumnInfo(defaultValue = "NULL") val deletedAt: Long? = null,
    /** Función1: Mensaje de respuesta de emoticono(ThumbUp/Favorite/SentimentSatisfied/SentimentDissatisfied/MoodBad/Bolt,null=Ninguno). */
    @ColumnInfo(defaultValue = "NULL") val reaction: String? = null,
    /** v1.0.30: ID de grupo variante (compartido por varias respuestas de asistente en la misma ubicación).null = Mensaje no variante. */
    @ColumnInfo(defaultValue = "NULL") val variantGroupId: String? = null,
    /** v1.0.30: Número de variante (0-based, posición en el grupo actual). */
    @ColumnInfo(defaultValue = "0") val variantIndex: Int = 0,
    /** v1.0.30: Número total de variantes (número de mensajes dentro del grupo, todas las variantes registran el mismo valor). */
    @ColumnInfo(defaultValue = "1") val variantCount: Int = 1,
    /**
     * P0 árbol de diálogo: El grupo de variantes de preguntas del usuario al que pertenece la variante del asistente. ID(parentGroupId).
     * ConversationTreeBuilder infiere cronológicamente los nodos principales cuando los datos antiguos son NULL.
     */
    @ColumnInfo(defaultValue = "NULL") val parentGroupId: String? = null,
    /** v1.0.47: Lista de archivos adjuntos de mensajes(JSON serialización [io.zer0.ai.core.AttachmentRef]). */
    @ColumnInfo(defaultValue = "[]") val attachmentsJson: String = "[]",
    /**
     * v1.0.72: Información de la tarjeta de llamada de herramientas(JSON serialización [io.zer0.ai.core.ToolCallInfo]).
     *
     * Reparación:Anteriormente, toolCallInfo no persistía,El historial de llamadas de herramientas se pierde después de reiniciar,
     * Se muestra como"interrumpido"en vez de"llamar". Ahora acércate con las novedades.,ToolCallCard se muestra normalmente después de reiniciar.
     */
    @ColumnInfo(defaultValue = "NULL") val toolCallInfoJson: String? = null,
    /**
     * A5: Metadatos de generación de mensajes(Para visualización de información,provider Se prefiere la medición real,Estimación local de UI cuando falta).
     * Rellenado cuando lo genera ChatViewModel;datos antiguos/No rellenado es NULL.
     * - [durationMs] Tiempo total necesario para generar(milisegundos,Con ciclo de herramienta)
     * - [promptTokens]/[completionTokens]/[reasoningTokens] provider Dosis medida real
     * - [cachedTokens] caché**leer** token. El calibre cambia con el tiempo:v120 Simplemente finge estar "leyendo".
     *   La fila anterior antes de la división contiene el valor combinado de "lectura + escritura" (la fila anterior se interpreta como "lectura", sin relleno ni recálculo)
     * - [cacheWriteTokens] caché**escribir** token(Solo antrópico);viejo comportamiento NULL=desconocido
     *
     * Para análisis histórico [cachedTokens] deben separarse primero, de lo contrario los dos calibres se considerarán uno solo:
     *  - `cacheWriteTokens IS NULL` = v120 Calibre antiguo anterior ([cachedTokens] incluida la escritura),
     *    v120 Después de eso, mientras se utilice, la columna no será NULL (OpenAI/Gemini es 0,Anthropic es la medida real),
     *    Por tanto, esta condición es más precisa que cortar por tiempo; se utiliza cuando se necesitan límites de tiempo. `createdAt` Delimite el momento de la actualización a v120.
     *  - Para obtener el total de "lectura + escritura" (comparable al calibre anterior), utilice
     *    `cachedTokens + COALESCE(cacheWriteTokens, 0)`, no lo utilices directamente `cachedTokens`.
     */
    @ColumnInfo(defaultValue = "NULL") val durationMs: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val promptTokens: Int? = null,
    @ColumnInfo(defaultValue = "NULL") val completionTokens: Int? = null,
    @ColumnInfo(defaultValue = "NULL") val reasoningTokens: Int? = null,
    @ColumnInfo(defaultValue = "NULL") val cachedTokens: Int? = null,
    @ColumnInfo(defaultValue = "NULL") val cacheWriteTokens: Int? = null,
    /**
     * H11: La traducción conserva el texto original. — El mensaje de traducción apunta al mensaje fuente traducido. id(forma de cadena),UI Proporciona plegado de comparación de texto original.
     */
    @ColumnInfo(defaultValue = "NULL") val translationSourceId: String? = null,
    /**
     * v2.4.6: Mapeo de traducción para este mensaje(JSON: Nombre del idioma → traducción). Bloque expandible colgado en el propio mensaje.,No abras otro mensaje del asistente.
     */
    @ColumnInfo(defaultValue = "{}") val translationsJson: String = "{}",
)
