package io.zer0.muse.ui.chat

import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig

/**
 * Solicita el resultado de la determinación de tres estados de la vinculación del modelo.
 *
 * fondo:sentencia original `provider.models.none { it.id == modelId }` en `models` Cuando la lista está vacía
 * **hengwei true**(en el conjunto vacío none{} Regresar true),Entonces "La lista de modelos aún no se ha extraído/La extracción falló"
 * Error de juicio como "el modelo realmente no existe" y desencadenante de una rebaja —— La sesión se cambia a un modelo encubierto menos capaz.
 * (Rendimiento real de la máquina:GLM-4-9B Malformación de llamada de herramienta,La generación de la tarjeta falla directamente).
 *
 * Los tres estados deben estar separados,este es el unico calibre:
 *  - [providerMissing]:Provider La configuración realmente ha desaparecido. → Bajar de categoría + alarma de nivel W(comportamiento original)
 *  - [modelMissing]:Provider Existencia, lista de modelos.**No vacío**, pero realmente no existe esto id → Bajar de categoría + alarma de nivel W
 *  - [listNotReady]:Provider Existencia, lista de modelos.**esta vacio**(Nunca jalé/no pude jalar)→ Sin degradación, sin advertencia,
 *    La vinculación solicitada por el usuario se conserva y se entrega al upstream para su determinación.;Solo queda un registro de información para solucionar problemas
 *
 * lista vacía ≠ No incluido.
 */
internal data class RequestedModelBinding(
    /** Provider La configuración no existe. */
    val providerMissing: Boolean,
    /** Provider Existe y la lista de modelos no está vacía.,Pero de hecho no hay ninguna identificación solicitada en la lista. */
    val modelMissing: Boolean,
    /** Provider Existe pero la lista de modelos está vacía. —— Lista no lista,No se puede considerar desaparecido basándose en esto. */
    val listNotReady: Boolean,
    /** Fijaciones disponibles:golpe preciso,O un enlace de marcador de posición construido por ID de solicitud cuando la lista no está lista;Si es verdadero, es nulo. */
    val model: Model?,
) {
    /** Sólo es necesario rebajar las \"verdaderas deficiencias\" y emitir una alarma de nivel W. */
    val missing: Boolean get() = providerMissing || modelMissing
}

/**
 * Analice el par vinculante "proveedor solicitado + modelo".
 *
 * Cómo escribir una única expresión de exportación:Tres estados son enumeraciones mutuamente excluyentes en lugar de cuatro estados booleanos independientes.,Utilice cuando de forma exhaustiva,
 * Evite que falten campos al ensamblar clases de datos con múltiples ramas de retorno.(También permite leer la sentencia de un vistazo.).
 *
 * @param providers Instantánea de todas las configuraciones actuales del proveedor
 * @param providerId Solicitado Provider id(null Indica que la persona que llama no ha especificado,Sin juicio)
 * @param modelId modelo solicitado id(null Indica que la persona que llama no ha especificado,Sin juicio)
 */
internal fun resolveRequestedModelBinding(providers: List<ProviderConfig>, providerId: String?, modelId: String?): RequestedModelBinding {
    val provider = providerId?.let { pid -> providers.firstOrNull { it.id == pid } }
    return when {
        // La persona que llama no especifica un proveedor: no se toma ninguna determinación.
        providerId == null -> NO_BINDING
        // Provider Falta tiene prioridad sobre modelId vacío —— Consistente con el comportamiento original.(Si se pierde la configuración, se emitirá una alarma.).
        provider == null -> MISSING_PROVIDER
        modelId == null -> NO_BINDING
        // golpe preciso → Usa la verdad de la lista. Model(Con declaración de competencia completa.)
        else -> {
            val exact = provider.models.firstOrNull { it.id == modelId }
            when {
                exact != null ->
                    RequestedModelBinding(
                        providerMissing = false,
                        modelMissing = false,
                        listNotReady = false,
                        model = exact,
                    )
                // La lista está vacía → No estoy listo. Preservar el enlace de la solicitud(Las capacidades son deducidas por ModelRegistry por id.),Sin degradación.
                provider.models.isEmpty() ->
                    RequestedModelBinding(
                        providerMissing = false,
                        modelMissing = false,
                        listNotReady = true,
                        model = Model(id = modelId, name = modelId, providerId = provider.id),
                    )
                // Realmente desaparecido:La lista no está vacía y no contiene el id → La persona que llama se procesa según la ruta original.
                else -> MISSING_MODEL
            }
        }
    }
}

private val NO_BINDING = RequestedModelBinding(
    providerMissing = false,
    modelMissing = false,
    listNotReady = false,
    model = null,
)

private val MISSING_PROVIDER = RequestedModelBinding(
    providerMissing = true,
    modelMissing = false,
    listNotReady = false,
    model = null,
)

private val MISSING_MODEL = RequestedModelBinding(
    providerMissing = false,
    modelMissing = true,
    listNotReady = false,
    model = null,
)
