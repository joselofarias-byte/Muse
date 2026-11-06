package io.zer0.muse.data.session

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * entidad de sesión.
 *
 * Una conversación corresponde a una conversación continua.,Tiene historial de mensajes independiente y aislamiento de sesiones de memoria.
 * - [id] clave primaria,cuerda UUID
 * - [title] Título de la sesión(Editable por el usuario,De forma predeterminada, se toman los primeros 20 caracteres del primer mensaje.)
 * - [createdAt] Crear marca de tiempo
 * - [updatedAt] Última actualización(Se utiliza para clasificar la barra lateral)
 * - [lastMessagePreview] Vista previa del último mensaje(Visualización de la barra lateral,Una sesión vacía es una cadena vacía)
 *
 * Phase 5-I: Agregue @Serializable para exportar copias de seguridad/Importar.
 * Phase 8.2: agregar [assistantId] Personalidad del asistente de enlace de campo;[pinned] Los campos admiten pegarse a la cima.
 * Phase 8.5 Reparación: @ColumnInfo(defaultValue=...) Totalmente alineado con MIGRATION_1_2 SQL,Evite fallas en la validación del esquema de la sala.
 * Phase 9.1 (M13): agregar [folderId] El campo admite agrupación de carpetas(null = No agrupado).
 * v0.45: agregar [archived] El campo admite el archivo(Predeterminado false,Después de archivarlo, no se mostrará en la lista principal.).
 * v1.28: agregar [isAgentSession] Los campos diferencian las sesiones de la pestaña Agente(true)Conversación con la pestaña Tarea(false),
 *        Agent Tab Los chats diarios no contaminan la lista de tareas pendientes.
 */
