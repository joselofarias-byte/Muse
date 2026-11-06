/**
 * Muse Complemento: Caja de herramientas normal
 *
 * Ejecute el contrato (alojado por WebViewSkillEngine/JsSandbox):
 *  - El host evalúa el archivo completo en el V8 de WebView y luego lo ejecuta.
 *    `JSON.stringify(<functionName>.apply(null, [objeto de parámetro]))`;
 *  - cada herramienta = Una función de nivel superior que solo recibe un objeto de parámetro y devuelve un objeto normal (no utilice JSON.stringify usted mismo);
 *  - Retorno del fracaso { error: "razón china" }(El anfitrión marcará dichos resultados como fracasos);
 *  - Sólo utiliza la sintaxis de ES5, no se conecta a Internet, no lee ni escribe archivos y no lanza excepciones bajo ninguna circunstancia.
 *
 * Diseño seguro:
 *  - Las reglas de usuario son uniformes. new RegExp(pattern, flags) Compilar y probar/capturar;
 *  - Rechazar cuantificadores anidados (como (a+)+ / (a*)* / (\d+){2,} este tipo"altura de la estrella >= 2"modo),
 *    Pueden provocar un retroceso catastrófico (ReDoS);
 *  - Límite inferior: texto <= 100000 personajes, patrones <= 1000 personajes, y se puede recopilar un máximo de 1000 coincidencias a la vez.
 */

var REGEX_MAX_TEXT = 100000;
var REGEX_MAX_PATTERN = 1000;
var REGEX_MAX_MATCHES = 1000;
var REGEX_LIMIT_DEFAULT = 20;
var REGEX_LIMIT_MAX = 100;
var REGEX_ALLOWED_FLAGS = "gimsuy";

/** El objeto de parámetro se utiliza para evitar generar excepciones cuando args es nulo/indefinido. */
function regexArgsObject(args) {
    return args !== null && typeof args === "object" && !Array.isArray(args) ? args : {};
}

/** Las excepciones se convierten uniformemente en texto. */
function regexMessage(e) {
    if (e && e.message) return String(e.message);
    return String(e);
}

/** Lectura booleana relajada: acepta verdadero/falso y "true"/"false"/1/0. */
function regexBool(value, def) {
    if (value === undefined || value === null) return def;
    if (typeof value === "string") {
        var text = value.replace(/\s+/g, "").toLowerCase();
        if (text === "true" || text === "1" || text === "yes") return true;
        if (text === "false" || text === "0" || text === "no" || text === "") return false;
    }
    return !!value;
}

/** flags Normalización: solo permitida g i m s u y, los caracteres duplicados se eliminan automáticamente. */
function regexNormalizeFlags(flagsValue) {
    if (flagsValue === undefined || flagsValue === null) return { flags: "" };
    if (typeof flagsValue !== "string") return { error: "flags debe ser una cuerda" };
    var raw = flagsValue.replace(/\s+/g, "");
    var out = "";
    for (var i = 0; i < raw.length; i++) {
        var ch = raw.charAt(i);
        if (REGEX_ALLOWED_FLAGS.indexOf(ch) < 0) {
            return { error: "Carácter de banderas no admitidas: " + ch + "(solo permitido g i m s u y)" };
        }
        if (out.indexOf(ch) < 0) out += ch;
    }
    return { flags: out };
}

/** Lea el cuantificador inmediatamente después del átomo y regrese { unbounded, end } o nulo (no cuantificador). */
function regexReadQuantifier(pattern, start) {
    var ch = pattern.charAt(start);
    var end;
    if (ch === "*" || ch === "+") {
        end = start + 1;
        if (pattern.charAt(end) === "?") end++;
        return { unbounded: true, end: end };
    }
    if (ch === "?") {
        end = start + 1;
        if (pattern.charAt(end) === "?") end++;
        return { unbounded: false, end: end };
    }
    if (ch === "{") {
        var close = pattern.indexOf("}", start);
        if (close < 0) return null;
        var body = pattern.substring(start + 1, close);
        var parsed = /^(\d+)(,(\d*))?$/.exec(body);
        if (parsed === null) return null;
        end = close + 1;
        if (pattern.charAt(end) === "?") end++;
        var hasComma = parsed[2] !== undefined;
        var upper = parsed[3];
        return { unbounded: hasComma && (upper === undefined || upper === ""), end: end };
    }
    return null;
}

