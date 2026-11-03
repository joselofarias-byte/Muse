package io.zer0.muse.data.preset

/**
 * De forma predeterminada, el ID de especificación tiene el mismo nombre que el ID de proveedor del directorio del modelo de nube.
 *
 * fondo (v2.6.0):
 *  - El specId del proveedor de aprovisionamiento viene dado por `id.removePrefix("preset_")` derivación, utilizada históricamente**subrayado**Nombre
 *    (como `openai_codex`,`baidu_cloud`,`dashscope_coding`).
 *  - Directorio de modelos en la nube `model-catalog.json` Se utiliza la sección de proveedores.**guión**Nombre
 *    (como `openai-codex-oauth`,`baidu-cloud`,`dashscope-coding`).
 *
 * [ModelCatalogStore.mergeIntoModels],[ModelCatalogStore.entries] espera providerId
 * Coincide exactamente (ignorando mayúsculas y minúsculas) con las entradas del directorio. En caso de denominación inconsistente**La partida silenciosa falló** —— Representado como una lista modelo.
 * Los nuevos modelos (como la serie GPT-6 de Codex) que permanecen en los directorios de datos antiguos y en la nube predeterminados y codificados no surten efecto.
 *
 * Este objeto sólo está disponible en**capa lógica de código**Realice la normalización de identificación y no preestablezca ningún dato de modelo: la lista de modelos siempre proviene del directorio de la nube.
 */
object CatalogProviderIds {

    /**
     * Alias explícito:specId → Id. del proveedor del catálogo.
     *
     * Sólo sobrescribir no se puede utilizar "subrayado"→Algunos casos de derivación de las reglas generales del guión;
     * El resto de la denominación es inconsistente (como `baidu_cloud` → `baidu-cloud`) por [toCatalogId] Procesamiento de reglas generales.
     */
    private val aliases: Map<String, String> = mapOf(
        // Codex Suscríbase a la autenticación oficial del dispositivo y la clave del directorio tiene el sufijo -oauth.
        "openai_codex" to "openai-codex-oauth",
        // La puerta de enlace OpenCode Go en el directorio utiliza opencode-go.
        "opencode" to "opencode-go",
        // xAI de suscripciones/OAuth Las variantes comparten la misma entrada de catálogo.
        "xai_oauth" to "xai",
    )

    /**
     * Normalice el specId predeterminado al proveedorId del directorio de la nube.
     *
     * Idempotente: si la clave del directorio correspondiente ya está en la tabla de alias (o no está en la tabla de alias y no está subrayada), se devuelve tal cual.
     * Por lo tanto, es seguro llamar al proveedor desde cualquier fuente.
     */
    fun toCatalogId(specId: String): String {
        val raw = specId.trim()
        if (raw.isEmpty()) return raw
        // Los alias explícitos tienen prioridad; de lo contrario, los guiones bajos se normalizan a guiones según las reglas generales
        // (No tiene ningún efecto secundario en las claves con guiones o en los identificadores personalizados que no están en el directorio).
        return aliases[raw.lowercase()] ?: raw.replace('_', '-')
    }
}
