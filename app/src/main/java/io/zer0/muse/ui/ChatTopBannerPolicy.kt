package io.zer0.muse.ui

/**
 * Banner en la parte superior de la página de chat (visualización mutuamente excluyente, solo uno a la vez).
 *
 * Prioridad (alta → bajo):
 *  1. [ERROR] —— Los errores hay que verlos primero.v2.6.8 El error ocurrió además de la compresión:
 *     La compresión es solo ejecutar comentarios, los errores requieren la toma de decisiones del usuario/Tenga en cuenta que la oclusión de un banner comprimido puede provocar errores
 *     "Expira silenciosamente en segundo plano."(La antigua oclusión de compresión superpuesta que desaparece automáticamente de 5 segundos, es posible que el error no aparezca ni una sola vez).
 *  2. [COMPRESSION] —— Compresión/Progreso de actualización de la memoria, comentarios en ejecución, junto a errores.
 *  3. [PENDING_TOOLS] —— Reanudar la descarga desde un punto de interrupción (la llamada a la herramienta no se completa) requiere que el usuario elija reanudar o descartar.
 *  4. [NOT_CONFIGURED] —— La punta de luz residente para el servicio modelo no está configurada.
 *  5. [NONE] —— No se muestra ningún banner.
 *
 * La exclusión mutua está determinada por esta función.UI solo lado `topBanner == X` Mostrar y ocultar juicio; al agregar un banner
 * Debe agregar un archivo aquí y sincronizar los comentarios, no agregue condiciones adicionales mutuamente excluyentes en Composable.
 */
internal enum class ChatTopBanner {
    ERROR,
    COMPRESSION,
    PENDING_TOOLS,
    NOT_CONFIGURED,
    NONE,
}

internal fun resolveChatTopBanner(
    isCompressing: Boolean,
    showPendingResume: Boolean,
    hasErrors: Boolean,
    isConfigured: Boolean,
): ChatTopBanner = when {
    // Los errores tienen prioridad sobre la compresión: los errores durante la compresión también deben ser visibles inmediatamente (los errores pueden determinar si el usuario tiene que esperar hasta la compresión).
    hasErrors -> ChatTopBanner.ERROR
    isCompressing -> ChatTopBanner.COMPRESSION
    showPendingResume -> ChatTopBanner.PENDING_TOOLS
    !isConfigured -> ChatTopBanner.NOT_CONFIGURED
    else -> ChatTopBanner.NONE
}

/**
 * Ventana de lectura que desaparece automáticamente (milisegundos) para errores recuperables.
 *
 * De 5 segundos a 15 segundos: un mensaje de error de dos líneas debe leerse en unos 5 segundos a velocidad de lectura normal y luego salir
 * El margen para cambiar la atención (la emisión del streaming acaba de terminar y la mirada todavía está en el área del mensaje); al mismo tiempo, no basta con permitir
 * Los errores acumulados ocupan la cima durante mucho tiempo.
 *
 * el reloj no esta ahi [addError] Inicie en ese momento, pero inicie después del error y en realidad se convierta en el banner superior.
 * (Ver [resolveErrorVisibility])—— De lo contrario, los 15 segundos bloqueados por el Banner comprimido seguirán siendo en vano.
 */
internal const val ERROR_AUTO_DISMISS_MS = 15_000L

/**
 * error"espera hasta que sea realmente visible"El límite superior del límite (milisegundos).
 *
 * La desaparición automática solo comienza la cuenta regresiva cuando el error es en realidad el banner superior que se muestra y la aplicación está en primer plano.
 * Si no es visible por algún motivo (compresión prolongada, la aplicación pasa a segundo plano), puede darse por vencido después de esperar tanto tiempo como máximo.
 * Esta vez desaparecerá automáticamente. —— Es mejor dejar que el usuario lo cierre manualmente que dejar que el error desaparezca sin que nadie lo vea.
 */
internal const val ERROR_VISIBLE_WAIT_MS = 60_000L

/**
 * Categorías de error que requieren la toma de decisiones del usuario y tienen prohibido desaparecer automáticamente.
 *
 * - [ChatErrorType.TOOL_ERROR]: La ejecución de la herramienta falla, el usuario debe decidir intentarlo nuevamente/Cambiar de plan/Desechar.
 * - [ChatErrorType.MODEL_ERROR]: El lado del modelo devuelve una excepción, lo que requiere que el usuario decida si cambiar el modelo o cambiar los parámetros.
 * - [ChatErrorType.API_KEY]:Certificado/Los problemas de saldo solo pueden ser manejados por el usuario en la página de configuración.
 */
private val STICKY_ERROR_TYPES = setOf(
    ChatErrorType.TOOL_ERROR,
    ChatErrorType.MODEL_ERROR,
    ChatErrorType.API_KEY,
)

/**
 * Si se permite que el error desaparezca automáticamente.
 *
 * solo"Recuperable + no entra en una categoría que requiere la toma de decisiones del usuario"desaparecerá automáticamente; el resto permanecerá hasta que el usuario los cierre.
 */
internal fun shouldAutoDismissError(type: ChatErrorType, isRecoverable: Boolean): Boolean = isRecoverable && type !in STICKY_ERROR_TYPES

/** El resultado de la determinación de visibilidad del banner de error en un momento determinado. */
internal enum class ErrorVisibility {
    /** El cronómetro aún no se puede iniciar (no está en primer plano ni está ocupado por un banner de mayor prioridad). */
    PENDING,

    /** Los usuarios ahora pueden ver este banner de error. */
    VISIBLE,

    /** El error ya no está en la lista (el usuario ha cerrado/ha sido borrado), no es necesario repetir el tiempo. */
    GONE,
}

/**
 * Determinar si hay un error actualmente."Realmente mostrado frente a los ojos del usuario.".
 *
 * El momento de la desaparición automática debe utilizar esta función como puerta, y no puede simplemente mirar `addError` el tiempo de llamada.
 * Reutilizar aquí [resolveChatTopBanner] en lugar de juzgar directamente `hasErrors`: Reglas de exclusión mutua de banners
 * Si se realizan ajustes en el futuro (por ejemplo, cierto tipo de error da paso a otro banner), la lógica de sincronización cambiará automáticamente en consecuencia y no
 * aparecer"La estrategia ha cambiado, pero el calendario sigue funcionando según las antiguas reglas."de dos juegos de calibres.
 *
 * Método de escritura de salida única: las tres condiciones previas se reducen primero a"¿Puedes verlo ahora?", regresó solo una vez al final.
 * De esta manera `when` No habrá múltiples devoluciones y las nuevas condiciones solo cambiarán la primera mitad.
 */
internal fun resolveErrorVisibility(
    errorStillPresent: Boolean,
    appInForeground: Boolean,
    isCompressing: Boolean,
    showPendingResume: Boolean,
    isConfigured: Boolean,
): ErrorVisibility {
    val bannerIsError = resolveChatTopBanner(
        isCompressing = isCompressing,
        showPendingResume = showPendingResume,
        hasErrors = true,
        isConfigured = isConfigured,
    ) == ChatTopBanner.ERROR
    // ha desaparecido → GONE; todavía está allí pero el usuario no puede verlo en este momento (fondo/ocupado por un banner de mayor prioridad)→ PENDING.
    val visible = errorStillPresent && appInForeground && bannerIsError
    return when {
        !errorStillPresent -> ErrorVisibility.GONE
        visible -> ErrorVisibility.VISIBLE
        else -> ErrorVisibility.PENDING
    }
}
