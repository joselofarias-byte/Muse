/**
 * Muse Complemento: Caja de herramientas de datos (CSV / JSON)
 *
 * Ejecute el contrato (alojado por WebViewSkillEngine/JsSandbox):
 *  - El host evalúa el archivo completo en el V8 de WebView y luego lo ejecuta.
 *    `JSON.stringify(<functionName>.apply(null, [objeto de parámetro]))`;
 *  - cada herramienta = Una función de nivel superior que solo recibe un objeto de parámetro y devuelve un objeto normal (no utilice JSON.stringify usted mismo);
 *  - Retorno del fracaso { error: "razón china" }, no se lanzará ninguna excepción bajo ninguna circunstancia.
 *
 * CSV reglas (RFC 4180 Análisis compatible y suelto):
 *  - Los campos entre comillas dobles pueden contener delimitadores, nuevas líneas y comillas dobles escapadas ("");
 *  - Cuando hay un encabezado, la primera fila es el nombre de la clave y cuando no hay encabezado, el nombre de la clave es col1..colN;
 *  - Se ignoran las líneas completamente en blanco.
 *  - Límite superior: 500.000 caracteres de texto, 200 columnas y un máximo de 10.000 líneas de salida a la vez.
 * Computación local pura: sin acceso a Internet, sin lectura ni escritura de archivos.
 */

var CSV_MAX_TEXT = 500000;
var CSV_MAX_ROWS = 10000;
var CSV_ROWS_DEFAULT = 1000;
var CSV_MAX_COLUMNS = 200;
var CSV_NUMBER_RE = /^[+-]?(\d+(\.\d+)?|\.\d+)([eE][+-]?\d+)?$/;

/** El objeto de parámetro cuenta la historia. */
function csvArgsObject(args) {
    return args !== null && typeof args === "object" && !Array.isArray(args) ? args : {};
}

/** Las excepciones se convierten uniformemente en texto. */
function csvMessage(e) {
    if (e && e.message) return String(e.message);
    return String(e);
}

/** Lectura booleana relajada. */
function csvBool(value, def) {
    if (value === undefined || value === null) return def;
    if (typeof value === "string") {
        var text = value.replace(/\s+/g, "").toLowerCase();
        if (text === "true" || text === "1" || text === "yes") return true;
        if (text === "false" || text === "0" || text === "no" || text === "") return false;
    }
    return !!value;
}

/** Normalización del delimitador: coma predeterminada, compatible ; | Caracteres individuales como tabulación. */
function csvNormalizeDelimiter(value) {
    if (value === undefined || value === null || value === "") return { delimiter: "," };
    if (typeof value !== "string") return { error: "delimiter debe ser una cuerda" };
    var text = value;
    if (text === "\\t" || text.toLowerCase() === "tab") text = "\t";
    if (text.length !== 1) return { error: "delimiter Debe ser un solo carácter (p. ej. , ; | o pestaña)" };
    if (text === "\"" || text === "\r" || text === "\n") return { error: "delimiter No pueden ser comillas dobles ni nuevas líneas." };
    return { delimiter: text };
}

/** Leer parámetro de texto CSV (parámetro principal csv, compatible text / data / input alias). */
function csvReadText(args) {
    var candidates = [args.csv, args.text, args.data, args.input];
    var value = null;
    for (var i = 0; i < candidates.length; i++) {
        if (typeof candidates[i] === "string") {
            value = candidates[i];
            break;
        }
    }
    if (value === null) return { error: "Falta el parámetro csv (requiere una cadena de texto CSV)" };
    if (value.length > CSV_MAX_TEXT) {
        return { error: "CSV Texto demasiado largo (máximo " + CSV_MAX_TEXT + " personaje, actual " + value.length + ")" };
    }
    var text = value.replace(/^\uFEFF/, "");
    if (text.replace(/\s+/g, "") === "") return { error: "CSV El texto está vacío." };
    return { text: text };
}

/**
 * Analice CSV en una matriz bidimensional (RFC 4180 relajado).
 * Regresar { rows, error };Las comillas dobles no cerradas se consideran un error de formato.
 */
