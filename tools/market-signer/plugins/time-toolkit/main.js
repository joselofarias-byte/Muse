/**
 * Muse Complemento: Caja de herramientas de tiempo
 *
 * Ejecute el contrato (alojado por WebViewSkillEngine):
 *  - Cada función de utilidad recibe un objeto de parámetro, como function nowTime(args);
 *  - El valor de retorno puede ser un objeto normal, y el host lo serializará JSON automáticamente y se lo entregará al modelo;
 *  - Devolución en caso de error { error: "Razón" }, el anfitrión marcará dichos resultados como fracasos.
 *
 * Zona horaria: excepto las entradas marcadas explícitamente con Z/UTC, siempre se interpretará y emitirá de acuerdo con la zona horaria local del dispositivo.
 */

var MS_PER_MINUTE = 60 * 1000;
var MS_PER_HOUR = 60 * MS_PER_MINUTE;
var MS_PER_DAY = 24 * MS_PER_HOUR;

var DATE_PATTERN = /^(\d{4})-(\d{1,2})-(\d{1,2})(?:[ T](\d{1,2}):(\d{2})(?::(\d{2}))?)?(Z)?$/;

/** poner "2026-09-19 15:30:00" / ISO Marca de tiempo de cadena/numérica pura analizada en milisegundos. */
function parseInstant(raw, unit) {
    var text = String(raw).trim();
    if (/^\d+$/.test(text)) {
        var numeric = parseInt(text, 10);
        return String(unit).toLowerCase() === "s" ? numeric * 1000 : numeric;
    }
    var matched = DATE_PATTERN.exec(text);
    if (!matched) return NaN;
    var year = parseInt(matched[1], 10);
    var month = parseInt(matched[2], 10);
    var day = parseInt(matched[3], 10);
    var hour = matched[4] === undefined ? 0 : parseInt(matched[4], 10);
    var minute = matched[5] === undefined ? 0 : parseInt(matched[5], 10);
    var second = matched[6] === undefined ? 0 : parseInt(matched[6], 10);
    if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || minute > 59 || second > 59) {
        return NaN;
    }
    if (matched[7]) return Date.UTC(year, month - 1, day, hour, minute, second);
    return new Date(year, month - 1, day, hour, minute, second).getTime();
}

function pad(value, width) {
    var text = String(value);
    while (text.length < width) text = "0" + text;
    return text;
}

/** Cadena de estilo ISO de zona horaria local (con desplazamiento para distinguirla de UTC). */
function localIso(ms) {
    var date = new Date(ms);
    var offsetMinutes = -date.getTimezoneOffset();
    var sign = offsetMinutes >= 0 ? "+" : "-";
    var absolute = Math.abs(offsetMinutes);
    return pad(date.getFullYear(), 4) + "-" + pad(date.getMonth() + 1, 2) + "-" + pad(date.getDate(), 2) +
        "T" + pad(date.getHours(), 2) + ":" + pad(date.getMinutes(), 2) + ":" + pad(date.getSeconds(), 2) +
        sign + pad(Math.floor(absolute / 60), 2) + ":" + pad(absolute % 60, 2);
}

function isoWeekNumber(date) {
    var target = new Date(date.getFullYear(), date.getMonth(), date.getDate());
    var dayNumber = (target.getDay() + 6) % 7;
    target.setDate(target.getDate() - dayNumber + 3);
    var firstThursday = new Date(target.getFullYear(), 0, 4);
    var firstDayNumber = (firstThursday.getDay() + 6) % 7;
    firstThursday.setDate(firstThursday.getDate() - firstDayNumber + 3);
    return 1 + Math.round((target.getTime() - firstThursday.getTime()) / (7 * MS_PER_DAY));
}

function nowTime() {
    var now = new Date();
    var ms = now.getTime();
    var weekdays = ["domingo", "lunes", "martes", "miércoles", "jueves", "viernes", "Sábado"];
    return {
        result: {
            epochMillis: ms,
            epochSeconds: Math.floor(ms / 1000),
            localIso: localIso(ms),
            utcIso: now.toISOString(),
            localDate: pad(now.getFullYear(), 4) + "-" + pad(now.getMonth() + 1, 2) + "-" + pad(now.getDate(), 2),
            localTime: pad(now.getHours(), 2) + ":" + pad(now.getMinutes(), 2) + ":" + pad(now.getSeconds(), 2),
            weekday: weekdays[now.getDay()],
            isoWeek: isoWeekNumber(now)
        },
        timezone: "zona horaria local del dispositivo,UTC compensar " + (-now.getTimezoneOffset() / 60) + " horas"
    };
}

