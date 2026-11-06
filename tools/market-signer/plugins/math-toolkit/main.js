/**
 * Muse Complemento: Caja de herramientas matemáticas
 *
 * Ejecute el contrato (alojado por WebViewSkillEngine):
 *  - Cada función de utilidad recibe un objeto de parámetro, como function mathEval(args),args.expr;
 *  - El valor de retorno es un objeto normal y el host lo serializará JSON automáticamente y se lo entregará al modelo;
 *  - Devolución en caso de error { error: "Razón" }, el anfitrión marcará dichos resultados como fracasos.
 *
 * Este complemento es puramente informático local: sin conexión a Internet, sin lectura y escritura de archivos, y sin evaluación dinámica de código.
 * La evaluación de expresiones se completa con el análisis léxico incorporado + analizador de descenso recursivo, que solo permite operadores, constantes y funciones en la lista blanca.
 * La conversión de base de enteros grandes se implementa mediante operaciones de cadena bit a bit (no depende de BigInt, es compatible con el antiguo WebView).
 */

/** Límite superior de entrada: evite que una entrada anormal destruya WebView dentro del tiempo de espera de 10 segundos del entorno limitado de JS. */
var MATH_MAX_EXPRESSION_LENGTH = 2000;
var MATH_MAX_LIST_LENGTH = 1000;
var MATH_MAX_BASE_INPUT_LENGTH = 2048;
var MATH_MAX_FACTORIZE = 1000000000000; /* 1e12, el método de división de prueba es controlable dentro de esta escala */
var MATH_SAFE_INTEGER = 9007199254740991;

var MATH_BASE_DIGITS = "0123456789abcdefghijklmnopqrstuvwxyz";
var MATH_PRECEDENCE = { "+": 1, "-": 1, "*": 2, "/": 2, "%": 2, "^": 4 };
var MATH_NUMERIC_PATTERN = /^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$/;

/** Constantes incorporadas. */
var MATH_CONSTANTS = {
    pi: Math.PI,
    e: Math.E,
    tau: Math.PI * 2,
    phi: (1 + Math.sqrt(5)) / 2
};

/** Funciones integradas (todas las funciones puras; NaN causado por parámetros ilegales serán interceptados durante la fase de evaluación). */
var MATH_FUNCTIONS = {
    abs: Math.abs,
    sqrt: Math.sqrt,
    cbrt: Math.cbrt,
    floor: Math.floor,
    ceil: Math.ceil,
    round: Math.round,
    trunc: Math.trunc,
    sign: Math.sign,
    ln: Math.log,
    log: Math.log10,
    log10: Math.log10,
    log2: Math.log2,
    exp: Math.exp,
    sin: Math.sin,
    cos: Math.cos,
    tan: Math.tan,
    pow: Math.pow,
    min: Math.min,
    max: Math.max,
    hypot: Math.hypot
};

/** Lista de nombres (la lista de modelos disponibles en el mensaje de error). */
function mathNamesOf(table) {
    var names = [];
    for (var key in table) {
        if (Object.prototype.hasOwnProperty.call(table, key)) names.push(key);
    }
    return names.sort();
}

/* ────────────────────────────── Evaluación de expresiones ────────────────────────────── */

/** poner chino/La entrada de ancho completo está normalizada en escritura matemática ASCII, lo que facilita ** Tratado como un operador de energía. */
function mathNormalizeText(text) {
    var out = "";
    for (var i = 0; i < text.length; i++) {
        var ch = text.charAt(i);
        if (ch >= "0" && ch <= "9") out += String.fromCharCode(ch.charCodeAt(0) - 0xfee0);
        else if (ch === "(") out += "(";
        else if (ch === ")") out += ")";
        else if (ch === "×" || ch === "·" || ch === "＊") out += "*";
        else if (ch === "÷" || ch === "/") out += "/";
        else if (ch === "-" || ch === "−" || ch === "—") out += "-";
        else if (ch === "+") out += "+";
        else if (ch === "%") out += "%";
        else if (ch === "＾") out += "^";
        else if (ch === ",") out += ",";
        else if (ch === "．") out += ".";
        else if (ch === "\u00a0" || ch === "\u3000" || ch === "\u200b") out += " ";
        else out += ch;
    }
    return out;
}

