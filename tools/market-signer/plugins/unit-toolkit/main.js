/**
 * Muse Complemento: Caja de herramientas de conversión de unidades
 *
 * Ejecute el contrato (alojado por WebViewSkillEngine):
 *  - Cada función de utilidad recibe un objeto de parámetro, como function unitConvert(args),args.value / args.from / args.to;
 *  - El valor de retorno es un objeto normal y el host lo serializará JSON automáticamente y se lo entregará al modelo;
 *  - Devolución en caso de error { error: "Razón" }, el anfitrión marcará dichos resultados como fracasos.
 *
 * Computación local pura: sin acceso a Internet, sin lectura ni escritura de archivos. Convertir todo a "unidades" → Unidad base de categoría → unidad objetivo",
 * La temperatura es una transformación afín (°C/°F/K), por lo que se procesa por separado sin factores de escala.
 */

/** El resultado conserva 12 dígitos significativos y elimina el ruido de punto flotante (como la Sección 1 → 1.8519999999999999 km/h). */
var UNIT_SIGNIFICANT_DIGITS = 12;

/** Símbolos de visualización de unidades: las unidades que no figuran en la lista utilizan directamente sus claves de especificación. */
var UNIT_SYMBOLS = {
    m2: "m²", km2: "km²", cm2: "cm²", mm2: "mm²", mi2: "mi²", yd2: "yd²", ft2: "ft²", in2: "in²",
    m3: "m³", cm3: "cm³",
    c: "°C", f: "°F", k: "K",
    l: "L", ml: "mL",
    um: "µm", us: "µs",
    jin: "malicioso", liang: "dos", qian: "dinero", li: "adentro", chi: "gobernante", cun: "pulgada", mu: "acres",
    nmi: "nmi"
};

/**
 * Tabla de categorías.units El factor numérico de es "1 unidad = factor unidad base":
 *  - length punto de referencia m,mass punto de referencia g,area punto de referencia m²,volume punto de referencia L,speed Línea base m/s,
 *    data Punto de referencia B (avanzado en 1024),time puntos de referencia,temperature es una transformación afín (ver unitToBase/unitFromBase).
 */
