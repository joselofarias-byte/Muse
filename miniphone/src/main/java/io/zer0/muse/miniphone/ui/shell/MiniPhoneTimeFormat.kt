package io.zer0.muse.miniphone.ui.shell

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Texto de hora en la interfaz.
 *
 * El tiempo relativo es sólo [relativeTimeOf] Implementado en un solo lugar:Ajústalo en Momentos, comentarios y listas de mensajes,No escribas el propio de cada uno.
 * Existe otro estándar para la separación temporal entre la barra de estado y la página de chat.(Lo que quieres es "a qué hora" en lugar de "hace cuánto tiempo".),Todavía en este objeto a continuación.
 */
internal object MiniPhoneTimeFormat {

    private val clockFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** reloj de la barra de estado:`HH:mm`. */
    fun clock(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(clockFormatter)

    /**
     * Separación de tiempo de la página de chat:"hoy"solo da tiempo,"ayer"Traiga la fecha con la fecha anterior.
     *
     * Separar filas tiene como objetivo dar"El tiempo salta repentinamente al pasar las páginas."marcado,Entonces no es necesario escribir el mismo día."hoy"Dos palabras.
     */
    fun separator(epochMs: Long, now: Long = System.currentTimeMillis()): String {
        if (epochMs <= 0L) return ""
        val zone = ZoneId.systemDefault()
        val clock = clock(epochMs)
        val thenDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        val nowDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when {
            thenDate == nowDate -> clock
            thenDate == nowDate.minusDays(1) -> "ayer $clock"
            thenDate.year == nowDate.year -> "${thenDate.monthValue} mes ${thenDate.dayOfMonth} dia $clock"
            else -> "${thenDate.year} año ${thenDate.monthValue} mes ${thenDate.dayOfMonth} dia $clock"
        }
    }
}

private const val MINUTE_MS: Long = 60_000L
private const val HOUR_MS: Long = 60L * MINUTE_MS

private val RelativeClock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * tiempo relativo:¿Cuánto tiempo hace que pasó eso?
 *
 * seis ramas,el orden es prioridad:
 * 1. dentro de un minuto(con marca de tiempo en el futuro)"Justo ahora"
 * 2. Dentro de una hora "12 hace minutos"
 * 3. El mismo día "3 hace horas"
 * 4. Ayer "Ayer 21:30"
 * 5. A principios de este año "10mes28Domingo 21:30"
 * 6. Nochevieja2025año10mes28dia—— Escribe la fecha solo en Nochevieja,El año es suficiente para posicionarse.
 *
 * La marca de tiempo es 0(no hay tiempo)Devolver cadena vacía,Déjelo en blanco a discreción de la persona que llama. La parte del calendario se calcula según la zona horaria local.:
 * "Ayer" es ayer en el calendario,No "24 hace horas"—— Mira una hora de diálogo temprano en la mañana.,Todavía pertenece al ayer.
 *
 * @param now Como marca de tiempo del "ahora",Reloj del sistema predeterminado;Prueba cada rama con ella clavada.
 */
internal fun relativeTimeOf(epochMs: Long, now: Long = System.currentTimeMillis()): String {
    if (epochMs <= 0L) return ""
    val diff = now - epochMs
    if (diff < MINUTE_MS) return "justo ahora"
    if (diff < HOUR_MS) return "${diff / MINUTE_MS} hace minutos"

    val zone = ZoneId.systemDefault()
    val then = Instant.ofEpochMilli(epochMs).atZone(zone)
    val thenDate = then.toLocalDate()
    val nowDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when {
        thenDate == nowDate -> "${diff / HOUR_MS} hace horas"
        thenDate == nowDate.minusDays(1) -> "ayer ${then.format(RelativeClock)}"
        thenDate.year == nowDate.year -> "${thenDate.monthValue}mes${thenDate.dayOfMonth}dia ${then.format(RelativeClock)}"
        else -> "${thenDate.year}año${thenDate.monthValue}mes${thenDate.dayOfMonth}dia"
    }
}
