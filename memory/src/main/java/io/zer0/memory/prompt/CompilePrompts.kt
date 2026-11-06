package io.zer0.memory.prompt

/**
 * Compile el generador de palabras rápidas.
 *
 * Proporciona cuatro compilaciones independientes (today / daily / week / longterm) con un mensaje del sistema de datos editable.
 * La salida se unifica en texto plano (sin encabezados de rebajas), mediante [CompiledMemoryState.normalizeLlmResult] Normalización.
 */
object CompilePrompts {

    /** compile today: ese dia sessions → today.md */
    fun buildTodayPrompt(locale: String = "es"): String {
        val isZh = locale.let { it.startsWith("es") || it.startsWith("zh") }
        if (!isZh) {
            return """
Condense today's conversation summaries into a short list of the user's current state and broad themes.

Rules:
- Group repeated exchanges about the same topic into one entry instead of a line-by-line log
- Use coarse time markers ("morning", "evening", or a rough HH:MM range), not minute-level precision
- The primary job of long-term memory is to maintain an accurate user model: who the user is, what they like, what matters to them, and what they are broadly focused on recently
- Work content may appear only at the broad-theme level: name the domain, project, or topic, not its internal details

May record:
- Identity, personality traits, aesthetics, interests, likes, and dislikes
- Broad themes currently occupying the user, such as "preparing to move", "learning to swim", or "writing a thesis draft"
- Changes in life, creative work, relationships, or long-term attention areas

Do NOT record:
- Execution steps, filenames, tools, commands, check order, collaboration preferences, or work details
- Methodology choices, tool preferences, format requirements, or terminology rules from individual tasks
- Specific subproblems, concrete solutions, concrete code changes, tests, or release flows
- Detailed content produced by the assistant ("wrote an article about X" is enough)
- Revisions, retries, interruptions, and resumptions — treat them as process noise

Output 3 to 5 coarse events, 1 to 2 sentences each, at most 180 words. Keep quiet days short. Do not use Markdown headings or start lines with #, ##, or ###; output body text only.
            """.trimIndent()
        }
        return """
Por favor, prepare un resumen de la conversación de hoy.“Estado del usuario y lista de temas principales”.

Principios de refinación:
- Se combinan varios viajes de ida y vuelta sobre el mismo tema o proyecto en uno y no se registran cuentas corrientes una por una.
- El tiempo utiliza el período principal (“mañana/tarde”o intervalo aproximado HH:MM), no es necesario que sea preciso al minuto
- La tarea principal de la memoria a largo plazo es mantener el modelo de usuario: dando prioridad a registrar quién es el usuario, qué le gusta, qué le importa y a qué ha prestado atención recientemente.
- El contenido del trabajo sólo se conserva hasta el nivel de los grandes temas: escriba sobre las áreas en las que los usuarios han invertido recientemente./Proyecto/Tema, no escribas detalles internos.

Puede grabar:
- Identidad, personalidad, estética, intereses, cosas que te gustan o no te gustan.
- Grandes temas en los que se invierte actualmente, como“Preparándose para mudarse”“Recientemente comencé a aprender a nadar.”“Trabajando en el primer borrador de un artículo esta semana.”
- Cambios en la vida, creatividad, relaciones o enfoque a largo plazo.

No iniciar sesión:
- Pasos de ejecución, nombres de archivos, herramientas, comandos, orden de inspección, preferencias de colaboración, detalles del trabajo
- Opciones metodológicas, preferencias de herramientas, requisitos de formato, reglas terminológicas en una sola tarea
- Subproblemas específicos, soluciones específicas, cambios específicos, pruebas específicas o procesos de lanzamiento
- El contenido específico generado por el asistente (“Generó un artículo sobre X.”Ya es suficiente, no hay extractos del texto)
- Fluctuaciones del proceso, como modificaciones de ida y vuelta, reintentos, interrupciones y recuperación, etc.

Genere de 3 a 5 eventos generales, de 1 a 2 oraciones cada uno, hasta 300 palabras. Si el día es aburrido, escribe breve. No genere títulos de Markdown, no comience con #,##,### Comienzo; generar directamente la lista de texto o párrafo.
        """.trimIndent()
    }