function csvParseRows(text, delimiter) {
    var rows = [];
    var quotedFlags = [];
    var row = [];
    var field = "";
    var rowQuoted = false;
    var inQuotes = false;
    var i = 0;
    var n = text.length;
    while (i < n) {
        var ch = text.charAt(i);
        if (inQuotes) {
            if (ch === "\"") {
                if (text.charAt(i + 1) === "\"") {
                    field += "\"";
                    i += 2;
                    continue;
                }
                inQuotes = false;
                i++;
                continue;
            }
            field += ch;
            i++;
            continue;
        }
        if (ch === "\"") {
            if (field.length === 0) {
                inQuotes = true;
                rowQuoted = true;
                i++;
                continue;
            }
            field += ch;
            i++;
            continue;
        }
        if (ch === delimiter) {
            row.push(field);
            field = "";
            i++;
            continue;
        }
        if (ch === "\r" || ch === "\n") {
            if (ch === "\r" && text.charAt(i + 1) === "\n") i++;
            row.push(field);
            rows.push(row);
            quotedFlags.push(rowQuoted);
            row = [];
            field = "";
            rowQuoted = false;
            i++;
            continue;
        }
        field += ch;
        i++;
    }
    if (inQuotes) return { error: "CSV Hay una comilla doble no cerrada." };
    // rowQuoted Cuente también: el final es "" Las líneas no se pueden perder cuando los campos vacíos están entre comillas.
    if (field.length > 0 || row.length > 0 || rowQuoted) {
        row.push(field);
        rows.push(row);
        quotedFlags.push(rowQuoted);
    }
    var filtered = [];
    for (var r = 0; r < rows.length; r++) {
        if (!quotedFlags[r] && rows[r].length === 1 && rows[r][0] === "") continue;
        filtered.push(rows[r]);
    }
    return { rows: filtered };
}

/** Generar nombres de columnas: los nombres de columnas vacías se completan con colN, los nombres duplicados se agregan con el sufijo _2/_3,__proto__ Reemplace con columna. */
function csvUniqueNames(headerRow, width) {
    var names = [];
    var seen = {};
    for (var i = 0; i < width; i++) {
        var raw = headerRow !== null && i < headerRow.length && headerRow[i] !== undefined
            ? String(headerRow[i]) : "";
        var base = raw.replace(/^\uFEFF/, "").trim();
        if (base === "" || base === "__proto__") base = "col" + (i + 1);
        var name = base;
        var suffix = 1;
        while (Object.prototype.hasOwnProperty.call(seen, name)) {
            suffix++;
            name = base + "_" + suffix;
        }
        seen[name] = true;
        names.push(name);
    }
    return names;
}

/** Convertir el valor de la celda a texto:null/undefined es una cadena vacía, objeto/Las matrices utilizan texto JSON. */
function csvFormatCell(value) {
    if (value === undefined || value === null) return "";
    if (typeof value === "string") return value;
    if (typeof value === "number" || typeof value === "boolean") return String(value);
    var text;
    try {
        text = JSON.stringify(value);
    } catch (e) {
        text = String(value);
    }
    return text === undefined ? "" : text;
}

/** RFC 4180 Escape: los valores que contienen delimitadores, comillas o nuevas líneas se citan y se incluyen entre " escrito como "". */
function csvQuoteCell(text, delimiter) {
    if (text.indexOf("\"") >= 0 || text.indexOf(delimiter) >= 0 ||
        text.indexOf("\n") >= 0 || text.indexOf("\r") >= 0) {
        return "\"" + text.replace(/"/g, "\"\"") + "\"";
    }
    return text;
}

/** Determine si la cadena es un número puro (permitiendo signos más y menos, decimales y notación científica). */
function csvIsNumber(value) {
    var text = String(value).trim();
    if (text === "") return false;
    if (!CSV_NUMBER_RE.test(text)) return false;
    var num = parseFloat(text);
    if (isNaN(num) || num === Infinity || num === -Infinity) return false;
    return true;
}

