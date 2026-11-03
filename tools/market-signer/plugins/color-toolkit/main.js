/**
 * Muse Complemento: Caja de herramientas de color
 *
 * Ejecute el contrato (alojado por WebViewSkillEngine):
 *  - Cada función de utilidad recibe un objeto de parámetro, como function colorConvert(args),args.color;
 *  - El valor de retorno es un objeto normal y el host lo serializará JSON automáticamente y se lo entregará al modelo;
 *  - Devolución en caso de error { error: "Razón" }, el anfitrión marcará dichos resultados como fracasos.
 *
 * Computación local pura: sin acceso a Internet, sin lectura ni escritura de archivos. El análisis de color admite #RGB/#RGBA/#RRGGBB/#RRGGBBAA,
 * rgb()/rgba(),hsl()/hsla() Con nombres de colores CSS comunes; El contraste se calcula como brillo relativo WCAG 2.x.
 */

/** Nombres de colores CSS de uso común (lo suficiente para evitar incluir el conjunto completo de 148 colores). */
var COLOR_NAMES = {
    black: "#000000", white: "#ffffff", red: "#ff0000", green: "#008000", blue: "#0000ff",
    yellow: "#ffff00", cyan: "#00ffff", aqua: "#00ffff", magenta: "#ff00ff", fuchsia: "#ff00ff",
    gray: "#808080", grey: "#808080", silver: "#c0c0c0", maroon: "#800000", olive: "#808000",
    lime: "#00ff00", teal: "#008080", navy: "#000080", purple: "#800080", orange: "#ffa500",
    pink: "#ffc0cb", brown: "#a52a2a", gold: "#ffd700", indigo: "#4b0082", violet: "#ee82ee",
    beige: "#f5f5dc", ivory: "#fffff0", khaki: "#f0e68c", salmon: "#fa8072", tomato: "#ff6347",
    turquoise: "#40e0d0", skyblue: "#87ceeb", steelblue: "#4682b4", darkgray: "#a9a9a9",
    lightgray: "#d3d3d3", transparent: "#00000000"
};

var COLOR_PALETTE_MODES = ["complementary", "analogous", "triadic", "tetradic", "monochromatic"];
var COLOR_ADJUST_MODES = ["lighten", "darken", "saturate", "desaturate", "rotate"];

/* ────────────────────────────── analizar ────────────────────────────── */

function colorIsFiniteNumber(value) {
    return typeof value === "number" && !isNaN(value) && isFinite(value);
}

function colorClamp(value, min, max) {
    if (value < min) return min;
    if (value > max) return max;
    return value;
}

function colorRoundAlpha(alpha) {
    return parseFloat(colorClamp(alpha, 0, 1).toFixed(3));
}

/** demoler "255, 0, 0 / 50%" Este tipo de cadena de parámetro es una matriz de tokens. */
function colorSplitTokens(body) {
    return body.replace(/[\/,]/g, " ").trim().split(/\s+/).filter(function (token) { return token.length > 0; });
}

function colorParseHex(text) {
    var hex = text.trim().toLowerCase();
    if (hex.charAt(0) === "#") hex = hex.slice(1);
    if (hex.length === 0) return { error: "El color hexadecimal está vacío." };
    if (!/^[0-9a-f]+$/.test(hex)) {
        return { error: "El color hexadecimal contiene caracteres ilegales: #" + hex + "(Solo se permiten 0-9 a-f)" };
    }
    var red;
    var green;
    var blue;
    var alpha = 1;
    if (hex.length === 3 || hex.length === 4) {
        red = parseInt(hex.charAt(0) + hex.charAt(0), 16);
        green = parseInt(hex.charAt(1) + hex.charAt(1), 16);
        blue = parseInt(hex.charAt(2) + hex.charAt(2), 16);
        if (hex.length === 4) alpha = parseInt(hex.charAt(3) + hex.charAt(3), 16) / 255;
    } else if (hex.length === 6 || hex.length === 8) {
        red = parseInt(hex.slice(0, 2), 16);
        green = parseInt(hex.slice(2, 4), 16);
        blue = parseInt(hex.slice(4, 6), 16);
        if (hex.length === 8) alpha = parseInt(hex.slice(6, 8), 16) / 255;
    } else {
        return { error: "La longitud del color hexadecimal debe ser de 3/4/6/8 bits (#RGB/#RGBA/#RRGGBB/#RRGGBBAA), actualmente " + hex.length + " poco" };
    }
    return { r: red, g: green, b: blue, a: colorRoundAlpha(alpha) };
}