/** Encuentra la ubicación con inicio '(' emparejar ')' El subíndice, si no se encuentra, devuelve -1. */
function regexFindGroupEnd(text, start) {
    var depth = 0;
    var inClass = false;
    for (var i = start; i < text.length; i++) {
        var ch = text.charAt(i);
        if (ch === "\\") {
            i++;
            continue;
        }
        if (inClass) {
            if (ch === "]") inClass = false;
            continue;
        }
        if (ch === "[") {
            inClass = true;
            continue;
        }
        if (ch === "(") {
            depth++;
            continue;
        }
        if (ch === ")") {
            depth--;
            if (depth === 0) return i;
        }
    }
    return -1;
}

/** Botón superior | Dividir ramas (ignorando escapes, clases de personajes y grupos anidados). */
function regexSplitBranches(body) {
    var parts = [];
    var depth = 0;
    var inClass = false;
    var current = "";
    for (var i = 0; i < body.length; i++) {
        var ch = body.charAt(i);
        if (ch === "\\") {
            current += ch + body.charAt(i + 1);
            i++;
            continue;
        }
        if (inClass) {
            current += ch;
            if (ch === "]") inClass = false;
            continue;
        }
        if (ch === "[") {
            inClass = true;
            current += ch;
            continue;
        }
        if (ch === "(") {
            depth++;
            current += ch;
            continue;
        }
        if (ch === ")") {
            depth--;
            current += ch;
            continue;
        }
        if (ch === "|" && depth === 0) {
            parts.push(current);
            current = "";
            continue;
        }
        current += ch;
    }
    parts.push(current);
    return parts;
}

/** Eliminar el prefijo de grupo (?: / ?= / ?! / ?<name>) para facilitar el análisis de grupos. */
function regexStripGroupPrefix(body) {
    if (body.charAt(0) !== "?") return body;
    var ch = body.charAt(1);
    if (ch === ":" || ch === "=" || ch === "!") return body.substring(2);
    if (ch === "<") {
        var close = body.indexOf(">", 2);
        if (close >= 0) return body.substring(close + 1);
    }
    return body;
}

/** Cuando las ramas tienen un prefijo entre sí (como a y ab), el cuantificador ilimitado retrocederá exponencialmente. */
function regexBranchesConflict(branches) {
    for (var i = 0; i < branches.length; i++) {
        for (var j = i + 1; j < branches.length; j++) {
            var a = branches[i];
            var b = branches[j];
            if (a.length === 0 || b.length === 0) return true;
            if (a === b) return true;
            if (a.length < b.length && b.substring(0, a.length) === a) return true;
            if (b.length < a.length && a.substring(0, b.length) === b) return true;
        }
    }
    return false;
}

/** Busque ramas que sean prefijos entre sí dentro de un grupo (incluidos los grupos anidados). */
function regexBodyHasPrefixConflict(body) {
    var content = regexStripGroupPrefix(body);
    if (regexBranchesConflict(regexSplitBranches(content))) return true;
    var i = 0;
    while (i < content.length) {
        var ch = content.charAt(i);
        if (ch === "\\") {
            i += 2;
            continue;
        }
        if (ch === "[") {
            var classEnd = content.indexOf("]", i);
            if (classEnd < 0) return false;
            i = classEnd + 1;
            continue;
        }
        if (ch === "(") {
            var end = regexFindGroupEnd(content, i);
            if (end < 0) return false;
            if (regexBodyHasPrefixConflict(content.substring(i + 1, end))) return true;
            i = end + 1;
            continue;
        }
        i++;
    }
    return false;
}

/**
 * Detección de riesgos catastróficos de retroceso:(1) Cuantificador anidado (altura de estrella >= 2);
 * (2) Un grupo modificado por un cuantificador ilimitado contiene ramas que son prefijos entre sí (p. ej. (a|ab)+).
 * Dichos patrones retrocederán exponencialmente en entradas no coincidentes y deben rechazarse antes de la ejecución.
 */
function regexHasNestedQuantifier(pattern) {
    var stack = [{ unbounded: false, start: 0 }];
    var i = 0;
    var inClass = false;
    var n = pattern.length;
    while (i < n) {
        var ch = pattern.charAt(i);
        if (ch === "\\") {
            i += 2;
            continue;
        }
        if (inClass) {
            if (ch === "]") inClass = false;
            i++;
            continue;
        }
        if (ch === "[") {
            inClass = true;
            i++;
            continue;
        }
        if (ch === "(") {
            stack.push({ unbounded: false, start: i + 1 });
            i++;
            continue;
        }
        if (ch === ")") {
            var frame = stack.length > 1 ? stack.pop() : stack[0];
            var after = regexReadQuantifier(pattern, i + 1);
            if (after !== null) {
                if (after.unbounded) {
                    if (frame.unbounded) return true;
                    if (regexBodyHasPrefixConflict(pattern.substring(frame.start, i))) return true;
                }
                i = after.end;
            } else {
                i++;
            }
            continue;
        }
        var quant = regexReadQuantifier(pattern, i + 1);
        if (quant !== null) {
            if (quant.unbounded) stack[stack.length - 1].unbounded = true;
            i = quant.end;
            continue;
        }
        i++;
    }
    return false;
}

