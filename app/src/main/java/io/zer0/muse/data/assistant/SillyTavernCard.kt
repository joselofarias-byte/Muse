package io.zer0.muse.data.assistant

import io.zer0.common.AppJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.util.zip.CRC32

/**
 * SillyTavern Especificación de la tarjeta de personaje V2 (chara_card_v2, spec 2.10).
 *
 * SillyTavern Las tarjetas de personajes son un formato común en la industria.:PNG Metadatos de imagen (tEXt chunk, key="chara")
 * o archivo JSON para almacenar la definición completa del rol. Admite la importación de tarjetas de personajes creadas por otras aplicaciones. Muse,
 * También puedes exportar el asistente Muse como una tarjeta de personaje y compartirlo con otras aplicaciones.
 *
 * V2 estructura de la tarjeta:
 *   {
 *     "spec": "chara_card_v2",
 *     "spec_version": "2.10",
 *     "data": { ... }
 *   }
 *
 * V1 Las tarjetas son planas. (Sin paquete de especificaciones/datos),Contiene sólo name/description/personality/
 * scenario/first_mes/mes_example campos. Importado por [CharacterCardImporter] Identifica automáticamente V1/V2.
 *
 * Sigue las convenciones de formato público de SillyTavern Character Card v2.
 */
@Serializable
data class SillyTavernCardV2(
    val spec: String = "chara_card_v2",
    @SerialName("spec_version")
    val specVersion: String = "2.10",
    val data: SillyTavernCardData,
) {

    /**
     * Convierte tarjetas de personajes en entidades auxiliares de Muse (id/Dejar la marca de tiempo en blanco,Completado por la persona que llama).
     *
     * Estrategia de mapeo de campo:
     *  - name → name
     *  - system_prompt Prioridad;Usado cuando está vacío description + personality + scenario empalme → systemPrompt
     *  - first_mes → messageTemplate (Úselo como plantilla de apertura)
     *  - post_history_instructions → deletrear hasta el final del sistemaPrompt
     *  - tags → tagsJson
     *  - mes_example / alternate_greetings / creator_notes / extensions → descartar (Muse Sin campo correspondiente)
     */
    fun toAssistantEntity(): AssistantEntity {
        val parts = mutableListOf<String>()
        val sp = data.systemPrompt.trim()
        if (sp.isNotEmpty()) {
            parts += sp
        } else {
            if (data.description.isNotBlank()) parts += data.description
            if (data.personality.isNotBlank()) parts += "Rasgos de carácter: ${data.personality}"
            if (data.scenario.isNotBlank()) parts += "Configuración de escena: ${data.scenario}"
        }
        if (data.postHistoryInstructions.isNotBlank()) {
            parts += "post-comando: ${data.postHistoryInstructions}"
        }
        val combined = parts.joinToString("\n\n")
        return AssistantEntity(
            id = "",
            name = data.name.ifBlank { "Importar roles" },
            systemPrompt = combined,
            messageTemplate = data.firstMes,
            tagsJson = serializeStringListSafe(data.tags),
        )
    }

    companion object {
        /**
         * Construye tarjetas de personajes de SillyTavern V2 a partir de entidades auxiliares de Muse.
         *
         * Mapeo de campo:
         *  - name → name
         *  - systemPrompt → description + system_prompt (doble escritura, Mejor compatibilidad)
         *  - messageTemplate → first_mes
         *  - tagsJson → tags
         *  - id → extensions.muse_id (Para viaje redondo)
         *  - avatarEmoji → extensions.muse_avatar_emoji (Para viaje redondo)
         */
        fun fromAssistantEntity(a: AssistantEntity): SillyTavernCardV2 {
            val tags = parseStringListSafe(a.tagsJson)
            return SillyTavernCardV2(
                data = SillyTavernCardData(
                    name = a.name,
                    description = a.systemPrompt,
                    systemPrompt = a.systemPrompt,
                    firstMes = a.messageTemplate,
                    tags = tags,
                    creator = "Muse",
                    characterVersion = "1.0",
                    extensions = buildMap {
                        if (a.id.isNotBlank()) put("muse_id", JsonPrimitive(a.id))
                        if (a.avatarEmoji.isNotBlank()) put("muse_avatar_emoji", JsonPrimitive(a.avatarEmoji))
                    },
                ),
            )
        }
    }
}

/**
 * SillyTavern Campo de datos de la tarjeta de rol (V2 spec).
 *
 * V1 Campos compatibles:name / description / personality / scenario / first_mes / mes_example.
 * V2 Agregar nuevo campo:creator_notes / system_prompt / post_history_instructions / tags /
 * creator / character_version / alternate_greetings / extensions.
 */
@Serializable
data class SillyTavernCardData(
    val name: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    @SerialName("first_mes") val firstMes: String = "",
    @SerialName("mes_example") val mesExample: String = "",
    @SerialName("creator_notes") val creatorNotes: String = "",
    @SerialName("system_prompt") val systemPrompt: String = "",
    @SerialName("post_history_instructions") val postHistoryInstructions: String = "",
    val tags: List<String> = emptyList(),
    val creator: String = "",
    @SerialName("character_version") val characterVersion: String = "",
    @SerialName("alternate_greetings") val alternateGreetings: List<String> = emptyList(),
    val extensions: Map<String, JsonElement> = emptyMap(),
)

// ── herramientas internas ──

/** Serializar una lista de cadenas en JSON (Consistente con AssistantRepository.serializeStringList). */
internal fun serializeStringListSafe(list: List<String>): String = AppJson.encodeToString(ListSerializer(String.serializer()), list)