var UNIT_CATEGORIES = {
    length: {
        label: "longitud",
        base: "m",
        units: {
            m: 1, km: 1000, dm: 0.1, cm: 0.01, mm: 0.001, um: 0.000001, nm: 0.000000001,
            mi: 1609.344, yd: 0.9144, ft: 0.3048, in: 0.0254, nmi: 1852,
            li: 500, chi: 1 / 3, cun: 1 / 30
        },
        aliases: {
            "metros": "m", meter: "m", metre: "m", meters: "m", metres: "m",
            "kilómetros": "km", "kilómetros": "km", kilometer: "km", kilometre: "km", kilometers: "km",
            "decímetro": "dm", decimeter: "dm", decimetre: "dm",
            "centímetros": "cm", "centímetros": "cm", centimeter: "cm", centimetre: "cm", centimeters: "cm",
            "milímetros": "mm", millimeter: "mm", millimetre: "mm", millimeters: "mm",
            "micra": "um", micrometer: "um", micrometre: "um",
            "nano": "nm", nanometer: "nm", nanometre: "nm",
            "millas": "mi", mile: "mi", miles: "mi",
            "código": "yd", yard: "yd", yards: "yd",
            "pies": "ft", foot: "ft", feet: "ft",
            "pulgadas": "in", inch: "in", inches: "in",
            "milla náutica": "nmi", nauticalmile: "nmi", nauticalmiles: "nmi",
            "adentro": "li", "ciudad": "li",
            "gobernante": "chi", "gobernante de la ciudad": "chi",
            "pulgada": "cun", "tamaño del mercado": "cun"
        }
    },
    mass: {
        label: "calidad",
        base: "g",
        units: { g: 1, mg: 0.001, ug: 0.000001, kg: 1000, t: 1000000, lb: 453.59237, oz: 28.349523125, jin: 500, liang: 50, qian: 5 },
        aliases: {
            "gramos": "g", gram: "g", grams: "g",
            "miligramos": "mg", milligram: "mg", milligrams: "mg",
            "microgramo": "ug", microgram: "ug", micrograms: "ug",
            "kilogramo": "kg", "kilogramo": "kg", kilogram: "kg", kilograms: "kg", kilogramme: "kg",
            "toneladas": "t", "toneladas métricas": "t", ton: "t", tons: "t", tonne: "t", tonnes: "t",
            "libras": "lb", pound: "lb", pounds: "lb", lbs: "lb",
            "onzas": "oz", ounce: "oz", ounces: "oz",
            "malicioso": "jin", "Shijin": "jin",
            "dos": "liang", "ciudad dos": "liang",
            "dinero": "qian", "dinero de mercado": "qian"
        }
    },
    temperature: {
        label: "temperatura",
        base: "c",
        units: { c: 1, f: 1, k: 1 },
        aliases: {
            "Celsius": "c", "grados centígrados": "c", celsius: "c", centigrade: "c",
            "Fahrenheit": "f", "Fahrenheit": "f", fahrenheit: "f",
            "abierto": "k", "kélvin": "k", "temperatura absoluta": "k", kelvin: "k"
        }
    },
    area: {
        label: "área",
        base: "m2",
        units: {
            m2: 1, km2: 1000000, cm2: 0.0001, mm2: 0.000001,
            ha: 10000, mu: 2000 / 3, acre: 4046.8564224,
            mi2: 2589988.110336, yd2: 0.83612736, ft2: 0.09290304, in2: 0.00064516
        },
        aliases: {
            "metros cuadrados": "m2", "metros cuadrados": "m2", squaremeter: "m2", sqm: "m2",
            "kilómetros cuadrados": "km2", "kilómetros cuadrados": "km2", squarekilometer: "km2",
            "centímetros cuadrados": "cm2", squarecentimeter: "cm2",
            "Milímetros cuadrados": "mm2", squaremillimeter: "mm2",
            "hectárea": "ha", hectare: "ha", hectares: "ha",
            "acres": "mu", "acres de la ciudad": "mu",
            "acres": "acre", acre: "acre", acres: "acre",
            "millas cuadradas": "mi2", squaremile: "mi2",
            "yardas cuadradas": "yd2", squareyard: "yd2",
            "pies cuadrados": "ft2", squarefoot: "ft2", sqft: "ft2",
            "pulgadas cuadradas": "in2", squareinch: "in2", sqin: "in2"
        }
    },
    volume: {
        label: "Volumen",
        base: "l",
        units: {
            l: 1, ml: 0.001, m3: 1000, cm3: 0.001,
            gal: 3.785411784, ukgal: 4.54609,
            qt: 0.946352946, pt: 0.473176473, cup: 0.2365882365,
            floz: 0.0295735295625, tbsp: 0.01478676478125, tsp: 0.00492892159375
        },
        aliases: {
            "yo": "l", liter: "l", litre: "l", liters: "l", litres: "l",
            "ml": "ml", milliliter: "ml", millilitre: "ml",
            "metros cúbicos": "m3", cubicmeter: "m3", cubicmetre: "m3",
            "centímetros cúbicos": "cm3", cubiccentimeter: "cm3", cc: "cm3",
            "galón": "gal", "galón estadounidense": "gal", gallon: "gal", gallons: "gal", usgallon: "gal",
            "galón imperial": "ukgal", imperialgallon: "ukgal",
            "cuarto": "qt", quart: "qt", quarts: "qt",
            "pinta": "pt", pint: "pt", pints: "pt",
            "taza": "cup", cup: "cup", cups: "cup",
            "onzas líquidas": "floz", fluidounce: "floz", floz: "floz",
            "cuchara": "tbsp", tablespoon: "tbsp",
            "cucharadita": "tsp", teaspoon: "tsp"
        }
    },
    speed: {
        label: "velocidad",
        base: "mps",
        units: { mps: 1, kmh: 1 / 3.6, mph: 0.44704, knot: 1852 / 3600, fps: 0.3048 },
        aliases: {
            "metros por segundo": "mps", "metros/segundos": "mps", "m/s": "mps", meterpersecond: "mps", mps: "mps",
            "kilómetros por hora": "kmh", "kilómetros por hora": "kmh", "kilómetros/tiempo": "kmh", "kilómetros/horas": "kmh", "km/h": "kmh", "kph": "kmh",
            kilometerperhour: "kmh", kmh: "kmh",
            "millas por hora": "mph", "millas/tiempo": "mph", "millas/horas": "mph", "mi/h": "mph", milesperhour: "mph", mph: "mph",
            "fiesta": "knot", "fiesta": "knot", knot: "knot", knots: "knot", kn: "knot",
            "pies por segundo": "fps", "ft/s": "fps", footpersecond: "fps", fps: "fps"
        }
    },
    data: {
        label: "Volumen de datos",
        base: "b",
        units: {
            b: 1, kb: 1024, mb: 1048576, gb: 1073741824, tb: 1099511627776, pb: 1125899906842624,
            bit: 0.125, kbit: 128, mbit: 131072, gbit: 134217728
        },
        aliases: {
            "bytes": "b", byte: "b", bytes: "b",
            "Kilobytes": "kb", kilobyte: "kb", kilobytes: "kb",
            "megabytes": "mb", megabyte: "mb", megabytes: "mb",
            "gigabytes": "gb", "gigabytes": "gb", gigabyte: "gb", gigabytes: "gb",
            "terabytes": "tb", terabyte: "tb", terabytes: "tb",
            "Petabytes": "pb", petabyte: "pb", petabytes: "pb",
            kib: "kb", kibibyte: "kb", mib: "mb", mebibyte: "mb",
            gib: "gb", gibibyte: "gb", tib: "tb", tebibyte: "tb", pib: "pb", pebibyte: "pb",
            "bits": "bit", "poco": "bit", bit: "bit", bits: "bit",
            "Kilobits": "kbit", kbit: "kbit", "megabits": "mbit", mbit: "mbit",
            "gigabits": "gbit", gbit: "gbit"
        }
    },
    time: {
        label: "tiempo",
        base: "s",
        units: { s: 1, ms: 0.001, us: 0.000001, ns: 0.000000001, min: 60, h: 3600, day: 86400, week: 604800 },
        aliases: {
            "segundos": "s", second: "s", seconds: "s", sec: "s",
            "milisegundos": "ms", millisecond: "ms", milliseconds: "ms",
            "microsegundos": "us", microsecond: "us", microseconds: "us",
            "nanosegundo": "ns", nanosecond: "ns", nanoseconds: "ns",
            "minutos": "min", minute: "min", minutes: "min",
            "horas": "h", "tiempo": "h", hour: "h", hours: "h", hr: "h",
            "dia": "day", "dia": "day", day: "day", days: "day",
            "semana": "week", "semana": "week", week: "week", weeks: "week", wk: "week"
        }
    }
};

