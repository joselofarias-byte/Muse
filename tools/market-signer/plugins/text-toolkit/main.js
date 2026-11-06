/**
 * Muse Complemento: Caja de herramientas de texto
 *
 * Ejecute el contrato (alojado por WebViewSkillEngine):
 *  - Cada función de utilidad recibe un objeto de parámetro, como function textStats(args),args.text;
 *  - El valor de retorno puede ser un objeto normal, y el host lo serializará JSON automáticamente y se lo entregará al modelo;
 *  - Devolución en caso de error { error: "Razón" }, el anfitrión marcará dichos resultados como fracasos.
 *
 * Este complemento es puramente informático local: no se conecta a Internet y no lee ni escribe archivos, por lo que el manifiesto no declara ninguna capacidad.
 */

/** Base64 codificación (UTF-8 Seguridad: sin restricciones latin1 para btoa/atob). */
function b64Encode(text) {
    return btoa(encodeURIComponent(text).replace(/%([0-9A-F]{2})/g, function (match, hex) {
        return String.fromCharCode(parseInt(hex, 16));
    }));
}

/** Base64 decodificar (UTF-8 seguridad). */
function b64Decode(encoded) {
    return decodeURIComponent(atob(encoded).split("").map(function (ch) {
        return "%" + ("00" + ch.charCodeAt(0).toString(16)).slice(-2);
    }).join(""));
}

/** Dividir por punto de código para evitar que los emoji se cuenten como dos caracteres. */
function toCodePoints(text) {
    var points = [];
    for (var i = 0; i < text.length; i++) {
        var code = text.charCodeAt(i);
        if (code >= 0xd800 && code <= 0xdbff && i + 1 < text.length) {
            var next = text.charCodeAt(i + 1);
            if (next >= 0xdc00 && next <= 0xdfff) {
                points.push(text.substring(i, i + 2));
                i++;
                continue;
            }
        }
        points.push(text.charAt(i));
    }
    return points;
}

/** UTF-8 Número de bytes (utilizado para determinar los límites de longitud, como el presupuesto de contexto de API). */
function utf8Length(text) {
    var bytes = 0;
    for (var i = 0; i < text.length; i++) {
        var code = text.charCodeAt(i);
        if (code < 0x80) bytes += 1;
        else if (code < 0x800) bytes += 2;
        else if (code >= 0xd800 && code <= 0xdbff && i + 1 < text.length &&
            text.charCodeAt(i + 1) >= 0xdc00 && text.charCodeAt(i + 1) <= 0xdfff) {
            bytes += 4;
            i++;
        } else bytes += 3;
    }
    return bytes;
}

/** Número de palabras:CJK Contadas palabra por palabra, las palabras latinas se segmentan por espacios. */
function countWords(text) {
    var cjk = text.match(/[\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]/g);
    var latin = text
        .replace(/[\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]/g, " ")
        .trim()
        .split(/\s+/)
        .filter(function (token) { return token.length > 0; });
    return { cjk: cjk ? cjk.length : 0, latin: latin.length };
}

function textStats(args) {
    var text = args.text;
    if (typeof text !== "string") return { error: "text debe ser una cuerda" };
    var points = toCodePoints(text);
    var nonWhitespace = points.filter(function (ch) { return !/\s/.test(ch); });
    var han = text.match(/[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]/g);
    var lines = text.length === 0 ? [] : text.split(/\r\n|\r|\n/);
    var paragraphs = text.split(/\n\s*\n/).filter(function (block) { return block.trim().length > 0; });
    var words = countWords(text);
    return {
        result: {
            characters: points.length,
            charactersNoWhitespace: nonWhitespace.length,
            chineseCharacters: han ? han.length : 0,
            words: words.latin + words.cjk,
            wordsLatin: words.latin,
            wordsCjk: words.cjk,
            lines: lines.length,
            paragraphs: paragraphs.length,
            bytesUtf8: utf8Length(text)
        },
        note: "characters Estadísticas por punto de código,emoji Contado como 1 carácter."
    };
}

function textTransform(args) {
    var text = args.text;
    var mode = args.mode;
    if (typeof text !== "string") return { error: "text debe ser una cuerda" };
    if (typeof mode !== "string" || mode.length === 0) return { error: "mode no puede estar vacío" };
    var normalized = mode.trim().toLowerCase();
    var tokens = text
        .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
        .replace(/[_\-.]+/g, " ")
        .trim()
        .split(/\s+/)
        .filter(function (token) { return token.length > 0; });
    var value;
    switch (normalized) {
        case "upper":
            value = text.toUpperCase();
            break;
        case "lower":
            value = text.toLowerCase();
            break;
        case "title":
            value = text.replace(/\S+/g, function (word) {
                return word.charAt(0).toUpperCase() + word.slice(1).toLowerCase();
            });
            break;
        case "camel":
            value = tokens.map(function (token, index) {
                var lower = token.toLowerCase();
                if (index === 0) return lower;
                return lower.charAt(0).toUpperCase() + lower.slice(1);
            }).join("");
            break;
        case "snake":
            value = tokens.map(function (token) { return token.toLowerCase(); }).join("_");
            break;
        case "kebab":
            value = tokens.map(function (token) { return token.toLowerCase(); }).join("-");
            break;
        case "trim":
            value = text.trim();
            break;
        case "collapse":
            value = text.replace(/[ \t]+/g, " ").replace(/\n{3,}/g, "\n\n").trim();
            break;
        default:
            return { error: "No compatible mode: " + mode };
    }
    return { result: value };
}

