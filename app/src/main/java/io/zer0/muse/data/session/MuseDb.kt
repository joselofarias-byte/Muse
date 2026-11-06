package io.zer0.muse.data.session

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.agentdm.AgentMessageDao
import io.zer0.muse.data.agentdm.AgentMessageEntity
import io.zer0.muse.data.artifact.ArtifactDao
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.data.assistant.AssistantDao
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.audit.AuditLogDao
import io.zer0.muse.data.audit.AuditLogEntity
import io.zer0.muse.data.experience.ExperienceDao
import io.zer0.muse.data.experience.ExperienceEntity
import io.zer0.muse.data.groupchat.GroupChatDao
import io.zer0.muse.data.groupchat.GroupChatEntity
import io.zer0.muse.data.groupchat.GroupChatGenerationLedgerEntity
import io.zer0.muse.data.groupchat.GroupChatMemoryDao
import io.zer0.muse.data.groupchat.GroupChatMemoryEntity
import io.zer0.muse.data.groupchat.GroupChatMessageDao
import io.zer0.muse.data.groupchat.GroupChatMessageEntity
import io.zer0.muse.data.knowledge.KnowledgeBaseDao
import io.zer0.muse.data.knowledge.KnowledgeBaseEntity
import io.zer0.muse.data.knowledge.KnowledgeChunkDao
import io.zer0.muse.data.knowledge.KnowledgeChunkEntity
import io.zer0.muse.data.knowledge.KnowledgeChunkFtsDao
import io.zer0.muse.data.knowledge.KnowledgeChunkFtsSelfHealer
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.data.knowledge.KnowledgeDocEntity
import io.zer0.muse.data.lorebook.LorebookDao
import io.zer0.muse.data.lorebook.LorebookEntity
import io.zer0.muse.data.milestone.MilestoneDao
import io.zer0.muse.data.milestone.MilestoneEntity
import io.zer0.muse.data.promptinjection.PromptInjectionDao
import io.zer0.muse.data.promptinjection.PromptInjectionEntity
import io.zer0.muse.data.quickmsg.QuickMessageDao
import io.zer0.muse.data.quickmsg.QuickMessageEntity
import io.zer0.muse.data.quicknote.QuickNoteConverters
import io.zer0.muse.data.quicknote.QuickNoteDao
import io.zer0.muse.data.quicknote.QuickNoteEntity
import io.zer0.muse.data.schedule.ScheduledTaskDao
import io.zer0.muse.data.schedule.ScheduledTaskEntity
import io.zer0.muse.data.schedule.ScheduledTaskExecutionDao
import io.zer0.muse.data.schedule.ScheduledTaskExecutionEntity
import io.zer0.muse.data.skill.SkillDao
import io.zer0.muse.data.skill.SkillEntity
import io.zer0.muse.data.stats.AutoBackupLogDao
import io.zer0.muse.data.stats.AutoBackupLogEntity
import io.zer0.muse.data.stats.DbIntegrityLogDao
import io.zer0.muse.data.stats.DbIntegrityLogEntity
import io.zer0.muse.data.stats.StatsCacheDao
import io.zer0.muse.data.stats.StatsCacheEntity
import io.zer0.muse.ui.translate.TranslateHistoryDao
import io.zer0.muse.ui.translate.TranslateHistoryEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * muse app Base de datos de Layer Room.
 *
 * Contiene conversaciones + mensajes. + Assistant + Lorebook + QuickMessage + PromptInjection seis mesas
 * (Independiente de :memory.db del módulo de memoria,Segregación de funciones).
 * - sessions: metadatos de sesión(Título/Marca de tiempo/Vista previa/atado Assistant)
 * - messages: Mensajes de chat persistentes(Asociar por ID de sesión)
 * - assistants: Assistant Configuración de personalidad múltiple(Phase 8.2)
 * - lorebooks: Entradas en libros mundiales activadas por palabras clave(Phase 8.5)
 * - quick_messages: Plantilla de mensaje rápido(Phase 8.5)
 * - prompt_injections: Palabra de solicitud de inyección de patrón(Phase 8.5)
 *
 * Phase 8.2: versión 1 → 2,Agregue la tabla de asistentes + sesiones.assistantId + sesiones.pinned.
 * Phase 8.3: versión 2 → 3,messages Agregar campo favorito(Predeterminado 0).
 * Phase 8.4: versión 3 → 4,messages Agregar el campo citationUrlsJson(Predeterminado "[]").
 * Phase 8.5: versión 4 → 5,Nuevo lorebooks / quick_messages / prompt_injections Tres mesas.
 * Phase 8.6: versión 5 → 6,messages Agregar campo imageBase64Json(Predeterminado "[]",multimodal).
 * Phase 8.8: versión 6 → 7,Agregar tabla de habilidades(Kotlin Realización directa skill,No hay necesidad QuickJS).
 * Phase 9.1: versión 7 → 8,Agregar tabla de carpetas + campo session.folderId(M13 Agrupación de carpetas).
 * Phase 10.3: versión 8 → 9,Se agregó la tabla virtual message_fts FTS4.(Índice de texto completo de ngram chino).
 *   - La migración solo crea tablas vacías,Comenzado por primera vez por SessionRepository.ensureFtsIndexConsistent cantidad total rebuild
 *   - El contenido del índice es preprocesado por MessageFtsManager.toNgram(CJK 2-gram Ventana deslizante + palabra ASCII minúscula)
 *   - MessageFtsManager.toMatchQuery se convierte en expresión MATCH al buscar(Escape de cita + semántica Y)
 * P1-7: versión 10 → 11,Agregar tabla de ejecuciones_tareas programadas(Historial de ejecución de tareas programadas).
 *   - La cadena de migración ha cubierto la ruta de actualización estándar.;Las versiones desconocidas o degradaciones son manejadas por el proceso de recuperación de respaldo y no se ejecutan destructive migration
 * v0.45: versión 11 → 12,sessions Agregar campo archivado(Función de archivo).
 * v1.43: versión 15 → 16,Tabla de artefactos agregada + campo message.artifactIdsJson(artefacto de conversación).
 * v1.0.23 hotfix: versión 41 → 42,Reparación"falso v41"Fallo en la verificación de integridad de la base de datos(Campos de complemento defensivo,Sin cambios de esquema).
 * v1.0.17: versión 43 → 44,Se agregó la tabla Translate_history.(Persistencia del historial de traducción).
 * v1.0.17: versión 44 → 45,Se agregó la tabla Quick_notes.(Registre rápidamente la persistencia de la habitación + papelera de reciclaje).
 *   Almacenamiento de archivos JSON que reemplaza el QuickNoteStore original;QuickNoteStore Mantener como fuente de migración + formato compatible.
 *   tags Las columnas se manejan a través de QuickNoteConverters separados por comas TypeConverter List<String>.
 * v1.0.17: versión 45 → 46,scheduled_tasks Agregar columnas retry_count + max_retries(Estrategia de reintento de fallo de ejecución).
 * v1.0.18: versión 47 → 48,quick_notes agregar folder/content_type/attachments_json/reminder_at/
 *   encrypted/encrypted_content 6 columna(Notas rápidas sobre 9 mejoras:Clasificación/texto enriquecido/Accesorios/recordatorio/Cifrado/Paginación, etc.).
 * v1.0.30 gap4.3: versión 46 → 47,translate_history Agregar columna favorita(Favoritos de traducción).
 * v1.0.19: versión 48 → 49,assistants Agregar resumen/useAssistantName/allowGroupChat 3 columnas
 *   (Assistant Finalización del campo;tags La agrupación ha sido realizada por tagsJson).
 * v2.x: versión 49 → 50,Crea una nueva tabla group_chat_memories(Aislamiento de memoria de chat grupal).
 * v1.0.x: versión 97 → 98,assistants Eliminar 3 columnas muertas backgroundUrl/backgroundOpacity/
 *   useGradientBackground(La función de imagen de fondo del asistente se ha eliminado por completo.,Sin lado del consumidor).
 *   v2.6.9: versión 118 → 119,Se agregaron tres tablas de mensajes activos.(pending_thought / proactive_outcome / proactive_relation).
 *   Mesa puramente nueva,El antiguo ProactiveMessageConfig permanece y no se migra.
 *   v2.6.8: versión 117 → 118,Tabla card_entities agregada(Conexión de tarjeta/portador de identidad y estado flotante entre aplicaciones).
 *   Mesa puramente nueva,No te muevas y ten cuidado;La tarjeta se actualiza de "message tail + card_data.json" a una entidad de primera clase(UUID clave primaria).
 *   Nota:SQLite 3.35 Solo admite DROP COLUMN,minSdk 26 El dispositivo antiguo no lo admite, así que ve"Crear nueva tabla → Mover datos →
 *   Cambiar nombre"camino (con MIGRATION_*_* consistente con la práctica histórica de group_chat_messages/skills en group_chat_messages/skills).
 *   v2.6.16: versión 120 → 121,messages Agregue voiceUri / voiceDurationMs dos columnas (mensajes de voz).
 *   Columna puramente nueva, la fila anterior voiceUri es NULL,voiceDurationMs es 0, se lee como"mensajes no de voz";
 *   La nueva versión del código no lee la semántica de las columnas antiguas y los datos antiguos permanecen sin cambios.
 *   versión 121 → 122, borra las cuatro mesas que dejó el viejo teléfono celular (momentos/diario) y isMiniPhone=1 conversaciones y sus mensajes.
 *   Esta función no está abierta a usuarios reales y no se conservan datos comerciales en la biblioteca;isMiniPhone Preservación de columnas (el antiguo SQLite no admite DROP COLUMN).
 */