/** Los alias de categoría (incluido el chino) y las claves también se escriben en forma normalizada. */
var UNIT_CATEGORY_ALIASES = {
    length: "length", "longitud": "length", "distancia": "length", distance: "length",
    mass: "mass", "calidad": "mass", "peso": "mass", weight: "mass",
    temperature: "temperature", "temperatura": "temperature", temp: "temperature",
    area: "area", "área": "area",
    volume: "volume", "Volumen": "volume", "Volumen": "volume", "Capacidad": "volume", capacity: "volume",
    speed: "speed", "velocidad": "speed", velocity: "speed",
    data: "data", "datos": "data", "Volumen de datos": "data", "almacenamiento": "data", datasize: "data", storage: "data",
    time: "time", "tiempo": "time", duration: "time"
};

/* ────────────────────────────── herramientas generales ────────────────────────────── */

/** unidad/Unifique los nombres de las categorías: minúsculas, elimine espacios y subrayados, convierta el ancho completo a medio ancho, superíndice y㎡/㎥ Expandir. */
function unitNormalizeKey(raw) {
    var text = String(raw).trim().toLowerCase();
    var out = "";
    for (var i = 0; i < text.length; i++) {
        var ch = text.charAt(i);
        if (ch === " " || ch === "_" || ch === "\t") continue;
        if (ch >= "0" && ch <= "9") out += String.fromCharCode(ch.charCodeAt(0) - 0xfee0);
        else if (ch === "²") out += "2";
        else if (ch === "³") out += "3";
        else if (ch === "㎡") out += "m2";
        else if (ch === "㎥") out += "m3";
        else if (ch === "µ" || ch === "μ") out += "u";
        else if (ch === "°" || ch === "º") continue;
        else out += ch;
    }
    return out;
}

function unitHas(object, key) {
    return Object.prototype.hasOwnProperty.call(object, key);
}

function unitSymbol(categoryKey, unit) {
    if (unitHas(UNIT_SYMBOLS, unit)) return UNIT_SYMBOLS[unit];
    return unit;
}

/** El nombre chino de la unidad (tome el primer alias chino en la tabla de alias), utilizado para avisos de error y visualización amigable. */
function unitChineseName(categoryKey, unit) {
    var aliases = UNIT_CATEGORIES[categoryKey].aliases;
    for (var alias in aliases) {
        if (!unitHas(aliases, alias)) continue;
        if (aliases[alias] === unit && /[\u4e00-\u9fff]/.test(alias)) return alias;
    }
    return "";
}

