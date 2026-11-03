package io.zer0.muse.ui.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import io.zer0.muse.ui.theme.codeColors
import java.util.concurrent.ConcurrentHashMap

/**
 * Resaltado de código ligero(autoimplementable,No se introducen bibliotecas de resaltado de terceros).
 *
 * E6 Mejora: Conjunto de idiomas ampliado de 10 a 21 idiomas principales + resaltado de nivel de línea diferencial.
 * Idiomas soportados: kotlin / java / python / javascript / typescript / go / rust / shell /
 *           sql / json / xml(Predeterminado) / c / cpp / csharp / ruby / swift / php / dart /
 *           html / css / yaml / diff
 * Resaltar dimensiones:
 *  - Palabras clave(keyword): Negrita + azul violeta
 *  - cuerda(string): verde
 *  - Comentario(comment): gris cursiva
 *  - numeros(number): naranja
 *  - anotación/decorador(annotation): amarillo
 *  - diff(diff): coloración a nivel de fila — hunk Cabeza azul / Agregar fila verde / Eliminar fila roja(Con fondo de fila translúcido.)
 *
 * Estrategia: Seleccionar conjuntos de palabras clave por idioma,Alternancia regular de una exploración,Coloreado por tipo de coincidencia.
 * Sin análisis completo del árbol de sintaxis,Simplemente cubra las escenas destacadas comunes.
 *
 * Phase 12: La coincidencia de colores cambió de codificada a @Composable MaterialTheme.colorScheme,
 * Los temas oscuros utilizan automáticamente colores más brillantes,Asegurar el contraste.
 */
object CodeHighlighter {