/** Análisis léxico: números (incluidos decimales, notación científica), nombres, operadores, paréntesis, comas. */
function mathTokenize(text) {
    var tokens = [];
    var index = 0;
    while (index < text.length) {
        var ch = text.charAt(index);
        if (/\s/.test(ch)) {
            index++;
            continue;
        }
        if (ch === "(" || ch === ")" || ch === "+" || ch === "-" || ch === "/" ||
            ch === "%" || ch === "^" || ch === ",") {
            tokens.push({ type: "op", value: ch, pos: index });
            index++;
            continue;
        }
        if (ch === "*") {
            if (text.charAt(index + 1) === "*") {
                tokens.push({ type: "op", value: "^", pos: index });
                index += 2;
            } else {
                tokens.push({ type: "op", value: "*", pos: index });
                index++;
            }
            continue;
        }
        if (ch >= "0" && ch <= "9" || ch === ".") {
            var start = index;
            var dots = 0;
            while (index < text.length) {
                var digitCh = text.charAt(index);
                if (digitCh >= "0" && digitCh <= "9") {
                    index++;
                    continue;
                }
                if (digitCh === ".") {
                    if (dots > 0) return { error: "Aparecen varios puntos decimales en el número (posición " + (index + 1) + ")" };
                    dots++;
                    index++;
                    continue;
                }
                break;
            }
            var literal = text.slice(start, index);
            if (literal === ".") return { error: "Un solo punto decimal no es un número legal (posición " + (start + 1) + ")" };
            var expCh = text.charAt(index);
            if (expCh === "e" || expCh === "E") {
                var probe = index + 1;
                var expSign = "";
                if (text.charAt(probe) === "+" || text.charAt(probe) === "-") {
                    expSign = text.charAt(probe);
                    probe++;
                }
                var expDigits = 0;
                while (probe < text.length && text.charAt(probe) >= "0" && text.charAt(probe) <= "9") {
                    probe++;
                    expDigits++;
                }
                if (expDigits > 0) {
                    literal = literal + "e" + expSign + text.slice(index + 1 + expSign.length, probe);
                    index = probe;
                }
            }
            var value = parseFloat(literal);
            if (isNaN(value)) return { error: "No se puede analizar el número: " + literal };
            tokens.push({ type: "number", value: value, text: literal, pos: start });
            continue;
        }
        if (/[A-Za-z_]/.test(ch)) {
            var nameStart = index;
            while (index < text.length && /[A-Za-z0-9_]/.test(text.charAt(index))) index++;
            tokens.push({ type: "name", value: text.slice(nameStart, index), pos: nameStart });
            continue;
        }
        return { error: "personajes ilegales '" + ch + "'(ubicación " + (index + 1) + ")" };
    }
    return { tokens: tokens };
}

/**
 * Análisis de descenso recursivo (devolviendo AST):
 *   expression := term (('+' | '-') term)*
 *   term       := unary (('*' | '/' | '%') unary)*
 *   unary      := ('+' | '-') unary | power
 *   power      := primary ('^' unary)?         // asociativo derecho, y -2^2 = -(2^2)
 *   primary    := number | constant | name '(' args ')' | '(' expression ')'
 */