@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        ArtifactEntity::class,
        AssistantEntity::class,
        LorebookEntity::class,
        QuickMessageEntity::class,
        PromptInjectionEntity::class,
        SkillEntity::class,
        FolderEntity::class,
        ScheduledTaskEntity::class,
        KnowledgeDocEntity::class,
        KnowledgeChunkEntity::class,
        ScheduledTaskExecutionEntity::class,
        GroupChatEntity::class,
        GroupChatMessageEntity::class,
        // v2.x: Aislamiento de memoria de chat grupal(independiente fact store,No contamina la memoria principal)
        GroupChatMemoryEntity::class,
        // v1.0.74: Registro de inspección activo
        io.zer0.muse.data.patrol.PatrolLogEntity::class,
        io.zer0.muse.data.stats.UsageRecordEntity::class,
        ExperienceEntity::class,
        // v1.107 Diseño redundante: Caché de estadísticas/registro de integridad/registro de copia de seguridad automática
        StatsCacheEntity::class,
        DbIntegrityLogEntity::class,
        AutoBackupLogEntity::class,
        // Phase 2 2B: tabla de hitos
        MilestoneEntity::class,
        AgentMessageEntity::class,
        // P2-4: tabla de registro de auditoría
        AuditLogEntity::class,
        // v1.133: Base de conocimiento múltiple + búsqueda híbrida FTS4
        // Nota:KnowledgeChunkFtsEntity No en esta lista — FTS4 vtable Creado por MIGRATION_38_39 SQL sin formato,
        // DAO Todos @SkipQueryVerification,Room No tengo conocimiento de la existencia de la mesa virtual.(Evite los informes de validación del esquema KSP vtable constructor failed)
        KnowledgeBaseEntity::class,
        // v2.4.6: Carpeta de la base de conocimientos(Contiene carpetas vacías) — Las carpetas se convierten en ciudadanos de primera clase que pueden existir de forma independiente.,Al mismo tiempo, también es una búsqueda de unidades de aislamiento.
        io.zer0.muse.data.knowledge.KnowledgeFolderEntity::class,
        // v1.0.15: envío de mensajes outbox(Cola de envío persistente,Recuperar mensajes no enviados después de que se finaliza el proceso)
        MessageOutboxEntity::class,
        // v1.0.17: Persistencia del historial de traducción
        TranslateHistoryEntity::class,
        // v1.0.17: registro rápido(Almacenamiento de archivos JSON alternativo + papelera de reciclaje)
        QuickNoteEntity::class,
        // P1-2: Worldbook Libro del mundo dinámico(permanente/palabras clave/regular/inyección profunda,independiente de Lorebook)
        io.zer0.muse.worldbook.WorldBookEntryEntity::class,
        // v1.0.53 Phase 1: Libro mayor de hilos de subagente(Versión persistente,Reemplazar la versión de memoria anterior SubagentThreadStore)
        io.zer0.muse.data.subagent.SubagentThreadEntity::class,
        // B5-01: Generación de puntos de control de streaming(Restaurar mensajes de interrupción después de que se finaliza el proceso)
        GenerationCheckpointEntity::class,
        // B5-02: El chat grupal genera un libro mayor(Una vez finalizado el proceso, presione el punto de interrupción para reproducir)
        GroupChatGenerationLedgerEntity::class,
        // Overnight rebuild: eventos de diálogo, giros, ruedas de herramientas,parts y cabeza de rama
        ConversationEventEntity::class,
        ConversationTurnEntity::class,
        ToolRoundEntity::class,
        MessagePartEntity::class,
        SessionBranchHeadEntity::class,
        io.zer0.muse.data.relationship.AssistantRelationshipEntity::class,
        io.zer0.muse.data.todo.SessionTodoEntity::class,
        // v2.6.8: entidad de tarjeta(Conexión de tarjeta/portador de identidad y estado flotante entre aplicaciones)
        io.zer0.muse.data.card.CardEntity::class,
        // v2.6.9: Reconstrucción activa de mensajes — Libro mayor de preocupaciones/libro mayor de resultados/intimidad en las relaciones
        io.zer0.muse.data.proactive.PendingThoughtEntity::class,
        io.zer0.muse.data.proactive.ProactiveOutcomeEntity::class,
        io.zer0.muse.data.proactive.ProactiveRelationEntity::class,
    ],
    version = 122,
    exportSchema = true,
)
@TypeConverters(QuickNoteConverters::class)
abstract class MuseDb : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun messageFtsDao(): MessageFtsDao
    abstract fun artifactDao(): ArtifactDao
    abstract fun assistantDao(): AssistantDao
    abstract fun lorebookDao(): LorebookDao
    abstract fun quickMessageDao(): QuickMessageDao
    abstract fun promptInjectionDao(): PromptInjectionDao
    abstract fun skillDao(): SkillDao
    abstract fun folderDao(): FolderDao
    abstract fun scheduledTaskDao(): ScheduledTaskDao
    abstract fun knowledgeDocDao(): KnowledgeDocDao
    abstract fun knowledgeChunkDao(): KnowledgeChunkDao

    // v1.133: Múltiples bases de conocimiento + FTS4
    abstract fun knowledgeBaseDao(): KnowledgeBaseDao

    // v2.4.6: Carpeta de la base de conocimientos
    abstract fun knowledgeFolderDao(): io.zer0.muse.data.knowledge.KnowledgeFolderDao
    abstract fun knowledgeChunkFtsDao(): KnowledgeChunkFtsDao
    abstract fun scheduledTaskExecutionDao(): ScheduledTaskExecutionDao
    abstract fun groupChatDao(): GroupChatDao
    abstract fun groupChatMessageDao(): GroupChatMessageDao

    // v2.x: Aislamiento de memoria de chat grupal(independiente fact store,No contamina la memoria principal)
    abstract fun groupChatMemoryDao(): GroupChatMemoryDao

    abstract fun patrolLogDao(): io.zer0.muse.data.patrol.PatrolLogDao

    /** Canalización de uso (una fila por solicitud de modelo). */
    abstract fun usageRecordDao(): io.zer0.muse.data.stats.UsageRecordDao

    // v1.98: Biblioteca de experiencias
    abstract fun experienceDao(): ExperienceDao

    // Phase 2 2B: hito
    abstract fun milestoneDao(): MilestoneDao
    abstract fun agentMessageDao(): AgentMessageDao

    // P2-4: Registro de auditoría
    abstract fun auditLogDao(): AuditLogDao

    // v1.134 P1-1/P1-2: Requerido para acceder a componentes huérfanos DAO
    abstract fun autoBackupLogDao(): AutoBackupLogDao
    abstract fun statsCacheDao(): StatsCacheDao

    // P3-3: Verificación de integridad de la base de datos DAO(IntegrityChecker uso)
    abstract fun integrityLogDao(): DbIntegrityLogDao

    // v1.0.15: envío de mensajes outbox DAO
    abstract fun messageOutboxDao(): MessageOutboxDao

    // v1.0.17: Historial de traducción DAO
    abstract fun translateHistoryDao(): TranslateHistoryDao

    // v1.0.17: registro rápido DAO(Almacenamiento de archivos JSON alternativo + papelera de reciclaje)
    abstract fun quickNoteDao(): QuickNoteDao

    // P1-2: Worldbook Libro del mundo dinámico DAO
    abstract fun worldBookDao(): io.zer0.muse.worldbook.WorldBookDao

    // v1.0.53 Phase 1: hilo de agente secundario DAO(Versión persistente)
    abstract fun subagentThreadDao(): io.zer0.muse.data.subagent.SubagentThreadDao

    // B5-01: Generación de puntos de control de streaming DAO
    abstract fun generationCheckpointDao(): GenerationCheckpointDao

    // B5-02: El chat grupal genera un libro mayor DAO
    abstract fun groupChatGenerationLedgerDao(): io.zer0.muse.data.groupchat.GroupChatGenerationLedgerDao

    // Overnight rebuild: Eventos ocultos de diálogo y nuevo almacenamiento oculto
    abstract fun conversationEventDao(): ConversationEventDao
    abstract fun conversationTurnDao(): ConversationTurnDao
    abstract fun toolRoundDao(): ToolRoundDao
    abstract fun messagePartDao(): MessagePartDao
    abstract fun sessionBranchHeadDao(): SessionBranchHeadDao

    /** v2.6.7(P1-1): Estado de la relación del personaje (intimidad/nivel de deseo/interacciones recientes). */
    abstract fun assistantRelationshipDao(): io.zer0.muse.data.relationship.AssistantRelationshipDao

    /** v2.6.7: Persistencia de tareas pendientes de sesión (libro mayor entre sesiones). */
    abstract fun sessionTodoDao(): io.zer0.muse.data.todo.SessionTodoDao

    /** v2.6.8: Entidad de tarjeta (identidad y contenido compartido por Dock/Hover). */
    abstract fun cardEntityDao(): io.zer0.muse.data.card.CardEntityDao

    /** v2.6.9: Preocuparse por el libro de contabilidad (“Tengo algo que decirle a esta persona”). */
    abstract fun pendingThoughtDao(): io.zer0.muse.data.proactive.PendingThoughtDao

    /** v2.6.9: Preocupado por el puerto de transferencia de estado del libro mayor (ya dicho/Caducado/rendirse). */
    abstract fun pendingThoughtWriteDao(): io.zer0.muse.data.proactive.PendingThoughtWriteDao

    /** v2.6.9: Cuenta de resultados de mensajes activa (hacer clic/responder/tachar). */
    abstract fun proactiveOutcomeDao(): io.zer0.muse.data.proactive.ProactiveOutcomeDao

    /** v2.6.9: Intimidad de la relación y presupuesto del día. */
    abstract fun proactiveRelationDao(): io.zer0.muse.data.proactive.ProactiveRelationDao

    companion object {
        @Volatile
        private var dbInstance: MuseDb? = null

        /** v1.0.53: FTS Crea un bloqueo global bajo el capó — La conexión múltiple onOpen serializa la creación de tablas cuando es simultánea. */
        private val FTS_CREATE_LOCK = Any()

        /** R-DB-02: Indicador de error de FTS en proceso,Establecer después de que falla la creación de la tabla,para la autocuración/UI Controlar. */
        @Volatile
        private var knowledgeFtsBroken = false

        /** R-DB-02: Borrar tabla de sombra residual/Reconstruya Knowledge_chunks_fts después de la tabla principal. */
        fun repairKnowledgeChunkFtsTable() {
            synchronized(FTS_CREATE_LOCK) {
                val db = dbInstance?.openHelper?.writableDatabase
                    ?: error("MuseDb not initialized")
                rebuildKnowledgeChunkFtsTable(db)
                knowledgeFtsBroken = false
                io.zer0.common.Logger.i("MuseDb", "knowledge_chunks_fts reconstruido")
            }
        }

        private fun dropKnowledgeFtsTables(db: SupportSQLiteDatabase) {
            val names = mutableListOf<String>()
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'knowledge_chunks_fts%'")
                .use { cursor ->
                    while (cursor.moveToNext()) names.add(cursor.getString(0))
                }
            names.forEach { name ->
                db.execSQL("DROP TABLE IF EXISTS `$name`")
            }
        }

        private fun createKnowledgeChunkFtsTable(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS knowledge_chunks_fts " +
                    "USING fts4(chunkId, doc_id, text_content)",
            )
        }

        /**
         * Reconstruya la base de conocimientos FTS4 y presione [MessageFtsManager] Rellenar ngram.
         *
         * FTS4 El tokenizador predeterminado tratará los caracteres chinos consecutivos como uno solo. token,Escribir el texto original directamente hará que los chinos
         * Error en la búsqueda de subcadenas;La reconstrucción debe utilizar el mismo texto normalizado que la escritura del nuevo índice.
         */
        private fun rebuildKnowledgeChunkFtsTable(db: SupportSQLiteDatabase) {
            db.beginTransaction()
            try {
                dropKnowledgeFtsTables(db)
                createKnowledgeChunkFtsTable(db)
                val insert = db.compileStatement(
                    "INSERT INTO knowledge_chunks_fts(chunkId, doc_id, text_content) VALUES (?, ?, ?)",
                )
                db.query("SELECT id, doc_id, content FROM knowledge_chunks").use { cursor ->
                    while (cursor.moveToNext()) {
                        insert.clearBindings()
                        insert.bindString(1, cursor.getString(0))
                        insert.bindString(2, cursor.getString(1))
                        insert.bindString(3, MessageFtsManager.toNgram(cursor.getString(2)))
                        insert.executeInsert()
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        /** R-DB-05: Detectar si el SQLite actual es compatible FTS5(temp sonda de mesa). */
        private fun fts5Available(db: SupportSQLiteDatabase): Boolean {
            val ok = runCatching {
                db.execSQL("DROP TABLE IF EXISTS temp.muse_fts5_probe")
                db.execSQL("CREATE VIRTUAL TABLE temp.muse_fts5_probe USING fts5(x)")
                true
            }.getOrDefault(false)
            runCatching { db.execSQL("DROP TABLE IF EXISTS temp.muse_fts5_probe") }
            return ok
        }

        private fun dropMessageFtsTables(db: SupportSQLiteDatabase) {
            val names = mutableListOf<String>()
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'messages_fts%'")
                .use { cursor -> while (cursor.moveToNext()) names.add(cursor.getString(0)) }
            names.forEach { name -> db.execSQL("DROP TABLE IF EXISTS `$name`") }
            val triggers = mutableListOf<String>()
            db.query("SELECT name FROM sqlite_master WHERE type='trigger' AND name LIKE 'messages_fts_%'")
                .use { cursor -> while (cursor.moveToNext()) triggers.add(cursor.getString(0)) }
            triggers.forEach { name -> db.execSQL("DROP TRIGGER IF EXISTS `$name`") }
        }

        private fun createMessageFtsTable(db: SupportSQLiteDatabase, useFts5: Boolean) {
            db.execSQL(MessageFtsDdl.createSql(useFts5))
            if (useFts5) {
                MessageFtsDdl.fts5TriggerSqls.forEach { db.execSQL(it) }
            }
            MessageFtsRuntime.useFts5 = useFts5
        }

        private fun detectMessageFtsMode(db: SupportSQLiteDatabase): Boolean {
            val sql = db.query("SELECT sql FROM sqlite_master WHERE type='table' AND name='messages_fts'").use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: return false
            return sql.contains("fts5", ignoreCase = true)
        }

        /** R-DB-05: 76→77 Migrar — reconstruir messages_fts,Prioridad FTS5,Recurrir a FTS4 en caso de error. */
        fun migrate76To77(): Migration = object : Migration(76, 77) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val useFts5 = fts5Available(db)
                dropMessageFtsTables(db)
                createMessageFtsTable(db, useFts5)
            }
        }

        /** v1.0.72: messages Agregar la columna tool_call_info_json(Persistencia de la tarjeta de llamada de herramientas). */
        val MIGRATION_77_78 = object : Migration(77, 78) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN toolCallInfoJson TEXT DEFAULT NULL")
            }
        }

        /** v1.0.72: sessions Agregar columna ignore_memory(Esta sesión no se refiere a la memoria.). */
        val MIGRATION_78_79 = object : Migration(78, 79) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN ignoreMemory INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v1.0.72: AI Lista de momentos(Actualizaciones + Comentarios).
         * Nota: El SQL para crear la tabla debe ser exactamente igual a la tabla generada por Room(mood sin valor predeterminado,No crear índices adicionales),
         * De lo contrario, la verificación de la migración en la máquina real falla y falla. */
        val MIGRATION_79_80 = object : Migration(79, 80) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS ai_moments (
                        id TEXT NOT NULL PRIMARY KEY,
                        content TEXT NOT NULL,
                        type TEXT NOT NULL DEFAULT 'life',
                        mood TEXT,
                        likes INTEGER NOT NULL DEFAULT 0,
                        likedByUser INTEGER NOT NULL DEFAULT 0,
                        source TEXT NOT NULL DEFAULT 'scheduled',
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )""",
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS ai_moment_comments (
                        id TEXT NOT NULL PRIMARY KEY,
                        momentId TEXT NOT NULL,
                        sender TEXT NOT NULL,
                        content TEXT NOT NULL,
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )""",
                )
            }
        }

        /** v1.0.73: Reparar el historial de tablas malas. Versión publicada(72/Versión intermedia)Hay dos tipos de diferencias en los ai_moments creados.:
         * 1) índice residual idx_moments_created / idx_moment_comments_moment
         * 2) mood Los valores predeterminados de las columnas no son uniformes(Algunos no tienen valor predeterminado 'undefined',Algunos DEFAULT NULL se leen como 'NULL')
         * Planificar: Reconstruir la tabla ai_moments,Unificado para ser completamente consistente con la Entidad schema(Sin estado de ánimo Predeterminado, sin índice),Todos los datos se conservan. */
        val MIGRATION_80_81 = object : Migration(80, 81) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS idx_moments_created")
                db.execSQL("DROP INDEX IF EXISTS idx_moment_comments_moment")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS ai_moments_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        content TEXT NOT NULL,
                        type TEXT NOT NULL DEFAULT 'life',
                        mood TEXT,
                        likes INTEGER NOT NULL DEFAULT 0,
                        likedByUser INTEGER NOT NULL DEFAULT 0,
                        source TEXT NOT NULL DEFAULT 'scheduled',
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )""",
                )
                db.execSQL(
                    """INSERT INTO ai_moments_new (id, content, type, mood, likes, likedByUser, source, createdAt)
                        SELECT id, content, type, mood, likes, likedByUser, source, createdAt FROM ai_moments""",
                )
                db.execSQL("DROP TABLE IF EXISTS ai_moments")
                db.execSQL("ALTER TABLE ai_moments_new RENAME TO ai_moments")
            }
        }

        /** v1.0.73: Moments admite la publicación de usuarios + coincidencia de imágenes por IA — ai_moments Agregue la columna remitente/imageUrl.
         * Nota: ponerse 80→81 Después de la reconstrucción,unificar ADD COLUMN,Asegúrese de que el esquema final de cualquier ruta de migración sea coherente. */
        val MIGRATION_81_82 = object : Migration(81, 82) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ai_moments ADD COLUMN senderType TEXT NOT NULL DEFAULT 'assistant'")
                db.execSQL("ALTER TABLE ai_moments ADD COLUMN senderName TEXT NOT NULL DEFAULT 'Muse'")
                db.execSQL("ALTER TABLE ai_moments ADD COLUMN senderAvatar TEXT")
                db.execSQL("ALTER TABLE ai_moments ADD COLUMN imageUrl TEXT")
            }
        }

        /** v1.0.73: Círculo de amigos multiasistente — ai_moments agregar senderId;Comentario más identidad del remitente;
         * Crea una nueva tabla ai_moment_likes(Usuario + asistente como el uno al otro). */
        val MIGRATION_82_83 = object : Migration(82, 83) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ai_moments ADD COLUMN senderId TEXT")
                db.execSQL("ALTER TABLE ai_moment_comments ADD COLUMN senderId TEXT")
                db.execSQL("ALTER TABLE ai_moment_comments ADD COLUMN senderName TEXT")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS ai_moment_likes (
                        momentId TEXT NOT NULL,
                        likerType TEXT NOT NULL,
                        likerId TEXT NOT NULL,
                        likerName TEXT NOT NULL,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(momentId, likerType, likerId)
                    )""",
                )
            }
        }

        /** v1.0.73: 9 Gonggeduotu — ai_moments agregar imagesJson(JSON matriz). */
        val MIGRATION_83_84 = object : Migration(83, 84) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ai_moments ADD COLUMN imagesJson TEXT NOT NULL DEFAULT '[]'")
            }
        }

        /** v1.0.74: AI diario — Crear una nueva tabla ai_diaries(Fecha clave principal). */
        val MIGRATION_84_85 = object : Migration(84, 85) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS ai_diaries (
                        date TEXT NOT NULL PRIMARY KEY,
                        content TEXT NOT NULL,
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )""",
                )
            }
        }

        /** v1.0.74: Registro de inspección activo — Cree una nueva tabla Patrol_logs. */
        val MIGRATION_85_86 = object : Migration(85, 86) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS patrol_logs (
                        id TEXT NOT NULL PRIMARY KEY,
                        timestamp INTEGER NOT NULL,
                        action TEXT NOT NULL,
                        summary TEXT NOT NULL
                    )""",
                )
            }
        }

        /**
         * v1.0.74 fix (auditoría CRITICAL-0.1): assistants Capacidades faltantes en la tabla Migración de columnas Json.
         * AssistantEntity Nuevo capabilitiesJson(v1.200) Ya entré Room schema,
         * Pero la cadena migratoria no corresponde a ALTER,La verificación del esquema de sala colapsará cuando los usuarios antiguos actualicen
         * "Migration didn't properly handle: assistants".
         */
        val MIGRATION_86_87 = object : Migration(86, 87) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // schema 86.json La tabla de asistentes ya contiene capabilitiesJson(Exportar desde entidad),
                // Sin embargo, la antigua base de datos actualizada a través de la cadena de migración real no;Primero verifique si la columna existe.,Evite caídas de columnas duplicadas.
                var hasCapabilities = false
                db.query("PRAGMA table_info(assistants)").use { cursor ->
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1) == "capabilitiesJson") {
                            hasCapabilities = true
                            break
                        }
                    }
                }
                if (!hasCapabilities) {
                    db.execSQL("ALTER TABLE assistants ADD COLUMN capabilitiesJson TEXT NOT NULL DEFAULT '[]'")
                }
            }
        }

        /**
         * Reparación de auditoría (S-02): messages Columna videoFileUri del complemento de tabla(Persistencia de resultados de generación de video).
         *
         * Anteriormente, el videoFileUri de generate_video solo existía en la memoria. UIMessage,
         * Reiniciar/El vídeo se pierde permanentemente después de cambiar de página.;MessageEntity Agregue nuevos campos y luego migrelos.
         */
        val MIGRATION_87_88 = object : Migration(87, 88) {
            override fun migrate(db: SupportSQLiteDatabase) {
                var hasVideoFileUri = false
                db.query("PRAGMA table_info(messages)").use { cursor ->
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1) == "videoFileUri") {
                            hasVideoFileUri = true
                            break
                        }
                    }
                }
                if (!hasVideoFileUri) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN videoFileUri TEXT DEFAULT NULL")
                }
            }
        }

        /**
         * Revisión de corrección (2.0 B-27): MIGRATION_88_89 — Corregir dos tipos de deriva del esquema histórico.
         *
         * 1. index_scheduled_tasks_enabled_next_run_at Solo creado el 21_22,Tablas actualizadas desde v22+
         *    El índice nunca se obtuvo(getDueTasks No hay índice para consultar.),Prueba de migración 22..54 Exposición a la matriz.
         *    Reconstrucción incondicional(IF NOT EXISTS idempotente,Los usuarios de v21 actualizados con 21_22 no se ven afectados).
         * 2. messages.imageUrlsJson Deriva de la declaración del valor predeterminado: v55(1.0.30) La era Entidad se agrega a esta columna.
         *    @ColumnInfo(defaultValue="[]"),Pero SQLite ALTER no puede cambiar el valor predeterminado de la columna y la tabla no se reconstruye en ese momento. →
         *    de <55 La columna de la tabla actualizada siempre ha estado vacía. DEFAULT '[]'(Pruebas de migración "Migration didn't properly
         *    handle: messages"). No hay problema al correr(Room El SQL generado siempre escribe columnas explícitamente),Sin embargo, las declaraciones inconsistentes
         *    Activando continuamente el error de verificación de MigrationTestHelper,e impactar las herramientas futuras que dependen de la coherencia del esquema.
         *    Sólo si falta la columna DEFAULT '[]' Reconstruir la tabla de mensajes cuando(55+ El punto de partida es consistente.,saltar directamente,
         *    Evite la duplicación innecesaria de bibliotecas grandes);Reconstruir las definiciones de columnas exactamente iguales a las de la Entidad actual(Con FK y 4 índices).
         * MIGRATION_29_30 Ya sincronizado DEFAULT '[]'(futuro <30 La ruta de actualización es directamente consistente).
         */
        val MIGRATION_88_89 = object : Migration(88, 89) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Índice de tareas programadas incondicionalmente completo(idempotente)
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_scheduled_tasks_enabled_next_run_at` " +
                        "ON `scheduled_tasks` (`enabled`, `next_run_at`)",
                )
                // 2. Reconstrucción condicional messages(imageUrlsJson desaparecido DEFAULT '[]' tiempo)
                var imageUrlsDefault = ""
                db.query("PRAGMA table_info(messages)").use { cursor ->
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1) == "imageUrlsJson") {
                            // SQLite dflt_value Devolver literal SQL(con comillas,Como por ejemplo '[]')
                            imageUrlsDefault = cursor.getString(4)?.trim('\'') ?: ""
                            break
                        }
                    }
                }
                if (imageUrlsDefault == "[]") return
                // Reconstruir mesa: Las definiciones de columnas son exactamente las mismas que las de Entidad.(30 columna + FK),Luego reconstruya 4 índices
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `messages_new` (
                        `id` TEXT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `reasoning` TEXT,
                        `thinkingSignature` TEXT DEFAULT NULL,
                        `thinkingEncryptedContent` TEXT DEFAULT NULL,
                        `modelId` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `imageUrlsJson` TEXT NOT NULL DEFAULT '[]',
                        `favorite` INTEGER NOT NULL DEFAULT 0,
                        `favoriteTag` TEXT DEFAULT NULL,
                        `citationUrlsJson` TEXT NOT NULL DEFAULT '[]',
                        `ragCitationsJson` TEXT NOT NULL DEFAULT '[]',
                        `imageBase64Json` TEXT NOT NULL DEFAULT '[]',
                        `videoFileUri` TEXT DEFAULT NULL,
                        `artifactIdsJson` TEXT NOT NULL DEFAULT '[]',
                        `moodSkin` TEXT DEFAULT NULL,
                        `mood` TEXT DEFAULT NULL,
                        `reflection` TEXT DEFAULT NULL,
                        `contentLength` INTEGER NOT NULL DEFAULT 0,
                        `deletedAt` INTEGER DEFAULT NULL,
                        `reaction` TEXT DEFAULT NULL,
                        `variantGroupId` TEXT DEFAULT NULL,
                        `variantIndex` INTEGER NOT NULL DEFAULT 0,
                        `variantCount` INTEGER NOT NULL DEFAULT 1,
                        `parentGroupId` TEXT DEFAULT NULL,
                        `attachmentsJson` TEXT NOT NULL DEFAULT '[]',
                        `toolCallInfoJson` TEXT DEFAULT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `messages_new` (
                        `id`,`sessionId`,`role`,`content`,`reasoning`,`thinkingSignature`,
                        `thinkingEncryptedContent`,`modelId`,`createdAt`,`imageUrlsJson`,
                        `favorite`,`favoriteTag`,`citationUrlsJson`,`ragCitationsJson`,
                        `imageBase64Json`,`videoFileUri`,`artifactIdsJson`,`moodSkin`,`mood`,
                        `reflection`,`contentLength`,`deletedAt`,`reaction`,`variantGroupId`,
                        `variantIndex`,`variantCount`,`parentGroupId`,`attachmentsJson`,`toolCallInfoJson`
                    )
                    SELECT
                        `id`,`sessionId`,`role`,`content`,`reasoning`,`thinkingSignature`,
                        `thinkingEncryptedContent`,`modelId`,`createdAt`,`imageUrlsJson`,
                        `favorite`,`favoriteTag`,`citationUrlsJson`,`ragCitationsJson`,
                        `imageBase64Json`,`videoFileUri`,`artifactIdsJson`,`moodSkin`,`mood`,
                        `reflection`,`contentLength`,`deletedAt`,`reaction`,`variantGroupId`,
                        `variantIndex`,`variantCount`,`parentGroupId`,`attachmentsJson`,`toolCallInfoJson`
                    FROM `messages`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `messages`")
                db.execSQL("ALTER TABLE `messages_new` RENAME TO `messages`")
                // Índice de reconstrucción(DROP TABLE Todos los índices serán eliminados.)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_sessionId` ON `messages` (`sessionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_role` ON `messages` (`role`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_sessionId_createdAt` ON `messages` (`sessionId`, `createdAt`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_messages_sessionId_createdAt_role` ON `messages` (`sessionId`, `createdAt`, `role`)",
                )
            }
        }

        /**
         * A5: MIGRATION_89_90 — messages El complemento de tabla genera columnas de metadatos.
         *
         * Capa emergente de información del mensaje(modelo/Consume mucho tiempo/ficha)Requiere un uso medido del proveedor de persistencia y requiere mucho tiempo:
         * durationMs(Tiempo total necesario para generar)/promptTokens/completionTokens/reasoningTokens/cachedTokens.
         * Utilice sureMessageColumns para finalización idempotente(Mismo asistente que 60_61/63_64,Se omiten las columnas existentes),
         * Compatible con la ruta de la tabla de reconstrucción 88_89(Columna perdida después de la reconstrucción,Vuelva a agregar aquí).
         */
        val MIGRATION_89_90 = object : Migration(89, 90) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureMessageColumns(db)
            }
        }

        /**
         * H11: MIGRATION_90_91 — messages La tabla complementa la columna del mensaje de origen de la traducción.
         *
         * mensaje de traducción(Mensaje de ASISTENTE independiente)Apunte al mensaje fuente traducido a través de TranslationSourceId,
         * UI proporcionar"Ver texto original"Control de plegado. Utilice sureMessageColumns para la finalización idempotente.
         */
        val MIGRATION_90_91 = object : Migration(90, 91) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureMessageColumns(db)
            }
        }

        /**
         * v92: MIGRATION_91_92 — messages Tabla más columna de secuencia monótona seq(Clave primaria estable de clasificación de mensajes).
         *
         * - ADD COLUMN seq INTEGER NOT NULL DEFAULT 0(SQLite O(1) Operaciones de metadatos,sin reescribir)
         * - Rellenar datos antiguos seq = rowid: rowid es una secuencia estable de orden de inserción,Después del reabastecimiento, los mensajes antiguos se organizan en su orden original.
         * - El índice idx_messages_sessionId_seq acelera la clasificación por secuencia dentro de una sesión
         */
        val MIGRATION_91_92 = object : Migration(91, 92) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN seq INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE messages SET seq = rowid WHERE seq = 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_sessionId_seq ON messages(sessionId, seq)")
            }
        }

        /** v92→v93: Cree nuevos eventos de diálogo, giros y ruedas de herramientas sin sobrescribir mensajes antiguos. */
        val MIGRATION_92_93 = object : Migration(92, 93) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS conversation_turns (
                        turnId TEXT NOT NULL PRIMARY KEY,
                        sessionId TEXT NOT NULL,
                        inputUserMessageId TEXT NOT NULL,
                        assistantMessageId TEXT NOT NULL,
                        phase TEXT NOT NULL,
                        streamId TEXT DEFAULT NULL,
                        generationSerial INTEGER NOT NULL DEFAULT 0,
                        startedAt INTEGER NOT NULL,
                        finishedAt INTEGER DEFAULT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(sessionId) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_conversation_turns_session_started ON conversation_turns(sessionId, startedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_conversation_turns_session_phase ON conversation_turns(sessionId, phase)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_conversation_turns_assistant ON conversation_turns(assistantMessageId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS conversation_events (
                        sessionId TEXT NOT NULL,
                        eventSeq INTEGER NOT NULL,
                        eventId TEXT NOT NULL,
                        turnId TEXT NOT NULL,
                        streamId TEXT DEFAULT NULL,
                        type TEXT NOT NULL,
                        payloadJson TEXT NOT NULL,
                        payloadHash TEXT NOT NULL,
                        payloadLength INTEGER NOT NULL,
                        provider TEXT DEFAULT NULL,
                        modelId TEXT DEFAULT NULL,
                        sequenceInStream INTEGER NOT NULL DEFAULT 0,
                        generationSerial INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        PRIMARY KEY(sessionId, eventSeq),
                        FOREIGN KEY(sessionId) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_conversation_events_session_turn_seq " +
                        "ON conversation_events(sessionId, turnId, eventSeq)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_conversation_events_turn_stream ON conversation_events(turnId, streamId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_conversation_events_event_id ON conversation_events(eventId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tool_rounds (
                        id TEXT NOT NULL PRIMARY KEY,
                        turnId TEXT NOT NULL,
                        roundIndex INTEGER NOT NULL,
                        toolCallId TEXT NOT NULL,
                        toolName TEXT NOT NULL,
                        argsJson TEXT NOT NULL,
                        resultJson TEXT DEFAULT NULL,
                        status TEXT NOT NULL,
                        startedAt INTEGER NOT NULL,
                        finishedAt INTEGER DEFAULT NULL,
                        errorDetail TEXT DEFAULT NULL,
                        FOREIGN KEY(turnId) REFERENCES conversation_turns(turnId) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_tool_rounds_turn_round ON tool_rounds(turnId, roundIndex)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_tool_rounds_call ON tool_rounds(toolCallId)")
            }
        }

        /** v93→v94: Agregue un orden de confirmación compatible y una referencia explícita al mensaje principal, y establezca una tabla de encabezados de rama. */
        val MIGRATION_93_94 = object : Migration(93, 94) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN commitSeq INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN parentMessageId TEXT DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_sessionId_commitSeq ON messages(sessionId, commitSeq)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_parentMessageId ON messages(parentMessageId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS session_branch_heads (
                        sessionId TEXT NOT NULL PRIMARY KEY,
                        headMessageId TEXT DEFAULT NULL,
                        nextCommitSeq INTEGER NOT NULL DEFAULT 1,
                        projectionVersion INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(sessionId) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
            }
        }

        /** v94→v95: Cree nuevas partes de mensajes estructurados y no rellene los mensajes existentes. */
        val MIGRATION_94_95 = object : Migration(94, 95) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS message_parts (
                        messageId TEXT NOT NULL,
                        partIndex INTEGER NOT NULL,
                        kind TEXT NOT NULL,
                        text TEXT NOT NULL,
                        metadataJson TEXT NOT NULL DEFAULT '{}',
                        createdAt INTEGER NOT NULL,
                        PRIMARY KEY(messageId, partIndex),
                        FOREIGN KEY(messageId) REFERENCES messages(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_message_parts_message_index ON message_parts(messageId, partIndex)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_message_parts_kind ON message_parts(kind)")
            }
        }

        /** v95→v96: sessions/Índice de complemento de eliminación de cascada de mensajes(auditoría D-P2,Eliminar el escaneo completo de la tabla). */
        val MIGRATION_95_96 = object : Migration(95, 96) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_parentGroupId ON messages(parentGroupId)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_sessions_assistantId_updatedAt " +
                        "ON sessions(assistantId, updatedAt)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_sessions_deletedAt_archived_updatedAt " +
                        "ON sessions(deletedAt, archived, updatedAt)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_sessions_parentSessionId ON sessions(parentSessionId)")
            }
        }

        /** v96→v97: U-26 Asistente habilitado/desactivar — assistants Agregar columna habilitada a la tabla(Habilitado por defecto). */
        val MIGRATION_96_97 = object : Migration(96, 97) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE assistants ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1")
            }
        }

        /** v97→v98: v1.138 Pequeño espacio para mensajes privados en teléfonos celulares. — sessions Agregue la columna isMiniPhone.
         *
         * La primera página del pequeño teléfono celular se cambia a"Espacio de mensajes privados",Mostrar solo isMiniPhone=true conversación(AI Mensaje privado activo + crea una nueva conversación en tu teléfono celular).
         * Las sesiones existentes no se ven afectadas.(isMiniPhone Predeterminado 0).
         */
        val MIGRATION_97_98 = object : Migration(97, 98) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // idempotente:64→72 La migración de segmentos se completará con anticipación a través de asegurarSessionColumns isMiniPhone(La lista de columnas ya contiene esta columna.),
                // Si no hay ningún ALTER condicional aquí, la columna duplicada fallará. —— Solo se agrega si falta.
                val existing = mutableSetOf<String>()
                db.query("PRAGMA table_info(sessions)").use { cursor ->
                    while (cursor.moveToNext()) existing.add(cursor.getString(1))
                }
                if ("isMiniPhone" !in existing) {
                    db.execSQL("ALTER TABLE sessions ADD COLUMN isMiniPhone INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        /**
         * v98→v99: Complete la columna programada_tasks.created_by.
         *
         * Esta columna ha estado disponible desde la v1.0.18. @Entity(defaultValue "user"),Pero construye una mesa(9→10)con todos los posteriores
         * ALTER Ni siquiera lo traje —— La base de datos antigua real migrada desde la creación de tablas manuscritas v9 carece de esta columna.,Room Verificación del esquema después de la migración
         * ("Migration didn't properly handle: scheduled_tasks") chocará.
         * Después de una instalación nueva, ejecute createAllTables y funcionará normalmente.,Por lo tanto, solo se expone en la ruta de actualización.
         * Actualice la versión a 99 en lugar de migrar desde la versión anterior:Las bibliotecas que se detuvieron en la versión 98 ya no se ejecutarán v97→98,Sólo puedo confiar en los nuevos. v98→99 Constituir.
         * idempotente:PRAGMA juzgado que existe más tarde ADD,Evite columnas duplicadas.
         */
        val MIGRATION_98_99 = object : Migration(98, 99) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val existing = mutableSetOf<String>()
                db.query("PRAGMA table_info(scheduled_tasks)").use { cursor ->
                    while (cursor.moveToNext()) existing.add(cursor.getString(1))
                }
                if ("created_by" !in existing) {
                    db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN created_by TEXT NOT NULL DEFAULT 'user'")
                }
            }
        }

        /**
         * v99→v100: Archivo de chat grupal — group_chats Agregue la columna isArchived a la tabla(Predeterminado 0).
         *
         * El chat grupal se oculta de la lista principal después de archivarse,Se puede ver en la lista de archivos./restaurar;Los datos antiguos no se archivan de forma predeterminada.
         * idempotente:PRAGMA juzgado que existe más tarde ADD,Evite columnas duplicadas.
         */
        val MIGRATION_99_100 = object : Migration(99, 100) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val existing = mutableSetOf<String>()
                db.query("PRAGMA table_info(group_chats)").use { cursor ->
                    while (cursor.moveToNext()) existing.add(cursor.getString(1))
                }
                if ("isArchived" !in existing) {
                    db.execSQL("ALTER TABLE group_chats ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        /**
         * v100→v101: Rango de duración del discurso de chat grupal — group_chats Agregar la columna de respuestaLongitudMode a la tabla(Predeterminado 'standard').
         *
         * brief/standard/detailed Tres niveles para controlar la duración de los discursos de los miembros;El antiguo chat grupal tiene como valor predeterminado el archivo estándar.
         * idempotente:PRAGMA juzgado que existe más tarde ADD,Evite columnas duplicadas.
         */
        val MIGRATION_100_101 = object : Migration(100, 101) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val existing = mutableSetOf<String>()
                db.query("PRAGMA table_info(group_chats)").use { cursor ->
                    while (cursor.moveToNext()) existing.add(cursor.getString(1))
                }
                if ("replyLengthMode" !in existing) {
                    db.execSQL("ALTER TABLE group_chats ADD COLUMN replyLengthMode TEXT NOT NULL DEFAULT 'standard'")
                }
            }
        }

        /**
         * v101→v102: Miembro observador del chat grupal — group_chats Agregar la columna observer_ids_json a la tabla(Predeterminado '[]').
         *
         * Los observadores no participan en la rotación predeterminada.(Mirar y discutir en silencio.),Pero permanezca en la lista de miembros.
         * idempotente:PRAGMA juzgado que existe más tarde ADD,Evite columnas duplicadas.
         */
        val MIGRATION_101_102 = object : Migration(101, 102) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val existing = mutableSetOf<String>()
                db.query("PRAGMA table_info(group_chats)").use { cursor ->
                    while (cursor.moveToNext()) existing.add(cursor.getString(1))
                }
                if ("observer_ids_json" !in existing) {
                    db.execSQL("ALTER TABLE group_chats ADD COLUMN observer_ids_json TEXT NOT NULL DEFAULT '[]'")
                }
            }
        }

        /**
         * v2.x: worldbook_entries Agregar columna de palabra completa(coincidencia de palabras completas,Predeterminado false).
         * idempotente:PRAGMA AGREGAR después de comprobar que existe.
         */
        val MIGRATION_102_103 = object : Migration(102, 103) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val existing = mutableSetOf<String>()
                db.query("PRAGMA table_info(worldbook_entries)").use { cursor ->
                    while (cursor.moveToNext()) existing.add(cursor.getString(1))
                }
                if ("wholeWord" !in existing) {
                    db.execSQL("ALTER TABLE worldbook_entries ADD COLUMN wholeWord INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        /**
         * v103→v104: group_chat_messages Agregar la columna videoFileUri(Los resultados de la generación de videos del chat grupal se conservan).
         *
         * Chatea con Dan messages.videoFileUri(Migrar 87→88)Alineación. Antes no había ninguna columna de vídeo en el chat grupal.,
         * generate_video Solo se puede bloquear mediante la política de la herramienta de chat grupal;Una vez agregada la cola, el video generado en el chat grupal se puede guardar y renderizar junto con el mensaje.
         * idempotente:PRAGMA juzgado que existe más tarde ADD,Evite columnas duplicadas.
         */
        val MIGRATION_103_104 = object : Migration(103, 104) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val existing = mutableSetOf<String>()
                db.query("PRAGMA table_info(group_chat_messages)").use { cursor ->
                    while (cursor.moveToNext()) existing.add(cursor.getString(1))
                }
                if ("videoFileUri" !in existing) {
                    db.execSQL("ALTER TABLE group_chat_messages ADD COLUMN videoFileUri TEXT DEFAULT NULL")
                }
            }
        }

        /**
         * v104→v105: sessions Agregar columna pendiente de calentamiento(Importar etiquetas de calentamiento,Predeterminado 0).
         *
         * Configuración de sesión de recuperación de copia de seguridad/importación externa: 1: la primera ronda de diálogo utiliza el historial completo + presupuesto de token para construir el contexto una vez
         * (ver WarmupHistory),Borrado por el repositorio de sesiones después de una respuesta exitosa. idempotente:PRAGMA AGREGAR después de comprobar que existe.
         */
        val MIGRATION_104_105 = object : Migration(104, 105) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val existing = mutableSetOf<String>()
                db.query("PRAGMA table_info(sessions)").use { cursor ->
                    while (cursor.moveToNext()) existing.add(cursor.getString(1))
                }
                if ("warmupPending" !in existing) {
                    db.execSQL("ALTER TABLE sessions ADD COLUMN warmupPending INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        /**
         * v105→v106: El asistente predeterminado conserva la memoria global; otros asistentes están aislados de forma predeterminada.
         *
         * En la versión anterior, useGlobalMemory está habilitado de forma predeterminada para todos los asistentes. Al actualizar, el uso compartido global para asistentes no predeterminados está desactivado.
         * Evite los retratos globales/El recuerdo fijado se vinculó accidentalmente con otros asistentes. Se conservan los datos propios de cada asistente y las conversaciones recientes.
         * Utilice compileStatement para ejecutar DML, compatible con las restricciones de Android 16 en execSQL DML.
         */
        val MIGRATION_105_106 = object : Migration(105, 106) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.compileStatement(
                    "UPDATE assistants SET useGlobalMemory = 0 WHERE id != 'default' AND useGlobalMemory != 0",
                ).use { statement ->
                    statement.executeUpdateDelete()
                }
            }
        }

        /** v106→v107: Unifique el formato de índice ngram chino de la base de conocimientos FTS4 y rellene los fragmentos existentes. */
        val MIGRATION_106_107 = object : Migration(106, 107) {
            override fun migrate(db: SupportSQLiteDatabase) {
                rebuildKnowledgeChunkFtsTable(db)
            }
        }

        /**
         * v107→v108: Tabla de carpetas de la base de conocimientos — dejar carpeta(Contiene carpetas vacías)Puede existir de forma independiente.
         *
         * Crear solo tablas nuevas,No muevas datos existentes:La ruta de la carpeta del documento existente todavía está en metadataJson(única fuente de verdad),
         * El árbol de carpetas se obtiene fusionando "metadatos del documento + esta tabla".
         */
        val MIGRATION_107_108 = object : Migration(107, 108) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v2.4.6: Tabla de carpetas de la base de conocimientos + asistente "KB+Columna de alcance de carpeta
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS knowledge_folders (
                        id TEXT NOT NULL PRIMARY KEY,
                        kb_id TEXT NOT NULL,
                        path TEXT NOT NULL,
                        name TEXT NOT NULL,
                        created_at INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_folders_kb_id ON knowledge_folders (kb_id)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_knowledge_folders_kb_id_path ON knowledge_folders (kb_id, path)",
                )
                // v2.4.6: El ayudante está obligado desde "KB id"Actualizado a"KB+Alcance de la "ruta de la carpeta"(Migración cero de datos antiguos,Matriz vacía predeterminada)
                db.execSQL(
                    "ALTER TABLE assistants ADD COLUMN knowledgeFolderScopesJson TEXT NOT NULL DEFAULT '[]'",
                )
            }
        }

        /**
         * v108→v109: Tabla de mensajes más columna de asignación de traducción.
         *
         * v2.4.6: La traducción se cambió de "abrir un nuevo mensaje del asistente" a "un bloque expandible colgado en el mensaje fuente".,
         * El nombre del idioma debe almacenarse en la tabla de mensajes. → JSON de la traducción. Solo agrega columnas,Los datos existentes no se mueven.
         */
        val MIGRATION_108_109 = object : Migration(108, 109) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN translationsJson TEXT NOT NULL DEFAULT '{}'")
            }
        }

        /**
         * v2.5.3 (P3-2): Instantánea de configuración a nivel de sesión.
         *
         * sessions Agregue configSnapshotJson (puede estar vacío, la sesión anterior es NULL = No tomado, se devuelve el valor actual del asistente al leer).
         */
        val MIGRATION_109_110 = object : Migration(109, 110) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN configSnapshotJson TEXT DEFAULT NULL")
            }
        }

        /**
         * v2.6.2(comentarios de los usuarios): Definición de personalidad del asistente + limitaciones de la cadena de pensamiento.
         * - personaDefinition: Sección de definición de personalidad editable
         * - thinkingChain: Restricciones del proceso de pensamiento personalizado
         */
        val MIGRATION_110_111 = object : Migration(110, 111) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE assistants ADD COLUMN personaDefinition TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE assistants ADD COLUMN thinkingChain TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v2.6.4 P2-2(informe de auditoría): per-KB Recuperar anulaciones de parámetros.
         *
         * La implementación anterior tiene una copia global de la configuración RAG y diferentes bibliotecas no pueden tener diferentes valores de fragmento/topK/umbral.
         * Tampoco es posible configurar diferentes modelos de incrustación para diferentes bibliotecas. Se agregaron 5 nuevas columnas de anulación anulables,
         * Cuando se utilizan todas las cadenas NULL/vacías, la configuración global se hereda por completo (el comportamiento de los datos existentes permanece sin cambios).
         */
        val MIGRATION_111_112 = object : Migration(111, 112) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE knowledge_bases ADD COLUMN chunk_size_override INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE knowledge_bases ADD COLUMN chunk_overlap_override INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE knowledge_bases ADD COLUMN top_k_override INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE knowledge_bases ADD COLUMN threshold_override REAL DEFAULT NULL")
                db.execSQL(
                    "ALTER TABLE knowledge_bases ADD COLUMN embedding_model_override TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        /**
         * v2.6.6: Medidor de uso + borrador de entrada de sesión.
         *
         * 1) Cree nuevos registros de uso: cada vez que el modelo solicite una fila, la página de estadísticas se cambiará para agregar directamente esta tabla.
         *    No más iteraciones a través de mensajes para decodificar JSON; solicitudes no conversacionales (comprimidas/Título/Traducción/memoria/OCR) también es visible por primera vez.
         * 2) Utilice el campo de token existente en los mensajes para rellenar el historial de conversaciones, de modo que no se pierdan los datos antiguos del usuario.
         *    messages El campo del token puede ser NULL, usar COALESCE para rellenar y solo rellenar filas con uso real.
         * 3) sessions Se agregó input_draft: cada uno retiene la entrada no enviada al cambiar de sesión.
         */
        val MIGRATION_112_113 = object : Migration(112, 113) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS usage_records (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        created_at INTEGER NOT NULL DEFAULT 0,
                        source TEXT NOT NULL,
                        model_id TEXT NOT NULL DEFAULT '',
                        provider_id TEXT NOT NULL DEFAULT '',
                        prompt_tokens INTEGER NOT NULL DEFAULT 0,
                        completion_tokens INTEGER NOT NULL DEFAULT 0,
                        reasoning_tokens INTEGER NOT NULL DEFAULT 0,
                        cached_tokens INTEGER NOT NULL DEFAULT 0,
                        first_token_ms INTEGER NOT NULL DEFAULT 0,
                        duration_ms INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_usage_records_created_at ON usage_records(created_at)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_usage_records_source_created_at " +
                        "ON usage_records(source, created_at)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_usage_records_model_id_created_at " +
                        "ON usage_records(model_id, created_at)",
                )

                // Relleno: solo rellene los mensajes del asistente que sean realmente útiles para evitar generar una gran cantidad de filas de valor cero.
                db.execSQL(
                    """
                    INSERT INTO usage_records (
                        created_at, source, model_id, provider_id,
                        prompt_tokens, completion_tokens, reasoning_tokens, cached_tokens
                    )
                    SELECT createdAt, 'chat', '', '',
                           COALESCE(promptTokens, 0), COALESCE(completionTokens, 0),
                           COALESCE(reasoningTokens, 0), COALESCE(cachedTokens, 0)
                    FROM messages
                    WHERE role = 'ASSISTANT'
                      AND (COALESCE(promptTokens, 0) + COALESCE(completionTokens, 0)) > 0
                    """.trimIndent(),
                )

                db.execSQL("ALTER TABLE sessions ADD COLUMN input_draft TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v2.6.7(P1-1): 113 → 114 Cree una tabla de relaciones de roles.
         *
         * no ir `assistants` Agregar columnas: la tabla tiene más de 50 columnas y está“Configuración de roles”,
         * Las relaciones son un estado que evoluciona en tiempo de ejecución (el grado de intimidad cambiará) y mezclarlas ensuciará las copias de seguridad y las comparaciones de configuración.
         *
         * No rellene filas aquí: no hay registros de relación = Valor predeterminado (nivel de intimidad 0 / nivel normal),
         * Inicialice en el sitio cuando la capa empresarial no pueda leerlo para evitar escribir líneas en blanco para cada asistente.
         */
        val MIGRATION_113_114 = object : Migration(113, 114) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS assistant_relationship (
                        assistantId TEXT NOT NULL PRIMARY KEY,
                        relationLabel TEXT NOT NULL DEFAULT '',
                        affinity INTEGER NOT NULL DEFAULT 0,
                        lastInteractionAt INTEGER NOT NULL DEFAULT 0,
                        proactiveLevel TEXT NOT NULL DEFAULT 'normal',
                        proactiveEnabled INTEGER NOT NULL DEFAULT 1,
                        dailyProactiveLimit INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * v2.6.9: 115 → 116 Moments admite el rango de visibilidad.
         *
         * Cadena vacía predeterminada = Los datos públicos antiguos se comportan sin cambios (no es necesario completarlos).
         */
        val MIGRATION_115_116 = object : Migration(115, 116) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ai_moments ADD COLUMN visibleToJson TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v2.6.7: 116 → 117 Persistencia de tareas pendientes de la sesión.
         *
         * nueva mesa `session_todos`: Anteriormente, la tarea pendiente solo se almacenaba en la memoria de proceso y se perdía cuando se cambiaba de sesión.
         * Simplemente agregue una nueva tabla, sin modificar la estructura de la tabla existente; no es necesario reponer los datos antiguos (el estado de la memoria no se ha colocado en el disco).
         *
         * Las definiciones de las columnas deben coincidir [io.zer0.muse.data.todo.SessionTodoEntity] exactamente lo mismo,
         * De lo contrario, la verificación del esquema de la sala de máquinas real arrojará "Migration didn't properly handle".
         */
        val MIGRATION_116_117 = object : Migration(116, 117) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `session_todos` (" +
                        "`id` TEXT NOT NULL, " +
                        "`sessionId` TEXT NOT NULL DEFAULT '', " +
                        "`todosJson` TEXT NOT NULL DEFAULT '', " +
                        "`hasOpenItems` INTEGER NOT NULL DEFAULT 0, " +
                        "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                        "`updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_session_todos_sessionId` ON `session_todos` (`sessionId`)",
                )
            }
        }

        /**
         * v2.6.8: 117 → 118 Tabla de entidades de tarjetas.
         *
         * Anteriormente, las tarjetas se almacenaban solo en dos lugares: "cola de texto del mensaje + card_data.json", y no había ningún lugar para almacenarlas después de que se separaron del flujo de mensajes.
         * La identidad está sólo dentro del proceso. `c_xxx`. La nueva tabla promociona tarjetas a entidades de primer nivel:UUID La clave principal, el contenido y el estado acoplado están todos en línea.
         *
         * Simplemente agregue una nueva tabla, sin modificar la estructura de la tabla existente; las tarjetas existentes se completan mediante el proceso de reposición de nivel superior. [CardEntity.legacyCardId] partido,
         * Si el reabastecimiento falla, el "flujo de mensajes solamente" permanece y la migración en sí no adivina los datos.
         *
         * Las definiciones de columnas y los nombres de índice deben coincidir [io.zer0.muse.data.card.CardEntity] exactamente lo mismo,
         * De lo contrario, la verificación del esquema de la sala de máquinas real arrojará "Migration didn't properly handle: card_entities".
         *
         * dockSlot El índice único trata a NULL como desigual, por lo que "múltiples tarjetas desacopladas" no entran en conflicto;
         * Sólo se rechazarán dos espacios idénticos que no sean NULL.
         */
        /**
         * v2.6.9: 118 → 119 Reconstrucción activa de mensajes.
         *
         * Se agregan exclusivamente tres tablas nuevas y las tablas existentes no se modifican, por lo que no hay riesgo para los datos existentes:
         *  - pending_thought     Preocuparse por el libro mayor (“Tengo algo que decirle a esta persona”)
         *  - proactive_outcome   Cuenta de resultados (haga clic para abrir/Responder/Tachar, impulsar el aprendizaje a largo plazo)
         *  - proactive_relation  Intimidad de la relación y presupuesto diario.
         *
         * El antiguo ProactiveMessageConfig continúa en el DataStore sin migración:
         * El nuevo enlace solo lee el ID habilitado/período/asistente y los demás parámetros (intervalo, probabilidad,
         * Límite diario) no tiene contrapartida en el nuevo modelo, y forzar el mapeo sólo traerá viejos problemas.
         * Después de que el usuario anterior actualice, el libro de cuentas estará vacío y permanecerá en silencio durante unos días. —— Esta es una intención de diseño, no un problema técnico.
         */
        val MIGRATION_118_119 = object : Migration(118, 119) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_thought` (" +
                        "`id` TEXT NOT NULL, " +
                        "`assistantId` TEXT NOT NULL DEFAULT '', " +
                        "`kind` TEXT NOT NULL DEFAULT 'FOLLOW_UP', " +
                        "`seed` TEXT NOT NULL DEFAULT '', " +
                        "`source` TEXT NOT NULL DEFAULT 'CONVERSATION', " +
                        "`sourceRef` TEXT DEFAULT NULL, " +
                        "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                        "`expiresAt` INTEGER DEFAULT NULL, " +
                        "`urgency` TEXT NOT NULL DEFAULT 'WINDOW', " +
                        "`weight` REAL NOT NULL DEFAULT 0.5, " +
                        "`lastSurfacedAt` INTEGER DEFAULT NULL, " +
                        "`surfaceCount` INTEGER NOT NULL DEFAULT 0, " +
                        "`status` TEXT NOT NULL DEFAULT 'OPEN', " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_thought_dispatch` " +
                        "ON `pending_thought` (`assistantId`, `status`, `expiresAt`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_thought_sourceRef` " +
                        "ON `pending_thought` (`sourceRef`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `proactive_outcome` (" +
                        "`id` TEXT NOT NULL, " +
                        "`thoughtId` TEXT DEFAULT NULL, " +
                        "`messageId` TEXT DEFAULT NULL, " +
                        "`assistantId` TEXT NOT NULL DEFAULT '', " +
                        "`sessionId` TEXT NOT NULL DEFAULT '', " +
                        "`kind` TEXT NOT NULL DEFAULT 'FOLLOW_UP', " +
                        "`sentAt` INTEGER NOT NULL DEFAULT 0, " +
                        "`openedAt` INTEGER DEFAULT NULL, " +
                        "`repliedAt` INTEGER DEFAULT NULL, " +
                        "`dismissedAt` INTEGER DEFAULT NULL, " +
                        "`state` TEXT NOT NULL DEFAULT 'PENDING', " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_proactive_outcome_assistant` " +
                        "ON `proactive_outcome` (`assistantId`, `sentAt`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_proactive_outcome_thought` " +
                        "ON `proactive_outcome` (`thoughtId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_proactive_outcome_state` " +
                        "ON `proactive_outcome` (`state`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `proactive_relation` (" +
                        "`assistantId` TEXT NOT NULL, " +
                        "`intimacy` REAL NOT NULL DEFAULT 0.15, " +
                        "`cooldownUntil` INTEGER NOT NULL DEFAULT 0, " +
                        "`intensity` TEXT NOT NULL DEFAULT 'STANDARD', " +
                        "`budgetDate` TEXT NOT NULL DEFAULT '', " +
                        "`spentToday` INTEGER NOT NULL DEFAULT 0, " +
                        "`earnedToday` REAL NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`assistantId`))",
                )
            }
        }

        /**
         * v2.6.15: 119 → 120 lectura de caché/Escribe columnas.
         *
         * Antecedentes: antes de dividir, message.cachedTokens / use_records.cached_tokens contenía dos cantidades en una columna.
         * (lectura en caché + escritura en caché), mientras que el precio unitario de la escritura en caché es mayor (Anthropic es 1,25 veces el precio del insumo),
         * En conjunto no pueden valorarse individualmente.
         *
         * Esta migración**Solo agregue columnas, no cambie ni rellene valores antiguos**:
         *  - messages.cacheWriteTokens INTEGER DEFAULT NULL(NULL = Desconocido, las filas antiguas siempre son NULL)
         *  - usage_records.cache_write_tokens INTEGER NOT NULL DEFAULT 0
         * El valor de inventario de cachedTokens/cached_tokens en la fila anterior permanece sin cambios y se interpreta como "leído" al leer (sin reabastecimiento ni recálculo).
         *
         * Escenario alternativo (degradación): ambas columnas son puramente nuevas y el código de la versión anterior no las lee, por lo que los datos en sí no están dañados.
         * Para degradar la biblioteca a v119 para usarla en versiones anteriores:
         *  1) Primero haga una copia de seguridad de muse.db (establezca → Gestión de datos → Exportar copia de seguridad o copiar archivos directamente);
         *  2) SQLite 3.35+:`ALTER TABLE messages DROP COLUMN cacheWriteTokens`,
         *     `ALTER TABLE usage_records DROP COLUMN cache_write_tokens`;
         *     Si es inferior a 3,35, "Crear una nueva tabla → copiar datos → Cambiar nombre" para reconstruir estas dos tablas (messages El índice debe reconstruirse juntos);
         *  3) `PRAGMA user_version = 119`.
         * Instale únicamente la versión anterior y no realice el segundo paso.3 Room rechazará el paso al inicio porque "la versión de la biblioteca es más reciente que el código"——
         * Esto es verificación de versión, no corrupción de datos.
         */
        val MIGRATION_119_120 = object : Migration(119, 120) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Completación idempotente:88_89 Las columnas se perderán cuando se reconstruya la ruta de la tabla, así que rellénelas aquí.
                ensureMessageColumns(db)
                ensureUsageRecordColumns(db)
            }
        }

        /**
         * v2.6.16: 120 → 121 Mensajes de voz.
         *
         * messages Agregue dos columnas:
         *  - voiceUri TEXT, puede estar vacío —— Referencia del archivo de audio (VoiceMessageStore archivo:// ruta),
         *    NULL Indica mensaje sin voz
         *  - voiceDurationMs INTEGER NOT NULL DEFAULT 0 —— duración (milisegundos),0 Indica desconocido
         *
         * Ambas columnas son puramente nuevas y no es necesario rellenar las filas antiguas:NULL / 0 ha sido interpretado por la ruta de lectura como"mensajes no de voz".
         * Idempotente:ensureMessageColumns El juicio existe según PRAGMA,88_89 También se puede recuperar cuando se pierden columnas en la ruta de la tabla de reconstrucción.
         */
        val MIGRATION_120_121 = object : Migration(120, 121) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureMessageColumns(db)
            }
        }

        /**
         * versión 121 → 122: Limpia los datos sobrantes en teléfonos pequeños y antiguos.
         *
         * La interfaz de usuario y la cadena de programación del antiguo teléfono celular se eliminaron por completo. Todavía quedan dos tipos de rastros en la capa de datos. Se eliminan aquí:
         *  - Cuatro mesas para círculo de amigos/diario (`ai_moments` / `ai_moment_comments` / `ai_moment_likes` / `ai_diaries`)
         *  - `sessions.isMiniPhone = 1` conversaciones de mensajes privados y los mensajes que cuelgan debajo de ellas
         *
         * Esta función nunca ha estado abierta a usuarios reales. No hay datos comerciales que deban conservarse en la biblioteca, por lo que se eliminan en lugar de archivarse.
         *
         * No eliminar columnas:SQLite 3.35 solo apoyo `DROP COLUMN`,minSdk 26 Los dispositivos más antiguos no tienen esta capacidad.
         * `sessions.isMiniPhone` Conservar como columna compatible con el historial (NOT NULL DEFAULT 0, no hay parte que escriba),
         * Se eliminará junto con el próximo cambio estructural.
         *
         * messages No hay ninguna columna isMiniPhone en la tabla. Sólo puedes consultarlo por la sesión a la que pertenece; borre el mensaje primero y luego borre la sesión.
         * Evite dejar un lote de mensajes sin propietario después de que desaparezca la sesión (copia de seguridad/Las estadísticas seguirán escaneando toda la tabla de mensajes).
         *
         * Idempotente:`DROP TABLE IF EXISTS` + Eliminación condicional, ejecución repetida sin error.
         */
        val MIGRATION_121_122 = object : Migration(121, 122) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `ai_moment_likes`")
                db.execSQL("DROP TABLE IF EXISTS `ai_moment_comments`")
                db.execSQL("DROP TABLE IF EXISTS `ai_moments`")
                db.execSQL("DROP TABLE IF EXISTS `ai_diaries`")
                db.execSQL(
                    "DELETE FROM `messages` WHERE `sessionId` IN " +
                        "(SELECT `id` FROM `sessions` WHERE `isMiniPhone` = 1)",
                )
                db.execSQL("DELETE FROM `sessions` WHERE `isMiniPhone` = 1")
            }
        }

        val MIGRATION_117_118 = object : Migration(117, 118) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `card_entities` (" +
                        "`id` TEXT NOT NULL, " +
                        "`legacyCardId` TEXT DEFAULT NULL, " +
                        "`originSessionId` TEXT DEFAULT NULL, " +
                        "`originMessageId` TEXT DEFAULT NULL, " +
                        "`title` TEXT NOT NULL DEFAULT '', " +
                        "`code` TEXT NOT NULL DEFAULT '', " +
                        "`isSvg` INTEGER NOT NULL DEFAULT 0, " +
                        "`dataJson` TEXT DEFAULT NULL, " +
                        "`dockSlot` INTEGER DEFAULT NULL, " +
                        "`dockedAt` INTEGER DEFAULT NULL, " +
                        "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                        "`updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_card_entities_dockSlot` ON `card_entities` (`dockSlot`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_card_entities_originMessageId` ON `card_entities` (`originMessageId`)",
                )
            }
        }

        /**
         * v2.6.7(P2-4): 114 → 115 Los comentarios admiten respuestas anidadas.
         *
         * Los datos antiguos son todos NULL y tienen la misma semántica.“Comentarios principales”, no es necesario rellenar.
         */
        val MIGRATION_114_115 = object : Migration(114, 115) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ai_moment_comments ADD COLUMN parentCommentId TEXT DEFAULT NULL")
            }
        }

        /**
         * Phase 8.2: v1 → v2 Migrar.
         * - Crear una nueva mesa de asistentes
         * - sessions agregar assistantId(Predeterminado 'default') + pinned(Predeterminado 0)campo
         * - Insertar valor predeterminado Assistant(id='default')
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Crear una nueva mesa de asistentes
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS assistants (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        sortIndex INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0,
                        modelId TEXT,
                        temperature REAL,
                        topP REAL,
                        maxTokens INTEGER,
                        contextMessageSize INTEGER NOT NULL DEFAULT 20,
                        reasoningLevel TEXT NOT NULL DEFAULT 'AUTO',
                        systemPrompt TEXT NOT NULL DEFAULT '',
                        messageTemplate TEXT NOT NULL DEFAULT '',
                        presetMessagesJson TEXT NOT NULL DEFAULT '[]',
                        toolIdsJson TEXT NOT NULL DEFAULT '[]',
                        mcpServerIdsJson TEXT NOT NULL DEFAULT '[]',
                        streamOutput INTEGER NOT NULL DEFAULT 1,
                        memoryEnabled INTEGER NOT NULL DEFAULT 1,
                        useGlobalMemory INTEGER NOT NULL DEFAULT 1,
                        enableRecentChatsReference INTEGER NOT NULL DEFAULT 1,
                        enableTimeReminder INTEGER NOT NULL DEFAULT 1,
                        avatarEmoji TEXT NOT NULL DEFAULT '',
                        avatarImageUrl TEXT NOT NULL DEFAULT '',
                        backgroundUrl TEXT NOT NULL DEFAULT '',
                        backgroundOpacity REAL NOT NULL DEFAULT 1.0,
                        useGradientBackground INTEGER NOT NULL DEFAULT 0,
                        tagsJson TEXT NOT NULL DEFAULT '[]',
                        quickMessageIdsJson TEXT NOT NULL DEFAULT '[]',
                        lorebookIdsJson TEXT NOT NULL DEFAULT '[]',
                        modeInjectionIdsJson TEXT NOT NULL DEFAULT '[]',
                        skillIdsJson TEXT NOT NULL DEFAULT '[]',
                        customHeadersJson TEXT NOT NULL DEFAULT '{}',
                        customBodiesJson TEXT NOT NULL DEFAULT '{}',
                        capabilitiesJson TEXT NOT NULL DEFAULT '[]'
                    )
                    """.trimIndent(),
                )
                // Insertar valor predeterminado Assistant
                // Android 16 (SDK 36) Deshabilite la ejecución de SQLiteDatabase.execSQL a partir de ahora DML,solo permitido DDL;
                // Usar en su lugar compileStatement + executeInsert(SQLiteStatement método,Android 16 todavía permitido)
                db.compileStatement(
                    """
                    INSERT OR IGNORE INTO assistants (id, name, sortIndex, createdAt, updatedAt)
                    VALUES ('default', 'Asistente predeterminado', 0, 0, 0)
                    """.trimIndent(),
                ).use { it.executeInsert() }
                // sessions Agregar campo
                db.execSQL("ALTER TABLE sessions ADD COLUMN assistantId TEXT NOT NULL DEFAULT 'default'")
                db.execSQL("ALTER TABLE sessions ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Phase 8.3: v2 → v3 migración.
         * - messages Agregar campo favorito(Predeterminado 0)
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Phase 8.4: v3 → v4 migración.
         * - messages Agregar el campo citationUrlsJson(Predeterminado "[]",JSON cuerda)
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN citationUrlsJson TEXT NOT NULL DEFAULT '[]'")
            }
        }

        /**
         * Phase 8.5: v4 → v5 migración.
         * - Crear una nueva tabla de libros de historia(Libro mundial activado por palabra clave)
         * - Crear una nueva tabla de mensajes rápidos(Plantilla de mensaje rápido)
         * - Crear una nueva tabla Prompt_injections(Palabra de solicitud de inyección de patrón)
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS lorebooks (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        keywordsJson TEXT NOT NULL DEFAULT '[]',
                        content TEXT NOT NULL DEFAULT '',
                        priority INTEGER NOT NULL DEFAULT 0,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        caseSensitive INTEGER NOT NULL DEFAULT 0,
                        insertionPosition TEXT NOT NULL DEFAULT 'after_system',
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS quick_messages (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        content TEXT NOT NULL DEFAULT '',
                        scope TEXT NOT NULL DEFAULT 'global',
                        assistantId TEXT NOT NULL DEFAULT '',
                        sortIndex INTEGER NOT NULL DEFAULT 0,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS prompt_injections (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        mode TEXT NOT NULL,
                        displayName TEXT NOT NULL DEFAULT '',
                        content TEXT NOT NULL DEFAULT '',
                        priority INTEGER NOT NULL DEFAULT 0,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        insertionPosition TEXT NOT NULL DEFAULT 'after_system',
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Phase 8.6: v5 → v6 migración.
         * - messages Agregar campo imageBase64Json(Predeterminado "[]",JSON cuerda)
         *   para multimodalidad:USER Publicar fotos(fotos locales base64)/ Gemini salida de la trama(ASSISTANT)
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN imageBase64Json TEXT NOT NULL DEFAULT '[]'")
            }
        }

        /**
         * Phase 8.8: v6 → v7 migración.
         * - Crear una nueva tabla de habilidades(Kotlin Realización directa skill,No hay necesidad QuickJS)
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS skills (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL,
                        parametersJson TEXT NOT NULL DEFAULT '{}',
                        requiredJson TEXT NOT NULL DEFAULT '[]',
                        implementationKotlin TEXT NOT NULL,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        category TEXT NOT NULL DEFAULT 'custom',
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Phase 9.1 (M13): v7 → v8 migración.
         * - Crear una nueva tabla de carpetas(Agrupación de carpetas de sesión)
         * - sessions Agregar campo de ID de carpeta(Predeterminado '',con SessionEntity @ColumnInfo(defaultValue="") Alineación)
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS folders (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        sortIndex INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0,
                        expanded INTEGER NOT NULL DEFAULT 1
                    )
                    """.trimIndent(),
                )
                // sessions Agregar campo de ID de carpeta(Predeterminado '',con SessionEntity @ColumnInfo(defaultValue="") Alineación)
                db.execSQL("ALTER TABLE sessions ADD COLUMN folderId TEXT DEFAULT ''")
            }
        }

        /**
         * Phase 10.3: v8 → v9 migración.
         * - Cree una nueva tabla virtual message_fts FTS4(Índice de texto completo de ngram chino)
         *
         * Nota:
         * - SQL Debe ser exactamente igual al generado por la anotación @Fts4(Con acento grave + orden de columnas),De lo contrario, la verificación del esquema de la sala falla.
         * - La migración solo crea tablas vacías,No dejes ningún dato en blanco. Comenzado por primera vez por SessionRepository.ensureFtsIndexConsistent
         *   Compare el recuento de las dos tablas y luego la cantidad total rebuild(Convertir usando MessageFtsManager.toNgram).
         * - No en migración INSERT SELECT: SQL no puede llamar a la función ngram de Kotlin,Insertar directamente el texto original dará lugar a
         *   índice/La consulta es inconsistente(MATCH menos que).rebuild Debe hacerse en la capa de Kotlin.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `messages_fts` USING FTS4(" +
                        "`message_id` TEXT, `content_ngram` TEXT" +
                        ")",
                )
            }
        }

        /**
         * v9 → v10 migración.
         * - Crear una nueva tabla de tareas_programadas(tareas programadas)
         * - Crear una nueva tabla Knowledge_docs(Documentación de la base de conocimientos)
         *
         * Nota: skills La mesa ya esta en v6→v7 Creado durante la migración,No se repite aquí.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // scheduled_tasks mesa(SQL Debe ser exactamente igual a lo que genera la anotación ScheduledTaskEntity @Entity.)
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS scheduled_tasks (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        prompt TEXT NOT NULL DEFAULT '',
                        assistant_id TEXT NOT NULL DEFAULT 'default',
                        interval TEXT NOT NULL DEFAULT 'daily',
                        cron_expr TEXT NOT NULL DEFAULT '',
                        enabled INTEGER NOT NULL DEFAULT 1,
                        next_run_at INTEGER NOT NULL DEFAULT 0,
                        last_run_at INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                // knowledge_docs mesa
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS knowledge_docs (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        content TEXT NOT NULL DEFAULT '',
                        file_path TEXT NOT NULL DEFAULT '',
                        file_type TEXT NOT NULL DEFAULT '',
                        created_at INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * P1-7: v10 → v11 migración.
         * - Crear una nueva tabla programada_task_executions(Historial de ejecución de tareas programadas)
         *
         * Nota: La cadena de migración ha cubierto la ruta de actualización estándar.;Las versiones desconocidas o degradaciones son manejadas por el proceso de recuperación de respaldo y no se realiza ninguna migración destructiva.
         * Esta migración es sólo para v10 → v11 ruta de actualización estándar,DDL usar execSQL(Room Prácticas estándar de migración).
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // scheduled_task_executions mesa(SQL Debe ser exactamente igual a lo que genera la anotación ScheduledTaskExecutionEntity @Entity.)
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS scheduled_task_executions (
                        id TEXT NOT NULL PRIMARY KEY,
                        task_id TEXT NOT NULL,
                        executed_at INTEGER NOT NULL,
                        status TEXT NOT NULL DEFAULT 'success',
                        reply_summary TEXT NOT NULL DEFAULT '',
                        error_message TEXT NOT NULL DEFAULT ''
                    )
                    """.trimIndent(),
                )
                // índice: Historial de ejecución de consultas por task_id(Corresponde a la anotación @Index)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scheduled_task_executions_task_id ON scheduled_task_executions(task_id)")
            }
        }

        /**
         * v0.45: v11 → v12 migración.
         * - sessions Agregar campo archivado(Predeterminado 0,Función de archivo)
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v1.28: v12 → v13 migración.
         * - sessions Agregar el campo isAgentSession(Predeterminado 0,Distinguir entre sesiones de la pestaña Agente y sesiones de tareas)
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN isAgentSession INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v1.30: v13 → v14 migración.
         * - Crear una nueva tabla group_chats(Metadatos del chat grupal de múltiples agentes)
         * - Crea una nueva tabla group_chat_messages(Mensajes de chat grupal)
         *
         * Función de chat grupal:Después de que un usuario publica un mensaje en un chat grupal,GroupChatScheduler Serial hace que cada miembro del Agente se turne para hablar.
         * La cadena de migración ha cubierto la ruta de actualización estándar.;Las versiones desconocidas o degradaciones son manejadas por el proceso de recuperación de respaldo y no se realiza ninguna migración destructiva.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // group_chats mesa(SQL Debe ser exactamente igual al generado por la anotación @Entity GroupChatEntity)
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS group_chats (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        memberIdsJson TEXT NOT NULL,
                        teamId TEXT,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                // group_chat_messages mesa(SQL Debe ser exactamente igual al generado por la anotación GroupChatMessageEntity @Entity)
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS group_chat_messages (
                        id TEXT NOT NULL PRIMARY KEY,
                        chatId TEXT NOT NULL,
                        senderType TEXT NOT NULL,
                        senderId TEXT NOT NULL,
                        senderName TEXT NOT NULL,
                        body TEXT NOT NULL,
                        timestamp INTEGER NOT NULL DEFAULT 0,
                        mood TEXT
                    )
                    """.trimIndent(),
                )
                // índice: Consultar mensajes por chatId(Corresponde a la anotación @Index)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_group_chat_messages_chatId ON group_chat_messages(chatId)")
            }
        }

        /**
         * v1.41: v14 → v15 migración.
         * - group_chat_messages Agregar campo imageBase64Json(Predeterminado "[]",JSON cuerda)
         *   Utilizado para la multimodalidad de chat grupal.:Los usuarios envían imágenes adjuntas.
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE group_chat_messages ADD COLUMN imageBase64Json TEXT NOT NULL DEFAULT '[]'")
            }
        }

        /**
         * v1.43: v15 → v16 migración.
         * - Crear una nueva tabla de artefactos(artefacto de conversación:código/Documentación/HTML/SVG/Imágenes, etc.)
         * - messages Agregar el campo artefactoIdsJson(Predeterminado "[]",JSON cuerda)
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS artifacts (
                        id TEXT NOT NULL PRIMARY KEY,
                        sessionId TEXT NOT NULL,
                        messageId TEXT NOT NULL,
                        title TEXT NOT NULL,
                        type TEXT NOT NULL,
                        content TEXT NOT NULL DEFAULT '',
                        language TEXT,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(sessionId) REFERENCES sessions(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_artifacts_sessionId ON artifacts(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_artifacts_messageId ON artifacts(messageId)")
                db.execSQL("ALTER TABLE messages ADD COLUMN artifactIdsJson TEXT NOT NULL DEFAULT '[]'")
            }
        }

        /**
         * v1.44: v16 → v17 migración.
         * - group_chats Agregar campo fijado(Predeterminado 0),Chat del grupo de soporte fijado
         *
         * v1.46: v17 → v18 migración.
         * - group_chat_messages Agregar campo de razonamiento(Opcional),Visualización del proceso de pensamiento de mensajes de chat de grupo de soporte
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE group_chats ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v1.46: v17 → v18 migración.
         * - group_chat_messages Agregar campo de razonamiento(Opcional),Visualización del proceso de pensamiento de mensajes de chat de grupo de soporte
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE group_chat_messages ADD COLUMN reasoning TEXT")
            }
        }

        /**
         * v1.49: v18 → v19 migración.
         *
         * Reparación:La versión anterior de la declaración de creación de tabla MIGRATION_6_7 puede ser inconsistente con el esquema actual de SkillEntity
         * (category Falta columna o el valor predeterminado no coincide),Provoca que la validación del esquema de la sala falle
         * "Migration didn't properly handle: skills".
         *
         * Planificar:Reconstruir tabla de habilidades,Asegúrese de que el esquema sea exactamente el mismo que el de SkillEntity actual.
         * - CREATE TABLE skills_new(completo schema)
         * - INSERT INTO skills_new SELECT ... FROM skills(Utilice COALESCE para completar las columnas que faltan)
         * - DROP TABLE skills
         * - ALTER TABLE skills_new RENAME TO skills
         *
         * Android 16 (SDK 36) Límite:execSQL prohibido DML(INSERT/UPDATE/DELETE),
         * Para la migración de datos db.query() ejecutar INSERT INTO ... SELECT(DDL usar execSQL).
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Crear nueva tabla(DDL,execSQL Disponible)
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS skills_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL,
                        parametersJson TEXT NOT NULL DEFAULT '{}',
                        requiredJson TEXT NOT NULL DEFAULT '[]',
                        implementationKotlin TEXT NOT NULL,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        category TEXT NOT NULL DEFAULT 'custom',
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                // 2. Migración de datos(DML,Android 16 Debe usar query() en vez de execSQL)
                //    MIGRATION_6_7 La columna categoría/creadoEn/actualizadoEn se ha incluido al crear la tabla,Simplemente INSERTAR directamente.
                //    Si a la tabla anterior le faltan columnas de forma anormal,Abandonar la migración(Las habilidades se pueden volver a importar).
                //    (La rama de captura original que usa COALESCE para completar las columnas que faltan es un código inactivo:SQLite Analizando columnas inexistentes
                //    Se informa un error en la etapa.,COALESCE irremediable,Por lo tanto eliminado.)
                try {
                    db.query(
                        """
                        INSERT INTO skills_new
                            (id, name, description, parametersJson, requiredJson,
                             implementationKotlin, enabled, category, createdAt, updatedAt)
                        SELECT
                            id, name, description, parametersJson, requiredJson,
                            implementationKotlin, enabled, category, createdAt, updatedAt
                        FROM skills
                        """.trimIndent(),
                    ).use { /* ejecutar INSERT,cerrar Cursor */ }
                } catch (e: Exception) {
                    // Excepción de tabla antigua:Abandonar la migración de datos,skills La mesa se reconstruirá vacía.(Las habilidades integradas se reescribirán al inicio)
                    // v1.71: registrando,Facilita la resolución de problemas de pérdida de habilidades del usuario.
                    Logger.w("MuseDb", "skills La migración de la tabla falló,Se perderán las habilidades definidas por el usuario.", e)
                }
                // 3. Reemplazar mesa vieja(DDL)
                db.execSQL("DROP TABLE skills")
                db.execSQL("ALTER TABLE skills_new RENAME TO skills")
            }
        }

        /**
         * v1.53: MIGRATION_19_20 — Agregar índice a la tabla de mensajes,Optimice el rendimiento de las consultas de sesiones largas.
         *
         * Agregue dos nuevos índices compuestos(DDL,execSQL Disponible):
         *  - index_messages_sessionId_createdAt:Portada observeBySession(ORDER BY createdAt)
         *    + deleteFromCreatedAt(WHERE sessionId AND createdAt >= ?)
         *  - index_messages_sessionId_createdAt_role:Cubriendo consultas con la decoración de roles.
         *
         * La v19 original solo tiene índice de columna única index_messages_sessionId,larga sesión(Miles)abajo
         * ORDER BY createdAt necesidad filesort,Después de completar el índice compuesto, se puede realizar un escaneo secuencial del índice.
         */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_sessionId_createdAt ON messages(sessionId, createdAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_sessionId_createdAt_role ON messages(sessionId, createdAt, role)")
            }
        }

        /**
         * v1.54: MIGRATION_20_21 — RAG Sistematización:Tabla fragmentada de la base de conocimientos + campo de seguimiento del índice de documentos.
         *
         * 1. Crear una nueva tabla de conocimientos_chunks(Almacenar texto fragmentado + vector de incrustación JSON)
         * 2. knowledge_docs Agregar campo chunk_count + embedding_model(ALTER TABLE ADD COLUMN,DDL)
         *
         * embedding Almacenado como cadena de matriz flotante JSON(Como por ejemplo "[0.012, -0.034, ...]"),
         * Cárguelo en la memoria al mismo tiempo durante la recuperación para el recorrido de similitud del coseno(escala <1 El rendimiento de 10.000 fragmentos es suficiente.).
         */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Crear una nueva tabla de conocimientos_chunks
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS knowledge_chunks (
                        id TEXT NOT NULL PRIMARY KEY,
                        doc_id TEXT NOT NULL,
                        content TEXT NOT NULL DEFAULT '',
                        embedding TEXT NOT NULL DEFAULT '',
                        chunk_index INTEGER NOT NULL DEFAULT 0,
                        token_count INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_chunks_doc_id ON knowledge_chunks(doc_id)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_knowledge_chunks_doc_id_chunk_index ON knowledge_chunks(doc_id, chunk_index)",
                )
                // 2. knowledge_docs Agregar campos de seguimiento de índice(ALTER TABLE ADD COLUMN si DDL,execSQL Disponible)
                db.execSQL("ALTER TABLE knowledge_docs ADD COLUMN chunk_count INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE knowledge_docs ADD COLUMN embedding_model TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v1.81: MIGRATION_21_22 — para mensajes.rol y scheduled_tasks(enabled, next_run_at) Índice complementario.
         *
         * Etapa correspondiente3(M-SESS5)y escenario4(M-SC1)Nueva anotación @Index:
         *  - index_messages_role: acelerar WHERE role=? Consulta sin prefijo sessionId
         *  - index_scheduled_tasks_enabled_next_run_at: Anular getDueTasks WHERE enabled AND next_run_at <= ? Consulta
         *
         * DDL(CREATE INDEX),Android 16 Permita que execSQL se ejecute.
         */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_role ON messages(role)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scheduled_tasks_enabled_next_run_at ON scheduled_tasks(enabled, next_run_at)")
            }
        }

        /**
         * v1.82: MIGRATION_22_23 — Índice de almohadilla para Prompt_injections y Quick_Messages.
         *
         * Etapa correspondiente4(M-PID1/M-QM1)Nueva anotación @Index:
         *  - index_prompt_injections_mode_enabled: acelerar getEnabledByMode(WHERE mode=? AND enabled=1)
         *  - index_quick_messages_scope_assistantId_enabled: acelerar observeForAssistant
         *    (WHERE scope='global' OR (scope='assistant' AND assistantId=?)) AND enabled=1)
         *
         * DDL(CREATE INDEX),Android 16 Permita que execSQL se ejecute.
         */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_prompt_injections_mode_enabled ON prompt_injections(mode, enabled)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_quick_messages_scope_assistantId_enabled " +
                        "ON quick_messages(scope, assistantId, enabled)",
                )
            }
        }

        /**
         * v1.83: MIGRATION_23_24 — Indexación complementaria para habilidades y conocimientos_docs.
         *
         * Correspondiente a la5paso(M-SD1/L-KE1)Nueva anotación @Index:
         *  - index_skills_enabled: acelerar listEnabled(WHERE enabled=1)
         *  - index_skills_category: acelerar observeAll(ORDER BY category ASC)
         *  - index_knowledge_docs_updated_at: acelerar observeAll(ORDER BY updated_at DESC)
         */
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_skills_enabled ON skills(enabled)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_skills_category ON skills(category)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_docs_updated_at ON knowledge_docs(updated_at)")
            }
        }

        /**
         * v1.85: MIGRATION_24_25 — Se corrigió la columna de implementación faltante de Kotlin en la tabla de habilidades DEFAULT ''.
         *
         * pregunta:No.5paso(v1.83)Se agregó SkillEntity.implementationKotlin @ColumnInfo(defaultValue = ""),
         * Pero al crear la tabla en MIGRATION_6_7/MIGRATION_18_19, la implementaciónKotlin TEXT NOT NULL no está disponible. DEFAULT,
         * MIGRATION_23_24 Solo agregar índices sin reconstruir la tabla,Provoca que la validación del esquema de la sala falle
         * "Migration didn't properly handle: skills".
         *
         * Planificar:Reconstruir tabla de habilidades,Agregar implementaciónKotlin DEFAULT ''.
         * - CREATE TABLE skills_new(completo schema,implementationKotlin agregar DEFAULT '')
         * - INSERT INTO skills_new SELECT ... FROM skills
         * - DROP TABLE skills(El índice se elimina junto con la tabla.)
         * - ALTER TABLE skills_new RENAME TO skills
         * - Índice de reconstrucción(index_skills_enabled, index_skills_category)
         */
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Crear nueva tabla(DDL,implementationKotlin agregar DEFAULT '')
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS skills_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL,
                        parametersJson TEXT NOT NULL DEFAULT '{}',
                        requiredJson TEXT NOT NULL DEFAULT '[]',
                        implementationKotlin TEXT NOT NULL DEFAULT '',
                        enabled INTEGER NOT NULL DEFAULT 1,
                        category TEXT NOT NULL DEFAULT 'custom',
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                // 2. Migración de datos(DML,Android 16 Debe usar query() en vez de execSQL)
                try {
                    db.query(
                        """
                        INSERT INTO skills_new
                            (id, name, description, parametersJson, requiredJson,
                             implementationKotlin, enabled, category, createdAt, updatedAt)
                        SELECT
                            id, name, description, parametersJson, requiredJson,
                            implementationKotlin, enabled, category, createdAt, updatedAt
                        FROM skills
                        """.trimIndent(),
                    ).use { /* ejecutar INSERT,cerrar Cursor */ }
                } catch (e: Exception) {
                    // Excepción de tabla antigua:Abandonar la migración de datos,skills La mesa se reconstruirá vacía.(Las habilidades integradas se reescribirán al inicio)
                    Logger.w("MuseDb", "skills La migración de la tabla falló,Se perderán las habilidades definidas por el usuario.", e)
                }
                // 3. Reemplazar mesa vieja(DDL)
                db.execSQL("DROP TABLE skills")
                db.execSQL("ALTER TABLE skills_new RENAME TO skills")
                // 4. Índice de reconstrucción(MIGRATION_23_24 El índice agregado se elimina junto con DROP TABLE)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_skills_enabled ON skills(enabled)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_skills_category ON skills(category)")
            }
        }

        /**
         * v1.97: MIGRATION_25_26 — Agregue el campo regexRulesJson a la tabla de asistentes(Reglas de reemplazo regulares).
         *
         * Nuevo valor predeterminado del campo '[]'(Matriz JSON vacía),con AssistantEntity.regexRulesJson @ColumnInfo(defaultValue) Alineación.
         * No hay reglas después de migrar la versión anterior del Asistente,El comportamiento es consistente con la versión original.(Sin reemplazo).
         */
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE assistants ADD COLUMN regexRulesJson TEXT NOT NULL DEFAULT '[]'",
                )
            }
        }

        /**
         * v1.98: MIGRATION_26_27 — Agregar nueva tabla de experiencias(Biblioteca de experiencias).
         *
         * Almacena el conocimiento experiencial acumulado por los usuarios en las conversaciones.(mejores practicas/Aprende de las trampas/Flujo de trabajo),
         * y la memoria ordinaria(fact)distinguir:fact grabar"¿Quién es el usuario?",experience grabar"como hacer algo".
         * cuando experienceEnabled=true Inyectado en el sistema solicita referencia de IA.
         */
        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS experiences (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        content TEXT NOT NULL,
                        category TEXT NOT NULL DEFAULT 'Universales',
                        tagsJson TEXT NOT NULL DEFAULT '[]',
                        source TEXT NOT NULL DEFAULT 'manual',
                        sessionId TEXT,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * v1.103: MIGRATION_27_28 — Complete la columna de estado de ánimo/reflexión de la tabla de mensajes.
         *
         * Anteriormente, MessageEntity carecía de estos dos campos.,sesión de tarea/Agent charlar(Usar tabla de mensajes)
         * El estado de ánimo/reflexión se descarta al realizar el pedido.,Cortar páginas/Las tarjetas MOOD desaparecen después de recargarlas.
         * mesa de chat grupal(group_chat_messages)La columna de estado de ánimo se incluyó al crear la tabla en MIGRATION_13_14,
         * Esta vez solo se agregará la tabla de mensajes.reflection Antes no había dos listas.,Inventadlo juntos.
         */
        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v1.105 Reparación:agregar DEFAULT NULL,con Entity @ColumnInfo(defaultValue="NULL") Alineación
                // (Originales "ALTER TABLE ADD COLUMN mood TEXT" Ninguno DEFAULT,SQLite dflt_value=null,
                //  Room Expectativa "NULL",Fallo por falta de coincidencia de autenticación)
                db.execSQL("ALTER TABLE messages ADD COLUMN mood TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN reflection TEXT DEFAULT NULL")
            }
        }

        /**
         * v1.104 U7: MIGRATION_28_29 — Agregar la columna de etiqueta favorita a la tabla de mensajes(Etiquetas de grupos favoritos).
         *
         * NULL Indica desagrupado;Después de las actualizaciones del usuario anterior, la etiqueta favorita de todas las colecciones existentes será NULL,
         * UI clasificado arriba"Todos"y"No agrupado"Se pueden ver ambos chips.
         */
        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v1.105 Reparación:agregar DEFAULT NULL,con Entity @ColumnInfo(defaultValue="NULL") Alineación
                db.execSQL("ALTER TABLE messages ADD COLUMN favoriteTag TEXT DEFAULT NULL")
            }
        }

        /**
         * v1.106: MIGRATION_29_30 — Reconstruir la tabla de mensajes.,Historial de reparaciones ALTER TABLE no traído DEFAULT NULL
         * Provocó que la validación del esquema fallara.
         *
         * fondo:MIGRATION_27_28(mood/reflection)y MIGRATION_28_29(favoriteTag)uso original
         * "ALTER TABLE ADD COLUMN x TEXT" Sin cláusula DEFAULT,SQLite de PRAGMA table_info
         * Devoluciones para estas columnas dflt_value=null;Y la entidad declara @ColumnInfo(defaultValue="NULL"),
         * Room Expectativa dflt_value="NULL". los dos no coinciden → IllegalStateException colapsar.
         *
         * Esta migración utiliza"Crear nueva tabla + copiar datos + reemplazar"Reconstruir la tabla de mensajes.,Asegúrese de que las tres columnas estén presentes
         * DEFAULT NULL. Esta migración soluciona un bloqueo para los usuarios que actualizaron a la versión 29.
         * MIGRATION_27_28/28_29 Ya sincronizado DEFAULT NULL,Los usuarios que actualicen en el futuro no necesitan ir a 29_30.
         */
        val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Crear nueva tabla(Las definiciones de columnas son exactamente las mismas que en onCreate,Contiene DEFAULT NULL)
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `messages_new` (
                        `id` TEXT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `reasoning` TEXT,
                        `modelId` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `imageUrlsJson` TEXT NOT NULL DEFAULT '[]',
                        `favorite` INTEGER NOT NULL DEFAULT 0,
                        `favoriteTag` TEXT DEFAULT NULL,
                        `citationUrlsJson` TEXT NOT NULL DEFAULT '[]',
                        `imageBase64Json` TEXT NOT NULL DEFAULT '[]',
                        `artifactIdsJson` TEXT NOT NULL DEFAULT '[]',
                        `mood` TEXT DEFAULT NULL,
                        `reflection` TEXT DEFAULT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                // 2. Copiar datos(El orden de las columnas es coherente con la creación de la tabla.)
                db.execSQL(
                    """
                    INSERT INTO `messages_new` (
                        `id`,`sessionId`,`role`,`content`,`reasoning`,`modelId`,
                        `createdAt`,`imageUrlsJson`,`favorite`,`favoriteTag`,
                        `citationUrlsJson`,`imageBase64Json`,`artifactIdsJson`,
                        `mood`,`reflection`
                    )
                    SELECT
                        `id`,`sessionId`,`role`,`content`,`reasoning`,`modelId`,
                        `createdAt`,`imageUrlsJson`,`favorite`,`favoriteTag`,
                        `citationUrlsJson`,`imageBase64Json`,`artifactIdsJson`,
                        `mood`,`reflection`
                    FROM `messages`
                    """.trimIndent(),
                )
                // 3. Reemplazar mesa vieja
                db.execSQL("DROP TABLE `messages`")
                db.execSQL("ALTER TABLE `messages_new` RENAME TO `messages`")
                // 4. Índice de reconstrucción(DROP TABLE Todos los índices serán eliminados.)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_sessionId` ON `messages` (`sessionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_role` ON `messages` (`role`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_sessionId_createdAt` ON `messages` (`sessionId`, `createdAt`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_messages_sessionId_createdAt_role` ON `messages` (`sessionId`, `createdAt`, `role`)",
                )
            }
        }

        /**
         * v1.107: MIGRATION_30_31 — Diseño completo de redundancia de base de datos.
         *
         * cuatro direcciones:
         *  1. Desnormalizar campos redundantes: sessions/assistants/folders/group_chats/messages Agregar columnas redundantes
         *  2. Tabla de caché de agregación de estadísticas: Nuevo stats_cache
         *  3. Redundancia de integridad de datos: messages.contentLength + Nuevo db_integrity_log
         *  4. Redundancia de recuperación ante desastres de respaldo: Nuevo auto_backup_log
         *
         * Todos los campos redundantes más DEFAULT,Alineado con la entidad @ColumnInfo,Evite fallas en la validación del esquema.
         * Relleno con subconsultas correlacionadas,Calcule los valores iniciales a partir de datos existentes de una sola vez.
         */
        val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ── 1. desnormalización: ALTER TABLE Agregar campos redundantes ──
                db.execSQL("ALTER TABLE sessions ADD COLUMN messageCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE assistants ADD COLUMN messageCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE assistants ADD COLUMN lastUsedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE folders ADD COLUMN sessionCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE group_chats ADD COLUMN lastMessagePreview TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE group_chats ADD COLUMN messageCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE group_chats ADD COLUMN lastActivityAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN contentLength INTEGER NOT NULL DEFAULT 0")

                // ── 2. Rellenar el valor inicial del campo redundante(Subconsulta correlacionada) ──
                // sessions.messageCount
                db.execSQL("UPDATE sessions SET messageCount = (SELECT COUNT(*) FROM messages WHERE sessionId = sessions.id)")
                // assistants.messageCount (La cantidad de mensajes de ASISTENTE en esta sesión de asociación de Asistente)
                db.execSQL(
                    """
                    UPDATE assistants SET messageCount = (
                        SELECT COUNT(*) FROM messages m
                        JOIN sessions s ON m.sessionId = s.id
                        WHERE s.assistantId = assistants.id AND m.role = 'ASSISTANT'
                    )
                    """.trimIndent(),
                )
                // assistants.lastUsedAt (El número máximo de sesiones asociadas. updatedAt)
                db.execSQL(
                    "UPDATE assistants SET lastUsedAt = " +
                        "COALESCE((SELECT MAX(updatedAt) FROM sessions " +
                        "WHERE assistantId = assistants.id), 0)",
                )
                // folders.sessionCount
                db.execSQL("UPDATE folders SET sessionCount = (SELECT COUNT(*) FROM sessions WHERE folderId = folders.id)")
                // group_chats.messageCount
                db.execSQL("UPDATE group_chats SET messageCount = (SELECT COUNT(*) FROM group_chat_messages WHERE chatId = group_chats.id)")
                // group_chats.lastActivityAt
                db.execSQL(
                    "UPDATE group_chats SET lastActivityAt = " +
                        "COALESCE((SELECT MAX(timestamp) FROM group_chat_messages " +
                        "WHERE chatId = group_chats.id), 0)",
                )
                // group_chats.lastMessagePreview (Contenido del último mensaje,Truncado a 50 palabras)
                // Nota: group_chat_messages La columna de contenido de la tabla es cuerpo, no content
                db.execSQL(
                    """
                    UPDATE group_chats SET lastMessagePreview = COALESCE(
                        (SELECT substr(body, 1, 50) FROM group_chat_messages
                         WHERE chatId = group_chats.id ORDER BY timestamp DESC LIMIT 1),
                        ''
                    )
                    """.trimIndent(),
                )
                // messages.contentLength
                db.execSQL("UPDATE messages SET contentLength = length(content)")

                // ── 3. Crear una nueva tabla de caché de estadísticas ──
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `stats_cache` (
                        `key` TEXT NOT NULL,
                        `value` TEXT NOT NULL DEFAULT '{}',
                        `updatedAt` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`key`)
                    )
                    """.trimIndent(),
                )

                // ── 4. Crear una nueva tabla de registro de verificación de integridad ──
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `db_integrity_log` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `status` TEXT NOT NULL DEFAULT 'ok',
                        `details` TEXT NOT NULL DEFAULT '',
                        `dbSizeBytes` INTEGER NOT NULL DEFAULT 0,
                        `checkedAt` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )

                // ── 5. Crear una nueva tabla de registro de copia de seguridad automática ──
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `auto_backup_log` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `backupPath` TEXT NOT NULL DEFAULT '',
                        `fileSizeBytes` INTEGER NOT NULL DEFAULT 0,
                        `status` TEXT NOT NULL DEFAULT 'success',
                        `errorMessage` TEXT NOT NULL DEFAULT '',
                        `messageCount` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * v2.0: MIGRATION_31_32 — eliminación suave(deletedAt columna).
         * sessions y los mensajes agregan la columna eliminadoEn,Admite la función de papelera de reciclaje.
         */
        val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN deletedAt INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN deletedAt INTEGER DEFAULT NULL")
            }
        }

        /**
         * Función1: MIGRATION_32_33 — Mensaje de respuesta de emoticono(reaction columna).
         * messages Agregar campo de reacción(null = Sin respuesta).
         */
        val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN reaction TEXT DEFAULT NULL")
            }
        }

        /**
         * Función2: MIGRATION_33_34 — assistants Agregue el campo ProviderId a la tabla.
         * Se utiliza para especificar el modelo al que pertenece cada asistente al vincular modelos independientes. Provider,Evite conflictos de nombres de modelos entre proveedores.
         */
        val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE assistants ADD COLUMN providerId TEXT DEFAULT NULL")
            }
        }

        /**
         * v1.122: MIGRATION_34_35 — Complete las columnas parentSessionId + childCount de la tabla de sesiones.
         * MIGRATION_31_32 se perdió la adición parentSessionId(sesión principal de rama)y childCount(recuento de sucursales),Agregado aquí.
         *
         * Revisión de corrección (2.0 B-27): idempotente — Instantánea estándar v34(Entity Se han declarado dos columnas.)no contiene"31_32 Adición faltante"Defectos,
         * Actualización desde duplicados del punto de partida estándar v34 ALTER → duplicate column colapsar(Prueba de migración 22..54 Matriz expuesta);
         * cierto"Equipo faltante"(31_32 En realidad no se ejecutó exitosamente)De hecho falta la columna,Complete aquí como de costumbre. Ambos son compatibles.
         */
        val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val existing = mutableSetOf<String>()
                db.query("PRAGMA table_info(sessions)").use { cursor ->
                    while (cursor.moveToNext()) existing.add(cursor.getString(1))
                }
                if ("parentSessionId" !in existing) {
                    db.execSQL("ALTER TABLE sessions ADD COLUMN parentSessionId TEXT DEFAULT NULL")
                }
                if ("childCount" !in existing) {
                    db.execSQL("ALTER TABLE sessions ADD COLUMN childCount INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        /**
         * Phase 2 2B: MIGRATION_35_36 — Crear una nueva tabla de hitos(hitos de la relación).
         */
        val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS milestones (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        type TEXT NOT NULL DEFAULT 'auto',
                        condition_type TEXT NOT NULL,
                        trigger_value INTEGER NOT NULL DEFAULT 0,
                        title TEXT NOT NULL,
                        message TEXT NOT NULL,
                        assistant_id TEXT,
                        session_id TEXT,
                        created_at INTEGER NOT NULL DEFAULT 0,
                        dismissed_at INTEGER
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Sistema de herramientas existente port: MIGRATION_36_37 — agent_messages table (DM system).
         */
        val MIGRATION_36_37 = object : Migration(36, 37) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_messages (
                        id TEXT NOT NULL PRIMARY KEY,
                        from_agent_id TEXT NOT NULL,
                        to_agent_id TEXT NOT NULL,
                        content TEXT NOT NULL,
                        is_read INTEGER NOT NULL DEFAULT 0,
                        reply_to_id TEXT,
                        created_at INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * P2-4: MIGRATION_37_38 — Crear una nueva tabla audit_log(Registro de auditoría).
         *
         * solo createTable,La estructura de la tabla existente no se modifica. AuditLogger mantiene la política de búfer circular.
         */
        val MIGRATION_37_38 = object : Migration(37, 38) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS audit_log (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        timestamp INTEGER NOT NULL,
                        category TEXT NOT NULL DEFAULT '',
                        action TEXT NOT NULL DEFAULT '',
                        target TEXT NOT NULL DEFAULT '',
                        detail TEXT NOT NULL DEFAULT '',
                        success INTEGER NOT NULL DEFAULT 1
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * v1.133: MIGRATION_38_39 — RAG Actualización sistemática(Múltiples bases de conocimiento + BLOB embedding + FTS4 + Actualizaciones incrementales + metadatos).
         *
         * Cambiar:
         *  1. Crear una nueva tabla de bases de conocimiento
         *  2. knowledge_docs Añadir 4 columnas:kb_id / is_internal / content_hash / metadata_json
         *  3. knowledge_chunks Añadir 2 columnas:embedding_blob / metadata_json
         *  4. Cree una nueva tabla virtual Knowledge_chunks_fts(FTS4)
         *  5. assistants Añadir 2 columnas:knowledgeBaseIdsJson / ragConfigOverride
         *  6. Insertar valor predeterminado KB(id="default")
         *  7. Establecer el documento existente kb_id en "default",is_internal prensa fileType="devdoc" / id.startsWith("devdoc-") marca
         *  8. Crear sincrónicamente un índice FTS para fragmentos existentes
         *  9. Actualizar la KB predeterminada de doc_count
         * 10. messages Agregar la columna ragCitationsJson(Lista de referencias RAG persistentes)
         *
         * Sin migración de datos:Se conservan las antiguas columnas JSON incrustadas,El nuevo código lee el primer JSON alternativo de BLOB.
         */
        val MIGRATION_38_39 = object : Migration(38, 39) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Crear una nueva tabla de bases de conocimiento
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS knowledge_bases (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        created_at INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL DEFAULT 0,
                        doc_count INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_bases_updated_at ON knowledge_bases(updated_at)")

                // 2. knowledge_docs Añadir 4 columnas(ALTER TABLE ADD COLUMN si DDL,SQLite apoyo)
                db.execSQL("ALTER TABLE knowledge_docs ADD COLUMN kb_id TEXT NOT NULL DEFAULT 'default'")
                db.execSQL("ALTER TABLE knowledge_docs ADD COLUMN is_internal INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE knowledge_docs ADD COLUMN content_hash TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE knowledge_docs ADD COLUMN metadata_json TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_docs_kb_id ON knowledge_docs(kb_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_docs_content_hash ON knowledge_docs(content_hash)")

                // 3. knowledge_chunks Añadir 2 columnas
                db.execSQL("ALTER TABLE knowledge_chunks ADD COLUMN embedding_blob BLOB DEFAULT NULL")
                db.execSQL("ALTER TABLE knowledge_chunks ADD COLUMN metadata_json TEXT NOT NULL DEFAULT '{}'")

                // 4. Crear una nueva mesa virtual FTS4
                // Utilice text_content en lugar de nombres de columnas content,Evite conflictos de marcadores de posición de columnas de contenido interno de FTS4
                // Los nombres de las columnas usan doc_id en lugar de docId: columnas reservadas FTS4 docid(rowid Alias,No distingue entre mayúsculas y minúsculas),docId conducirá a vtable constructor failed
                // Sin especificar el tokenizador: la persona que llama realiza la recuperación del chino después del preprocesamiento de ngram y luego la escribe.(Por el modo MessageFtsManager)
                db.execSQL(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS knowledge_chunks_fts
                    USING fts4(chunkId, doc_id, text_content)
                    """.trimIndent(),
                )

                // 5. assistants Añadir 2 columnas
                db.execSQL("ALTER TABLE assistants ADD COLUMN knowledgeBaseIdsJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE assistants ADD COLUMN ragConfigOverride TEXT DEFAULT NULL")

                // 6. Insertar valor predeterminado KB
                db.compileStatement(
                    """
                    INSERT OR IGNORE INTO knowledge_bases (id, name, description, created_at, updated_at, doc_count)
                    VALUES ('default', 'Base de conocimientos predeterminada', 'Todos los documentos que no especifican una base de conocimientos se colocan aquí de forma predeterminada.', 0, 0, 0)
                    """.trimIndent(),
                ).use { it.executeInsert() }

                // 7. Marcar documentos internos(sustituto original fileType="devdoc" + id.startsWith("devdoc-") Doble codificación)
                db.execSQL("UPDATE knowledge_docs SET is_internal = 1 WHERE file_type = 'devdoc' OR id LIKE 'devdoc-%'")

                // 8. Crear sincrónicamente un índice FTS para fragmentos existentes(usar INSERT INTO ... SELECT)
                // Android 16 (SDK 36) Deshabilite la ejecución de execSQL a partir de ahora DML,El uso de compileStatement en su lugar no funciona con combinaciones SELECT INSERT,
                // Usado aquí SupportSQLiteDatabase.execSQL(originales SQL) — Room SupportSQLiteDatabase.execSQL sigue siendo compatible con INSERT.
                try {
                    db.execSQL(
                        """
                        INSERT INTO knowledge_chunks_fts(chunkId, doc_id, text_content)
                        SELECT id, doc_id, content FROM knowledge_chunks
                        WHERE content != ''
                        """.trimIndent(),
                    )
                } catch (e: Exception) {
                    // FTS El error de sincronización no bloquea la migración,La reindexación posterior completará el proceso.
                    io.zer0.common.Logger.w("MuseDb", "FTS Error de sincronización,Vuelva a indexar más tarde para completar el proceso.", e)
                }

                // 9. Actualizar la KB predeterminada de doc_count(campos redundantes)
                db.execSQL(
                    """
                    UPDATE knowledge_bases
                    SET doc_count = (SELECT COUNT(*) FROM knowledge_docs WHERE kb_id = 'default' AND is_internal = 0)
                    WHERE id = 'default'
                    """.trimIndent(),
                )

                // 10. messages Agregar la columna ragCitationsJson(Lista de referencias RAG persistentes)
                db.execSQL("ALTER TABLE messages ADD COLUMN ragCitationsJson TEXT NOT NULL DEFAULT '[]'")
            }
        }

        /**
         * v1.134 P2-1: MIGRATION_39_40 — Agregue la columna dedicada_session_id a tareas_programadas.
         *
         * fondo:El ScheduledTaskRunner.executeTask original crea una nueva sesión cada vez que se ejecuta.,de larga duración daily
         * Las tareas generan una gran cantidad de sesiones dispersas. Se agregó el campo dedicado_session_id,Crea un dedicado
         * sesión y escribir en este campo,Reutilizar la misma sesión para ejecuciones posteriores.(Modo de agregación de sesiones).
         *
         * Una cadena vacía en el campo indica que la agregación no está habilitada.(compatible con versiones anteriores,Las tareas antiguas no se completarán hasta que se ejecuten por primera vez.).
         */
        val MIGRATION_39_40 = object : Migration(39, 40) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE scheduled_tasks ADD COLUMN dedicated_session_id TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        /**
         * v1.137: MIGRATION_40_41 — Agregue campos de automatización complejos a tareas_programadas.
         *
         * Nuevo:
         *  - condition_json: Configuración de disparador condicional
         *  - action_type / action_config_json: Tipos de acción y configuraciones.
         *  - next_task_ids_json: Lista de ID de tareas encadenadas
         *  - parent_task_id: tarea de los padres ID(Trazabilidad de la cadena)
         */
        val MIGRATION_40_41 = object : Migration(40, 41) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN condition_json TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN action_type TEXT NOT NULL DEFAULT 'ai_prompt'")
                db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN action_config_json TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN next_task_ids_json TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN parent_task_id TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v1.0.23 hotfix: MIGRATION_41_42 — Reparación"falso v41"La verificación de integridad de la base de datos falló.
         *
         * causa raíz del problema:
         *  Una versión de desarrollo intermedia cambió ScheduledTaskEntity pero no se actualizó. MuseDb version(Todavía marcado 41),
         *  Como resultado, el hash de esquema de la base de datos del dispositivo instalado con esta versión intermedia es f651a76c...(v41 no estándar 8edc5eb8...),
         *  Después de actualizar al APK v41 correcto, se omitió Room debido a la misma versión. migration,La verificación de integridad directa falla y falla.
         *
         * Estrategia de reparación:
         *  Promocionar la versión a 42,Forzar a Room a seguir el camino de la migración(Omitir v41 integrity check).
         *  migration Verifique de manera defensiva la integridad del campo de la tabla de tareas programadas en — Comprobación PRAGMA table_info columna por columna,
         *  Si falta, se utilizará ALTER TABLE ADD COLUMN para completarlo.
         *  De esta manera, no importa cuál sea el dispositivo"Verdadero v41"(Campos completos,No es necesario ALTER)Aún así"falso v41"(Falta campo,componer uno por uno),
         *  migration Este último esquema está relacionado con v42(= Correcto v41)consistente,integrity check aprobar.
         *
         * Seguridad de datos:
         *  - "Verdadero v41"Usuario:Todos los campos existen,migration Corriendo en vacío,Pérdida de datos cero
         *  - "falso v41"Usuario:Complete los campos faltantes(Relleno de valor predeterminado),Se conservan los datos existentes
         */
        val MIGRATION_41_42 = object : Migration(41, 42) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // reparación defensiva:Verifique la integridad del campo de la tabla Scheduled_tasks,Complete las posibles columnas que faltan
                val existingColumns = mutableSetOf<String>()
                db.query("PRAGMA table_info(scheduled_tasks)").use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) {
                        existingColumns.add(cursor.getString(nameIndex))
                    }
                }
                // v1.134: dedicated_session_id
                if ("dedicated_session_id" !in existingColumns) {
                    db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN dedicated_session_id TEXT NOT NULL DEFAULT ''")
                }
                // v1.137: 5 campos automatizados
                if ("condition_json" !in existingColumns) {
                    db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN condition_json TEXT NOT NULL DEFAULT ''")
                }
                if ("action_type" !in existingColumns) {
                    db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN action_type TEXT NOT NULL DEFAULT 'ai_prompt'")
                }
                if ("action_config_json" !in existingColumns) {
                    db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN action_config_json TEXT NOT NULL DEFAULT ''")
                }
                if ("next_task_ids_json" !in existingColumns) {
                    db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN next_task_ids_json TEXT NOT NULL DEFAULT ''")
                }
                if ("parent_task_id" !in existingColumns) {
                    db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN parent_task_id TEXT NOT NULL DEFAULT ''")
                }
            }
        }

        val MIGRATION_42_43 = object : Migration(42, 43) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v1.0.15: Mensaje enviado a la tabla de bandeja de salida(Cola de envío persistente)
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS message_outbox (
                        id TEXT NOT NULL PRIMARY KEY,
                        sessionId TEXT NOT NULL,
                        text TEXT NOT NULL,
                        imageBase64Json TEXT NOT NULL DEFAULT '[]',
                        userMessageId TEXT NOT NULL,
                        assistantMessageId TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(sessionId) REFERENCES sessions(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_message_outbox_sessionId ON message_outbox(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_message_outbox_createdAt ON message_outbox(createdAt)")
            }
        }

        /**
         * v1.0.17: MIGRATION_43_44 — Se agregó la tabla Translate_history.(Persistencia del historial de traducción).
         *
         * Anteriormente, el historial de traducción solo se conservaba en la memoria.(MAX_HISTORY=50),El proceso se pierde cuando se mata.
         * Migrar solo crear tablas + índices,Sin migración de datos(Las viejas versiones de la historia no son persistentes.).
         */
        val MIGRATION_43_44 = object : Migration(43, 44) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // translate_history mesa(SQL Debe ser exactamente igual al generado por la anotación TranslateHistoryEntity @Entity)
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS translate_history (
                        id TEXT NOT NULL PRIMARY KEY,
                        source_text TEXT NOT NULL,
                        translated_text TEXT NOT NULL,
                        source_language TEXT NOT NULL,
                        target_language TEXT NOT NULL,
                        style TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                // índice: Historial de consultas en orden cronológico inverso(Corresponde a la anotación @Index)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_translate_history_created_at ON translate_history(created_at)")
                // índice: Consulta por par de idiomas(Corresponde a la anotación @Index)
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_translate_history_source_language_target_language " +
                        "ON translate_history(source_language, target_language)",
                )
            }
        }

        /**
         * v1.0.17: MIGRATION_44_45 — Se agregó la tabla Quick_notes.(Registre rápidamente la persistencia de la habitación + papelera de reciclaje).
         *
         * Alternativa de almacenamiento de archivos JSON a QuickNoteStore;App Iniciado por QuickNoteStore.migrateToRoom
         * Importar datos antiguos Room(Idempotencia garantizada a través del indicador SharedPreferences quick_notes_migrad).
         *
         * tags Cadena separada por comas de almacenamiento de columnas(Manejado por QuickNoteConverters TypeConverter List<String> ↔ String).
         */
        val MIGRATION_44_45 = object : Migration(44, 45) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // quick_notes mesa(SQL Debe ser exactamente igual al generado por la anotación QuickNoteEntity @Entity)
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS quick_notes (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        content TEXT NOT NULL,
                        tags TEXT NOT NULL,
                        pinned INTEGER NOT NULL DEFAULT 0,
                        deleted INTEGER NOT NULL DEFAULT 0,
                        deleted_at INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                // índice: Anular observeActive/observeTrash WHERE + ORDER BY
                db.execSQL("CREATE INDEX IF NOT EXISTS index_quick_notes_deleted_updated_at ON quick_notes(deleted, updated_at)")
                // índice: Portada ORDER BY pinned DESC, updated_at DESC
                db.execSQL("CREATE INDEX IF NOT EXISTS index_quick_notes_pinned_updated_at ON quick_notes(pinned, updated_at)")
            }
        }

        /**
         * v1.0.17: MIGRATION_45_46 — Agregue el campo de reintento a tareas_programadas.
         *
         * Nuevo:
         *  - retry_count: Número actual de reintentos(Predeterminado 0,Incremento en caso de falla,éxito/Restablecer cuando se alcanza el límite superior)
         *  - max_retries: Número máximo de reintentos(Predeterminado 3)
         *
         * Estrategia de reintento de retroceso exponencial utilizada en ejecutarTask.
         */
        val MIGRATION_45_46 = object : Migration(45, 46) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN max_retries INTEGER NOT NULL DEFAULT 3")
            }
        }

        /**
         * v1.0.30 gap4.3: MIGRATION_46_47 — translate_history Agregar columna favorita(Favoritos de traducción).
         *
         * Nuevo campo predeterminado 0(No favorito),Los usuarios pueden hacer clic en la estrella en el historial de traducción para recopilar traducciones utilizadas con frecuencia.
         * El índice index_translate_history_favorite anula la consulta observeFavorites
         * (WHERE favorite=1 ORDER BY created_at DESC).
         *
         * Nota: v1.0.17 Reintentar el campo tareas_programadas con MIGRATION_45_46,
         * Por lo tanto, los usos favoritos de esta traducción 46 → 47.
         */
        val MIGRATION_46_47 = object : Migration(46, 47) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE translate_history ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_translate_history_favorite ON translate_history(favorite)")
            }
        }

        /**
         * v1.0.18: MIGRATION_47_48 — Mejora rápida del registro(9 artículo).
         *
         * Agregue 6 columnas a la tabla Quick_notes:
         *  - folder: Clasificación/carpeta(Predeterminado '')
         *  - content_type: tipo de contenido plain/markdown(Predeterminado 'plain')
         *  - attachments_json: Matriz JSON de ruta de archivo adjunto de imagen(Predeterminado '')
         *  - reminder_at: marca de tiempo de recordatorio(Predeterminado 0 = Sin recordatorio)
         *  - encrypted: token cifrado(Predeterminado 0)
         *  - encrypted_content: Texto cifrado de contenido cifrado(Predeterminado '')
         *
         * Agregar índice de columna de carpeta,Anule la consulta observeByFolder.
         */
        val MIGRATION_47_48 = object : Migration(47, 48) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE quick_notes ADD COLUMN folder TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE quick_notes ADD COLUMN content_type TEXT NOT NULL DEFAULT 'plain'")
                db.execSQL("ALTER TABLE quick_notes ADD COLUMN attachments_json TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE quick_notes ADD COLUMN reminder_at INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE quick_notes ADD COLUMN encrypted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE quick_notes ADD COLUMN encrypted_content TEXT NOT NULL DEFAULT ''")
                // índice: Portada observeByFolder(WHERE folder = ?) + observeFolders(DISTINCT folder)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_quick_notes_folder ON quick_notes(folder)")
            }
        }

        /**
         * v2.x: MIGRATION_49_50 — Crea una nueva tabla group_chat_memories(Aislamiento de memoria de chat grupal).
         *
         * fondo:El mensaje de chat grupal contiene varios agentes hablando,Escribir directamente en la memoria principal del asistente contamina el contexto principal de la conversación.
         * Esta tabla almacena el resumen de los mensajes del chat grupal.,con [io.zer0.memory] El sistema de memoria principal del módulo está completamente aislado.
         * SystemPromptAssembler Se utiliza al inyectar `<group_chat_memory>` Etiquetas y memoria principal `<long_term_memory>` Distinguir.
         *
         * índice:
         *  - assistantId:Portada [GroupChatMemoryDao.getByAssistant] Consulta(SystemPromptAssembler inyectar)
         *  - groupChatId:Portada [GroupChatMemoryDao.getByGroupChat] Consulta + Eliminación en cascada
         *  - createdAt:Portada [GroupChatMemoryDao.deleteOlderThan] limpiar
         */
        val MIGRATION_49_50 = object : Migration(49, 50) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS group_chat_memories (
                        id TEXT NOT NULL PRIMARY KEY,
                        groupChatId TEXT NOT NULL,
                        assistantId TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        expiresAt INTEGER
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_group_chat_memories_assistantId ON group_chat_memories(assistantId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_group_chat_memories_groupChatId ON group_chat_memories(groupChatId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_group_chat_memories_createdAt ON group_chat_memories(createdAt)")
            }
        }

        /**
         * v2.x: MIGRATION_50_51 — Campo del modo de discusión de chat grupal.
         *
         * Agregue 3 columnas a la tabla group_chats:
         *  - discussion_mode: modo de discusión(round_robin/auto/debate/host,Predeterminado round_robin)
         *  - auto_max_rounds: Auto Número máximo de rondas de diálogo consecutivas en el modo.(Predeterminado 5)
         *  - host_id: modo anfitrión AI id(Predeterminado NULL)
         *
         * Todas las columnas nuevas tienen valores predeterminados,Manténgase compatible con versiones anteriores;Los chats grupales existentes obtienen automáticamente el modo round_robin.
         */
        val MIGRATION_50_51 = object : Migration(50, 51) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE group_chats ADD COLUMN discussionMode TEXT NOT NULL DEFAULT 'round_robin'")
                db.execSQL("ALTER TABLE group_chats ADD COLUMN autoMaxRounds INTEGER NOT NULL DEFAULT 5")
                db.execSQL("ALTER TABLE group_chats ADD COLUMN host_id TEXT DEFAULT NULL")
            }
        }

        /**
         * v2.x: MIGRATION_51_52 — Campos mejorados para mensajes de chat grupal(Susurro / Respuesta de cotización / Tipo de mensaje).
         */
        val MIGRATION_51_52 = object : Migration(51, 52) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE group_chat_messages ADD COLUMN whisper_target_id TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE group_chat_messages ADD COLUMN reply_to_id TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE group_chat_messages ADD COLUMN messageType TEXT NOT NULL DEFAULT 'normal'")
            }
        }

        /**
         * v2.x: MIGRATION_52_53 — Campos de gestión del contexto del chat grupal(Documentos compartidos en grupo + contexto exclusivo de IA).
         *
         * Agregue 2 columnas a la tabla group_chats:
         *  - shared_docs_json: Lista de documentos compartidos en grupo JSON(Predeterminado "[]"),Visible para todos los miembros
         *  - member_private_context_json: contexto específico del miembro Map JSON(Predeterminado "{}"),Cuarentena por ID de asistente
         *
         * Todas las columnas nuevas tienen valores predeterminados,Manténgase compatible con versiones anteriores;Los chats grupales existentes obtienen automáticamente una lista vacía/Mapa vacío.
         */
        val MIGRATION_52_53 = object : Migration(52, 53) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE group_chats ADD COLUMN shared_docs_json TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE group_chats ADD COLUMN member_private_context_json TEXT NOT NULL DEFAULT '{}'")
            }
        }

        /**
         * v1.0.24: MIGRATION_53_54 — Se corrigió el valor predeterminado de la columna de la lista de chat grupal.
         *
         * migración temprana(50_51 / 51_52)Se perdió cuando ALTER TABLE ADD COLUMN DEFAULT NULL,
         * dando como resultado una base de datos migrada host_id / whisper_target_id / reply_to_id El valor predeterminado es
         * 'undefined'(Room representación interna),declarado con entidad defaultValue = "NULL" no hay coincidencia,
         * Se genera una excepción IllegalStateException al inicio.
         *
         * SQLite No compatible ALTER COLUMN,La mesa debe ser reconstruida:
         *  1. Crear tabla temporal(Cinturón correctamente DEFAULT NULL)
         *  2. Copiar todos los datos
         *  3. DROP reloj viejo
         *  4. RENAME La tabla temporal es el nombre oficial de la tabla.
         *
         * Reconstruya las tablas group_chats y group_chat_messages al mismo tiempo.
         */
        val MIGRATION_53_54 = object : Migration(53, 54) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ── reconstruir group_chats(host_id Valor predeterminado NULL) ──
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS group_chats_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        memberIdsJson TEXT NOT NULL,
                        teamId TEXT,
                        pinned INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0,
                        lastMessagePreview TEXT NOT NULL DEFAULT '',
                        messageCount INTEGER NOT NULL DEFAULT 0,
                        lastActivityAt INTEGER NOT NULL DEFAULT 0,
                        discussionMode TEXT NOT NULL DEFAULT 'round_robin',
                        autoMaxRounds INTEGER NOT NULL DEFAULT 5,
                        host_id TEXT DEFAULT NULL,
                        shared_docs_json TEXT NOT NULL DEFAULT '[]',
                        member_private_context_json TEXT NOT NULL DEFAULT '{}'
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO group_chats_new (id, name, description, memberIdsJson, teamId, pinned,
                        createdAt, updatedAt, lastMessagePreview, messageCount, lastActivityAt,
                        discussionMode, autoMaxRounds, host_id, shared_docs_json, member_private_context_json)
                    SELECT id, name, description, memberIdsJson, teamId, pinned,
                        createdAt, updatedAt, lastMessagePreview, messageCount, lastActivityAt,
                        discussionMode, autoMaxRounds, host_id, shared_docs_json, member_private_context_json
                    FROM group_chats
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE group_chats")
                db.execSQL("ALTER TABLE group_chats_new RENAME TO group_chats")

                // ── reconstruir group_chat_messages(whisper_target_id / reply_to_id Valor predeterminado NULL) ──
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS group_chat_messages_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        chatId TEXT NOT NULL,
                        senderType TEXT NOT NULL,
                        senderId TEXT NOT NULL,
                        senderName TEXT NOT NULL,
                        body TEXT NOT NULL,
                        imageBase64Json TEXT NOT NULL DEFAULT '[]',
                        timestamp INTEGER NOT NULL DEFAULT 0,
                        mood TEXT,
                        reasoning TEXT,
                        whisper_target_id TEXT DEFAULT NULL,
                        reply_to_id TEXT DEFAULT NULL,
                        messageType TEXT NOT NULL DEFAULT 'normal'
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO group_chat_messages_new (id, chatId, senderType, senderId, senderName,
                        body, imageBase64Json, timestamp, mood, reasoning,
                        whisper_target_id, reply_to_id, messageType)
                    SELECT id, chatId, senderType, senderId, senderName,
                        body, imageBase64Json, timestamp, mood, reasoning,
                        whisper_target_id, reply_to_id, messageType
                    FROM group_chat_messages
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE group_chat_messages")
                db.execSQL("ALTER TABLE group_chat_messages_new RENAME TO group_chat_messages")

                // Índice de reconstrucción(Desaparece con la tabla DROP)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_group_chat_messages_chatId ON group_chat_messages(chatId)")
            }
        }

        /**
         * v1.0.19: MIGRATION_48_49 — Assistant Finalización del campo.
         *
         * Agregue 3 columnas a la tabla de asistentes:
         *  - summary: Introducción de una frase(Predeterminado '',Usado para listas de chat grupal/subtítulo de la tarjeta)
         *  - useAssistantName: Reemplace el nombre del modelo con el nombre del asistente en la interfaz de chat(Predeterminado 0 = false)
         *  - allowGroupChat: Si permitir unirse al chat grupal(Predeterminado 1 = true)
         *
         * Nota: tags La función de agrupación ya la realiza el campo tagsJson,Sin adiciones repetidas.
         * Todas las columnas nuevas tienen valores predeterminados,Manténgase compatible con versiones anteriores;Las líneas del Asistente existentes obtienen automáticamente los valores predeterminados.
         */
        val MIGRATION_48_49 = object : Migration(48, 49) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE assistants ADD COLUMN summary TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE assistants ADD COLUMN useAssistantName INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE assistants ADD COLUMN allowGroupChat INTEGER NOT NULL DEFAULT 1")
            }
        }

        /**
         * v1.0.30: MIGRATION_54_55 — Tabla de mensajes más campo variante(variantGroupId/variantIndex/variantCount).
         *
         * por la persistencia"regenerar"Respuesta del asistente de múltiples versiones generada.
         * ALTER TABLE ADD COLUMN Utilice valores predeterminados NULL/0/1 en todo momento,Compatible con datos existentes.
         */
        val MIGRATION_54_55 = object : Migration(54, 55) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN variantGroupId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN variantIndex INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN variantCount INTEGER NOT NULL DEFAULT 1")
            }
        }

        /**
         * v1.0.47: MIGRATION_55_56 — Tabla de mensajes más campo para archivos adjuntos(attachmentsJson).
         *
         * Para una persistencia estructurada de metadatos de archivos sin formato(nombre de archivo/MIME/tamaño/Extraer texto),
         * Reemplaza el método anterior de fusionar el documento con el contenido y descartar los metadatos del archivo original después de analizarlo.
         * Predeterminado '[]'(matriz vacía),Compatible con datos existentes.
         */
        val MIGRATION_55_56 = object : Migration(55, 56) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN attachmentsJson TEXT NOT NULL DEFAULT '[]'")
                // v1.0.47: lorebooks Tabla más columna WholeWord(patrón de coincidencia de palabras completas,Predeterminado false)
                db.execSQL("ALTER TABLE lorebooks ADD COLUMN wholeWord INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v1.0.47 P3: MIGRATION_56_57 — sessions Tabla más columna SkillIdsJson(Cobertura de habilidades a nivel de sesión).
         *
         * "[]" Indica heredar el Asistente skillIdsJson(Comportamiento predeterminado sin cambios);
         * Una matriz no vacía representa la cobertura. Assistant,Solo está habilitada la habilidad especificada.
         * Predeterminado '[]' Compatible con datos existentes.
         */
        val MIGRATION_56_57 = object : Migration(56, 57) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN skillIdsJson TEXT NOT NULL DEFAULT '[]'")
            }
        }

        /**
         * P1-2: MIGRATION_57_58 — Crear una nueva tabla worldbook_entries(Libro del mundo dinámico).
         *
         * Independiente de la tabla de libros de historia existentes,Admite funciones avanzadas no disponibles en Lorebook:
         *  - alwaysActive Activación residente/escaneo multicapa scanDepth/palabra clave regular isRegex
         *  - injectTarget(system/user/assistant) + injectPosition(prepend/append/at_depth) + insertionDepth
         *  - assistantId Vincular un ayudante específico(NULL = situación general)
         *
         * Todas las columnas DEFAULT,con [io.zer0.muse.worldbook.WorldBookEntryEntity] de @ColumnInfo(defaultValue=...) Alineación.
         * assistantId uso de columna DEFAULT NULL(sqlite El valor predeterminado de la columna TEXTO en el medio puede ser NULL).
         */
        val MIGRATION_57_58 = object : Migration(57, 58) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS worldbook_entries (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        keywordsJson TEXT NOT NULL DEFAULT '[]',
                        content TEXT NOT NULL DEFAULT '',
                        priority INTEGER NOT NULL DEFAULT 50,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        caseSensitive INTEGER NOT NULL DEFAULT 0,
                        isRegex INTEGER NOT NULL DEFAULT 0,
                        alwaysActive INTEGER NOT NULL DEFAULT 0,
                        scanDepth INTEGER NOT NULL DEFAULT 3,
                        injectTarget TEXT NOT NULL DEFAULT 'system',
                        injectPosition TEXT NOT NULL DEFAULT 'append',
                        insertionDepth INTEGER NOT NULL DEFAULT 0,
                        assistantId TEXT DEFAULT NULL,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * v1.0.53 Phase 1: MIGRATION_58_59 — Crear una nueva tabla subagent_threads(Persistencia del libro mayor de subagentes).
         *
         * Implementado por la persistencia del libro mayor de subprocesos del subagente,reemplazar viejo tools/SubagentThreadStore.kt(Versión de memoria).
         * Dos rutas de subagente compartidas:
         *  - camino A: SubagentTool + SkillExecutor.delegateAgent nonBlocking(Delegación Subasistente)
         *  - camino B: SubagentRunSkill + SubagentRunner(Pasivo agent)
         *
         * Para obtener descripciones de las columnas, consulte [io.zer0.muse.data.subagent.SubagentThreadEntity];Historial de subsesiones(noticias)
         * Ir al archivo JSONL filesDir/subagent_sessions/<threadId>.jsonl(Gestionado por SubagentSessionStore),
         * No permitido Room(Evite escribir los resultados de cada ronda de herramientas en una tabla.,y aislado de la lista principal de conversaciones).
         *
         * Todas las columnas DEFAULT,con entidad @ColumnInfo(defaultValue=...) / Kotlin Alineación de valores predeterminados.
         * childSessionId / label / lastRunStatus / lastSummary Se permite NULO.
         */
        val MIGRATION_58_59 = object : Migration(58, 59) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS subagent_threads (
                        threadId TEXT NOT NULL PRIMARY KEY,
                        parentSessionId TEXT NOT NULL,
                        childSessionId TEXT DEFAULT NULL,
                        childSessionPath TEXT NOT NULL,
                        assistantId TEXT NOT NULL,
                        label TEXT DEFAULT NULL,
                        access TEXT NOT NULL DEFAULT 'read',
                        status TEXT NOT NULL DEFAULT 'open',
                        runCount INTEGER NOT NULL DEFAULT 0,
                        lastRunStatus TEXT DEFAULT NULL,
                        lastSummary TEXT DEFAULT NULL,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_subagent_threads_parentSessionId ON subagent_threads(parentSessionId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_subagent_threads_status ON subagent_threads(status)",
                )
            }
        }

        /**
         * v1.0.53: MIGRATION_59_60 — assistants Tabla más columna toolModelId(per-assistant modelo de herramienta).
         * En la ronda de llamada de herramientas, se utilizará primero el modelo de herramienta propio del asistente.,Reserva global si no se establece.
         */
        val MIGRATION_59_60 = object : Migration(59, 60) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE assistants ADD COLUMN toolModelId TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * B5-03: MIGRATION_60_61 — messages Agregue think_signature/thinking_encrypted_content.
         */
        val MIGRATION_60_61 = object : Migration(60, 61) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureMessageColumns(db)
            }
        }

        /**
         * B5-01: MIGRATION_61_62 — Se agregó la tabla generate_checkpoints.
         */
        val MIGRATION_61_62 = object : Migration(61, 62) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `generation_checkpoints` (" +
                        "`assistantMessageId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, " +
                        "`userMessageId` TEXT NOT NULL, `content` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`assistantMessageId`), " +
                        "FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_generation_checkpoints_sessionId` ON `generation_checkpoints` (`sessionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_generation_checkpoints_createdAt` ON `generation_checkpoints` (`createdAt`)")
            }
        }

        /**
         * B5-02: MIGRATION_62_63 — Se agregó la tabla group_chat_generation_ledger.
         */
        val MIGRATION_62_63 = object : Migration(62, 63) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `group_chat_generation_ledger` (" +
                        "`id` TEXT NOT NULL, `chatId` TEXT NOT NULL, `mode` TEXT NOT NULL, " +
                        "`round` INTEGER NOT NULL, `memberIndex` INTEGER NOT NULL, " +
                        "`memberIdsJson` TEXT NOT NULL DEFAULT '[]', " +
                        "`status` TEXT NOT NULL DEFAULT 'running', " +
                        "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                        "`updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`chatId`) REFERENCES `group_chats`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_group_chat_generation_ledger_chatId` ON `group_chat_generation_ledger` (`chatId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_group_chat_generation_ledger_status` ON `group_chat_generation_ledger` (`status`)",
                )
            }
        }

        /**
         * B6-03: MIGRATION_63_64 — messages Agregar columna mood_skin(Logotipo de piel emocional).
         */
        val MIGRATION_63_64 = object : Migration(63, 64) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureMessageColumns(db)
            }
        }

        /**
         * B7-03/B7-05: MIGRATION_64_65 — sessions Agregue lastReadMessageId/sortOrder.
         */
        val MIGRATION_64_65 = object : Migration(64, 65) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureSessionColumns(db)
            }
        }

        /**
         * B7-03: MIGRATION_65_66 — sessions agregar lastReadCount,Logotipo no leído para la lista de sesiones.
         */
        val MIGRATION_65_66 = object : Migration(65, 66) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureSessionColumns(db)
            }
        }

        /**
         * B8-01: MIGRATION_66_67 — sessions agregar proactiveNextTriggerAt,Admite la programación de mensajes activos a nivel de sesión.
         */
        val MIGRATION_66_67 = object : Migration(66, 67) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureSessionColumns(db)
            }
        }

        /**
         * B8-06: MIGRATION_67_68 — Se solucionó el problema de la falta de mensajes/sesiones en la base de datos del historial.
         *
         * Si la versión anterior falla durante la migración o se usa de manera inconsistente schema,Room Informe
         * "Migration didn't properly handle: messages". Esta migración se compara con PRAGMA.,
         * Complete solo las columnas que faltan,Las columnas existentes permanecen sin cambios.
         */
        val MIGRATION_67_68 = object : Migration(67, 68) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureMessageColumns(db)
                ensureSessionColumns(db)
                ensureGroupChatMessageColumns(db)
            }
        }

        /**
         * v1.0.62 fix: Compatible desde v1.0.60 (DB version 73)Escenario de actualización:
         * Asegúrese de que existan las tablas generate_checkpoints y group_chat_generation_ledger.
         */
        val MIGRATION_68_74 = object : Migration(68, 74) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureGenerationTables(db)
                ensureSessionColumns(db)
                ensureMessageColumns(db)
                ensureGroupChatMessageColumns(db)
            }
        }

        /** v1.0.74: 68→69 cadena complementaria(68_74 Queda el salto recto,Suministrar equipos viejos). */
        val MIGRATION_68_69 = object : Migration(68, 69) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureSessionColumns(db)
                ensureMessageColumns(db)
            }
        }

        /** v1.0.74: Llene los vacíos en las versiones 69-72 — Si estas versiones alguna vez se lanzaran(Escala de grises/prueba),Al actualizar, Room fallará si no puede encontrar la migración.
         *  Migración vacía + asegurar complemento idempotente,Comparte la lógica de defensa con 68_74. */
        val MIGRATION_69_70 = object : Migration(69, 70) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureSessionColumns(db)
                ensureMessageColumns(db)
            }
        }
        val MIGRATION_70_71 = object : Migration(70, 71) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureSessionColumns(db)
                ensureMessageColumns(db)
            }
        }
        val MIGRATION_71_72 = object : Migration(71, 72) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureSessionColumns(db)
                ensureMessageColumns(db)
            }
        }
        val MIGRATION_72_73 = object : Migration(72, 73) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureSessionColumns(db)
                ensureMessageColumns(db)
            }
        }

        /**
         * P0 árbol de diálogo: messages Tabla más parentGroupId(El grupo de variantes de preguntas del usuario al que pertenece la variante del asistente.).
         * Al actualizar la biblioteca anterior, asegúrese de que se utilice MessageColumns para complementar las columnas idempotentes.,No afecta los datos existentes.
         */
        val MIGRATION_74_75 = object : Migration(74, 75) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureMessageColumns(db)
                ensureGroupChatMessageColumns(db)
            }
        }
        val MIGRATION_73_74 = object : Migration(73, 74) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureGenerationTables(db)
                ensureGroupChatMessageColumns(db)
            }
        }

        /**
         * R-DB-04: 75→76 — messages La tabla almacena imágenes base64 externamente en filesDir/muse_images/.
         * Reutilice MessageImageStore.toPersistable: base64 larga y cámbielo a file:// referencia,
         * Base64 corta se mantiene en línea(Consistente con el nuevo comportamiento de escritura v1.134);Regresar al valor original en caso de falla,Idempotente y reentrante.
         */
        fun migrate75To76(storageDir: File): Migration = object : Migration(75, 76) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val store = MessageImageStore(storageDir)
                val jsonSerializer = ListSerializer(String.serializer())
                db.query(
                    "SELECT id, imageBase64Json FROM messages " +
                        "WHERE imageBase64Json IS NOT NULL AND imageBase64Json != '' AND imageBase64Json != '[]'",
                ).use { cursor ->
                    val idIdx = cursor.getColumnIndex("id")
                    val jsonIdx = cursor.getColumnIndex("imageBase64Json")
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(idIdx)
                        val json = cursor.getString(jsonIdx)
                        val base64List = runCatching {
                            AppJson.decodeFromString(jsonSerializer, json)
                        }.getOrNull()
                        if (base64List.isNullOrEmpty()) continue
                        val persistable = store.toPersistable(id, base64List)
                        if (persistable != base64List) {
                            val updated = AppJson.encodeToString(jsonSerializer, persistable)
                            db.execSQL(
                                "UPDATE messages SET imageBase64Json = ? WHERE id = ?",
                                arrayOf(updated, id),
                            )
                        }
                    }
                }
            }
        }

        fun get(context: Context): MuseDb {
            return dbInstance ?: synchronized(this) {
                dbInstance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MuseDb::class.java,
                    "muse.db",
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                        MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                        MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
                        MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21,
                        MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24,
                        MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28,
                        MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31,
                        MIGRATION_31_32,
                        MIGRATION_32_33,
                        MIGRATION_33_34,
                        MIGRATION_34_35,
                        MIGRATION_35_36,
                        MIGRATION_36_37,
                        MIGRATION_37_38,
                        MIGRATION_38_39,
                        MIGRATION_39_40,
                        MIGRATION_40_41,
                        MIGRATION_41_42,
                        MIGRATION_42_43,
                        MIGRATION_43_44,
                        MIGRATION_44_45,
                        MIGRATION_45_46,
                        MIGRATION_46_47,
                        MIGRATION_47_48,
                        MIGRATION_48_49,
                        MIGRATION_49_50,
                        MIGRATION_50_51,
                        MIGRATION_51_52,
                        MIGRATION_52_53,
                        MIGRATION_53_54,
                        MIGRATION_54_55,
                        MIGRATION_55_56,
                        MIGRATION_56_57,
                        MIGRATION_57_58,
                        MIGRATION_58_59,
                        MIGRATION_59_60,
                        MIGRATION_60_61,
                        MIGRATION_61_62,
                        MIGRATION_62_63,
                        MIGRATION_63_64,
                        MIGRATION_64_65,
                        MIGRATION_65_66,
                        MIGRATION_66_67,
                        MIGRATION_67_68,
                        MIGRATION_68_69,
                        // MIGRATION_68_74 código hasta la muerte(68→69 Partido primero),Mantenga notas para el seguimiento histórico,No eliminar para evitar conflictos de migración
                        // MIGRATION_68_74,
                        MIGRATION_69_70,
                        MIGRATION_70_71,
                        MIGRATION_71_72,
                        MIGRATION_72_73,
                        MIGRATION_73_74,
                        MIGRATION_74_75,
                        migrate75To76(File(context.applicationContext.filesDir, "muse_images")),
                        migrate76To77(),
                        MIGRATION_77_78,
                        MIGRATION_78_79,
                        MIGRATION_79_80,
                        MIGRATION_80_81,
                        MIGRATION_81_82,
                        MIGRATION_82_83,
                        MIGRATION_83_84,
                        MIGRATION_84_85,
                        MIGRATION_85_86,
                        MIGRATION_86_87,
                        MIGRATION_87_88,
                        MIGRATION_88_89,
                        MIGRATION_89_90,
                        MIGRATION_90_91,
                        MIGRATION_91_92,
                        MIGRATION_92_93,
                        MIGRATION_93_94,
                        MIGRATION_94_95,
                        MIGRATION_95_96,
                        MIGRATION_96_97,
                        MIGRATION_97_98,
                        MIGRATION_98_99,
                        MIGRATION_99_100,
                        MIGRATION_100_101,
                        MIGRATION_101_102,
                        MIGRATION_102_103,
                        MIGRATION_103_104,
                        MIGRATION_104_105,
                        MIGRATION_105_106,
                        MIGRATION_106_107,
                        MIGRATION_107_108,
                        MIGRATION_108_109,
                        MIGRATION_109_110,
                        MIGRATION_110_111,
                        MIGRATION_111_112,
                        MIGRATION_112_113,
                        MIGRATION_113_114,
                        MIGRATION_114_115,
                        MIGRATION_115_116, MIGRATION_116_117, MIGRATION_117_118, MIGRATION_118_119,
                        MIGRATION_119_120,
                        MIGRATION_120_121,
                        MIGRATION_121_122,
                    )
                    // Habilitar restricciones de clave externa(artifacts ON DELETE CASCADE para tablas depende de esta configuración)
                    // onOpen No dentro de la transacción onCreate,Estos comandos se pueden ejecutar;onCreate prohibido dentro PRAGMA
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            // R-DB-02: La nueva instalación crea explícitamente una tabla FTS(El primer nivel de doble seguro.).
                            synchronized(FTS_CREATE_LOCK) {
                                try {
                                    createKnowledgeChunkFtsTable(db)
                                    // R-DB-05: Nueva construcción de instalación messages_fts(FTS5 Prioridad,FTS4 revertir).
                                    try {
                                        createMessageFtsTable(db, fts5Available(db))
                                    } catch (e: Exception) {
                                        io.zer0.common.Logger.e("MuseDb", "No se pudo crear mensajes_fts: ${e.message}", e)
                                    }
                                } catch (e: Exception) {
                                    knowledgeFtsBroken = true
                                    io.zer0.common.Logger.e("MuseDb", "Falló la creación de Knowledge_chunks_fts: ${e.message}", e)
                                }
                            }
                        }

                        override fun onOpen(db: SupportSQLiteDatabase) {
                            db.setForeignKeyConstraintsEnabled(true)
                            // R-DB-02: onOpen solo haz la verificación;Limpiar la tabla de sombra y reconstruirla si falta,El error establece el indicador en proceso.
                            try {
                                synchronized(FTS_CREATE_LOCK) {
                                    val exists = db.query(
                                        "SELECT name FROM sqlite_master WHERE type='table' AND name='knowledge_chunks_fts'",
                                    ).use { it.moveToFirst() }
                                    if (!exists) {
                                        dropKnowledgeFtsTables(db)
                                        try {
                                            createKnowledgeChunkFtsTable(db)
                                            knowledgeFtsBroken = false
                                            io.zer0.common.Logger.i("MuseDb", "knowledge_chunks_fts Autocuración y reconstrucción exitosas")
                                        } catch (e2: Exception) {
                                            knowledgeFtsBroken = true
                                            io.zer0.common.Logger.e("MuseDb", "knowledge_chunks_fts La reconstrucción falló: ${e2.message}", e2)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                knowledgeFtsBroken = true
                                io.zer0.common.Logger.e("MuseDb", "knowledge_chunks_fts La verificación falló: ${e.message}", e)
                            }
                            // R-DB-05: verificar messages_fts;Reconstruir según la capacidad cuando falta,FTS5 Prioridad.
                            try {
                                synchronized(FTS_CREATE_LOCK) {
                                    val messageFtsExists = db.query(
                                        "SELECT name FROM sqlite_master WHERE type='table' AND name='messages_fts'",
                                    ).use { it.moveToFirst() }
                                    if (!messageFtsExists) {
                                        dropMessageFtsTables(db)
                                        createMessageFtsTable(db, fts5Available(db))
                                    } else {
                                        MessageFtsRuntime.useFts5 = detectMessageFtsMode(db)
                                    }
                                }
                            } catch (e: Exception) {
                                io.zer0.common.Logger.e("MuseDb", "messages_fts La verificación falló: ${e.message}", e)
                            }
                            // v1.107: WAL El patrón consta de setJournalMode(WRITE_AHEAD_LOGGING) habilitar,No hay configuraciones repetidas aquí
                            // v1.107: Pasivo checkpoint,Fusionar registros WAL en la base de datos principal
                            //   Nota: PRAGMA wal_checkpoint Conjunto de resultados de retorno,Debe utilizar consulta en lugar de execSQL
                            db.query("PRAGMA wal_checkpoint(PASSIVE)").use { it.moveToFirst() }
                        }
                    })
                    // v1.107: Establecer explícitamente el modo de registro WAL(Recuperación ante desastres redundante:Simultaneidad de lectura y escritura + recuperación ante fallos)
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    // Reparación de auditoría (0.3): Archive el repositorio antiguo antes de degradar la migración destructiva(.bak),Evite las reversiones beta/Cuando el canal retrocede
                    // Toda la base de datos se destruye silenciosamente y las conversaciones y los recuerdos no se pueden restaurar.onDestructiveMigration Devolución de llamada a la antigua biblioteca antes de la destrucción.
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onDestructiveMigration(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            super.onDestructiveMigration(db)
                            try {
                                val ctx = context.applicationContext
                                val dbFile = ctx.getDatabasePath("muse.db")
                                if (dbFile.exists()) {
                                    val bak = java.io.File(dbFile.parentFile, "muse.db.pre-destructive.bak")
                                    runCatching { if (bak.exists()) bak.delete() }
                                    runCatching {
                                        val wal = java.io.File(dbFile.parentFile, "muse.db-wal")
                                        val shm = java.io.File(dbFile.parentFile, "muse.db-shm")
                                        // Combinación de puntos de control primero WAL,Copia la biblioteca principal nuevamente.
                                        runCatching { db.query("PRAGMA wal_checkpoint(PASSIVE)").use { it.moveToFirst() } }
                                        dbFile.copyTo(bak, overwrite = true)
                                        runCatching { if (wal.exists()) wal.delete() }
                                        runCatching { if (shm.exists()) shm.delete() }
                                    }
                                }
                            } catch (e: Exception) {
                                io.zer0.common.Logger.w("MuseDb", "Error al degradar el archivo(No afecta la reconstrucción.): ${e.message}")
                            }
                        }
                    })
                    // La migración destructiva está prohibida: las degradaciones deben restaurarse mediante copias de seguridad durante el proceso de lanzamiento y las bibliotecas de los usuarios no se pueden borrar de forma silenciosa.
                    .build()
                    .also { dbInstance = it }
                    .also {
                        KnowledgeChunkFtsSelfHealer.install {
                            MuseDb.repairKnowledgeChunkFtsTable()
                        }
                    }
            }
        }
    }
}

