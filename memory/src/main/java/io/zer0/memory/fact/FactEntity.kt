package io.zer0.memory.fact

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Entidad metahecho.
 *
 * v3 schema: Eliminar campo de texto de búsqueda(ya no se usa FTS5,No se requiere columna de índice de n-gramas).
 * v4 schema: Campo de importancia agregado(0=ordinario,1=importante,2=clave),Los hechos clave nunca decaen.
 * v8 schema: Agregar campo de alcance(alcance de la memoria,Predeterminado "main" Representa el ámbito de ayuda principal.,
 *   sub-asistente/Los miembros del equipo usan los suyos. assistantId),Se utiliza para aislar los recuerdos de diferentes Agentes.
 * v9 schema: Campo space_id agregado(espacio de memoria,Predeterminado "default" Representa el espacio predeterminado.),
 *   Utilizado para aislamiento de múltiples espacios.,Ortogonal al alcance.
 * v12 schema: Campo entidad_key agregado(Clave de normalización de entidad). Diferentes formas de escribir la misma entidad
 *   (Como por ejemplo"Zhang San"/"Sr. Zhang"/"Maestro Zhang San")compartir lo mismo entity_key,Para una deduplicación precisa y
 *   Fusionar distintas ortografías,resolver"3 memorias duplicadas para el mismo nombre de usuario"pregunta. Los datos históricos son null,
 *   Rellenado con tareas de reflexión durante la recopilación.
 * v17 schema: Nuevo score / score_updated_at / suppressed_at campo —— Atenuación de"Eliminar al vencimiento"Cambiar a"apariencia de la partitura".
 *   La memoria de puntuación baja simplemente deja de recordarse(Se puede reservar y volver a llamar.),La limpieza debe realizarse mediante eliminación explícita.
 * v18 schema: Se agregó el campo reemplaza_id —— La nueva memoria apunta a la antigua memoria que reemplaza. La fila anterior permanece disponible para su revisión.,
 *   Ceder el paso sólo en el contexto de compilación;Si no hay pruebas suficientes, no ingrese en esta columna.,Ambos se dejan en contexto y se juzgan según el modelo.
 * v19 schema: Se agregó el campo fusionado_into_id —— ¿En qué memoria se fusionó este elemento? El nuevo recuerdo al que se apunta lleva
 *   Texto integrado después de la deduplicación,Dirección de señalización(fila original)Reservado para inspección,Pero no participa en la recuperación automática y no ingresa en la proyección de compilación.
 *
 * Phase 7: @Serializable para exportación de respaldo/Importar.id La clave principal de incremento automático se reasigna después de borrar la tabla durante la importación.
 */
