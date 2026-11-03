package io.zer0.muse.tools

import android.content.Context
import android.os.BatteryManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * current_status Herramientas.
 *
 * Conciencia ambiental ligera:Hora, información del dispositivo, batería, pantalla.
 * action=list Devolver llaves disponibles;action=get Devuelve el valor de una clave.
 */
object CurrentStatusTool {

    private const val DAY_BOUNDARY_HOUR = 4

    /**
     * v1.131: SimpleDateFormat Cambiar a caché ThreadLocal,Evite crear uno nuevo cada vez que se llame a la herramienta current_status.
     * time provider El fmt interno necesita ser modificado. timeZone,tan solo ThreadLocal(Aislar estado mutable).
     */
    private val FMT_FULL_TZ = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault()) }
    private val FMT_DATE = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }

    data class StatusProvider(
        val key: String,
        val description: String,
        val get: (Context) -> String,
    )

    private fun getProviders(): List<StatusProvider> = listOf(
        StatusProvider("time", "Current time with timezone.") { ctx ->
            val tz = TimeZone.getDefault()
            // v1.131: Reutilizar caché ThreadLocal formatter(Este fmt sólo se establece dentro del proveedor de tiempo de este hilo. timeZone,
            // No interactuará con otros proveedores.,Seguro de reutilizar).
            val fmt = FMT_FULL_TZ.get() ?: SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault())
            fmt.timeZone = tz
            "time: ${fmt.format(Date())} | timezone: ${tz.id} | utcOffset: ${tz.getOffset(System.currentTimeMillis()) / 3600000}"
        },
        StatusProvider("logical_date", "Logical date (day starts at 04:00).") { _ ->
            val cal = java.util.Calendar.getInstance()
            if (cal.get(java.util.Calendar.HOUR_OF_DAY) < DAY_BOUNDARY_HOUR) {
                cal.add(java.util.Calendar.DAY_OF_MONTH, -1)
            }
            val fmt = FMT_DATE.get() ?: SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            "logical_date: ${fmt.format(cal.time)} | dayBoundaryHour: $DAY_BOUNDARY_HOUR"
        },
        StatusProvider("device", "Device brand, model, Android version.") { _ ->
            "brand: ${android.os.Build.BRAND} | model: ${android.os.Build.MODEL} | android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})"
        },
        StatusProvider("battery", "Battery level and charging status.") { ctx ->
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            "level: $level% | charging: $charging"
        },
        StatusProvider("display", "Screen resolution and density.") { ctx ->
            val dm = ctx.resources.displayMetrics
            "resolution: ${dm.widthPixels}x${dm.heightPixels} | density: ${dm.density}x"
        },
    )

    fun toolDef() = ToolRegistry.ToolDef(
        name = "current_status",
        description = "Estado del entorno ligero(Hora, dispositivo, batería, pantalla.)." +
            "Usar primero action=list Ver claves disponibles,otra vez action=get Tome un valor específico.",
        parameters = mapOf(
            "action" to "Required. 'list' returns available keys; 'get' returns one key's value.",
            "key" to "Required when action=get. The status key to query (time/logical_date/device/battery/display).",
        ),
        required = setOf("action"),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
    )

    fun execute(args: Map<String, String>, context: Context): String {
        val action = args["action"]?.trim() ?: return "Error: action parameter is required."
        val providers = getProviders()
        return when (action) {
            "list" -> buildString {
                appendLine("Available status keys:")
                for (p in providers) {
                    appendLine("- ${p.key}: ${p.description}")
                }
                appendLine()
                appendLine("Usage: call with action=get and key=<key_name>")
            }.trimEnd()

            "get" -> {
                val key = args["key"]?.trim()
                    ?: return "Error: key parameter is required when action=get."
                val provider = providers.find { it.key == key }
                    ?: return "Error: unknown key '$key'. Call action=list to see available keys."
                provider.get(context)
            }

            else -> "Error: action must be 'list' or 'get'."
        }
    }
}
