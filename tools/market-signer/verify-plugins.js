#!/usr/bin/env node
/**
 * Script de aceptación del comportamiento del complemento.
 *
 * Utilice el anfitrión (WebViewSkillEngine) Exactamente el mismo contrato de llamada se ejecuta en todas las herramientas de cada complemento:
 *   eval(Código fuente de entrada); JSON.stringify(fn.apply(null, [args]))
 *
 * Calibre de aceptación:
 *  1. Los casos de uso normales deben devolver objetos sin campos de error;
 *  2. Los casos de uso ilegal deben devolver objetos con un error no nulo (en lugar de generar excepciones);
 *  3. No se permiten excepciones en ningún caso de uso.——El lanzamiento se considera un defecto del complemento (el host lo convertirá en una llamada de herramienta fallida).
 *
 * Uso:node verify-plugins.js [Directorio de complementos, predeterminado ../plugins]
 */

const fs = require("fs");
const path = require("path");

const pluginsRoot = process.argv[2] || path.join(__dirname, "plugins");

const cases = {
    "text-toolkit": [
        ["textStats", { text: "Hello mundo 👋\n\nsegundo párrafo" }, "ok"],
        ["textStats", { text: 123 }, "error"],
        ["textTransform", { text: "hello world_test-case", mode: "camel" }, "ok"],
        ["textTransform", { text: "x", mode: "nope" }, "error"],
        ["slugify", { text: "Hello, Muse mundo!" }, "ok"],
        ["base64Tool", { text: "prueba china 🚀", mode: "encode" }, "ok"],
        ["base64Tool", { text: "!!!not-base64!!!", mode: "decode" }, "error"],
        ["jsonTool", { text: '{"a":1,"b":[2,3]}', mode: "format" }, "ok"],
        ["jsonTool", { text: "{", mode: "format" }, "error"],
        ["linesTool", { text: "b\na\nb\nc", mode: "dedupe" }, "ok"],
    ],
    "time-toolkit": [
        ["nowTime", {}, "ok"],
        ["timestampConvert", { value: "1789826713", mode: "to_date", unit: "s" }, "ok"],
        ["timestampConvert", { value: "not-a-date", mode: "to_date" }, "error"],
        ["durationFormat", { value: 93784000 }, "ok"],
        ["durationFormat", { value: "abc" }, "error"],
        ["dateDiff", { from: "2026-09-01", to: "2026-09-19" }, "ok"],
        ["dateDiff", { from: "oops", to: "2026-09-19" }, "error"],
    ],
    "math-toolkit": [
        ["mathEval", { expr: "(1+2)*3^2 - 4/2" }, "ok"],
        ["mathEval", { expr: "1+*2" }, "error"],
        ["mathEval", { expr: "1/0" }, "any"],
        ["baseConvert", { value: "ff", fromBase: 16, toBase: 2 }, "ok"],
        ["baseConvert", { value: "zz", fromBase: 16, toBase: 2 }, "error"],
        ["numberTheory", { numbers: "12,18", mode: "gcd" }, "ok"],
        ["stats", { numbers: "1 2 3 4" }, "ok"],
        ["stats", { numbers: "a b c" }, "error"],
    ],
    "unit-toolkit": [
        ["unitConvert", { value: 1, from: "km", to: "m" }, "ok"],
        ["unitConvert", { value: 100, from: "C", to: "F" }, "ok"],
        ["unitConvert", { value: 1, from: "km", to: "zzz" }, "error"],
        ["dataSize", { mode: "format", bytes: 1610612736 }, "ok"],
        ["dataSize", { mode: "parse", text: "1.5GB" }, "ok"],
        ["numberFormat", { value: 1234567.891, mode: "thousands" }, "ok"],
    ],
    "color-toolkit": [
        ["colorConvert", { color: "#3366ff" }, "ok"],
        ["colorConvert", { color: "not-a-color" }, "error"],
        ["colorAdjust", { color: "#3366ff", mode: "lighten", amount: 0.2 }, "ok"],
        ["colorContrast", { color1: "#000000", color2: "#ffffff" }, "ok"],
        ["colorPalette", { color: "#3366ff", mode: "triadic" }, "ok"],
        ["colorPalette", { color: "#3366ff", mode: "nope" }, "error"],
    ],
    "regex-toolkit": [
        ["regexTest", { pattern: "([a-z])(\\d)", text: "a1 b2" }, "ok"],
        ["regexTest", { pattern: "(a+)+", text: "aaaa" }, "any"],
        ["regexTest", { pattern: "a", text: "aaa", flags: "z" }, "error"],
        ["regexExtract", { pattern: "\\d+", text: "a1 b22 c333" }, "ok"],
        ["regexReplace", { pattern: "\\d+", text: "a1b2", replacement: "#" }, "ok"],
    ],
    "url-toolkit": [
        ["urlParse", { url: "https://user:pass@example.com:8443/a/b?x=1&y=2#frag" }, "ok"],
        ["urlParse", { url: "ht tp://bad" }, "error"],
        ["urlBuild", { protocol: "https", hostname: "example.com", path: "/a", params: { x: "1" } }, "ok"],
        ["urlBuild", { hostname: "example.com" }, "ok"],
        ["queryTool", { mode: "parse", query: "a=1&b=2&a=3" }, "ok"],
        ["queryTool", { mode: "nope" }, "error"],
    ],
    "csv-toolkit": [
        ["csvToJson", { csv: 'a,b\n1,"x,y"\n2,3' }, "ok"],
        ["csvToJson", { csv: 42 }, "error"],
        ["jsonToCsv", { data: [{ a: 1, b: 2 }, { a: 3, b: 4 }] }, "ok"],
        ["csvStats", { csv: "n,s\n1,a\n2,b\n3,a" }, "ok"],
    ],
};