/** B8-06: Complete las columnas de mensajes que faltan según el esquema actual(idempotente,Se omiten las columnas existentes). */
private fun ensureMessageColumns(db: androidx.sqlite.db.SupportSQLiteDatabase) {
    val existing = mutableSetOf<String>()
    db.query("PRAGMA table_info(messages)").use { cursor ->
        while (cursor.moveToNext()) {
            existing.add(cursor.getString(1))
        }
    }
    val columns = listOf(
        "thinkingSignature TEXT DEFAULT NULL",
        "thinkingEncryptedContent TEXT DEFAULT NULL",
        "moodSkin TEXT DEFAULT NULL",
        "mood TEXT DEFAULT NULL",
        "reflection TEXT DEFAULT NULL",
        "contentLength INTEGER NOT NULL DEFAULT 0",
        "deletedAt INTEGER DEFAULT NULL",
        "reaction TEXT DEFAULT NULL",
        "variantGroupId TEXT DEFAULT NULL",
        "variantIndex INTEGER NOT NULL DEFAULT 0",
        "variantCount INTEGER NOT NULL DEFAULT 1",
        "parentGroupId TEXT DEFAULT NULL",
        "attachmentsJson TEXT NOT NULL DEFAULT '[]'",
        "artifactIdsJson TEXT NOT NULL DEFAULT '[]'",
        "imageBase64Json TEXT NOT NULL DEFAULT '[]'",
        "ragCitationsJson TEXT NOT NULL DEFAULT '[]'",
        "citationUrlsJson TEXT NOT NULL DEFAULT '[]'",
        "imageUrlsJson TEXT NOT NULL DEFAULT '[]'",
        "favoriteTag TEXT DEFAULT NULL",
        // A5: Generar columnas de metadatos(Capa emergente de información del mensaje:modelo/Consume mucho tiempo/ficha)
        "durationMs INTEGER DEFAULT NULL",
        "promptTokens INTEGER DEFAULT NULL",
        "completionTokens INTEGER DEFAULT NULL",
        "reasoningTokens INTEGER DEFAULT NULL",
        "cachedTokens INTEGER DEFAULT NULL",
        // H11: El mensaje fuente del mensaje de traducción. id(Comparación y plegado de texto original.)
        "translationSourceId TEXT DEFAULT NULL",
                        // v2.6.15: Tokens de escritura en caché (separados de cachedTokens; comportamiento anterior NULL = desconocido)
        "cacheWriteTokens INTEGER DEFAULT NULL",
        // v2.6.16: Cita de audio del mensaje de voz + duración (línea antigua NULL / 0 = mensajes no de voz)
        "voiceUri TEXT DEFAULT NULL",
        "voiceDurationMs INTEGER NOT NULL DEFAULT 0",
    )
    columns.forEach { spec ->
        val name = spec.substringBefore(' ')
        if (name !in existing) {
            db.execSQL("ALTER TABLE messages ADD COLUMN $spec")
        }
    }
}

