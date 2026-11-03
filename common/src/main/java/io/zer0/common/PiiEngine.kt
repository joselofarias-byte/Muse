package io.zer0.common

/** Pure Kotlin PII rules shared by app and memory facades. */
object PiiEngine {
    enum class AppType { PHONE, EMAIL, ID_CARD, BANK_CARD, IP_ADDRESS, ADDRESS }
    data class AppMatch(val type: AppType, val original: String, val masked: String, val start: Int, val end: Int)

    private fun appPrefix(type: AppType) = if (type == AppType.IP_ADDRESS) "IP" else type.name
    private val appPatterns = linkedMapOf(
        AppType.PHONE to Regex("(?<!\\d)1[3-9](?:[ -]?\\d){9}(?!\\d)"),
        AppType.EMAIL to Regex("(?i)(?<!\\w)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}(?!\\w)"),
        AppType.ID_CARD to Regex("(?<!\\d)(?:[1-9]\\d{5}(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]|[1-9]\\d{5}\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3})(?!\\d)"),
        AppType.BANK_CARD to Regex("(?<!\\d)\\d{16,19}(?!\\d)"),
        AppType.IP_ADDRESS to Regex("(?<!\\d)\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(?!\\d)"),
        AppType.ADDRESS to Regex("(?:provincia|ciudad|Distrito|Condado|ciudad|municipio|pueblo|camino|calle|carril|hacer|habitación|edificio|unidad)\\s*\\d{1,6}(?:[^-\\s]{0,20})"),
    )

    fun detectApp(text: String): List<AppMatch> {
        if (text.isEmpty()) return emptyList()
        val raw = appPatterns.flatMap { (type, regex) ->
            regex.findAll(text).map { AppMatch(type, it.value, "", it.range.first, it.range.last + 1) }
        }.sortedBy { it.start }
        val result = mutableListOf<AppMatch>()
        var lastEnd = -1
        for (match in raw) if (match.start >= lastEnd) {
            result += match
            lastEnd = match.end
        }
        return result
    }

    fun maskApp(text: String): Pair<String, List<AppMatch>> {
        val matches = detectApp(text)
        if (matches.isEmpty()) return text to emptyList()
        val counters = mutableMapOf<AppType, Int>()
        val annotated = matches.map { match ->
            val number = (counters[match.type] ?: 0) + 1
            counters[match.type] = number
            match.copy(masked = "[${appPrefix(match.type)}_%03d]".format(number))
        }
        val result = StringBuilder(text.length + annotated.size * 12)
        var cursor = 0
        for (match in annotated) {
            result.append(text, cursor, match.start)
            result.append(match.masked)
            cursor = match.end
        }
        result.append(text, cursor, text.length)
        return result.toString() to annotated
    }

    fun unmaskApp(text: String, matches: List<AppMatch>): String {
        if (matches.isEmpty()) return text
        var result = text
        for (match in matches) if (match.masked.isNotEmpty()) result = result.replace(match.masked, match.original)
        return result
    }

