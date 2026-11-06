package io.zer0.muse.tools

/**
 * P1 Verificación del esquema de parámetros de la herramienta.
 *
 * Verificar antes de ejecutar la herramienta:
 * - Faltan parámetros requeridos
 * - Tipo de parámetro y [ToolRegistry.ToolDef.parameterTypes] La declaración es inconsistente (si se puede forzar su transferencia, se obligará a transferirla)
 * - Valor de parámetro JSON ilegal
 *
 * Objetivo: evitar“El modelo pierde la consulta/pasa el número a una cadena”Provoca que el 400 o la herramienta aguas arriba fallen silenciosamente,
 * Deje que LLM reciba mensajes de error estructurados y legibles (details.errorType = INVALID_ARGUMENTS).
 */
object ToolArgValidator {

    const val ERROR_TYPE = "INVALID_ARGUMENTS"

    data class ValidationResult(
        val valid: Boolean,
        val errors: List<String> = emptyList(),
        val coercedArgs: Map<String, String> = emptyMap(),
    ) {
        companion object {
            fun ok(args: Map<String, String>) = ValidationResult(
                valid = true,
                errors = emptyList(),
                coercedArgs = args,
            )

            fun invalid(errors: List<String>, args: Map<String, String>) = ValidationResult(
                valid = false,
                errors = errors,
                coercedArgs = args,
            )
        }
    }

    /**
     * @param def definición de herramienta;null Indica que no está registrado y es manejado por la persona que llama. Extraviado.
     */
    fun validate(name: String, args: Map<String, String>, def: ToolRegistry.ToolDef?): ValidationResult {
        if (def == null) {
            return ValidationResult.invalid(listOf("herramienta desconocida: $name"), args)
        }
        val errors = mutableListOf<String>()
        val coerced = LinkedHashMap<String, String>()

        // Parámetros requeridos
        def.required.forEach { required ->
            val value = args[required]
            if (value.isNullOrBlank()) {
                errors += "Falta el parámetro requerido: $required"
            } else {
                coerced[required] = value
            }
        }

        // Verificación de declaración de tipo (opcional, pero también se debe verificar el valor pasado)
        def.parameterTypes.forEach { (param, typeName) ->
            val raw = args[param] ?: return@forEach
            if (raw.isBlank()) return@forEach
            when (typeName.lowercase()) {
                "integer", "int", "long" -> {
                    val normalized = raw.trim().trim('"')
                    if (normalized.toLongOrNull() == null) {
                        errors += "parámetros $param debería ser $typeName,valor real: $raw"
                    } else {
                        coerced[param] = normalized
                    }
                }
                "number", "float", "double" -> {
                    val normalized = raw.trim().trim('"')
                    if (normalized.toDoubleOrNull() == null) {
                        errors += "parámetros $param debería ser $typeName,valor real: $raw"
                    } else {
                        coerced[param] = normalized
                    }
                }
                "boolean", "bool" -> {
                    val normalized = raw.trim().trim('"')
                    if (normalized !in setOf("true", "false", "1", "0", "si", "No", "yes", "no")) {
                        errors += "parámetros $param debería ser boolean,valor real: $raw"
                    } else {
                        coerced[param] = when (normalized) {
                            "1", "si", "yes" -> "true"
                            "0", "No", "no" -> "false"
                            else -> normalized
                        }
                    }
                }
                "array", "object", "json" -> {
                    // Déjelo como está (análisis interno del ejecutor); solo haga verificación nula.
                    coerced[param] = raw
                }
                else -> {
                    coerced[param] = raw
                }
            }
        }

        // tipo no declarado/Los parámetros no necesarios se transmiten de forma transparente tal como están
        args.forEach { (k, v) ->
            if (!coerced.containsKey(k)) {
                coerced[k] = v
            }
        }

        return if (errors.isEmpty()) {
            ValidationResult.ok(coerced)
        } else {
            ValidationResult.invalid(errors, coerced)
        }
    }
}
