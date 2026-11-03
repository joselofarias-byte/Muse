package io.zer0.ai.core

/**
 * v2.6.1: Retire la etiqueta de agrupación de canales principal adjunta a la estación de transferencia del sistema one-api/new-api en la lista de modelos.
 *
 * Algunas estaciones de transferencia `/v1/models` Usado en el interior `[grupo]Nombre del modelo` Mostrar agrupación de canales(Como por ejemplo `[ais]gemini-3.8-flash`),
 * Pero la sección de corchetes**No es un nombre de modelo enrutable**,Si lo envías tal cual, recibirás
 * `No available channel for model [ais]xxx ... code=model_not_found`(Pruebas de usuarios reales).
 *
 * Los nombres de los modelos legales no contienen corchetes,Por lo tanto, mientras haya una dirección `[...]` Sólo quítalo;Soporte multinivel(Como por ejemplo `[A][vip]claude-3`).
 * - Corchetes entre nombres de modelos(Como por ejemplo `model[1]`)No es una etiqueta principal,Déjalo como está.
 * - Etiqueta pura, vacía después de pelar(Sin nombre de modelo real)Volver al valor original,Evite enviar solicitudes a un modelo vacío.
 */
internal fun stripChannelGroupLabels(modelId: String): String {
    val trimmed = modelId.trim()
    if (!trimmed.startsWith("[")) return trimmed
    val stripped = Regex("^(\\[[^\\]\\[]{0,64}\\])+").replace(trimmed, "")
    return stripped.takeIf { it.isNotBlank() } ?: trimmed
}