/** Cuente el número de grupos de captura (solo se usa para dar pistas al modelo). */
function regexCountGroups(pattern) {
    var count = 0;
    var i = 0;
    var inClass = false;
    while (i < pattern.length) {
        var ch = pattern.charAt(i);
        if (ch === "\\") {
            i += 2;
            continue;
        }
        if (inClass) {
            if (ch === "]") inClass = false;
            i++;
            continue;
        }
        if (ch === "[") {
            inClass = true;
            i++;
            continue;
        }
        if (ch === "(") {
            if (pattern.charAt(i + 1) === "?") {
                var kind = pattern.charAt(i + 2);
                if (kind === "<") {
                    var next = pattern.charAt(i + 3);
                    if (next !== "=" && next !== "!") count++;
                } else if (kind !== ":" && kind !== "=" && kind !== "!") {
                    count++;
                }
            } else {
                count++;
            }
        }
        i++;
    }
    return count;
}

/** Extraer el nombre del grupo de captura con nombre ((?<name>...)). */
function regexNamedGroups(pattern) {
    var names = [];
    var re = /\(\?<([A-Za-z_$][A-Za-z0-9_$]*)>/g;
    var m = re.exec(pattern);
    while (m !== null) {
        names.push(m[1]);
        m = re.exec(pattern);
    }
    return names;
}

/** Compilar usuario regular: longitud,flags, los cuantificadores anidados y los errores gramaticales se unifican en { error }. */
function regexCompile(pattern, flags) {
    if (typeof pattern !== "string") return { error: "pattern debe ser una cuerda" };
    if (pattern.length === 0) return { error: "pattern no puede estar vacío" };
    if (pattern.length > REGEX_MAX_PATTERN) {
        return { error: "pattern Demasiado largo (límite superior " + REGEX_MAX_PATTERN + " personaje, actual " + pattern.length + ")" };
    }
    var normalized = regexNormalizeFlags(flags);
    if (normalized.error) return normalized;
    if (regexHasNestedQuantifier(pattern)) {
        return {
            error: "Detección de patrones que podrían desencadenar un retroceso catastrófico (cuantificadores anidados como (a+)+, o ramas que son prefijos entre sí, como (a|ab)+);" +
                "Vuelva a escribir el patrón: elimine la duplicación exterior o escriba las ramas como mutuamente excluyentes"
        };
    }
    try {
        return { re: new RegExp(pattern, normalized.flags), flags: normalized.flags };
    } catch (e) {
        return { error: "La compilación regular falló: " + regexMessage(e) };
    }
}

/** Verifique la longitud del texto que se está probando. */
function regexCheckText(text) {
    if (typeof text !== "string") return { error: "text debe ser una cuerda" };
    if (text.length > REGEX_MAX_TEXT) {
        return { error: "text Demasiado largo (límite superior " + REGEX_MAX_TEXT + " personaje, actual " + text.length + ")" };
    }
    return { ok: true };
}

/** Lea el parámetro de límite (se utiliza el valor predeterminado si falta, converge al límite superior cuando se excede el límite superior). */
function regexReadLimit(value, def, max) {
    if (value === undefined || value === null || value === "") return { value: def };
    var num = typeof value === "number" ? value : parseInt(String(value), 10);
    if (isNaN(num) || num < 1) return { error: "limit debe ser 1-" + max + " número entero entre" };
    if (num > max) return { value: max };
    return { value: Math.floor(num) };
}

/** Asegúrese de que la expresión regular tenga el indicador g (requerido para la coincidencia de enumeraciones), devuelva una nueva RegExp o { error }. */
function regexGlobalCopy(pattern, flags) {
    var finalFlags = flags.indexOf("g") >= 0 ? flags : flags + "g";
    try {
        return { re: new RegExp(pattern, finalFlags), flags: finalFlags };
    } catch (e) {
        return { error: "La compilación regular falló: " + regexMessage(e) };
    }
}

/**
 * Las coincidencias se realizan de forma secuencial, recopilando hasta un máximo de elementos.
 * El script en lastIndex avanza manualmente las coincidencias de longitud cero para evitar bucles infinitos; Hay un límite superior estricto en el número de iteraciones.
 */
function regexCollect(re, text, max) {
    var matches = [];
    var truncated = false;
    re.lastIndex = 0;
    var guard = 0;
    var guardMax = max + 2;
    while (guard < guardMax) {
        guard++;
        var m = re.exec(text);
        if (m === null) break;
        if (matches.length >= max) {
            truncated = true;
            break;
        }
        matches.push(m);
        if (m[0].length === 0) {
            re.lastIndex = re.lastIndex + 1;
            if (re.lastIndex > text.length) break;
        }
    }
    return { matches: matches, truncated: truncated };
}

/** Convierta los resultados de coincidencias nativas en objetos normales que puedan serializarse en JSON. */
function regexMatchInfo(m) {
    var groups = [];
    for (var i = 1; i < m.length; i++) {
        groups.push(m[i] === undefined ? null : m[i]);
    }
    var named = null;
    if (m.groups) {
        named = {};
        var keys = Object.keys(m.groups);
        for (var k = 0; k < keys.length; k++) {
            named[keys[k]] = m.groups[keys[k]] === undefined ? null : m.groups[keys[k]];
        }
    }
    return { index: m.index, text: m[0], groups: groups, namedGroups: named };
}

/** Analice el parámetro del grupo: déjelo en blanco para devolver la coincidencia completa, complete el número para obtener el número de secuencia, complete el nombre para obtener el grupo nombrado. */
function regexResolveGroup(group, groupCount, groupNames) {
    if (group === undefined || group === null || group === "") return { mode: "full", echo: null };
    if (typeof group === "number") {
        if (isNaN(group) || group < 1 || group !== Math.floor(group)) {
            return { error: "group debe ser >=1 un número entero o el nombre de un grupo de captura con nombre" };
        }
        if (group > groupCount) {
            return { error: "group Fuera de alcance: este modo sólo tiene " + groupCount + " grupos de captura" };
        }
        return { mode: "index", index: group, echo: group };
    }
    if (typeof group === "string") {
        var text = group.trim();
        if (/^\d+$/.test(text)) {
            var index = parseInt(text, 10);
            if (index < 1 || index > groupCount) {
                return { error: "group Fuera de alcance: este modo sólo tiene " + groupCount + " grupos de captura" };
            }
            return { mode: "index", index: index, echo: index };
        }
        var found = false;
        for (var i = 0; i < groupNames.length; i++) {
            if (groupNames[i] === text) found = true;
        }
        if (!found) {
            return {
                error: "No se encontró el grupo de captura con nombre " + text +
                    (groupNames.length > 0 ? "(disponible: " + groupNames.join(", ") + ")" : "(Este patrón no tiene grupos de captura con nombre)")
            };
        }
        return { mode: "named", name: text, echo: text };
    }
    return { error: "group Debe ser un número entero basado en 1 o un nombre de grupo con nombre" };
}

/** Herramienta regex_test: prueba una coincidencia y devuelve el primer detalle de la coincidencia con un límite de coincidencias. */
function regexTest(args) {
    args = regexArgsObject(args);
    var textCheck = regexCheckText(args.text);
    if (textCheck.error) return textCheck;
    var compiled = regexCompile(args.pattern, args.flags);
    if (compiled.error) return compiled;
    var limit = regexReadLimit(args.limit, REGEX_LIMIT_DEFAULT, REGEX_LIMIT_MAX);
    if (limit.error) return limit;
    var globalCopy = regexGlobalCopy(args.pattern, compiled.flags);
    if (globalCopy.error) return globalCopy;
    var collected = regexCollect(globalCopy.re, args.text, limit.value);
    var matches = [];
    for (var i = 0; i < collected.matches.length; i++) {
        matches.push(regexMatchInfo(collected.matches[i]));
    }
    return {
        result: {
            matched: matches.length > 0,
            matchCount: matches.length,
            firstMatch: matches.length > 0 ? matches[0] : null,
            matches: matches,
            groupCount: regexCountGroups(args.pattern),
            groupNames: regexNamedGroups(args.pattern)
        },
        limit: limit.value,
        truncated: collected.truncated,
        flags: globalCopy.flags,
        note: "tapa de texto " + REGEX_MAX_TEXT + " personaje;matches la mayoría " + limit.value +
            " artículo,truncated Es cierto para indicar que hay más coincidencias que no han sido devueltas."
    };
}

/** Herramienta regex_extract: extrae coincidencias en lotes (se pueden especificar grupos de captura y se pueden eliminar duplicados). */
function regexExtract(args) {
    args = regexArgsObject(args);
    var textCheck = regexCheckText(args.text);
    if (textCheck.error) return textCheck;
    var compiled = regexCompile(args.pattern, args.flags);
    if (compiled.error) return compiled;
    var limit = regexReadLimit(args.limit, 200, REGEX_MAX_MATCHES);
    if (limit.error) return limit;
    var globalCopy = regexGlobalCopy(args.pattern, compiled.flags);
    if (globalCopy.error) return globalCopy;
    var groupCount = regexCountGroups(args.pattern);
    var groupNames = regexNamedGroups(args.pattern);
    var selector = regexResolveGroup(args.group, groupCount, groupNames);
    if (selector.error) return selector;
    var collected = regexCollect(globalCopy.re, args.text, limit.value);
    var values = [];
    var missing = 0;
    for (var i = 0; i < collected.matches.length; i++) {
        var m = collected.matches[i];
        var value;
        if (selector.mode === "full") {
            value = m[0];
        } else if (selector.mode === "index") {
            value = m[selector.index] === undefined ? null : m[selector.index];
        } else {
            value = m.groups === undefined || m.groups === null || m.groups[selector.name] === undefined
                ? null : m.groups[selector.name];
        }
        if (value === null) missing++;
        values.push(value === undefined ? null : value);
    }
    var matchedCount = values.length;
    var unique = regexBool(args.unique, false);
    if (unique) {
        var seen = {};
        var deduped = [];
        for (var j = 0; j < values.length; j++) {
            var key = values[j] === null ? "\u0000null" : "\u0000" + values[j];
            if (Object.prototype.hasOwnProperty.call(seen, key)) continue;
            seen[key] = true;
            deduped.push(values[j]);
        }
        values = deduped;
    }
    return {
        result: values,
        count: values.length,
        matchedCount: matchedCount,
        group: selector.echo,
        groupCount: groupCount,
        groupNames: groupNames,
        unique: unique,
        missingGroups: missing,
        truncated: collected.truncated,
        limit: limit.value,
        flags: globalCopy.flags,
        note: "Los grupos de captura no coincidentes se registran como nulos;truncated Si es verdadero, significa que se alcanzó el límite superior;" +
            "tapa de texto " + REGEX_MAX_TEXT + " personajes."
    };
}

/** Herramienta regex_replace: reemplazar por patrón, soporte $1..$9 Referencias retrospectivas y cambios globales. */
function regexReplace(args) {
    args = regexArgsObject(args);
    var textCheck = regexCheckText(args.text);
    if (textCheck.error) return textCheck;
    var replacement = args.replacement;
    if (typeof replacement === "number" || typeof replacement === "boolean") replacement = String(replacement);
    if (typeof replacement !== "string") return { error: "replacement debe ser una cuerda" };
    if (replacement.length > REGEX_MAX_PATTERN * 10) {
        return { error: "replacement Demasiado largo (límite superior " + (REGEX_MAX_PATTERN * 10) + " personajes)" };
    }
    var compiled = regexCompile(args.pattern, args.flags);
    if (compiled.error) return compiled;
    var global = regexBool(args.global, true);
    var flags = compiled.flags;
    if (global) {
        if (flags.indexOf("g") < 0) flags += "g";
    } else {
        flags = flags.replace(/g/g, "");
    }
    var re;
    try {
        re = new RegExp(args.pattern, flags);
    } catch (e) {
        return { error: "La compilación regular falló: " + regexMessage(e) };
    }
    var count = 0;
    var truncated = false;
    if (global) {
        var collected = regexCollect(re, args.text, REGEX_MAX_MATCHES);
        count = collected.matches.length;
        truncated = collected.truncated;
    } else {
        re.lastIndex = 0;
        count = re.exec(args.text) === null ? 0 : 1;
    }
    re.lastIndex = 0;
    var replaced;
    try {
        replaced = args.text.replace(re, replacement);
    } catch (e) {
        return { error: "Reemplazo fallido: " + regexMessage(e) };
    }
    return {
        result: replaced,
        replacements: count,
        global: global,
        flags: flags,
        truncated: truncated,
        note: "replacement apoyo $1..$9 referencia retrospectiva,$& representa todo el partido,$$ Representa un signo de dólar literal;" +
            "Estadísticas de más sustituciones en modo global " + REGEX_MAX_MATCHES + " veces."
    };
}