function mathParse(tokens) {
    var pos = 0;
    var openParens = 0;

    function peek() {
        return pos < tokens.length ? tokens[pos] : null;
    }

    function parseExpression() {
        var left = parseTerm();
        if (left.error) return left;
        var node = left.node;
        while (true) {
            var token = peek();
            if (!token || token.type !== "op" || (token.value !== "+" && token.value !== "-")) break;
            pos++;
            var right = parseTerm();
            if (right.error) return right;
            node = { kind: "bin", op: token.value, left: node, right: right.node };
        }
        return { node: node };
    }

    function parseTerm() {
        var left = parseUnary();
        if (left.error) return left;
        var node = left.node;
        while (true) {
            var token = peek();
            if (!token || token.type !== "op" || (token.value !== "*" && token.value !== "/" && token.value !== "%")) break;
            pos++;
            var right = parseUnary();
            if (right.error) return right;
            node = { kind: "bin", op: token.value, left: node, right: right.node };
        }
        return { node: node };
    }

    function parseUnary() {
        var token = peek();
        if (token && token.type === "op" && (token.value === "-" || token.value === "+")) {
            pos++;
            var operand = parseUnary();
            if (operand.error) return operand;
            if (token.value === "-") return { node: { kind: "unary", op: "-", operand: operand.node } };
            return { node: operand.node };
        }
        return parsePower();
    }

    function parsePower() {
        var base = parsePrimary();
        if (base.error) return base;
        var token = peek();
        if (token && token.type === "op" && token.value === "^") {
            pos++;
            var exponent = parseUnary();
            if (exponent.error) return exponent;
            return { node: { kind: "bin", op: "^", left: base.node, right: exponent.node } };
        }
        return { node: base.node };
    }

    function parsePrimary() {
        var token = peek();
        if (!token) {
            if (openParens > 0) return { error: "No coinciden los soportes: falta " + openParens + " soporte derecho ')'" };
            return { error: "Expresión incompleta: falta un número o un paréntesis al final" };
        }
        if (token.type === "number") {
            pos++;
            return { node: { kind: "num", value: token.value, text: token.text } };
        }
        if (token.type === "op" && token.value === "(") {
            pos++;
            openParens++;
            var inner = parseExpression();
            if (inner.error) return inner;
            var closing = peek();
            if (!closing || closing.type !== "op" || closing.value !== ")") {
                return { error: "No coinciden los corchetes: falta el corchete de cierre ')'(ubicación correspondiente " + (token.pos + 1) + " paréntesis izquierdo)" };
            }
            pos++;
            openParens--;
            return { node: inner.node };
        }
        if (token.type === "name") {
            pos++;
            var lower = token.value.toLowerCase();
            var after = peek();
            var isCall = after && after.type === "op" && after.value === "(";
            if (isCall) {
                if (!Object.prototype.hasOwnProperty.call(MATH_FUNCTIONS, lower)) {
                    return { error: "función desconocida: " + token.value + "(Funciones disponibles: " + mathNamesOf(MATH_FUNCTIONS).join(", ") + ")" };
                }
                pos++;
                openParens++;
                var callArgs = [];
                var immediateClose = peek();
                if (immediateClose && immediateClose.type === "op" && immediateClose.value === ")") {
                    pos++;
                    openParens--;
                } else {
                    while (true) {
                        var arg = parseExpression();
                        if (arg.error) return arg;
                        callArgs.push(arg.node);
                        var separator = peek();
                        if (!separator) return { error: "Los paréntesis no coinciden: función " + lower + " falta soporte de cierre ')'" };
                        if (separator.type === "op" && separator.value === ",") {
                            pos++;
                            continue;
                        }
                        if (separator.type === "op" && separator.value === ")") {
                            pos++;
                            openParens--;
                            break;
                        }
                        return { error: "función " + lower + " Los parámetros deben estar separados por comas." };
                    }
                }
                return { node: { kind: "call", name: lower, args: callArgs } };
            }
            if (Object.prototype.hasOwnProperty.call(MATH_CONSTANTS, lower)) {
                return { node: { kind: "const", name: lower } };
            }
            return { error: "nombre desconocido: " + token.value + "(Constantes disponibles: " + mathNamesOf(MATH_CONSTANTS).join(", ") + ")" };
        }
        return { error: "Contenido no analizable: " + token.value + "(ubicación " + (token.pos + 1) + ")" };
    }

    var parsed = parseExpression();
    if (parsed.error) return parsed;
    if (pos < tokens.length) {
        var extra = tokens[pos];
        if (extra.type === "op" && extra.value === ")") {
            return { error: "No coinciden los soportes: soporte extra derecho ')'(ubicación " + (extra.pos + 1) + ")" };
        }
        return { error: "La expresión contiene contenido redundante.: '" + (extra.text !== undefined ? extra.text : extra.value) + "'(ubicación " + (extra.pos + 1) + ")" };
    }
    return parsed;
}