function slugify(args) {
    var text = args.text;
    if (typeof text !== "string") return { error: "text debe ser una cuerda" };
    var keepCjk = args.keepCjk === undefined ? true : !!args.keepCjk;
    var value = text.toLowerCase();
    if (!keepCjk) {
        value = value.replace(/[\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]/g, "-");
    }
    value = value
        .replace(/['"`]/g, "")
        .replace(/[^a-z0-9\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]+/g, "-")
        .replace(/^-+|-+$/g, "")
        .replace(/-{2,}/g, "-");
    if (value.length === 0) return { error: "No se puede generar slug: no hay caracteres disponibles en el contenido" };
    return { result: value, keepCjk: keepCjk };
}

function base64Tool(args) {
    var text = args.text;
    var mode = args.mode;
    if (typeof text !== "string") return { error: "text debe ser una cuerda" };
    var normalized = typeof mode === "string" ? mode.trim().toLowerCase() : "";
    if (normalized === "encode") return { result: b64Encode(text) };
    if (normalized !== "decode") return { error: "mode Debe estar codificado o decode" };
    var compact = text.replace(/\s+/g, "");
    if (!/^[A-Za-z0-9+/]*={0,2}$/.test(compact) || compact.length % 4 !== 0) {
        return { error: "Texto Base64 no válido" };
    }
    try {
        return { result: b64Decode(compact) };
    } catch (e) {
        return { error: "Base64 Falló la decodificación: " + e.message };
    }
}

function jsonTool(args) {
    var text = args.text;
    var mode = args.mode;
    if (typeof text !== "string") return { error: "text debe ser una cuerda" };
    var normalized = typeof mode === "string" ? mode.trim().toLowerCase() : "";
    var parsed;
    try {
        parsed = JSON.parse(text);
    } catch (e) {
        return { error: "JSON El análisis falló: " + e.message };
    }
    function shapeOf(value, depth) {
        if (depth > 6) return "…";
        if (Array.isArray(value)) {
            return { type: "array", length: value.length, sample: value.length > 0 ? shapeOf(value[0], depth + 1) : null };
        }
        if (value === null || typeof value !== "object") return typeof value;
        var keys = Object.keys(value);
        return { type: "object", keys: keys.length, firstKeys: keys.slice(0, 12) };
    }
    if (normalized === "validate") return { result: "valid", shape: shapeOf(parsed, 0) };
    if (normalized === "minify") return { result: JSON.stringify(parsed) };
    if (normalized === "format") {
        var indent = args.indent === undefined ? 2 : parseInt(args.indent, 10);
        if (isNaN(indent) || indent < 0 || indent > 8) return { error: "indent Debe estar entre 0..8" };
        return { result: JSON.stringify(parsed, null, indent) };
    }
    return { error: "mode Debe ser formato,minify o validate" };
}

function linesTool(args) {
    var text = args.text;
    var mode = args.mode;
    if (typeof text !== "string") return { error: "text debe ser una cuerda" };
    var normalized = typeof mode === "string" ? mode.trim().toLowerCase() : "";
    var lines = text.split(/\r\n|\r|\n/);
    var before = lines.length;
    if (args.dropEmpty) {
        lines = lines.filter(function (line) { return line.trim().length > 0; });
    }
    var value;
    switch (normalized) {
        case "dedupe": {
            var seen = {};
            value = lines.filter(function (line) {
                if (Object.prototype.hasOwnProperty.call(seen, line)) return false;
                seen[line] = true;
                return true;
            });
            break;
        }
        case "sort":
            value = lines.slice().sort();
            break;
        case "unique_sort":
            value = lines.slice().sort().filter(function (line, index, array) {
                return index === 0 || array[index - 1] !== line;
            });
            break;
        case "reverse":
            value = lines.slice().reverse();
            break;
        case "number": {
            var width = String(lines.length).length;
            value = lines.map(function (line, index) {
                var label = String(index + 1);
                while (label.length < width) label = " " + label;
                return label + "  " + line;
            });
            break;
        }
        default:
            return { error: "No compatible mode: " + mode };
    }
    return { result: value.join("\n"), lines: value.length, linesBefore: before };
}
