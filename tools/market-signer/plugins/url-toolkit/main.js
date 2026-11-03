/**
 * Muse Complemento:URL caja de herramientas
 *
 * Ejecute el contrato (alojado por WebViewSkillEngine/JsSandbox):
 *  - El host evalúa el archivo completo en el V8 de WebView y luego lo ejecuta.
 *    `JSON.stringify(<functionName>.apply(null, [objeto de parámetro]))`;
 *  - cada herramienta = Una función de nivel superior que solo recibe un objeto de parámetro y devuelve un objeto normal (no utilice JSON.stringify usted mismo);
 *  - Retorno del fracaso { error: "razón china" }, no se lanzará ninguna excepción bajo ninguna circunstancia.
 *
 * Instrucciones de implementación:
 *  - No se basa en el constructor URL/URLSearchParams, sino que utiliza análisis escrito a mano, con un comportamiento predecible;
 *  - Sólo se realizan cálculos de cadenas locales puras: sin conexión a Internet, sin lectura ni escritura de archivos.
 */

var URL_MAX_LENGTH = 20000;
/** decodeURI Caracteres reservados según la semántica: mantienen su porcentaje de codificación y no lo restauran al decodificar en su conjunto. */
var URL_RESERVED_CHARS = "!#$&'()*+,/:;=?@[]";
/** Las rutas permiten personajes que aparecen directamente (RFC 3986 pchar + barra diagonal). */
var URL_PATH_KEEP = /[A-Za-z0-9\-._~!$&'()*+,;=:@\/%]/;
/** El texto de la cadena de consulta permite caracteres que aparecen directamente (reservados & = + ? / separador). */
var URL_QUERY_KEEP = /[A-Za-z0-9\-._~!$&'()*+,;=:@\/?%]/;
/** userinfo Caracteres que aparecen directamente (@ estará codificado para evitar romper la estructura). */
var URL_USERINFO_KEEP = /[A-Za-z0-9\-._~!$&'()*+,;=:%]/;

/** El objeto de parámetro cuenta la historia. */
function urlArgsObject(args) {
    return args !== null && typeof args === "object" && !Array.isArray(args) ? args : {};
}

/** Las excepciones se convierten uniformemente en texto. */
function urlMessage(e) {
    if (e && e.message) return String(e.message);
    return String(e);
}

/** Lectura booleana relajada. */
function urlBool(value, def) {
    if (value === undefined || value === null) return def;
    if (typeof value === "string") {
        var text = value.replace(/\s+/g, "").toLowerCase();
        if (text === "true" || text === "1" || text === "yes") return true;
        if (text === "false" || text === "0" || text === "no" || text === "") return false;
    }
    return !!value;
}

/** Valide la entrada de texto de la URL (no caracteres vacíos, de longitud ni de espacios en blanco). */
function urlCheckInput(value, label) {
    if (typeof value !== "string") return { error: label + " debe ser una cuerda" };
    var text = value.trim();
    if (text === "") return { error: label + " no puede estar vacío" };
    if (text.length > URL_MAX_LENGTH) {
        return { error: label + " Demasiado largo (límite superior " + URL_MAX_LENGTH + " personaje, actual " + text.length + ")" };
    }
    if (/\s/.test(text)) {
        return { error: label + " No puede contener espacios en blanco (escriba %20 para espacios)" };
    }
    return { value: text };
}

/** Decodificación segura de signos de porcentaje: devuelve nulo en caso de error. */
function urlTryDecode(value) {
    try {
        return decodeURIComponent(value);
    } catch (e) {
        return null;
    }
}

/** EncodeURIComponent de un solo carácter: los caracteres sustitutos aislados no generarán una excepción. */
function urlEncodeChar(ch) {
    try {
        return encodeURIComponent(ch);
    } catch (e) {
        return "";
    }
}

/** EncodeURIComponent general seguro. */
function urlEncodeComponent(value) {
    try {
        return encodeURIComponent(String(value));
    } catch (e) {
        return "";
    }
}

/**
 * Codificación porcentual carácter por carácter:keepRe El carácter de hit se conserva tal como está y el %XX legal no se vuelve a codificar.
 * Par de agentes (emoji) se codifica en su totalidad y los caracteres sustitutos huérfanos ilegales se descartan.
 */
function urlEncodeKeep(value, keepRe) {
    var text = String(value);
    var out = "";
    var i = 0;
    while (i < text.length) {
        var ch = text.charAt(i);
        if (ch === "%" && i + 2 <= text.length && /^[0-9A-Fa-f]{2}$/.test(text.substring(i + 1, i + 3))) {
            out += "%" + text.substring(i + 1, i + 3).toUpperCase();
            i += 3;
            continue;
        }
        var code = text.charCodeAt(i);
        if (code >= 0xd800 && code <= 0xdbff && i + 1 < text.length) {
            var next = text.charCodeAt(i + 1);
            if (next >= 0xdc00 && next <= 0xdfff) {
                out += urlEncodeChar(text.substring(i, i + 2));
                i += 2;
                continue;
            }
        }
        out += keepRe.test(ch) ? ch : urlEncodeChar(ch);
        i++;
    }
    return out;
}

/** Decodificar y registrar advertencias; devolver el texto original si falla la decodificación. */
function urlDecodeWithWarning(value, warnings, label) {
    var decoded = urlTryDecode(value);
    if (decoded === null) {
        if (warnings) warnings.push(label + " La codificación porcentual es ilegal y se ha devuelto el texto original.");
        return value;
    }
    return decoded;
}

/** Decodifica un único fragmento en la cadena de consulta:+ Si se trata como un espacio (semántica del formulario), la codificación ilegal se devolverá como el texto original. */
function urlDecodeQueryPart(value, warnings, label) {
    var text = String(value);
    var plus = text.replace(/\+/g, " ");
    var decoded = urlTryDecode(plus);
    if (decoded === null) {
        if (warnings) warnings.push(label + "La codificación porcentual es ilegal y se ha devuelto el texto original.");
        return text;
    }
    return decoded;
}

/**
 * Decodificación general de cadenas de consulta (component=false Semántica): restaurar el porcentaje de codificación,
 * pero sigue ! # $ & ' ( ) * + , / : ; = ? @ [ ] La forma de codificación de estos caracteres delimitadores (igual que decodeURI).
 */
function urlDecodeWhole(text) {
    var input = String(text).replace(/\u0001/g, "");
    var placeholders = [];
    var masked = input.replace(/%[0-9A-Fa-f]{2}/g, function (seq) {
        var ch = String.fromCharCode(parseInt(seq.substring(1), 16));
        if (URL_RESERVED_CHARS.indexOf(ch) >= 0) {
            placeholders.push(ch);
            return "\u0001" + (placeholders.length - 1) + "\u0001";
        }
        return seq;
    });
    try {
        var decoded = decodeURIComponent(masked);
        return decoded.replace(/\u0001(\d+)\u0001/g, function (all, index) {
            return placeholders[parseInt(index, 10)];
        });
    } catch (e) {
        return null;
    }
}

/** URL Segmentación estructural:protocol / authority / path / query / fragment. */
function urlSplitParts(url) {
    var m = /^(?:([A-Za-z][A-Za-z0-9+.\-]*):)?(?:\/\/([^\/?#]*))?([^?#]*)(?:\?([^#]*))?(?:#([\s\S]*))?$/.exec(url);
    if (m === null) return { error: "URL La estructura no se puede analizar" };
    return {
        protocol: m[1] === undefined ? null : m[1].toLowerCase(),
        hasAuthority: m[2] !== undefined,
        authority: m[2] === undefined ? "" : m[2],
        path: m[3] === undefined ? "" : m[3],
        query: m[4] === undefined ? null : m[4],
        fragment: m[5] === undefined ? null : m[5]
    };
}

/** Legalidad de los caracteres del nombre de host (nombre de dominio,IPv4, corchetes IPv6,IDN son liberados). */
function urlCheckHostname(hostname) {
    if (hostname.length === 0) return "URL Falta el nombre de host";
    if (hostname.length > 253) return "El nombre del host es demasiado largo";
    if (/[\s\/?#@\\<>"{}|^`]/.test(hostname)) return "El nombre del host contiene caracteres ilegales: " + hostname;
    return null;
}

/** Verificación de validez del puerto. */
function urlCheckPort(port) {
    if (port === null || port === undefined || port === "") return null;
    if (!/^\d{1,5}$/.test(port)) return "El puerto debe ser numérico.: " + port;
    var num = parseInt(port, 10);
    if (num < 1 || num > 65535) return "El puerto debe estar entre 1-65535: " + port;
    return null;
}

/** Dividir la autoridad en userinfo / hostname / port. */
function urlSplitAuthority(authority) {
    var userinfo = null;
    var hostport = authority;
    var at = authority.lastIndexOf("@");
    if (at >= 0) {
        userinfo = authority.substring(0, at);
        hostport = authority.substring(at + 1);
        if (/[\s\/?#]/.test(userinfo)) return { error: "URL La información del usuario contiene caracteres ilegales." };
    }
    var hostname = hostport;
    var port = null;
    if (hostport.charAt(0) === "[") {
        var close = hostport.indexOf("]");
        if (close < 0) return { error: "IPv6 Falta el anfitrión ]" };
        hostname = hostport.substring(0, close + 1);
        var rest = hostport.substring(close + 1);
        if (rest !== "") {
            if (rest.charAt(0) !== ":") return { error: "IPv6 Personajes ilegales después del anfitrión.: " + rest };
            port = rest.substring(1);
        }
    } else {
        var colon = hostport.indexOf(":");
        if (colon >= 0) {
            hostname = hostport.substring(0, colon);
            port = hostport.substring(colon + 1);
        }
    }
    var hostError = urlCheckHostname(hostname);
    if (hostError) return { error: hostError };
    var portError = urlCheckPort(port);
    if (portError) return { error: portError };
    return {
        userinfo: userinfo,
        hostname: hostname.toLowerCase(),
        port: port === null || port === "" ? null : parseInt(port, 10)
    };
}

/** Analizar la cadena de consulta: devolver parámetros (los parámetros con el mismo nombre son matrices),keys(orden de primera aparición) y pares que conservan el orden. */
function urlParseQueryPairs(query) {
    var params = {};
    var keys = [];
    var pairs = [];
    var warnings = [];
    if (query === null || query === undefined || query === "") {
        return { params: params, keys: keys, pairs: pairs, warnings: warnings };
    }
    var segments = String(query).split("&");
    for (var i = 0; i < segments.length; i++) {
        var segment = segments[i];
        if (segment === "") continue;
        var eq = segment.indexOf("=");
        var rawKey = eq >= 0 ? segment.substring(0, eq) : segment;
        var rawValue = eq >= 0 ? segment.substring(eq + 1) : "";
        var key = urlDecodeQueryPart(rawKey, warnings, "Nombre del parámetro");
        var value = urlDecodeQueryPart(rawValue, warnings, "Valor del parámetro");
        if (key === "") continue;
        if (key === "__proto__") {
            warnings.push("Se omitieron nombres de parámetros ilegales __proto__");
            continue;
        }
        pairs.push({ key: key, value: value });
        if (!Object.prototype.hasOwnProperty.call(params, key)) {
            keys.push(key);
            params[key] = value;
        } else if (Array.isArray(params[key])) {
            params[key].push(value);
        } else {
            params[key] = [params[key], value];
        }
    }
    return { params: params, keys: keys, pairs: pairs, warnings: warnings };
}

/** Valor del parámetro a texto (objeto/matriz por texto JSON,null/undefined Recuerde la cadena vacía). */
function urlParamValue(value, warnings) {
    if (value === undefined || value === null) return "";
    if (typeof value === "object") {
        if (warnings) warnings.push("El valor del parámetro contiene un objeto o una matriz, codificado como texto JSON");
        try {
            return JSON.stringify(value);
        } catch (e) {
            return String(value);
        }
    }
    return String(value);
}

/** El objeto de parámetro se serializa en una cadena de consulta (todos los valores clave están codificados en porcentaje y varios valores con el mismo nombre son matrices). */
function urlSerializeParams(params, warnings) {
    var keys = Object.keys(params);
    var parts = [];
    for (var i = 0; i < keys.length; i++) {
        var key = keys[i];
        if (key === "__proto__") {
            if (warnings) warnings.push("Se omitieron nombres de parámetros ilegales __proto__");
            continue;
        }
        var value = params[key];
        if (Array.isArray(value)) {
            if (value.length === 0) {
                parts.push(urlEncodeComponent(key) + "=");
                continue;
            }
            for (var j = 0; j < value.length; j++) {
                parts.push(urlEncodeComponent(key) + "=" + urlEncodeComponent(urlParamValue(value[j], warnings)));
            }
            continue;
        }
        parts.push(urlEncodeComponent(key) + "=" + urlEncodeComponent(urlParamValue(value, warnings)));
    }
    return parts.join("&");
}

/** pairs Serializado en cadena de consulta. */
function urlSerializePairs(pairs) {
    var parts = [];
    for (var i = 0; i < pairs.length; i++) {
        parts.push(urlEncodeComponent(pairs[i].key) + "=" + urlEncodeComponent(pairs[i].value));
    }
    return parts.join("&");
}

/** pairs Restaurar al formato de parámetros (los parámetros con el mismo nombre son matrices). */
function urlPairsToParams(pairs) {
    var params = {};
    var keys = [];
    for (var i = 0; i < pairs.length; i++) {
        var key = pairs[i].key;
        var value = pairs[i].value;
        if (!Object.prototype.hasOwnProperty.call(params, key)) {
            keys.push(key);
            params[key] = value;
        } else if (Array.isArray(params[key])) {
            params[key].push(value);
        } else {
            params[key] = [params[key], value];
        }
    }
    return { params: params, keys: keys };
}

/** Reconstruya la URL completa con la nueva cadena de consulta (conserve el fragmento original y elimine la cadena de consulta anterior). */
function urlRebuildQuery(base, query) {
    var hashIndex = base.indexOf("#");
    var fragment = hashIndex >= 0 ? base.substring(hashIndex) : "";
    var head = hashIndex >= 0 ? base.substring(0, hashIndex) : base;
    var qIndex = head.indexOf("?");
    if (qIndex >= 0) head = head.substring(0, qIndex);
    return head + (query !== "" ? "?" + query : "") + fragment;
}

/** Herramienta url_parse: divide la URL en componentes y expande los parámetros de consulta. */
function urlParse(args) {
    args = urlArgsObject(args);
    var inputCheck = urlCheckInput(args.url, "url");
    if (inputCheck.error) return inputCheck;
    var url = inputCheck.value;
    var parts = urlSplitParts(url);
    if (parts.error) return parts;
    if (parts.protocol === null && !parts.hasAuthority && url.indexOf("://") >= 0) {
        return { error: "URL El formato del acuerdo es ilegal (el acuerdo debe comenzar con una letra y solo puede contener letras, números,+,-,.)" };
    }
    var warnings = [];
    var userinfo = null;
    var username = null;
    var password = null;
    var hostname = null;
    var host = null;
    var port = null;
    if (parts.hasAuthority) {
        var authority = urlSplitAuthority(parts.authority);
        if (authority.error) return { error: authority.error };
        hostname = authority.hostname;
        port = authority.port;
        host = port === null ? hostname : hostname + ":" + port;
        if (authority.userinfo !== null) {
            userinfo = authority.userinfo;
            var colon = userinfo.indexOf(":");
            var rawUser = colon >= 0 ? userinfo.substring(0, colon) : userinfo;
            var rawPass = colon >= 0 ? userinfo.substring(colon + 1) : null;
            username = urlDecodeWithWarning(rawUser, warnings, "userinfo");
            password = rawPass === null ? null : urlDecodeWithWarning(rawPass, warnings, "userinfo");
        }
    }
    var pathDecoded = urlDecodeWithWarning(parts.path, warnings, "path");
    var fragmentDecoded = parts.fragment === null ? null : urlDecodeWithWarning(parts.fragment, warnings, "fragment");
    var queryInfo = urlParseQueryPairs(parts.query);
    for (var i = 0; i < queryInfo.warnings.length; i++) warnings.push(queryInfo.warnings[i]);
    return {
        result: {
            protocol: parts.protocol,
            host: host,
            hostname: hostname,
            port: port,
            path: parts.path,
            pathDecoded: pathDecoded,
            query: parts.query,
            params: queryInfo.params,
            keys: queryInfo.keys,
            fragment: parts.fragment,
            fragmentDecoded: fragmentDecoded,
            userinfo: userinfo,
            username: username,
            password: password,
            hasAuthority: parts.hasAuthority,
            relative: parts.protocol === null
        },
        warnings: warnings,
        note: "protocol sin dos puntos;host para hostname[:port];pathDecoded es el camino decodificado;" +
            "query no contiene ?;fragment No contiene #;params El parámetro con el mismo nombre es una matriz;" +
            "relative Si es verdadero, la entrada no tiene un protocolo escrito (resuelto por referencia relativa)."
    };
}

/** Herramienta url_build: ensambla URL a partir de objetos de widget, compatible con la salida url_parse. */
function urlBuild(args) {
    args = urlArgsObject(args);
    var warnings = [];
    var protocol = null;
    if (args.protocol !== undefined && args.protocol !== null && String(args.protocol).trim() !== "") {
        protocol = String(args.protocol).trim().replace(/:\/\/$/, "").replace(/:$/, "");
        if (!/^[A-Za-z][A-Za-z0-9+.\-]*$/.test(protocol)) {
            return { error: "protocol ilegal: " + args.protocol };
        }
        protocol = protocol.toLowerCase();
    }
    var hostname = null;
    var port = null;
    if (args.hostname !== undefined && args.hostname !== null && String(args.hostname).trim() !== "") {
        hostname = String(args.hostname).trim();
        var hostnameError = urlCheckHostname(hostname);
        if (hostnameError) return { error: hostnameError };
    }
    if (args.host !== undefined && args.host !== null && String(args.host).trim() !== "") {
        var parsedHost = urlSplitAuthority(String(args.host).trim());
        if (parsedHost.error) return { error: "host ilegal: " + parsedHost.error };
        if (hostname === null) hostname = parsedHost.hostname;
        if (parsedHost.port !== null) port = parsedHost.port;
    }
    if (args.port !== undefined && args.port !== null && String(args.port).trim() !== "") {
        var portText = String(args.port).trim().replace(/^:/, "");
        var portError = urlCheckPort(portText);
        if (portError) return { error: portError };
        port = parseInt(portText, 10);
    }
    var userinfo = null;
    if (args.userinfo !== undefined && args.userinfo !== null && String(args.userinfo) !== "") {
        if (hostname === null) return { error: "Al proporcionar información de usuario, también debe proporcionar host o hostname" };
        userinfo = urlEncodeKeep(String(args.userinfo), URL_USERINFO_KEEP);
    } else if (args.username !== undefined && args.username !== null && String(args.username) !== "") {
        if (hostname === null) return { error: "El nombre de usuario debe proporcionarse junto con el host o hostname" };
        var user = urlEncodeComponent(String(args.username));
        var pass = args.password === undefined || args.password === null ? null : String(args.password);
        userinfo = pass === null ? user : user + ":" + urlEncodeComponent(pass);
    } else if (args.password !== undefined && args.password !== null && String(args.password) !== "") {
        return { error: "Proporcionar solo la contraseña no es válido; indíquela al mismo tiempo username" };
    }
    var rawPath = "";
    if (args.path !== undefined && args.path !== null) rawPath = String(args.path);
    else if (args.pathname !== undefined && args.pathname !== null) rawPath = String(args.pathname);
    var path = urlEncodeKeep(rawPath, URL_PATH_KEEP);
    if (hostname !== null && path !== "" && path.charAt(0) !== "/") path = "/" + path;
    var query = null;
    if (args.params !== undefined && args.params !== null) {
        if (typeof args.params !== "object" || Array.isArray(args.params)) {
            return { error: "params Debe ser un objeto (el valor puede ser una cadena, un número, una matriz,true/false o nulo)" };
        }
        query = urlSerializeParams(args.params, warnings);
    } else if (args.query !== undefined && args.query !== null) {
        if (typeof args.query === "object") {
            if (Array.isArray(args.query)) return { error: "query No puede ser una matriz; utilice parámetros de forma de objeto. params" };
            query = urlSerializeParams(args.query, warnings);
        } else {
            var rawQuery = String(args.query).replace(/^\?/, "");
            query = urlEncodeKeep(rawQuery, URL_QUERY_KEEP);
        }
    }
    var fragment = null;
    if (args.fragment !== undefined && args.fragment !== null && String(args.fragment) !== "") {
        fragment = urlEncodeKeep(String(args.fragment).replace(/^#/, ""), URL_QUERY_KEEP);
    }
    if (protocol === null && hostname === null && path === "" && query === null && fragment === null) {
        return { error: "No hay componentes que se puedan ensamblar (al menos protocolo,host/hostname o camino)" };
    }
    if (protocol !== null && hostname === null && path === "") {
        return { error: "Solo el protocolo no puede formar una URL; proporcione también el host/nombre de host o path" };
    }
    var prefix = "";
    if (hostname !== null) {
        prefix = (protocol === null ? "" : protocol + ":") + "//" +
            (userinfo === null ? "" : userinfo + "@") + hostname + (port === null ? "" : ":" + port);
    } else if (protocol !== null) {
        prefix = protocol + ":";
    }
    var url = prefix + path;
    if (query !== null && query !== "") url += "?" + query;
    if (fragment !== null) url += "#" + fragment;
    if (url.length > URL_MAX_LENGTH) {
        return { error: "El resultado del ensamblaje es demasiado largo (el límite superior " + URL_MAX_LENGTH + " personajes)" };
    }
    return {
        result: url,
        query: query === null ? "" : query,
        warnings: warnings,
        note: "hostname Cuando se proporciona junto con el host, prevalecerá el nombre del host;params tiene prioridad sobre la consulta;" +
            "path Los caracteres ilegales, como los espacios, se codificarán automáticamente en porcentaje y el %XX legal no se volverá a codificar."
    };
}

/** encode / decode Implementación del patrón:component=true Procesado como un valor único,false Procesado como una cadena de consulta completa. */
function urlQueryCodec(args, mode, base, baseInfo, explicitQuery) {
    var component = urlBool(args.component, true);
    var input = null;
    var fromBase = false;
    if (typeof args.text === "string") {
        input = args.text;
    } else if (typeof args.value === "string") {
        input = args.value;
    } else if (typeof args.value === "number") {
        input = String(args.value);
    } else if (explicitQuery !== null) {
        input = explicitQuery;
    } else if (baseInfo !== null) {
        input = baseInfo.query === null ? "" : baseInfo.query;
        fromBase = true;
    }
    if (input === null) return { error: mode + " Se requiere texto (también acepta valor,query o cadena de consulta en base)" };
    if (input.length > URL_MAX_LENGTH) {
        return { error: "text Demasiado largo (límite superior " + URL_MAX_LENGTH + " personaje, actual " + input.length + ")" };
    }
    var output;
    if (mode === "encode") {
        output = component ? urlEncodeComponent(input) : urlEncodeKeep(input, URL_QUERY_KEEP);
    } else if (component) {
        var decodedComponent = urlTryDecode(String(input).replace(/\+/g, " "));
        if (decodedComponent === null) return { error: "Error de decodificación: el texto contiene un porcentaje de codificación ilegal" };
        output = decodedComponent;
    } else {
        var decodedWhole = urlDecodeWhole(input);
        if (decodedWhole === null) return { error: "Error de decodificación: el texto contiene un porcentaje de codificación ilegal" };
        output = decodedWhole;
    }
    var response = {
        result: output,
        mode: mode,
        component: component,
        query: null,
        url: null,
        note: component
            ? "component=true Manejado como un valor único: se usa encodeURIComponent y la decodificación trata + como un espacio."
            : "component=false Procesado como una cadena de consulta completa: retenido = & + ? delimitador, no trate + como un espacio."
    };
    if (fromBase || (explicitQuery !== null && base !== null)) {
        response.query = output;
        if (/\s/.test(output)) {
            response.warnings = ["El resultado contiene caracteres en blanco y no se vuelve a escribir en la URL (codifique primero)"];
        } else {
            response.url = urlRebuildQuery(base, output);
        }
    }
    return response;
}

/** Herramienta query_tool: análisis, adición, modificación, eliminación y codificación y decodificación de parámetros de consulta. */
function queryTool(args) {
    args = urlArgsObject(args);
    var mode = typeof args.mode === "string" ? args.mode.replace(/\s+/g, "").toLowerCase() : "";
    if (mode === "") return { error: "mode no puede estar vacío (parse/set/remove/encode/decode)" };
    if (mode !== "parse" && mode !== "set" && mode !== "remove" && mode !== "encode" && mode !== "decode") {
        return { error: "No compatible mode: " + args.mode + "(opcional analizar/configurar/eliminar/codificar/decodificar)" };
    }
    var base = null;
    var baseInfo = null;
    if (args.base !== undefined && args.base !== null) {
        if (typeof args.base !== "string") return { error: "base Debe ser una cadena (URL completa)" };
        if (args.base.trim() !== "") {
            var baseCheck = urlCheckInput(args.base, "base");
            if (baseCheck.error) return baseCheck;
            base = baseCheck.value;
            var parsedBase = urlParse({ url: base });
            if (parsedBase.error) return { error: "base El análisis falló: " + parsedBase.error };
            baseInfo = parsedBase.result;
        }
    }
    var explicitQuery = null;
    if (args.query !== undefined && args.query !== null) {
        if (typeof args.query === "string") explicitQuery = args.query.replace(/^\?/, "");
        else if (!(typeof args.query === "object" && !Array.isArray(args.query) && mode === "set")) {
            return { error: "query Debe ser una cadena (cadena de consulta) o un objeto de parámetro en modo establecido" };
        }
    }
    if (mode === "encode" || mode === "decode") {
        return urlQueryCodec(args, mode, base, baseInfo, explicitQuery);
    }
    var query;
    if (explicitQuery !== null) {
        query = explicitQuery;
    } else if (baseInfo !== null) {
        query = baseInfo.query === null ? "" : baseInfo.query;
    } else {
        return { error: mode + " Requiere consulta (cadena de consulta) o base (URL completa)" };
    }
    if (query.length > URL_MAX_LENGTH) {
        return { error: "query Demasiado largo (límite superior " + URL_MAX_LENGTH + " personajes)" };
    }
    var parsedQuery = urlParseQueryPairs(query);
    if (mode === "parse") {
        var pairs = [];
        for (var i = 0; i < parsedQuery.pairs.length; i++) {
            pairs.push({ name: parsedQuery.pairs[i].key, value: parsedQuery.pairs[i].value });
        }
        return {
            result: parsedQuery.params,
            query: query,
            url: baseInfo === null ? null : base,
            pairs: pairs,
            keys: parsedQuery.keys,
            count: parsedQuery.keys.length,
            pairCount: pairs.length,
            warnings: parsedQuery.warnings,
            note: "result Es un objeto clave-valor (el parámetro con el mismo nombre es una matriz);pairs Mantener el orden original con duplicados."
        };
    }
    var updates = args.params;
    if ((updates === undefined || updates === null) && args.query !== null && typeof args.query === "object" && !Array.isArray(args.query)) {
        updates = args.query;
    }
    var resultPairs = [];
    if (mode === "set") {
        if (updates === undefined || updates === null) {
            return { error: "set Es necesario proporcionar un objeto de parámetros (la clave es el nombre del parámetro y el valor es el nuevo valor;null Representa eliminación, la matriz representa valores múltiples)" };
        }
        if (typeof updates !== "object" || Array.isArray(updates)) return { error: "params debe ser un objeto" };
        var updateKeys = Object.keys(updates);
        if (updateKeys.length === 0) return { error: "params No puede ser un objeto nulo" };
        var pending = {};
        var pendingOrder = [];
        for (var u = 0; u < updateKeys.length; u++) {
            var key = updateKeys[u];
            if (key === "__proto__") return { error: "El nombre del parámetro __proto__ es ilegal" };
            var value = updates[key];
            var list = [];
            if (value === null || value === undefined) {
                list = [];
            } else if (Array.isArray(value)) {
                for (var v = 0; v < value.length; v++) {
                    var item = value[v];
                    if (item !== null && typeof item === "object") {
                        return { error: "params." + key + " Los elementos de la matriz no pueden ser objetos." };
                    }
                    list.push(item === null || item === undefined ? "" : String(item));
                }
            } else if (typeof value === "object") {
                return { error: "params." + key + " El valor no puede ser un objeto; primero conviértalo en una cadena." };
            } else {
                list.push(String(value));
            }
            pending[key] = list;
            pendingOrder.push(key);
        }
        var applied = {};
        for (var p = 0; p < parsedQuery.pairs.length; p++) {
            var pair = parsedQuery.pairs[p];
            if (!Object.prototype.hasOwnProperty.call(pending, pair.key)) {
                resultPairs.push({ key: pair.key, value: pair.value });
                continue;
            }
            if (Object.prototype.hasOwnProperty.call(applied, pair.key)) continue;
            applied[pair.key] = true;
            var newValues = pending[pair.key];
            for (var q = 0; q < newValues.length; q++) {
                resultPairs.push({ key: pair.key, value: newValues[q] });
            }
        }
        for (var w = 0; w < pendingOrder.length; w++) {
            var appendKey = pendingOrder[w];
            if (Object.prototype.hasOwnProperty.call(applied, appendKey)) continue;
            applied[appendKey] = true;
            var appendValues = pending[appendKey];
            for (var x = 0; x < appendValues.length; x++) {
                resultPairs.push({ key: appendKey, value: appendValues[x] });
            }
        }
    } else {
        var removeKeys = [];
        if (args.keys !== undefined && args.keys !== null) {
            if (typeof args.keys === "string") {
                if (args.keys !== "") removeKeys.push(args.keys);
            } else if (Array.isArray(args.keys)) {
                for (var k = 0; k < args.keys.length; k++) {
                    if (typeof args.keys[k] !== "string") return { error: "keys Debe ser una matriz de cadenas" };
                    if (args.keys[k] !== "") removeKeys.push(args.keys[k]);
                }
            } else {
                return { error: "keys Debe ser una cadena o una serie de cadenas." };
            }
        } else if (updates !== undefined && updates !== null && typeof updates === "object" && !Array.isArray(updates)) {
            var objectKeys = Object.keys(updates);
            for (var o = 0; o < objectKeys.length; o++) removeKeys.push(objectKeys[o]);
        } else {
            var single = args.key !== undefined ? args.key : args.name;
            if (typeof single === "string" && single !== "") {
                removeKeys.push(single);
            } else {
                return { error: "remove Debe proporcionar claves (matriz de nombres de parámetros que se eliminarán) o parámetros (cualquiera que sea la clave) o key" };
            }
        }
        if (removeKeys.length === 0) return { error: "remove No se dieron nombres de parámetros para eliminar" };
        var removeMap = {};
        for (var r = 0; r < removeKeys.length; r++) removeMap[removeKeys[r]] = true;
        for (var s = 0; s < parsedQuery.pairs.length; s++) {
            if (Object.prototype.hasOwnProperty.call(removeMap, parsedQuery.pairs[s].key)) continue;
            resultPairs.push({ key: parsedQuery.pairs[s].key, value: parsedQuery.pairs[s].value });
        }
    }
    var serialized = urlSerializePairs(resultPairs);
    var before = urlSerializePairs(parsedQuery.pairs);
    var summary = urlPairsToParams(resultPairs);
    return {
        result: serialized,
        query: serialized,
        url: baseInfo === null ? null : urlRebuildQuery(base, serialized),
        params: summary.params,
        keys: summary.keys,
        pairCount: resultPairs.length,
        changed: serialized !== before,
        warnings: parsedQuery.warnings,
        note: "query no contiene ?;Los parámetros con el mismo nombre se conservan en su orden original;set Un valor nulo indica que el parámetro se elimina."
    };
}