/** Evaluación: Sólo se aceptan números finitos; la división por cero, fuera de dominio y desbordamiento se convierten en errores explícitos. */
function mathEvaluate(node) {
    if (node.kind === "num") return { value: node.value };
    if (node.kind === "const") return { value: MATH_CONSTANTS[node.name] };
    if (node.kind === "unary") {
        var operand = mathEvaluate(node.operand);
        if (operand.error) return operand;
        return mathCheckValue(-operand.value, "El resultado de un signo unario negativo.");
    }
    if (node.kind === "call") {
        var values = [];
        for (var i = 0; i < node.args.length; i++) {
            var arg = mathEvaluate(node.args[i]);
            if (arg.error) return arg;
            values.push(arg.value);
        }
        var outcome;
        try {
            outcome = MATH_FUNCTIONS[node.name].apply(null, values);
        } catch (e) {
            return { error: "función " + node.name + " llamada fallida: " + (e && e.message ? e.message : e) };
        }
        return mathCheckValue(outcome, "función " + node.name + " el resultado");
    }
    if (node.kind === "bin") {
        var left = mathEvaluate(node.left);
        if (left.error) return left;
        var right = mathEvaluate(node.right);
        if (right.error) return right;
        var a = left.value;
        var b = right.value;
        var value;
        if (node.op === "+") value = a + b;
        else if (node.op === "-") value = a - b;
        else if (node.op === "*") value = a * b;
        else if (node.op === "/") {
            if (b === 0) return { error: "División por cero: el divisor no puede ser 0" };
            value = a / b;
        } else if (node.op === "%") {
            if (b === 0) return { error: "El divisor de la operación de módulo no puede ser 0" };
            value = a % b;
        } else if (node.op === "^") {
            value = Math.pow(a, b);
        } else {
            return { error: "operador desconocido: " + node.op };
        }
        return mathCheckValue(value, "operador " + node.op + " el resultado");
    }
    return { error: "Excepción de estructura de expresión" };
}

function mathCheckValue(value, label) {
    if (typeof value !== "number" || isNaN(value)) return { error: label + "No es un número válido (puede estar fuera del dominio)" };
    if (!isFinite(value)) return { error: label + "Desbordamiento (fuera del rango numérico)" };
    return { value: value };
}

/** Salida normalizada: normalización de la estructura + eliminación de paréntesis redundantes, manteniendo los literales como están. */
function mathRender(node, minPrecedence) {
    var text;
    var precedence;
    if (node.kind === "num") {
        text = node.text;
        precedence = 5;
    } else if (node.kind === "const") {
        text = node.name;
        precedence = 5;
    } else if (node.kind === "unary") {
        text = "-" + mathRender(node.operand, 3);
        precedence = 3;
    } else if (node.kind === "call") {
        var rendered = [];
        for (var i = 0; i < node.args.length; i++) rendered.push(mathRender(node.args[i], 0));
        text = node.name + "(" + rendered.join(", ") + ")";
        precedence = 5;
    } else if (node.kind === "bin") {
        precedence = MATH_PRECEDENCE[node.op];
        var leftMin = node.op === "^" ? precedence + 1 : precedence;
        var rightMin = node.op === "^" ? precedence : precedence + 1;
        var leftText = mathRender(node.left, leftMin);
        var rightText = mathRender(node.right, rightMin);
        text = node.op === "^" ? leftText + "^" + rightText : leftText + " " + node.op + " " + rightText;
    } else {
        return "?";
    }
    if (precedence < minPrecedence) return "(" + text + ")";
    return text;
}