/** \"unidad\" legible por humanos(nombre chino)”, devuelta como mensaje cuando se desconoce la unidad. */
function unitListOf(categoryKey) {
    var category = UNIT_CATEGORIES[categoryKey];
    var items = [];
    for (var unit in category.units) {
        if (!unitHas(category.units, unit)) continue;
        var chinese = unitChineseName(categoryKey, unit);
        items.push(unitSymbol(categoryKey, unit) + (chinese ? "(" + chinese + ")" : ""));
    }
    return items;
}

/** Encuentra unidades en todas las categorías y devuelve todos los resultados { category, unit }. */
function unitFindMatches(rawUnit) {
    var key = unitNormalizeKey(rawUnit);
    var matches = [];
    for (var categoryKey in UNIT_CATEGORIES) {
        if (!unitHas(UNIT_CATEGORIES, categoryKey)) continue;
        var category = UNIT_CATEGORIES[categoryKey];
        if (unitHas(category.aliases, key)) matches.push({ category: categoryKey, unit: category.aliases[key] });
        else if (unitHas(category.units, key)) matches.push({ category: categoryKey, unit: key });
    }
    return matches;
}

function unitResolveCategoryKey(rawCategory) {
    if (rawCategory === undefined || rawCategory === null) return null;
    var key = unitNormalizeKey(rawCategory);
    if (key.length === 0) return null;
    if (unitHas(UNIT_CATEGORY_ALIASES, key)) return UNIT_CATEGORY_ALIASES[key];
    if (unitHas(UNIT_CATEGORIES, key)) return key;
    return undefined;
}

function unitAllCategoryNames() {
    var names = [];
    for (var categoryKey in UNIT_CATEGORIES) {
        if (!unitHas(UNIT_CATEGORIES, categoryKey)) continue;
        names.push(categoryKey + "(" + UNIT_CATEGORIES[categoryKey].label + ")");
    }
    return names;
}

function unitParseNumber(raw, label) {
    if (typeof raw === "number") {
        if (isNaN(raw) || !isFinite(raw)) return { error: label + " debe ser un numero finito" };
        return { value: raw };
    }
    if (typeof raw === "string") {
        var text = raw.trim();
        if (text.length === 0) return { error: label + " no puede estar vacío" };
        if (!/^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$/.test(text)) {
            return { error: label + " No es un número legal: " + raw };
        }
        var parsed = parseFloat(text);
        if (isNaN(parsed) || !isFinite(parsed)) return { error: label + " Más allá del rango analizable" };
        return { value: parsed };
    }
    return { error: label + " debe ser un numero" };
}

/** Ruido suave de punto flotante: conserva 12 dígitos significativos. */
function unitRound(value) {
    if (value === 0) return 0;
    return parseFloat(value.toPrecision(UNIT_SIGNIFICANT_DIGITS));
}

/** Cadena numérica compacta (para campos formateados). */
function unitFormatNumber(value) {
    if (value === 0) return "0";
    var abs = Math.abs(value);
    if (abs >= 1e15 || abs < 1e-6) return parseFloat(value.toPrecision(6)).toExponential(6).replace(/\.?0+e/, "e");
    var text = value.toPrecision(UNIT_SIGNIFICANT_DIGITS);
    return String(parseFloat(text));
}

/* ────────────────────────────── Temperatura (afín) ────────────────────────────── */

/** Cualquier unidad de temperatura → grados centígrados. */
function unitTemperatureToCelsius(unit, value) {
    if (unit === "c") return value;
    if (unit === "f") return (value - 32) * 5 / 9;
    return value - 273.15;
}

/** grados centígrados → Cualquier unidad de temperatura. */
function unitTemperatureFromCelsius(unit, celsius) {
    if (unit === "c") return celsius;
    if (unit === "f") return celsius * 9 / 5 + 32;
    return celsius + 273.15;
}

/* ────────────────────────────── Conversión ────────────────────────────── */

function unitToBase(categoryKey, unit, value) {
    if (categoryKey === "temperature") return unitTemperatureToCelsius(unit, value);
    return value * UNIT_CATEGORIES[categoryKey].units[unit];
}

function unitFromBase(categoryKey, unit, value) {
    if (categoryKey === "temperature") return unitTemperatureFromCelsius(unit, value);
    return value / UNIT_CATEGORIES[categoryKey].units[unit];
}

