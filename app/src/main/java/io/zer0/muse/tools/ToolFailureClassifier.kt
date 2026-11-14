package io.zer0.muse.tools

/**
 * Una clasificación estructurada de recibos de fallas de herramientas.
 *
 * ## por qué es necesario
 *
 * Autoevaluación del asistente: "Si la herramienta falla, solo podré corregirla una vez". —— Esta restricción en sí es correcta, pero si el mensaje de error es ambiguo
 * (Por ejemplo"El parámetro es ilegal."), sólo puedo adivinar, y una vez que lo hago, es inútil”.
 *
 * Situación actual: en caso de falla, solo se inyecta un arranque de prioridad fija de cuatro niveles (parámetros incorrectos)→correcciones, permisos→Cambiar herramientas, red.→Inténtalo de nuevo,
 * Otros→informar al usuario), el modelo tiene que adivinar a qué nivel pertenece a partir del texto de error. Adivinar mal desperdicia tu única oportunidad de corregir.
 *
 * Aquí se calcula "A qué categoría pertenece este error y si se puede volver a intentar de forma segura" y se adjunta directamente al recibo de error.
 * La clasificación solo se basa en el texto del error (los valores de retorno de cada herramienta no tienen una estructura unificada), por lo que las reglas deben ser conservadoras:
 * Si no puedes juzgar, simplemente regresa [FailureKind.UNKNOWN] y `retryable=false` —— Prefiero dejar que la modelo pregunte,
 * Tampoco permita que lo vuelva a intentar una y otra vez en caso de fallos que no se pueden reintentar.
 */
object ToolFailureClassifier {

    /** Categoría de fracaso. */
    enum class FailureKind {
        /** parámetros/camino/Error de formato —— Tiene sentido volver a intentarlo después de cambiar los parámetros. */
        INVALID_ARGUMENT,

        /** Falta de permiso o canal no listo —— Reintentar es inútil y requiere que el usuario lo abra. */
        PERMISSION,

        /** El recurso no existe (archivo/grabar/id). */
        NOT_FOUND,

        /** La red o el flujo ascendente no están disponibles temporalmente —— Normalmente puedes intentarlo de nuevo. */
        TRANSIENT,

        /** se acabó el tiempo. */
        TIMEOUT,

        /** Cancelado o interrumpido, no es un error. */
        CANCELLED,

        /** golpe de seguridad/Restricciones de aprobación. */
        BLOCKED,

        /** Fallo que no se puede categorizar. */
        UNKNOWN,
    }

    /** Una conclusión estructurada para una respuesta fallida. */
    data class Verdict(
        val kind: FailureKind,
        val retryable: Boolean,
        /** Se puede escribir un consejo para el modelo directamente en el recibo. */
        val hint: String,
    )

    private val TIMEOUT_MARKERS = listOf("[tiempo de espera]", "[Tiempo agotado]", "tiempo de espera", "Tiempo agotado", "timed out", "timeout")
    private val CANCELLED_MARKERS = listOf("[interrumpir]", "[Cancelar]", "[Cancelado]", "Cancelado", "cancelled", "canceled", "aborted")
    private val PERMISSION_MARKERS = listOf(
        "Permisos", "No autorizado", "permission denied", "unauthorized", "forbidden", "403",
        "shizuku", "root", "Accesibilidad", "Aviso de derechos de uso", "acceso de uso",
    )
    private val NOT_FOUND_MARKERS = listOf(
        "no existe",
        "no encontrado",
        "no encontrado",
        "not found",
        "no such file",
        "404",
    )
    private val TRANSIENT_MARKERS = listOf(
        "red", "La conexión falló", "Tiempo de espera de conexión", "timeout connecting", "connection", "503", "502", "429",
        "rate limit", "too many requests", "temporalmente", "Vuelve a intentarlo más tarde",
    )
    private val BLOCKED_MARKERS = listOf(
        "Requiere intervención",
        "Aprobación",
        "bloque",
        "rechazar",
        "interceptado",
        "seguro",
        "ultra vires",
        "denied",
        "blocked",
        "approval",
    )
    private val INVALID_ARGUMENT_MARKERS = listOf(
        "Faltan campos obligatorios", "Parámetro faltante", "parámetro", "parámetros", "Formato", "ilegal", "invalid", "must be", "required", "requerido",
        "El análisis falló", "error de tipo",
    )

