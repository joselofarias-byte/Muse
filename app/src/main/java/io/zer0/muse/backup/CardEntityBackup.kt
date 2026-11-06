package io.zer0.muse.backup

import io.zer0.common.Logger
import io.zer0.muse.data.session.MuseDb

/**
 * `card_entities` La interfaz de lectura y escritura de respaldo.
 *
 * ## ¿Por qué no hay una lista aquí?
 *
 * La versión anterior usaba SQL escrito a mano + lista escrita a mano (`CardEntityTable`), la razón es que DAO carece de consulta y compensación completas.
 * El precio es que si olvida sincronizar la lista de columnas al agregar entidades, las nuevas columnas se perderán silenciosamente en la copia de seguridad y solo podrá confiar en las pruebas de reflexión para detenerlas.
 * ahora [io.zer0.muse.data.card.CardEntityDao] Completado `getAll()` / `insertAll()` /
 * `deleteAll()` / `count()`, toda la lectura y escritura regresa al mapeo generado por Room: solo hay una fuente verdadera para la lista
 * (`CardEntity` de `@ColumnInfo`), agregar campos no requiere cambiar ningún archivo en el lado de la copia de seguridad.
 *
 * Solo hay tres cosas que Room no puede ofrecer: conversión entre formatos por lotes y de línea, verificación previa de la ranura de acoplamiento (para permitir que se realicen copias de seguridad incorrectas).
 * Cite una razón legible por humanos en lugar de `UNIQUE constraint failed`) y la conciliación del número de filas después de la recuperación.
 *
 * ## negocios
 *
 * Todo suspendido, la persona que llama es responsable de envolver `db.withTransaction { }` (mismo calibre que las vías de recuperación de otras entidades).
 * Opte por DAO en su lugar `openHelper.writableDatabase`:Room Enviará llamadas DAO a transacciones
 * En esa conexión, la notificación de error a nivel de tabla también se activa como de costumbre y la suscripción de Flow en el área de acoplamiento se actualizará después de la recuperación.
 */

/**
 * Después de la recuperación, verifique la tarjeta: si el número de filas es insuficiente, se considerará un error y se descartará, lo que permitirá revertir toda la transacción de recuperación.
 *
 * con `BackupService` División de conciliación en B-34: Allá la verificación general se hace según la metadeclaración, aquí solo se agrega una.
 * Información específica de la tarjeta que puede señalar directamente el origen del problema (si realmente falta la tarjeta de conexión).
 * Si no se recuperan los datos, no se bloquea la importación, sino que solo se registra el registro (del mismo calibre que la conciliación existente).
 */
internal suspend fun verifyCardEntitiesRestored(db: MuseDb, expected: Int) {
    if (expected <= 0) return
    val actual = runCatching { db.cardEntityDao().count() }
        .onFailure { Logger.w("BackupService", "No se pudo recuperar la conciliación de almacenamiento de tarjetas: ${it.message}", it) }
        .getOrNull() ?: return
    check(actual >= expected) {
        "La cantidad de copias de seguridad almacenadas en la base de datos es inconsistente: cardEntities Declaración $expected, Almacenamiento real $actual"
    }
}

/**
 * Reemplazo completo de la tabla (para una única ruta de recuperación JSON): primero verifique las ranuras, luego bórrelas y finalmente escríbalas nuevamente en lotes.
 *
 * El cheque se coloca antes de la compensación. —— Aunque se puede evitar la reversión de transacciones, "confirmar que los datos no están dañados antes de eliminarlos" puede
 * El registro de la ruta fallida apunta más directamente al archivo de copia de seguridad. Se requiere vacío: la semántica de recuperación es un reemplazo completo,
 * No fusionada (consistente con todas las demás entidades); de lo contrario, la tarjeta antigua coexistirá con la tarjeta recuperada en el área de acoplamiento.
 *
 * @return El número real de tarjetas devueltas (para fines de conciliación).
 */
internal suspend fun MuseDb.replaceCardEntities(records: List<CardEntityRecord>): Int {
    val entities = records.map { it.toEntity() }
    requireNoDuplicateDockSlots(entities)
    cardEntityDao().deleteAll()
    if (entities.isNotEmpty()) cardEntityDao().insertAll(entities)
    return entities.size
}

/**
 * Escribe un lote adicional de tarjetas (NDJSON Para la recuperación de transmisión, la persona que llama ha borrado toda la tabla en el punto de inicio de la recuperación).
 *
 * Verifique las ranuras individualmente para cada lote: los índices únicos rechazarán las ranuras duplicadas en la capa SQLite, pero ese error solo escribe
 * `UNIQUE constraint failed`, al solucionar problemas, no es obvio que los datos de la copia de seguridad estén dañados. También se pueden bloquear las duplicaciones entre lotes.
 * Debido a que la verificación se basa en las filas del lote, y la repetición entre lotes alcanzará el índice único durante la inserción. —— Esa situación pertenece a
 * Los archivos de respaldo se contradicen: informar un error de restricción es mejor que sobrescribir silenciosamente una tarjeta.
 */
internal suspend fun MuseDb.appendCardEntities(records: List<CardEntityRecord>) {
    if (records.isEmpty()) return
    val entities = records.map { it.toEntity() }
    requireNoDuplicateDockSlots(entities)
    cardEntityDao().insertAll(entities)
}