/** B8-06: Complete las columnas que faltan de use_records según el esquema actual(idempotente,Se omiten las columnas existentes). */
private fun ensureUsageRecordColumns(db: androidx.sqlite.db.SupportSQLiteDatabase) {
    val existing = mutableSetOf<String>()
    db.query("PRAGMA table_info(usage_records)").use { cursor ->
        while (cursor.moveToNext()) {
            existing.add(cursor.getString(1))
        }
    }
    val columns = listOf(
        // v2.6.15: escrituras en caché token(Separar con cached_tokens;viejo comportamiento 0)
        "cache_write_tokens INTEGER NOT NULL DEFAULT 0",
    )
    columns.forEach { spec ->
        val name = spec.substringBefore(' ')
        if (name !in existing) {
            db.execSQL("ALTER TABLE usage_records ADD COLUMN $spec")
        }
    }
}

/** B8-06: Complete las columnas faltantes de sesiones según el esquema actual(idempotente,Se omiten las columnas existentes). */
private fun ensureSessionColumns(db: androidx.sqlite.db.SupportSQLiteDatabase) {
    val existing = mutableSetOf<String>()
    db.query("PRAGMA table_info(sessions)").use { cursor ->
        while (cursor.moveToNext()) {
            existing.add(cursor.getString(1))
        }
    }
    val columns = listOf(
        "assistantId TEXT NOT NULL DEFAULT 'default'",
        "pinned INTEGER NOT NULL DEFAULT 0",
        "folderId TEXT DEFAULT ''",
        "archived INTEGER NOT NULL DEFAULT 0",
        "isAgentSession INTEGER NOT NULL DEFAULT 0",
        "isLocked INTEGER NOT NULL DEFAULT 0",
        "messageCount INTEGER NOT NULL DEFAULT 0",
        "deletedAt INTEGER DEFAULT NULL",
        "parentSessionId TEXT DEFAULT NULL",
        "childCount INTEGER NOT NULL DEFAULT 0",
        "lastReadMessageId TEXT DEFAULT NULL",
        "lastReadCount INTEGER NOT NULL DEFAULT 0",
        "sortOrder INTEGER NOT NULL DEFAULT 0",
        "proactiveNextTriggerAt INTEGER DEFAULT NULL",
        "skillIdsJson TEXT NOT NULL DEFAULT '[]'",
        "isMiniPhone INTEGER NOT NULL DEFAULT 0",
    )
    columns.forEach { spec ->
        val name = spec.substringBefore(' ')
        if (name !in existing) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN $spec")
        }
    }
}