    /**
     * Tabla de reglas de clasificación.**el orden es prioridad**: Cuanto antes sea, antes será juzgado.
     *
     * tiempo de espera/La cancelación debe preceder a los permisos. —— Los mensajes de cancelación suelen contener palabras como "autorización".
     * Si primero determina los permisos, se informará erróneamente como "falta de permisos".
     */
    private val RULES: List<Rule> = listOf(
        Rule(FailureKind.TIMEOUT, true, TIMEOUT_MARKERS, "Tiempo de espera: puedes volver a intentarlo tal cual; Si aún se agota el tiempo de espera, infórmele al usuario el hecho del tiempo de espera y no vuelva a intentarlo con parámetros diferentes."),
        Rule(FailureKind.CANCELLED, false, CANCELLED_MARKERS, "La operación fue cancelada, no fallida. No lo vuelva a intentar, continúe con la intención actual del usuario."),
        Rule(
            FailureKind.PERMISSION,
            false,
            PERMISSION_MARKERS,
            "Falta de permisos/Canal no listo: volver a intentarlo no tiene ningún efecto. Primero ajuste tool_status para ver qué falta y luego dígale al usuario la ruta de activación.",
        ),
        Rule(FailureKind.BLOCKED, false, BLOCKED_MARKERS, "Bloqueado por política de seguridad o aprobación: No lo omita, explique el motivo del bloqueo al usuario y espere instrucciones."),
        Rule(
            FailureKind.NOT_FOUND,
            false,
            NOT_FOUND_MARKERS,
            "El objetivo no existe: primero confirme la ruta/id ¿Es correcto (enumerar o buscar si es necesario)? No vuelva a intentarlo con el mismo valor.",
        ),
        Rule(FailureKind.TRANSIENT, true, TRANSIENT_MARKERS, "Fallo temporal: Puedes volver a intentarlo; Si aún falla, diga la verdad con sinceridad y no pierda el tiempo reintentando continuamente."),
        Rule(
            FailureKind.INVALID_ARGUMENT,
            true,
            INVALID_ARGUMENT_MARKERS,
            "Problemas de parámetros: cambie los parámetros de acuerdo con el texto original del error y vuelva a intentarlo; Si no se pueden realizar los cambios, pregúntele al usuario y no adivine a ciegas.",
        ),
    )

    /** Una regla de clasificación. */
    private class Rule(
        val kind: FailureKind,
        val retryable: Boolean,
        val markers: List<String>,
        val hint: String,
    ) {
        fun matches(lower: String): Boolean = markers.any { lower.contains(it.lowercase()) }
    }

    private val UNKNOWN = Verdict(
        kind = FailureKind.UNKNOWN,
        retryable = false,
        hint = "No se puede determinar la causa: no vuelva a intentarlo a ciegas. Lea primero el texto original del error; Si realmente necesita continuar, primero explique la situación al usuario.",
    )

    /**
     * Clasificar un resultado fallido.
     *
     * @param result El texto original devuelto por la herramienta (la versión sin la directriz inyectada)
     * @param timedOut Si está determinado por la lógica de tiempo de espera del ejecutor (más confiable que el texto)
     */
    fun classify(result: String, timedOut: Boolean = false): Verdict {
        val lower = result.trimStart().lowercase()
        // Cuando el ejecutor ha determinado que se ha agotado el tiempo de espera.,Incluso si el texto es similar a otras categorías, se procesará como tiempo de espera. —— Esa es una señal más confiable.
        val forced = if (timedOut) RULES.first() else null
        val rule = forced ?: RULES.firstOrNull { it.matches(lower) }
        return rule?.let { Verdict(it.kind, it.retryable, it.hint) } ?: UNKNOWN
    }

    /**
     * Genere filas estructuradas para recibos de fallas de entrada.
     *
     * Forma como:`[Clasificación de fallas] kind=permission retryable=false hint=...`
     * usar key=value En lugar de lenguaje natural, es conveniente para la lectura directa del modelo y la recuperación de registros.
     */
    fun receiptLine(verdict: Verdict): String =
        "[Clasificación de fallas] kind=${verdict.kind.name.lowercase()} retryable=${verdict.retryable} hint=${verdict.hint}"
}