function mathEval(args) {
    if (!args || typeof args !== "object") return { error: "El parámetro debe ser un objeto." };
    var expr = args.expr;
    if (typeof expr !== "string") return { error: "expr debe ser una cuerda" };
    var trimmed = expr.trim();
    if (trimmed.length === 0) return { error: "expr no puede estar vacío" };
    if (trimmed.length > MATH_MAX_EXPRESSION_LENGTH) {
        return { error: "expr Demasiado largo (límite superior " + MATH_MAX_EXPRESSION_LENGTH + " personajes)" };
    }
    var lexed = mathTokenize(mathNormalizeText(trimmed));
    if (lexed.error) return { error: lexed.error };
    if (lexed.tokens.length === 0) return { error: "expr No hay contenido analizable en" };
    var parsed = mathParse(lexed.tokens);
    if (parsed.error) return { error: parsed.error };
    var evaluated = mathEvaluate(parsed.node);
    if (evaluated.error) return { error: evaluated.error };
    return {
        result: evaluated.value,
        expression: mathRender(parsed.node, 0),
        note: "apoyo + - * / % ^(o **), paréntesis, signos unarios, decimales y notación científica; constantes " +
            mathNamesOf(MATH_CONSTANTS).join("/") + "; función " + mathNamesOf(MATH_FUNCTIONS).join("/") + "."
    };
}

/* ────────────────────────────── Conversión de bases ────────────────────────────── */

/** Cadena de dígitos arbitrarios → Cadena de dígitos decimales (multiplicación y suma de dígitos, admite números enteros de cualquier longitud). */
function mathBaseToDecimal(digitValues, base) {
    var decimal = [0];
    for (var i = 0; i < digitValues.length; i++) {
        var carry = digitValues[i];
        for (var j = decimal.length - 1; j >= 0; j--) {
            var current = decimal[j] * base + carry;
            decimal[j] = current % 10;
            carry = (current - decimal[j]) / 10;
        }
        while (carry > 0) {
            decimal.unshift(carry % 10);
            carry = (carry - (carry % 10)) / 10;
        }
    }
    var text = decimal.join("");
    text = text.replace(/^0+(?=\d)/, "");
    return text;
}

/** Cadena de dígitos decimales → Cadena de dígitos de la base de destino (división corta por dígitos). */
function mathDecimalToBase(decimalText, base) {
    if (decimalText === "0" || decimalText === "") return "0";
    var digits = [];
    for (var i = 0; i < decimalText.length; i++) digits.push(decimalText.charCodeAt(i) - 48);
    var out = [];
    var start = 0;
    while (start < digits.length) {
        var remainder = 0;
        for (var j = start; j < digits.length; j++) {
            var current = remainder * 10 + digits[j];
            digits[j] = Math.floor(current / base);
            remainder = current - digits[j] * base;
        }
        out.push(MATH_BASE_DIGITS.charAt(remainder));
        while (start < digits.length && digits[start] === 0) start++;
    }
    out.reverse();
    return out.join("");
}

