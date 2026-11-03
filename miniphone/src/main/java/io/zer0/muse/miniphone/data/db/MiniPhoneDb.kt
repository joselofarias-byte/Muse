package io.zer0.muse.miniphone.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.zer0.muse.miniphone.data.db.dao.MpAlbumDao
import io.zer0.muse.miniphone.data.db.dao.MpDiaryDao
import io.zer0.muse.miniphone.data.db.dao.MpMessageDao
import io.zer0.muse.miniphone.data.db.dao.MpMomentCommentDao
import io.zer0.muse.miniphone.data.db.dao.MpMomentDao
import io.zer0.muse.miniphone.data.db.dao.MpMomentLikeDao
import io.zer0.muse.miniphone.data.db.dao.MpProfileDao
import io.zer0.muse.miniphone.data.db.dao.MpThreadDao
import io.zer0.muse.miniphone.data.db.dao.MpThreadMemberDao
import io.zer0.muse.miniphone.data.db.entity.MpAlbumEntity
import io.zer0.muse.miniphone.data.db.entity.MpDiaryEntity
import io.zer0.muse.miniphone.data.db.entity.MpMessageEntity
import io.zer0.muse.miniphone.data.db.entity.MpMomentCommentEntity
import io.zer0.muse.miniphone.data.db.entity.MpMomentEntity
import io.zer0.muse.miniphone.data.db.entity.MpMomentLikeEntity
import io.zer0.muse.miniphone.data.db.entity.MpProfileEntity
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.data.db.entity.MpThreadMemberEntity

/**
 * Versión pequeña de base de datos celular. Este módulo viene con su propia biblioteca.,Independiente de la base de datos del host,El número de versión se incrementa de forma independiente.
 *
 * 2:mp_message Agregar columna de estado de envío(status).
 * 3:mp_thread El valor predeterminado de la capa SQL del parche anclado(0),Y hacer converger el valor de las acciones a 0/1.
 * 4:mp_thread Agregar etiqueta de chat grupal(isGroup)con nombre de grupo(title),Agregar una nueva lista de miembros del grupo(mp_thread_member).
 * 5:mp_thread aumentar titleCustomized,Distinga entre "nombre de grupo cambiado por el usuario" y "nombre de grupo escrito automáticamente".
 */
internal const val MINI_PHONE_DB_VERSION: Int = 5

/**
 * 1 → 2:Agregue el estado de envío a la tabla de mensajes privados.
 *
 * La fila del historial no tiene estado de envío,En ese momento no existía el "Envío"/"fracaso",Entonces el valor predeterminado es 0(Enviado),
 * Después de la actualización, la apariencia de la sesión anterior es la misma que antes de la actualización. Ya hay datos en la tabla y la tabla no se puede reconstruir.,Solo puedes agregar columnas en el momento.
 */
internal val MINI_PHONE_MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE mp_message ADD COLUMN status INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * 2 → 3:Escriba el valor predeterminado de la etiqueta adhesiva en SQL.
 *
 * La columna superior ha estado en la tabla desde la versión 1.(La entidad siempre toma `pinned`),Entonces no se puede hacer aquí. ADD COLUMN —— Sumar y restar columnas de la base de datos anterior
 * golpeará `duplicate column name`. Lo único que falta es "enumerar los valores predeterminados":Sin PREDETERMINADO,cualquiera
 * No escribir fijado INSERT(SQL manual, rutas de código heredadas)Golpeará NOT NULL.SQLite No se puede cambiar el valor predeterminado vigente,
 * Entonces reconstruya esta tabla con solo unas pocas columnas.:Al copiar, los valores antiguos que no son 0 convergen a 1,Asegúrese de que solo aparezca 0/1 en la columna.
 */
internal val MINI_PHONE_MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS mp_thread_new (" +
                "id TEXT NOT NULL, characterId TEXT NOT NULL, pinned INTEGER NOT NULL DEFAULT 0, " +
                "unreadCount INTEGER NOT NULL, lastMessagePreview TEXT, lastMessageAt INTEGER NOT NULL, " +
                "muted INTEGER NOT NULL, PRIMARY KEY(id))",
        )
        db.execSQL(
            "INSERT INTO mp_thread_new " +
                "(id, characterId, pinned, unreadCount, lastMessagePreview, lastMessageAt, muted) " +
                "SELECT id, characterId, CASE WHEN pinned <> 0 THEN 1 ELSE 0 END, unreadCount, " +
                "lastMessagePreview, lastMessageAt, muted FROM mp_thread",
        )
        db.execSQL("DROP TABLE mp_thread")
        db.execSQL("ALTER TABLE mp_thread_new RENAME TO mp_thread")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_mp_thread_characterId ON mp_thread (characterId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_mp_thread_lastMessageAt ON mp_thread (lastMessageAt)")
    }
}