    /** Conjuntos de palabras clave para cada idioma(minúscula). */
    private val KEYWORDS: Map<String, Set<String>> = mapOf(
        "kotlin" to setOf(
            "fun", "val", "var", "class", "object", "interface", "enum", "sealed", "data",
            "companion", "override", "private", "public", "protected", "internal", "open",
            "abstract", "final", "lateinit", "const", "vararg", "suspend", "inline", "reified",
            "import", "package", "return", "if", "else", "when", "for", "while", "do", "break",
            "continue", "try", "catch", "finally", "throw", "in", "is", "as", "out", "inout",
            "null", "true", "false", "this", "super", "it", "by", "get", "set", "init",
        ),
        "java" to setOf(
            "public", "private", "protected", "class", "interface", "enum", "extends", "implements",
            "static", "final", "void", "int", "long", "double", "float", "boolean", "char",
            "byte", "short", "new", "return", "if", "else", "for", "while", "do", "switch",
            "case", "break", "continue", "try", "catch", "finally", "throw", "throws",
            "import", "package", "this", "super", "null", "true", "false", "instanceof",
            "synchronized", "volatile", "transient", "native", "abstract",
        ),
        "python" to setOf(
            "def", "class", "if", "elif", "else", "for", "while", "return", "import", "from",
            "as", "try", "except", "finally", "raise", "with", "pass", "break", "continue",
            "lambda", "yield", "global", "nonlocal", "assert", "del", "in", "is", "not",
            "and", "or", "None", "True", "False", "self", "cls", "async", "await",
        ),
        "javascript" to setOf(
            "function", "var", "let", "const", "class", "extends", "return", "if", "else",
            "for", "while", "do", "switch", "case", "break", "continue", "try", "catch",
            "finally", "throw", "new", "delete", "typeof", "instanceof", "in", "of",
            "this", "super", "null", "undefined", "true", "false", "async", "await",
            "import", "export", "default", "from", "as",
        ),
        "typescript" to setOf(
            "function", "var", "let", "const", "class", "extends", "implements", "interface",
            "type", "enum", "return", "if", "else", "for", "while", "do", "switch", "case",
            "break", "continue", "try", "catch", "finally", "throw", "new", "delete",
            "typeof", "instanceof", "in", "of", "this", "super", "null", "undefined",
            "true", "false", "async", "await", "import", "export", "default", "from", "as",
            "public", "private", "protected", "readonly", "abstract", "static",
        ),
        "go" to setOf(
            "func", "var", "const", "type", "struct", "interface", "package", "import",
            "return", "if", "else", "for", "switch", "case", "default", "break", "continue",
            "defer", "go", "chan", "range", "select", "map", "nil", "true", "false",
        ),
        "rust" to setOf(
            "fn", "let", "mut", "const", "static", "struct", "enum", "trait", "impl",
            "pub", "priv", "use", "mod", "crate", "self", "super", "return", "if", "else",
            "for", "while", "loop", "match", "break", "continue", "in", "ref", "as",
            "move", "async", "await", "dyn", "unsafe", "true", "false", "Some", "None",
            "Ok", "Err",
        ),
        "shell" to setOf(
            "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case",
            "esac", "function", "return", "echo", "export", "local", "readonly",
        ),
        "sql" to setOf(
            "SELECT", "FROM", "WHERE", "INSERT", "UPDATE", "DELETE", "CREATE", "TABLE",
            "DROP", "ALTER", "INDEX", "VIEW", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER",
            "ON", "AS", "AND", "OR", "NOT", "NULL", "ORDER", "BY", "GROUP", "HAVING",
            "LIMIT", "OFFSET", "DISTINCT", "UNION", "ALL", "INTO", "VALUES", "SET",
            "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "DEFAULT", "CHECK",
        ),
        // L3 Reparación: Conjunto completo de palabras clave json(true/false/null);xml Sin palabras clave,ir DEFAULT_KEYWORDS
        "json" to setOf("true", "false", "null"),
        // E6 Mejora: Serie C convencional/script/conjunto de idiomas de front-end
        "c" to setOf(
            "auto", "break", "case", "char", "const", "continue", "default", "do", "double",
            "else", "enum", "extern", "float", "for", "goto", "if", "int", "long", "register",
            "return", "short", "signed", "sizeof", "static", "struct", "switch", "typedef",
            "union", "unsigned", "void", "volatile", "while",
        ),
        "cpp" to setOf(
            "auto", "break", "case", "catch", "char", "class", "const", "constexpr", "continue",
            "default", "delete", "do", "double", "else", "enum", "explicit", "extern", "false",
            "float", "for", "friend", "goto", "if", "inline", "int", "long", "namespace", "new",
            "nullptr", "operator", "private", "protected", "public", "register", "return",
            "short", "signed", "sizeof", "static", "struct", "switch", "template", "this",
            "throw", "true", "try", "typedef", "typename", "union", "unsigned", "using",
            "virtual", "void", "volatile", "while",
        ),
        "csharp" to setOf(
            "abstract", "as", "base", "bool", "break", "byte", "case", "catch", "char",
            "checked", "class", "const", "continue", "decimal", "default", "delegate", "do",
            "double", "else", "enum", "event", "explicit", "extern", "false", "finally",
            "fixed", "float", "for", "foreach", "goto", "if", "implicit", "in", "int",
            "interface", "internal", "is", "lock", "long", "namespace", "new", "null",
            "object", "operator", "out", "override", "params", "private", "protected",
            "public", "readonly", "ref", "return", "sbyte", "sealed", "short", "sizeof",
            "stackalloc", "static", "string", "struct", "switch", "this", "throw", "true",
            "try", "typeof", "uint", "ulong", "unchecked", "unsafe", "ushort", "using",
            "virtual", "void", "volatile", "while",
        ),
        "ruby" to setOf(
            "def", "class", "module", "if", "elsif", "else", "end", "for", "while", "until",
            "do", "case", "when", "then", "return", "yield", "self", "nil", "true", "false",
            "begin", "rescue", "ensure", "raise", "require", "include", "extend", "lambda",
            "proc", "new", "super", "and", "or", "not",
        ),
        "swift" to setOf(
            "func", "var", "let", "class", "struct", "enum", "protocol", "extension", "import",
            "return", "if", "else", "guard", "for", "while", "repeat", "switch", "case",
            "break", "continue", "defer", "do", "catch", "throw", "throws", "try", "in", "as",
            "is", "nil", "true", "false", "self", "super", "init", "deinit", "static",
            "private", "public", "internal", "open", "fileprivate", "lazy", "weak", "unowned",
            "where",
        ),
        "php" to setOf(
            "function", "class", "interface", "extends", "implements", "public", "private",
            "protected", "static", "final", "const", "return", "if", "else", "elseif", "for",
            "foreach", "while", "do", "switch", "case", "break", "continue", "try", "catch",
            "finally", "throw", "new", "echo", "print", "require", "require_once", "include",
            "include_once", "namespace", "use", "as", "true", "false", "null", "this",
            "global",
        ),
        "dart" to setOf(
            "class", "interface", "extends", "implements", "mixin", "abstract", "factory",
            "const", "final", "var", "void", "return", "if", "else", "for", "while", "do",
            "switch", "case", "break", "continue", "try", "catch", "finally", "throw", "new",
            "null", "true", "false", "this", "super", "static", "import", "export", "library",
            "part", "async", "await",
        ),
        "html" to setOf(
            "html", "head", "body", "div", "span", "p", "a", "img", "ul", "ol", "li",
            "table", "tr", "td", "th", "form", "input", "button", "select", "option",
            "script", "style", "meta", "link", "title", "h1", "h2", "h3", "h4", "h5", "h6",
            "br", "hr", "section", "article", "nav", "header", "footer", "main", "aside",
        ),
        "css" to setOf(
            "@media", "@import", "@keyframes", "@font-face", "@supports", "@charset", "@page",
        ),
        "yaml" to setOf("true", "false", "null", "yes", "no", "on", "off"),
    )