@Serializable
@Entity(
    tableName = "facts",
    indices = [
        Index(value = ["time"], name = "idx_facts_time"),
        Index(value = ["session_id"], name = "idx_facts_session"),
        Index(value = ["importance"], name = "idx_facts_importance"),
        Index(value = ["category"], name = "idx_facts_category"),
        Index(value = ["scope"], name = "index_facts_scope"),
        Index(value = ["space_id"], name = "index_facts_space_id"),
        // v12 Reparación: entity_key El índice debe ser coherente con CREATE INDEX de MIGRATION_11_12.
        // La anotación anterior omitió este índice.,Y se construyó en la migración. → La verificación del esquema falla cuando se abre la sala del dispositivo real
        // (Migration didn't properly handle: facts),El centro de la memoria colapsa tan pronto como se abre.
        Index(value = ["entity_key"], name = "idx_facts_entity_key"),
        // v16(auditoría de memoria): índice compuesto — La gran mayoría de las consultas son por (scope, space_id) Filtrar.
        Index(value = ["scope", "space_id"], name = "index_facts_scope_space_id"),
    ],
)
data class FactEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** hechos originales(PII desensibilizada). */
    @ColumnInfo(name = "fact")
    val fact: String,

    /** etiqueta cadena de matriz JSON,Como por ejemplo `["sistema de memoria","Situación actual"]`. */
    @ColumnInfo(name = "tags", defaultValue = "[]")
    val tags: String = "[]",

    /** tiempo real(YYYY-MM-DDTHH:MM),Disponible. */
    @ColumnInfo(name = "time")
    val time: String? = null,

    /** ID de sesión de origen. */
    @ColumnInfo(name = "session_id")
    val sessionId: String? = null,

    /** Creado ISO 8601. */
    @ColumnInfo(name = "created_at")
    val createdAt: String,

    /**
     * v4: importancia(0=ordinario,1=importante,2=clave).
     * - 0(ordinario): preferencias diarias,Está bien recordarlo mal(Como por ejemplo"Café y estilo americano.")
     * - 1(importante): riesgo medio,Recordar mal puede provocar malentendidos(Como por ejemplo"Informe entregado el miércoles")
     * - 2(clave): alto riesgo,Si lo recuerdas mal, algo sucederá.(Como por ejemplo"alergia a la penicilina"),nunca decaer
     * Determinación preliminar al extraer por LLM.,Los usuarios pueden ajustarlo manualmente en la página de memoria.
     */
    @ColumnInfo(name = "importance", defaultValue = "0")
    val importance: Int = 0,

    /**
     * v5: clasificación estructurada(Como por ejemplo preference / identity / event / relationship / goal / medical espera).
     * Se utiliza para la recuperación por tipo y visualización de grupo de UI.
     */
    @ColumnInfo(name = "category", defaultValue = "general")
    val category: String = "general",

    /**
     * v5: LLM Confianza en la fiabilidad de los hechos.,alcance 0.0 ~ 1.0.
     * El usuario declara explícitamente que 1.0,La información especulativa se toma por debajo de 0,5.,Facilitar la revisión manual posterior.
     */
    @ColumnInfo(name = "confidence", defaultValue = "1.0")
    val confidence: Float = 1.0f,

    /**
     * v5: Fuente de hecho.
     * - user_explicit: declaración explícita del usuario
     * - inferred: LLM inferir del contexto
     * - imported: importación externa
     */
    @ColumnInfo(name = "source", defaultValue = "inferred")
    val source: String = "inferred",

    /**
     * v5: Tiempo de vencimiento del hecho ISO 8601,Disponible.
     * para hechos urgentes(Como arreglos temporales)atenuación automática,La prioridad es más alta de lo normal y decae en el momento de la creación.
     */
    @ColumnInfo(name = "expires_at")
    val expiresAt: String? = null,

    /**
     * v5: La hora en que fue confirmado por última vez por el usuario o el sistema. ISO 8601,Disponible.
     * Se actualiza cada vez que se hace referencia y se confirma un hecho en la conversación.,Su reloj de decadencia se puede restablecer.
     */
    @ColumnInfo(name = "last_confirmed_at")
    val lastConfirmedAt: String? = null,

    /**
     * v7: Hora del último golpe ISO 8601,Disponible.
     * hechos citados/Confirmar/Actualización sobre la fusión,Se utiliza para restablecer el reloj de decadencia y disfrutar del bono hitBonus.
     */
    @ColumnInfo(name = "last_hit_at", defaultValue = "NULL")
    val lastHitAt: String? = null,
    /** v14: El número acumulado de visitas por recuperación (para determinar si esta memoria realmente está funcionando). */
    @ColumnInfo(name = "hit_count", defaultValue = "0")
    val hitCount: Int = 0,

    /**
     * v8: alcance de la memoria,Se utiliza para aislar los recuerdos de diferentes Agentes.
     *  - "main":alcance de ayuda principal(Predeterminado),Datos sobre la conversación del usuario con el asistente principal.
     *  - assistantId:sub-asistente/Alcance de los miembros del equipo,Los hechos de la conversación del usuario con el subasistente.
     * Filtrar por alcance al realizar consultas,Evite que los asistentes infantiles hagan mal uso del hecho de que el asistente principal,También evita la confusión de memoria entre los miembros del equipo.
     */
    @ColumnInfo(name = "scope", defaultValue = "main")
    val scope: String = "main",

    /**
     * v9: espacio de memoria id,Se utiliza para aislamiento de múltiples espacios.
     *  - "default":Espacio predeterminado(Compatible con datos antiguos)
     *  - ID personalizado: trabajo creado por el usuario/vida/Estudio y otros espacios
     * con [scope] ortogonal:scope Cuarentena por agente,spaceId Aislar por escena.
     * Un hecho pertenece a algo Agent scope,También pertenece a un Espacio.
     */
    @ColumnInfo(name = "space_id", defaultValue = "default")
    val spaceId: String = "default",

    /**
     * B4-05: Tiempo de fijación manual ISO 8601,null Indica que no está fijado.
     * Lista ordenada por máxima prioridad,Al inyectar palabras clave, se da prioridad a la memoria superior.
     */
    @ColumnInfo(name = "pinned_at", defaultValue = "NULL")
    val pinnedAt: String? = null,

    /**
     * v12: Clave de normalización de la entidad. Nombres canónicos escritos de diferentes formas para una misma entidad.
     *  - Dado por la etapa de extracción LLM(Como por ejemplo entity_key="Zhang San",aliases=["Sr. Zhang","Maestro Zhang San"])
     *  - vacío/Inferido del texto de hechos por FactStore cuando no se recupera(nombre/nombre propio o texto normalizado)
     *  - Método de escritura cruzada que se fusiona para realizar tareas precisas de verificación de duplicaciones y reflexión mientras se escribe
     */
    @ColumnInfo(name = "entity_key")
    val entityKey: String? = null,

    /**
     * v17: Fracción de decadencia de persistencia —— "¿Este recuerdo sigue presente?"base para el juicio.
     *
     * Las puntuaciones anteriores se calculan cada vez.(Recomendado por edad),Sin almacenamiento,La única consecuencia del deterioro es la eliminación de filas.;
     * La puntuación ahora se vuelve a escribir en esta columna con la liquidación de caída diaria.,Por tanto, la sentencia de umbral tiene una base estable.:
     *  - nueva memoria de [io.zer0.memory.ticker.MemoryConfig.baseImportance](Predeterminado 10.0)A partir de
     *  - Cada liquidación presiona exp(-λ·días) Disminuyendo,λ = decayPerDay × forgetSpeed
     *  - golpear/Agregar al fusionar hitBonus,límite superior baseImportance + hitBonus
     */
    @ColumnInfo(name = "score", defaultValue = "10.0")
    val score: Float = 10f,

    /**
     * v17: Tiempo de liquidación del último decaimiento ISO 8601 —— Reloj de decadencia para partituras persistentes.
     *
     * La liquidación incremental depende de esta columna para que no se contradiga.:Solo presiona cada ronda"Número de días desde la última liquidación"Disminuyendo,
     * En lugar de volver a calcular desde create_at cada vez.null significa que nunca se ha resuelto,Recurrir a last_hit_at/creado_at.
     */
    @ColumnInfo(name = "score_updated_at", defaultValue = "NULL")
    val scoreUpdatedAt: String? = null,

    /**
     * v17: tiempo de juego ISO 8601 —— null Dijo que todavía estaba en retirada.
     *
     * Cuando la puntuación es inferior al umbral de compilación, se escribe en esta columna mediante el cálculo de decadencia.:Esta memoria ya no participa en la recuperación ni en la inyección de contexto.,
     * pero toda la fila permanece,citado posteriormente/Borre esta columna durante la recuperación explícita para volver al rango de recuperación.
     */
    @ColumnInfo(name = "suppressed_at", defaultValue = "NULL")
    val suppressedAt: String? = null,

    /**
     * v18: El viejo recuerdo reemplazado por este recuerdo. id —— null Esto significa que este artículo no reemplaza ningún recuerdo.
     *
     * Al escribir, si se determina que este artículo pertenece a la misma entidad que un recuerdo antiguo, la afirmación es contraria.,Escriba aquí la antigua identificación de la memoria.:
     * No eliminar filas antiguas(La página de memoria todavía está visible y se puede revertir),Pero el producto recopilatorio(FACTS segmento)No más escribirlo en contexto.
     * Dejar en blanco si no hay pruebas suficientes —— Ambos entran en el contexto.,El modelo ve el conflicto mismo.
     */
    @ColumnInfo(name = "supersedes_id", defaultValue = "NULL")
    val supersedesId: Long? = null,

    /**
     * v19: ¿En qué memoria se fusiona esta memoria? —— null Indica que no se ha fusionado.
     *
     * con [supersedesId] son dos relaciones diferentes,no mezclar:
     *  - [supersedesId]: Nuevos recuerdos reemplazan a los viejos**contenido**(Dos afirmaciones contradictorias,lo nuevo es correcto)
     *  - [mergedIntoId]: Este artículo es**Fusionado en**otro(Los dos no son contradictorios.,solo repite,Sólo queda una copia después de la agregación.)
     * La misma línea no tendrá dos punteros al mismo tiempo.: Una memoria se reemplaza(Contenido no válido),o fusionarse(El contenido se ha fusionado con otros artículos.).
     *
     * dirección semántica: Apunta a la identificación del nuevo hecho generado después de la fusión. El nuevo hecho en sí no tiene este valor de columna.,
     * La relación de agregación la invierte la parte señalada.(ver FactStore.getMergedChildren).
     * Las líneas no se eliminan y las páginas de memoria son visibles.,Simplemente no participa en la recuperación automática y no ingresa a la proyección de compilación.;Al eliminar el nuevo hecho, se deshace automáticamente la combinación.
     */
    @ColumnInfo(name = "merged_into_id", defaultValue = "NULL")
    val mergedIntoId: Long? = null,
)