function colorParseChannel(token, max, label) {
    var text = String(token).trim();
    var value;
    if (text.charAt(text.length - 1) === "%") {
        value = parseFloat(text.slice(0, -1)) * max / 100;
    } else {
        value = parseFloat(text);
    }
    if (isNaN(value)) return { error: label + " No es un número legal: " + token };
    return { value: value };
}

function colorParseAlpha(token) {
    var text = String(token).trim();
    var value;
    if (text.charAt(text.length - 1) === "%") value = parseFloat(text.slice(0, -1)) / 100;
    else value = parseFloat(text);
    if (isNaN(value)) return { error: "alpha No es un número legal: " + token };
    return { value: colorRoundAlpha(value) };
}

function colorParseRgbFunction(text) {
    var matched = /^rgba?\(([^)]*)\)$/.exec(text.trim());
    if (!matched) return { error: "rgb() Escritura incorrecta, ejemplo: rgb(30, 144, 255) o rgba(30, 144, 255, 0.5)" };
    var tokens = colorSplitTokens(matched[1]);
    if (tokens.length < 3 || tokens.length > 4) {
        return { error: "rgb() Requiere 3 componentes de color (1 alfa opcional), actualmente " + tokens.length + " un" };
    }
    var channels = [];
    var labels = ["r", "g", "b"];
    for (var i = 0; i < 3; i++) {
        var parsed = colorParseChannel(tokens[i], 255, labels[i]);
        if (parsed.error) return { error: parsed.error };
        channels.push(Math.round(colorClamp(parsed.value, 0, 255)));
    }
    var alpha = 1;
    if (tokens.length === 4) {
        var parsedAlpha = colorParseAlpha(tokens[3]);
        if (parsedAlpha.error) return { error: parsedAlpha.error };
        alpha = parsedAlpha.value;
    }
    return { r: channels[0], g: channels[1], b: channels[2], a: alpha };
}

function colorParseHslFunction(text) {
    var matched = /^hsla?\(([^)]*)\)$/.exec(text.trim());
    if (!matched) return { error: "hsl() Escritura incorrecta, ejemplo: hsl(210, 100%, 56%) o hsla(210, 100%, 56%, 0.5)" };
    var tokens = colorSplitTokens(matched[1]);
    if (tokens.length < 3 || tokens.length > 4) {
        return { error: "hsl() Tono requerido/saturación/Ligereza 3 componentes (se puede agregar 1 alfa), actualmente " + tokens.length + " un" };
    }
    var hueText = tokens[0].replace(/deg$/, "");
    var hue = parseFloat(hueText);
    if (isNaN(hue)) return { error: "Hue h no es un número legal: " + tokens[0] };
    var parsedS = colorParseChannel(tokens[1], 100, "s");
    if (parsedS.error) return { error: parsedS.error };
    var parsedL = colorParseChannel(tokens[2], 100, "l");
    if (parsedL.error) return { error: parsedL.error };
    var saturation = colorClamp(parsedS.value, 0, 100);
    var lightness = colorClamp(parsedL.value, 0, 100);
    var alpha = 1;
    if (tokens.length === 4) {
        var parsedAlpha = colorParseAlpha(tokens[3]);
        if (parsedAlpha.error) return { error: parsedAlpha.error };
        alpha = parsedAlpha.value;
    }
    var rgb = colorHslToRgb(hue, saturation, lightness);
    return { r: rgb.r, g: rgb.g, b: rgb.b, a: alpha };
}

