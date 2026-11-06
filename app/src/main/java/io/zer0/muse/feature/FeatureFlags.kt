package io.zer0.muse.feature

/**
 * v2.6.7: interruptor de función —— Puerta unificada de manifestación y ocultamiento de funciones inmaduras.
 *
 * ¿Por qué utilizar constantes en tiempo de compilación en lugar de configuraciones en tiempo de ejecución?
 *  - Conmutadores de tiempo de ejecución (como `miniPhoneEnabledFlow`) es visible para los usuarios, se puede omitir y no está completamente oculto;
 *  - Las constantes en tiempo de compilación permiten que R8 elimine las ramas inalcanzables, sin dejar ningún código de entrada en el producto;
 *  - "Añádelo más tarde"Simplemente cambie la constante a verdadera,**No elimines ni una sola línea de código**.
 *
 * Acuerdo: todas las entradas externas (menú de inicio, elementos de configuración, registro de ruta del mapa de navegación) deben pasar por aquí.
 * No puedes simplemente ocultar la interfaz de usuario y mantener la ruta —— De lo contrario, aún puedes ingresar a través de un enlace profundo/restaurar la navegación.
 */
object FeatureFlags {
    /**
     * pequeño teléfono celular (MiniPhone) funcionalidad completa.
     *
     * v2.6.6 Cerrado por una ronda (la interfaz de usuario y la interacción no estaban finalizadas en ese momento);v2.6.12 ha sido reescrito como un módulo independiente
     * (`:miniphone`, con su propia base de datos y sistema artístico), reabierto al mundo exterior.
     */
    const val MINI_PHONE: Boolean = true

    /**
     * Si el teléfono celular pequeño muestra la entrada en la página de inicio y en la página de categoría de configuración.
     *
     * Separado del almacenamiento de esta función en sí:[MINI_PHONE] Si el módulo de control y el enrutamiento existen,
     * Este artículo sólo controla“No puedo ver ni ver”. En la etapa de producto semiacabado no existe entrada para el empujón principal.
     * Aún se puede realizar la búsqueda, aún se puede ingresar el enlace y aún se puede empaquetar el módulo en el paquete como de costumbre.
     */
    const val MINI_PHONE_ENTRY: Boolean = false

    /** Integrado en teléfonos celulares pequeños"AI Momentos"(Moments). depender de [MINI_PHONE]. */
    const val MOMENTS: Boolean = MINI_PHONE
}
