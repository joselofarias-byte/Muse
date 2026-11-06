@file:Suppress("TooGenericExceptionCaught", "ThrowsCount")

package io.zer0.muse.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import io.zer0.memory.fact.FactDb
import io.zer0.memory.fact.FactEntity
import io.zer0.memory.summary.CompiledSectionEntity
import io.zer0.memory.summary.ScopedCompiledSectionEntity
import io.zer0.memory.summary.DailyStateEntity
import io.zer0.memory.summary.MemoryDb
import io.zer0.memory.summary.SessionSummaryEntity
import io.zer0.memory.summary.ContextCheckpointEntity
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.stats.AutoBackupLogDao
import io.zer0.muse.data.stats.AutoBackupLogEntity
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.data.session.MessageEntity
import io.zer0.muse.data.session.MessageImageStore
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.lorebook.LorebookEntity
import io.zer0.muse.data.skill.SkillEntity
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.data.quickmsg.QuickMessageEntity
import io.zer0.muse.data.promptinjection.PromptInjectionEntity
import io.zer0.muse.data.session.FolderEntity
import io.zer0.muse.data.groupchat.GroupChatEntity
import io.zer0.muse.data.groupchat.GroupChatMessageEntity
import io.zer0.muse.data.schedule.ScheduledTaskEntity
import io.zer0.muse.data.schedule.ScheduledTaskExecutionEntity
import io.zer0.muse.data.knowledge.KnowledgeDocEntity
import io.zer0.muse.data.knowledge.KnowledgeChunkEntity
import io.zer0.muse.data.experience.ExperienceEntity
import io.zer0.muse.data.milestone.MilestoneEntity
import io.zer0.muse.data.agentdm.AgentMessageEntity
// P0-10: Entidad de dominio de sesión que faltaba anteriormente(Finalización de la copia de seguridad)
import io.zer0.muse.data.session.ConversationEventEntity
import io.zer0.muse.data.session.ConversationTurnEntity
import io.zer0.muse.data.session.MessagePartEntity
import io.zer0.muse.data.session.MessageOutboxEntity
// P0-10: Restantes entidades de datos de usuario faltantes(Historial de traducción/base de conocimientos/hilo infantil/rueda de herramientas/cabeza de rama/Memoria de chat grupal)
import io.zer0.muse.data.knowledge.KnowledgeBaseEntity
import io.zer0.muse.data.session.ToolRoundEntity
import io.zer0.muse.data.session.SessionBranchHeadEntity
import io.zer0.muse.data.groupchat.GroupChatMemoryEntity
import io.zer0.muse.data.subagent.SubagentThreadEntity
import io.zer0.muse.ui.translate.TranslateHistoryEntity
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedWriter
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Phase 5-I: Exportación de copia de seguridad/Servicios de importación.
 *
 * Phase 7 Expandir:Incluir datos completos de memoria.db + hechos.db(4 Zhang Biao).
 * Phase 8.9 Expandir:Se agregó carga en la nube S3/WebDAV/Descargar.
 * Phase 12 Expandir:Copia de seguridad completa de la tabla(MuseDb Todas las tablas de datos del usuario + instantánea de la configuración del DataStore).
 *
 * diseño:
 *  - usar SAF (Storage Access Framework) Permitir que los usuarios elijan exportar/Ubicación de importación
 *  - JSON El formato serializa todas las sesiones + mensajes + datos de memoria + tablas de extensión
 *  - Campo de número de versión [Backup.version] Facilitar la migración posterior
 *  - tres independientes Room DB(MuseDb/MemoryDb/FactDb)Abrir asuntos por separado
 *  - Copia de seguridad en la nube:serialización local → bytes → CloudBackupService.uploadBackupWithLatest
 *  - Recuperación de la nube:CloudBackupService.downloadLatestBackup → bytes → Deserializar importación
 *
 * Límite:
 *  - Gran volumen de datos(10000+ noticias)JSON Posibilidad de serialización única OOM,Deja fragmentos posteriores
 *  - No contiene imagen binaria(sólo mantener URL,URL puede fallar)
 */
internal suspend fun <T> withBackupImportWriteGate(block: suspend () -> T): T {
    require(io.zer0.common.ProcessWriteGate.begin()) { "Ya se ha importado una copia de seguridad o la recuperación está en curso,No repetir operaciones" }
    return try {
        block()
    } finally {
        io.zer0.common.ProcessWriteGate.end()
    }
}