/** Entrada unificada: cualquier método de escritura en color → { r, g, b, a }. */
function colorParse(raw) {
    if (typeof raw !== "string" || raw.trim().length === 0) {
        return { error: "El color no puede estar vacío, admitido. #RRGGBB / #RGB / #RRGGBBAA / rgb()/rgba() / hsl()/hsla() y nombres de colores de uso común" };
    }
    var text = raw.trim().toLowerCase();
    if (text.charAt(0) === "#") return colorParseHex(text);
    if (/^[0-9a-f]{3,8}$/.test(text)) return colorParseHex(text);
    if (/^rgba?\(/.test(text)) return colorParseRgbFunction(text);
    if (/^hsla?\(/.test(text)) return colorParseHslFunction(text);
    if (Object.prototype.hasOwnProperty.call(COLOR_NAMES, text)) return colorParseHex(COLOR_NAMES[text]);
    return {
        error: "Color no reconocido: " + raw +
            "(Admite #RGB/#RRGGBB/#RRGGBBAA,rgb()/rgba(),hsl()/hsla() y nombres de colores CSS comunes)"
    };
}

/* ────────────────────────────── conversión de espacio de color ────────────────────────────── */

function colorRgbToHsl(r, g, b) {
    var red = r / 255;
    var green = g / 255;
    var blue = b / 255;
    var max = Math.max(red, green, blue);
    var min = Math.min(red, green, blue);
    var lightness = (max + min) / 2;
    var hue = 0;
    var saturation = 0;
    if (max !== min) {
        var delta = max - min;
        saturation = lightness > 0.5 ? delta / (2 - max - min) : delta / (max + min);
        if (max === red) hue = ((green - blue) / delta) % 6;
        else if (max === green) hue = (blue - red) / delta + 2;
        else hue = (red - green) / delta + 4;
        hue = hue * 60;
        if (hue < 0) hue += 360;
    }
    return { h: hue, s: saturation * 100, l: lightness * 100 };
}

function colorHslToRgb(h, s, l) {
    var hue = ((h % 360) + 360) % 360;
    var saturation = colorClamp(s, 0, 100) / 100;
    var lightness = colorClamp(l, 0, 100) / 100;
    var chroma = (1 - Math.abs(2 * lightness - 1)) * saturation;
    var second = chroma * (1 - Math.abs(((hue / 60) % 2) - 1));
    var match = lightness - chroma / 2;
    var red = 0;
    var green = 0;
    var blue = 0;
    if (hue < 60) {
        red = chroma; green = second; blue = 0;
    } else if (hue < 120) {
        red = second; green = chroma; blue = 0;
    } else if (hue < 180) {
        red = 0; green = chroma; blue = second;
    } else if (hue < 240) {
        red = 0; green = second; blue = chroma;
    } else if (hue < 300) {
        red = second; green = 0; blue = chroma;
    } else {
        red = chroma; green = 0; blue = second;
    }
    return {
        r: Math.round((red + match) * 255),
        g: Math.round((green + match) * 255),
        b: Math.round((blue + match) * 255)
    };
}

/** WCAG 2.x brillo relativo. */
function colorLuminance(r, g, b) {
    function channel(value) {
        var scaled = value / 255;
        if (scaled <= 0.03928) return scaled / 12.92;
        return Math.pow((scaled + 0.055) / 1.055, 2.4);
    }
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}

/* ────────────────────────────── Formato de salida ────────────────────────────── */

function colorHexPart(value) {
    var text = Math.round(colorClamp(value, 0, 255)).toString(16);
    return text.length === 1 ? "0" + text : text;
}

function colorToHex(rgb) {
    return "#" + colorHexPart(rgb.r) + colorHexPart(rgb.g) + colorHexPart(rgb.b);
}

function colorToHex8(rgb) {
    return colorToHex(rgb) + colorHexPart(colorClamp(rgb.a, 0, 1) * 255);
}

function colorTrimNumber(value, digits) {
    var text = value.toFixed(digits);
    if (text.indexOf(".") >= 0) text = text.replace(/0+$/, "").replace(/\.$/, "");
    return text;
}

function colorToRgb(rgb) {
    var base = "rgb(" + rgb.r + ", " + rgb.g + ", " + rgb.b + ")";
    if (rgb.a >= 1) return base;
    return "rgba(" + rgb.r + ", " + rgb.g + ", " + rgb.b + ", " + colorTrimNumber(rgb.a, 3) + ")";
}

function colorToHsl(rgb) {
    var hsl = colorRgbToHsl(rgb.r, rgb.g, rgb.b);
    var base = "hsl(" + colorTrimNumber(hsl.h, 1) + ", " + colorTrimNumber(hsl.s, 1) + "%, " + colorTrimNumber(hsl.l, 1) + "%)";
    if (rgb.a >= 1) return base;
    return "hsla(" + colorTrimNumber(hsl.h, 1) + ", " + colorTrimNumber(hsl.s, 1) + "%, " +
        colorTrimNumber(hsl.l, 1) + "%, " + colorTrimNumber(rgb.a, 3) + ")";
}

/** Objeto de resumen de color:hex / rgb / hsl Tres representaciones + componentes. */
function colorDescribe(rgb) {
    var hsl = colorRgbToHsl(rgb.r, rgb.g, rgb.b);
    return {
        hex: colorToHex(rgb),
        hexWithAlpha: colorToHex8(rgb),
        rgb: colorToRgb(rgb),
        hsl: colorToHsl(rgb),
        components: {
            r: rgb.r, g: rgb.g, b: rgb.b, a: rgb.a,
            h: parseFloat(hsl.h.toFixed(1)),
            s: parseFloat(hsl.s.toFixed(1)),
            l: parseFloat(hsl.l.toFixed(1))
        }
    };
}

/* ────────────────────────────── Herramienta 1: conversión de formato ────────────────────────────── */

function colorConvert(args) {
    if (!args || typeof args !== "object") return { error: "El parámetro debe ser un objeto." };
    var parsed = colorParse(args.color);
    if (parsed.error) return { error: parsed.error };
    var described = colorDescribe(parsed);
    return {
        result: {
            hex: described.hex,
            hexWithAlpha: described.hexWithAlpha,
            rgb: described.rgb,
            hsl: described.hsl
        },
        components: described.components,
        note: "hex son 6 dígitos en minúscula;hexWithAlpha Está escrito en 8 bits (termina en ff cuando es opaco);hsl El componente es h(0-360),s%,l%."
    };
}

/* ────────────────────────────── Herramienta 2: Valor/saturación/Ajuste de tono ────────────────────────────── */

function colorAdjust(args) {
    if (!args || typeof args !== "object") return { error: "El parámetro debe ser un objeto." };
    var parsed = colorParse(args.color);
    if (parsed.error) return { error: parsed.error };
    var mode = typeof args.mode === "string" ? args.mode.trim().toLowerCase() : "";
    if (COLOR_ADJUST_MODES.indexOf(mode) < 0) {
        return { error: "mode debe ser " + COLOR_ADJUST_MODES.join("/") + "(actualmente " + (args.mode === undefined ? "vacío" : args.mode) + ")" };
    }
    var amount = args.amount;
    if (amount === undefined || amount === null || amount === "") {
        amount = mode === "rotate" ? 30 : 10;
    } else {
        if (typeof amount === "string" && /^[+-]?(\d+\.?\d*|\.\d+)$/.test(amount.trim())) amount = parseFloat(amount);
        if (!colorIsFiniteNumber(amount)) return { error: "amount debe ser un numero finito" };
    }
    if (Math.abs(amount) > 1000) return { error: "amount demasiado grande (|amount| ≤ 1000)" };

    var hsl = colorRgbToHsl(parsed.r, parsed.g, parsed.b);
    var before = { h: hsl.h, s: hsl.s, l: hsl.l };
    var after = { h: hsl.h, s: hsl.s, l: hsl.l };
    if (mode === "lighten") after.l = colorClamp(hsl.l + amount, 0, 100);
    else if (mode === "darken") after.l = colorClamp(hsl.l - amount, 0, 100);
    else if (mode === "saturate") after.s = colorClamp(hsl.s + amount, 0, 100);
    else if (mode === "desaturate") after.s = colorClamp(hsl.s - amount, 0, 100);
    else after.h = ((hsl.h + amount) % 360 + 360) % 360;

    var converted = colorHslToRgb(after.h, after.s, after.l);
    var output = { r: converted.r, g: converted.g, b: converted.b, a: parsed.a };
    var described = colorDescribe(output);
    var beforeDescribed = colorDescribe(parsed);

    return {
        result: { hex: described.hex, rgb: described.rgb, hsl: described.hsl },
        before: { hex: beforeDescribed.hex, rgb: beforeDescribed.rgb, hsl: beforeDescribed.hsl },
        change: {
            mode: mode,
            amount: amount,
            hue: { from: parseFloat(before.h.toFixed(1)), to: parseFloat(after.h.toFixed(1)) },
            saturation: { from: parseFloat(before.s.toFixed(1)), to: parseFloat(after.s.toFixed(1)) },
            lightness: { from: parseFloat(before.l.toFixed(1)), to: parseFloat(after.l.toFixed(1)) }
        },
        note: "lighten/darken Ajusta el brillo L,saturate/desaturate Ajuste la saturación S (unidad: punto porcentual),rotate Gire el tono H (unidad: grados, puede ser negativo). más allá 0~100 será truncado."
    };
}

/* ────────────────────────────── Herramienta 3:WCAG Contraste ────────────────────────────── */

function colorContrast(args) {
    if (!args || typeof args !== "object") return { error: "El parámetro debe ser un objeto." };
    var firstRaw = args.color1 !== undefined ? args.color1 : args.foreground;
    var secondRaw = args.color2 !== undefined ? args.color2 : args.background;
    if (firstRaw === undefined || secondRaw === undefined) {
        return { error: "Se necesitan dos colores:color1 con color2 (también acepta primer plano/fondo)" };
    }
    var first = colorParse(firstRaw);
    if (first.error) return { error: "color1: " + first.error };
    var second = colorParse(secondRaw);
    if (second.error) return { error: "color2: " + second.error };

    var firstLuminance = colorLuminance(first.r, first.g, first.b);
    var secondLuminance = colorLuminance(second.r, second.g, second.b);
    var lighter = firstLuminance >= secondLuminance ? firstLuminance : secondLuminance;
    var darker = firstLuminance >= secondLuminance ? secondLuminance : firstLuminance;
    var ratio = (lighter + 0.05) / (darker + 0.05);
    var rounded = parseFloat(ratio.toFixed(2));

    var firstIsLighter = firstLuminance >= secondLuminance;
    var firstDescribed = colorDescribe(first);
    var secondDescribed = colorDescribe(second);

    return {
        result: rounded,
        ratioText: rounded.toFixed(2) + ":1",
        aa: ratio >= 4.5,
        aaa: ratio >= 7,
        aaLarge: ratio >= 3,
        aaaLarge: ratio >= 4.5,
        lighter: {
            input: firstIsLighter ? "color1" : "color2",
            hex: firstIsLighter ? firstDescribed.hex : secondDescribed.hex,
            luminance: parseFloat((firstIsLighter ? firstLuminance : secondLuminance).toFixed(4))
        },
        darker: {
            input: firstIsLighter ? "color2" : "color1",
            hex: firstIsLighter ? secondDescribed.hex : firstDescribed.hex,
            luminance: parseFloat((firstIsLighter ? secondLuminance : firstLuminance).toFixed(4))
        },
        colors: {
            color1: { hex: firstDescribed.hex, rgb: firstDescribed.rgb },
            color2: { hex: secondDescribed.hex, rgb: secondDescribed.rgb }
        },
        note: "Calculado según el brillo relativo WCAG 2.x, cuanto mayor es la relación, más claro es:AA Texto ≥ 4.5,AAA Texto ≥ 7,AA Caracteres grandes (≥18.66px Negrita o ≥24px)≥ 3.alpha No participa en la síntesis y se calcula como color opaco."
    };
}

/* ────────────────────────────── Herramienta 4: Esquema de colores ────────────────────────────── */

function colorPalette(args) {
    if (!args || typeof args !== "object") return { error: "El parámetro debe ser un objeto." };
    var parsed = colorParse(args.color);
    if (parsed.error) return { error: parsed.error };
    var mode = typeof args.mode === "string" ? args.mode.trim().toLowerCase() : "";
    if (COLOR_PALETTE_MODES.indexOf(mode) < 0) {
        return { error: "mode debe ser " + COLOR_PALETTE_MODES.join("/") + "(actualmente " + (args.mode === undefined ? "vacío" : args.mode) + ")" };
    }

    var baseHsl = colorRgbToHsl(parsed.r, parsed.g, parsed.b);
    var steps = [];
    var note = "";

    if (mode === "complementary") {
        steps.push({ hue: baseHsl.h, lightness: baseHsl.l, label: "color base" });
        steps.push({ hue: baseHsl.h + 180, lightness: baseHsl.l, label: "colores complementarios (+180°)" });
    } else if (mode === "analogous") {
        var count = colorPaletteCount(args.count, 3, 2, 7);
        if (count.error) return { error: count.error };
        for (var i = 0; i < count.value; i++) {
            var offset = Math.round(30 * (i - (count.value - 1) / 2) * 10) / 10;
            steps.push({ hue: baseHsl.h + offset, lightness: baseHsl.l, label: offset === 0 ? "color base" : "colores adyacentes " + (offset > 0 ? "+" : "") + offset + "°" });
        }
    } else if (mode === "triadic") {
        steps.push({ hue: baseHsl.h, lightness: baseHsl.l, label: "color base" });
        steps.push({ hue: baseHsl.h + 120, lightness: baseHsl.l, label: "Tres roles +120°" });
        steps.push({ hue: baseHsl.h + 240, lightness: baseHsl.l, label: "Tres roles +240°" });
    } else if (mode === "tetradic") {
        steps.push({ hue: baseHsl.h, lightness: baseHsl.l, label: "color base" });
        steps.push({ hue: baseHsl.h + 60, lightness: baseHsl.l, label: "cuatro roles +60°" });
        steps.push({ hue: baseHsl.h + 180, lightness: baseHsl.l, label: "colores complementarios (+180°)" });
        steps.push({ hue: baseHsl.h + 240, lightness: baseHsl.l, label: "cuatro roles +240°" });
        note = "tetradic Adoptando el clásico esquema de color rectangular de cuatro esquinas (+60°/+180°/+240°).";
    } else {
        var monoCount = colorPaletteCount(args.count, 5, 2, 9);
        if (monoCount.error) return { error: monoCount.error };
        for (var j = 0; j < monoCount.value; j++) {
            var ratio = monoCount.value === 1 ? 0.5 : j / (monoCount.value - 1);
            var lightness = colorClamp(baseHsl.l + (ratio - 0.5) * 50, 4, 96);
            var isBase = Math.abs(lightness - baseHsl.l) < 0.75;
            steps.push({
                hue: baseHsl.h,
                lightness: lightness,
                label: isBase ? "color base" : "Serie del mismo color Brillo " + parseFloat(lightness.toFixed(1)) + "%"
            });
        }
        note = "monochromatic Mantenga el tono y la saturación, sólo en ±25 Da pasos equidistantes dentro del rango de brillo del 100%.";
    }

    var colors = [];
    for (var k = 0; k < steps.length; k++) {
        var rgb = colorHslToRgb(steps[k].hue, baseHsl.s, steps[k].lightness);
        var described = colorDescribe({ r: rgb.r, g: rgb.g, b: rgb.b, a: parsed.a });
        colors.push({ name: steps[k].label, hex: described.hex, hsl: described.hsl });
    }

    return {
        result: colors,
        mode: mode,
        count: colors.length,
        base: { hex: colorToHex(parsed), hsl: colorToHsl(parsed) },
        note: note.length > 0 ? note : "Coincidencia de color con cambio de tono, la saturación es consistente con el color base."
    };
}

function colorPaletteCount(raw, defaultValue, min, max) {
    if (raw === undefined || raw === null || raw === "") return { value: defaultValue };
    var value = typeof raw === "string" ? parseInt(raw, 10) : raw;
    if (!colorIsFiniteNumber(value) || value % 1 !== 0) return { error: "count Debe ser un número entero" };
    if (value < min || value > max) return { error: "count debe estar en " + min + ".." + max + " entre" };
    return { value: value };
}
