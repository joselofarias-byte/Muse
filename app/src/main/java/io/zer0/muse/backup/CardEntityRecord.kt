package io.zer0.muse.backup

import io.zer0.muse.data.card.CardEntity
import kotlinx.serialization.Serializable

/**
 * entidad de tarjeta (`card_entities`) formato de línea de respaldo.
 *
 * ¿Por qué no simplemente poner [CardEntity] relleno [BackupService.Backup]:Room La entidad es propiedad del dominio de la tarjeta,
 * Ninguno per se `@Serializable`. El lado de respaldo utiliza duplicación proporcional + mapeo bidireccional. La ventaja es que el formato de la copia de seguridad no va acompañado de anotaciones de entidades.
 * Al agregar entidades, solo necesita agregar valores predeterminados a la imagen para continuar leyendo copias de seguridad antiguas.
 *
 * Nombre del campo y `CardEntity` Los nombres de las propiedades de Kotlin corresponden uno a uno (no nombres de columnas), por lo que al leer la copia de seguridad
 * Tiene el mismo aspecto que la entidad en el código. La asignación de nombres de columnas es generada por Room por anotación de entidad (v2.6.9 Leer y escribir desde la copia de seguridad
 * Utilice CardEntityDao, ya no mantiene listas escritas a mano).
 */
@Serializable
data class CardEntityRecord(
    /** UUID Clave principal, identidad de tarjeta estable durante los reinicios. */
    val id: String,
    /** viejo `c_xxx` Identificación de la tarjeta, solo se usa para rellenar. */
    val legacyCardId: String? = null,
    val originSessionId: String? = null,
    val originMessageId: String? = null,
    val title: String = "",
    /** HTML / SVG fuente. */
    val code: String = "",
    val isSvg: Boolean = false,
    /** Vincular datos (texto JSON en card_data.json original). */
    val dataJson: String? = null,
    /** null = Sólo en el flujo de mensajes;>= 0 = secuencia de atraque. */
    val dockSlot: Int? = null,
    val dockedAt: Long? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

internal fun CardEntity.toRecord(): CardEntityRecord = CardEntityRecord(
    id = id,
    legacyCardId = legacyCardId,
    originSessionId = originSessionId,
    originMessageId = originMessageId,
    title = title,
    code = code,
    isSvg = isSvg,
    dataJson = dataJson,
    dockSlot = dockSlot,
    dockedAt = dockedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun CardEntityRecord.toEntity(): CardEntity = CardEntity(
    id = id,
    legacyCardId = legacyCardId,
    originSessionId = originSessionId,
    originMessageId = originMessageId,
    title = title,
    code = code,
    isSvg = isSvg,
    dataJson = dataJson,
    dockSlot = dockSlot,
    dockedAt = dockedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/**
 * El orden de las tarjetas en la copia de seguridad: las tarjetas acopladas van primero en orden ascendente de ranuras y las tarjetas desacopladas van al final en orden ascendente de tiempo de creación.
 *
 * ¿Por qué realizar pedidos en el lado de exportación en lugar de depender del orden físico de la mesa?`dockSlot` Tiene un índice único y la recuperación se reescribe una por una.
 * El orden se fija para que los archivos de respaldo se puedan comparar y restaurar de manera reproducible; la tarjeta en el flujo de mensajes (`dockSlot` es nulo)
 * El comportamiento de clasificación no está definido en SQLite, use `createdAt` + `id` Asegúrese de que los dos resultados de exportación sean consistentes.
 */
internal fun List<CardEntityRecord>.inDockOrder(): List<CardEntityRecord> = sortedWith(
    compareBy<CardEntityRecord>(
        { it.dockSlot == null },
        { it.dockSlot ?: 0 },
        { it.createdAt },
        { it.id },
    ),
)

/**
 * Verificación de la unicidad de la ranura del muelle: si dos tarjetas ocupan la misma ranura, se generará un error de restricción en la capa SQLite.
 * Pero ese error solo escribe "UNIQUE constraint failed", al solucionar problemas, no es obvio que los datos de la copia de seguridad estén dañados.
 * Las razones legibles se dan aquí de antemano; la recuperación sigue siendo rápida y la reversión completa (del mismo calibre que la entidad existente, no se abren casos especiales).
 */
internal fun requireNoDuplicateDockSlots(cards: List<CardEntity>) {
    val duplicates = cards
        .mapNotNull { it.dockSlot }
        .groupingBy { it }
        .eachCount()
        .filterValues { it > 1 }
        .keys
    check(duplicates.isEmpty()) {
        "Ranuras de acoplamiento para tarjetas duplicadas en respaldo: ${duplicates.sorted()},Negarse a restaurar(La ranura sólo puede pertenecer a una tarjeta al mismo tiempo.)"
    }
}
