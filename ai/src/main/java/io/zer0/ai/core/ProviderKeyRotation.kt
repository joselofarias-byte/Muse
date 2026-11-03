package io.zer0.ai.core

import io.zer0.ai.util.KeyRoulette

/**
 * T1.2: Lógica de rotación multiclave reutilizable.
 *
 * con [ProviderHttpSupport] Los métodos integrados con el mismo nombre son lógicamente consistentes.,Pero sepárelos en categorías.,
 * Si heredar o no [ProviderHttpSupport] de Provider(Como la familia ImageProvider)Reutilizar,
 * hacer effectiveApiKey / switchToNextKey / markKeyFailed La verificación se puede construir directamente en la prueba.
 *
 * Cada instancia tiene un independiente [KeyRoulette],Evite la interferencia de estados entre proveedores.
 *
 * @param config Configuración del proveedor(Contiene posibles comas./Muchas separaciones de nueva línea key apiKey)
 * @param keyRoulette Inyección opcional,Autoconstruido por defecto(Las pruebas pueden inyectar instancias compartidas.)
 */
open class ProviderKeyRotation(
    val config: ProviderConfig,
    private val keyRoulette: KeyRoulette = KeyRoulette(),
) {

    @Volatile
    var currentApiKey: String = config.apiKey
        private set

    /**
     * Obtenga la clave API actual que se debe utilizar.
     *
     * Escenario clave única(config.apiKey sin coma/nueva línea)Devuelve directamente el valor original después de recortar key,Omita la lógica LRU.
     *
     * T1.2 Reparación:Los escenarios de múltiples claves se vuelven a ejecutar cada vez que se llaman. LRU pick,No más golpes"caché"La sucursal regresa directamente
     * [currentApiKey] —— De lo contrario quedará bloqueado [switchToNextKey] / [markKeyFailed] rotado solo
     * key en,Se pierde el significado de rotación de LRU y omisión de listas negras.
     */
    open fun effectiveApiKey(): String {
        if (!hasMultipleKeys()) {
            return config.apiKey.trim()
        }
        currentApiKey = keyRoulette.pick(config.id, config.apiKey)
        return currentApiKey
    }

    /**
     * 429 Cambie a la siguiente tecla cuando la corriente sea limitada.
     *
     * Agregue la clave actual a la lista negra suave(60s),Luego seleccione la siguiente clave.
     * si solo hay uno key,Regresar false(Provider Debería utilizar un retroceso exponencial y volver a intentarlo.).
     *
     * @return true Indica un cambio exitoso al nuevo key(Debería intentarlo de nuevo inmediatamente.);
     *         false Indica que solo hay una clave o que sigue siendo la misma después del cambio. key
     */
    open fun switchToNextKey(): Boolean {
        if (!hasMultipleKeys()) {
            return false
        }
        val previous = currentApiKey
        currentApiKey = keyRoulette.pickNext(config.id, config.apiKey, previous)
        return currentApiKey != previous
    }

    /**
     * Error al marcar la clave actual(Como la autenticación 401 falló),5 excluir completamente el key,y elige el siguiente.
     *
     * @return true Indica que hay otras claves disponibles.(Inténtalo de nuevo después de cambiar);
     *         false Indica que solo una clave o todas las claves han fallado
     */
    fun markKeyFailed(hardBlock: Boolean = true): Boolean {
        if (!hasMultipleKeys()) {
            return false
        }
        val failedKey = currentApiKey
        keyRoulette.markFailed(config.id, failedKey, hardBlock = hardBlock)
        currentApiKey = ""
        val newKey = effectiveApiKey()
        return newKey != failedKey
    }

    private fun hasMultipleKeys(): Boolean = config.apiKey.contains(',') || config.apiKey.contains('\n')
}