@Serializable
@Entity(
    tableName = "sessions",
    // D-AUDIT: Lista de sesiones/consulta por asistente/índice compuesto para consulta bifurcada(Escaneo completo de la tabla con índice cero anterior)
    indices = [
        Index(value = ["assistantId", "updatedAt"], name = "idx_sessions_assistantId_updatedAt"),
        Index(value = ["deletedAt", "archived", "updatedAt"], name = "idx_sessions_deletedAt_archived_updatedAt"),
        Index(value = ["parentSessionId"], name = "idx_sessions_parentSessionId"),
    ],
)
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastMessagePreview: String = "",
    @ColumnInfo(defaultValue = "default") val assistantId: String = "default",
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    @ColumnInfo(defaultValue = "") val folderId: String? = null,
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
    /** v1.28: Si se trata de una sesión de la pestaña Agente(Agent Tab charla diaria,No mostrar en la lista de tareas). */
    @ColumnInfo(defaultValue = "0") val isAgentSession: Boolean = false,
    /** v1.107 Redundancia: Número de mensajes de sesión(Evite la página de lista COUNT escaneo de tabla completa,Repository Mantenimiento de doble escritura). */
    /**
     * columna reservada:Se utilizaron versiones históricas para el bloqueo de sesiones, actualmente**No hay manera de leer o escribir**(ENG-10 Revisar y confirmar).
     *
     * El motivo para conservar campos en lugar de eliminar columnas: eliminar migraciones que requieren reconstruir la tabla (el antiguo SQLite no admite DROP COLUMN),
     * Los rendimientos son reales y libres de riesgo. No lo utilice en código nuevo; Si está seguro de borrarlo, realice una migración por separado con la verificación del dispositivo.
     */
    @ColumnInfo(defaultValue = "0") val isLocked: Boolean = false,
    @ColumnInfo(defaultValue = "0") val messageCount: Int = 0,
    /** v2.0: Marca de tiempo de eliminación temporal(null = no eliminado). */
    @ColumnInfo(defaultValue = "NULL") val deletedAt: Long? = null,
    /** v5: Sesión fuente bifurcada id(null = sesión no bifurcada). */
    @ColumnInfo(defaultValue = "NULL") val parentSessionId: String? = null,
    /** v5: Número de sesiones bifurcadas(Solo se mantiene en la sesión original.). */
    @ColumnInfo(defaultValue = "0") val childCount: Int = 0,
    /**
     * v1.0.47 P3: Anulaciones de habilidades a nivel de sesión.
     * "[]" = Asistente de heredas skillIdsJson(Comportamiento predeterminado sin cambios);
     * matriz no vacía = Portada Assistant,Solo está habilitada la habilidad especificada.
     */

    /** B7-03: posición de lectura(último mensaje leído id,null = Aún no grabado). */
    @ColumnInfo(defaultValue = "NULL") val lastReadMessageId: String? = null,
    /** B7-03: Número de mensajes leídos(Funciona con messageCount para contar mensajes no leídos,Evite consultar la tabla de mensajes sesión por sesión en la página de lista). */
    @ColumnInfo(defaultValue = "0") val lastReadCount: Int = 0,
    /** B7-05: Número de secuencia de conversación superior(Sólo las conversaciones fijadas participan en la clasificación.,La conversación ordinaria todavía presiona updatedAt). */
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
    /** B8-01: Mensaje activo a nivel de sesión siguiente marca de tiempo de activación(null=No programado,Limpiar después de eliminar una sesión). */
    @ColumnInfo(defaultValue = "NULL") val proactiveNextTriggerAt: Long? = null,
    @ColumnInfo(defaultValue = "[]") val skillIdsJson: String = "[]",
    /**
     * v1.0.72: Esta sesión no se refiere a la memoria.(Opción de página de conversación en blanco).
     * true El mensaje del sistema omite el retrato del usuario/Memoria de pines/memoria a largo plazo/Memoria de chat grupal/Biblioteca de experiencias,
     * La conversación comienza desde cero,Ninguna referencia a ninguna memoria histórica.
     */
    @ColumnInfo(defaultValue = "0") val ignoreMemory: Boolean = false,
    /** v1.138: Columna compatible con historial——Marcadores de conversación en el espacio de mensajes privados de pequeños teléfonos celulares antiguos.
     * El viejo y pequeño teléfono celular ha sido retirado por completo y está en la biblioteca. isMiniPhone = 1 La línea está en 121→122 Eliminar durante la migración;
     * Esta columna está reservada únicamente para compatibilidad histórica y no tiene parte autora. Se eliminará cuando se produzca el próximo cambio de estructura (es necesario reconstruir la tabla).
     */
    @ColumnInfo(defaultValue = "0") val isMiniPhone: Boolean = false,
    /** v2.x Importar calentamiento:Las sesiones se importan desde el exterior./Copia de seguridad y restauración,En la primera ronda, el contexto se construye una vez con toda la historia.(Borrar después de una respuesta exitosa). */
    @ColumnInfo(defaultValue = "0") val warmupPending: Boolean = false,
    /**
     * v2.5.3 (P3-2): Instantánea de configuración a nivel de sesión(JSON, null = No tomado).
     *
     * Cuando la sesión se genera por primera vez, el modelo actual/nivel de pensamiento/La temperatura, etc. son fijas, y luego, incluso si se cambia el modelo global,
     * Las sesiones antiguas todavía hacen clic"La configuración en ese momento."Reenviar. al leer"Los campos de sesión anulan los campos auxiliares"; El modelo fijo volverá al valor actual del asistente si se elimina el modelo.
     */
    @ColumnInfo(defaultValue = "NULL") val configSnapshotJson: String? = null,

    /**
     * Borrador de entrada no enviado.
     *
     * Al cambiar de sesión, conserve el contenido no enviado de cada una para evitar cortar y tirar el contenido a medio escribir.
     * Una cadena vacía indica que no hay borrador; cada cambio de entrada se escribe mediante antivibración de la capa de interfaz de usuario.
     */
    @ColumnInfo(name = "input_draft", defaultValue = "") val inputDraft: String = "",
)