function baseConvert(args) {
    if (!args || typeof args !== "object") return { error: "El parámetro debe ser un objeto." };
    var raw = args.value;
    if (raw === undefined || raw === null || raw === "") return { error: "value no puede estar vacío" };
    if (typeof raw === "number") {
        if (isNaN(raw) || !isFinite(raw) || raw % 1 !== 0) {
            return { error: "value Cuando es un número, debe ser un número entero limitado. Si el número entero es muy grande, utilice una cadena para pasarlo." };
        }
        raw = String(raw);
    }
    if (typeof raw !== "string") return { error: "value Debe ser una cadena o un número entero" };

    var fromParsed = mathParseBase(args.fromBase, "fromBase");
    if (fromParsed.error) return { error: fromParsed.error };
    var toParsed = mathParseBase(args.toBase, "toBase");
    if (toParsed.error) return { error: toParsed.error };

    var text = raw.trim().replace(/[\s_]/g, "");
    var negative = false;
    if (text.charAt(0) === "-") {
        negative = true;
        text = text.slice(1);
    } else if (text.charAt(0) === "+") {
        text = text.slice(1);
    }
    if (text.length === 0) return { error: "value sin dígitos significativos" };
    if (text.length > MATH_MAX_BASE_INPUT_LENGTH) {
        return { error: "value Demasiado largo (límite superior " + MATH_MAX_BASE_INPUT_LENGTH + " poco)" };
    }
    var lower = text.toLowerCase();
    if (fromParsed.value === 16 && lower.indexOf("0x") === 0) lower = lower.slice(2);
    else if (fromParsed.value === 2 && lower.indexOf("0b") === 0) lower = lower.slice(2);
    else if (fromParsed.value === 8 && lower.indexOf("0o") === 0) lower = lower.slice(2);
    if (lower.length === 0) return { error: "value sin dígitos significativos" };
    if (lower.indexOf(".") >= 0) return { error: "La conversión decimal aún no es compatible; pase solo números enteros." };

    var digitValues = [];
    for (var i = 0; i < lower.length; i++) {
        var value = MATH_BASE_DIGITS.indexOf(lower.charAt(i));
        if (value < 0) return { error: "personajes ilegales '" + text.charAt(i) + "'(" + fromParsed.value + " El sistema base sólo permite 0-9 y a-z)" };
        if (value >= fromParsed.value) {
            return { error: "numeros '" + text.charAt(i) + "' más allá " + fromParsed.value + " Rango base (número máximo " + MATH_BASE_DIGITS.charAt(fromParsed.value - 1) + ")" };
        }
        digitValues.push(value);
    }

    var decimalText = mathBaseToDecimal(digitValues, fromParsed.value);
    var converted = mathDecimalToBase(decimalText, toParsed.value).toUpperCase();
    return {
        result: (negative ? "-" : "") + converted,
        decimal: (negative ? "-" : "") + decimalText,
        fromBase: fromParsed.value,
        toBase: toParsed.value,
        digits: converted.length,
        negative: negative,
        note: "La salida utiliza letras mayúsculas; La entrada no distingue entre mayúsculas y minúsculas y admite prefijos 0x/0b/0o y números enteros de cualquier longitud (excluidos decimales)."
    };
}

function mathParseBase(raw, label) {
    var value = raw;
    if (typeof value === "string") {
        if (!/^\s*[+-]?\d+\s*$/.test(value)) return { error: label + " Debe ser un número entero" };
        value = parseInt(value, 10);
    }
    if (typeof value !== "number" || isNaN(value) || value % 1 !== 0) {
        return { error: label + " Debe ser un número entero" };
    }
    if (value < 2 || value > 36) return { error: label + " Debe estar entre 2..36 (actualmente " + value + ")" };
    return { value: value };
}

/* ────────────────────────────── teoría de números ────────────────────────────── */

/** Analice \"matriz de números/cadena separada por comas o espacios en blanco\" en una lista de números. */
function mathParseNumberList(raw, label) {
    var items;
    if (raw === undefined || raw === null) return { error: label + " no puede estar vacío" };
    if (typeof raw === "number") items = [raw];
    else if (Array.isArray(raw)) items = raw.slice();
    else if (typeof raw === "string") {
        var text = raw.trim();
        if (text.length === 0) return { error: label + " No puede ser una cadena vacía" };
        items = text.split(/[\s,,;;,]+/).filter(function (token) { return token.length > 0; });
    } else {
        return { error: label + " Debe ser una matriz numérica o una cadena delimitadora." };
    }
    if (items.length === 0) return { error: label + " Debe haber al menos un número" };
    if (items.length > MATH_MAX_LIST_LENGTH) {
        return { error: label + " Demasiados elementos (límite superior " + MATH_MAX_LIST_LENGTH + " )" };
    }
    var values = [];
    for (var i = 0; i < items.length; i++) {
        var item = items[i];
        if (typeof item === "number") {
            if (isNaN(item) || !isFinite(item)) return { error: "No. " + (i + 1) + " los números no son finitos" };
            values.push(item);
            continue;
        }
        if (typeof item !== "string" || !MATH_NUMERIC_PATTERN.test(item.trim())) {
            return { error: "No. " + (i + 1) + " El artículo no es un número legal.: " + String(item) };
        }
        var parsed = parseFloat(item);
        if (isNaN(parsed) || !isFinite(parsed)) return { error: "No. " + (i + 1) + " números fuera de rango" };
        values.push(parsed);
    }
    return { values: values };
}