    /** compile daily: hoy borrador para el día terminado/Resumen → daily/{date}.md */
    fun buildDailyPrompt(locale: String = "es"): String {
        val isZh = locale.let { it.startsWith("es") || it.startsWith("zh") }
        if (!isZh) {
            return """
Condense that day's timeline entries or final today draft into a two-to-three-sentence diary entry.

Positioning: this entry feeds a weekly overview; it is not a detailed log. The reader only needs a glance at what broadly happened and what the user focused on that day.

Rules:
- Group repeated exchanges about the same topic into one entry
- Keep a coarse sense of time, such as "morning", "evening", or one representative HH:MM; do not turn it into timeless topic labels
- The primary job of long-term memory is to maintain an accurate user model: who the user is, what they like, what matters to them, and what they focused on that day
- Work content may appear only at the broad-theme level

Do NOT record execution details, methodology choices, tool preferences, format requirements, specific subproblems, concrete solutions, code changes, tests, release flows, assistant output content, or process noise.

Output only two to three sentences, at most 30 words; keep quiet days shorter. Do not add a date heading (the caller adds it) and do not use Markdown headings.
            """.trimIndent()
        }
        return """
Recibirás una entrada de la línea de tiempo o una versión final para este día.“El borrador de hoy.”, resúmalo en una breve entrada de diario de dos o tres oraciones.

Posicionamiento: este es un registro para una descripción general de una semana, no un registro detallado. Los lectores solo necesitan ver de un vistazo qué sucedió ese día y a qué están prestando atención los usuarios.

Principios de refinación:
- Múltiples viajes de ida y vuelta para el mismo tema o proyecto se fusionan en uno
- Mantener un sentido aproximado del tiempo, p.“mañana”“tarde”O un representante HH:MM; no lo escribas como un hashtag sin un ancla de tiempo
- La tarea principal de la memoria a largo plazo es mantener el modelo de usuario: priorizar el registro de quién es el usuario, qué le gusta, qué le importa y a qué presta atención hoy.
- El contenido del trabajo solo se conserva hasta el nivel del gran tema.

No registre pasos de ejecución, nombres de archivos, herramientas, comandos, orden de verificación, preferencias de colaboración, detalles de trabajo, ni registre opciones metodológicas, preferencias de herramientas, requisitos de formato, subproblemas específicos, soluciones específicas, modificaciones específicas, procesos de prueba o lanzamiento, salida de contenido específico por parte del asistente, así como fluctuaciones de procesos como modificaciones de ida y vuelta, reintentos, interrupciones y recuperación.

Genere solo dos o tres oraciones, hasta 60 palabras; más corto en un día normal. No genere encabezados de fecha (la persona que llama los agregará) y no utilice encabezados de Markdown.
        """.trimIndent()
    }

    /** compile week: 7 ventana corredera de día sessions → week.md */
    fun buildWeekPrompt(locale: String = "es"): String {
        val isZh = locale.let { it.startsWith("es") || it.startsWith("zh") }
        if (!isZh) {
            return """
Condense the past 7 days of conversation summaries into a weekly overview of the user's broad themes.

Positioning: at this layer the record is already coarse. It is one level above daily logs: capture what the user broadly focused on, invested time in, and what important changes happened.

Layering:
- The primary job of long-term memory is to maintain an accurate user model
- Work content may appear only at the broad-theme level
- Sustained focus themes come first ("focused on X all week", "spent several days on Y")
- Substantial personal changes, creative themes, relationship changes, or interest changes come second
- Time is vague ("early in the week", "a few days ago", "these last two days"); do not keep exact timestamps

Do NOT keep: execution steps, filenames, tools, commands, check order, collaboration preferences, work details, specific subproblems, concrete solutions, code changes, tests, release flows, task-level methodology or format choices, in-conversation revisions, temporary decisions, assistant output content, or trivial activity.

Record only what the user broadly focused on and what important changes happened this week. Output 3 to 5 themes or events, at most 240 words, without Markdown headings.
            """.trimIndent()
        }
        return """
Por favor, recopile un resumen de las conversaciones de los últimos 7 días.“Resumen de temas de usuarios esta semana”.

Posicionamiento: Esta capa ya está grabada en líneas gruesas, no“cosas que pasan todos los dias”la colección de——A qué están prestando atención los usuarios esta semana, en qué están invirtiendo y qué cambios importantes se han producido.

Nivel:
- La tarea principal de la memoria a largo plazo es mantener el modelo del usuario: quién es el usuario, qué le gusta, qué le importa y a qué ha prestado atención recientemente.
- El contenido del trabajo solo se conserva hasta el nivel del gran tema.
- Temas de preocupación actuales (“Estén atentos esta semana X”“Estoy haciendo esto principalmente estos días. Y”) poner primero
- Suficiente estatus personal, temas creativos, cambios en las relaciones y cambios en los intereses, seguidos de
- El tiempo se expresa vagamente (“Zhou temprano/hace unos dias/estos dos dias”), sin dejar una marca de tiempo precisa

Deje en claro que no debe retener: pasos de ejecución, nombres de archivos, herramientas, comandos, orden de verificación, preferencias de colaboración, detalles del trabajo; subproblemas específicos, soluciones específicas, modificaciones específicas, procesos de prueba o lanzamiento; metodologías, herramientas y opciones de formato en las tareas; modificaciones de ida y vuelta y decisiones ad hoc dentro de una sola conversación; salidas específicas de los asistentes; tareas sin importancia.

grabar solo“A qué suelen prestar atención los usuarios esta semana y qué cambios importantes se han producido”. Salida de 3 a 5 temas para la semana./Evento, máximo 400 palabras, no utilice títulos Markdown.
        """.trimIndent()
    }