/** Clasificación numérica que conserva 6 decimales (para evitar que se escriban errores de punto flotante en el resultado). */
function csvRound(value) {
    if (value === null || value === undefined) return null;
    if (typeof value !== "number" || isNaN(value) || value === Infinity || value === -Infinity) return null;
    return Math.round(value * 1000000) / 1000000;
}

/** Obtenga los primeros valores límite que aparecen con más frecuencia (el mismo número de veces está en orden de primera aparición). */
function csvTopValues(freq, limit) {
    var keys = Object.keys(freq);
    var items = [];
    for (var i = 0; i < keys.length; i++) {
        items.push({ value: keys[i].substring(1), count: freq[keys[i]], order: i });
    }
    items.sort(function (a, b) {
        if (b.count !== a.count) return b.count - a.count;
        return a.order - b.order;
    });
    var out = [];
    for (var j = 0; j < items.length && j < limit; j++) {
        out.push({ value: items[j].value, count: items[j].count });
    }
    return out;
}

/** Lea parámetros enteros positivos como maxRows/top. */
function csvReadCount(value, def, max, label) {
    if (value === undefined || value === null || value === "") return { value: def };
    var num = typeof value === "number" ? value : parseInt(String(value), 10);
    if (isNaN(num) || num < 1) return { error: label + " debe ser >=1 entero" };
    return { value: num > max ? max : Math.floor(num) };
}

/** Herramienta csv_to_json:CSV Convierte texto en matriz de objetos. */
function csvToJson(args) {
    args = csvArgsObject(args);
    var input = csvReadText(args);
    if (input.error) return input;
    var delim = csvNormalizeDelimiter(args.delimiter);
    if (delim.error) return delim;
    var header = csvBool(args.header, true);
    var trim = csvBool(args.trim, false);
    var maxRows = csvReadCount(args.maxRows, CSV_ROWS_DEFAULT, CSV_MAX_ROWS, "maxRows");
    if (maxRows.error) return maxRows;
    var parsed = csvParseRows(input.text, delim.delimiter);
    if (parsed.error) return parsed;
    var rows = parsed.rows;
    if (rows.length === 0) return { error: "CSV No hay contenido para analizar" };
    var width = 0;
    for (var i = 0; i < rows.length; i++) {
        if (rows[i].length > width) width = rows[i].length;
    }
    if (width > CSV_MAX_COLUMNS) {
        return { error: "El número de columnas excede el límite superior (máximo " + CSV_MAX_COLUMNS + " columna, actual " + width + " columna)" };
    }
    var columns = csvUniqueNames(header ? rows[0] : null, width);
    var dataRows = header ? rows.slice(1) : rows.slice(0);
    var truncated = false;
    if (dataRows.length > maxRows.value) {
        dataRows = dataRows.slice(0, maxRows.value);
        truncated = true;
    }
    var records = [];
    for (var r = 0; r < dataRows.length; r++) {
        var cells = dataRows[r];
        var record = {};
        for (var c = 0; c < columns.length; c++) {
            var value = c < cells.length && cells[c] !== undefined ? cells[c] : "";
            record[columns[c]] = trim ? value.replace(/^\s+|\s+$/g, "") : value;
        }
        records.push(record);
    }
    return {
        result: records,
        columns: columns,
        rowCount: records.length,
        totalRows: header ? rows.length - 1 : rows.length,
        truncated: truncated,
        delimiter: delim.delimiter,
        header: header,
        note: "Las comas y los saltos de línea dentro de los campos se manejan entre comillas dobles.\"\" Volver a comillas dobles literales;" +
            "truncated es cierto para indicar que sólo el primero " + maxRows.value + " DE ACUERDO."
    };
}

