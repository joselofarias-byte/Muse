package io.zer0.muse.ui.theme

/**
 * v1.75: Administre de forma centralizada cadenas de formato de fecha y hora dentro de la aplicación.
 *
 * Originalmente `"MM-dd HH:mm"`,`"HH:mm"`,`"yyyy-MM-dd HH:mm"` etc. cadenas de formato repartidas en más de 16 archivos,
 * La modificación del formato requiere sincronización en varios lugares y es fácil pasarla por alto. Centralice aquí para un ajuste unificado.
 *
 * Nota:SimpleDateFormat El objeto en sí todavía se crea utilizando variables locales/recordadas en cada llamada.(v1.71 Ruta de recombinación de alta frecuencia optimizada).
 * Aquí solo se unifican los literales de cadena de formato.
 */
object MuseDateFormats {
    /** poco tiempo(tiempo:puntos),Como por ejemplo "14:30". */
    const val TIME_SHORT = "HH:mm"

    /**
     * 12 hora por hora(traer AM/PM),Como por ejemplo "2:30 PM".
     * L-DF1: "a" El texto AM/PM del marcador de posición está determinado por la configuración regional del SimpleDateFormat que llama.
     * (Locale.US → "AM/PM",Locale.CHINA → "mañana/tarde"). Si necesita arreglar la pantalla en inglés,
     * El sitio que realiza la llamada debe pasar explícitamente Locale.US para construir SimpleDateFormat.
     */
    const val TIME_12H = "h:mm a"

    /** mes-hora del día:puntos,Como por ejemplo "07-11 14:30". La visualización de la hora del elemento de lista es la más utilizada. */
    const val DATE_TIME_SHORT = "MM-dd HH:mm"

    /** mes-dia,Como por ejemplo "07-11". */
    const val DATE_SHORT = "MM-dd"

    /** año-mes-hora del día:puntos,Como por ejemplo "2026-07-11 14:30". Página de detalles/Configure la visualización del tiempo de la página. */
    const val DATE_TIME_FULL = "yyyy-MM-dd HH:mm"

    /** año-mes-hora del día:puntos:segundos,Como por ejemplo "2026-07-11 14:30:45". Marca de tiempo precisa. */
    const val DATE_TIME_FULL_SEC = "yyyy-MM-dd HH:mm:ss"

    /** año-mes-dia,Como por ejemplo "2026-07-11". Sólo fecha. */
    const val DATE_ONLY = "yyyy-MM-dd"

    /** Marca de tiempo del nombre del archivo,Como por ejemplo "20260711-143045"(Locale.US,para respaldo/Nombre del archivo de registro de fallos). */
    const val FILE_TIMESTAMP = "yyyyMMdd-HHmmss"

    /** Separador de fechas chino,Como por ejemplo "07mes11dia viernes"(Locale.getDefault,EEEE para el día de la semana localizado). */
    const val DATE_WEEKDAY_CN = "MMmesdddia EEEE"

    /** I18N-06: Línea del tiempo del mini teléfono celular — Hora + mes y día chinos,Como por ejemplo "14:30  ·  07mes11dia". */
    const val TIME_WITH_MONTH_DAY = "HH:mm  ·  MMmesdddia"
}
