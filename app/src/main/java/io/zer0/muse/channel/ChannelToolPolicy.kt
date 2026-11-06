package io.zer0.muse.channel

import io.zer0.common.AppJson
import io.zer0.muse.tools.ToolRiskLevel
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * El canal no tiene un enlace de aprobación interactivo.:Cuando la lista blanca está corrupta fail closed,Y el final de la ejecución solo acepta herramientas que no sean ALTAS y que realmente estén expuestas en esta ronda.
 */
internal fun parseChannelToolAllowlist(json: String): Set<String>? {
    if (json.isBlank()) return emptySet()
    return runCatching {
        AppJson.parseToJsonElement(json).jsonArray
            .mapNotNull { it.jsonPrimitive.contentOrNull }
            .toSet()
    }.getOrNull()
}

internal fun mayExecuteChannelToolCall(toolName: String, offeredToolNames: Set<String>, risk: ToolRiskLevel): Boolean {
    return toolName in offeredToolNames && risk != ToolRiskLevel.HIGH
}