function loadPlugin(id) {
    const dir = path.join(pluginsRoot, id);
    const manifest = JSON.parse(fs.readFileSync(path.join(dir, "manifest.json"), "utf8"));
    const source = fs.readFileSync(path.join(dir, manifest.entry), "utf8");
    return { manifest, source };
}

/** Una réplica exacta del contrato de llamada al anfitrión. */
function callTool(source, functionName, args) {
    const combined =
        source + "\n;" + "JSON.stringify(" + functionName + ".apply(null, " + JSON.stringify([args]) + "))";
    return eval(combined);
}

let passed = 0;
let failed = 0;
const failures = [];

for (const [pluginId, pluginCases] of Object.entries(cases)) {
    let plugin;
    try {
        plugin = loadPlugin(pluginId);
    } catch (error) {
        failed += 1;
        failures.push(`${pluginId}: Error al cargar ${error.message}`);
        continue;
    }

    const declared = new Set(plugin.manifest.tools.map((tool) => tool.functionName));
    for (const [functionName, args, expectation] of pluginCases) {
        const label = `${pluginId}/${functionName}`;
        if (!declared.has(functionName)) {
            failed += 1;
            failures.push(`${label}: manifest La función no está declarada.`);
            continue;
        }

        let raw;
        try {
            raw = callTool(plugin.source, functionName, args);
        } catch (error) {
            failed += 1;
            failures.push(`${label}: Lanzar excepción ${error.message}`);
            continue;
        }

        let value;
        try {
            value = JSON.parse(raw);
        } catch (error) {
            failed += 1;
            failures.push(`${label}: El valor de retorno no es un JSON válido (${raw.slice(0, 80)})`);
            continue;
        }
        if (value === null || typeof value !== "object" || Array.isArray(value)) {
            failed += 1;
            failures.push(`${label}: El valor de retorno debe ser un objeto, que en realidad es ${typeof value}`);
            continue;
        }

        const hasError = typeof value.error === "string" && value.error.length > 0;
        if (expectation === "ok" && hasError) {
            failed += 1;
            failures.push(`${label}: Éxito esperado pero devolvió el error \"${value.error}\"`);
            continue;
        }
        if (expectation === "error" && !hasError) {
            failed += 1;
            failures.push(`${label}: Esperando algo equivocado pero teniendo éxito (${JSON.stringify(value).slice(0, 80)})`);
            continue;
        }
        passed += 1;
    }

    // Cada herramienta debe tener al menos un caso de uso para evitar herramientas nuevas no probadas.
    for (const functionName of declared) {
        const covered = pluginCases.some(([name]) => name === functionName);
        if (!covered) {
            failed += 1;
            failures.push(`${pluginId}/${functionName}: Faltan casos de uso en el script de aceptación`);
        }
    }
}

console.log(`Aceptación de complementos:${passed} pasó,${failed} falló`);
if (failures.length > 0) {
    for (const failure of failures) console.log(`  - ${failure}`);
    process.exit(1);
}