/** Herramienta json_to_csv: convierte una matriz de objetos a CSV (el orden de las columnas se toma en el orden en que apareció la primera unión). */
function jsonToCsv(args) {
    args = csvArgsObject(args);
    var raw = args.data;
    if (raw === undefined || raw === null) raw = args.json;
    if (raw === undefined || raw === null) raw = args.rows;
    if (raw === undefined || raw === null) return { error: "Parámetro de datos faltantes (JSON matriz, o su forma de cadena)" };
    var data = raw;
    if (typeof data === "string") {
        var trimmed = data.replace(/^\s+|\s+$/g, "");
        if (trimmed === "") return { error: "data es una cadena vacía y no se puede analizar en una matriz JSON." };
        try {
            data = JSON.parse(trimmed);
        } catch (e) {
            return { error: "JSON El análisis falló: " + csvMessage(e) };
        }
    }
    if (!Array.isArray(data)) return { error: "data Debe ser una matriz JSON" };
    if (data.length > CSV_MAX_ROWS) {
        return { error: "Demasiados elementos de matriz (límite superior " + CSV_MAX_ROWS + " barra, actual " + data.length + " artículo)" };
    }
    var delim = csvNormalizeDelimiter(args.delimiter);
    if (delim.error) return delim;
    var header = csvBool(args.header, true);
    var crlf = csvBool(args.crlf, false);
    var eol = crlf ? "\r\n" : "\n";
    if (data.length === 0) {
        return {
            result: "",
            rows: 0,
            columns: [],
            delimiter: delim.delimiter,
            header: header,
            note: "Si la matriz está vacía, se genera texto vacío."
        };
    }
    var allObjects = true;
    var allArrays = true;
    for (var i = 0; i < data.length; i++) {
        var item = data[i];
        if (item === null || typeof item !== "object") {
            allObjects = false;
            allArrays = false;
        } else if (Array.isArray(item)) {
            allObjects = false;
        } else {
            allArrays = false;
        }
    }
    var columns = [];
    var table = [];
    var warnings = [];
    if (allObjects) {
        var seen = {};
        for (var o = 0; o < data.length; o++) {
            var keys = Object.keys(data[o]);
            for (var k = 0; k < keys.length; k++) {
                var key = keys[k];
                if (key === "__proto__") {
                    if (warnings.length === 0) warnings.push("Se omitieron nombres de campos ilegales __proto__");
                    continue;
                }
                if (!Object.prototype.hasOwnProperty.call(seen, key)) {
                    seen[key] = true;
                    columns.push(key);
                }
            }
        }
        for (var r = 0; r < data.length; r++) {
            var cells = [];
            for (var c = 0; c < columns.length; c++) {
                cells.push(csvFormatCell(data[r][columns[c]]));
            }
            table.push(cells);
        }
    } else if (allArrays) {
        var width = 0;
        for (var a = 0; a < data.length; a++) {
            if (data[a].length > width) width = data[a].length;
        }
        if (width > CSV_MAX_COLUMNS) {
            return { error: "El número de columnas excede el límite superior (máximo " + CSV_MAX_COLUMNS + " columna, actual " + width + " columna)" };
        }
        columns = csvUniqueNames(null, width);
        for (var b = 0; b < data.length; b++) {
            var rowCells = [];
            for (var d = 0; d < width; d++) {
                rowCells.push(csvFormatCell(data[b][d]));
            }
            table.push(rowCells);
        }
    } else {
        columns = ["value"];
        for (var p = 0; p < data.length; p++) {
            table.push([csvFormatCell(data[p])]);
        }
    }
    var lines = [];
    var useHeader = allObjects && header;
    if (useHeader) {
        var headerCells = [];
        for (var h = 0; h < columns.length; h++) {
            headerCells.push(csvQuoteCell(columns[h], delim.delimiter));
        }
        lines.push(headerCells.join(delim.delimiter));
    }
    for (var t = 0; t < table.length; t++) {
        var outCells = [];
        for (var q = 0; q < table[t].length; q++) {
            outCells.push(csvQuoteCell(table[t][q], delim.delimiter));
        }
        lines.push(outCells.join(delim.delimiter));
    }
    var response = {
        result: lines.join(eol),
        rows: table.length,
        columns: columns,
        delimiter: delim.delimiter,
        header: useHeader,
        note: "Los valores que contienen delimitadores, comillas dobles o nuevas líneas se citan y configuran automáticamente de acuerdo con RFC 4180. \" Escapó como \"\";" +
            "Los campos faltantes se dejan en blanco; Las filas del encabezado se generan solo si el objeto es una matriz y el encabezado es verdadero."
    };
    if (warnings.length > 0) response.warnings = warnings;
    return response;
}