    private const val SURNAMES = "Wang Li Zhang Liu Chen Yang Zhao Huang Zhou Wu Xu Sun Hu Zhu Gaolin He Guo Maluo Liang Song Zheng Xie Han Tang Feng Yu Dong Xiao Cheng Cao Yuan Deng Xu Fu Shen Zeng Peng Lu Sulu Jiang Cai Jia Ding Wei Xue Ye Yan Yu Pan Du Dai Xia Zhong Wang Tian Ren Jiang Fan Fang Shi Yao Tan Liao Zou Xiong Jin Lu Hao Kong Bai Cui Kang Mao Qiu Qin Jiang Shi Gu Hou Meng Shao Long Wan Duan Lei Qian Tang Yin Li Yi Chang Wu Qiao He Lai Gong Wen"
    private const val TITLES = "viejo maestro|maestro|doctor|enfermera|camarada|compañeros de clase|señor|dama|maestro|gerente|Directora|profesor|jefe|director de fábrica|decano|Director|abogado|entrenador|presidente|presidente|mentor|consultor|contabilidad|tío|tía|abuelo|abuela|hermano|hermana"
    private val whitelist =
        setOf("entender", "entender", "pregunta", "propósito", "método", "noticias", "dirección", "Título", "empezar", "fin", "arreglar", "diseño", "desarrollar", "darse cuenta", "actualizar", "Reparación", "Completo", "Cancelar", "Confirmar", "posible", "OK", "debería", "porque", "entonces", "si", "entonces", "pero", "que", "como", "De esta manera", "de esa manera", "esto", "eso", "nosotros", "tu", "ellos", "yo mismo", "amigos", "familia", "nombre", "cumpleaños", "me gusta", "odio", "trabajo", "aprender", "vida", "memoria", "sistema", "Proyecto", "equipo", "cultura", "Artículo", "reino", "civilización", "literatura", "texto", "talento literario", "Gramática", "Literatura", "Manuscrito", "estilo de escritura", "Antología", "estilo de escritura", "Chino clásico", "elegante", "sentido de dirección", "metodología", "metodología", "ubicación", "dirección", "tratamiento", "Ley aplicable", "ley y orden", "gobernanza", "curar la enfermedad", "programa", "proceso", "programático", "proceso", "ingeniero", "Ingeniería", "distancia", "viajar", "cumplir", "militar", "armas", "armado", "artes marciales", "economía", "negocio", "gerente", "gestión", "Razón", "Moralidad", "lógica", "espíritu", "esencia", "Exquisito", "maravilloso", "elfo", "líder", "campo", "territorio", "Flexibles", "liderazgo", "Liu Bang", "Liu Bei", "Archivo", "Tarea", "declaración de misión", "cualquiera", "dónde", "Personal", "personajes", "humano", "Talento", "dinero", "Finanzas", "bóveda", "metales", "piedra", "estela", "aceite", "fósil", "larga marcha", "Río Yangtsé", "Gran Muralla", "carrera de larga distancia", "mano de obra", "trabajador modelo", "seguro", "Tranquilidad", "estable", "tranquilo", "eliminar", "extinción de incendios", "consumo", "digerir", "Nankín", "Nanning", "Nantong", "sur", "ir al norte", "Pekín", "norte", "mostrar", "sauce", "álamo", "sauce", "autoridad", "poder", "Equidad", "derechos", "Estado de Wei", "pico", "escuela secundaria", "Gao Jian", "feliz", "elegante", "Avanzado", "tren de alta velocidad", "alta presión", "alto o bajo", "Guo Jing", "Guo Jingming", "Teresa Teng", "programador", "grado", "muebles", "idioma wu", "Las suaves palabras de Wu Nong", "margen", "rico", "rico", "camino", "relación", "Seguir", "Acerca de", "preocupación", "momento critico", "soldado", "Desempleado", "Demostración", "Demostración", "abierto", "cambiar", "abierto", "agua hirviendo", "poesía tanga", "abrupto", "Proceso", "Desechar", "hacer cosas", "en todas partes", "prescripción", "melodía", "viajar", "viaje", "diariamente", "dias", "amanecer", "Fecha", "Uso diario", "luna", "luz de la luna", "menstruación", "fin de semana", "pensativo", "ocupado", "brillante", "claro", "obvio", "luna brillante", "estrella", "sabio", "celebridad", "lugar de interés", "citas famosas", "obra maestra", "fama", "Nombre", "Lista", "Clasificación", "Calificado", "razonable", "cooperación", "total", "adecuado", "recuerda", "grabar", "nota", "conmemorar", "disciplina", "aniversario", "siglo", "el mundo", "generaciones", "carrera", "evento", "escrituras", "hechos", "cosas", "cosas", "importa", "importa", "vale la pena", "directo", "hasta", "Responsabilidades", "carrera", "Posición", "Posición", "hoy", "clima", "cálido", "leve", "gentil", "montañas", "feliz", "silencio", "profundo", "profundo", "profundo", "pensamiento profundo", "claro", "claro", "clásico", "pasó", "negocio", "resultado", "Conclusión", "fin", "combinar", "casarse", "nervioso", "urgente", "Requisitos estrictos", "serio", "estricto", "serio", "invierno severo", "principal", "Toma la iniciativa", "Tema", "importante", "Repetir", "renacimiento", "Chongqing")
    private data class Rule(val label: String, val regex: Regex, val protected: Boolean = false)
    private val rules by lazy {
        listOf(
            Rule(
                "api_key",
                Regex(
                    "(?i)(?:sk-[a-z0-9_]{20,}|AKIA[0-9A-Z]{16}|gsk_[a-z0-9_]{20,}|ghp_[a-z0-9]{36}|glpat-[a-z0-9_-]{20,}|xoxb-[0-9a-z]+)",
                ),
            ),
            Rule(
                "inline_secret",
                Regex("(?i)(?:api[_-]?key|secret[_-]?key|access[_-]?token|auth[_-]?token|password)\\s*[:=]\\s*[a-z0-9_/+=.\\-]{16,}"),
            ),
            Rule(
                "private_key",
                Regex(
                    "-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----.*?-----END (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----",
                    setOf(RegexOption.DOT_MATCHES_ALL),
                ),
            ),
            Rule(
                "id_card",
                Regex(
                    "(?<!\\d)(?:[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx]|[1-9]\\d{5}\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3})(?!\\d)",
                ),
            ),
            Rule("credit_card", Regex("(?<!\\d)(?:\\d{4}[- ]?){3}\\d{4,7}(?!\\d)")),
            Rule("ssn", Regex("(?<!\\d)\\d{3}-\\d{2}-\\d{4}(?!\\d)")),
            Rule("email", Regex("(?i)(?<!\\w)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}(?!\\w)")),
            Rule("phone", Regex("(?<!\\d)1[3-9](?:[ -]?\\d){9}(?!\\d)")),
            Rule("ipv4", Regex("(?<!\\d)(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(?!\\d)")),
            Rule(
                "address",
                Regex(
                    "[\\p{IsHan}]{2,12}(?:provincia|región autónoma|ciudad|prefectura autónoma|área|alianza)[\\p{IsHan}]{2,12}(?:Distrito|Condado|bandera|ciudad|ciudad|municipio|calle|camino|calle|carril|hacer|pueblo)(?:\\d{1,6}No.?)?(?:[\\p{IsHan}\\d-]{0,20})?",
                ),
            ),
            Rule(
                "name",
                Regex("(?:[$SURNAMES](?:$TITLES)|(?:llamar|Apellido|Nombre|llamado|llamado|llamar|llamado|título|nombre)\\s*[$SURNAMES][\\p{IsHan}]{1,2}(?![\\p{IsHan}]))"),
                true,
            ),
            Rule("english_name", Regex("\\b[A-Z][a-z]{1,12}\\s+[A-Z][a-z]{1,12}\\b"), true),
        )
    }
    data class ScrubResult(val cleaned: String, val detected: List<String>)
    data class MaskResult(val masked: String, val map: Map<String, String>)
    private val residual =
        Regex("\\[(?:API_KEY|INLINE_SECRET|PRIVATE_KEY|ID_CARD|CREDIT_CARD|SSN|EMAIL|PHONE|IPV4|ADDRESS|NAME|ENGLISH_NAME)_\\d+\\]")

    fun scrubMemory(text: String): ScrubResult {
        if (text.isEmpty()) return ScrubResult(text, emptyList())
        var current = text
        val detected = mutableListOf<String>()
        for (rule in rules) {
            if (!rule.regex.containsMatchIn(current)) continue
            current = rule.regex.replace(current) { if (rule.protected && it.value in whitelist) it.value else "[REDACTED]" }
            if (rule.label !in detected) detected += rule.label
        }
        return ScrubResult(current, detected)
    }
    fun maskMemory(text: String): MaskResult {
        if (text.isEmpty()) return MaskResult(text, emptyMap())
        var current = text
        val map = linkedMapOf<String, String>()
        for (rule in rules) current = rule.regex.replace(current) {
            if (rule.protected && it.value in whitelist) it.value else "[${rule.label.uppercase()}_${map.size + 1}]".also { token -> map[token] = it.value }
        }
        return MaskResult(current, map)
    }
    fun unmaskMemory(text: String, map: Map<String, String>): String {
        var current = text
        for ((token, original) in map) current = current.replace(token, original)
        return residual.replace(current, "")
    }
}