@Suppress(
    "LongParameterList",
    "TooGenericExceptionCaught",
    "ThrowsCount",
) // Las construcciones de inyección de dependencia y la recuperación entre tiendas deben preservar límites explícitos para evitar cambios de comportamiento a gran escala.
class BackupService(
    private val db: MuseDb,
    private val memoryDb: MemoryDb,
    private val factDb: FactDb,
    private val cloudBackupService: CloudBackupService,
    private val settings: SettingsRepository,
    /** F-04: Persistencia del registro de respaldo(La página de diagnóstico es visible + lee los registros de clasificación de fallas de verificación). */
    private val autoBackupLogDao: AutoBackupLogDao,
    /** v1.0.74: Reconstruir el índice FTS después de la importación(La mensajería en línea evita la sincronización FTS). */
    private val sessionRepository: io.zer0.muse.data.session.SessionRepository,
    /** Libro mayor de la fase de recuperación de bases de datos cruzadas; no guarda texto de respaldo ni valores de configuración confidenciales. */
    private val restoreJournal: RestoreJournal,
    /** Conserve las copias de seguridad de destino y preimporte puntos de recuperación en todos los procesos. */
    private val restoreStagingStore: RestoreStagingStore,
    /** F-27: contexto de aplicación — Localice la ruta del archivo de la base de datos y el directorio de descompresión temporal. */
    private val context: Context,
    /** F-27: almacenamiento de hechos — Reconstruya el índice FTS de hechos después de la restauración automática de la copia de seguridad. */
    private val factStore: io.zer0.memory.fact.FactStore,
    /** P0-10: Proveedor de subbiblioteca — base de datos del subasistente(facts_<id>.db)enumeración/Escribir. */
    private val factDbProvider: io.zer0.memory.fact.FactDbProvider,
    /** Después de una recuperación exitosa, limpie la imagen lateral a la que ya no hacen referencia los mensajes antiguos. */
    private val messageImageStore: MessageImageStore,
    /** Invalidar el estado HNSW derivado de RagService después de la recuperación;null Pruebas de compatibilidad/Antiguo montaje. */
    private val ragIndexInvalidator: (suspend () -> Unit)? = null,
    /**
     * B-23: Tamaño máximo de copia de seguridad JSON única(bytes).
     *
     * Este límite sólo se aplica a"Realmente necesita un análisis completo"camino(La copia de seguridad JSON única debe descodificarse en la memoria,
     * De lo contrario malicioso/Corromper la copia de seguridad hará que el proceso OOM). Manténgase compatible con versiones anteriores,El valor predeterminado sigue siendo 64MB;
     * Si la empresa tiene una copia de seguridad JSON única legal que excede este volumen,Permite escalar mediante parámetros de construcción.
     * NDJSON Análisis ruta por línea,No consumir el límite superior(defensa natural OOM).
     */
    private val singleJsonMaxBytes: Long = MAX_SINGLE_JSON_BACKUP_BYTES,
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /** F-27: Bloqueo Mutex para copia de seguridad y recuperación automáticas,Prevenir la recuperación simultánea/Haga una copia de seguridad de los archivos de la biblioteca mutua. */
    private val autoRestoreLock = Any()

    private suspend fun invalidateRagAfterRestore() {
        resultOf { ragIndexInvalidator?.invoke() }
            .onError { msg, _ -> Logger.w("BackupService", "El procesamiento de invalidación del índice HNSW falla después de la recuperación: $msg") }
    }

    /**
     * Realizar copias de seguridad de las estructuras de datos.
     *
     * version=1: solo sessions + messages(Phase 5-I)
     * version=2: Contiene datos de la memoria(Phase 7)— Nuevos campos con valores predeterminados,Compatible con la importación de copias de seguridad antiguas
     * version=3: Copia de seguridad completa de la tabla(Phase 12)— Agregue todas las tablas de datos de usuario de MuseDb + instantánea de configuración de DataStore
     */
    @Serializable
    data class Backup(
        val version: Int = 3,
        val exportedAt: Long,
        // ── Campo original(v1/v2) ──
        val sessions: List<SessionEntity>,
        val messages: List<MessageEntity>,
        val sessionSummaries: List<SessionSummaryEntity> = emptyList(),
        val contextCheckpoints: List<ContextCheckpointEntity> = emptyList(),
        val dailyStates: List<DailyStateEntity> = emptyList(),
        val compiledSections: List<CompiledSectionEntity> = emptyList(),
        val scopedCompiledSections: List<ScopedCompiledSectionEntity> = emptyList(),
        val facts: List<FactEntity> = emptyList(),
        // ── v3 Nuevo: MuseDb mesa extendida ──
        val assistants: List<AssistantEntity> = emptyList(),
        val lorebooks: List<LorebookEntity> = emptyList(),
        val skills: List<SkillEntity> = emptyList(),
        val artifacts: List<ArtifactEntity> = emptyList(),
        val quickMessages: List<QuickMessageEntity> = emptyList(),
        val promptInjections: List<PromptInjectionEntity> = emptyList(),
        val folders: List<FolderEntity> = emptyList(),
        val groupChats: List<GroupChatEntity> = emptyList(),
        val groupChatMessages: List<GroupChatMessageEntity> = emptyList(),
        val scheduledTasks: List<ScheduledTaskEntity> = emptyList(),
        val scheduledTaskExecutions: List<ScheduledTaskExecutionEntity> = emptyList(),
        val knowledgeDocs: List<KnowledgeDocEntity> = emptyList(),
        val knowledgeChunks: List<KnowledgeChunkEntity> = emptyList(),
        val experiences: List<ExperienceEntity> = emptyList(),
        val milestones: List<MilestoneEntity> = emptyList(),
        val agentMessages: List<AgentMessageEntity> = emptyList(),
        // ── P0-10: entidad previamente desaparecida(Recuperar datos perdidos después de cambiar de dispositivo)──
        val quickNotes: List<io.zer0.muse.data.quicknote.QuickNoteEntity> = emptyList(),
        val worldBookEntries: List<io.zer0.muse.worldbook.WorldBookEntryEntity> = emptyList(),
        val conversationEvents: List<ConversationEventEntity> = emptyList(),
        val conversationTurns: List<ConversationTurnEntity> = emptyList(),
        val messageParts: List<MessagePartEntity> = emptyList(),
        val messageOutboxes: List<MessageOutboxEntity> = emptyList(),
        // P0-10: Datos de la base de datos del subasistente(facts_<id>.db)— assistantId → facts
        val scopedFacts: Map<String, List<FactEntity>> = emptyMap(),
        // P0-10: Restantes entidades de datos de usuario que faltaban anteriormente(Recuperar datos perdidos después de cambiar de dispositivo)
        val translateHistories: List<TranslateHistoryEntity> = emptyList(),
        val knowledgeBases: List<KnowledgeBaseEntity> = emptyList(),
        // v2.4.6: Carpeta de la base de conocimientos(Contiene carpetas vacías) — Datos de usuario,Migrar con copia de seguridad
        val knowledgeFolders: List<io.zer0.muse.data.knowledge.KnowledgeFolderEntity> = emptyList(),
        val subagentThreads: List<SubagentThreadEntity> = emptyList(),
        val toolRounds: List<ToolRoundEntity> = emptyList(),
        val sessionBranchHeads: List<SessionBranchHeadEntity> = emptyList(),
        /** v2.6.7(P1-1): estado de relación de rol — Los activos del usuario deben conservarse al cambiar de máquina. */
        val assistantRelationships: List<io.zer0.muse.data.relationship.AssistantRelationshipEntity> = emptyList(),
        /** v2.6.7: Sesión para hacer — Activos del usuario ("lo escribi"Si no puedes reemplazarlo, simplemente tíralo). */
        val sessionTodos: List<io.zer0.muse.data.todo.SessionTodoEntity> = emptyList(),
        /**
         * v2.6.9: Preocupado por el libro mayor —— El asistente recordó "algo que preguntarle más tarde".
         *
         * Este es el combustible para los mensajes proactivos y la sustancia de la relación entre este asistente y este usuario.
         * No retroceder significa que después de cambiar la máquina, olvidará todo lo que dijiste, y esa es la parte que la hace parecer humana.
         */
        val pendingThoughts: List<io.zer0.muse.data.proactive.PendingThoughtEntity> = emptyList(),
        /** v2.6.9: cuenta de resultados —— Haga clic para abrir/Responder/Historia tachada, de aquí se aprende la intimidad. */
        val proactiveOutcomes: List<io.zer0.muse.data.proactive.ProactiveOutcomeEntity> = emptyList(),
        /** v2.6.9: Intimidad relacional y niveles de iniciativa. */
        val proactiveRelations: List<io.zer0.muse.data.proactive.ProactiveRelationEntity> = emptyList(),
        val groupChatMemories: List<GroupChatMemoryEntity> = emptyList(),
        /**
         * v2.6.8: entidad de tarjeta (`card_entities`).
         *
         * Una vez acoplada la tarjeta, abandona el flujo del mensaje y deja solo una barra de marcador de posición en el mensaje; Si no se realiza una copia de seguridad de esta tabla, la tarjeta
         * Todas las tarjetas acopladas y flotantes se pierden permanentemente y no se pueden reconstruir a partir del mensaje (el mensaje solo almacena una referencia de cardId).
         * Para formato de línea [CardEntityRecord], desacoplado de la entidad Habitación, consulte la descripción de esta clase para conocer el motivo.
         */
        val cardEntities: List<CardEntityRecord> = emptyList(),
        // ── v3 Nuevo: DataStore Instantánea de configuración ──
        val settingsSnapshot: Map<String, String> = emptyMap(),
        // ── v4 Nuevo: Instantánea de almacenamiento de archivos(nombre de archivo → contenido)──
        // Canales de cobertura/conector/Comentario/Bandeja de entrada/Registro y configuración de complementos;El campo de credenciales en sí ya es texto cifrado.
        val fileStores: Map<String, String> = emptyMap(),
        // ── v4.1 Nuevo: Instantánea del archivo del directorio(Ruta relativa a filesDir → Base64 contenido)──
        // Portada note_attachments/(Nota adjunta)con avatar/(Copia privada de avatar);
        // Estas dos categorías no estaban respaldadas en absoluto antes.,Después de la restauración, todas las referencias dejan de ser válidas. ver [BackupBinaryFiles].
        // Si la copia de seguridad anterior no tiene este campo, la deserialización estará vacía. map,Volver a la normalidad;Este campo se ignora cuando una versión anterior lee una nueva copia de seguridad.
        val binaryFiles: Map<String, String> = emptyMap(),
    )

    /** La protección de copia de seguridad vacía debe cubrir todas las tablas de exportación,No puedo simplemente verificar sesiones/mensajes. */
    private fun Backup.hasAnyData(): Boolean = sessions.isNotEmpty() || messages.isNotEmpty() || sessionSummaries.isNotEmpty() ||
        contextCheckpoints.isNotEmpty() ||
        dailyStates.isNotEmpty() || compiledSections.isNotEmpty() || scopedCompiledSections.isNotEmpty() ||
        facts.isNotEmpty() || assistants.isNotEmpty() || lorebooks.isNotEmpty() || skills.isNotEmpty() ||
        artifacts.isNotEmpty() || quickMessages.isNotEmpty() || promptInjections.isNotEmpty() ||
        folders.isNotEmpty() || groupChats.isNotEmpty() || groupChatMessages.isNotEmpty() ||
        scheduledTasks.isNotEmpty() || scheduledTaskExecutions.isNotEmpty() || knowledgeDocs.isNotEmpty() ||
        knowledgeChunks.isNotEmpty() || experiences.isNotEmpty() || milestones.isNotEmpty() ||
        agentMessages.isNotEmpty() || quickNotes.isNotEmpty() || worldBookEntries.isNotEmpty() ||
        conversationEvents.isNotEmpty() || conversationTurns.isNotEmpty() || messageParts.isNotEmpty() ||
        messageOutboxes.isNotEmpty() || scopedFacts.isNotEmpty() ||
        translateHistories.isNotEmpty() || knowledgeBases.isNotEmpty() || knowledgeFolders.isNotEmpty() ||
        subagentThreads.isNotEmpty() ||
        toolRounds.isNotEmpty() || sessionBranchHeads.isNotEmpty() || assistantRelationships.isNotEmpty() ||
        sessionTodos.isNotEmpty() ||
        pendingThoughts.isNotEmpty() || proactiveOutcomes.isNotEmpty() || proactiveRelations.isNotEmpty() ||
        groupChatMemories.isNotEmpty() ||
        cardEntities.isNotEmpty() ||
        settingsSnapshot.isNotEmpty() || fileStores.isNotEmpty() || binaryFiles.isNotEmpty()

    /**
     * Exporte todos los datos de sesión + mensaje + memoria al URI especificado.
     * @return Número de sesiones exportadas + número de mensajes(para indicaciones de la interfaz de usuario)
     */
    suspend fun export(context: Context, uri: Uri, includeSecrets: Boolean = false): Pair<Int, Int> {
        // P0-1: Contraseña no válida(Keystore perdido)Rechazar la degradación para borrar la exportación de texto cuando,Solicitar reinicio en lugar de escribir silenciosamente el disco en texto claro
        requireBackupPasswordAvailable(context)
        val backup = buildBackup(includeSecrets)
        val text = json.encodeToString(Backup.serializer(), backup)
        // F-28: La exportación local utiliza el mismo formato de cifrado que la copia de seguridad en la nube cuando se configura una contraseña de copia de seguridad.(MENC),retirar dinero
        // "Cifrado de archivos de respaldo,Sólo tu contraseña puede desbloquearlo."promesa de producto;Cuando no se configura ninguna contraseña, el texto sin formato sigue siendo compatible con el proceso anterior.
        val password = settings.cloudBackupConfigFlow.first().backupPassword
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { os: OutputStream ->
                if (password.isNotEmpty()) {
                    val encrypted = BackupCrypto.encrypt(text.toByteArray(Charsets.UTF_8), password)
                    os.write(encrypted)
                } else {
                    OutputStreamWriter(os, Charsets.UTF_8).use { writer ->
                        writer.write(text)
                    }
                }
            } ?: error(context.getString(R.string.backup_cannot_write, uri))
        }
        return backup.sessions.size to backup.messages.size
    }

    /**
     * Importe datos de sesión + mensaje + memoria desde el URI especificado.
     * Estrategia: Borrar todas las tablas en tres bases de datos → Insertar datos de respaldo(Versión simplificada,Sin fusión ni deduplicación).
     * @param backupPassword B-8: Contraseña ingresada por el usuario al importar una copia de seguridad cifrada;Contraseña para recurrir a la configuración de copia de seguridad en la nube cuando esté vacía.
     * @return Conversaciones importadas + mensajes
     */
    suspend fun import(context: Context, uri: Uri, backupPassword: String? = null): Pair<Int, Int> = withContext(Dispatchers.IO) {
        withBackupImportWriteGate {
            val rawInput = context.contentResolver.openInputStream(uri)
                ?: error(context.getString(R.string.backup_cannot_read, uri))
            rawInput.use { input ->
                val buffered = BufferedInputStream(input)
                buffered.mark(4)
                val magic = ByteArray(4)
                var read = 0
                while (read < magic.size) {
                    val chunk = buffered.read(magic, read, magic.size - read)
                    if (chunk < 0) break
                    if (chunk == 0) continue
                    read += chunk
                }
                buffered.reset()
                if (read == 4 && BackupCrypto.isEncrypted(magic)) {
                    // F-28: Cifrar copia de seguridad local(MENC magic)——Después de leer y descifrar, realice un análisis unificado.
                    // B-8: Priorizar las contraseñas ingresadas por el usuario,Es conveniente recuperar la antigua copia de seguridad cifrada antes de olvidar la contraseña.
                    val password = backupPassword?.takeIf { it.isNotEmpty() }
                        ?: settings.cloudBackupConfigFlow.first().backupPassword
                    require(password.isNotEmpty()) {
                        context.getString(R.string.backup_encrypted_need_password)
                    }
                    val rest = buffered.readBytes()
                    check(rest.size.toLong() <= singleJsonMaxBytes) {
                        singleJsonTooLargeConfiguredMessage(rest.size.toLong())
                    }
                    val decrypted = BackupCrypto.decrypt(magic + rest, password)
                    val text = decrypted.toString(Charsets.UTF_8)
                    importReader(context, java.io.BufferedReader(java.io.StringReader(text)))
                } else if (read == 4 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()) {
                    // Compatible con versiones anteriores/Copia de seguridad ZIP exportada por el Administrador de archivos,Seleccione la primera entrada de copia de seguridad JSON o NDJSON.
                    ZipInputStream(buffered).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null && (entry.isDirectory || !isBackupEntry(entry.name))) {
                            entry = zip.nextEntry
                        }
                        if (entry == null) {
                            error(context.getString(R.string.backup_format_unrecognized))
                        }
                        importReader(context, zip.bufferedReader(Charsets.UTF_8))
                    }
                } else {
                    importReader(context, buffered.bufferedReader(Charsets.UTF_8))
                }
            }
        }
    }

    /** Identifica archivos JSON/NDJSON en el ZIP de respaldo local que pueden contener la cantidad total de datos. */
    private fun isBackupEntry(name: String): Boolean {
        val lower = name.lowercase()
        val fileName = lower.substringAfterLast('/')
        // ZIP Traiga siempre el archivo de manifiesto/metadatos,Estos archivos no son texto de copia de seguridad completa de Muse.
        if (fileName.startsWith("manifest.") || fileName.startsWith("metadata.") || fileName == "index.json") {
            return false
        }
        return lower.endsWith(".json") || lower.endsWith(".ndjson") || lower.contains("backup")
    }

    /** Importe JSON o NDJSON único desde un lector ubicado al principio del cuerpo. */
    private suspend fun importReader(context: Context, reader: java.io.BufferedReader): Pair<Int, Int> {
        reader.use { source ->
            val firstLine = generateSequence { source.readLine() }
                .firstOrNull { !it.isNullOrBlank() }
                ?: error(context.getString(R.string.backup_format_unrecognized))
            val isNdJson = resultOf {
                val obj = json.decodeFromString(JsonObject.serializer(), firstLine)
                obj["type"]?.let { (it as? JsonPrimitive)?.content } == "meta"
            }.getOrNull() ?: false
            if (isNdJson) {
                // B4-07: La primera línea se ha consumido.,Continuar transmitiendo la lectura desde el lector,No leer completo
                val rest = sequence {
                    while (true) {
                        val line = source.readLine() ?: break
                        yield(line)
                    }
                }
                val imported = applyNdJsonStreamingWithImageCleanup(sequenceOf(firstLine) + rest)
                warmUpRoomAfterImport()
                return imported
            }
            // B-25: Copia de seguridad JSON única OOM — Volumen UTF-8 acumulativo al leer línea por línea,Si se excede el límite superior, se generará un error inmediatamente.
            val text = readSingleJsonWithLimit(source, firstLine)
            val backup = resultOf { json.decodeFromString(Backup.serializer(), text) }
                .onError { msg, t -> Logger.w("BackupService", "Error en el análisis de la copia de seguridad JSON única", t) }
                .getOrNull()
                ?: error(context.getString(R.string.backup_format_unrecognized))
            if (!backup.hasAnyData()) {
                error("Archivo de copia de seguridad vacío,Importación rechazada")
            }
            val imported = applyBackup(backup)
            warmUpRoomAfterImport()
            return imported
        }
    }

    /**
     * B-25: Lea el texto de copia de seguridad JSON único y verifique el límite superior del tamaño.
     *
     * Acumular por bytes UTF-8 al leer línea por línea [primera línea + líneas siguientes],Una vez que excede [MAX_SINGLE_JSON_BACKUP_BYTES]
     * tirar inmediatamente [IllegalArgumentException] Dar un límite superior claro,Ya no leer en silencio textos enteros muy extensos.
     *
     * @param reader Ya consumido [firstLine] de reader(La primera línea se lee durante la fase de reconocimiento de formato.)
     * @param firstLine Se lee la primera línea que no está en blanco.(Incluido en volumen)
     */
    private fun readSingleJsonWithLimit(reader: java.io.BufferedReader, firstLine: String): String {
        var cumulative = firstLine.toByteArray(Charsets.UTF_8).size.toLong()
        check(withinConfigurableLimit(cumulative, 0L)) {
            singleJsonTooLargeConfiguredMessage(cumulative)
        }
        return buildString {
            appendLine(firstLine)
            while (true) {
                val line = reader.readLine() ?: break
                cumulative += line.toByteArray(Charsets.UTF_8).size.toLong()
                check(withinConfigurableLimit(cumulative, 0L)) {
                    singleJsonTooLargeConfiguredMessage(cumulative)
                }
                appendLine(line)
            }
        }
    }

    /**
     * B-23: con configurable [singleJsonMaxBytes] Determine si el volumen de copia de seguridad JSON único todavía está dentro del rango permitido para el límite superior.
     * [MAX_SINGLE_JSON_BACKUP_BYTES] es el límite superior predeterminado para la compatibilidad con versiones anteriores,Los parámetros de construcción se pueden ajustar para que sean más grandes para dar cabida a respaldos legales más grandes.
     */
    private fun withinConfigurableLimit(cumulativeUtf8Bytes: Long, additionalUtf8Bytes: Long): Boolean =
        cumulativeUtf8Bytes <= singleJsonMaxBytes - additionalUtf8Bytes

    /** B-23: Generar un único mensaje de error de desbordamiento de copia de seguridad JSON,Refleja el límite superior real de la configuración de la instancia.(Predeterminado 64MB). */
    private fun singleJsonTooLargeConfiguredMessage(cumulativeUtf8Bytes: Long): String =
        "Volumen de copia de seguridad JSON único ${(cumulativeUtf8Bytes + 1024 * 1024 - 1) / (1024 * 1024)}MB Superar el límite superior " +
            "${singleJsonMaxBytes / (1024 * 1024)}MB,Importación rechazada"

    /**
     * Phase 11.2.2: Exportación de fragmentos en streaming(NDJSON Formato).
     *
     * Resolver grandes volúmenes de datos(10000+ noticias)Problema único de OOM de serialización JSON.
     * Formato:primera linea meta({type:"meta",version,counts...}),Cada línea subsiguiente contiene un registro.
     *
     * @return Número de sesiones exportadas + número de mensajes
     */
    suspend fun exportStreaming(context: Context, uri: Uri, includeSecrets: Boolean = false): Pair<Int, Int> = withContext(Dispatchers.IO) {
        // P0-1: Contraseña no válida(Keystore perdido)Rechazar la degradación para borrar la exportación de texto cuando,El mismo juicio que subido a la nube.
        requireBackupPasswordAvailable(context)
        val summary = context.contentResolver.openOutputStream(uri)?.use { os: OutputStream ->
            BufferedWriter(OutputStreamWriter(os, Charsets.UTF_8)).use { writer ->
                val wrote = writeNdJson(writer, includeSecrets)
                writer.flush()
                Logger.i("BackupService", "Exportación de streaming completada: ${wrote.sessions} sesión, ${wrote.messages} noticias")
                wrote
            }
        } ?: error(context.getString(R.string.backup_cannot_write, uri))
        summary.sessions to summary.messages
    }

    /**
     * B-25: NDJSON Implementación pública de extracción de datos completos + escritura fila por fila.
     *
     * por [exportStreaming](Archivo SAF local)con [exportToCloud](Flujo de bytes en la nube)Reutilizar.
     * Serializar artículo por artículo y escribir línea por línea,Los mensajes se extraen en lotes por sesión,No es una porción completa buildBackup + encodeToString,
     * evitar"Backup Objeto completo + cadena enorme + matriz de bytes"OOM causado por tres copias de memoria que residen al mismo tiempo.
     *
     * @return Recuento de tablas principales(Para que las personas que llaman impriman registros de diagnóstico de báscula)
     */
    private suspend fun writeNdJson(writer: BufferedWriter, includeSecrets: Boolean = false): NdJsonExportSummary {
        // B-32: Todas las lecturas de la tabla MuseDb se incluyen en una sola transacción,Obtenga una instantánea consistente.
        // Implementación antigua de getAll + no transaccional tabla por tabla por sesión observeBySession,Al escribir la biblioteca al mismo tiempo que se ejecuta,
        // Diferentes mesas/Las sesiones pueden leer datos en diferentes momentos → Generar copia de seguridad rota. Cambiar a db.withTransaction
        // Lea todos los datos de MuseDb a la vez,Una vez confirmada la transacción, se escribe en forma de transmisión. NDJSON,No ocupa tiempo IO durante la transacción.
        // Las transacciones solo cubren lecturas(pronto),La escritura formal fluye línea por línea fuera de la transacción,No afecta el objetivo de evitar OOM para exportaciones de streaming.;
        // Las transacciones se ejecutan en Dispatchers.IO,No bloquea el hilo principal.
        // memoryDb/factDb/settings como biblioteca independiente,No se puede fusionar con la transacción MuseDb,Todavía lee por separado.
        val muse = db.withTransaction {
            val s = db.sessionDao().observeAll().first()
            val msgs = s.flatMap { db.messageDao().observeBySession(it.id).first() }
            ExportedMuseSnapshot(
                sessions = s,
                messages = msgs,
                assistants = db.assistantDao().getAll(),
                lorebooks = db.lorebookDao().getAll(),
                skills = db.skillDao().getAll(),
                artifacts = db.artifactDao().getAll(),
                quickMessages = db.quickMessageDao().getAll(),
                promptInjections = db.promptInjectionDao().getAll(),
                folders = db.folderDao().getAll(),
                groupChats = db.groupChatDao().getAll(),
                groupChatMessages = db.groupChatMessageDao().getAll(),
                scheduledTasks = db.scheduledTaskDao().getAll(),
                scheduledTaskExecutions = db.scheduledTaskExecutionDao().getAll(),
                knowledgeDocs = db.knowledgeDocDao().getAll(),
                knowledgeChunks = db.knowledgeChunkDao().getAll(),
                experiences = db.experienceDao().getAll(),
                milestones = db.milestoneDao().getAll(),
                agentMessages = db.agentMessageDao().getAll(),
                // P0-10: entidad previamente desaparecida(Recuperar datos perdidos después de cambiar de dispositivo)
                quickNotes = db.quickNoteDao().getAll(),
                worldBookEntries = db.worldBookDao().getAll(),
                conversationEvents = db.conversationEventDao().getAll(),
                conversationTurns = db.conversationTurnDao().getAll(),
                messageParts = db.messagePartDao().getAll(),
                messageOutboxes = db.messageOutboxDao().getAll(),
                translateHistories = db.translateHistoryDao().getAll(),
                knowledgeBases = db.knowledgeBaseDao().getAll(),
                knowledgeFolders = db.knowledgeFolderDao().getAll(),
                subagentThreads = db.subagentThreadDao().getAll(),
                toolRounds = db.toolRoundDao().getAll(),
                sessionBranchHeads = db.sessionBranchHeadDao().getAll(),
                assistantRelationships = db.assistantRelationshipDao().getAll(),
                sessionTodos = db.sessionTodoDao().getAll(),
                pendingThoughts = db.pendingThoughtDao().getAll(),
                proactiveOutcomes = db.proactiveOutcomeDao().getAll(),
                proactiveRelations = db.proactiveRelationDao().getAll(),
                groupChatMemories = db.groupChatMemoryDao().getAll(),
                // v2.6.8: entidad de tarjeta (NDJSON Exportar, mismo orden que JSON único).
                // v2.6.9: Utilice DAO para consultas completas en lugar de listas escritas a mano (consulte CardEntityBackup.kt).
                cardEntities = db.cardEntityDao().getAll().map { it.toRecord() }.inDockOrder(),
            )
        }
        val sessions = muse.sessions
        val allMessages = muse.messages
        val sessionSummaries = memoryDb.sessionSummaryDao().getAll()
        val contextCheckpoints = memoryDb.contextCheckpointDao().getAll()
        val compiledSections = memoryDb.compiledSectionDao().getAll()
        val scopedCompiledSections = memoryDb.scopedCompiledSectionDao().getAll()
        val dailyState = memoryDb.dailyStateDao().getAll()
        val facts = factDb.factDao().getAll()
        val assistants = muse.assistants
        val lorebooks = muse.lorebooks
        val skills = muse.skills
        val artifacts = muse.artifacts
        val quickMessages = muse.quickMessages
        val promptInjections = muse.promptInjections
        val folders = muse.folders
        val groupChats = muse.groupChats
        val groupChatMessages = muse.groupChatMessages
        val scheduledTasks = muse.scheduledTasks
        val scheduledTaskExecutions = muse.scheduledTaskExecutions
        val knowledgeDocs = muse.knowledgeDocs
        val knowledgeChunks = muse.knowledgeChunks
        val experiences = muse.experiences
        val milestones = muse.milestones
        val agentMessages = muse.agentMessages
        // P0-10: entidad previamente desaparecida
        val quickNotes = muse.quickNotes
        val worldBookEntries = muse.worldBookEntries
        val conversationEvents = muse.conversationEvents
        val conversationTurns = muse.conversationTurns
        val messageParts = muse.messageParts
        val messageOutboxes = muse.messageOutboxes
        val translateHistories = muse.translateHistories
        val knowledgeBases = muse.knowledgeBases
        val knowledgeFolders = muse.knowledgeFolders
        val subagentThreads = muse.subagentThreads
        val toolRounds = muse.toolRounds
        val sessionBranchHeads = muse.sessionBranchHeads
        val assistantRelationships = muse.assistantRelationships
        val sessionTodos = muse.sessionTodos
        val pendingThoughts = muse.pendingThoughts
        val proactiveOutcomes = muse.proactiveOutcomes
        val proactiveRelations = muse.proactiveRelations
        val groupChatMemories = muse.groupChatMemories
        // v2.6.8: Entidad de tarjeta.
        val cardEntities = muse.cardEntities
        // P0-10: Biblioteca subasistente(facts_<id>.db)
        val scopedFactIds = listScopedFactDbIds()
        val settingsSnapshot = settings.exportSettingsSnapshot(includeSecrets)
        val fileStores = readFileStores()
        // v4.1: Instantánea del archivo del directorio(Nota adjunta/avatar). Léelo antes de escribir meta.,Garantizar el número de metadeclaraciones
        // Consistente con el número real de líneas de archivos binarios escritas(La conciliación del lado de importación B-34 se basa en esta coherencia).
        val binaryFiles = withContext(Dispatchers.IO) { BackupBinaryFiles.read(context.filesDir) }

        // Step 2: Escribir metalínea
        val meta = buildJsonObject {
            put("type", "meta")
            put("version", 4)
            put("exportedAt", System.currentTimeMillis())
            put("sessions", sessions.size)
            put("messages", allMessages.size)
            put("sessionSummaries", sessionSummaries.size)
            put("contextCheckpoints", contextCheckpoints.size)
            put("dailyStates", dailyState.size)
            put("compiledSections", compiledSections.size)
            put("scopedCompiledSections", scopedCompiledSections.size)
            put("facts", facts.size)
            put("assistants", assistants.size)
            put("lorebooks", lorebooks.size)
            put("skills", skills.size)
            put("artifacts", artifacts.size)
            put("quickMessages", quickMessages.size)
            put("promptInjections", promptInjections.size)
            put("folders", folders.size)
            put("groupChats", groupChats.size)
            put("groupChatMessages", groupChatMessages.size)
            put("scheduledTasks", scheduledTasks.size)
            put("scheduledTaskExecutions", scheduledTaskExecutions.size)
            put("knowledgeDocs", knowledgeDocs.size)
            put("knowledgeChunks", knowledgeChunks.size)
            put("experiences", experiences.size)
            put("milestones", milestones.size)
            put("agentMessages", agentMessages.size)
            // P0-10: entidad previamente desaparecida
            put("quickNotes", quickNotes.size)
            put("worldBookEntries", worldBookEntries.size)
            put("conversationEvents", conversationEvents.size)
            put("conversationTurns", conversationTurns.size)
            put("messageParts", messageParts.size)
            put("messageOutboxes", messageOutboxes.size)
            put("scopedFacts", scopedFactIds.size)
            put("translateHistories", translateHistories.size)
            put("knowledgeBases", knowledgeBases.size)
            put("knowledgeFolders", knowledgeFolders.size)
            put("subagentThreads", subagentThreads.size)
            put("toolRounds", toolRounds.size)
            put("sessionBranchHeads", sessionBranchHeads.size)
            put("sessionTodos", sessionTodos.size)
            put("pendingThoughts", pendingThoughts.size)
            put("proactiveOutcomes", proactiveOutcomes.size)
            put("proactiveRelations", proactiveRelations.size)
            put("assistantRelationships", assistantRelationships.size)
            put("groupChatMemories", groupChatMemories.size)
            put("cardEntities", cardEntities.size)
            // settings Es un registro opcional que utiliza 0/1 para indicar si realmente está escrito.
            put("settings", if (settingsSnapshot.isEmpty()) 0 else 1)
            put("fileStores", fileStores.size)
            put("binaryFiles", binaryFiles.items.size)
        }
        writer.write(meta.toString())
        writer.newLine()

        // Step 2.5: v4 Almacenamiento de archivos(tamaño pequeño,Escribir directamente en línea)
        fileStores.forEach { (name, content) ->
            val line = buildJsonObject {
                put("type", "fileStore")
                put("name", name)
                put("content", content)
            }
            writer.write(line.toString())
            writer.newLine()
        }

        // Step 2.6: v4.1 archivo de directorio(Nota adjunta/avatar),Una línea por archivo Base64
        binaryFiles.items.forEach { entry ->
            val line = buildJsonObject {
                put("type", "binaryFile")
                put("path", entry.path)
                put("content", Base64.getEncoder().encodeToString(entry.bytes))
            }
            writer.write(line.toString())
            writer.newLine()
        }

        // Step 3: escribir sessions
        var sessionCount = 0
        sessions.forEach { session ->
            val line = buildJsonObject {
                put("type", "session")
                put("data", json.encodeToJsonElement(SessionEntity.serializer(), session))
            }
            writer.write(line.toString())
            writer.newLine()
            sessionCount++
        }

        // Step 4: escribir messages(B-32: Escriba directamente en la lista de mensajes en la instantánea de la transacción,No más volver a verificar sesión por sesión)
        var messageCount = 0
        allMessages.forEach { msg ->
            val line = buildJsonObject {
                put("type", "message")
                put("data", json.encodeToJsonElement(MessageEntity.serializer(), msg))
            }
            writer.write(line.toString())
            writer.newLine()
            messageCount++
        }

        // Step 5: Escribir datos de memoria
        sessionSummaries.forEach { summary ->
            val line = buildJsonObject {
                put("type", "summary")
                put("data", json.encodeToJsonElement(SessionSummaryEntity.serializer(), summary))
            }
            writer.write(line.toString())
            writer.newLine()
        }
        contextCheckpoints.forEach { checkpoint ->
            val line = buildJsonObject {
                put("type", "contextCheckpoint")
                put("data", json.encodeToJsonElement(ContextCheckpointEntity.serializer(), checkpoint))
            }
            writer.write(line.toString())
            writer.newLine()
        }
        dailyState.forEach { ds ->
            val line = buildJsonObject {
                put("type", "dailyState")
                put("data", json.encodeToJsonElement(DailyStateEntity.serializer(), ds))
            }
            writer.write(line.toString())
            writer.newLine()
        }
        compiledSections.forEach { cs ->
            val line = buildJsonObject {
                put("type", "compiledSection")
                put("data", json.encodeToJsonElement(CompiledSectionEntity.serializer(), cs))
            }
            writer.write(line.toString())
            writer.newLine()
        }
        scopedCompiledSections.forEach { cs ->
            val line = buildJsonObject {
                put("type", "scopedCompiledSection")
                put("data", json.encodeToJsonElement(ScopedCompiledSectionEntity.serializer(), cs))
            }
            writer.write(line.toString())
            writer.newLine()
        }

        // Step 6: escribir facts
        facts.forEach { fact ->
            val line = buildJsonObject {
                put("type", "fact")
                put("data", json.encodeToJsonElement(FactEntity.serializer(), fact))
            }
            writer.write(line.toString())
            writer.newLine()
        }

        // Step 7: Escribir tabla extendida(v3)
        writeTypedLines(writer, "assistant", assistants, AssistantEntity.serializer())
        writeTypedLines(writer, "lorebook", lorebooks, LorebookEntity.serializer())
        writeTypedLines(writer, "skill", skills, SkillEntity.serializer())
        writeTypedLines(writer, "artifact", artifacts, ArtifactEntity.serializer())
        writeTypedLines(writer, "quickMessage", quickMessages, QuickMessageEntity.serializer())
        writeTypedLines(writer, "promptInjection", promptInjections, PromptInjectionEntity.serializer())
        writeTypedLines(writer, "folder", folders, FolderEntity.serializer())
        writeTypedLines(writer, "groupChat", groupChats, GroupChatEntity.serializer())
        writeTypedLines(writer, "groupChatMessage", groupChatMessages, GroupChatMessageEntity.serializer())
        writeTypedLines(writer, "scheduledTask", scheduledTasks, ScheduledTaskEntity.serializer())
        writeTypedLines(writer, "scheduledTaskExecution", scheduledTaskExecutions, ScheduledTaskExecutionEntity.serializer())
        writeTypedLines(writer, "knowledgeDoc", knowledgeDocs, KnowledgeDocEntity.serializer())
        writeTypedLines(writer, "knowledgeChunk", knowledgeChunks, KnowledgeChunkEntity.serializer())
        writeTypedLines(writer, "experience", experiences, ExperienceEntity.serializer())
        writeTypedLines(writer, "milestone", milestones, MilestoneEntity.serializer())
        writeTypedLines(writer, "agentMessage", agentMessages, AgentMessageEntity.serializer())
        // P0-10: entidad previamente desaparecida
        writeTypedLines(writer, "quickNote", quickNotes, io.zer0.muse.data.quicknote.QuickNoteEntity.serializer())
        writeTypedLines(writer, "worldBookEntry", worldBookEntries, io.zer0.muse.worldbook.WorldBookEntryEntity.serializer())
        writeTypedLines(writer, "conversationEvent", conversationEvents, ConversationEventEntity.serializer())
        writeTypedLines(writer, "conversationTurn", conversationTurns, ConversationTurnEntity.serializer())
        writeTypedLines(writer, "messagePart", messageParts, MessagePartEntity.serializer())
        writeTypedLines(writer, "messageOutbox", messageOutboxes, MessageOutboxEntity.serializer())
        // P0-10: Restantes entidades de datos de usuario que faltaban anteriormente
        writeTypedLines(writer, "translateHistory", translateHistories, TranslateHistoryEntity.serializer())
        writeTypedLines(writer, "knowledgeBase", knowledgeBases, KnowledgeBaseEntity.serializer())
        writeTypedLines(
            writer,
            "knowledgeFolder",
            knowledgeFolders,
            io.zer0.muse.data.knowledge.KnowledgeFolderEntity.serializer(),
        )
        writeTypedLines(writer, "subagentThread", subagentThreads, SubagentThreadEntity.serializer())
        writeTypedLines(writer, "toolRound", toolRounds, ToolRoundEntity.serializer())
        writeTypedLines(writer, "sessionBranchHead", sessionBranchHeads, SessionBranchHeadEntity.serializer())
        writeTypedLines(writer, "groupChatMemory", groupChatMemories, GroupChatMemoryEntity.serializer())
        // v2.6.8: Entidad de tarjeta. Una vez que la tarjeta acoplada abandona el flujo de mensajes, solo existe en esta tabla. Si se omite, la tarjeta se perderá al cambiar de máquina.
        writeTypedLines(writer, "cardEntity", cardEntities, CardEntityRecord.serializer())
        // v2.6.7 fix (AUDIT F1): Anteriormente, las relaciones de funciones y tareas pendientes de la sesión solo incluían metaconteo y limpieza de rutas.
        // No se escriben filas de datos —— Durante la recuperación, no se rellena ninguna rama después de eliminar todo, y la copia de seguridad equivale a la preparación en blanco.
        writeTypedLines(
            writer,
            "sessionTodo",
            sessionTodos,
            io.zer0.muse.data.todo.SessionTodoEntity.serializer(),
        )
        writeTypedLines(
            writer,
            "pendingThought",
            pendingThoughts,
            io.zer0.muse.data.proactive.PendingThoughtEntity.serializer(),
        )
        writeTypedLines(
            writer,
            "proactiveOutcome",
            proactiveOutcomes,
            io.zer0.muse.data.proactive.ProactiveOutcomeEntity.serializer(),
        )
        writeTypedLines(
            writer,
            "proactiveRelation",
            proactiveRelations,
            io.zer0.muse.data.proactive.ProactiveRelationEntity.serializer(),
        )
        writeTypedLines(
            writer,
            "assistantRelationship",
            assistantRelationships,
            io.zer0.muse.data.relationship.AssistantRelationshipEntity.serializer(),
        )
        // P0-10: Biblioteca subasistente — Una línea por base de datos,data Por el importe total de la biblioteca. facts
        scopedFactIds.forEach { id ->
            val line = buildJsonObject {
                put("type", "scopedFact")
                put("scope", id)
                put(
                    "data",
                    json.encodeToJsonElement(
                        ListSerializer(FactEntity.serializer()),
                        readScopedFacts(id),
                    ),
                )
            }
            writer.write(line.toString())
            writer.newLine()
        }

        // Step 8: Escribir instantánea de configuración
        if (settingsSnapshot.isNotEmpty()) {
            val line = buildJsonObject {
                put("type", "settings")
                put(
                    "data",
                    json.encodeToJsonElement(
                        MapSerializer(
                            String.serializer(),
                            String.serializer(),
                        ),
                        settingsSnapshot,
                    ),
                )
            }
            writer.write(line.toString())
            writer.newLine()
        }

        return NdJsonExportSummary(
            sessions = sessionCount,
            messages = messageCount,
            facts = facts.size,
            groupChatMessages = groupChatMessages.size,
        )
    }

    /**
     * NDJSON Ayudante de escritura en streaming: serialice el tipo de escritura + líneas de datos una por una.
     */
    private fun <T> writeTypedLines(
        writer: BufferedWriter,
        type: String,
        items: List<T>,
        serializer: kotlinx.serialization.KSerializer<T>,
    ) {
        items.forEach { item ->
            val line = buildJsonObject {
                put("type", type)
                put("data", json.encodeToJsonElement(serializer, item))
            }
            writer.write(line.toString())
            writer.newLine()
        }
    }

    /**
     * B-25: [writeNdJson] resultados estadísticos,Permite al exportador imprimir registros de diagnóstico de báscula.
     */
    private data class NdJsonExportSummary(
        val sessions: Int,
        val messages: Int,
        val facts: Int,
        val groupChatMessages: Int,
    )

    /**
     * B-32: [writeNdJson] en single [MuseDb] Una instantánea coherente leída dentro de una transacción.
     * Una vez enviada la transacción, se escribe según el flujo. NDJSON;Contiene una lista de objetos de entidad.(ligero)sin sostener una cuerda gigante serializada,
     * Teniendo en cuenta los objetivos de instantáneas consistentes y exportación de transmisión para evitar OOM.
     */
    private data class ExportedMuseSnapshot(
        val sessions: List<SessionEntity>,
        val messages: List<MessageEntity>,
        val assistants: List<AssistantEntity>,
        val lorebooks: List<LorebookEntity>,
        val skills: List<SkillEntity>,
        val artifacts: List<ArtifactEntity>,
        val quickMessages: List<QuickMessageEntity>,
        val promptInjections: List<PromptInjectionEntity>,
        val folders: List<FolderEntity>,
        val groupChats: List<GroupChatEntity>,
        val groupChatMessages: List<GroupChatMessageEntity>,
        val scheduledTasks: List<ScheduledTaskEntity>,
        val scheduledTaskExecutions: List<ScheduledTaskExecutionEntity>,
        val knowledgeDocs: List<KnowledgeDocEntity>,
        val knowledgeChunks: List<KnowledgeChunkEntity>,
        val experiences: List<ExperienceEntity>,
        val milestones: List<MilestoneEntity>,
        val agentMessages: List<AgentMessageEntity>,
        // P0-10: entidad previamente desaparecida(Recuperar datos perdidos después de cambiar de dispositivo)
        val quickNotes: List<io.zer0.muse.data.quicknote.QuickNoteEntity>,
        val worldBookEntries: List<io.zer0.muse.worldbook.WorldBookEntryEntity>,
        val conversationEvents: List<ConversationEventEntity>,
        val conversationTurns: List<ConversationTurnEntity>,
        val messageParts: List<MessagePartEntity>,
        val messageOutboxes: List<MessageOutboxEntity>,
        // P0-10: Restantes entidades de datos de usuario que faltaban anteriormente(Recuperar datos perdidos después de cambiar de dispositivo)
        val translateHistories: List<TranslateHistoryEntity>,
        val knowledgeBases: List<KnowledgeBaseEntity>,
        val knowledgeFolders: List<io.zer0.muse.data.knowledge.KnowledgeFolderEntity>,
        val subagentThreads: List<SubagentThreadEntity>,
        val toolRounds: List<ToolRoundEntity>,
        val sessionBranchHeads: List<SessionBranchHeadEntity>,
        /** v2.6.7(P1-1): Estado de la relación de roles. */
        val assistantRelationships: List<io.zer0.muse.data.relationship.AssistantRelationshipEntity>,
        /** v2.6.7: Sesión pendiente. */
        val sessionTodos: List<io.zer0.muse.data.todo.SessionTodoEntity>,
        /** v2.6.9: Libro mayor de cuidados/libro mayor de resultados/intimidad en las relaciones. */
        val pendingThoughts: List<io.zer0.muse.data.proactive.PendingThoughtEntity>,
        val proactiveOutcomes: List<io.zer0.muse.data.proactive.ProactiveOutcomeEntity>,
        val proactiveRelations: List<io.zer0.muse.data.proactive.ProactiveRelationEntity>,
        /** v2.6.8: entidad de tarjeta (`card_entities`)—— El único lugar donde permanecen las tarjetas acopladas después de abandonar el flujo de mensajes. */
        val cardEntities: List<CardEntityRecord>,
        val groupChatMemories: List<GroupChatMemoryEntity>,
    )

    /**
     * v1.104: Streaming analizando la copia de seguridad NDJSON e insertándola en la base de datos en lotes.
     *
     * Resolver grandes copias de seguridad(10000+ noticias)Problema de OOM causado por la acumulación total de Lista durante la importación:
     *  - Analizar línea por línea,Distribuir a los lotes correspondientes por tipo. buffer
     *  - buffer alcanzar [IMPORT_BATCH] Utilice withTransaction para insertar y borrar en lotes
     *  - pico de memoria = lote de registros(Alrededor de varios cientos),en lugar del importe total
     *  - tolerancia a fallas:Saltar líneas no analizables(Continuar grabando,recuperación parcial)
     *
     * @return Conversaciones importadas + mensajes
     */
    private suspend fun applyNdJsonStreaming(lines: Sequence<String>, allowEmptyBackup: Boolean = false): Pair<Int, Int> {
        // v1.0.74 fix: Protección de copia de seguridad vacía — El camino de la nube tiene"0 Se negó a reanudar el mensaje de sesión 0",No hay camino local,
        // Seleccionar vacío por error/Los archivos dañados borrarán todas las tablas primero. Lea la metalínea y verifíquela antes de decidir si continúa.
        val lineIter = lines.iterator()
        val firstLine = if (lineIter.hasNext()) lineIter.next() else null
        val typeToMetaKey = ndjsonTypeToMetaKey
        val expectedCounts = mutableMapOf<String, Int>()
        val actualCounts = mutableMapOf<String, Int>()
        if (!firstLine.isNullOrBlank()) {
            resultOf {
                val obj = json.decodeFromString(JsonObject.serializer(), firstLine)
                val type = obj["type"]?.let { (it as? JsonPrimitive)?.content }
                require(type == "meta") { "NDJSON A la copia de seguridad le falta la meta primera línea,Importación rechazada" }
                // B-21/B-34: meta Se utiliza tanto para la protección de copias de seguridad vacías como para la reconciliación de integridad.
                // El generador actual declara todos los recuentos de tipos; Los campos de recuento faltantes de versiones anteriores siguen siendo compatibles.
                // Pero sólo se verifican estrictamente los campos declarados reales.
                typeToMetaKey.values.distinct().forEach { key ->
                    obj[key]?.let { value ->
                        val count = (value as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                            ?: error("El metaconteo de respaldo es ilegal: $key")
                        require(count >= 0) { "El metaconteo de respaldo no puede ser negativo: $key" }
                        expectedCounts[key] = count
                    }
                }
                if (!allowEmptyBackup && (expectedCounts.isEmpty() || expectedCounts.values.sum() == 0)) {
                    throw IllegalArgumentException("Archivo de copia de seguridad vacío(meta sin datos),Importación rechazada")
                }
            }.onError { msg, t ->
                // meta Error de análisis o copia de seguridad vacía:Lanzar a la persona que llama,No borrar ninguna mesa
                throw IllegalArgumentException(t?.message ?: msg)
            }
        } else {
            throw IllegalArgumentException("El archivo de copia de seguridad está vacío,Importación rechazada")
        }
        val remaining = sequenceOf(firstLine).plus(lineIter.asSequence())

        // Reparación de auditoría (0.5): Borrar + Todos los paquetes de inserción de MuseDb en la misma transacción.
        // En la implementación original, la compensación es una transacción independiente y la inserción es una transacción independiente en lotes.,Excepción durante el análisis/Cancelar dejará una biblioteca medio vacía
        // (Las conversaciones y los recuerdos se pierden.).Room Nested withTransaction se unirá a la transacción externa,
        // Cualquier lote falló → todo el asunto(Contiene limpieza)revertir,Los datos permanecen en su estado anterior a la importación.
        // C-10: memoryDb / factDb como base de datos independiente,No se puede realizar un repositorio atómico cruzado con MuseDb,
        // Por lo tanto, sólo después de que la transacción MuseDb sea exitosa, se pueden unificar en transacciones separadas."Borrar + Insertar",
        // Elimine los semiestados entre bases de datos y los vaciados tempranos repetidos causados ​​por la confirmación temprana de memoria/hechos dentro de las transacciones de MuseDb.
        var sessionCount = 0
        var messageCount = 0
        // v4: Almacenamiento de archivos(canal/conector/Comentario/Bandeja de entrada/complemento),La declaración está escrita fuera del intento de escritura de cola.
        val fileStoreBuf = mutableMapOf<String, String>()
        // v4.1: archivo de directorio(Nota adjunta/avatar),También intenta afuera,La cola se escribe junto con el almacenamiento de archivos.
        // Buffer Realizar listas blancas y recortar capacidad durante la fase de acumulación,Evite copias de seguridad maliciosas que primero consuman memoria y luego verifiquen.
        val binaryFileBuf = BackupBinaryFiles.Buffer()
        try {
            // buffer declarado fuera de la transacción(También se accede al bloque de transacción de hechos/memoria de cola)
            val sessionBuf = mutableListOf<SessionEntity>()
            val messageBuf = mutableListOf<MessageEntity>()
            // El viejo y pequeño teléfono celular ha estado desconectado.:Las sesiones restantes de isMiniPhone en la copia de seguridad ya no se restauran como sesiones normales.
            // El filtrado se produce antes de escribir.(Eliminar la biblioteca sin escribirla),Sus mensajes también se omiten juntos.
            val skippedMiniPhoneSessionIds = mutableSetOf<String>()
            var skippedMiniPhoneSessions = 0
            var skippedMiniPhoneMessages = 0
            val summaryBuf = mutableListOf<SessionSummaryEntity>()
            val contextCheckpointBuf = mutableListOf<ContextCheckpointEntity>()
            val dailyBuf = mutableListOf<DailyStateEntity>()
            val compiledBuf = mutableListOf<CompiledSectionEntity>()
            val scopedCompiledBuf = mutableListOf<ScopedCompiledSectionEntity>()
            val factBuf = mutableListOf<FactEntity>()
            // v3: mesa extendida buffer
            val assistantBuf = mutableListOf<AssistantEntity>()
            val lorebookBuf = mutableListOf<LorebookEntity>()
            val skillBuf = mutableListOf<SkillEntity>()
            val artifactBuf = mutableListOf<ArtifactEntity>()
            val quickMsgBuf = mutableListOf<QuickMessageEntity>()
            val promptInjBuf = mutableListOf<PromptInjectionEntity>()
            val folderBuf = mutableListOf<FolderEntity>()
            val groupChatBuf = mutableListOf<GroupChatEntity>()
            val groupChatMsgBuf = mutableListOf<GroupChatMessageEntity>()
            val schedTaskBuf = mutableListOf<ScheduledTaskEntity>()
            val schedExecBuf = mutableListOf<ScheduledTaskExecutionEntity>()
            val knowDocBuf = mutableListOf<KnowledgeDocEntity>()
            val knowChunkBuf = mutableListOf<KnowledgeChunkEntity>()
            val experienceBuf = mutableListOf<ExperienceEntity>()
            val milestoneBuf = mutableListOf<MilestoneEntity>()
            val agentMsgBuf = mutableListOf<AgentMessageEntity>()
            // P0-10: entidad previamente desaparecida
            val quickNoteBuf = mutableListOf<io.zer0.muse.data.quicknote.QuickNoteEntity>()
            val worldBookBuf = mutableListOf<io.zer0.muse.worldbook.WorldBookEntryEntity>()
            val convEventBuf = mutableListOf<ConversationEventEntity>()
            val convTurnBuf = mutableListOf<ConversationTurnEntity>()
            val messagePartBuf = mutableListOf<MessagePartEntity>()
            val messageOutboxBuf = mutableListOf<MessageOutboxEntity>()
            val translateHistoryBuf = mutableListOf<TranslateHistoryEntity>()
            val knowledgeBaseBuf = mutableListOf<KnowledgeBaseEntity>()
            // AUD-20261011-NDJSON-FOLDERS: La carpeta de la base de conocimientos anteriormente solo tenía líneas de exportación y no tenía ramas de recuperación.,
            // No se puede reponer después de eliminar todo en la fase de limpieza(Vacíe todas las carpetas cada vez que se restaure NDJSON).
            val knowledgeFolderBuf = mutableListOf<io.zer0.muse.data.knowledge.KnowledgeFolderEntity>()
            val subagentThreadBuf = mutableListOf<SubagentThreadEntity>()
            val toolRoundBuf = mutableListOf<ToolRoundEntity>()
            val sessionBranchHeadBuf = mutableListOf<SessionBranchHeadEntity>()
            val groupChatMemoryBuf = mutableListOf<GroupChatMemoryEntity>()
            // v2.6.7 fix (AUDIT F1): Búfer de recuperación para relaciones de roles y tareas pendientes.
            val sessionTodoBuf = mutableListOf<io.zer0.muse.data.todo.SessionTodoEntity>()
            // v2.6.9: Mensaje activo en búfer de recuperación de tres tablas.
            val pendingThoughtBuf = mutableListOf<io.zer0.muse.data.proactive.PendingThoughtEntity>()
            val proactiveOutcomeBuf = mutableListOf<io.zer0.muse.data.proactive.ProactiveOutcomeEntity>()
            val proactiveRelationBuf = mutableListOf<io.zer0.muse.data.proactive.ProactiveRelationEntity>()
            val assistantRelationshipBuf =
                mutableListOf<io.zer0.muse.data.relationship.AssistantRelationshipEntity>()
            // v2.6.8: Búfer de recuperación de entidades de tarjetas.
            val cardEntityBuf = mutableListOf<CardEntityRecord>()
            // P0-10: Biblioteca subasistente — scope → facts(Base de datos cruzada,Independiente de las transacciones de MuseDb)
            val scopedFactBuf = LinkedHashMap<String, MutableList<FactEntity>>()
            var settingsSnapshot: Map<String, String> = emptyMap()

            db.withTransaction {
                // 1. Borrar todas las tablas primero
                clearConversationStateForRestore(db)
                db.assistantDao().deleteAll()
                db.lorebookDao().deleteAll()
                db.skillDao().deleteAll()
                db.artifactDao().deleteAll()
                db.quickMessageDao().deleteAll()
                db.promptInjectionDao().deleteAll()
                db.folderDao().deleteAll()
                db.groupChatMessageDao().deleteAll()
                db.groupChatDao().deleteAll()
                db.scheduledTaskExecutionDao().deleteAll()
                db.scheduledTaskDao().deleteAll()
                db.knowledgeChunkDao().deleteAll()
                db.knowledgeDocDao().deleteAll()
                db.experienceDao().deleteAll()
                db.milestoneDao().deleteAll()
                db.agentMessageDao().deleteAll()
                // P0-10: entidad previamente desaparecida
                db.quickNoteDao().deleteAll()
                db.worldBookDao().deleteAll()
                db.conversationEventDao().deleteAll()
                db.conversationTurnDao().deleteAll()
                db.messagePartDao().deleteAll()
                db.messageOutboxDao().deleteAll()
                db.translateHistoryDao().deleteAll()
                db.knowledgeBaseDao().deleteAll()
                db.knowledgeFolderDao().deleteAll()
                db.sessionTodoDao().deleteAll()
                db.pendingThoughtWriteDao().deleteAll()
                db.proactiveOutcomeDao().deleteAll()
                db.proactiveRelationDao().deleteAll()
                db.assistantRelationshipDao().deleteAll()
                db.subagentThreadDao().deleteAll()
                db.toolRoundDao().deleteAll()
                db.sessionBranchHeadDao().deleteAll()
                db.groupChatMemoryDao().deleteAll()
                // v2.6.8: Entidad de tarjeta.
                db.cardEntityDao().deleteAll()

                // 2. Analizar línea por línea + insertar en lotes(Aún dentro de la transacción db.withTransaction,Si se produce algún fallo, se cancelará toda la reversión.)
                remaining.forEachIndexed { idx, line ->
                    if (line.isBlank()) return@forEachIndexed
                    // Trate sólo los errores de formato de una sola línea como saltables;inserción de base de datos/Las excepciones de transacciones deben seguir descartándose,
                    // De esta manera, la transacción externa de MuseDb se puede revertir.,no aparecerá“Importación exitosa pero faltan datos parcialmente”.
                    val obj = resultOf { json.decodeFromString(JsonObject.serializer(), line) }
                        .onError { msg, t ->
                            Logger.w("BackupService", "No. ${idx + 1} Falló el análisis JSON de fila,saltar: ${t?.message ?: msg}", t)
                        }
                        .getOrNull()
                        ?: return@forEachIndexed
                    val type = obj["type"]?.let { (it as? JsonPrimitive)?.content }
                        ?: return@forEachIndexed
                    if (type != "meta" && type in typeToMetaKey) {
                        // fileStore/binaryFile Utilice nombre/contenido o ruta/estructura de contenido y no sean registros de datos de tipo entidad.
                        if (type != "fileStore" && type != "binaryFile") {
                            require(obj["data"] != null) { "Falta el registro de respaldo data: $type" }
                        }
                        val key = typeToMetaKey.getValue(type)
                        actualCounts[key] = (actualCounts[key] ?: 0) + 1
                    }
                    when (type) {
                        "meta" -> { /* version/exportedAt Metainformación,No se requiere inserción de transmisión */ }
                        "fileStore" -> {
                            val name = obj["name"]?.let { (it as? JsonPrimitive)?.content }
                            val content = obj["content"]?.let { (it as? JsonPrimitive)?.content }
                            if (!name.isNullOrBlank() && content != null) fileStoreBuf[name] = content
                        }
                        "binaryFile" -> {
                            // v4.1: El prefijo de la lista blanca y el límite de capacidad se recortan en el búfer, y las rutas ilegales se omiten y registran directamente.
                            val path = obj["path"]?.let { (it as? JsonPrimitive)?.content }
                            val content = obj["content"]?.let { (it as? JsonPrimitive)?.content }
                            if (!path.isNullOrBlank() && content != null) binaryFileBuf.offer(path, content)
                        }
                        "session" -> obj["data"]?.let {
                            val session = json.decodeFromJsonElement(SessionEntity.serializer(), it)
                            if (session.isMiniPhone) {
                                skippedMiniPhoneSessionIds += session.id
                                skippedMiniPhoneSessions += 1
                            } else {
                                // v2.x Importar calentamiento:La sesión restaurada construirá el contexto una vez con la historia completa en la primera ronda.
                                sessionBuf.add(session.copy(warmupPending = true))
                                if (sessionBuf.size >= IMPORT_BATCH) {
                                    sessionCount += sessionBuf.size
                                    flushBatch(sessionBuf) { batch -> db.withTransaction { batch.forEach { db.sessionDao().insert(it) } } }
                                }
                            }
                        }
                        "message" -> obj["data"]?.let {
                            val message = json.decodeFromJsonElement(MessageEntity.serializer(), it)
                            if (message.sessionId in skippedMiniPhoneSessionIds) {
                                skippedMiniPhoneMessages += 1
                                return@let
                            }
                            messageBuf.add(message)
                            if (messageBuf.size >= IMPORT_BATCH) {
                                messageCount += messageBuf.size
                                flushBatch(messageBuf) { batch -> db.withTransaction { batch.forEach { db.messageDao().upsert(it) } } }
                            }
                        }
                        "summary" -> obj["data"]?.let {
                            summaryBuf.add(json.decodeFromJsonElement(SessionSummaryEntity.serializer(), it))
                            // C-10: memory/fact El búfer solo se envía de manera uniforme en transacciones separadas después de que la transacción MuseDb sea exitosa.,
                            // Ya no mirar hacia adelante en el bucle interno de transacciones de MuseDb flush(La implementación anterior dejaría la memoria comprometida en medio estado cuando MuseDb retroceda).
                        }
                        "contextCheckpoint" -> obj["data"]?.let {
                            contextCheckpointBuf.add(
                                json.decodeFromJsonElement(ContextCheckpointEntity.serializer(), it),
                            )
                        }
                        "dailyState" -> obj["data"]?.let {
                            dailyBuf.add(json.decodeFromJsonElement(DailyStateEntity.serializer(), it))
                        }
                        "compiledSection" -> obj["data"]?.let {
                            compiledBuf.add(json.decodeFromJsonElement(CompiledSectionEntity.serializer(), it))
                        }
                        "scopedCompiledSection" -> obj["data"]?.let {
                            scopedCompiledBuf.add(json.decodeFromJsonElement(ScopedCompiledSectionEntity.serializer(), it))
                        }
                        "fact" -> obj["data"]?.let {
                            factBuf.add(json.decodeFromJsonElement(FactEntity.serializer(), it))
                        }
                        // v3: tipo de tabla extendida
                        "assistant" -> obj["data"]?.let {
                            assistantBuf.add(json.decodeFromJsonElement(AssistantEntity.serializer(), it))
                            if (assistantBuf.size >= IMPORT_BATCH) {
                                flushBatch(
                                    assistantBuf,
                                ) { batch -> db.withTransaction { db.assistantDao().insertAll(batch) } }
                            }
                        }
                        "lorebook" -> obj["data"]?.let {
                            lorebookBuf.add(json.decodeFromJsonElement(LorebookEntity.serializer(), it))
                            if (lorebookBuf.size >= IMPORT_BATCH) {
                                flushBatch(
                                    lorebookBuf,
                                ) { batch -> db.withTransaction { db.lorebookDao().insertAll(batch) } }
                            }
                        }
                        "skill" -> obj["data"]?.let {
                            skillBuf.add(json.decodeFromJsonElement(SkillEntity.serializer(), it))
                            if (skillBuf.size >= IMPORT_BATCH) {
                                flushBatch(
                                    skillBuf,
                                ) { batch -> db.withTransaction { batch.forEach { db.skillDao().upsert(it) } } }
                            }
                        }
                        "artifact" -> obj["data"]?.let {
                            artifactBuf.add(json.decodeFromJsonElement(ArtifactEntity.serializer(), it))
                            if (artifactBuf.size >= IMPORT_BATCH) {
                                flushBatch(artifactBuf) { batch ->
                                    db.withTransaction {
                                        batch.forEach { db.artifactDao().upsert(it) }
                                    }
                                }
                            }
                        }
                        "quickMessage" -> obj["data"]?.let {
                            quickMsgBuf.add(json.decodeFromJsonElement(QuickMessageEntity.serializer(), it))
                            if (quickMsgBuf.size >= IMPORT_BATCH) {
                                flushBatch(
                                    quickMsgBuf,
                                ) { batch -> db.withTransaction { db.quickMessageDao().insertAll(batch) } }
                            }
                        }
                        "promptInjection" -> obj["data"]?.let {
                            promptInjBuf.add(json.decodeFromJsonElement(PromptInjectionEntity.serializer(), it))
                            if (promptInjBuf.size >= IMPORT_BATCH) {
                                flushBatch(
                                    promptInjBuf,
                                ) { batch -> db.withTransaction { db.promptInjectionDao().insertAll(batch) } }
                            }
                        }
                        "folder" -> obj["data"]?.let {
                            folderBuf.add(json.decodeFromJsonElement(FolderEntity.serializer(), it))
                            if (folderBuf.size >= IMPORT_BATCH) {
                                flushBatch(
                                    folderBuf,
                                ) { batch -> db.withTransaction { batch.forEach { db.folderDao().insert(it) } } }
                            }
                        }
                        "groupChat" -> obj["data"]?.let {
                            groupChatBuf.add(json.decodeFromJsonElement(GroupChatEntity.serializer(), it))
                            if (groupChatBuf.size >= IMPORT_BATCH) {
                                flushBatch(groupChatBuf) { batch ->
                                    db.withTransaction {
                                        batch.forEach { db.groupChatDao().upsert(it) }
                                    }
                                }
                            }
                        }
                        "groupChatMessage" -> obj["data"]?.let {
                            groupChatMsgBuf.add(json.decodeFromJsonElement(GroupChatMessageEntity.serializer(), it))
                            if (groupChatMsgBuf.size >= IMPORT_BATCH) {
                                flushBatch(groupChatMsgBuf) { batch ->
                                    db.withTransaction {
                                        db.groupChatMessageDao().insertAll(batch)
                                    }
                                }
                            }
                        }
                        "scheduledTask" -> obj["data"]?.let {
                            schedTaskBuf.add(json.decodeFromJsonElement(ScheduledTaskEntity.serializer(), it))
                            if (schedTaskBuf.size >= IMPORT_BATCH) {
                                flushBatch(schedTaskBuf) { batch ->
                                    db.withTransaction {
                                        batch.forEach { db.scheduledTaskDao().upsert(it) }
                                    }
                                }
                            }
                        }
                        "scheduledTaskExecution" -> obj["data"]?.let {
                            schedExecBuf.add(json.decodeFromJsonElement(ScheduledTaskExecutionEntity.serializer(), it))
                            if (schedExecBuf.size >= IMPORT_BATCH) {
                                flushBatch(schedExecBuf) { batch ->
                                    db.withTransaction {
                                        batch.forEach { db.scheduledTaskExecutionDao().insert(it) }
                                    }
                                }
                            }
                        }
                        "knowledgeDoc" -> obj["data"]?.let {
                            knowDocBuf.add(json.decodeFromJsonElement(KnowledgeDocEntity.serializer(), it))
                            if (knowDocBuf.size >= IMPORT_BATCH) {
                                flushBatch(knowDocBuf) { batch ->
                                    db.withTransaction {
                                        batch.forEach { db.knowledgeDocDao().upsert(it) }
                                    }
                                }
                            }
                        }
                        "knowledgeChunk" -> obj["data"]?.let {
                            knowChunkBuf.add(json.decodeFromJsonElement(KnowledgeChunkEntity.serializer(), it))
                            if (knowChunkBuf.size >= IMPORT_BATCH) {
                                flushBatch(
                                    knowChunkBuf,
                                ) { batch -> db.withTransaction { db.knowledgeChunkDao().insertAll(batch) } }
                            }
                        }
                        "experience" -> obj["data"]?.let {
                            experienceBuf.add(json.decodeFromJsonElement(ExperienceEntity.serializer(), it))
                            if (experienceBuf.size >= IMPORT_BATCH) {
                                flushBatch(experienceBuf) { batch ->
                                    db.withTransaction {
                                        batch.forEach { db.experienceDao().upsert(it) }
                                    }
                                }
                            }
                        }
                        "milestone" -> obj["data"]?.let {
                            milestoneBuf.add(json.decodeFromJsonElement(MilestoneEntity.serializer(), it))
                            if (milestoneBuf.size >= IMPORT_BATCH) {
                                flushBatch(milestoneBuf) { batch ->
                                    db.withTransaction {
                                        batch.forEach { db.milestoneDao().upsert(it) }
                                    }
                                }
                            }
                        }
                        "agentMessage" -> obj["data"]?.let {
                            agentMsgBuf.add(json.decodeFromJsonElement(AgentMessageEntity.serializer(), it))
                            if (agentMsgBuf.size >= IMPORT_BATCH) {
                                flushBatch(agentMsgBuf) { batch ->
                                    db.withTransaction {
                                        batch.forEach { db.agentMessageDao().upsert(it) }
                                    }
                                }
                            }
                        }
                        // P0-10: entidad previamente desaparecida
                        "quickNote" -> obj["data"]?.let {
                            quickNoteBuf.add(json.decodeFromJsonElement(io.zer0.muse.data.quicknote.QuickNoteEntity.serializer(), it))
                            if (quickNoteBuf.size >= IMPORT_BATCH) {
                                flushBatch(quickNoteBuf) { batch ->
                                    db.withTransaction {
                                        batch.forEach { db.quickNoteDao().upsert(it) }
                                    }
                                }
                            }
                        }
                        "worldBookEntry" -> obj["data"]?.let {
                            worldBookBuf.add(json.decodeFromJsonElement(io.zer0.muse.worldbook.WorldBookEntryEntity.serializer(), it))
                            if (worldBookBuf.size >= IMPORT_BATCH) {
                                flushBatch(worldBookBuf) { batch ->
                                    db.withTransaction {
                                        batch.forEach { db.worldBookDao().upsert(it) }
                                    }
                                }
                            }
                        }
                        "conversationEvent" -> obj["data"]?.let {
                            convEventBuf.add(json.decodeFromJsonElement(ConversationEventEntity.serializer(), it))
                            if (convEventBuf.size >= IMPORT_BATCH) {
                                flushBatch(convEventBuf) { batch ->
                                    db.withTransaction {
                                        db.conversationEventDao().insertAll(batch)
                                    }
                                }
                            }
                        }
                        "conversationTurn" -> obj["data"]?.let {
                            convTurnBuf.add(json.decodeFromJsonElement(ConversationTurnEntity.serializer(), it))
                            if (convTurnBuf.size >= IMPORT_BATCH) {
                                flushBatch(convTurnBuf) { batch ->
                                    db.withTransaction {
                                        batch.forEach { db.conversationTurnDao().upsert(it) }
                                    }
                                }
                            }
                        }
                        "messagePart" -> obj["data"]?.let {
                            messagePartBuf.add(json.decodeFromJsonElement(MessagePartEntity.serializer(), it))
                            if (messagePartBuf.size >= IMPORT_BATCH) {
                                flushBatch(
                                    messagePartBuf,
                                ) { batch -> db.withTransaction { db.messagePartDao().upsertAll(batch) } }
                            }
                        }
                        "messageOutbox" -> obj["data"]?.let {
                            messageOutboxBuf.add(json.decodeFromJsonElement(MessageOutboxEntity.serializer(), it))
                            if (messageOutboxBuf.size >= IMPORT_BATCH) {
                                flushBatch(messageOutboxBuf) { batch ->
                                    db.withTransaction {
                                        batch.forEach { db.messageOutboxDao().upsert(it) }
                                    }
                                }
                            }
                        }
                        "translateHistory" -> obj["data"]?.let {
                            translateHistoryBuf.add(json.decodeFromJsonElement(TranslateHistoryEntity.serializer(), it))
                            if (translateHistoryBuf.size >= IMPORT_BATCH) {
                                flushBatch(translateHistoryBuf) { batch ->
                                    db.withTransaction {
                                        batch.forEach { db.translateHistoryDao().insert(it) }
                                    }
                                }
                            }
                        }
                        "knowledgeBase" -> obj["data"]?.let {
                            knowledgeBaseBuf.add(json.decodeFromJsonElement(KnowledgeBaseEntity.serializer(), it))
                            if (knowledgeBaseBuf.size >= IMPORT_BATCH) {
                                flushBatch(knowledgeBaseBuf) { batch ->
                                    db.withTransaction {
                                        batch.forEach { db.knowledgeBaseDao().upsert(it) }
                                    }
                                }
                            }
                        }
                        // AUD-20261011-NDJSON-FOLDERS: con lado de exportación writeTypedLines("knowledgeFolder") corresponder,
                        // antes knowledgeDoc/knowledgeChunk flush,Claves foráneas garantizadas/La semántica de la asociación es coherente con el orden de exportación.
                        "knowledgeFolder" -> obj["data"]?.let {
                            knowledgeFolderBuf.add(
                                json.decodeFromJsonElement(
                                    io.zer0.muse.data.knowledge.KnowledgeFolderEntity.serializer(),
                                    it,
                                ),
                            )
                            if (knowledgeFolderBuf.size >= IMPORT_BATCH) {
                                flushBatch(knowledgeFolderBuf) { batch ->
                                    db.withTransaction {
                                        batch.forEach { db.knowledgeFolderDao().upsert(it) }
                                    }
                                }
                            }
                        }
                        "subagentThread" -> obj["data"]?.let {
                            subagentThreadBuf.add(json.decodeFromJsonElement(SubagentThreadEntity.serializer(), it))
                            if (subagentThreadBuf.size >= IMPORT_BATCH) {
                                flushBatch(subagentThreadBuf) { batch ->
                                    db.withTransaction {
                                        batch.forEach { db.subagentThreadDao().upsert(it) }
                                    }
                                }
                            }
                        }
                        "toolRound" -> obj["data"]?.let {
                            toolRoundBuf.add(json.decodeFromJsonElement(ToolRoundEntity.serializer(), it))
                            if (toolRoundBuf.size >= IMPORT_BATCH) {
                                flushBatch(
                                    toolRoundBuf,
                                ) { batch -> db.withTransaction { db.toolRoundDao().upsertAll(batch) } }
                            }
                        }
                        "sessionBranchHead" -> obj["data"]?.let {
                            sessionBranchHeadBuf.add(json.decodeFromJsonElement(SessionBranchHeadEntity.serializer(), it))
                            if (sessionBranchHeadBuf.size >= IMPORT_BATCH) {
                                flushBatch(sessionBranchHeadBuf) { batch ->
                                    db.withTransaction {
                                        batch.forEach { db.sessionBranchHeadDao().upsert(it) }
                                    }
                                }
                            }
                        }
                        // v2.6.7 fix (AUDIT F1): Restaurar las relaciones de tareas y roles.
                        // DeleteAll se eliminó en la fase de limpieza y se debe reponer aquí; de lo contrario, se descartará el contenido exportado.
                        "sessionTodo" -> obj["data"]?.let {
                            sessionTodoBuf.add(
                                json.decodeFromJsonElement(
                                    io.zer0.muse.data.todo.SessionTodoEntity.serializer(),
                                    it,
                                ),
                            )
                            if (sessionTodoBuf.size >= IMPORT_BATCH) {
                                flushBatch(sessionTodoBuf) { batch ->
                                    db.withTransaction { batch.forEach { db.sessionTodoDao().upsert(it) } }
                                }
                            }
                        }
                        "pendingThought" -> obj["data"]?.let {
                            pendingThoughtBuf.add(
                                json.decodeFromJsonElement(
                                    io.zer0.muse.data.proactive.PendingThoughtEntity.serializer(),
                                    it,
                                ),
                            )
                            if (pendingThoughtBuf.size >= IMPORT_BATCH) {
                                flushBatch(pendingThoughtBuf) { batch ->
                                    db.withTransaction { batch.forEach { db.pendingThoughtDao().upsert(it) } }
                                }
                            }
                        }
                        "proactiveOutcome" -> obj["data"]?.let {
                            proactiveOutcomeBuf.add(
                                json.decodeFromJsonElement(
                                    io.zer0.muse.data.proactive.ProactiveOutcomeEntity.serializer(),
                                    it,
                                ),
                            )
                            if (proactiveOutcomeBuf.size >= IMPORT_BATCH) {
                                flushBatch(proactiveOutcomeBuf) { batch ->
                                    db.withTransaction { batch.forEach { db.proactiveOutcomeDao().upsert(it) } }
                                }
                            }
                        }
                        "proactiveRelation" -> obj["data"]?.let {
                            proactiveRelationBuf.add(
                                json.decodeFromJsonElement(
                                    io.zer0.muse.data.proactive.ProactiveRelationEntity.serializer(),
                                    it,
                                ),
                            )
                            if (proactiveRelationBuf.size >= IMPORT_BATCH) {
                                flushBatch(proactiveRelationBuf) { batch ->
                                    db.withTransaction { batch.forEach { db.proactiveRelationDao().upsert(it) } }
                                }
                            }
                        }
                        "assistantRelationship" -> obj["data"]?.let {
                            assistantRelationshipBuf.add(
                                json.decodeFromJsonElement(
                                    io.zer0.muse.data.relationship.AssistantRelationshipEntity.serializer(),
                                    it,
                                ),
                            )
                            if (assistantRelationshipBuf.size >= IMPORT_BATCH) {
                                flushBatch(assistantRelationshipBuf) { batch ->
                                    db.withTransaction {
                                        batch.forEach { db.assistantRelationshipDao().upsert(it) }
                                    }
                                }
                            }
                        }
                        "groupChatMemory" -> obj["data"]?.let {
                            groupChatMemoryBuf.add(json.decodeFromJsonElement(GroupChatMemoryEntity.serializer(), it))
                            if (groupChatMemoryBuf.size >= IMPORT_BATCH) {
                                flushBatch(groupChatMemoryBuf) { batch ->
                                    db.withTransaction {
                                        batch.forEach { db.groupChatMemoryDao().insert(it) }
                                    }
                                }
                            }
                        }
                        // v2.6.8: Entidad de tarjeta. La ranura de acoplamiento tiene un índice único. Al lavar en lotes, primero se deben verificar los duplicados.
                        // De lo contrario, será arrojado a la capa SQLite. "UNIQUE constraint failed", la solución de problemas no revela que los datos de la copia de seguridad estén dañados.
                        "cardEntity" -> obj["data"]?.let {
                            cardEntityBuf.add(json.decodeFromJsonElement(CardEntityRecord.serializer(), it))
                            if (cardEntityBuf.size >= IMPORT_BATCH) {
                                flushBatch(cardEntityBuf) { batch ->
                                    db.withTransaction { db.appendCardEntities(batch) }
                                }
                            }
                        }
                        "scopedFact" -> {
                            val scope = (obj["scope"] as? JsonPrimitive)?.contentOrNull ?: return@forEachIndexed
                            val facts = obj["data"]?.let {
                                json.decodeFromJsonElement(
                                    ListSerializer(FactEntity.serializer()),
                                    it,
                                )
                            } ?: return@forEachIndexed
                            scopedFactBuf.getOrPut(scope) { mutableListOf() }.addAll(facts)
                        }
                        "settings" -> obj["data"]?.let {
                            settingsSnapshot = json.decodeFromJsonElement(
                                MapSerializer(
                                    String.serializer(),
                                    String.serializer(),
                                ),
                                it,
                            )
                        }
                    }
                }

                // B-34: Verifique el recuento de metadeclaraciones antes de confirmar la transacción de MuseDb y revierta toda la copia de seguridad de las filas truncadas o perdidas.
                expectedCounts.forEach { (key, expected) ->
                    val actual = actualCounts[key] ?: 0
                    check(actual == expected) {
                        "El número de registros de respaldo es inconsistente: $key Declaración $expected, real $actual"
                    }
                }
                // saltar no es un comportamiento silencioso:Las pequeñas sesiones telefónicas antiguas dejan huellas cuando se desechan
                if (skippedMiniPhoneSessions > 0) {
                    Logger.w(
                        "BackupService",
                        "saltar ${skippedMiniPhoneSessions} Una vieja pequeña conversación por teléfono celular y su ${skippedMiniPhoneMessages} mensajes" +
                            "(Esta función ha estado fuera de línea y ya no se restaurará como una sesión normal)",
                    )
                }

                // 3. flush restante buffer(C-10: memory/fact No más almacenamiento en búfer aquí flush,
                // Unifique eliminar todo + insertar una sola vez en una transacción independiente después de que la transacción MuseDb sea exitosa.).
                if (sessionBuf.isNotEmpty()) {
                    sessionCount += sessionBuf.size
                    flushBatch(sessionBuf) { batch -> db.withTransaction { batch.forEach { db.sessionDao().insert(it) } } }
                }
                if (messageBuf.isNotEmpty()) {
                    messageCount += messageBuf.size
                    flushBatch(messageBuf) { batch -> db.withTransaction { batch.forEach { db.messageDao().upsert(it) } } }
                }
                // v3: flush mesa extendida
                flushBatch(assistantBuf) { batch -> db.withTransaction { db.assistantDao().insertAll(batch) } }
                flushBatch(lorebookBuf) { batch -> db.withTransaction { db.lorebookDao().insertAll(batch) } }
                flushBatch(skillBuf) { batch -> db.withTransaction { batch.forEach { db.skillDao().upsert(it) } } }
                flushBatch(artifactBuf) { batch -> db.withTransaction { batch.forEach { db.artifactDao().upsert(it) } } }
                flushBatch(quickMsgBuf) { batch -> db.withTransaction { db.quickMessageDao().insertAll(batch) } }
                flushBatch(promptInjBuf) { batch -> db.withTransaction { db.promptInjectionDao().insertAll(batch) } }
                flushBatch(folderBuf) { batch -> db.withTransaction { batch.forEach { db.folderDao().insert(it) } } }
                flushBatch(groupChatBuf) { batch -> db.withTransaction { batch.forEach { db.groupChatDao().upsert(it) } } }
                flushBatch(groupChatMsgBuf) { batch -> db.withTransaction { db.groupChatMessageDao().insertAll(batch) } }
                flushBatch(schedTaskBuf) { batch -> db.withTransaction { batch.forEach { db.scheduledTaskDao().upsert(it) } } }
                flushBatch(schedExecBuf) { batch -> db.withTransaction { batch.forEach { db.scheduledTaskExecutionDao().insert(it) } } }
                flushBatch(knowDocBuf) { batch -> db.withTransaction { batch.forEach { db.knowledgeDocDao().upsert(it) } } }
                flushBatch(knowChunkBuf) { batch -> db.withTransaction { db.knowledgeChunkDao().insertAll(batch) } }
                flushBatch(experienceBuf) { batch -> db.withTransaction { batch.forEach { db.experienceDao().upsert(it) } } }
                flushBatch(milestoneBuf) { batch -> db.withTransaction { batch.forEach { db.milestoneDao().upsert(it) } } }
                flushBatch(agentMsgBuf) { batch -> db.withTransaction { batch.forEach { db.agentMessageDao().upsert(it) } } }
                // P0-10: entidad previamente desaparecida
                flushBatch(quickNoteBuf) { batch -> db.withTransaction { batch.forEach { db.quickNoteDao().upsert(it) } } }
                flushBatch(worldBookBuf) { batch -> db.withTransaction { batch.forEach { db.worldBookDao().upsert(it) } } }
                flushBatch(convEventBuf) { batch -> db.withTransaction { db.conversationEventDao().insertAll(batch) } }
                flushBatch(convTurnBuf) { batch -> db.withTransaction { batch.forEach { db.conversationTurnDao().upsert(it) } } }
                flushBatch(messagePartBuf) { batch -> db.withTransaction { db.messagePartDao().upsertAll(batch) } }
                flushBatch(messageOutboxBuf) { batch -> db.withTransaction { batch.forEach { db.messageOutboxDao().upsert(it) } } }
                flushBatch(translateHistoryBuf) { batch -> db.withTransaction { batch.forEach { db.translateHistoryDao().insert(it) } } }
                flushBatch(knowledgeBaseBuf) { batch -> db.withTransaction { batch.forEach { db.knowledgeBaseDao().upsert(it) } } }
                // AUD-20261011-NDJSON-FOLDERS: buffer restante flush(Mismo grupo que la base de conocimientos.,transacción entrante)
                flushBatch(knowledgeFolderBuf) { batch ->
                    db.withTransaction { batch.forEach { db.knowledgeFolderDao().upsert(it) } }
                }
                flushBatch(subagentThreadBuf) { batch -> db.withTransaction { batch.forEach { db.subagentThreadDao().upsert(it) } } }
                flushBatch(toolRoundBuf) { batch -> db.withTransaction { db.toolRoundDao().upsertAll(batch) } }
                flushBatch(sessionBranchHeadBuf) { batch -> db.withTransaction { batch.forEach { db.sessionBranchHeadDao().upsert(it) } } }
                flushBatch(groupChatMemoryBuf) { batch -> db.withTransaction { batch.forEach { db.groupChatMemoryDao().insert(it) } } }
                // v2.6.7 fix (AUDIT F1): Tareas pendientes y relación de roles.
                flushBatch(sessionTodoBuf) { batch -> db.withTransaction { batch.forEach { db.sessionTodoDao().upsert(it) } } }
                // v2.6.9: Limpia la cola de las tres tablas de mensajes activos.
                flushBatch(pendingThoughtBuf) { batch ->
                    db.withTransaction { batch.forEach { db.pendingThoughtDao().upsert(it) } }
                }
                flushBatch(proactiveOutcomeBuf) { batch ->
                    db.withTransaction { batch.forEach { db.proactiveOutcomeDao().upsert(it) } }
                }
                flushBatch(proactiveRelationBuf) { batch ->
                    db.withTransaction { batch.forEach { db.proactiveRelationDao().upsert(it) } }
                }
                flushBatch(assistantRelationshipBuf) { batch ->
                    db.withTransaction { batch.forEach { db.assistantRelationshipDao().upsert(it) } }
                }
                // v2.6.8: Se vacía el final de la entidad de la tarjeta (las tarjetas de acoplamiento y las tarjetas de desacoplamiento se escriben en el mismo orden).
                flushBatch(cardEntityBuf) { batch ->
                    db.withTransaction { db.appendCardEntities(batch) }
                }

                // v2.6.0 (AUD-DATA-B34): Conciliación después del almacenamiento. —— B-34 Originalmente solo comprobado“Número de líneas analizadas”,
                // El éxito del análisis no significa el éxito de la eliminación de la base de datos (la inserción del vaciado del búfer puede perder registros debido a conflictos de restricciones, etc.).
                // Aquí, se vuelve a verificar el número real de filas en la tabla principal contable y la brecha es la reversión general.
                // evitar“Importación exitosa pero faltan datos parcialmente”Pasó en silencio.
                verifyInsertedCounts(expectedCounts, skippedSessionCount = skippedMiniPhoneSessions)
            } // Auditoría 0.5: cierre de grandes transacciones de MuseDb(Reversión completa en caso de falla,Contiene limpieza)

            // C-10: memory/fact Confíe en una base de datos independiente,No se puede cruzar el repositorio atómico con MuseDb.
            // Sólo en MuseDb la transacción tiene éxito(Ven aquí)después,Cada uno dentro de una sola transacción"Primero borre los datos antiguos y luego inserte datos nuevos",
            // evitar la implementación anterior"Insertar→Limpiar de nuevo→El búfer está vacío y no se puede volver a insertar."pérdida resultante de datos de memoria/hechos,
            // También elimina el compromiso anticipado causado por el vaciado anticipado dentro de las transacciones de MuseDb.
            // compensación:memory/fact Registre toda la memoria temporal y colóquela en la base de datos de una vez,aceptable——
            // Su volumen de datos es mucho menor que messages,Y a cambio del orden correcto y límites de presentación consistentes.
            memoryDb.withTransaction {
                memoryDb.sessionSummaryDao().deleteAll()
                memoryDb.contextCheckpointDao().deleteAll()
                memoryDb.dailyStateDao().deleteAll()
                memoryDb.compiledSectionDao().deleteAll()
                memoryDb.scopedCompiledSectionDao().deleteAll()
                summaryBuf.forEach { memoryDb.sessionSummaryDao().upsert(it) }
                contextCheckpointBuf.forEach { memoryDb.contextCheckpointDao().upsert(it) }
                dailyBuf.forEach { memoryDb.dailyStateDao().upsert(it) }
                compiledBuf.forEach { memoryDb.compiledSectionDao().upsert(it) }
                scopedCompiledBuf.forEach { memoryDb.scopedCompiledSectionDao().upsert(it) }
            }
            factDb.withTransaction {
                factDb.factDao().deleteAll()
                if (factBuf.isNotEmpty()) {
                    factDb.factDao().insertAll(factBuf.map { it.copy(id = 0) })
                }
            }
            // P0-10: Restaurar la biblioteca del subasistente(Biblioteca independiente,Borrar base de datos por base de datos+escribir)
            scopedFactBuf.forEach { (scope, facts) ->
                writeScopedFacts(scope, facts)
            }
            // Restaurar instantánea de configuración
            if (settingsSnapshot.isNotEmpty()) {
                settings.restoreSettingsSnapshot(
                    settingsSnapshot,
                    includeSecrets = settingsSnapshot.containsKey("providers_json"),
                )
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("BackupService", "Falló la importación de streaming,revertido: ${e.message}", e)
            throw e
        }
        // v1.0.74 fix: Los mensajes en línea omiten la sincronización FTS,Heurística de conteo para asegurarFtsIndexConsistent al inicio
        // (El número de mensajes es exactamente el mismo.)saltará rebuild,El índice de búsqueda se atascó antes de la importación. La importación completa la reconstrucción explícita.
        // FTS Es parte del resultado de la recuperación; el error debe propagarse hacia afuera y desencadenar la reversión del punto de recuperación persistente.
        // No puede simplemente registrar el registro y continuar afirmando que la recuperación fue exitosa como una actualización normal del índice derivado.
        sessionRepository.rebuildFtsIndex()
        invalidateRagAfterRestore()

        // v4: Restaurar el almacenamiento de archivos(en la lista blanca,Un solo fallo no bloquea)
        if (fileStoreBuf.isNotEmpty()) writeFileStores(fileStoreBuf)
        // v4.1: Recuperar archivos del catálogo(Lista blanca + límite de capacidad;El error de escritura se propaga hacia arriba para desencadenar la reversión)
        if (binaryFileBuf.stats.skippedFiles > 0) {
            Logger.w(
                "BackupService",
                "NDJSON Importación omitida ${binaryFileBuf.stats.skippedFiles} archivos de directorio" +
                    "(${binaryFileBuf.stats.skippedBytes} bytes),Archivos adjuntos restaurados/El avatar puede estar incompleto",
            )
        }
        if (binaryFileBuf.toMap().isNotEmpty()) writeBinaryFiles(binaryFileBuf.toMap())

        Logger.i("BackupService", "Importación de streaming completada: $sessionCount sesión, $messageCount noticias")
        return sessionCount to messageCount
    }

    private suspend fun applyNdJsonStreamingWithImageCleanup(lines: Sequence<String>): Pair<Int, Int> {
        return restoreAndPruneMessageImages(
            imageStore = messageImageStore,
            restore = { applyNdJsonStreamingWithRecovery(lines) },
            readReferencedPaths = { readReferencedMessageImagePaths() },
        )
    }

    /**
     * NDJSON Contenedor de recuperación entre repositorios importado.
     *
     * NDJSON El cuerpo no puede reutilizar la preparación JSON única; aquí, el estado actual se escribe primero en transmisión NDJSON
     * Conserve el punto de recuperación y luego realice la importación de destino. Falló la base de datos independiente o el paso FTS,
     * Todos se transmiten desde este punto de recuperación.
     */
    private suspend fun applyNdJsonStreamingWithRecovery(lines: Sequence<String>): Pair<Int, Int> {
        val journalEntry = restoreJournal.begin(
            restoreId = "restore-${UUID.randomUUID()}",
            sourceHash = "ndjson-${UUID.randomUUID()}",
            backupVersion = 4,
            recoveryFormat = RestoreJournal.RecoveryFormat.NDJSON,
        )
        var currentJournal = journalEntry
        return try {
            currentJournal = restoreJournal.advance(currentJournal, RestoreJournal.Phase.STAGING)
            restoreStagingStore.writeRecoveryNdJson(currentJournal) { writer ->
                writeNdJson(writer)
            }
            currentJournal = restoreJournal.advance(currentJournal, RestoreJournal.Phase.VALIDATING)
            currentJournal = restoreJournal.advance(currentJournal, RestoreJournal.Phase.COMMITTING)
            val result = applyNdJsonStreaming(lines)
            currentJournal = restoreJournal.advance(
                currentJournal,
                RestoreJournal.Phase.REBUILDING,
                completedStores = setOf(
                    RestoreJournal.Store.MUSE_DB,
                    RestoreJournal.Store.MEMORY_DB,
                    RestoreJournal.Store.FACT_DB,
                    RestoreJournal.Store.SETTINGS,
                    RestoreJournal.Store.FTS,
                ),
            )
            restoreJournal.complete(currentJournal)
            restoreStagingStore.cleanup(currentJournal)
            result
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            val rolledBack = rollbackNdJsonRestore(currentJournal, e)
            if (!rolledBack) restoreJournal.fail(currentJournal, e)
            throw e
        } catch (e: Exception) {
            val rolledBack = rollbackNdJsonRestore(currentJournal, e)
            if (!rolledBack) restoreJournal.fail(currentJournal, e)
            throw e
        }
    }

    private suspend fun rollbackNdJsonRestore(entry: RestoreJournal.Entry, originalError: Throwable): Boolean =
        withContext(kotlinx.coroutines.NonCancellable) {
            try {
                Logger.w("BackupService", "NDJSON La recuperación falló,Revertir un punto de recuperación persistente: ${originalError.message}", originalError)
                var rollbackJournal = restoreJournal.advance(entry, RestoreJournal.Phase.ROLLING_BACK)
                restoreStagingStore.readRecoveryNdJson(entry) { recoveryLines ->
                    applyNdJsonStreaming(recoveryLines, allowEmptyBackup = true)
                }
                rollbackJournal = restoreJournal.advance(
                    rollbackJournal,
                    RestoreJournal.Phase.REBUILDING,
                    completedStores = setOf(
                        RestoreJournal.Store.MUSE_DB,
                        RestoreJournal.Store.MEMORY_DB,
                        RestoreJournal.Store.FACT_DB,
                        RestoreJournal.Store.SETTINGS,
                        RestoreJournal.Store.FTS,
                    ),
                )
                restoreJournal.complete(rollbackJournal)
                restoreStagingStore.cleanup(rollbackJournal)
                true
            } catch (rollbackError: Exception) {
                Logger.e("BackupService", "NDJSON Falló la reversión del punto de recuperación,Mantenga libros de contabilidad y copias: ${rollbackError.message}", rollbackError)
                false
            }
        }

    /**
     * P0-10: Enumerar los archivos de la subbiblioteca del subasistente que ya existen en el disco facts_<id>.db,Devuelve una lista de ID de asistente.
     * No contiene biblioteca predeterminada facts.db(Sus datos han sido [factDb] Exportar por separado);-wal/-shm Los archivos adjuntos también quedan excluidos.
     */
    private fun listScopedFactDbIds(): List<String> {
        val defaultDb = context.getDatabasePath(FACT_DB_FILE_NAME)
        val parent = defaultDb.parentFile ?: return emptyList()
        return parent.listFiles { f ->
            f.isFile && f.name.startsWith(SCOPED_FACT_DB_PREFIX) && f.name.endsWith(".db")
        }?.mapNotNull { f ->
            f.name.removePrefix(SCOPED_FACT_DB_PREFIX).removeSuffix(".db")
                .takeIf { it.isNotBlank() && it != "db" }
        }?.distinct() ?: emptyList()
    }

    /**
     * P0-10: Lea todos los datos de la rama especificada.(para exportar).
     * La biblioteca sucursal es independiente de la biblioteca predeterminada.,No participar en transacciones de MuseDb;Después de leer, libere el identificador de caché a través de proveedor.release,
     * Evite la acumulación de copias de seguridad a largo plazo fd(Consistente con la semántica de liberación al eliminar un asistente).
     */
    private suspend fun readScopedFacts(assistantId: String): List<FactEntity> {
        val db = factDbProvider.getFactDb(assistantId)
        return try {
            db.factDao().getAll()
        } finally {
            runCatching { factDbProvider.release(assistantId) }
        }
    }

    /**
     * P0-10: Escriba los datos de la rama en el ID de asistente especificado. facts_<id>.db(para la recuperación).
     * Vaciar primero y luego insertar.(Consistente con la semántica de recuperación de biblioteca predeterminada),id La clave principal de incremento automático se restablece a 0 cuando la base de datos la importa y reasigna.
     */
    private suspend fun writeScopedFacts(assistantId: String, facts: List<FactEntity>) {
        if (facts.isEmpty()) return
        val db = factDbProvider.getFactDb(assistantId)
        try {
            db.withTransaction {
                db.factDao().deleteAll()
                db.factDao().insertAll(facts.map { it.copy(id = 0) })
            }
        } finally {
            runCatching { factDbProvider.release(assistantId) }
        }
    }

    /**
     * Ayudante de inserción por lotes: realice la inserción y borre el búfer cuando no esté vacío.
     */
    private suspend fun <T> flushBatch(buf: MutableList<T>, insert: suspend (List<T>) -> Unit) {
        if (buf.isEmpty()) return
        insert(buf.toList())
        buf.clear()
    }

    /** v2.0: Calentamiento explícito después de la importación a nivel de archivo Room,Evite que las primeras escrituras posteriores lleguen a la conexión cerrada. */
    private fun warmUpRoomAfterImport() {
        runCatching { db.openHelper.writableDatabase }
            .onFailure { Logger.w("BackupService", "La sala no pudo volver a abrirse después de la importación", it) }
        // v2.6.7 fix (AUDIT F7): DB Ha sido reemplazado por completo y la instantánea pendiente en la memoria debe invalidarse.
        // UI Suscríbase únicamente al flujo de memoria de TodoTool. Si no se limpia, se seguirá mostrando la lista anterior antes de la restauración.
        runCatching { io.zer0.muse.tools.TodoTool.clearAll() }
            .onFailure { Logger.w("BackupService", "Limpiar instantáneas pendientes después de que falla la importación", it) }
    }
    companion object {
        /** v4: Almacenamiento de archivos incluido en las copias de seguridad(relativamente filesDir). */
        internal val backupFileStorePaths = listOf(
            "channel_configs.json",
            "connector_configs.json",
            "annotations.json",
            "channel_inbox.json",
            "plugin_configs.json",
            "plugin_registry.json",
            "pinned_memory/pinned-memory.json",
            "pinned_memory/pinned.md",
            // v4.1: Enlace de datos de tarjeta(cardId → JSON texto). Anteriormente excluido de la lista blanca,
            // Después de la recuperación, se perderán todos los datos de la tarjeta escritos por show_card/update_card_data.
            "card_data.json",
        )

        /** P0-10: Nombre de archivo de base de datos predeterminado(facts.db). */
        internal const val FACT_DB_FILE_NAME = "facts.db"

        /** P0-10: Prefijo de archivo de biblioteca del subasistente(facts_<id>.db). */
        internal const val SCOPED_FACT_DB_PREFIX = "facts_"

        /** Número de elementos insertados en cada lote de importación de streaming(Equilibrio de la sobrecarga de transacciones con picos de memoria). */
        private const val IMPORT_BATCH = 500

        /**
         * B-25: Tamaño máximo de una única copia de seguridad JSON(bytes,64MB).
         * Cualquier cosa más que eso se considera malicioso./Copia de seguridad corrupta,Rechace el análisis al importar para evitar OOM.
         */
        internal const val MAX_SINGLE_JSON_BACKUP_BYTES: Long = 64L * 1024 * 1024

        /**
         * B-25: Determine si el volumen acumulado de una única copia de seguridad JSON todavía está dentro del límite superior.
         * Al leer línea por línea, utilice [cumulativeUtf8Bytes] Bytes acumulados consumidos,anexar [additionalUtf8Bytes] Luego determine si excede el límite.
         * @return true No exceder el límite;false ha superado el límite(La persona que llama debería abortar con un error.).
         */
        internal fun withinSingleJsonLimit(cumulativeUtf8Bytes: Long, additionalUtf8Bytes: Long): Boolean =
            cumulativeUtf8Bytes <= MAX_SINGLE_JSON_BACKUP_BYTES - additionalUtf8Bytes

        /** B-25: Genere un mensaje de error claro para el desbordamiento de la copia de seguridad JSON única(Contiene bytes de tamaño actual y límite superior predeterminado 64MB). */
        internal fun singleJsonTooLargeMessage(cumulativeUtf8Bytes: Long): String =
            "Volumen de copia de seguridad JSON único ${(cumulativeUtf8Bytes + 1024 * 1024 - 1) / (1024 * 1024)}MB Superar el límite superior " +
                "${MAX_SINGLE_JSON_BACKUP_BYTES / (1024 * 1024)}MB,Importación rechazada"

        /**
         * P0-1: Determinación de invalidación de contraseña de respaldo(Exportar/Subir una guardia única compartida).
         * El usuario ha establecido una contraseña([CloudBackupConfig.backupPasswordSet])Pero después de que se pierde la clave del almacén de claves
         * decrypt Leer de nuevo como una cadena vacía → Deshabilitar la degradación para borrar la escritura de texto,Debe rechazarse y solicitar reinicio.
         * Determinado independientemente como interno para facilitar pruebas individuales(no depende de Context/ContentResolver).
         */
        @JvmStatic
        internal fun isBackupPasswordUnavailable(config: CloudBackupConfig): Boolean =
            config.backupPasswordSet && config.backupPassword.isBlank()

        /**
         * pregunta7.3: Recopilación de claves de configuración relacionadas con el dispositivo,Omita estas claves al restaurar la configuración Instantánea.
         *
         * - "theme_mode":Los modos temáticos pueden seguir el sistema./Estado del dispositivo,Restaurar entre dispositivos no tiene sentido
         * - "bool:dynamic_color":Material You Fondo de pantalla dependiente del dispositivo de color dinámico,No disponible en todos los dispositivos
         * - "display_density"/"window_width_class":reservado(La instantánea actual no se exporta,Pero como futura defensa ampliada)
         */
        private val DEVICE_SPECIFIC_KEYS = setOf(
            "theme_mode",
            "bool:dynamic_color",
            "display_density",
            "window_width_class",
        )
    }

    /**
     * Phase 8.9: Subir datos actuales a la nube(S3 / WebDAV).
     * Cargue también una versión archivada con marca de tiempo. + muse-backup-latest.json(para una recuperación rápida).
     * @return true éxito;false Copia de seguridad en la nube fallida o no configurada
     */
    /**
     * F-04: Clasificación de resultados de respaldo en la nube. Diferente del valor booleano,Copia de notificación de decisión de motivo de falla y registro de diagnóstico:
     *  - [WRITE_FAILED]: La carga en sí falló(red/rechazo remoto/Autenticación);
     *  - [VERIFY_FAILED]: La carga se realizó correctamente pero falló la verificación de lectura.(el tamaño no coincide/Volver a leer como vacío),Los datos pueden estar incompletos;
     *  - [NOT_CONFIGURED]: Copia de seguridad en la nube no configurada,No molesta a los usuarios.
     */
    enum class CloudBackupOutcome { SUCCESS, WRITE_FAILED, VERIFY_FAILED, NOT_CONFIGURED, PASSWORD_UNAVAILABLE }

    /**
     * F-04: Escribir registro de respaldo(auto_backup_log mesa,La página de diagnóstico es visible.).
     * @param status success / write_failed / verify_failed
     */
    private suspend fun logCloudBackup(status: String, size: Long, messageCount: Long, error: String) {
        try {
            autoBackupLogDao.insert(
                AutoBackupLogEntity(
                    backupPath = "cloud",
                    fileSizeBytes = size,
                    status = status,
                    errorMessage = error,
                    messageCount = messageCount,
                ),
            )
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            // El error de escritura del registro no bloquea el proceso de copia de seguridad principal,Sólo iniciar sesión
            Logger.w("BackupService", "logCloudBackup failed: ${e.message}")
        }
    }

    /**
     * F-04: Recibo de tres etapas de copia de seguridad en la nube — Escribir + leer verificación + registro.
     *
     * accidente historico: Volver tras una carga exitosa true, Sin verificación; La nube pierde bytes silenciosamente/Usuario de medio tiempo
     * No hay forma de saber que la copia de seguridad está dañada., Faltan datos después de la recuperación. proceso actual:
     *  1. escribir NDJSON(bytes de memoria,Consistente con la implementación anterior);
     *  2. Subir a la nube;
     *  3. Después de cargar con éxito [downloadLatestBackup] Vuelva a leer y compare el número de bytes.(Vuelva a leer y verifique después de escribir);
     *  4. resultado(Incluyendo razones de falla)escribir auto_backup_log, Para visualización de diagnóstico de página de respaldo.
     */
    suspend fun exportToCloud(includeSecrets: Boolean = false): CloudBackupOutcome {
        val config = settings.cloudBackupConfigFlow.first()
        // ReturnCount restricción(umbral 2): Sólo 1 en todo el viaje. return, Los resultados se unifican cuando se resumen.
        return when {
            !config.isConfigured -> CloudBackupOutcome.NOT_CONFIGURED
            // B-9/P0-1: El usuario ha establecido una contraseña de respaldo pero falló el descifrado del almacén de claves(password esta vacio)tiempo,
            // Negarse a bajar la categoría para borrar la carga de texto(Exportar el mismo juicio que la exportación local. [isBackupPasswordUnavailable]),
            // Solicitud de reinicio,Evite que conversaciones sensibles queden en la nube en texto plano.
            isBackupPasswordUnavailable(config) -> CloudBackupOutcome.PASSWORD_UNAVAILABLE
            else -> {
                val write = writeBackupToCloud(config, includeSecrets)
                when {
                    write == null -> CloudBackupOutcome.WRITE_FAILED
                    !verifyCloudBackupWrite(config, write) -> CloudBackupOutcome.VERIFY_FAILED
                    else -> {
                        settings.saveCloudBackupConfig(config.copy(lastSyncAt = System.currentTimeMillis()))
                        CloudBackupOutcome.SUCCESS
                    }
                }
            }
        }
    }

    /**
     * P0-1: Determinación de invalidación de contraseña de respaldo(Exportar/Subir una guardia única compartida).
     * El usuario ha establecido una contraseña([CloudBackupConfig.backupPasswordSet])Pero después de que se pierde la clave del almacén de claves
     * decrypt Leer de nuevo como una cadena vacía → Deshabilitar la degradación para borrar la escritura de texto,Debe rechazarse y solicitar reinicio.
     * Independientemente como determinación interna de alto nivel para facilitar las pruebas únicas(no depende de Context/ContentResolver).
     */
    private suspend fun requireBackupPasswordAvailable(context: Context) {
        if (isBackupPasswordUnavailable(settings.cloudBackupConfigFlow.first())) {
            error(context.getString(R.string.settings_backup_password_unavailable))
        }
    }

    /** F-04: Escalar información escrita en una copia de seguridad en la nube(Para verificación de lectura y registros). El resumen es para cargar texto cifrado. */
    private data class CloudWrite(val bytes: Long, val messages: Long, val sha256: String)

    /**
     * F-04: escribir NDJSON(bytes de memoria)y subir a la nube;El error se registra en el registro write_failed.
     * @return Escalar información al cargar exitosamente;null significa escribir/Error al subir
     */
    private suspend fun writeBackupToCloud(config: CloudBackupConfig, includeSecrets: Boolean = false): CloudWrite? {
        // B-25: Reutilizar productos de streaming writeNdJson — Serializar la escritura en el búfer de bytes uno por uno,no más buildBackup + encodeToString
        // (Backup Tres copias del objeto completo + cadena enorme + matriz de bytes residen en la memoria). La API de carga en la nube requiere bytes completos,
        // Aquí el pico es solo una copia de la matriz de bytes NDJSON.(Texto sin formato o cifrado),Reduzca significativamente el riesgo OOM de mensajes de nivel 10,000.
        val plaintext: ByteArray
        val writeSummary: NdJsonExportSummary
        ByteArrayOutputStream().use { baos ->
            BufferedWriter(OutputStreamWriter(baos, Charsets.UTF_8)).use { writer ->
                writeSummary = writeNdJson(writer, includeSecrets)
            }
            plaintext = baos.toByteArray()
        }
        // v1.x Diagnóstico: Tamaño récord de exportación,Fácil de solucionar problemas"La copia de seguridad está incompleta/Pérdida de datos después de la recuperación"problema de clase
        Logger.i(
            "BackupService",
            "Exportar copia de seguridad en la nube: ${writeSummary.sessions} sesión / ${writeSummary.messages} noticias" +
                " / ${writeSummary.facts} memoria / ${writeSummary.groupChatMessages} Mensajes de chat grupal",
        )
        val data = if (config.backupPassword.isNotEmpty()) {
            BackupCrypto.encrypt(plaintext, config.backupPassword)
        } else {
            plaintext
        }
        val uploaded = cloudBackupService.uploadBackupWithLatest(config, data)
        if (!uploaded) {
            Logger.w("BackupService", "Error al cargar la copia de seguridad en la nube(fase de escritura)")
            logCloudBackup(
                status = "write_failed",
                size = data.size.toLong(),
                messageCount = writeSummary.messages.toLong(),
                error = "upload failed",
            )
            return null
        }
        return CloudWrite(
            bytes = data.size.toLong(),
            messages = writeSummary.messages.toLong(),
            sha256 = sha256Hex(data),
        )
    }

    /**
     * F-04: Vuelva a leer y verifique después de escribir — Descargue la copia de seguridad que acaba de cargar y compare los bytes + SHA-256.
     *
     * Phase 3 (Fiabilidad P1): Una vez que la capa de bytes es coherente, se agrega la estructura NDJSON descifrada/meta verificar
     * (ver [validateCloudReadBackNdjson]),evitar"Los bytes son consistentes pero el contenido descifrado es ilegal."fue registrado como un éxito.
     * Resultado de la verificación(éxito o verify_failed,Contiene razones claras del fracaso.)Escribe auto_backup_log.
     */
    private suspend fun verifyCloudBackupWrite(config: CloudBackupConfig, write: CloudWrite): Boolean {
        val readBack = cloudBackupService.downloadLatestBackup(config)
        val readBackHash = readBack?.let(::sha256Hex)
        val byteVerified = readBack != null &&
            readBack.size.toLong() == write.bytes &&
            readBackHash == write.sha256
        if (!byteVerified) {
            Logger.w(
                "BackupService",
                "La verificación de lectura de la copia de seguridad en la nube falla(bytes): subir ${write.bytes} bytes/hash=${write.sha256}, " +
                    "volver a leer ${readBack?.size ?: -1} bytes/hash=${readBackHash ?: "-"}",
            )
            logCloudBackup(
                status = "verify_failed",
                size = write.bytes,
                messageCount = write.messages,
                error = "read-back mismatch: uploaded ${write.bytes}/${write.sha256}, " +
                    "got ${readBack?.size ?: -1}/${readBackHash ?: "-"}",
            )
            return false
        }
        // Phase 3 (P1): Bytes consistentes ≠ El contenido es recuperable — Verifique la estructura NDJSON después del descifrado/meta/Número de líneas
        val contentError =
            validateCloudReadBackNdjsonWithPolicy(
                readBack,
                config.backupPassword,
                config.backupPasswordSet,
            )
        if (contentError != null) {
            Logger.w("BackupService", "La verificación de lectura de la copia de seguridad en la nube falla(contenido): $contentError")
            logCloudBackup(
                status = "verify_failed",
                size = write.bytes,
                messageCount = write.messages,
                error = "read-back content invalid: $contentError",
            )
            return false
        }
        logCloudBackup(
            status = "success",
            size = write.bytes,
            messageCount = write.messages,
            error = "",
        )
        return true
    }

    /**
     * Phase 8.9: Descargue la última copia de seguridad de la nube e impórtela.
     * @return Conversaciones importadas + mensajes;null Indica que no hay errores de copia de seguridad o descarga
     */
    suspend fun importFromCloud(): Pair<Int, Int>? {
        val config = settings.cloudBackupConfigFlow.first()
        if (!config.isConfigured) return null
        val data = cloudBackupService.downloadLatestBackup(config) ?: return null
        return withBackupImportWriteGate { applyCloudBackupData(config, data) }
    }

    /**
     * v1.132: Descargue la versión de archivo especificada desde la nube e impórtela(suministro CloudBackupPage "Restaurar por versión"uso).
     * @param fileName Nombre del archivo comprimido(Como por ejemplo muse-backup-20250719-153000.json)
     * @return Conversaciones importadas + mensajes;null Indica que no hay errores de copia de seguridad o descarga
     */
    suspend fun importFromCloudFile(fileName: String): Pair<Int, Int>? {
        val config = settings.cloudBackupConfigFlow.first()
        if (!config.isConfigured) return null
        val data = cloudBackupService.downloadBackup(config, fileName) ?: return null
        return withBackupImportWriteGate { applyCloudBackupData(config, data) }
    }

    /**
     * F-27(Revisión de integridad funcional): Realizar copias de seguridad de paquetes automáticamente desde dentro(muse_backup_*.zip)Restaurar tres bases de datos.
     *
     * Paquete de respaldo automático por [io.zer0.muse.data.stats.AutoBackupHelper] si MuseDb/MemoryDb/FactDb
     * Empaque y genere después de ASPIRAR EN uno por uno. reanudar la adopción"Reemplazo a nivel de archivo + verificación de integridad + reversión en caso de falla":
     *   1. Cerrar tres habitaciones individuales,Evite identificadores de archivos que apunten a archivos antiguos que serán reemplazados;
     *   2. Primero copie las tres bibliotecas actuales al directorio de reversión(El fallo de reemplazo se puede restaurar);
     *   3. Sobrescriba las tres bibliotecas actuales con instantáneas,Limpiar el resto -wal/-shm/-journal;
     *   4. Realice PRAGMA integridad_check en el muse.db restaurado;
     *   5. Reconstruir el índice FTS de mensajes y hechos(VACUUM La tabla sombra FTS de Snapshot puede ser inconsistente);
     *   6. Devuelve el número de sesiones restauradas + el número de mensajes.
     *
     * Si falla cualquiera de los pasos anteriores, se revertirá al estado anterior al reemplazo y se generará una excepción.
     *
     * @param path Ruta completa del paquete de respaldo(de auto_backup_log.backupPath)
     * @return Número de sesiones restauradas + número de mensajes
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun restoreFromAutoBackup(path: String): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val bundle = File(path)
        require(bundle.exists() && bundle.isFile) { "El archivo de copia de seguridad automática no existe: $path" }
        // B-7: Ingrese al control de puerta de escritura a nivel de proceso,Pausar tic de memoria/sondeo de tareas/Copia de seguridad automática y otras escrituras en segundo plano,Evite la pérdida por escritura en tránsito del período ventana.
        require(io.zer0.common.ProcessWriteGate.begin()) { "Copia de seguridad existente y restauración en curso,No repetir operaciones" }
        val tmpDir = File(context.cacheDir, "auto_restore_${UUID.randomUUID()}")
        tmpDir.mkdirs()
        try {
            // B-24: Compatible con versiones anteriores de copia de seguridad automática de un solo archivo .db(En ese momento, solo había una biblioteca, muse.db.).
            // El archivo en sí es una instantánea de VACUUM.,Directamente como fuente muse.db,Sin desembalaje ZIP.
            val isLegacySingleDb = path.endsWith(".db", ignoreCase = true)
            val museSnap: File
            val memSnap: File?
            val factSnap: File?
            if (isLegacySingleDb) {
                museSnap = bundle
                memSnap = null
                factSnap = null
                Logger.i("BackupService", "restoreFromAutoBackup: Se detectó una copia de seguridad de la base de datos única de la versión anterior,Restaurar solo muse.db: $path")
            } else {
                extractAutoBackupBundle(bundle, tmpDir)
                museSnap = File(tmpDir, io.zer0.muse.data.stats.AutoBackupHelper.ENTITY_MUSE_DB)
                memSnap = File(tmpDir, io.zer0.muse.data.stats.AutoBackupHelper.ENTITY_MEMORY_DB)
                factSnap = File(tmpDir, io.zer0.muse.data.stats.AutoBackupHelper.ENTITY_FACTS_DB)
                require(museSnap.exists()) { "Falta el paquete de respaldo muse.db,No se puede recuperar" }
            }
            // Reemplazo de nivel de archivo(Incluye reversión en caso de falla)ejecutar dentro del bloqueo,Todas son operaciones sin suspensión.,Atomicidad garantizada;
            // FTS Estadísticas de reconstrucción y resultados.(reopen Room)Ejecutar bloqueo exterior,Evite que los puntos colgantes entren en secciones críticas.
            synchronized(autoRestoreLock) {
                val currentDbFile = context.getDatabasePath("muse.db")
                val currentMemoryFile = context.getDatabasePath("memory.db")
                val currentFactFile = context.getDatabasePath("facts.db")

                // 1. Cerrar habitación singleton
                db.close()
                memoryDb.close()
                factDb.close()

                // 2. Copie previamente las tres bibliotecas actuales en el directorio de reversión
                val rollbackDir = File(context.cacheDir, "auto_restore_rollback_${UUID.randomUUID()}")
                rollbackDir.mkdirs()
                val rollbackMuse = copyToDir(currentDbFile, rollbackDir, "muse.db")
                val rollbackMem = copyToDir(currentMemoryFile, rollbackDir, "memory.db")
                val rollbackFact = copyToDir(currentFactFile, rollbackDir, "facts.db")
                try {
                    // 3. Reemplazo de nivel de archivo(B-24: La versión anterior de la copia de seguridad de base de datos única memSnap/factSnap es null,Saltar el reemplazo correspondiente)
                    swapDatabase(museSnap, currentDbFile)
                    memSnap?.takeIf { it.exists() }?.let { swapDatabase(it, currentMemoryFile) }
                    factSnap?.takeIf { it.exists() }?.let { swapDatabase(it, currentFactFile) }

                    // 4. Biblioteca principal de verificación de integridad
                    require(verifyDbIntegrity(currentDbFile)) { "La verificación de integridad de muse.db falló después de la recuperación" }
                } catch (e: Exception) {
                    Logger.e("BackupService", "restoreFromAutoBackup Error al reemplazar el archivo,Revertir la biblioteca actual: ${e.message}", e)
                    // revertir:Restaurar con precopia;Guarde una copia cuando falle la reversión e infórmela con sinceridad
                    val rolledBack = listOf(
                        rollbackMuse to currentDbFile,
                        rollbackMem to currentMemoryFile,
                        rollbackFact to currentFactFile,
                    ).filter { (src, _) -> src != null && src.exists() }
                        .map { (src, dst) -> runCatching { swapDatabase(src!!, dst) }.isSuccess }
                        .all { it }
                    if (!rolledBack) {
                        Logger.e("BackupService", "restoreFromAutoBackup La reversión no fue completamente exitosa,Reinicie la aplicación para verificar los datos.")
                    }
                    throw e
                } finally {
                    rollbackDir.deleteRecursively()
                }
            }

            // 5a. v2.0: Vuelva a abrir explícitamente la sala y verifique;close Post-dependencia"Las visitas posteriores se reabrirán automáticamente"Fallará en algunos entornos.,Por lo tanto, reinicie en el acto. Si falla, se le pedirá que reinicie.
            runCatching { db.openHelper.writableDatabase }
                .onFailure { e ->
                    Logger.e("BackupService", "La base de datos no pudo volver a abrirse después de la recuperación", e)
                    error("Error al restablecer la conexión a la base de datos,Reinicie la aplicación e inténtelo de nuevo.")
                }
            restoreSettingsFromAutoBackup(path, isLegacySingleDb)
            // 5. fuera de la cerradura:reconstruir FTS(La tabla de sombra FTS de Snapshot puede ser inconsistente después de VACUUM)y estadísticas de resultados.
            //    Estas acciones hacen que Room vuelva a abrir archivos nuevos,El fracaso no bloquea la recuperación(El índice se puede reconstruir más tarde.).
            resultOf { sessionRepository.rebuildFtsIndex() }
                .onError { msg, t -> Logger.w("BackupService", "Error en la reconstrucción de FTS del mensaje posterior a la recuperación: $msg", t) }
            resultOf { factStore.rebuildFtsIndex() }
                .onError { msg, t -> Logger.w("BackupService", "La reconstrucción del FTS posterior a la recuperación falló: $msg", t) }
            invalidateRagAfterRestore()

            val sessions = db.sessionDao().observeAll().first()
            val messageCount = sessions.sumOf { session ->
                db.messageDao().observeBySession(session.id).first().size
            }
            Logger.i("BackupService", "restoreFromAutoBackup Completo: ${sessions.size} sesión, $messageCount noticias")
            sessions.size to messageCount
        } finally {
            // B-7: Salir del control de puerta de escritura(Debe restablecerse independientemente del éxito o el fracaso.)
            io.zer0.common.ProcessWriteGate.end()
            tmpDir.deleteRecursively()
        }
    }

    /**
     * v2.6.x: Lea la instantánea de configuración del paquete de copia de seguridad automática y vuelva a escribirla (solo claves de lista blanca no confidenciales).
     *
     * Si no hay ninguna entrada de configuración en el paquete anterior, se omitirá para mantener la compatibilidad con versiones anteriores; si la lectura o la reescritura fallan, se lanzará hacia arriba y se entregará al proceso de recuperación de periféricos para su reversión.
     */
    private suspend fun restoreSettingsFromAutoBackup(path: String, isLegacySingleDb: Boolean) {
        val snapshot = readAutoBackupSettingsSnapshot(path, isLegacySingleDb)
        if (!snapshot.isNullOrEmpty()) settings.restoreSettingsSnapshot(snapshot, includeSecrets = false)
    }

    /**
     * v2.6.x: Lea la instantánea de configuración del paquete de copia de seguridad automática.
     *
     * Sólo lectura [io.zer0.muse.data.stats.AutoBackupHelper.ENTITY_SETTINGS] Esta entrada
     * Lectura con streaming zip (con [extractAutoBackupBundle] Mismo modo), no descomprime el paquete completo en la memoria.
     * Si el paquete de copia de seguridad anterior o la copia de seguridad de la versión anterior de la base de datos única no tiene esta entrada, se devuelve nulo y el lado de recuperación omite la reescritura para mantener la compatibilidad con versiones anteriores.
     * Cuando la entrada existe pero no se puede analizar, se lanza hacia arriba (se maneja para revertirla) para evitar perder la configuración silenciosamente.
     */
    private fun readAutoBackupSettingsSnapshot(path: String, isLegacySingleDb: Boolean): Map<String, String>? {
        if (isLegacySingleDb) return null
        return findAutoBackupEntryText(path)
            ?.takeIf { it.isNotBlank() }
            ?.let { json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), it) }
    }

    /** Extraiga el contenido de texto de la entrada especificada según el nombre de la entrada en el paquete de copia de seguridad automática;Devuelve nulo si no está presente. */
    @Suppress("NestedBlockDepth", "ReturnCount") // while+zip Los bucles de lectura están inherentemente anidados;Regresar temprano=Detente cuando te golpeen
    private fun findAutoBackupEntryText(path: String): String? {
        ZipInputStream(BufferedInputStream(File(path).inputStream())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.substringAfterLast('/') == io.zer0.muse.data.stats.AutoBackupHelper.ENTITY_SETTINGS) {
                    return zis.readBytes().toString(Charsets.UTF_8)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return null
    }

    /**
     * F-27: Descomprima el paquete de copia de seguridad automática en un directorio temporal(Reconocer solo el nombre del archivo de entrada,Descartar jerarquía de directorios).
     */
    @Suppress("NestedBlockDepth") // while+zip Los bucles de lectura están inherentemente anidados
    private fun extractAutoBackupBundle(bundle: File, targetDir: File) {
        ZipInputStream(BufferedInputStream(bundle.inputStream())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.substringAfterLast('/')
                if (name.isNotBlank()) {
                    File(targetDir, name).outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /** F-27: Copie el archivo al directorio de destino y devuelva el nuevo archivo;Devuelve nulo si el archivo fuente no existe. */
    private fun copyToDir(src: File, dir: File, name: String): File? {
        if (!src.exists()) return null
        val dst = File(dir, name)
        return if (runCatching { src.copyTo(dst, overwrite = true) }.isSuccess) dst else null
    }

    /**
     * F-27: Sobrescribir el archivo de la biblioteca de destino con una instantánea,Y limpie los archivos adjuntos WAL/SHM/journal del mismo archivo,
     * Evite que los marcos residuales contaminen las nuevas bibliotecas. Lanza una excepción cuando la instantánea no existe,Deje que la persona que llama retroceda.
     */
    private fun swapDatabase(snapshot: File, target: File) {
        require(snapshot.exists()) { "La instantánea no existe: ${snapshot.absolutePath}" }
        snapshot.copyTo(target, overwrite = true)
        File(target.absolutePath + "-wal").delete()
        File(target.absolutePath + "-shm").delete()
        File(target.absolutePath + "-journal").delete()
    }

    /** F-27: Verifique la integridad del archivo SQLite usando PRAGMA integridad_check. */
    private fun verifyDbIntegrity(file: File): Boolean {
        return runCatching {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
            ).use { conn ->
                conn.rawQuery("PRAGMA integrity_check", null).use { c ->
                    if (c.moveToFirst()) c.getString(0) == "ok" else false
                }
            }
        }.getOrDefault(false)
    }

    /**
     * v1.132: Descargar desde la nube(posiblemente cifrado)El flujo de bytes se descifra, analiza e importa a la base de datos local.
     * Extraer la lógica común para [importFromCloud] / [importFromCloudFile] Reutilizar.
     * @return Conversaciones importadas + mensajes;null Indica descifrado/El análisis falló
     */
    private suspend fun applyCloudBackupData(config: CloudBackupConfig, data: ByteArray): Pair<Int, Int>? {
        if (shouldRejectPlaintextCloudBackup(data, config.backupPasswordSet, config.backupPassword)) {
            Logger.w("BackupService", "Denegar copia de seguridad en la nube de texto claro: El dispositivo actual tiene configurado el cifrado de respaldo")
            return null
        }
        val plaintext = if (BackupCrypto.isEncrypted(data)) {
            if (config.backupPassword.isEmpty()) {
                Logger.w("BackupService", "La copia de seguridad en la nube está cifrada pero no se establece ninguna contraseña de copia de seguridad,No se puede descifrar")
                return null
            }
            resultOf { BackupCrypto.decrypt(data, config.backupPassword) }
                .onError { msg, t ->
                    Logger.w("BackupService", "Error al descifrar la copia de seguridad en la nube(Contraseña incorrecta?)", t)
                    return null
                }
                .getOrNull() ?: return null
        } else {
            data
        }

        // B-23: No más porción entera plaintext.toString + lineSequence(El texto plano descifrado se duplicará y almacenará en la memoria.).
        // Utilice BufferedReader en su lugar para leer la transmisión línea por línea,Lea solo la rama JSON completa que realmente necesita ser analizada por completo.
        val rawReader = plaintext.inputStream().bufferedReader(Charsets.UTF_8)
        val firstLine = generateSequence { rawReader.readLine() }
            .firstOrNull { !it.isNullOrBlank() }
            ?: run {
                rawReader.close()
                return null
            }
        val isNdJson = resultOf {
            val obj = json.decodeFromString(JsonObject.serializer(), firstLine)
            obj["type"]?.let { (it as? JsonPrimitive)?.content } == "meta"
        }.getOrNull() ?: false

        return if (isNdJson) {
            // B-23: La primera línea se ha consumido.,Continuar transmitiendo la importación línea por línea desde el lector,Evite que resida toda la cadena NDJSON.
            rawReader.use { reader ->
                val rest = sequence {
                    while (true) {
                        val line = reader.readLine() ?: break
                        yield(line)
                    }
                }
                resultOf { applyNdJsonStreamingWithImageCleanup(sequenceOf(firstLine) + rest) }
                    .onError { _, t -> Logger.w("BackupService", "La importación de streaming de Cloud NDJSON falla", t) }
                    .getOrNull()
            }
        } else {
            rawReader.use { reader ->
                // B-25: La copia en la nube ya reside en la memoria.(Descargar + Descifrar),No hay forma de eliminar el periodo de descarga. OOM;
                // B-23: Se debe decodificar completamente una única ruta JSON en la memoria.,Por lo tanto, se conserva el límite superior del volumen para interceptar y evitar el análisis. OOM,
                // El límite superior se puede configurar como parámetro. [singleJsonMaxBytes] Ampliado para dar cabida a copias de seguridad legítimas más grandes(Compatible con versiones anteriores por defecto 64MB).
                val backup = resultOf {
                    json.decodeFromString(Backup.serializer(), readSingleJsonWithLimit(reader, firstLine))
                }.onError { msg, t ->
                    Logger.w("BackupService", "Lectura de copia de seguridad JSON única en la nube/El análisis falló,Negarse a restaurar: ${t?.message ?: msg}", t)
                }.getOrNull() ?: return null
                // v1.x Seguridad de datos: La copia de seguridad en la nube está vacía(0 Mensajes de sesión 0)Negarse a restaurar,
                // evitar"La copia de seguridad vacía sobrescribe los datos locales"Todas las conversaciones se perderán.(Comentarios de los usuarios: todas las conversaciones desaparecieron después de restaurar WebDAV).
                if (!backup.hasAnyData()) {
                    Logger.w("BackupService", "La copia de seguridad en la nube está vacía,Rechazar la restauración para evitar sobrescribir datos locales")
                    return null
                }
                applyBackup(backup)
            }
        }
    }

    /**
     * Phase 8.9: Compruebe si el archivo de copia de seguridad existe en la nube.
     */
    suspend fun hasCloudBackup(): Boolean {
        val config = settings.cloudBackupConfigFlow.first()
        if (!config.isConfigured) return false
        return cloudBackupService.hasBackup(config)
    }

    /**
     * Phase 8.9: Recopilar todos los datos para generar [Backup](Para exportación local y reutilización de carga en la nube).
     * v3: Contiene una instantánea de todas las tablas de datos de usuario de MuseDb + configuraciones de DataStore.
     */
    /** B-23: MuseDb Instantánea completa de la coherencia de la tabla(Lectura única dentro de la transacción,Evite las vistas rotas). */
    private data class DbSnapshot(
        val sessions: List<SessionEntity>,
        val allMessages: List<MessageEntity>,
        val assistants: List<AssistantEntity>,
        val lorebooks: List<LorebookEntity>,
        val skills: List<SkillEntity>,
        val artifacts: List<ArtifactEntity>,
        val quickMessages: List<QuickMessageEntity>,
        val promptInjections: List<PromptInjectionEntity>,
        val folders: List<FolderEntity>,
        val groupChats: List<GroupChatEntity>,
        val groupChatMessages: List<GroupChatMessageEntity>,
        val scheduledTasks: List<ScheduledTaskEntity>,
        val scheduledTaskExecutions: List<ScheduledTaskExecutionEntity>,
        val knowledgeDocs: List<KnowledgeDocEntity>,
        val knowledgeChunks: List<KnowledgeChunkEntity>,
        val experiences: List<ExperienceEntity>,
        val milestones: List<MilestoneEntity>,
        val agentMessages: List<AgentMessageEntity>,
        // P0-10: entidad previamente desaparecida(Recuperar datos perdidos después de cambiar de dispositivo)
        val quickNotes: List<io.zer0.muse.data.quicknote.QuickNoteEntity>,
        val worldBookEntries: List<io.zer0.muse.worldbook.WorldBookEntryEntity>,
        val conversationEvents: List<ConversationEventEntity>,
        val conversationTurns: List<ConversationTurnEntity>,
        val messageParts: List<MessagePartEntity>,
        val messageOutboxes: List<MessageOutboxEntity>,
        // P0-10: Restantes entidades de datos de usuario que faltaban anteriormente(Recuperar datos perdidos después de cambiar de dispositivo)
        val translateHistories: List<TranslateHistoryEntity>,
        val knowledgeBases: List<KnowledgeBaseEntity>,
        val knowledgeFolders: List<io.zer0.muse.data.knowledge.KnowledgeFolderEntity>,
        val subagentThreads: List<SubagentThreadEntity>,
        val toolRounds: List<ToolRoundEntity>,
        val sessionBranchHeads: List<SessionBranchHeadEntity>,
        /** v2.6.7(P1-1): Estado de la relación de roles. */
        val assistantRelationships: List<io.zer0.muse.data.relationship.AssistantRelationshipEntity>,
        /** v2.6.7: Sesión pendiente. */
        val sessionTodos: List<io.zer0.muse.data.todo.SessionTodoEntity>,
        /** v2.6.9: Libro mayor de cuidados/libro mayor de resultados/intimidad en las relaciones. */
        val pendingThoughts: List<io.zer0.muse.data.proactive.PendingThoughtEntity>,
        val proactiveOutcomes: List<io.zer0.muse.data.proactive.ProactiveOutcomeEntity>,
        val proactiveRelations: List<io.zer0.muse.data.proactive.ProactiveRelationEntity>,
        /** v2.6.8: Entidad de tarjeta. */
        val cardEntities: List<CardEntityRecord>,
        val groupChatMemories: List<GroupChatMemoryEntity>,
    )

    /** v4: Leer instantánea de almacenamiento de archivos(desaparecido/Saltar ante el fracaso). */
    private suspend fun readFileStores(): Map<String, String> = withContext(Dispatchers.IO) {
        readBackupFileStores(backupFileStorePaths) { name ->
            val file = File(context.filesDir, name)
            file.takeIf { it.isFile }?.readText()
        }
    }

    /** v4: Restaurar el almacenamiento de archivos(Vuelva a escribir uno por uno en la lista blanca.,Un solo fallo no bloquea el resto). */
    private suspend fun writeFileStores(stores: Map<String, String>) = withContext(Dispatchers.IO) {
        restoreBackupFileStores(
            stores = stores,
            allowedPaths = backupFileStorePaths.toSet(),
        ) { name, content ->
            val target = File(context.filesDir, name)
            target.parentFile?.mkdirs()
            io.zer0.muse.data.AtomicFileStore.writeText(target, content)
        }
    }

    /** v4.1: Leer instantánea del archivo del directorio(note_attachments/ + avatar/),por encima del límite/Saltar y registrar ilegible. */
    private suspend fun readBinaryFiles(): Map<String, String> = withContext(Dispatchers.IO) {
        BackupBinaryFiles.read(context.filesDir).asBase64Map()
    }

    /**
     * v4.1: Restaurar instantáneas de archivos de tipo directorio.
     *
     * El prefijo de la lista blanca y el límite de capacidad están en [BackupBinaryFiles.restore] Suma de comprobación interna;
     * Los errores de escritura se propagan hacia arriba(Libro mayor de recuperación compartido,fail-fast Para activar la reversión del punto de recuperación).
     */
    private suspend fun writeBinaryFiles(files: Map<String, String>) = withContext(Dispatchers.IO) {
        BackupBinaryFiles.restore(files = files, filesDir = context.filesDir)
    }

    private suspend fun buildBackup(includeSecrets: Boolean = false): Backup {
        // B-23: MuseDb Leer transacción del paquete,Evite intercalar con escrituras simultáneas para generar instantáneas rotas(Coherencia del punto de recuperación de reversión).
        // memory/fact Cada biblioteca tiene una pequeña cantidad de lectura y es una sola mesa.,La coherencia entre bases de datos se maneja jerárquicamente mediante la capa superior recoveryIncompleteRestore.
        val snap = db.withTransaction {
            DbSnapshot(
                sessions = db.sessionDao().observeAll().first(),
                allMessages = db.sessionDao().observeAll().first().flatMap { session ->
                    db.messageDao().observeBySession(session.id).first()
                },
                assistants = db.assistantDao().getAll(),
                lorebooks = db.lorebookDao().getAll(),
                skills = db.skillDao().getAll(),
                artifacts = db.artifactDao().getAll(),
                quickMessages = db.quickMessageDao().getAll(),
                promptInjections = db.promptInjectionDao().getAll(),
                folders = db.folderDao().getAll(),
                groupChats = db.groupChatDao().getAll(),
                groupChatMessages = db.groupChatMessageDao().getAll(),
                scheduledTasks = db.scheduledTaskDao().getAll(),
                scheduledTaskExecutions = db.scheduledTaskExecutionDao().getAll(),
                knowledgeDocs = db.knowledgeDocDao().getAll(),
                knowledgeChunks = db.knowledgeChunkDao().getAll(),
                experiences = db.experienceDao().getAll(),
                milestones = db.milestoneDao().getAll(),
                agentMessages = db.agentMessageDao().getAll(),
                // P0-10: entidad previamente desaparecida(Recuperar datos perdidos después de cambiar de dispositivo)
                quickNotes = db.quickNoteDao().getAll(),
                worldBookEntries = db.worldBookDao().getAll(),
                conversationEvents = db.conversationEventDao().getAll(),
                conversationTurns = db.conversationTurnDao().getAll(),
                messageParts = db.messagePartDao().getAll(),
                messageOutboxes = db.messageOutboxDao().getAll(),
                translateHistories = db.translateHistoryDao().getAll(),
                knowledgeBases = db.knowledgeBaseDao().getAll(),
                knowledgeFolders = db.knowledgeFolderDao().getAll(),
                subagentThreads = db.subagentThreadDao().getAll(),
                toolRounds = db.toolRoundDao().getAll(),
                sessionBranchHeads = db.sessionBranchHeadDao().getAll(),
                assistantRelationships = db.assistantRelationshipDao().getAll(),
                sessionTodos = db.sessionTodoDao().getAll(),
                pendingThoughts = db.pendingThoughtDao().getAll(),
                proactiveOutcomes = db.proactiveOutcomeDao().getAll(),
                proactiveRelations = db.proactiveRelationDao().getAll(),
                groupChatMemories = db.groupChatMemoryDao().getAll(),
                // v2.6.8 / v2.6.9: Entidad de tarjeta. Vaya a DAO para obtener una consulta completa (Room mapeo generado,
                // La adición de entidades ya no perderá campos silenciosamente). La exportación se secuencia por orden de atraque.
                cardEntities = db.cardEntityDao().getAll().map { it.toRecord() }.inDockOrder(),
            )
        }
        val sessions = snap.sessions
        val allMessages = snap.allMessages
        // memory datos(4 Zhang Biao)
        val sessionSummaries = memoryDb.sessionSummaryDao().getAll()
        val contextCheckpoints = memoryDb.contextCheckpointDao().getAll()
        val compiledSections = memoryDb.compiledSectionDao().getAll()
        val scopedCompiledSections = memoryDb.scopedCompiledSectionDao().getAll()
        val dailyState = memoryDb.dailyStateDao().getAll()
        val facts = factDb.factDao().getAll()
        val assistants = snap.assistants
        val lorebooks = snap.lorebooks
        val skills = snap.skills
        val artifacts = snap.artifacts
        val quickMessages = snap.quickMessages
        val promptInjections = snap.promptInjections
        val folders = snap.folders
        val groupChats = snap.groupChats
        val groupChatMessages = snap.groupChatMessages
        val scheduledTasks = snap.scheduledTasks
        val scheduledTaskExecutions = snap.scheduledTaskExecutions
        val knowledgeDocs = snap.knowledgeDocs
        val knowledgeChunks = snap.knowledgeChunks
        val experiences = snap.experiences
        val milestones = snap.milestones
        val agentMessages = snap.agentMessages
        // P0-10: entidad previamente desaparecida
        val quickNotes = snap.quickNotes
        val worldBookEntries = snap.worldBookEntries
        val conversationEvents = snap.conversationEvents
        val conversationTurns = snap.conversationTurns
        val messageParts = snap.messageParts
        val messageOutboxes = snap.messageOutboxes
        val translateHistories = snap.translateHistories
        val knowledgeBases = snap.knowledgeBases
        // AUD-20261011-NDJSON-FOLDERS: Lectura instantánea folders,pero antes Backup(...) Fuga de construcción ——
        // La exportación JSON única pierde carpetas,y recovery point(El producto de esta función)Al retroceder se borrarán todas las carpetas.
        val knowledgeFolders = snap.knowledgeFolders
        val subagentThreads = snap.subagentThreads
        val toolRounds = snap.toolRounds
        val sessionBranchHeads = snap.sessionBranchHeads
        val assistantRelationships = snap.assistantRelationships
        // v2.6.7 fix (AUDIT F2): DbSnapshot La tarea pendiente se leyó pero se omitió al crear la copia de seguridad.
        // Se perderá una única exportación JSON; y esta función también es la fuente de instantáneas para la reversión después de un error de recuperación.
        val sessionTodos = snap.sessionTodos
        val groupChatMemories = snap.groupChatMemories
        // v2.6.8: Entidad de la tarjeta (tarjeta base/tarjeta flotante).buildBackup Al mismo tiempo, se revierte si falla la recuperación. recovery point
        // Fuente, la transmisión perdida borrará todas las tarjetas de usuario al revertir.
        val cardEntities = snap.cardEntities
        // P0-10: Biblioteca subasistente(facts_<id>.db)
        val scopedFacts = listScopedFactDbIds().associateWith { id ->
            readScopedFacts(id)
        }.filterValues { it.isNotEmpty() }
        // Instantánea de configuración
        val settingsSnapshot = settings.exportSettingsSnapshot(includeSecrets)
        // v4: Instantánea de almacenamiento de archivos(canal/conector/Comentario/Bandeja de entrada/complemento)
        val fileStores = readFileStores()
        // v4.1: Instantánea del archivo del directorio(Nota adjunta/avatar)
        val binaryFiles = readBinaryFiles()

        return Backup(
            version = 4,
            exportedAt = System.currentTimeMillis(),
            sessions = sessions,
            messages = allMessages,
            sessionSummaries = sessionSummaries,
            contextCheckpoints = contextCheckpoints,
            dailyStates = dailyState,
            compiledSections = compiledSections,
            scopedCompiledSections = scopedCompiledSections,
            facts = facts,
            assistants = assistants,
            lorebooks = lorebooks,
            skills = skills,
            artifacts = artifacts,
            quickMessages = quickMessages,
            promptInjections = promptInjections,
            folders = folders,
            groupChats = groupChats,
            groupChatMessages = groupChatMessages,
            scheduledTasks = scheduledTasks,
            scheduledTaskExecutions = scheduledTaskExecutions,
            knowledgeDocs = knowledgeDocs,
            knowledgeChunks = knowledgeChunks,
            experiences = experiences,
            milestones = milestones,
            agentMessages = agentMessages,
            quickNotes = quickNotes,
            worldBookEntries = worldBookEntries,
            conversationEvents = conversationEvents,
            conversationTurns = conversationTurns,
            messageParts = messageParts,
            messageOutboxes = messageOutboxes,
            scopedFacts = scopedFacts,
            translateHistories = translateHistories,
            knowledgeBases = knowledgeBases,
            knowledgeFolders = knowledgeFolders,
            subagentThreads = subagentThreads,
            toolRounds = toolRounds,
            sessionBranchHeads = sessionBranchHeads,
            assistantRelationships = assistantRelationships,
            sessionTodos = sessionTodos,
            groupChatMemories = groupChatMemories,
            cardEntities = cardEntities,
            settingsSnapshot = settingsSnapshot,
            fileStores = fileStores,
            binaryFiles = binaryFiles,
        )
    }

    /**
     * poner [Backup] Aplicar a todos DB(Para importación local y reutilización de recuperación en la nube).
     * v3: Restaure todas las tablas de datos de usuario de MuseDb + instantánea de configuración de DataStore.
     *
     * pregunta7.1: El fracaso de una transacción entre tres bases de datos independientes resultará en la pérdida completa de datos.
     * transformado en"Instantánea previa a la importación + reversión en caso de error":primero buildBackup() Tome una instantánea de los datos actuales,
     * Cuando cualquier escritura de base de datos genera una excepción, use la instantánea para aplicarla nuevamente.,Haga todo lo posible para restaurar el estado anterior a la importación.
     * pregunta7.2: La entrada se llama primero. [migrateBackup] Hacer migración de versión(v1/v2 → v3).
     * pregunta7.3: Filtre las claves relacionadas con el dispositivo al restaurar la configuración Instantánea.
     *
     * @return Conversaciones importadas + mensajes
     */
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private suspend fun applyBackup(backup: Backup): Pair<Int, Int> {
        require(backup.hasAnyData()) { "Archivo de copia de seguridad vacío,Importación rechazada" }
        // pregunta7.2: Migración de versión(v1/v2 → v3),Backup data class Los campos tienen valores predeterminados,Solo agrega la versión
        val migrated = migrateBackup(backup)
        val journalEntry = restoreJournal.begin(
            restoreId = "restore-${UUID.randomUUID()}",
            sourceHash = backupHash(migrated),
            backupVersion = migrated.version,
        )
        var currentJournal = journalEntry
        return try {
            currentJournal = restoreJournal.advance(currentJournal, RestoreJournal.Phase.STAGING)
            // Primero persista el objetivo y el punto de recuperación antes de importar; de esta manera, incluso si el proceso finaliza durante el envío entre bases de datos, aún se puede revertir.
            val preImportSnapshot = buildBackup()
            restoreStagingStore.writeTarget(currentJournal, migrated)
            restoreStagingStore.writeRecoveryPoint(currentJournal, preImportSnapshot)
            currentJournal = restoreJournal.advance(currentJournal, RestoreJournal.Phase.VALIDATING)
            currentJournal = restoreJournal.advance(currentJournal, RestoreJournal.Phase.COMMITTING)
            val imageFilesBeforeRestore = messageImageStore.snapshotStoredFilePaths()
            val result = applyBackupInternal(migrated)
            currentJournal = restoreJournal.advance(
                currentJournal,
                RestoreJournal.Phase.REBUILDING,
                completedStores = setOf(
                    RestoreJournal.Store.MUSE_DB,
                    RestoreJournal.Store.MEMORY_DB,
                    RestoreJournal.Store.FACT_DB,
                    RestoreJournal.Store.SETTINGS,
                ),
            )
            try {
                sessionRepository.rebuildFtsIndex()
            } catch (error: Exception) {
                Logger.w("BackupService", "Después de la recuperación, la reconstrucción del FTS falla y se retiene el libro de recuperación.: ${error.message}", error)
                throw error
            }
            invalidateRagAfterRestore()
            currentJournal = restoreJournal.advance(
                currentJournal,
                RestoreJournal.Phase.REBUILDING,
                completedStores = currentJournal.completedStores + RestoreJournal.Store.FTS,
            )
            restoreJournal.complete(currentJournal)
            restoreStagingStore.cleanup(currentJournal)
            pruneRestoredMessageImages(imageFilesBeforeRestore)
            result
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            val rolledBack = rollbackAfterRestoreFailure(currentJournal, e)
            if (!rolledBack) restoreJournal.fail(currentJournal, e)
            throw e
        } catch (e: Exception) {
            val rolledBack = rollbackAfterRestoreFailure(currentJournal, e)
            if (!rolledBack) restoreJournal.fail(currentJournal, e)
            throw e
        }
    }

    /**
     * Realice de manera uniforme una reversión persistente del punto de recuperación en caso de fallas de recuperación.
     *
     * Esta ruta cubre el error de escritura de la base de datos de destino y el error de reconstrucción de FTS; la reversión en sí usa NonCancellable,
     * Evite que las detenciones de usuarios o las cancelaciones en proceso dejen los puntos de recuperación en un estado semicomprometido nuevamente. Mantener el libro mayor cuando falla la reversión
     * con una copia para el próximo inicio para continuar con el diagnóstico/tratamiento.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun rollbackAfterRestoreFailure(entry: RestoreJournal.Entry, originalError: Throwable): Boolean =
        withContext(kotlinx.coroutines.NonCancellable) {
            val recovery = restoreStagingStore.readRecoveryPoint(entry)
            if (recovery == null) {
                Logger.e("BackupService", "La recuperación falló y no se encontró persistencia recovery point: ${originalError.message}", originalError)
                return@withContext false
            }
            try {
                Logger.w("BackupService", "La recuperación falló,Revertir a la persistencia recovery point: ${originalError.message}", originalError)
                val imageFilesBeforeRollback = messageImageStore.snapshotStoredFilePaths()
                var rollbackJournal = restoreJournal.advance(entry, RestoreJournal.Phase.ROLLING_BACK)
                applyBackupInternal(recovery)
                sessionRepository.rebuildFtsIndex()
                invalidateRagAfterRestore()
                rollbackJournal = restoreJournal.advance(
                    rollbackJournal,
                    RestoreJournal.Phase.ROLLING_BACK,
                    completedStores = setOf(
                        RestoreJournal.Store.MUSE_DB,
                        RestoreJournal.Store.MEMORY_DB,
                        RestoreJournal.Store.FACT_DB,
                        RestoreJournal.Store.SETTINGS,
                        RestoreJournal.Store.FTS,
                    ),
                )
                restoreJournal.complete(rollbackJournal)
                restoreStagingStore.cleanup(rollbackJournal)
                pruneRestoredMessageImages(imageFilesBeforeRollback)
                true
            } catch (rollbackError: Exception) {
                Logger.e("BackupService", "Falló la reversión del punto de recuperación,Mantenga libros de contabilidad y copias: ${rollbackError.message}", rollbackError)
                false
            }
        }

    /**
     * Reanuda la última importación entre almacenamiento que finalizó el proceso al inicio.
     *
     * El protocolo actual prioriza la seguridad: cualquier recuperación incompleta se revierte al punto de recuperación persistente.
     * En lugar de continuar cometiendo un objetivo que puede haber sido escrito parcialmente. De esta manera, la mitad de un conjunto de datos no se confundirá con un nuevo estado.
     * @return Si se ha descubierto y procesado un libro de recuperación inacabado
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun recoverIncompleteRestore(gateAlreadyHeld: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        suspend fun restoreBody(): Boolean {
            val entry = restoreJournal.incompleteEntry() ?: return false
            var current = entry
            return try {
                val imageFilesBeforeRollback = messageImageStore.snapshotStoredFilePaths()
                current = restoreJournal.advance(current, RestoreJournal.Phase.ROLLING_BACK)
                if (entry.recoveryFormat == RestoreJournal.RecoveryFormat.NDJSON) {
                    restoreStagingStore.readRecoveryNdJson(entry) { recoveryLines ->
                        applyNdJsonStreaming(recoveryLines, allowEmptyBackup = true)
                    }
                } else {
                    val recovery = restoreStagingStore.readRecoveryPoint(entry)
                        ?: error("El libro de recuperación existe, pero no se puede encontrar la persistencia recovery point")
                    applyBackupInternal(recovery)
                    sessionRepository.rebuildFtsIndex()
                    invalidateRagAfterRestore()
                }
                current = restoreJournal.advance(
                    current,
                    RestoreJournal.Phase.REBUILDING,
                    completedStores = setOf(
                        RestoreJournal.Store.MUSE_DB,
                        RestoreJournal.Store.MEMORY_DB,
                        RestoreJournal.Store.FACT_DB,
                        RestoreJournal.Store.SETTINGS,
                        RestoreJournal.Store.FTS,
                    ),
                )
                restoreJournal.complete(current)
                restoreStagingStore.cleanup(current)
                pruneRestoredMessageImages(imageFilesBeforeRollback)
                Logger.w("BackupService", "Iniciar recuperación Comprobar revertir Recuperación inacabada: phase=${entry.phase}")
                true
            } catch (error: Exception) {
                restoreJournal.fail(current, error)
                Logger.e("BackupService", "La verificación de recuperación de inicio falló, se retuvo la copia de recuperación para el diagnóstico: ${error.message}", error)
                false
            }
        }
        if (gateAlreadyHeld) {
            require(io.zer0.common.ProcessWriteGate.restoring) { "Puerta de recuperación no retenida" }
            restoreBody()
        } else {
            withBackupImportWriteGate { restoreBody() }
        }
    }

    /**
     * Descarte un punto de recuperación que no se pueda revertir.
     *
     * Cuando la autocomprobación de inicio encuentre que la recuperación no se ha completado, intentará retroceder; si la reversión falla (falta la copia, la base de datos es inconsistente, etc.),
     * El libro mayor se detendrá en FALLADO.`incompleteEntry()` FAILED no está excluido, por lo que el siguiente arranque continuará
     * Lea el mismo punto negativo y vuelva a fallar. —— Los usuarios deben pasar por una ronda de recuperación de fallas cada vez que encienden la computadora.
     *
     * El libro mayor se cierra aquí (elimine después de avanzar a COMPLETADO) y el archivo de copia de recuperación se conserva en un directorio privado para diagnóstico.
     */
    suspend fun abandonIncompleteRestore(cause: Throwable): Boolean = withContext(Dispatchers.IO) {
        val entry = restoreJournal.incompleteEntry() ?: return@withContext false
        resultOf {
            restoreJournal.advance(
                entry,
                RestoreJournal.Phase.FAILED,
                failureReason = "No se pudo iniciar la reversión y se rindió: ${cause.message ?: cause::class.java.simpleName}",
            )
        }.onError { msg, t -> Logger.w("BackupService", "Error al registrar el motivo del abandono: ${t?.message ?: msg}", t) }

        // complete() Empujará la fase a COMPLETADO y eliminará el libro mayor para que la autoprueba de inicio ya no se realice.
        restoreJournal.complete(restoreJournal.read() ?: entry)
        Logger.w("BackupService", "Recuperación inacabada abandonada restoreId=${entry.restoreId}, se conserva una copia para el diagnóstico")
        true
    }

    /** Calcular el resumen de bytes; La verificación en la nube sirve para cargar texto cifrado para evitar filtrar o confundir contenido de texto sin formato. */
    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    /** Guarde solo un resumen del contenido de la copia de seguridad para evitar restaurar el libro mayor que contiene datos del usuario o configuraciones confidenciales. */
    private fun backupHash(backup: Backup): String {
        val encoded = json.encodeToString(Backup.serializer(), backup)
        return MessageDigest.getInstance("SHA-256")
            .digest(encoded.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    /**
     * pregunta7.2: Gancho de migración de la versión de respaldo.
     *
     * - v4: Versión actual,No es necesario migrar
     * - v3: Almacenamiento sin archivos,fileStores Tome el valor vacío predeterminado
     * - v1/v2: Las copias de seguridad más antiguas sólo contienen sessions + messages + memory datos(La mesa de extensión está vacía),
     *   Backup data class Los nuevos campos tienen valores predeterminados,suplemento version=4 Eso es todo
     * - Versión desconocida: Advertir y manejar como v4
     */
    private fun migrateBackup(backup: Backup): Backup = when (backup.version) {
        4, 3 -> backup
        1, 2 -> {
            Logger.i("BackupService", "Migrar copia de seguridad v${backup.version} → v4:Complementar los campos de tabla extendida predeterminados")
            backup.copy(version = 4)
        }
        else -> {
            Logger.w("BackupService", "Versión de copia de seguridad desconocida v${backup.version},Procesado según la versión actual v4.")
            backup.copy(version = 4)
        }
    }

    /**
     * Ejecución real de lógica de borrado + inserción.(suministro [applyBackup] Reutilizar con reversión,no más retroceso).
     *
     * Nota:Este método abre transacciones en tres bases de datos independientes.,No puede ser atómico;llamador([applyBackup])Responsable de la reversión.
     */
    private suspend fun applyBackupInternal(backup: Backup): Pair<Int, Int> {
        // 1. Importar la tabla principal de MuseDb(sessions + messages)
        db.withTransaction {
            clearConversationStateForRestore(db)
            // v2.x Importar calentamiento:La sesión restaurada construirá el contexto una vez con la historia completa en la primera ronda.
            // El viejo y pequeño teléfono celular ha estado desconectado.:La sesión restante es MiniPhone y sus mensajes en la copia de seguridad se pierden antes de escribirse.,
            // No escriba la biblioteca primero y luego la elimine(De lo contrario aparecerán brevemente en la lista principal como una sesión normal.).
            val miniPhoneSessionIds = backup.sessions.filter { it.isMiniPhone }.map { it.id }.toSet()
            if (miniPhoneSessionIds.isNotEmpty()) {
                Logger.w(
                    "BackupService",
                    "saltar ${miniPhoneSessionIds.size} Una vieja pequeña conversación por teléfono celular y su" +
                        " ${backup.messages.count { it.sessionId in miniPhoneSessionIds }} mensajes" +
                        "(Esta función ha estado fuera de línea y ya no se restaurará como una sesión normal)",
                )
            }
            backup.sessions.forEach {
                if (!it.isMiniPhone) db.sessionDao().insert(it.copy(warmupPending = true))
            }
            backup.messages.forEach {
                if (it.sessionId !in miniPhoneSessionIds) db.messageDao().upsert(it)
            }

            // v3: mesa extendida(Borrar + insertar en la misma transacción)
            db.assistantDao().deleteAll()
            db.lorebookDao().deleteAll()
            db.skillDao().deleteAll()
            db.artifactDao().deleteAll()
            db.quickMessageDao().deleteAll()
            db.promptInjectionDao().deleteAll()
            db.folderDao().deleteAll()
            db.groupChatMessageDao().deleteAll()
            db.groupChatDao().deleteAll()
            db.scheduledTaskExecutionDao().deleteAll()
            db.scheduledTaskDao().deleteAll()
            db.knowledgeChunkDao().deleteAll()
            db.knowledgeDocDao().deleteAll()
            db.experienceDao().deleteAll()
            db.milestoneDao().deleteAll()
            db.agentMessageDao().deleteAll()
            // P0-10: entidad previamente desaparecida
            db.quickNoteDao().deleteAll()
            db.worldBookDao().deleteAll()
            db.conversationEventDao().deleteAll()
            db.conversationTurnDao().deleteAll()
            db.messagePartDao().deleteAll()
            db.messageOutboxDao().deleteAll()
            db.translateHistoryDao().deleteAll()
            db.knowledgeBaseDao().deleteAll()
            db.knowledgeFolderDao().deleteAll()
            db.sessionTodoDao().deleteAll()
            db.pendingThoughtWriteDao().deleteAll()
            db.proactiveOutcomeDao().deleteAll()
            db.proactiveRelationDao().deleteAll()
            db.assistantRelationshipDao().deleteAll()
            db.subagentThreadDao().deleteAll()
            db.toolRoundDao().deleteAll()
            db.sessionBranchHeadDao().deleteAll()
            db.groupChatMemoryDao().deleteAll()
            // v2.6.8: Entidad de tarjeta.DAO Eliminar todo suplementado (v2.6.9), del mismo calibre que otras mesas,
            // Dentro de la transacción MuseDb, el error sigue a una reversión general.
            db.cardEntityDao().deleteAll()

            backup.assistants.forEach { db.assistantDao().upsert(it) }
            backup.lorebooks.forEach { db.lorebookDao().upsert(it) }
            backup.skills.forEach { db.skillDao().upsert(it) }
            backup.artifacts.forEach { db.artifactDao().upsert(it) }
            backup.quickMessages.forEach { db.quickMessageDao().upsert(it) }
            backup.promptInjections.forEach { db.promptInjectionDao().upsert(it) }
            backup.folders.forEach { db.folderDao().insert(it) }
            backup.groupChats.forEach { db.groupChatDao().upsert(it) }
            backup.groupChatMessages.forEach { db.groupChatMessageDao().upsert(it) }
            backup.scheduledTasks.forEach { db.scheduledTaskDao().upsert(it) }
            backup.scheduledTaskExecutions.forEach { db.scheduledTaskExecutionDao().insert(it) }
            backup.knowledgeDocs.forEach { db.knowledgeDocDao().upsert(it) }
            if (backup.knowledgeChunks.isNotEmpty()) db.knowledgeChunkDao().insertAll(backup.knowledgeChunks)
            backup.experiences.forEach { db.experienceDao().upsert(it) }
            backup.milestones.forEach { db.milestoneDao().upsert(it) }
            backup.agentMessages.forEach { db.agentMessageDao().upsert(it) }
            // P0-10: entidad previamente desaparecida
            backup.quickNotes.forEach { db.quickNoteDao().upsert(it) }
            backup.worldBookEntries.forEach { db.worldBookDao().upsert(it) }
            if (backup.conversationEvents.isNotEmpty()) {
                val insertedEventIds = db.conversationEventDao().insertAll(backup.conversationEvents)
                check(insertedEventIds.all { it != -1L }) {
                    "La copia de seguridad de conversacion_eventos tiene un conflicto entre eventId y clave principal, rechaza la pérdida silenciosa de eventos"
                }
            }
            backup.conversationTurns.forEach { db.conversationTurnDao().upsert(it) }
            if (backup.messageParts.isNotEmpty()) db.messagePartDao().upsertAll(backup.messageParts)
            backup.messageOutboxes.forEach { db.messageOutboxDao().upsert(it) }
            backup.translateHistories.forEach { db.translateHistoryDao().insert(it) }
            backup.knowledgeBases.forEach { db.knowledgeBaseDao().upsert(it) }
            backup.knowledgeFolders.forEach { db.knowledgeFolderDao().upsert(it) }
            // v2.6.7: Restauración de tareas pendientes con copia de seguridad —— Exportar sin restaurar equivale a "hacer una copia de seguridad pero es inútil".
            backup.sessionTodos.forEach { db.sessionTodoDao().upsert(it) }
            // v2.6.9: Libro mayor de preocupaciones/libro mayor de resultados/relación
            backup.pendingThoughts.forEach { db.pendingThoughtDao().upsert(it) }
            backup.proactiveOutcomes.forEach { db.proactiveOutcomeDao().upsert(it) }
            backup.proactiveRelations.forEach { db.proactiveRelationDao().upsert(it) }
            // v2.6.7: La relación entre personajes anteriormente solo se exportaba y no se restauraba (había una brecha existente), y el nivel de intimidad después de cambiar de dispositivo./Todas las etiquetas de relación se pierden.
            backup.assistantRelationships.forEach { db.assistantRelationshipDao().upsert(it) }
            backup.subagentThreads.forEach { db.subagentThreadDao().upsert(it) }
            backup.toolRounds.forEach { db.toolRoundDao().upsertAll(listOf(it)) }
            backup.sessionBranchHeads.forEach { db.sessionBranchHeadDao().upsert(it) }
            backup.groupChatMemories.forEach { db.groupChatMemoryDao().insert(it) }
            // v2.6.8: Las tarjetas se escriben una por una en el orden en que fueron exportadas. La ranura de acoplamiento tiene un índice único. Los duplicados se verifican primero y luego se escriben.
            // Que se informen datos incorrectos con un motivo legible en lugar de "UNIQUE constraint failed".
            // v2.6.9: Borre + escriba el puerto de recuperación en CardEntityBackup.kt y use DAO para toda la lectura y escritura.
            val restoredCardCount = db.replaceCardEntities(backup.cardEntities)
            // v2.6.8: Conciliación de una única ruta JSON (NDJSON ruta para verificarInsertedCounts).
            // Si el número de filas es insuficiente, se generará un error. Al igual que otras entidades, la falla rápida desencadenará una reversión del punto de recuperación.
            verifyCardEntitiesRestored(db, restoredCardCount)
        }

        // 2. Importar datos de la memoria(MemoryDb — 5 Zhang Biao)
        memoryDb.withTransaction {
            memoryDb.sessionSummaryDao().deleteAll()
            memoryDb.contextCheckpointDao().deleteAll()
            memoryDb.dailyStateDao().deleteAll()
            memoryDb.compiledSectionDao().deleteAll()
            memoryDb.scopedCompiledSectionDao().deleteAll()
            backup.sessionSummaries.forEach { memoryDb.sessionSummaryDao().upsert(it) }
            backup.contextCheckpoints.forEach { memoryDb.contextCheckpointDao().upsert(it) }
            backup.dailyStates.forEach { memoryDb.dailyStateDao().upsert(it) }
            backup.compiledSections.forEach { memoryDb.compiledSectionDao().upsert(it) }
            backup.scopedCompiledSections.forEach { memoryDb.scopedCompiledSectionDao().upsert(it) }
        }

        // 3. importar facts (FactDb)
        factDb.withTransaction {
            factDb.factDao().deleteAll()
            if (backup.facts.isNotEmpty()) {
                val reset = backup.facts.map { it.copy(id = 0) }
                factDb.factDao().insertAll(reset)
            }
        }

        // 3.5 P0-10: Restaurar la biblioteca del subasistente(facts_<id>.db)— Borrar + escribir base de datos por base de datos
        backup.scopedFacts.forEach { (scope, facts) ->
            writeScopedFacts(scope, facts)
        }

        // 4. Restaurar una instantánea de la configuración de DataStore
        // pregunta7.3: Filtrar dispositivos relacionados key(theme_mode Sigue el sistema,dynamic_color Depende del dispositivo Material You et al.),
        // Evite anular las preferencias locales del dispositivo de destino.bool:/int:/long: el prefijo también coincide,Como por ejemplo "bool:dynamic_color".
        if (backup.settingsSnapshot.isNotEmpty()) {
            val filtered = backup.settingsSnapshot.filterKeys { it !in DEVICE_SPECIFIC_KEYS }
            if (filtered.isNotEmpty()) {
                settings.restoreSettingsSnapshot(filtered, includeSecrets = filtered.containsKey("providers_json"))
            }
        }

        // 5. v4: Restaurar el almacenamiento de archivos(canal/conector/Comentario/Bandeja de entrada/complemento)
        if (backup.fileStores.isNotEmpty()) writeFileStores(backup.fileStores)

        // 5.5 v4.1: Recuperar archivos del catálogo(Nota adjunta/avatar). Los errores de escritura se propagan hacia arriba ——
        // El almacenamiento basado en archivos comparte el libro de recuperación con tres bibliotecas de Room,La falla puede desencadenar la reversión del punto de recuperación.
        if (backup.binaryFiles.isNotEmpty()) writeBinaryFiles(backup.binaryFiles)

        return backup.sessions.size to backup.messages.size
    }

    private suspend fun pruneRestoredMessageImages(snapshot: Set<String>) {
        pruneMessageImageSnapshot(messageImageStore, snapshot) { readReferencedMessageImagePaths() }
    }

    private suspend fun readReferencedMessageImagePaths(): Set<String>? {
        val referenced = mutableSetOf<String>()
        val encodedPaths = resultOf { db.messageDao().getMessagesWithPersistedImagePaths() }
            .onError { message, error -> Logger.w("BackupService", "Error en la consulta de la ruta de la imagen del mensaje, se omitió la limpieza del sidecar: $message", error) }
            .getOrNull()
        if (encodedPaths == null) return null
        var failedToParse = false
        for (encoded in encodedPaths) {
            val paths = resultOf {
                json.decodeFromString(ListSerializer(String.serializer()), encoded)
            }
                .onError { message, error -> Logger.w("BackupService", "No se pudo leer la ruta de la imagen del mensaje, omitiendo la limpieza del sidecar: $message", error) }
                .getOrNull()
            if (paths == null) {
                failedToParse = true
            } else {
                referenced.addAll(paths)
            }
        }
        return if (failedToParse) null else referenced
    }

    /**
     * v2.6.0 (AUD-DATA-B34): Conciliación después del almacenamiento. —— comprobar“El número real de líneas incluidas en el inventario.”En lugar de simplemente analizar el número de líneas.
     *
     * B-34 La implementación original solo compara la cantidad de metadeclaraciones y la cantidad de análisis antes de que se confirme la transacción; el análisis pasa pero el buffer se vacía
     * upsert Si los registros se pierden debido a conflictos de restricciones, etc., la conciliación no se puede ver y el resultado es“Importación exitosa pero faltan datos parcialmente”.
     *
     * Aquí revisamos nuevamente la tabla principal que puede obtener directamente el monto total: número de declaración > El número real de almacenes se considera un fracaso de importación.
     * (La transacción aún no se ha confirmado y, al realizarla, se revertirá toda la transacción de MuseDb). No recuperar los datos no bloquea la importación.
     * Sólo mantén un registro——La conciliación es una ganancia y las copias de seguridad legítimas no se pueden restaurar debido a excepciones en la interfaz de estadísticas.
     */
    private suspend fun verifyInsertedCounts(
        expectedCounts: Map<String, Int>,
        skippedSessionCount: Int = 0,
    ) {
        expectedCounts.forEach { (key, expected) ->
            // Se omiten pequeñas sesiones telefónicas antiguas antes de escribir:Deben deducirse de la base de conciliación.,De lo contrario será"Saltar según las reglas"Mal juzgado como"perder línea"
            val base = if (key == "sessions") expected - skippedSessionCount else expected
            if (base <= 0) return@forEach
            val actual = resultOf {
                when (key) {
                    "sessions" -> db.sessionDao().observeAll().first().size
                    "assistants" -> db.assistantDao().getAll().size
                    "lorebooks" -> db.lorebookDao().getAll().size
                    "skills" -> db.skillDao().getAll().size
                    // Debe estar relacionado con el lado de exportación (getAll())Mismo calibre:observeAllUser() Se excluirán los documentos incorporados.
                    // Todos los documentos de conocimiento del equipo recién instalado están integrados y al usarlos obtendrán 0, lo que provocará que la copia de seguridad autoexportada no se pueda volver a importar.
                    "knowledgeDocs" -> db.knowledgeDocDao().getAll().size
                    "folders" -> db.folderDao().getAll().size
                    "cardEntities" -> db.cardEntityDao().count()
                    else -> return@forEach
                }
            }.onError { msg, t ->
                Logger.w("BackupService", "No se pudo recuperar la conciliación de almacenamiento($key): $msg", t)
            }.getOrNull() ?: return@forEach
            check(actual >= base) {
                "La cantidad de copias de seguridad almacenadas en la base de datos es inconsistente: $key Declaración $base, Almacenamiento real $actual"
            }
        }
    }
}

/** NDJSON Exportar type → meta Tecla de conteo(Exportación, generación e importación./La verificación de relectura comparte el mismo mapeo). */
private val ndjsonTypeToMetaKey: Map<String, String> = mapOf(
    "session" to "sessions",
    "message" to "messages",
    "summary" to "sessionSummaries",
    "contextCheckpoint" to "contextCheckpoints",
    "dailyState" to "dailyStates",
    "compiledSection" to "compiledSections",
    "scopedCompiledSection" to "scopedCompiledSections",
    "fact" to "facts",
    "fileStore" to "fileStores",
    "binaryFile" to "binaryFiles",
    "assistant" to "assistants",
    "lorebook" to "lorebooks",
    "skill" to "skills",
    "artifact" to "artifacts",
    "quickMessage" to "quickMessages",
    "promptInjection" to "promptInjections",
    "folder" to "folders",
    "groupChat" to "groupChats",
    "groupChatMessage" to "groupChatMessages",
    "scheduledTask" to "scheduledTasks",
    "scheduledTaskExecution" to "scheduledTaskExecutions",
    "knowledgeDoc" to "knowledgeDocs",
    "knowledgeChunk" to "knowledgeChunks",
    "experience" to "experiences",
    "milestone" to "milestones",
    "agentMessage" to "agentMessages",
    // P0-10: entidad previamente desaparecida
    "quickNote" to "quickNotes",
    "worldBookEntry" to "worldBookEntries",
    "conversationEvent" to "conversationEvents",
    "conversationTurn" to "conversationTurns",
    "messagePart" to "messageParts",
    "messageOutbox" to "messageOutboxes",
    "scopedFact" to "scopedFacts",
    "translateHistory" to "translateHistories",
    "knowledgeBase" to "knowledgeBases",
    "knowledgeFolder" to "knowledgeFolders",
    "subagentThread" to "subagentThreads",
    "toolRound" to "toolRounds",
    "sessionBranchHead" to "sessionBranchHeads",
    "sessionTodo" to "sessionTodos",
    "pendingThought" to "pendingThoughts",
    "proactiveOutcome" to "proactiveOutcomes",
    "proactiveRelation" to "proactiveRelations",
    "assistantRelationship" to "assistantRelationships",
    "cardEntity" to "cardEntities",
    "groupChatMemory" to "groupChatMemories",
    "settings" to "settings",
)

/** Phase 3 (P1): Analizador JSON relajado para validación de lectura(Ignorar campos desconocidos,Igual que el lado de exportación.). */
private val readBackJson = Json { ignoreUnknownKeys = true }

/**
 * Phase 3 (Fiabilidad P1): Verificación de lectura de respaldo en la nube — Estructura NDJSON descifrada/meta Controlar.
 *
 * F-04 La verificación de relectura anteriormente solo comparaba la longitud del texto cifrado cargado con SHA-256(capa de bytes). Copias de seguridad cifradas en rotación de contraseñas/
 * Los bytes aún pueden ser consistentes al encapsular errores,Sin embargo, el contenido descifrado no se puede analizar. Agregue verificación de capa estructural aquí:
 *  1. Las cargas útiles cifradas deben poder descifrarse con la contraseña de respaldo configurada actualmente;
 *  2. La primera línea que no esté en blanco debe ser una metalínea legal.(type=meta,Los recuentos declarados son todos números enteros no negativos.);
 *  3. meta El número total de elementos de datos declarados debe ser > 0(Copia de seguridad vacía rechazada);
 *  4. meta Habrá al menos una fila de datos no vacía después(Número de líneas > 0).
 *
 * Verificación de solo lectura:No claro/Escribir en cualquier mesa(diferente de [BackupService] Ruta de importación interna).
 *
 * @return null significa pasar;No nulo es el motivo del error(Escriba en auto_backup_log para visualización de diagnóstico)
 */
internal fun validateCloudReadBackNdjson(payload: ByteArray, password: String): String? {
    val plaintext = if (BackupCrypto.isEncrypted(payload)) {
        if (password.isEmpty()) {
            return "encrypted read-back but backup password is empty"
        }
        runCatching { BackupCrypto.decrypt(payload, password) }
            .getOrElse { return "decrypt failed: ${it.message ?: it.javaClass.simpleName}" }
    } else {
        payload
    }
    if (plaintext.isEmpty()) return "read-back payload is empty"
    plaintext.inputStream().bufferedReader(Charsets.UTF_8).use { reader ->
        val firstLine = generateSequence { reader.readLine() }
            .firstOrNull { !it.isNullOrBlank() }
            ?: return "read-back has no non-blank lines"
        val meta = runCatching {
            readBackJson.parseToJsonElement(firstLine) as? JsonObject
        }.getOrNull() ?: return "read-back first line is not a JSON object"
        val type = (meta["type"] as? JsonPrimitive)?.contentOrNull
        if (type != "meta") return "read-back first line type is '$type', expected 'meta'"

        var declaredTotal = 0L
        for (key in ndjsonTypeToMetaKey.values.distinct()) {
            val value = meta[key] ?: continue
            val count = (value as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                ?: return "read-back meta count '$key' is not an integer"
            if (count < 0) return "read-back meta count '$key' is negative: $count"
            declaredTotal += count
        }
        if (declaredTotal <= 0) return "read-back meta declares no data (all counts are 0)"

        val hasDataLine = generateSequence { reader.readLine() }.any { it.isNotBlank() }
        if (!hasDataLine) return "read-back has no record lines after meta"
        return null
    }
}

internal fun validateCloudReadBackNdjsonWithPolicy(payload: ByteArray, password: String, passwordWasSet: Boolean): String? {
    return if (shouldRejectPlaintextCloudBackup(payload, passwordWasSet, password)) {
        "plaintext read-back while backup encryption is configured"
    } else {
        validateCloudReadBackNdjson(payload, password)
    }
}

internal fun shouldRejectPlaintextCloudBackup(payload: ByteArray, passwordWasSet: Boolean, password: String): Boolean {
    return !BackupCrypto.isEncrypted(payload) && (passwordWasSet || password.isNotBlank())
}