function timestampConvert(args) {
    var raw = args.value;
    if (raw === undefined || raw === null || String(raw).trim().length === 0) {
        return { error: "value no puede estar vacío" };
    }
    var mode = typeof args.mode === "string" ? args.mode.trim().toLowerCase() : "";
    var unit = typeof args.unit === "string" ? args.unit.trim().toLowerCase() : "ms";
    if (unit !== "ms" && unit !== "s") return { error: "unit Debe ser ms o s" };
    if (mode === "to_date") {
        var ms = parseInstant(raw, unit);
        if (isNaN(ms)) return { error: "No se puede analizar la marca de tiempo: " + raw };
        var date = new Date(ms);
        return {
            result: {
                epochMillis: ms,
                epochSeconds: Math.floor(ms / 1000),
                localIso: localIso(ms),
                utcIso: date.toISOString()
            }
        };
    }
    if (mode !== "to_timestamp") return { error: "mode Debe ser hasta la fecha o to_timestamp" };
    var instant = parseInstant(raw, unit);
    if (isNaN(instant)) return { error: "No se puede analizar la fecha: " + raw };
    return {
        result: {
            epochMillis: instant,
            epochSeconds: Math.floor(instant / 1000),
            localIso: localIso(instant),
            utcIso: new Date(instant).toISOString()
        }
    };
}

function durationFormat(args) {
    var value = typeof args.value === "number" ? args.value : parseFloat(args.value);
    if (isNaN(value)) return { error: "value debe ser un numero" };
    var unit = typeof args.unit === "string" ? args.unit.trim().toLowerCase() : "ms";
    if (unit !== "ms" && unit !== "s") return { error: "unit Debe ser ms o s" };
    var totalMs = unit === "s" ? value * 1000 : value;
    var negative = totalMs < 0;
    var remaining = Math.abs(Math.round(totalMs));
    var days = Math.floor(remaining / MS_PER_DAY);
    remaining -= days * MS_PER_DAY;
    var hours = Math.floor(remaining / MS_PER_HOUR);
    remaining -= hours * MS_PER_HOUR;
    var minutes = Math.floor(remaining / MS_PER_MINUTE);
    remaining -= minutes * MS_PER_MINUTE;
    var seconds = Math.floor(remaining / 1000);
    var millis = remaining - seconds * 1000;
    var parts = [];
    if (days > 0) parts.push(days + "dia");
    if (hours > 0) parts.push(hours + "horas");
    if (minutes > 0) parts.push(minutes + "puntos");
    if (seconds > 0) parts.push(seconds + "segundos");
    if (parts.length === 0) parts.push(millis + "milisegundos");
    return {
        result: (negative ? "-" : "") + parts.join(""),
        breakdown: { days: days, hours: hours, minutes: minutes, seconds: seconds, millis: millis }
    };
}

function dateDiff(args) {
    var fromMs = parseInstant(args.from, "ms");
    var toMs = parseInstant(args.to, "ms");
    if (isNaN(fromMs)) return { error: "No se puede analizar la fecha de inicio: " + args.from };
    if (isNaN(toMs)) return { error: "No se puede analizar la fecha de finalización: " + args.to };
    var deltaMs = toMs - fromMs;
    var absolute = Math.abs(deltaMs);
    var days = Math.floor(absolute / MS_PER_DAY);
    if (args.excludeEnd) days = days > 0 ? days - 1 : 0;
    var remainder = absolute - Math.floor(absolute / MS_PER_DAY) * MS_PER_DAY;
    var hours = Math.floor(remainder / MS_PER_HOUR);
    var minutes = Math.floor((remainder - hours * MS_PER_HOUR) / MS_PER_MINUTE);
    return {
        result: {
            direction: deltaMs === 0 ? "same" : (deltaMs > 0 ? "later" : "earlier"),
            totalDays: Math.floor(absolute / MS_PER_DAY),
            days: days,
            hours: hours,
            minutes: minutes,
            totalHours: Math.floor(absolute / MS_PER_HOUR),
            totalMinutes: Math.floor(absolute / MS_PER_MINUTE),
            totalSeconds: Math.floor(absolute / 1000),
            text: days + "dia" + hours + "horas" + minutes + "puntos"
        },
        from: localIso(fromMs),
        to: localIso(toMs)
    };
}