    /** palabras clave genéricas fallback(shell/sql aparte del predeterminado). */
    private val DEFAULT_KEYWORDS = setOf(
        "function", "return", "if", "else", "for", "while", "break", "continue",
        "true", "false", "null", "none", "nil", "undefined",
    )

    /** L3 Reparación: Mapeo normalizado de alias de idioma,Asigne abreviaturas a nombres completos en PALABRAS CLAVE. */
    private val LANG_ALIASES: Map<String, String> = mapOf(
        "js" to "javascript",
        "ts" to "typescript",
        "py" to "python",
        "sh" to "shell",
        "golang" to "go",
        // E6 Mejora: Alias más comunes
        "bash" to "shell",
        "zsh" to "shell",
        "yml" to "yaml",
        "c++" to "cpp",
        "h" to "c",
        "cs" to "csharp",
        "objc" to "c",
        "objective-c" to "c",
        "objectivec" to "c",
        "rb" to "ruby",
        "html5" to "html",
    )

    /** L4 Reparación: Fragmentos regulares que nunca coinciden.(Se utiliza para cerrar la rama de comentarios de un idioma.). */
    private const val NEVER_MATCH = "[^\\s\\S]"

    /** M3 Reparación: Caché compilado expresión regular resaltada por idioma,Evite la recompilación línea por línea de bloques de código grandes. Utilice ConcurrentHashMap para garantizar la seguridad de los subprocesos. */
    private val regexCache = ConcurrentHashMap<String, Regex>()

    /** M3 Reparación: tomar(o compilar y almacenar en caché)Resalte las reglas habituales para el idioma especificado. */
    private fun getRegex(lang: String?): Regex {
        val key = lang ?: ""
        return regexCache.getOrPut(key) {
            buildRegex(KEYWORDS[lang] ?: DEFAULT_KEYWORDS, lang)
        }
    }

    /**
     * Phase 12: Resalte la clase de datos de coincidencia de colores.
     *
     * Los temas brillantes utilizan colores con mayor saturación,Utilice colores aclarantes para temas oscuros.,Asegurar el contraste.
     *
     * M-MD12 Descripción: La combinación de colores es un código que resalta el color semántico funcional.,Ya concentrado en HighlightColors,
     * por highlightColors() Derivar luz de MaterialTheme.colorScheme/Dos conjuntos oscuros.
     * Estos valores de color no pertenecen al color del tema de la marca.,Mantenga una codificación independiente para garantizar el contraste semántico entre los bloques de código.,
     * no con"lámpara de mesa nocturna"Tema conflicto férreo. Si es necesario ajuste,Cambio unificado highlightColors() Eso es todo.
     */
    data class HighlightColors(
        val keyword: Color,
        val string: Color,
        val comment: Color,
        val number: Color,
        val annotation: Color,
        /** diff Agregar nueva fila. */
        val diffAdded: Color,
        /** diff Eliminar la fila. */
        val diffRemoved: Color,
        /** diff hunk cabeza(+++/---/@@). */
        val diffHunk: Color,
    )

