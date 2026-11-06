package io.zer0.muse.web

/**
 * Establezca los valores de tiempo de ejecución necesarios para la página de inicio.
 *
 * Instale sólo lo necesario para el renderizado.:Una pequeña cantidad de valores de visualización de solo lectura + el estado actual de los interruptores de uso común + una tabla de valores sin procesar para formularios de subpágina.
 */
internal data class HostSettingsValues(
    val language: String = "",
    val themeMode: String = "",
    val fontSizeScale: String = "",
    val themeId: String = "",
    val toggles: Map<String, Boolean> = emptyMap(),
    /**
     * El valor original del formulario de subpágina, con la misma clave que [HostSettingDefinitions.all].
     *
     * Siempre llevado como una cadena (booleano `true`/`false`, texto decimal numérico), consistente con el calibre de la cadena cuando se escribe,
     * La información de tipo en la instantánea la proporciona la tabla de definición y no es necesario volver a crear un conjunto de tipos aquí.
     */
    val raw: Map<String, String> = emptyMap(),
)

/**
 * Configure el puerto de lectura y escritura.
 *
 * La escritura es "clave + cadena normalizada por tipo": la clave debe estar en [HostSettingDefinitions.writableKeys] dentro,
 * El valor debe pasar la verificación de tipo del artículo, estas dos cosas son [HostSettingsCommands] terminado; solo en este nivel
 * La clave se distribuye al método correspondiente de SettingsRepository. La cadena enviada por el navegador no se ingresará en el almacén tal como está.
 */
internal interface HostSettingsAccess {
    suspend fun readValues(): HostSettingsValues

    suspend fun write(key: String, value: String)
}

internal sealed class HostSettingsUpdateResult {
    data class Applied(val key: String) : HostSettingsUpdateResult()

    data class Rejected(val reason: String) : HostSettingsUpdateResult()
}

/**
 * settings.snapshot / settings.update realización.
 *
 * Los valores de lista blanca, tipo, rango y enumeración están representados por [HostSettingDefinitions] Esta definición determina:
 * La lista de entrega y el conjunto de claves grabables tienen la misma fuente. La verificación y la escritura comparten el mismo tipo de información, por lo que no habrá "está en la instantánea pero no se puede escribir".
 * O la deriva "se puede escribir, pero no se puede ver en las instantáneas".
 *
 * en instantánea `title` / `desc` / `groupTitle` / Los nombres para mostrar de los candidatos enumerados son todos texto localizado, representado por
 * [HostSettingsStrings] Analizado de acuerdo con el idioma de la aplicación actual y luego emitido; nombre del recurso (`titleKey` / `descKey` / grupo `key`)
 * Continúe en el protocolo como identificador estable. Por lo tanto, la página web no necesita mantener una segunda copia.
 */
internal class HostSettingsCommands(
    private val access: HostSettingsAccess,
    private val strings: HostSettingsStrings,
) {

    suspend fun snapshot(): HostSettingsSnapshot {
        val values = access.readValues()
        return HostSettingsSnapshot(
            groups = HostSettingsCatalog.groups.map { group ->
                group.copy(
                    groupTitle = strings.resolve(group.key, values.language).orEmpty(),
                    entries = group.entries.map { it.withChecked(values.toggles).withLocalizedText(values.language) },
                )
            },
            toggles = HostSettingKeys.readable.map { key ->
                HostSettingToggle(key = key, enabled = values.toggles[key] ?: false)
            },
            display = HostSettingsDisplay(
                language = values.language,
                themeMode = values.themeMode,
                fontSizeScale = values.fontSizeScale,
                themeId = values.themeId,
            ),
            writableKeys = HostSettingDefinitions.writableKeys,
            values = HostSettingDefinitions.all.map { it.toProtocol(values.raw, values.language) },
        )
    }

    suspend fun update(key: String?, rawValue: String?): HostSettingsUpdateResult {
        val definition = HostSettingDefinitions.find(key) ?: return HostSettingsUpdateResult.Rejected(
            if (key.isNullOrBlank()) {
                "settings.update requires key"
            } else {
                "settings.update rejected: '${key.trim()}' is not a known setting"
            },
        )
        if (!definition.writable) {
            return HostSettingsUpdateResult.Rejected("settings.update rejected: '${definition.key}' is read-only")
        }
        return when (val parsed = definition.parse(rawValue)) {
            is HostSettingParseResult.Rejected -> HostSettingsUpdateResult.Rejected(parsed.reason)
            is HostSettingParseResult.Ok -> {
                access.write(definition.key, parsed.canonical)
                HostSettingsUpdateResult.Applied(definition.key)
            }
        }
    }

    /** Escrito en un elemento del protocolo: valor + tipo + candidato de localización. */
    private fun HostSettingDefinition.toProtocol(raw: Map<String, String>, language: String): HostSettingValue =
        HostSettingValue(
            key = key,
            type = type.wireName,
            value = raw[key] ?: defaultWireValue(),
            options = options.map { (value, labelResource) ->
                HostSettingOption(value = value, label = strings.resolve(labelResource, language).orEmpty())
            },
            writable = writable,
            min = min,
            max = max,
            step = step,
        )

    /** Cuando no se puede obtener el valor, el valor booleano se rellena con falso y el resto se deja en blanco.——Mismo calibre que la parte inferior de los interruptores. */
    private fun HostSettingDefinition.defaultWireValue(): String =
        if (type == HostSettingType.BOOLEAN) "false" else ""

    private fun HostSettingsEntry.withChecked(toggles: Map<String, Boolean>): HostSettingsEntry {
        val toggleKey = toggleKey ?: return this
        return copy(checked = toggles[toggleKey] ?: false)
    }

    /** Complete el texto localizado. Si no se puede analizar, déjelo en blanco.——Sería mejor perder una línea que mostrar el nombre del recurso como redacción publicitaria. */
    private fun HostSettingsEntry.withLocalizedText(language: String): HostSettingsEntry = copy(
        title = strings.resolve(titleKey, language).orEmpty(),
        // No hay entrada con descKey declarada(Las instrucciones están detalladas en el tiempo de ejecución.,como el estado de proxy)No se emitieron instrucciones.
        desc = descKey?.let { key -> strings.resolve(key, language) },
    )
}
