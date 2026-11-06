package io.zer0.ai.core

/**
 * Provider Referencias conjuntas estables con modelos.
 *
 * Muse La configuración anterior de todavía guarda el ID del proveedor y el ID del modelo por separado; este tipo proporciona un formulario de texto serializable unificado,
 * Se utiliza para registrar, almacenar en caché y migraciones de configuración posteriores. El ID del modelo en sí puede contener `/`, por lo que solo se divide el primer delimitador.
 */
data class ModelReference(
    val providerId: String,
    val modelId: String,
) {
    init {
        require(providerId.isNotBlank()) { "providerId must not be blank" }
        require(modelId.isNotBlank()) { "modelId must not be blank" }
    }

    /** retorno estable `providerId/modelId` Cita. */
    fun asString(): String = "$providerId/$modelId"

    companion object {
        /** analizar `providerId/modelId`, la identificación del modelo desnudo no será una atribución adivinada. */
        fun parse(raw: String?): ModelReference? {
            val value = raw?.trim().orEmpty()
            val separator = value.indexOf('/')
            if (separator <= 0 || separator == value.lastIndex) return null
            val provider = value.substring(0, separator).trim()
            val model = value.substring(separator + 1).trim()
            return if (provider.isBlank() || model.isBlank()) null else ModelReference(provider, model)
        }

        /** Generar referencias de unión estables a partir de modelos resueltos. */
        fun of(model: Model): ModelReference = ModelReference(model.providerId, model.id)
    }
}