    /**
     * Phase 12: Deriva el color de resaltado de MaterialTheme.
     *
     * v1.0.52: Paleta de colores movida a ui/theme/StatusColors.kt [MuseCodeColors],
     * Inyectado por MuseTheme según colores oscuros y claros. CompositionLocal,Aquí solo se realiza el mapeo de campos.
     * Los temas personalizados posteriores pueden anular el color de resaltado del código.
     */
    @Composable
    fun highlightColors(): HighlightColors {
        val c = MaterialTheme.codeColors
        return HighlightColors(
            keyword = c.keyword,
            string = c.string,
            comment = c.comment,
            number = c.number,
            annotation = c.annotation,
            diffAdded = c.diffAdded,
            diffRemoved = c.diffRemoved,
            diffHunk = c.diffHunk,
        )
    }

    /**
     * Resaltar el texto del código,Devuelve cadena anotada.
     *
     * Phase 12: Cambiar a @Composable,Obtener color de MaterialTheme,El tema oscuro se adapta automáticamente.
     *
     * @param code código original
     * @param language identificador de idioma(minúscula,puede ser null)
     */
    @Composable
    fun highlight(code: String, language: String?): AnnotatedString {
        val rawLang = language?.lowercase()?.trim()
        // E6 Mejora: diff identificar — fence el idioma puede ser "diff" / "git diff" / "unified diff"
        val isDiff = rawLang != null && rawLang.contains("diff")
        // L3 Reparación: Normalización de alias(js→javascript espera),Verifique el conjunto de palabras clave y el caché nuevamente
        val normalizedLang = if (isDiff) "diff" else LANG_ALIASES[rawLang] ?: rawLang
        val colors = highlightColors()
        if (isDiff) {
            return remember(code, colors) { highlightDiff(code, colors) }
        }
        return remember(code, language, colors) {
            buildAnnotatedString {
                val regex = getRegex(normalizedLang)
                var lastEnd = 0
                regex.findAll(code).forEach { match ->
                    if (match.range.first > lastEnd) {
                        append(code.substring(lastEnd, match.range.first))
                    }
                    appendHighlightedToken(match.value, match, colors)
                    lastEnd = match.range.last + 1
                }
                if (lastEnd < code.length) {
                    append(code.substring(lastEnd))
                }
            }
        }
    }