/**
 * Complete las columnas que faltan de group_chat_messages según el esquema actual.
 *
 * La cadena de actualización de versiones antiguas puede omitir la migración mejorada de mensajes de chat grupal(susurrar/Cotización Responder/Tipo de mensaje/Accesorios),
 * Causa Suma de comprobación de la habitación "Migration didn't properly handle: group_chat_messages".
 * Reconstruya la tabla y copie los datos cuando falten columnas o los valores predeterminados clave no coincidan,Si se cumple el esquema actual, omítalo directamente.
 */
private fun ensureGroupChatMessageColumns(db: androidx.sqlite.db.SupportSQLiteDatabase) {
    val tableExists = db.query(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name='group_chat_messages'",
    ).use { it.moveToFirst() }
    val expectedColumns = listOf(
        "id", "chatId", "senderType", "senderId", "senderName", "body",
        "imageBase64Json", "timestamp", "mood", "reasoning",
        "whisper_target_id", "reply_to_id", "messageType", "fileAttachmentsJson",
        "videoFileUri",
    )
    val existing = mutableMapOf<String, String?>()
    if (tableExists) {
        db.query("PRAGMA table_info(group_chat_messages)").use { cursor ->
            while (cursor.moveToNext()) {
                existing[cursor.getString(1)] = cursor.getString(4)
            }
        }
    }

    val expectedDefaults = mapOf(
        "imageBase64Json" to "'[]'",
        "timestamp" to "'0'",
        "whisper_target_id" to "NULL",
        "reply_to_id" to "NULL",
        "messageType" to "'normal'",
        "fileAttachmentsJson" to "'[]'",
    )
    fun defaultMatches(name: String): Boolean {
        val expected = expectedDefaults[name] ?: return true
        val actual = existing[name]
        return if (expected == "NULL") {
            actual == null || actual.equals("NULL", ignoreCase = true)
        } else if (name == "timestamp") {
            actual == "0" || actual == "'0'"
        } else {
            actual?.equals(expected, ignoreCase = true) == true
        }
    }
    val complete = tableExists &&
        expectedColumns.all { it in existing } &&
        expectedDefaults.keys.all(::defaultMatches)
    if (complete) return

    val createSql = """
        CREATE TABLE IF NOT EXISTS group_chat_messages_new (
            id TEXT NOT NULL PRIMARY KEY,
            chatId TEXT NOT NULL,
            senderType TEXT NOT NULL,
            senderId TEXT NOT NULL,
            senderName TEXT NOT NULL,
            body TEXT NOT NULL,
            imageBase64Json TEXT NOT NULL DEFAULT '[]',
            timestamp INTEGER NOT NULL DEFAULT 0,
            mood TEXT,
            reasoning TEXT,
            whisper_target_id TEXT DEFAULT NULL,
            reply_to_id TEXT DEFAULT NULL,
            messageType TEXT NOT NULL DEFAULT 'normal',
            fileAttachmentsJson TEXT NOT NULL DEFAULT '[]',
            videoFileUri TEXT DEFAULT NULL
        )
    """.trimIndent()
    db.execSQL("DROP TABLE IF EXISTS group_chat_messages_new")
    db.execSQL(createSql)
    if (tableExists) {
        val fallback = mapOf(
            "id" to "''", "chatId" to "''", "senderType" to "''", "senderId" to "''",
            "senderName" to "''", "body" to "''", "imageBase64Json" to "'[]'", "timestamp" to "0",
            "mood" to "NULL", "reasoning" to "NULL", "whisper_target_id" to "NULL",
            "reply_to_id" to "NULL", "messageType" to "'normal'", "fileAttachmentsJson" to "'[]'",
            "videoFileUri" to "NULL",
        )
        val insertColumns = expectedColumns.joinToString(", ") { "`$it`" }
        val selectExpr = expectedColumns.joinToString(", ") { name ->
            if (name in existing) "`$name`" else fallback[name] ?: "''"
        }
        db.execSQL(
            "INSERT INTO group_chat_messages_new ($insertColumns) SELECT $selectExpr FROM group_chat_messages",
        )
        db.execSQL("DROP TABLE group_chat_messages")
        db.execSQL("ALTER TABLE group_chat_messages_new RENAME TO group_chat_messages")
    } else {
        db.execSQL("ALTER TABLE group_chat_messages_new RENAME TO group_chat_messages")
    }
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS index_group_chat_messages_chatId ON group_chat_messages(chatId)",
    )
}

