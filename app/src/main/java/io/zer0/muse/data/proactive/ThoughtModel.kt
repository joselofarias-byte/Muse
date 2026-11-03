package io.zer0.muse.data.proactive

/**
 * La naturaleza de la preocupación.
 *
 * La materia prima de los mensajes proactivos no es “se acabó el tiempo”, sino “hay algo que quiero decirle”.
 * Esta enumeración responde "cuál es la naturaleza de esa cosa".
 *
 * Las cinco propiedades tienen tonos completamente diferentes al generar indicaciones:
 * Los recordatorios deben ser breves y directos, la atención debe dejarse en blanco y el intercambio debe ser informativo.
 */
enum class ThoughtKind {
    /** Cosas específicas que el usuario dijo que haría. Tenga un momento o fecha límite claro. */
    REMINDER,

    /** No hay temas cerrados en la conversación. El usuario lo mencionó una vez, pero luego no se dijo nada al respecto. */
    FOLLOW_UP,

    /** Algo sobre el estado de esta persona. No dormir bien, enfermo, estresado. */
    CARE,

    /** Quiero compartir mi versión de las cosas. Debe ser informativo, no un saludo vacío. */
    SHARE,

    /** Un nodo en una relación o uso. Úselo con moderación, si lo usa demasiado será una transmisión de logros. */
    MILESTONE,
}

/**
 * Tipo de limitación de interés.
 *
 * Esta es la distinción más crítica en todo el sistema de programación:
 * "No tiene sentido decirlo después de este punto" y "Se puede decir en cualquier momento" son cosas completamente diferentes.
 * La implementación anterior consideraba que los dos eran iguales, por lo que se producía el comportamiento de puntuación negativa de "reemitir recordatorios caducados".
 */
enum class ThoughtUrgency {
    /**
     * No tiene sentido pasar por la ventana. Caducará cuando llegue el momento.**Nunca reeditar**.
     *
     * Típico: "Recuérdele que vea la transmisión en vivo esta noche a las 8 en punto". Al día siguiente agregué: "¿Viste la transmisión en vivo de anoche?"
     * Peor que no enviarlo —— Revela que el sistema finge tener memoria.
     */
    IMMEDIATE,

    /** Es apropiado decirlo por un período de tiempo. Selección libre dentro de la ventana, expiró fuera de la ventana. */
    WINDOW,

    /** Puedes decirlo en cualquier momento. No caduca, pero decae con SurfaceCount. */
    EVERGREEN,
}

/** Estado del ciclo de vida del cuidado. */
enum class ThoughtStatus {
    /** Aún no lo he dicho. Sólo se programará este estado. */
    OPEN,

    /** Dicho esto, ya está. */
    DONE,

    /** El plazo de prescripción pasó y no hubo tiempo para decir nada. */
    EXPIRED,

    /** Renunciar voluntariamente. Por ejemplo, el mismo tema se ha mencionado demasiadas veces y no ha habido respuesta. */
    DROPPED,
}

/** La fuente de preocupación se utiliza para recordar \"cuánto me preocupaba esto\". */
enum class ThoughtSource {
    /** El propio asistente anotó la conversación. fuente principal. */
    CONVERSATION,

    /** Deducido del sistema de memoria según reglas. */
    MEMORY,

    /** Derivado de hitos. */
    MILESTONE,

    /** El usuario acepta activamente. Máxima prioridad. */
    USER_DEFINED,
}

/**
 * Nivel de iniciativa —— Los usuarios sólo necesitan expresar "qué tan activo quieren que sea".
 *
 * Esta enumeración controla dos cosas:
 *  1. rango de presupuesto ([floor] / [cap])
 *  2. Tipos de inquietudes que se permiten expresar ([allowedKinds])
 *
 * La segunda es la diferencia clave: "Silencio" no es una frecuencia menor, sino una semántica diferente. ——
 * Di sólo lo que hay que decir, no lo que quieras decir. Por lo que corta directamente SHARE/MILESTONE y similares
 * Tipos de "quiero hablar", en lugar de reducir su frecuencia a un nivel muy bajo.
 *
 * Solía haber otro aquí. `threshold`(fitness Umbral), utilizado para filtrar el contenido clasificado por debajo del umbral.
 * El sistema de puntuación se eliminó por completo cuando se reconstruyó el mensaje activo (en su lugar, la conversación más reciente se entregó al modelo, y este decidió si hablar o no).
 * El umbral perderá entonces su lado consumista, y mantenerlo sólo hará que más tarde la gente piense que todavía hay algo en juego.
 *
 * con aprendizaje a largo plazo [ProactiveRelationEntity.intimacy] División del trabajo:
 * Esta enumeración proporciona directamente el número máximo diario de elementos (silencioso 3 / moderación 8 / estándar 15 / activo 30 / sin límite de apego),
 * Es el seleccionado por el usuario. En la versión anterior, se utilizaba "nivel de piso + valor de aprendizaje de intimidad" para competir por la cuota, lo que resultaba en
 * Los usuarios han optado por ser pegajosos pero sólo pueden publicar 5 mensajes por día, lo cual ha sido abandonado.
 */
enum class ProactiveIntensity(
    /** La cantidad máxima de mensajes que se pueden enviar por día (el archivo fijo es Int.MAX_VALUE, es decir, no hay límite). */
    val cap: Int,
    /** Este nivel permite expresar el tipo de preocupación. */
    val allowedKinds: Set<ThoughtKind>,
) {
    /** Sólo un recordatorio, no una pequeña charla. Máximo 3 entradas por día. */
    QUIET(
        cap = 3,
        allowedKinds = setOf(ThoughtKind.REMINDER),
    ),

    /** Es agradable pensar en ello de vez en cuando. Máximo 8 entradas por día. */
    RESERVED(
        cap = 8,
        allowedKinds = setOf(ThoughtKind.REMINDER, ThoughtKind.FOLLOW_UP, ThoughtKind.CARE),
    ),

    /** El valor predeterminado para la mayoría de las personas. Máximo 15 por día. */
    STANDARD(
        cap = 15,
        allowedKinds = ThoughtKind.entries.toSet(),
    ),

    /** Me gusta interactuar. Máximo 30 entradas por día. */
    ACTIVE(
        cap = 30,
        allowedKinds = ThoughtKind.entries.toSet(),
    ),

    /** Quiere una sensación de compañerismo: no hay límite en el número de veces. */
    CLINGY(
        cap = Int.MAX_VALUE,
        allowedKinds = ThoughtKind.entries.toSet(),
    ),

    ;

    /** Si no hay límite para el número de veces (actualmente solo existe el modo de adherencia). */
    val unlimited: Boolean get() = cap == Int.MAX_VALUE

    /** Si el tipo permite el habla a este nivel. */
    fun allows(kind: ThoughtKind): Boolean = kind in allowedKinds

    /** ¿Este nivel es “solo decir lo que hay que decir”?—— No debes hablar sin una razón específica. */
    val onlyWhenNeeded: Boolean get() = this == QUIET

    companion object {
        /** Equipo predeterminado. */
        val DEFAULT = STANDARD

        /**
         * Restaurar desde una cadena persistente, los valores ilegales vuelven al archivo predeterminado.
         *
         * Utilice el nombre en lugar del número de serie: el número de serie se perderá a medida que se agregue o elimine la enumeración.
         * Después de la actualización, los usuarios pueden cambiar de "estándar" a "fijo".
         */
        fun fromName(name: String?): ProactiveIntensity =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