/** Convierta la lista de números en una lista de números enteros. */
function mathParseIntegerList(raw, label) {
    var parsed = mathParseNumberList(raw, label);
    if (parsed.error) return parsed;
    for (var i = 0; i < parsed.values.length; i++) {
        if (parsed.values[i] % 1 !== 0) {
            return { error: "No. " + (i + 1) + " los numeros no son enteros: " + parsed.values[i] };
        }
        if (Math.abs(parsed.values[i]) > MATH_SAFE_INTEGER) {
            return { error: "No. " + (i + 1) + " números enteros fuera del rango seguro (|n| ≤ 2^53-1)" };
        }
    }
    return parsed;
}

function mathGcdPair(a, b) {
    a = Math.abs(a);
    b = Math.abs(b);
    while (b !== 0) {
        var rest = a % b;
        a = b;
        b = rest;
    }
    return a;
}

/** Mínimo común múltiplo; devuelve NaN si excede el rango de enteros seguros. */
function mathLcmPair(a, b) {
    if (a === 0 || b === 0) return 0;
    var gcd = mathGcdPair(a, b);
    var result = Math.abs((a / gcd) * b);
    if (!isFinite(result) || result > MATH_SAFE_INTEGER) return NaN;
    return result;
}

function mathIsPrime(n) {
    if (n < 2) return false;
    if (n === 2 || n === 3) return true;
    if (n % 2 === 0 || n % 3 === 0) return false;
    var step = 4;
    for (var i = 5; i * i <= n; i += step) {
        if (n % i === 0) return false;
        step = 6 - step;
    }
    return true;
}

/** Factorización prima (división de prueba, rendimientos [{prime, exponent}]). */
function mathFactorize(n) {
    var factors = [];
    var remaining = n;
    var divisor = 2;
    while (divisor * divisor <= remaining) {
        if (remaining % divisor !== 0) {
            divisor = divisor === 2 ? 3 : divisor + 2;
            continue;
        }
        var exponent = 0;
        while (remaining % divisor === 0) {
            remaining = remaining / divisor;
            exponent++;
        }
        factors.push({ prime: divisor, exponent: exponent });
    }
    if (remaining > 1) factors.push({ prime: remaining, exponent: 1 });
    return factors;
}