function unitConvert(args) {
    if (!args || typeof args !== "object") return { error: "El parámetro debe ser un objeto." };
    var parsedValue = unitParseNumber(args.value, "value");
    if (parsedValue.error) return { error: parsedValue.error };
    if (typeof args.from !== "string" || args.from.trim().length === 0) return { error: "from Debe ser una cadena unitaria, como por ejemplo“m”o“kilómetros”" };
    if (typeof args.to !== "string" || args.to.trim().length === 0) return { error: "to Debe ser una cadena unitaria, como por ejemplo“km”" };

    var explicitCategory = unitResolveCategoryKey(args.category);
    if (explicitCategory === undefined) {
        return {
            error: "Categoría desconocida: " + args.category + ". Categorías disponibles: " + unitAllCategoryNames().join(","),
            availableCategories: unitAllCategoryNames()
        };
    }

    var fromMatch;
    var toMatch;
    if (explicitCategory) {
        var fromMatches = unitFindMatches(args.from).filter(function (item) { return item.category === explicitCategory; });
        if (fromMatches.length === 0) {
            return {
                error: "categoría " + explicitCategory + " No hay unidades en '" + args.from + "'. Unidades disponibles: " + unitListOf(explicitCategory).join(","),
                availableUnits: unitListOf(explicitCategory)
            };
        }
        fromMatch = fromMatches[0];
        var toMatches = unitFindMatches(args.to).filter(function (item) { return item.category === explicitCategory; });
        if (toMatches.length === 0) {
            return {
                error: "categoría " + explicitCategory + " No hay unidades en '" + args.to + "'. Unidades disponibles: " + unitListOf(explicitCategory).join(","),
                availableUnits: unitListOf(explicitCategory)
            };
        }
        toMatch = toMatches[0];
    } else {
        var fromAll = unitFindMatches(args.from);
        if (fromAll.length === 0) {
            return {
                error: "unidad desconocida: " + args.from + ". Ejemplos de unidades conocidas: " + unitListOf("length").join(",") + " etc. También puede utilizar categoría para especificar la categoría.",
                availableUnits: unitListOf("length"),
                availableCategories: unitAllCategoryNames()
            };
        }
        if (fromAll.length > 1) {
            return { error: "unidad '" + args.from + "' Aparece en varias categorías; utilice categoría para especificar la categoría (" + unitAllCategoryNames().join(",") + ")" };
        }
        fromMatch = fromAll[0];
        var toAll = unitFindMatches(args.to);
        if (toAll.length === 0) {
            return {
                error: "unidad desconocida: " + args.to + "." + fromMatch.category + " Unidades disponibles: " + unitListOf(fromMatch.category).join(","),
                availableUnits: unitListOf(fromMatch.category)
            };
        }
        if (toAll.length > 1) {
            return { error: "unidad '" + args.to + "' Aparece en varias categorías; utilice categoría para especificar la categoría (" + unitAllCategoryNames().join(",") + ")" };
        }
        toMatch = toAll[0];
        if (toMatch.category !== fromMatch.category) {
            return {
                error: "from No en la misma categoría que: " + args.from + " pertenecer a " + fromMatch.category +
                    "(" + UNIT_CATEGORIES[fromMatch.category].label + ")," + args.to + " pertenecer a " + toMatch.category +
                    "(" + UNIT_CATEGORIES[toMatch.category].label + ")",
                availableUnits: unitListOf(fromMatch.category)
            };
        }
    }

    var categoryKey = fromMatch.category;
    var baseValue = unitToBase(categoryKey, fromMatch.unit, parsedValue.value);
    if (categoryKey === "temperature" && baseValue < -273.15) {
        return { error: "Temperatura bajo el cero absoluto (-273.15 °C), por favor verifique la entrada" };
    }
    var resultValue = unitRound(unitFromBase(categoryKey, toMatch.unit, baseValue));

    var note = categoryKey === "temperature"
        ? "La temperatura es una transformación afín (°C/°F/K), calibrado al cero absoluto."
        : "";
    if (categoryKey === "data") note = "La cantidad de datos se ingresa en 1024 (1 KB = 1024 B), consistente con la herramienta data_size.";
    if (categoryKey === "length" && (fromMatch.unit === "in" || toMatch.unit === "in")) note = "in en pulgadas (inch).";
    if (categoryKey === "volume" && (fromMatch.unit === "gal" || toMatch.unit === "gal")) note = "gal a galones estadounidenses (3.785411784 L), utilice ukgal para el sistema imperial.";
    if (categoryKey === "area" && (fromMatch.unit === "mu" || toMatch.unit === "mu")) note = "Mu es la ciudad mu (1 acres = 666.6667 m²).";

    return {
        result: resultValue,
        formatted: unitFormatNumber(resultValue) + " " + unitSymbol(categoryKey, toMatch.unit),
        category: categoryKey,
        categoryLabel: UNIT_CATEGORIES[categoryKey].label,
        from: { unit: fromMatch.unit, symbol: unitSymbol(categoryKey, fromMatch.unit), value: parsedValue.value },
        to: { unit: toMatch.unit, symbol: unitSymbol(categoryKey, toMatch.unit), value: resultValue },
        note: note
    };
}