/** Herramienta csv_stats: tipo de estadísticas de columna, recuento no nulo, distribución numérica y valores de alta frecuencia. */
function csvStats(args) {
    args = csvArgsObject(args);
    var input = csvReadText(args);
    if (input.error) return input;
    var delim = csvNormalizeDelimiter(args.delimiter);
    if (delim.error) return delim;
    var header = csvBool(args.header, true);
    var top = csvReadCount(args.top, 3, 10, "top");
    if (top.error) return top;
    var parsed = csvParseRows(input.text, delim.delimiter);
    if (parsed.error) return parsed;
    var rows = parsed.rows;
    if (rows.length === 0) return { error: "CSV No hay contenido para analizar" };
    var width = 0;
    for (var i = 0; i < rows.length; i++) {
        if (rows[i].length > width) width = rows[i].length;
    }
    if (width > CSV_MAX_COLUMNS) {
        return { error: "El número de columnas excede el límite superior (máximo " + CSV_MAX_COLUMNS + " columna, actual " + width + " columna)" };
    }
    var columns = csvUniqueNames(header ? rows[0] : null, width);
    var dataRows = header ? rows.slice(1) : rows.slice(0);
    if (dataRows.length === 0) return { error: "CSV Sólo filas de encabezado, sin filas de datos" };
    if (dataRows.length > CSV_MAX_ROWS * 10) {
        return { error: "Demasiadas filas de datos (límite superior " + (CSV_MAX_ROWS * 10) + " Bien, actual " + dataRows.length + " bien)" };
    }
    var stats = [];
    for (var c = 0; c < width; c++) {
        var distinct = {};
        var freq = {};
        var distinctCount = 0;
        var nonEmpty = 0;
        var empty = 0;
        var numericCount = 0;
        var min = null;
        var max = null;
        var sum = 0;
        for (var r = 0; r < dataRows.length; r++) {
            var cells = dataRows[r];
            var raw = c < cells.length && cells[c] !== undefined ? cells[c] : "";
            if (raw === "") {
                empty++;
                continue;
            }
            nonEmpty++;
            var mapKey = "\u0000" + raw;
            if (!Object.prototype.hasOwnProperty.call(distinct, mapKey)) {
                distinct[mapKey] = true;
                distinctCount++;
            }
            if (Object.prototype.hasOwnProperty.call(freq, mapKey)) freq[mapKey]++;
            else freq[mapKey] = 1;
            if (csvIsNumber(raw)) {
                numericCount++;
                var num = parseFloat(raw.replace(/^\s+|\s+$/g, ""));
                if (min === null || num < min) min = num;
                if (max === null || num > max) max = num;
                sum += num;
            }
        }
        var entry = {
            name: columns[c],
            position: c + 1,
            type: nonEmpty === 0 ? "empty" : (numericCount === nonEmpty ? "number" : "text"),
            nonEmpty: nonEmpty,
            empty: empty,
            distinct: distinctCount
        };
        if (entry.type === "number") {
            entry.min = csvRound(min);
            entry.max = csvRound(max);
            entry.sum = csvRound(sum);
            entry.mean = csvRound(sum / nonEmpty);
            entry.topValues = csvTopValues(freq, top.value);
        } else if (entry.type === "text") {
            entry.numericValues = numericCount;
            entry.topValues = csvTopValues(freq, top.value);
        } else {
            entry.topValues = [];
        }
        stats.push(entry);
    }
    return {
        result: stats,
        rowCount: dataRows.length,
        columnCount: width,
        delimiter: delim.delimiter,
        header: header,
        note: "type Determinación: si el valor no vacío son todos números, es un número, de lo contrario es texto, si está todo vacío, está vacío;" +
            "number Las columnas dan mínimo/máximo/suma/media (con 6 decimales),text Las columnas proporcionan valores distintos y los que ocurren con mayor frecuencia."
    };
}