    /** compile longterm: week.md fold entrar longterm.md */
    fun buildLongtermPrompt(locale: String = "es"): String {
        val isZh = locale.let { it.startsWith("es") || it.startsWith("zh") }
        if (!isZh) {
            return """
Fold this week's additions into the existing long-term context.

The long-term context is the most stable layer. It records durable identity, personality, aesthetics, values, and long-running focus themes. It does not record transient current state or task details.

Rules:
- Merge new additions into the existing context; do not duplicate
- Keep only durable, stable user attributes
- Drop transient current-state information (it belongs in the today/week layers)
- For the same topic, newer information wins

Output 3 to 6 long-term attributes or themes, at most 240 words, without Markdown headings.
            """.trimIndent()
        }
        return """
Incorpore las nuevas incorporaciones de esta semana a situaciones existentes a largo plazo.

La situación a largo plazo es la capa más estable y registra identidad, personalidad, estética, valores y preocupaciones a largo plazo duraderos; no registra situaciones a corto plazo ni detalles de tareas.

Principios:
- Incorporar contenido nuevo en situaciones existentes a largo plazo sin duplicaciones.
- Conserve únicamente atributos de usuario persistentes y estables
- Descartar el historial breve (pertenece a la capa de hoy/semana)
- El mismo tema está sujeto a nueva información.

Salida 3 a 6 atributos a largo plazo/Tema, máximo 400 palabras, no utilice títulos Markdown.
        """.trimIndent()
    }

    /** compile facts: 30 sección de hechos del resumen del día → facts.md */
    fun buildFactsPrompt(locale: String = "es"): String {
        val isZh = locale.let { it.startsWith("es") || it.startsWith("zh") }
        if (!isZh) {
            return """
Merge new candidate facts into the existing facts list.

Rules:
- Each fact must be atomic: one fact per line
- Merge duplicates and keep the original wording; do not add "the user" as a subject
- When two facts differ only by a subject, keep the shorter subject-less version
- Drop transient facts (task-level or one-time events)
- Keep only durable user-profile facts: identity, preferences, long-term focus
- Format: one fact per line, no bullet prefix, no headings

Output the merged list, at most 100 words, without Markdown headings.
            """.trimIndent()
        }
        return """
Fusionar nuevos hechos candidatos con hechos existentes.

Principios:
- Cada hecho debe ser atómico: uno por línea
- Fusionar duplicados y conservar la representación original; no complementar“Usuario”Asunto
- Cuando la única diferencia entre dos hechos es el tema, conserve la versión más corta sin el tema.
- Descartar hechos efímeros (nivel de tarea, eventos únicos)
- Mantenga solo datos persistentes del perfil del usuario: identidad, preferencias, atención a largo plazo.
- Formato: una línea por línea, sin prefijo de viñetas, sin título

Genere la lista combinada, hasta 200 palabras, y no utilice encabezados de Markdown.
        """.trimIndent()
    }

    /** compile editable facts: Versión de línea de flotación incremental,Igual que compileFacts pero la semántica es"base confiable" */
    fun buildEditableFactsPrompt(locale: String = "es"): String = buildFactsPrompt(locale)
}