/** Analizar de forma segura listas de cadenas JSON, Devuelve una lista vacía en caso de error. */
internal fun parseStringListSafe(json: String): List<String> {
    if (json.isBlank() || json == "[]") return emptyList()
    return runCatching {
        AppJson.decodeFromString(ListSerializer(String.serializer()), json)
    }.getOrDefault(emptyList())
}

/**
 * PNG chunk análisis manual/herramienta de escritura (No se introducen bibliotecas PNG de terceros).
 *
 * PNG Estructura de archivos:
 *   signature(8) + chunk* (length(4) + type(4) + data(length) + crc(4))
 *
 * tEXt chunk data Formato: keyword(1-79 bytes Latin-1) + 0x00 + text(Latin-1)
 * SillyTavern Acuerdo: keyword="chara", text=base64(JSON)
 *
 * CRC-32 Portada type + data, usar [CRC32] Calcular.
 */
internal object PngChunkUtil {

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4E,
        0x47,
        0x0D,
        0x0A,
        0x1A,
        0x0A,
    )

    /**
     * Lee los bytes PNG especificados [keyword] El texto del fragmento de texto.
     *
     * @return Devolución del golpe text (Latin-1 decodificar); PNG no encontrado o no devuelto null
     */
    fun readTextChunk(bytes: ByteArray, keyword: String): String? {
        // Verificar firma
        if (bytes.size < PNG_SIGNATURE.size + 8) return null
        for (i in PNG_SIGNATURE.indices) {
            if (bytes[i] != PNG_SIGNATURE[i]) return null
        }
        var pos = PNG_SIGNATURE.size
        while (pos + 8 <= bytes.size) {
            val length = readUInt32BE(bytes, pos)
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            val dataStart = pos + 8
            val dataEnd = dataStart + length.toInt()
            if (dataEnd + 4 > bytes.size) break // cruzar la linea, Archivos corruptos
            if (type == "tEXt") {
                val data = bytes.copyOfRange(dataStart, dataEnd)
                val sepIndex = data.indexOf(0)
                if (sepIndex > 0) {
                    val kw = String(data, 0, sepIndex, Charsets.ISO_8859_1)
                    if (kw == keyword) {
                        return String(data, sepIndex + 1, data.size - sepIndex - 1, Charsets.ISO_8859_1)
                    }
                }
            }
            pos = dataEnd + 4 // saltar CRC
            if (type == "IEND") break
        }
        return null
    }

    /**
     * Insertar en bytes PNG tEXt chunk (Después de insertar en IHDR, Ubicación recomendada según especificaciones.).
     *
     * No comprueba si ya existe la misma palabra clave tEXt (La persona que llama es responsable.).
     * si [bytes] Si no es un PNG, se devuelve sin cambios.
     */
    fun writeTextChunk(bytes: ByteArray, keyword: String, text: String): ByteArray {
        // Verificar firma
        if (bytes.size < PNG_SIGNATURE.size) return bytes
        for (i in PNG_SIGNATURE.indices) {
            if (bytes[i] != PNG_SIGNATURE[i]) return bytes
        }
        // estructura tEXt chunk data: keyword + 0x00 + text
        val keywordBytes = keyword.toByteArray(Charsets.ISO_8859_1)
        require(keywordBytes.size in 1..79) { "tEXt keyword La longitud debe estar dentro 1..79" }
        val textBytes = text.toByteArray(Charsets.ISO_8859_1)
        val chunkData = ByteArray(keywordBytes.size + 1 + textBytes.size)
        System.arraycopy(keywordBytes, 0, chunkData, 0, keywordBytes.size)
        chunkData[keywordBytes.size] = 0
        System.arraycopy(textBytes, 0, chunkData, keywordBytes.size + 1, textBytes.size)
        // CRC Portada type + data
        val crc = CRC32().apply {
            update("tEXt".toByteArray(Charsets.US_ASCII))
            update(chunkData)
        }.value
        // empalme chunk: length(4) + type(4) + data + crc(4)
        val chunk = ByteArray(8 + chunkData.size + 4)
        writeUInt32BE(chunk, 0, chunkData.size.toLong())
        "tEXt".toByteArray(Charsets.US_ASCII).copyInto(chunk, 4)
        chunkData.copyInto(chunk, 8)
        writeUInt32BE(chunk, 8 + chunkData.size, crc)
        // El primer trozo debe ser IHDR; Lea su longitud para determinar la posición final.
        val sigEnd = PNG_SIGNATURE.size
        if (sigEnd + 8 > bytes.size) return bytes
        val ihdrType = String(bytes, sigEnd + 4, 4, Charsets.US_ASCII)
        if (ihdrType != "IHDR") return bytes // anormal PNG
        val ihdrLen = readUInt32BE(bytes, sigEnd).toInt()
        val ihdrEnd = sigEnd + 8 + ihdrLen + 4 // length + type + data + crc
        if (ihdrEnd > bytes.size) return bytes
        val before = bytes.copyOfRange(0, ihdrEnd)
        val after = bytes.copyOfRange(ihdrEnd, bytes.size)
        return before + chunk + after
    }

    // ── Herramientas de orden de bytes ──

    private fun readUInt32BE(bytes: ByteArray, offset: Int): Long = ((bytes[offset].toLong() and 0xFF) shl 24) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
        (bytes[offset + 3].toLong() and 0xFF)

    private fun writeUInt32BE(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = ((value ushr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }
}