function numberTheory(args) {
    if (!args || typeof args !== "object") return { error: "El parámetro debe ser un objeto." };
    var mode = typeof args.mode === "string" ? args.mode.trim().toLowerCase() : "";
    if (mode.length === 0) return { error: "mode No puede estar vacío, debe ser uno de gcd/lcm/factorize/is_prime" };
    var parsed = mathParseIntegerList(args.numbers, "numbers");
    if (parsed.error) return { error: parsed.error };
    var numbers = parsed.values;

    if (mode === "gcd" || mode === "lcm") {
        var accumulator = Math.abs(numbers[0]);
        for (var i = 1; i < numbers.length; i++) {
            if (mode === "gcd") {
                accumulator = mathGcdPair(accumulator, numbers[i]);
            } else {
                accumulator = mathLcmPair(accumulator, numbers[i]);
                if (isNaN(accumulator)) {
                    return { error: "El mínimo común múltiplo está fuera del rango de enteros seguros (2^53-1), reduzca la cantidad o utilice un número menor en su lugar" };
                }
            }
        }
        return {
            result: accumulator,
            mode: mode,
            numbers: numbers,
            note: mode === "gcd" ? "El resultado es un máximo común divisor no negativo;gcd(0, 0) = 0." : "El resultado es el mínimo común múltiplo no negativo; si algún número es 0, el resultado es 0."
        };
    }

    if (mode === "factorize" || mode === "is_prime") {
        if (numbers.length !== 1) {
            return { error: mode + " Sólo acepta un número entero (actualmente recibido " + numbers.length + " )" };
        }
        var target = numbers[0];
        if (Math.abs(target) > MATH_MAX_FACTORIZE) {
            return { error: "El valor es demasiado grande (límite superior " + MATH_MAX_FACTORIZE + "), utilice un número entero más pequeño en su lugar" };
        }
        if (target < 0) return { error: "Sólo se admiten números enteros no negativos (actualmente " + target + ")" };
        if (mode === "is_prime") {
            var prime = mathIsPrime(target);
            return {
                result: prime,
                mode: "is_prime",
                number: target,
                divisors: prime ? [1, target] : mathSmallDivisors(target),
                note: "El método de división de prueba se utiliza para determinar el resultado."
            };
        }
        if (target === 0 || target === 1) {
            return {
                result: [],
                factors: [],
                expression: String(target),
                isPrime: false,
                number: target,
                note: "0 No hay factorización prima con 1."
            };
        }
        var factors = mathFactorize(target);
        var flat = [];
        var parts = [];
        for (var j = 0; j < factors.length; j++) {
            parts.push(factors[j].exponent === 1 ? String(factors[j].prime) : factors[j].prime + "^" + factors[j].exponent);
            for (var k = 0; k < factors[j].exponent; k++) flat.push(factors[j].prime);
        }
        return {
            result: factors,
            factors: flat,
            expression: parts.join(" × "),
            isPrime: factors.length === 1 && factors[0].exponent === 1,
            number: target
        };
    }

    return { error: "No compatible mode: " + args.mode + "(gcd/lcm/factorize/is_prime disponible)" };
}

/** Todos los factores positivos de números enteros pequeños (sólo para is_prime=false mensaje, el tamaño está limitado por MATH_MAX_FACTORIZE). */
function mathSmallDivisors(n) {
    var divisors = [];
    if (n < 1) return divisors;
    for (var i = 1; i * i <= n; i++) {
        if (n % i !== 0) continue;
        divisors.push(i);
        if (i !== n / i) divisors.push(n / i);
    }
    divisors.sort(function (a, b) { return a - b; });
    return divisors.length > 64 ? divisors.slice(0, 64) : divisors;
}

/* ────────────────────────────── Estadísticas ────────────────────────────── */

function stats(args) {
    if (!args || typeof args !== "object") return { error: "El parámetro debe ser un objeto." };
    var parsed = mathParseNumberList(args.numbers, "numbers");
    if (parsed.error) return { error: parsed.error };
    var values = parsed.values.slice();
    var count = values.length;

    var sum = 0;
    var min = values[0];
    var max = values[0];
    for (var i = 0; i < count; i++) {
        sum += values[i];
        if (values[i] < min) min = values[i];
        if (values[i] > max) max = values[i];
    }
    var mean = sum / count;

    var sorted = values.slice().sort(function (a, b) { return a - b; });
    var middle = Math.floor(count / 2);
    var median = count % 2 === 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;

    var squared = 0;
    for (var j = 0; j < count; j++) {
        var diff = values[j] - mean;
        squared += diff * diff;
    }
    var variance = squared / count;
    var sampleVariance = count > 1 ? squared / (count - 1) : 0;

    return {
        result: {
            count: count,
            sum: sum,
            mean: mean,
            median: median,
            min: min,
            max: max,
            range: max - min,
            stddev: Math.sqrt(variance),
            variance: variance,
            sampleStddev: Math.sqrt(sampleVariance)
        },
        note: "stddev/variance es la desviación estándar y la varianza de la población (dividida por n);sampleStddev es la desviación estándar de la muestra (dividida por n-1);median es la mediana después de la clasificación."
    };
}