/**
 * 3 → 4:La mesa de conversación está separada en "Chat grupal".,Se crea una tabla separada para las relaciones de membresía.
 *
 * Ambas columnas tienen valores predeterminados.,Una vez que se actualice el banco existente, naturalmente caerá en "Mensaje privado":isGroup=0,title=''. Solo agregue columnas aquí,No reconstruir la tabla ——
 * Los dos índices antiguos y los identificadores de fila se dejan intactos.
 *
 * La membresía no se puede agrupar en una columna de la fila de la sesión.(No hay límite superior en el número de personas en el grupo.,Dividirlo en columnas equivale a establecer un límite superior en el número de personas.),Así que empieza uno nuevo
 * mp_thread_member,clave primaria(sesión, papel)Asegúrese de que la misma persona ingrese al mismo grupo en una sola fila. Solo hay roles en la mesa.,
 * El usuario local no está en la tabla.
 */
internal val MINI_PHONE_MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE mp_thread ADD COLUMN isGroup INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE mp_thread ADD COLUMN title TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS mp_thread_member (threadId TEXT NOT NULL, characterId TEXT NOT NULL, " +
                "joinedAt INTEGER NOT NULL, PRIMARY KEY(threadId, characterId))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_mp_thread_member_threadId ON mp_thread_member (threadId)")
    }
}

/**
 * 4 → 5:Recuerde si el nombre del grupo está "escrito automáticamente" o "modificado por el usuario".
 *
 * De hecho, esta es una nueva columna.,ALTER in situ para agregar columnas,Todas las líneas de acciones caen en 0(No cambiado).
 * No existe tal información en el grupo anterior.,Sólo puedo fingir que no he cambiado nada. —— Esos nombres de grupos se deletrearon automáticamente cuando se creó el grupo.,
 * consistente con la situación real;Se establece en 1 cada vez que el usuario cambia su nombre.
 */
internal val MINI_PHONE_MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE mp_thread ADD COLUMN titleCustomized INTEGER NOT NULL DEFAULT 0")
    }
}

/** Nombre de archivo de biblioteca independiente. */
internal const val MINI_PHONE_DB_NAME: String = "miniphone.db"

/**
 * La propia base de datos de la habitación del teléfono pequeño.
 *
 * cuerpo del personaje,AI habilidad,TTS Ninguno de ellos está aquí. —— los pasados por el anfitrión [io.zer0.muse.miniphone.api.MiniPhoneHost]
 * inyectar;Aquí sólo se almacena el estado de persistencia de la "vida del personaje" en este módulo.:
 * información, conversación(Mensajes privados y chats grupales.), miembros del grupo, mensajes, actualizaciones e interacciones, diarios, álbumes de fotos.
 */
@Database(
    entities = [
        MpProfileEntity::class,
        MpThreadEntity::class,
        MpThreadMemberEntity::class,
        MpMessageEntity::class,
        MpMomentEntity::class,
        MpMomentLikeEntity::class,
        MpMomentCommentEntity::class,
        MpDiaryEntity::class,
        MpAlbumEntity::class,
    ],
    version = MINI_PHONE_DB_VERSION,
    exportSchema = true,
)
@TypeConverters(MpConverters::class)
abstract class MiniPhoneDb : RoomDatabase() {
    abstract fun profileDao(): MpProfileDao

    abstract fun threadDao(): MpThreadDao

    abstract fun threadMemberDao(): MpThreadMemberDao

    abstract fun messageDao(): MpMessageDao

    abstract fun momentDao(): MpMomentDao

    abstract fun momentLikeDao(): MpMomentLikeDao

    abstract fun momentCommentDao(): MpMomentCommentDao

    abstract fun diaryDao(): MpDiaryDao

    abstract fun albumDao(): MpAlbumDao

    companion object {
        /** abierto(o crear por primera vez)miniphone.db. */
        fun create(context: Context): MiniPhoneDb = Room.databaseBuilder(context, MiniPhoneDb::class.java, MINI_PHONE_DB_NAME)
            .addMigrations(MINI_PHONE_MIGRATION_1_2, MINI_PHONE_MIGRATION_2_3, MINI_PHONE_MIGRATION_3_4, MINI_PHONE_MIGRATION_4_5)
            .build()
    }
}