/* ────────────────────────────── Formato de volumen de datos ────────────────────────────── */

var DATA_UNITS = ["B", "KB", "MB", "GB", "TB", "PB"];
var DATA_BASE = 1024;

function dataFormat(bytes, decimals) {
    var index = 0;
    var value = bytes;
    while (value >= DATA_BASE && index < DATA_UNITS.length - 1) {
        value = value / DATA_BASE;
        index++;
    }
    var text = value.toFixed(decimals);
    if (text.indexOf(".") >= 0) text = text.replace(/0+$/, "").replace(/\.$/, "");
    return { value: value, unit: DATA_UNITS[index], text: text + " " + DATA_UNITS[index] };
}

function dataSize(args) {
    if (!args || typeof args !== "object") return { error: "El parámetro debe ser un objeto." };
    var mode = typeof args.mode === "string" ? args.mode.trim().toLowerCase() : "";
    if (mode !== "format" && mode !== "parse") return { error: "mode Debe tener formato (número de bytes→texto legible) o parse (texto legible→número de bytes)" };

    var decimals = 2;
    if (args.decimals !== undefined && args.decimals !== null) {
        var parsedDecimals = typeof args.decimals === "string" ? parseInt(args.decimals, 10) : args.decimals;
        if (typeof parsedDecimals !== "number" || isNaN(parsedDecimals) || parsedDecimals % 1 !== 0 || parsedDecimals < 0 || parsedDecimals > 6) {
            return { error: "decimals Debe ser un número entero de 0..6" };
        }
        decimals = parsedDecimals;
    }

    if (mode === "format") {
        var parsedBytes = unitParseNumber(args.bytes, "bytes");
        if (parsedBytes.error) return { error: parsedBytes.error };
        if (parsedBytes.value < 0) return { error: "bytes no puede ser negativo" };
        if (parsedBytes.value > 9007199254740991) return { error: "bytes Más allá del rango de enteros seguro (límite superior 2^53-1)" };
        var formatted = dataFormat(parsedBytes.value, decimals);
        return {
            result: formatted.text,
            bytes: parsedBytes.value,
            unit: formatted.unit,
            value: parseFloat(formatted.value.toFixed(decimals)),
            note: "Llevar por 1024 (1 KB = 1024 B)."
        };
    }

    if (typeof args.text !== "string" || args.text.trim().length === 0) return { error: "parse El patrón requiere texto, como“1.5GB”" };
    var text = args.text.trim();
    var matched = /^([+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?)\s*([A-Za-z\u4e00-\u9fff]*)$/.exec(text);
    if (!matched) return { error: "No se puede analizar el texto del volumen de datos: " + args.text + "(Ejemplo: 1.5GB / 1024 / 512 MB)" };
    var amount = parseFloat(matched[1]);
    if (isNaN(amount) || !isFinite(amount)) return { error: "El valor del volumen de datos no es válido: " + matched[1] };
    if (amount < 0) return { error: "La cantidad de datos no puede ser negativa." };

    var unitKey = matched[2].length === 0 ? "b" : unitNormalizeKey(matched[2]);
    var factors = {
        b: 1, "bytes": 1, byte: 1, bytes: 1,
        kb: DATA_BASE, "Kilobytes": DATA_BASE, kilobyte: DATA_BASE, kib: DATA_BASE,
        mb: DATA_BASE * DATA_BASE, "megabytes": DATA_BASE * DATA_BASE, megabyte: DATA_BASE * DATA_BASE, mib: DATA_BASE * DATA_BASE,
        gb: Math.pow(DATA_BASE, 3), "gigabytes": Math.pow(DATA_BASE, 3), gigabyte: Math.pow(DATA_BASE, 3), gib: Math.pow(DATA_BASE, 3),
        tb: Math.pow(DATA_BASE, 4), "terabytes": Math.pow(DATA_BASE, 4), terabyte: Math.pow(DATA_BASE, 4), tib: Math.pow(DATA_BASE, 4),
        pb: Math.pow(DATA_BASE, 5), "Petabytes": Math.pow(DATA_BASE, 5), petabyte: Math.pow(DATA_BASE, 5), pib: Math.pow(DATA_BASE, 5),
        bit: 0.125, "bits": 0.125, bits: 0.125,
        kbit: 128, mbit: 131072, gbit: 134217728
    };
    if (!unitHas(factors, unitKey)) {
        return {
            error: "Unidad de volumen de datos desconocida: " + matched[2] + ". Unidades disponibles: " + DATA_UNITS.join(",") + ",bit(trozos,1 bit = 1/8 B)",
            availableUnits: DATA_UNITS.concat(["bit"])
        };
    }
    var bytes = amount * factors[unitKey];
    if (!isFinite(bytes) || bytes > 9007199254740991) return { error: "El resultado de la conversión excede el rango de números enteros seguros" };
    var rounded = unitRound(bytes);
    return {
        result: rounded,
        bytes: rounded,
        text: args.text,
        unit: unitKey,
        human: dataFormat(rounded, 2).text,
        note: "Llevar a 1024;b/B Todo en bytes,bit Significa poco."
    };
}

/* ────────────────────────────── Legibilidad digital ────────────────────────────── */

var RMB_DIGITS = ["cero", "uno", "dos", "tres", "cuatro", "Wu", "tierra", "siete", "ocho", "jiu"];
var RMB_UNITS = ["", "recoger", "cien", "mil"];
var RMB_GROUPS = ["", "millones", "mil millones", "billones"];
var RMB_MAX_AMOUNT = 999999999999.99;

/** Convierta un número entero de cuatro dígitos a mayúsculas chinas (el 0 en el grupo se suma a \"cero\"). */
function numberRmbGroup(group) {
    var text = "";
    var pendingZero = false;
    for (var i = 0; i < group.length; i++) {
        var digit = group.charCodeAt(i) - 48;
        var unit = RMB_UNITS[group.length - 1 - i];
        if (digit === 0) {
            if (text.length > 0) pendingZero = true;
            continue;
        }
        if (pendingZero) {
            text += "cero";
            pendingZero = false;
        }
        text += RMB_DIGITS[digit] + unit;
    }
    return text;
}

/** numeros → Cantidad en letras mayúsculas chinas (como 1234.56 → Mil doscientos treinta y cuatro Yuan Wujiao Lufen). */
function numberRmbUpper(value) {
    var negative = value < 0;
    var cents = Math.round(Math.abs(value) * 100);
    var integerPart = Math.floor(cents / 100);
    var fraction = cents - integerPart * 100;
    var jiao = Math.floor(fraction / 10);
    var fen = fraction % 10;

    var rest = String(integerPart);
    var groups = [];
    while (rest.length > 0) {
        var cut = rest.length - 4;
        if (cut < 0) cut = 0;
        groups.unshift(rest.substring(cut));
        rest = rest.substring(0, cut);
    }
    var yuanText = "";
    var pendingZero = false;
    for (var i = 0; i < groups.length; i++) {
        var groupText = numberRmbGroup(groups[i]);
        var groupUnit = RMB_GROUPS[groups.length - 1 - i];
        if (groupText.length === 0) {
            if (yuanText.length > 0) pendingZero = true;
            continue;
        }
        if (pendingZero) {
            yuanText += "cero";
            pendingZero = false;
        }
        yuanText += groupText + groupUnit;
    }
    if (yuanText.length === 0) yuanText = "cero";

    var text = yuanText + "yuanes";
    if (jiao === 0 && fen === 0) text += "entero";
    else if (jiao === 0) text += "cero" + RMB_DIGITS[fen] + "puntos";
    else if (fen === 0) text += RMB_DIGITS[jiao] + "ángulo";
    else text += RMB_DIGITS[jiao] + "ángulo" + RMB_DIGITS[fen] + "puntos";
    return (negative ? "Negativo" : "") + text;
}

/** Miles: inserte comas por lugar decimal original o lugar decimal especificado. */
function numberThousands(value, decimals, hasDecimals) {
    var text = hasDecimals ? value.toFixed(decimals) : String(value);
    if (text.indexOf("e") >= 0 || text.indexOf("E") >= 0) return text;
    var negative = text.charAt(0) === "-";
    if (negative) text = text.slice(1);
    var parts = text.split(".");
    var integer = parts[0];
    var grouped = "";
    for (var i = 0; i < integer.length; i++) {
        if (i > 0 && (integer.length - i) % 3 === 0) grouped += ",";
        grouped += integer.charAt(i);
    }
    var out = grouped + (parts.length > 1 ? "." + parts[1] : "");
    return (negative ? "-" : "") + out;
}

function numberFormat(args) {
    if (!args || typeof args !== "object") return { error: "El parámetro debe ser un objeto." };
    var mode = typeof args.mode === "string" ? args.mode.trim().toLowerCase() : "";
    if (mode.length === 0) return { error: "mode No puede estar vacío, debe ser uno de miles/fijo/científico/porcentaje/rmb" };
    var parsedValue = unitParseNumber(args.value, "value");
    if (parsedValue.error) return { error: parsedValue.error };
    var value = parsedValue.value;

    var decimals = null;
    if (args.decimals !== undefined && args.decimals !== null) {
        var parsedDecimals = typeof args.decimals === "string" ? parseInt(args.decimals, 10) : args.decimals;
        if (typeof parsedDecimals !== "number" || isNaN(parsedDecimals) || parsedDecimals % 1 !== 0 || parsedDecimals < 0 || parsedDecimals > 20) {
            return { error: "decimals Debe ser un número entero de 0 a 20." };
        }
        decimals = parsedDecimals;
    }

    if (mode === "thousands" || mode === "thousand" || mode === "group") {
        if (Math.abs(value) >= 1e21) return { error: "No se admiten milésimas ≥ 1e21 para números, use el modo científico en su lugar" };
        var hasDecimals = decimals !== null;
        var text = numberThousands(value, hasDecimals ? decimals : 0, hasDecimals);
        return {
            result: text,
            mode: "thousands",
            decimals: hasDecimals ? decimals : null,
            note: "decimals Cuando se omite, se conservan los decimales del propio número."
        };
    }

    if (mode === "fixed" || mode === "decimal") {
        var fixedDecimals = decimals === null ? 2 : decimals;
        return { result: value.toFixed(fixedDecimals), mode: "fixed", decimals: fixedDecimals, number: parseFloat(value.toFixed(fixedDecimals)) };
    }

    if (mode === "scientific" || mode === "sci") {
        var digits = decimals === null ? 5 : decimals;
        var exponential = value.toExponential(digits);
        var matched = /^(-?)(\d)(?:\.(\d+))?e([+-]\d+)$/.exec(exponential);
        return {
            result: exponential,
            mode: "scientific",
            mantissa: matched ? parseFloat(matched[1] + matched[2] + (matched[3] ? "." + matched[3] : "")) : value,
            exponent: matched ? parseInt(matched[4], 10) : 0,
            note: "decimals Controla el número de decimales en la mantisa (predeterminado 5)."
        };
    }

    if (mode === "percent" || mode === "percentage") {
        var alreadyPercent = args.alreadyPercent === true;
        var percentValue = alreadyPercent ? value : value * 100;
        var percentDecimals = decimals === null ? 2 : decimals;
        var percentText = percentValue.toFixed(percentDecimals);
        if (percentDecimals > 0) percentText = percentText.replace(/0+$/, "").replace(/\.$/, "");
        return {
            result: percentText + "%",
            mode: "percent",
            number: parseFloat(percentValue.toFixed(percentDecimals)),
            ratio: alreadyPercent ? percentValue / 100 : value,
            note: alreadyPercent ? "presionado alreadyPercent=true Formatee directamente." : "El valor predeterminado es tratar el valor como una escala y multiplicarlo por 100 (0.256 → 25.6%)."
        };
    }

    if (mode === "rmb" || mode === "chinese" || mode === "capital") {
        if (Math.abs(value) > RMB_MAX_AMOUNT) {
            return { error: "La cantidad excede el rango admitido (el límite superior " + RMB_MAX_AMOUNT + ")" };
        }
        var roundedCents = Math.round(Math.abs(value) * 100) / 100;
        var signedCents = value < 0 ? -roundedCents : roundedCents;
        return {
            result: numberRmbUpper(value),
            mode: "rmb",
            amount: signedCents,
            note: "Redondeado a centavos; La producción es una cantidad financiera capitalizada."
        };
    }

    return { error: "No compatible mode: " + args.mode + "(disponible en miles/fijo/científico/porcentaje/rmb)" };
}