/**
 * v1.0.62 fix: Asegúrese de que existan las tablas generate_checkpoints y group_chat_generation_ledger.
 * Compatible desde v1.0.60 (DB version 73) escenario de actualización, estas tablas no existen en el esquema v1.0.60.
 */
private fun ensureGenerationTables(db: androidx.sqlite.db.SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `generation_checkpoints` (
            `assistantMessageId` TEXT NOT NULL,
            `sessionId` TEXT NOT NULL,
            `userMessageId` TEXT NOT NULL,
            `content` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            PRIMARY KEY(`assistantMessageId`),
            FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_generation_checkpoints_sessionId` ON `generation_checkpoints` (`sessionId`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_generation_checkpoints_createdAt` ON `generation_checkpoints` (`createdAt`)")
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `group_chat_generation_ledger` (
            `id` TEXT NOT NULL,
            `chatId` TEXT NOT NULL,
            `mode` TEXT NOT NULL,
            `round` INTEGER NOT NULL,
            `memberIndex` INTEGER NOT NULL,
            `memberIdsJson` TEXT NOT NULL DEFAULT '[]',
            `status` TEXT NOT NULL DEFAULT 'running',
            `createdAt` INTEGER NOT NULL DEFAULT 0,
            `updatedAt` INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`chatId`) REFERENCES `group_chats`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_chat_generation_ledger_chatId` ON `group_chat_generation_ledger` (`chatId`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_chat_generation_ledger_status` ON `group_chat_generation_ledger` (`status`)")
}
