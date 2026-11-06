package io.zer0.muse.transformer

import io.zer0.common.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 9.2 (M22): Motor de plantillas ligero compatible con Pebble(Implementación independiente,Cero dependencias).
 *
 * Según la especificación de sintaxis de los documentos públicos de Pebble.,subconjunto autoimplementable,No introducir dependencias de Pebble
 * (Pebble es una biblioteca JVM,Android Incompatible y aumentará el tamaño del APK).
 *
 * Sintaxis de soporte(Subconjunto de guijarros de referencia):
 *  - variable: {{ var }} / {{ user.name }}(Acceso a la ruta del número de punto Map)
 *  - literal: 'string' / "string" / 123 / 1.5 / true / false / null
 *  - filtrar(cadena de tubo):
 *      {{ var | upper }}                  Convertir a mayúsculas
 *      {{ var | lower }}                  Convertir a minúsculas
 *      {{ var | length }}                 longitud(CharSequence/Collection/Map)
 *      {{ var | default('foo') }}         Retorno cuando es nulo/vacío
 *      {{ var | trim }}                   Eliminar los espacios en blanco iniciales y finales
 *      {{ list | join(', ') }}            Unirse con delimitador
 *      {{ var | replace('a', 'b') }}      reemplazar
 *      {{ var | capitalize }}             Poner en mayúscula la primera letra
 *      {{ list | first }} / {{ list | last }}  toma el primero/cola
 *  - Condiciones:
 *      {% if cond %}...{% elif cond2 %}...{% else %}...{% endif %}
 *      expresión condicional: ==  !=  >  <  >=  <=  and  or  not
 *      prueba: x is defined / x is empty / x is null / x is even / x is odd
 *      prueba negativa: x is not empty
 *  - bucle:
 *      {% for item in items %}...{% endfor %}
 *      loop variables de contexto:
 *        {{ loop.index }}    1-based número de serie
 *        {{ loop.index0 }}   0-based número de serie
 *        {{ loop.first }}    ¿Es el primero?
 *        {{ loop.last }}     ¿Es el último?
 *        {{ loop.length }}   longitud total
 *  - Comentario: {# comment #}(Descartar sin salida)
 *
 * Uso:
 * ```
 * val engine = PebbleTemplateEngine()
 * val out = engine.render(
 *     "Hola {{ name }},hoy {% if weather == 'rain' %}lloviendo{% else %}claro{% endif %}",
 *     mapOf("name" to "Alice", "weather" to "rain"),
 * )
 * // → "Hola Alice,esta lloviendo hoy"
 * ```
 *
 * principios de diseño:
 *  - Cero dependencias(puro Kotlin,No importar la biblioteca Pebble/Jinja)
 *  - seguro(Caja de arena de plantilla,Sin reflexión ni ejecución de código.)
 *  - conciso(archivo único ~700 OK)
 *  - Pebble Compatible con sintaxis(Facilite a los usuarios hacer clic en documentos de Pebble)
 *
 * Protección de seguridad(M-TPL1/M-TPL2/M-PE1):
 *  - AST caché: La misma cadena de plantilla solo es lex+parse una vez(M-TPL1)
 *  - Número máximo de iteraciones: for El bucle se repite como máximo MAX_ITERACIONES veces(M-TPL2,DoS protección)
 *  - Límite de profundidad de anidación: Parser/Evaluator No anidar más de MAX_NESTING_DEPTH niveles(M-PE1,StackOverflow protección)
 *
 * pozo de pai:
 *  - Lexer Utilice coincidencias codiciosas {{ }} / {% %} / {# #},No se admite el anidamiento
 *  - Análisis de expresiones mediante descenso recursivo,prioridad: or < and < not < is < compare < filter < primary
 *  - Se accede a las rutas variables mediante notación de puntos. Map;Objeto que no es de mapa devuelto null(No reflectante)
 *  - truthy reglas: null/vacío/0/false → false,Otros → true
 */
class PebbleTemplateEngine {

    // M-TPL1: AST caché,Tome la cadena de plantilla como key,Evite volver a lex+parse
    private val astCache = ConcurrentHashMap<String, List<Node>>()

    /**
     * Plantilla de renderizado.
     *
     * @param template cadena de plantilla
     * @param context  mapeo de variables(Anidamiento de soporte Map,Como por ejemplo mapOf("user" to mapOf("name" to "Alice")))
     * @return Cadena renderizada
     */
    fun render(template: String, context: Map<String, Any?>): String {
        val ast = astCache.computeIfAbsent(template) {
            val tokens = Lexer(it).tokenize()
            Parser(tokens).parse()
        }
        return Evaluator(context).eval(ast)
    }
}

// ============================================================================
// constante(M-TPL2/M-PE1 límite superior de seguridad)
// ============================================================================

/** M-PE1: Parser/Evaluator Profundidad máxima de anidación,Evite StackOverflow profundamente anidado. */
private const val MAX_NESTING_DEPTH = 50

/** M-TPL2: for Número máximo de iteraciones de bucle,Prevenir DoS. */
private const val MAX_ITERATIONS = 10000

// ============================================================================
// Lexer — Análisis léxico
// ============================================================================

private enum class TokenType { TEXT, VAR, TAG }

// L-PE9: Token llevar número de línea,Parser Incluido al informar un error
private data class Token(val type: TokenType, val content: String, val line: Int)

/**
 * Divida la cadena de plantilla en una lista de tokens.
 * - TEXT: texto plano(Salida tal cual)
 * - VAR: `{{ expr }}`(Salida después de la evaluación de la expresión)
 * - TAG: `{% statement %}`(controlar el flujo,Sin salida)
 * - Comentario `{# ... #}` Descartar directamente
 */
private class Lexer(private val src: String) {
    private val out = mutableListOf<Token>()
    private var i = 0
    private var line = 1 // L-PE9: Seguimiento del número de línea(1-based)

    fun tokenize(): List<Token> {
        val text = StringBuilder()
        while (i < src.length) {
            when {
                match("{{") -> {
                    flushText(text)
                    val tokenLine = line
                    out.add(Token(TokenType.VAR, readUntil("}}").trim(), tokenLine))
                }
                match("{%") -> {
                    flushText(text)
                    val tokenLine = line
                    out.add(Token(TokenType.TAG, readUntil("%}").trim(), tokenLine))
                }
                match("{#") -> {
                    flushText(text)
                    readUntil("#}") // Comentario descartado
                }
                else -> {
                    if (src[i] == '\n') line++
                    text.append(src[i])
                    i++
                }
            }
        }
        flushText(text)
        return out
    }

    private fun match(s: String): Boolean {
        if (i + s.length > src.length) return false
        if (src.substring(i, i + s.length) != s) return false
        i += s.length
        return true
    }

    // L-PE8: Identificar delimitadores dentro de cadenas literales,omitir citas }} / %} / #}
    private fun readUntil(end: String): String {
        val start = i
        while (i + end.length <= src.length && src.substring(i, i + end.length) != end) {
            val c = src[i]
            if (c == '\'' || c == '"') {
                // Saltar literales de cadena(No se reconocen los delimitadores entre comillas)
                val quote = c
                i++ // Consumir citas de apertura
                while (i < src.length && src[i] != quote) {
                    if (src[i] == '\n') line++
                    if (src[i] == '\\' && i + 1 < src.length) i += 2 else i++
                }
                if (i < src.length) {
                    if (src[i] == '\n') line++
                    i++ // Consumir cotizaciones finales
                }
            } else {
                if (c == '\n') line++
                i++
            }
        }
        val content = if (i + end.length <= src.length) src.substring(start, i) else src.substring(start)
        i += end.length
        return content
    }

    private fun flushText(b: StringBuilder) {
        if (b.isNotEmpty()) {
            out.add(Token(TokenType.TEXT, b.toString(), line))
            b.clear()
        }
    }
}

// ============================================================================
// AST nodo
// ============================================================================

private sealed class Node {
    /** Nodo de texto plano. */
    data class Text(val text: String) : Node()

    /** Nodo de salida de expresión. */
    data class Output(val expr: Expr) : Node()

    /**
     * if/elif/else nodo de condición.
     * - branches si (Condiciones?, body) lista,Si la condición es nula, significa otra rama.
     * - Al evaluar, encuentre la primera rama cuya condición sea verdadera en orden.,ejecutar su body
     */
    data class If(val branches: List<Pair<Expr?, List<Node>>>) : Node()

    /**
     * for Nodo de bucle.
     * - item es el nombre de la variable del bucle
     * - list es la expresión que se va a iterar(Necesito ser Iterable)
     * - body es el cuerpo del bucle(Variables de elementos y bucles accesibles)
     */
    data class For(val item: String, val list: Expr, val body: List<Node>) : Node()

    /** Phase 12: {% set var = expr %} ???????*/
    data class Set(val name: String, val value: Expr) : Node()
}

private sealed class Expr {
    /** literal(cuerda/numeros/Booleano/null). */
    data class Literal(val value: Any?) : Expr()

    /** ruta variable(Como por ejemplo user.name → ["user", "name"]). */
    data class Var(val path: List<String>) : Expr()

    /** filtrar llamada(input | filter_name(args)). */
    data class Filter(val input: Expr, val name: String, val args: List<Expr>) : Expr()

    /** operaciones binarias(==/!=/>/</>=/<=/and/or). */
    data class Binary(val op: String, val left: Expr, val right: Expr) : Expr()

    /** Operaciones unarias(not). */
    data class Unary(val op: String, val operand: Expr) : Expr()

    /** prueba(is defined / is empty / is null / is even / is odd). */
    data class Test(val operand: Expr, val name: String, val negated: Boolean) : Expr()
}

// ============================================================================
// Parser — análisis de sintaxis(descenso recursivo)
// ============================================================================

/**
 * Analice la lista de tokens en una lista de nodos AST.
 * - llamada de alto nivel parse() Regresar List<Node>
 * - parseNodes(stopOn) Devuelto cuando se analiza la palabra clave stopOn(para si/para anidar)
 *
 * M-PE1: Trazado de profundidad anidado,Superar MAX_NESTING_DEPTH genera IllegalStateException.
 */
private class Parser(private val tokens: List<Token>) {
    private var i = 0
    private var depth = 0 // M-PE1: Trazado de profundidad anidado

    fun parse(): List<Node> = parseNodes(stopOn = null)

    private fun parseNodes(stopOn: ((String) -> Boolean)?): List<Node> {
        depth++
        if (depth > MAX_NESTING_DEPTH) {
            throw IllegalStateException("La profundidad de anidamiento de la plantilla supera el límite superior($MAX_NESTING_DEPTH)")
        }
        try {
            val nodes = mutableListOf<Node>()
            while (i < tokens.size) {
                val t = tokens[i]
                when (t.type) {
                    TokenType.TEXT -> {
                        nodes.add(Node.Text(t.content))
                        i++
                    }
                    TokenType.VAR -> {
                        nodes.add(Node.Output(ExprParser(t.content).parse()))
                        i++
                    }
                    TokenType.TAG -> {
                        val keyword = t.content.split(WHITESPACE_REGEX).firstOrNull() ?: ""
                        if (stopOn != null && stopOn(keyword)) return nodes
                        when (keyword) {
                            "if" -> {
                                i++ // consumir if token
                                nodes.add(parseIf(t))
                            }
                            "for" -> {
                                i++ // consumir for token
                                nodes.add(parseFor(t))
                            }
                            "set" -> {
                                i++
                                nodes.add(parseSet(t))
                            }
                            else -> throw IllegalStateException("Etiqueta de plantilla desconocida: $keyword (OK ${t.line}, contenido completo: ${t.content})")
                        }
                    }
                }
            }
            return nodes
        } finally {
            depth--
        }
    }

    /** Analizar bloques if/elif/else/endif.i El token if se ha consumido. */
    private fun parseIf(ifToken: Token): Node.If {
        val branches = mutableListOf<Pair<Expr?, List<Node>>>()
        // if rama
        val ifCond = ExprParser(ifToken.content.removePrefix("if").trim()).parse()
        val ifBody = parseNodes(stopOn = { it == "elif" || it == "else" || it == "endif" })
        branches.add(ifCond to ifBody)

        // elif / else / endif Procesamiento de palabras clave de rama
        while (i < tokens.size && tokens[i].type == TokenType.TAG) {
            val content = tokens[i].content
            val kw = content.split(WHITESPACE_REGEX).first()
            when (kw) {
                "elif" -> {
                    i++ // consumir elif token
                    val cond = ExprParser(content.removePrefix("elif").trim()).parse()
                    val body = parseNodes(stopOn = { it == "elif" || it == "else" || it == "endif" })
                    branches.add(cond to body)
                }
                "else" -> {
                    i++ // consumir else token
                    val body = parseNodes(stopOn = { it == "endif" })
                    branches.add(null to body)
                }
                "endif" -> {
                    i++ // consumir endif token
                    break
                }
                else -> break
            }
        }
        return Node.If(branches)
    }

    /** Analizar bloques para/finalizar.i porque el token se ha consumido. */
    private fun parseFor(forToken: Token): Node.For {
        // Gramática: for <var> in <expr>
        val m = FOR_REGEX.matchEntire(forToken.content)
            ?: throw IllegalStateException("No válido para la sintaxis (OK ${forToken.line}): ${forToken.content}")
        val item = m.groupValues[1]
        val listExpr = ExprParser(m.groupValues[2].trim()).parse()
        val body = parseNodes(stopOn = { it == "endfor" })
        // consumir endfor
        if (i < tokens.size && tokens[i].type == TokenType.TAG && tokens[i].content.startsWith("endfor")) {
            i++
        }
        return Node.For(item, listExpr, body)
    }

    /** Phase 12: ?? {% set var = expr %}?*/
    private fun parseSet(setToken: Token): Node.Set {
        val content = setToken.content.removePrefix("set").trim()
        val eqIdx = content.indexOf('=')
        if (eqIdx < 0) {
            throw IllegalStateException("?? set ?? (?${setToken.line}): ${setToken.content}")
        }
        val name = content.substring(0, eqIdx).trim()
        if (name.isEmpty() || !name.all { it.isLetterOrDigit() || it == '_' }) {
            throw IllegalStateException("?? set ??? (?${setToken.line}): '$name'")
        }
        val exprStr = content.substring(eqIdx + 1).trim()
        val expr = ExprParser(exprStr).parse()
        return Node.Set(name, expr)
    }

    companion object {
        // L-PE3: Regex Extraer la constante del objeto complementario,Evite la compilación repetida
        val WHITESPACE_REGEX = Regex("\\s+")
        val FOR_REGEX = Regex("""for\s+(\w+)\s+in\s+(.+)""", RegexOption.DOT_MATCHES_ALL)
    }
}

// ============================================================================
// ExprParser — Análisis de expresiones(descenso recursivo,prioridad climbing)
// ============================================================================

/**
 * Analizador de expresiones.
 *
 * prioridad(de menor a mayor):
 *   or  <  and  <  not  <  is(test)  <  compare(==/!=/>/</>=/<=)  <  filter(|)  <  primary(de menor a mayor)
 *
 * Ejemplo:`a == b or c is empty and not d`
 *   → or(and(is(c, empty, _), not(d)), ==(a, b))
 */
private class ExprParser(private val src: String) {
    private var pos = 0

    fun parse(): Expr {
        val e = parseOr()
        skipSpace()
        if (pos < src.length) {
            throw IllegalStateException("La expresión no se ha consumido.: restante '${src.substring(pos)}'")
        }
        return e
    }

    private fun parseOr(): Expr {
        var left = parseAnd()
        while (matchKeyword("or")) {
            val right = parseAnd()
            left = Expr.Binary("or", left, right)
        }
        return left
    }

    private fun parseAnd(): Expr {
        var left = parseNot()
        while (matchKeyword("and")) {
            val right = parseNot()
            left = Expr.Binary("and", left, right)
        }
        return left
    }

    private fun parseNot(): Expr {
        if (matchKeyword("not")) {
            return Expr.Unary("not", parseNot())
        }
        return parseTest()
    }

    private fun parseTest(): Expr {
        val left = parseCompare()
        skipSpace()
        if (matchKeyword("is")) {
            val negated = matchKeyword("not")
            val name = readWord()
            return Expr.Test(left, name, negated)
        }
        return left
    }

    private fun parseCompare(): Expr {
        val left = parseFilter()
        skipSpace()
        val op = matchCompareOp() ?: return left
        val right = parseFilter()
        return Expr.Binary(op, left, right)
    }

    private fun parseFilter(): Expr {
        var left = parsePrimary()
        skipSpace()
        while (pos < src.length && src[pos] == '|') {
            pos++ // consumir |
            skipSpace()
            val name = readWord()
            val args = mutableListOf<Expr>()
            skipSpace()
            if (pos < src.length && src[pos] == '(') {
                pos++ // consumir (
                skipSpace()
                while (pos < src.length && src[pos] != ')') {
                    args.add(parsePrimary())
                    skipSpace()
                    if (pos < src.length && src[pos] == ',') {
                        pos++
                        skipSpace()
                    }
                }
                if (pos < src.length && src[pos] == ')') pos++ // consumir )
            }
            left = Expr.Filter(left, name, args)
            skipSpace()
        }
        return left
    }

    private fun parsePrimary(): Expr {
        skipSpace()
        if (pos >= src.length) throw IllegalStateException("La expresión terminó inesperadamente")
        val c = src[pos]
        // literal de cadena
        if (c == '\'' || c == '"') return Expr.Literal(readString())
        // literal numérico
        if (c.isDigit() || (c == '-' && pos + 1 < src.length && src[pos + 1].isDigit())) {
            return Expr.Literal(readNumber())
        }
        // agrupación de corchetes
        if (c == '(') {
            pos++ // consumir (
            val e = parseOr()
            skipSpace()
            if (pos < src.length && src[pos] == ')') pos++ // consumir )
            return e
        }
        // ruta de palabra clave/variable
        val word = peekWord()
        when (word) {
            "true" -> {
                pos += 4
                return Expr.Literal(true)
            }
            "false" -> {
                pos += 5
                return Expr.Literal(false)
            }
            "null", "none" -> {
                pos += 4
                return Expr.Literal(null)
            }
        }
        if (word.isNotEmpty() && (word.first().isLetter() || word.first() == '_')) {
            pos += word.length
            val path = mutableListOf(word)
            while (pos < src.length && src[pos] == '.') {
                pos++ // consumir .
                val seg = readWord()
                path.add(seg)
            }
            return Expr.Var(path)
        }
        throw IllegalStateException("No se puede analizar la expresión: '${src.substring(pos)}'")
    }

    // ---- Función auxiliar ----

    private fun skipSpace() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    // L-PE7: Eliminar peek() y peek(offset) código muerto

    /** mira la palabra actual(No consumir),palabra = letras/numeros/Subrayar. */
    private fun peekWord(): String {
        var j = pos
        while (j < src.length && (src[j].isLetterOrDigit() || src[j] == '_')) j++
        return src.substring(pos, j)
    }

    /** leer una palabra(consumo),palabra = letras/numeros/Subrayar. */
    private fun readWord(): String {
        skipSpace()
        val start = pos
        while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
        return src.substring(start, pos)
    }

    private fun readString(): String {
        val quote = src[pos]
        pos++ // Consumir citas de apertura
        val sb = StringBuilder()
        while (pos < src.length && src[pos] != quote) {
            if (src[pos] == '\\' && pos + 1 < src.length) {
                // escapar:\' → '  \" → "  \\ → \  \n → nueva línea  \t → tab
                pos++
                when (src[pos]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    else -> sb.append(src[pos])
                }
            } else {
                sb.append(src[pos])
            }
            pos++
        }
        if (pos < src.length) pos++ // Consumir cotizaciones finales
        return sb.toString()
    }

    // L-PE2: Detenga el análisis digital cuando encuentre el segundo punto.(La implementación original permitía múltiples puntos decimales.)
    private fun readNumber(): Number {
        val start = pos
        if (src[pos] == '-') pos++
        var hasDot = false
        while (pos < src.length && (src[pos].isDigit() || (src[pos] == '.' && !hasDot))) {
            if (src[pos] == '.') hasDot = true
            pos++
        }
        val s = src.substring(start, pos)
        return if (hasDot) s.toDouble() else s.toLong()
    }

    /** Coincidencia de palabra clave(límites de palabras,evitar 'and' partido 'android'). */
    private fun matchKeyword(kw: String): Boolean {
        skipSpace()
        val word = peekWord()
        if (word == kw) {
            pos += kw.length
            return true
        }
        return false
    }

    private fun matchCompareOp(): String? {
        skipSpace()
        val ops = listOf("==", "!=", ">=", "<=", ">", "<")
        for (op in ops) {
            if (pos + op.length <= src.length && src.substring(pos, pos + op.length) == op) {
                pos += op.length
                return op
            }
        }
        return null
    }
}

// ============================================================================
// Evaluator — evaluar
// ============================================================================

/**
 * Evaluar AST con contexto,Devuelve la cadena renderizada.
 * - Admite impulsar el alcance de la variable de bucle a través de alcanceStack cuando se realiza un bucle
 * - Búsqueda de variables desde el ámbito interior al ámbito exterior
 *
 * M-PE1: Trazado de profundidad anidado,Superar MAX_NESTING_DEPTH genera IllegalStateException.
 */
private class Evaluator(root: Map<String, Any?>) {
    private val scopeStack = ArrayDeque<Map<String, Any?>>()
    private var depth = 0 // M-PE1: Trazado de profundidad anidado

    init {
        scopeStack.addLast(root)
    }

    fun eval(nodes: List<Node>): String {
        val sb = StringBuilder()
        for (n in nodes) evalNode(n, sb)
        return sb.toString()
    }

    private fun evalNode(n: Node, sb: StringBuilder) {
        depth++
        if (depth > MAX_NESTING_DEPTH) {
            throw IllegalStateException("La profundidad de anidamiento de la evaluación de la plantilla supera el límite superior($MAX_NESTING_DEPTH)")
        }
        try {
            when (n) {
                is Node.Text -> sb.append(n.text)
                is Node.Set -> {
                    val value = evalExpr(n.value)
                    if (scopeStack.isNotEmpty()) {
                        // Modificar el alcance más interno en su lugar,Evite romper con las referencias de bucle
                        val inner = scopeStack.removeLast()
                        val mutable = if (inner is MutableMap) inner else inner.toMutableMap()
                        mutable[n.name] = value
                        scopeStack.addLast(mutable)
                    }
                }
                is Node.Output -> sb.append(stringify(evalExpr(n.expr)))
                is Node.If -> {
                    for ((cond, body) in n.branches) {
                        if (cond == null || truthy(evalExpr(cond))) {
                            for (child in body) evalNode(child, sb)
                            return
                        }
                    }
                }
                is Node.For -> {
                    val list = evalExpr(n.list)
                    if (list is Iterable<*>) {
                        val allItems = list.toList()
                        val totalLen = allItems.size
                        // M-TPL2: Limitar el número máximo de iteraciones.,prevenir DoS
                        val truncated = totalLen > MAX_ITERATIONS
                        if (truncated) {
                            Logger.w("PebbleTemplateEngine", "for Número de iteraciones del bucle($totalLen)Superar el límite superior($MAX_ITERATIONS),truncado")
                        }
                        val items = if (truncated) allItems.take(MAX_ITERATIONS) else allItems
                        val len = items.size
                        val loopScope = mutableMapOf<String, Any?>()
                        scopeStack.addLast(loopScope)
                        items.forEachIndexed { idx, item ->
                            loopScope.clear()
                            loopScope[n.item] = item
                            loopScope["loop"] = mapOf(
                                "index" to idx + 1,
                                "index0" to idx,
                                "first" to (idx == 0),
                                "last" to (idx == len - 1),
                                "length" to len,
                            )
                            for (child in n.body) evalNode(child, sb)
                        }
                        scopeStack.removeLast()
                    }
                }
            }
        } finally {
            depth--
        }
    }

    private fun evalExpr(e: Expr): Any? = when (e) {
        is Expr.Literal -> e.value
        is Expr.Var -> resolveVar(e.path)
        is Expr.Filter -> applyFilter(evalExpr(e.input), e.name, e.args.map { evalExpr(it) })
        is Expr.Binary -> evalBinary(e.op, evalExpr(e.left), evalExpr(e.right))
        is Expr.Unary -> when (e.op) {
            "not" -> !truthy(evalExpr(e.operand))
            else -> null
        }
        is Expr.Test -> evalTest(e.name, evalExpr(e.operand), e.negated)
    }

    private fun resolveVar(path: List<String>): Any? {
        if (path.isEmpty()) return null
        // primer párrafo:Verifique desde el alcance interior hacia el exterior.
        var cur: Any? = null
        var found = false
        for (scope in scopeStack.reversed()) {
            if (scope.containsKey(path[0])) {
                cur = scope[path[0]]
                found = true
                break
            }
        }
        if (!found) return null
        // párrafo siguiente:Acceso al número de punto Map
        for (k in path.drop(1)) {
            cur = when (cur) {
                is Map<*, *> -> cur[k]
                else -> null
            }
            if (cur == null) break
        }
        return cur
    }

    private fun evalBinary(op: String, l: Any?, r: Any?): Any? = when (op) {
        "and" -> truthy(l) && truthy(r)
        "or" -> truthy(l) || truthy(r)
        // Phase 12 Reparación:Number Para uso unificado toDouble() comparar,De lo contrario, Int vs Long usando iguales volverá false
        "==" -> if (l is Number && r is Number) l.toDouble() == r.toDouble() else l == r
        "!=" -> if (l is Number && r is Number) l.toDouble() != r.toDouble() else l != r
        ">", "<", ">=", "<=" -> compareNums(op, l, r)
        else -> null
    }

    private fun compareNums(op: String, l: Any?, r: Any?): Boolean {
        val ln = (l as? Number)?.toDouble() ?: return false
        val rn = (r as? Number)?.toDouble() ?: return false
        return when (op) {
            ">" -> ln > rn
            "<" -> ln < rn
            ">=" -> ln >= rn
            "<=" -> ln <= rn
            else -> false
        }
    }

    private fun evalTest(name: String, value: Any?, negated: Boolean): Boolean {
        val result = when (name) {
            "defined" -> value != null
            "undefined" -> value == null
            "empty" -> when (value) {
                null -> true
                is CharSequence -> value.isEmpty()
                is Collection<*> -> value.isEmpty()
                is Map<*, *> -> value.isEmpty()
                else -> false
            }
            "null", "none" -> value == null
            "even" -> (value as? Number)?.toInt()?.rem(2) == 0
            "odd" -> (value as? Number)?.toInt()?.rem(2) == 1
            "true" -> value == true
            "false" -> value == false
            else -> false
        }
        return if (negated) !result else result
    }

    private fun applyFilter(input: Any?, name: String, args: List<Any?>): Any? = when (name) {
        "upper" -> input?.toString()?.uppercase()
        "lower" -> input?.toString()?.lowercase()
        "length" -> when (input) {
            is CharSequence -> input.length
            is Collection<*> -> input.size
            is Map<*, *> -> input.size
            is Array<*> -> input.size
            else -> 0
        }
        // L-PE5: Diferencias en semántica de Pebble.Pebble El respaldo predeterminado solo para nulo/indefinido;
        // Esta implementación maneja cadenas nulas/vacías./Se devuelven las colecciones vacías.(Compatible con versiones anteriores de plantillas existentes,Cambiar la semántica puede romper las plantillas existentes)
        "default" -> if (input == null || input == "" || (input is Collection<*> && input.isEmpty())) {
            args.firstOrNull()
        } else {
            input
        }
        "trim" -> input?.toString()?.trim()
        "first" -> when (input) {
            is List<*> -> input.firstOrNull()
            is CharSequence -> input.firstOrNull()?.toString()
            is Array<*> -> input.firstOrNull()
            else -> null
        }
        "last" -> when (input) {
            is List<*> -> input.lastOrNull()
            is CharSequence -> input.lastOrNull()?.toString()
            is Array<*> -> input.lastOrNull()
            else -> null
        }
        // L-PE6: CharSequence Iterar por carácter join
        "join" -> when (input) {
            is Iterable<*> -> input.joinToString(args.firstOrNull()?.toString() ?: ", ") { it?.toString() ?: "" }
            is Array<*> -> input.joinToString(args.firstOrNull()?.toString() ?: ", ") { it?.toString() ?: "" }
            is CharSequence -> input.toList().joinToString(args.firstOrNull()?.toString() ?: ", ") { it.toString() }
            else -> input?.toString()
        }
        "replace" -> {
            if (args.size >= 2) {
                input?.toString()?.replace(args[0].toString(), args[1].toString())
            } else {
                input
            }
        }
        "capitalize" -> input?.toString()?.replaceFirstChar { it.uppercase() }
        "abs" -> (input as? Number)?.let { kotlin.math.abs(it.toDouble()) }
        "reverse" -> when (input) {
            is List<*> -> input.reversed()
            is CharSequence -> input.reversed()
            else -> input
        }
        // L-PE4: Number Priorizar la comparación numérica,Comparación de cadenas no numéricas y luego alternativas
        "sort" -> (input as? List<*>)?.sortedWith { a, b ->
            val an = a as? Number
            val bn = b as? Number
            if (an != null && bn != null) {
                an.toDouble().compareTo(bn.toDouble())
            } else {
                (a?.toString() ?: "").compareTo(b?.toString() ?: "")
            }
        }
        else -> input // Los filtros desconocidos se devuelven sin cambios
    }

    private fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is CharSequence -> v.isNotEmpty()
        is Collection<*> -> v.isNotEmpty()
        is Map<*, *> -> v.isNotEmpty()
        is Array<*> -> v.isNotEmpty()
        else -> true
    }

    private fun stringify(v: Any?): String = when (v) {
        null -> ""
        is Boolean -> if (v) "true" else "false"
        is Number -> {
            // Eliminar el sufijo .0 del número entero
            val d = v.toDouble()
            if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
        }
        else -> v.toString()
    }
}