    /**
     * Colorea fichas individuales capturando el tipo de grupo.
     * Extraído como una función independiente para controlar highlight() La complejidad ciclomática de(6 capturar ramas del grupo).
     */
    private fun AnnotatedString.Builder.appendHighlightedToken(token: String, match: MatchResult, colors: HighlightColors) {
        when {
            match.groups[1] != null -> {
                // Comentarios de línea(por idioma: // o # o --,Ver detalles buildRegex)
                withStyle(SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)) {
                    append(token)
                }
            }
            match.groups[2] != null -> {
                // bloquear comentarios /* */
                withStyle(SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)) {
                    append(token)
                }
            }
            match.groups[3] != null -> {
                // cuerda "..." o '...'
                withStyle(SpanStyle(color = colors.string)) {
                    append(token)
                }
            }
            match.groups[4] != null -> {
                // Palabras clave
                withStyle(SpanStyle(color = colors.keyword, fontWeight = FontWeight.Bold)) {
                    append(token)
                }
            }
            match.groups[5] != null -> {
                // numeros
                withStyle(SpanStyle(color = colors.number)) {
                    append(token)
                }
            }
            match.groups[6] != null -> {
                // anotación @...
                withStyle(SpanStyle(color = colors.annotation)) {
                    append(token)
                }
            }
            else -> append(token)
        }
    }

    /** E6: diff clasificación de filas — Para renderizado a nivel de fila y pruebas unitarias. */
    internal enum class DiffLineKind { HUNK_HEADER, ADDED, REMOVED, CONTEXT, EMPTY }

    /**
     * E6: Categorizar texto de diferencias de una sola línea.
     *
     * orden de sentencia: hunk cabeza(@@ o +++/---)Prefiere +/- líneas de un solo carácter,
     * evitar "+++ b/File" Se consideró erróneamente como una nueva línea.
     */
    internal fun classifyDiffLine(line: String): DiffLineKind {
        if (line.isEmpty()) return DiffLineKind.EMPTY
        return when {
            line.startsWith("@@") -> DiffLineKind.HUNK_HEADER
            line.startsWith("+++") || line.startsWith("---") -> DiffLineKind.HUNK_HEADER
            line.startsWith("+") -> DiffLineKind.ADDED
            line.startsWith("-") -> DiffLineKind.REMOVED
            else -> DiffLineKind.CONTEXT
        }
    }

    /**
     * E6: diff Resaltado a nivel de fila — hunk Cabeza azul / Agregar fila verde / Eliminar fila roja(Con fondo de fila translúcido.).
     * Coloración independiente fila por fila.,Compatible con el método de llamada de resaltado línea por línea de MarkdownText.
     */
    private fun highlightDiff(code: String, colors: HighlightColors): AnnotatedString = buildAnnotatedString {
        code.split("\n").forEachIndexed { index, line ->
            if (index > 0) append("\n")
            val kind = classifyDiffLine(line)
            val style = when (kind) {
                DiffLineKind.HUNK_HEADER -> SpanStyle(
                    color = colors.diffHunk,
                    fontWeight = FontWeight.Bold,
                )
                DiffLineKind.ADDED -> SpanStyle(
                    color = colors.diffAdded,
                    background = colors.diffAdded.copy(alpha = 0.12f),
                )
                DiffLineKind.REMOVED -> SpanStyle(
                    color = colors.diffRemoved,
                    background = colors.diffRemoved.copy(alpha = 0.12f),
                )
                // CONTEXT / EMPTY Mantener el estilo predeterminado
                else -> null
            }
            if (style != null) {
                withStyle(style) { append(line) }
            } else {
                append(line)
            }
        }
    }

    /** Construir regularidad resaltada. */
    private fun buildRegex(keywords: Set<String>, lang: String?): Regex {
        // v0.42: Reparación IndexOutOfBoundsException — Grupo sin captura utilizado originalmente (?:...),
        // pero highlight() Pase groups[1..6] Determinar el tipo de token,Regular debe proporcionar 6 grupos de captura.,
        // De lo contrario match.groups[N] puede tirar "No group N".
        // SQL Poner en mayúsculas las palabras clave,unificado más \b Los límites de las palabras evitan discrepancias(Rama SQL original Ninguna \b coincidirá con ON en CONFIGURATION).
        val kwPattern = keywords.joinToString("|") { Regex.escape(it) }
        // grupo: 1=Comentarios de línea 2=bloquear comentarios 3=cuerda 4=Palabras clave 5=numeros 6=anotación
        // Utilice grupos de captura para todos (...),con highlight() en groups[1..6] correspondencia uno a uno
        // L4 Reparación: Las reglas de comentarios cambian según el idioma,Evite # ser juzgado mal como comentario en kotlin/java.// Mal juzgado en Python.
        //  - kotlin/java/js/ts/go/rust/c/cpp/csharp/swift/php/dart: Comentarios de línea // + comentarios de bloque /* */
        //  - python/shell/ruby/yaml: Comentarios de línea #,Sin bloquear comentarios
        //  - sql: Comentarios de línea --,Sin bloquear comentarios
        //  - Otros(Contiene json/xml/html/css): rama sin comentar(Utilice marcadores de posición NEVER_MATCH,Mantener la estructura de 6 grupos.)
        val (lineComment, blockComment) = when (lang) {
            // E6 Mejora: Serie C agregada / swift / php / dart heredar // + /* */
            "kotlin", "java", "javascript", "typescript", "go", "rust",
            "c", "cpp", "csharp", "swift", "php", "dart",
            -> "//[^\\n]*" to "/\\*[\\s\\S]*?\\*/"
            "python", "shell", "ruby", "yaml" -> "#[^\\n]*" to NEVER_MATCH
            "sql" -> "--[^\\n]*" to NEVER_MATCH
            else -> NEVER_MATCH to NEVER_MATCH
        }
        // M-MD10 Reparación: IGNORE_CASE ya no está unificado para todos los idiomas.
        // SQL Las palabras clave se almacenan en mayúsculas.(SELECT/FROM...),El usuario podrá ingresar en minúsculas,necesitar IGNORE_CASE;
        // Otros idiomas(kotlin/java/python/js/ts/go/rust/shell)Las palabras clave distinguen entre mayúsculas y minúsculas
        // (Por ejemplo, la diversión de Kotlin no debería coincidir. Fun),No agregue IGNORE_CASE.
        val options = if (lang == "sql") setOf(RegexOption.IGNORE_CASE) else emptySet()
        return Regex(
            """($lineComment)|($blockComment)|("[^"]*"|'[^']*'|`[^`]*`)|(\b(?:$kwPattern)\b)|(\b\d+\.?\d*[fFlL]?\b)|(@\w+)""",
            options,
        )
    }
}
