package io.zer0.muse.transformer

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.TimeZone

/**
 * Prompt Plantilla Transformer(Phase 8.1 H1 + Phase 9.2 M22 Expansión variable mejorada + v1.97).
 *
 * Representar el contenido del mensaje del SISTEMA como una plantilla compatible con Pebble,apoyo:
 *  - variable: {{ var }} / {{ user.name }}(ruta del número de punto)
 *  - filtrar: {{ var | upper }} / {{ var | default('foo') }} / {{ list | length }}
 *  - Condiciones: {% if cond %}...{% elif c %}...{% else %}...{% endif %}
 *  - bucle: {% for item in items %}...{% endfor %}(loop.index/first/last/length)
 *  - Comentario: {# comment #}
 *
 * variables incorporadas:
 *  - {{ date }}: fecha actual(yyyy-MM-dd)
 *  - {{ time }}: hora actual(HH:mm:ss)
 *  - {{ datetime }}: Fecha y hora actuales(yyyy-MM-dd HH:mm:ss)
 *  - {{ session_id }}: sesión actual id
 *  - {{ model_id }}: modelo actual id
 *  - {{ assistant_name }}: Nombre del asistente actual(Si se proporcionan extras)
 *
 * v1.97 Nuevo(13 variables incorporadas):
 *  - {{ locale }}: idioma del sistema/área(Como zh-CN,en-US)
 *  - {{ timezone }}: Zona horaria del sistema(Como por ejemplo Asia/Shanghai)
 *  - {{ device_info }}: Modelo y fabricante del equipo.(Como por ejemplo "Pixel 7 (Google)")
 *  - {{ battery_level }}: porcentaje de batería(0-100,entero;La lectura falló como "unknown")
 *  - {{ user }} / {{ user_name }} / {{ nickname }}: Apodo de usuario(de extras "user_nickname" leer)
 *  - {{ char }} / {{ character_name }}: Nombre del personaje(Lo mismo assistant_name,Prioridad extras "assistant_name")
 *
 * Variables personalizadas: extras adentro "template_vars" → Map<String, Any?> Se fusionará en el mapeo de variables.
 *
 * Uso:
 * ```
 * val context = TransformContext(
 *     extras = mapOf("template_vars" to mapOf("user_name" to "Alice"))
 * )
 * // system prompt: "Hola {{ user_name }},hoy es {{ date }}"
 * // → "Hola Alice,hoy es 2026-07-04"
 * ```
 *
 * Phase 9.2 (M22) Mejora:
 *  - La versión anterior usa reglas regulares. [varRegex] Sólo admite `{{var}}` Reemplazo sencillo
 *  - Utilice la nueva versión en su lugar [PebbleTemplateEngine],Condiciones de soporte/bucle/filtrar
 *  - compatible con versiones anteriores:Contiene sólo `{{var}}` La antigua plantilla de todavía funciona bien.
 *  - Errores:Volver al texto original cuando se produce un error de sintaxis de la plantilla(No lanzar excepción,Evita bloquear conversaciones)
 *
 * v1.97 Mejora:
 *  - Se agregaron 8 nuevas variables integradas.(locale/timezone/device_info/battery_level/user/user_name/nickname/char)
 *  - Variables a nivel del sistema(locale/timezone/device_info/battery_level)Transformador interior calculado,No es necesario que la persona que llama pase
 *  - variables a nivel de usuario(user/user_name/nickname/char)leer de extras,Inyectado por ChatViewModel
 */
class TemplateTransformer(
    /** v1.97: Se utiliza para leer el nivel de la batería.(Disponible,No es necesario aprobarlo durante las pruebas unitarias.). */
    private val appContext: Context? = null,
) : Transformer {

    override val name: String = "Template"

    /** Phase 9.2: Pebble Compatible con motores de plantillas(singleton,apátrida). */
    private val engine = PebbleTemplateEngine()

    override suspend fun transform(messages: List<UIMessage>, context: TransformContext): List<UIMessage> {
        // Solo maneja mensajes del SISTEMA(Otros mensajes de roles no serán reemplazados por plantillas.,Evite cambiar el texto original del usuario)
        // L-TPL3: Combinado en un solo recorrido(El código original se recorre dos veces.)
        val hasTemplate = messages.any {
            it.role == MessageRole.SYSTEM && (it.content.contains("{{") || it.content.contains("{%"))
        }
        if (!hasTemplate) return messages

        // Recopilar mapeo de variables(pasar a PebbleTemplateEngine)
        val vars: Map<String, Any?> = buildMap {
            // variables incorporadas — tiempo
            val now = java.time.LocalDateTime.now()
            put("date", now.toLocalDate().toString())
            put("time", now.toLocalTime().toString().take(8))
            put("datetime", now.toString().take(19).replace('T', ' '))
            context.sessionId?.let { put("session_id", it) }
            context.modelId?.let { put("model_id", it) }

            // v1.97: variables incorporadas — Información del sistema(No es necesario que la persona que llama pase)
            put("locale", Locale.getDefault().toLanguageTag())
            put("timezone", TimeZone.getDefault().id)
            put("device_info", "${Build.MODEL} (${Build.MANUFACTURER})")
            put("battery_level", readBatteryLevel())

            // v1.97: variables a nivel de usuario — leer de extras(ChatViewModel inyectar)
            // v1.97 Reparación:Originales {{userName}} Los marcadores de posición no se pueden reemplazar,Porque TemplateTransformer solo registra el alias Snake_case.
            //           Ahora agregue el alias camelCase userName / assistantName,Equivalente a Snake_case,Compatible con los hábitos de escritura de los usuarios.
            // v1.97 Ten todo en cuenta:user_nickname Dar valor predeterminado cuando esté vacío"tu",evitar {{user_name}} El literal permanece en aviso.
            // v1.109 Reparación: assistant_name Dar valor predeterminado cuando esté vacío"Muse"(Nombre de marca),evitar {{char}} Representado como una cadena vacía.
            val userNickname = (context.extra("user_nickname") as? String) ?: "tu"
            put("user", userNickname)
            put("user_name", userNickname)
            put("nickname", userNickname)
            put("userName", userNickname) // v1.97: camelCase Alias
            val assistantName = (context.extra("assistant_name") as? String) ?: "Muse"
            put("assistant_name", assistantName)
            put("char", assistantName)
            put("character_name", assistantName)
            put("assistantName", assistantName) // v1.97: camelCase Alias

            // Variables definidas por el usuario(template_vars si Map<String, Any?>)
            (context.extra("template_vars") as? Map<*, *>)?.forEach { (k, v) ->
                if (k is String) put(k, v)
            }
        }
        // L-TPL4: vars contiene al menos date/time/datetime(nunca vacío),Originales if (vars.isEmpty()) return messages El código de muerte ha sido eliminado.
        // M-TPL5: Utilice un bucle for en su lugar messages.map,Para llamar a suspender withContext dentro del cuerpo del bucle.
        // PebbleTemplateEngine.render Es una llamada de bloqueo sincrónica.,Plantilla compleja(Contiene bucle for/anidado if)Puede llevar mucho tiempo,
        // usar withContext(Dispatchers.Default) Ajuste para evitar bloquear la corrutina actual.(Como la rutina IO enviada por el hilo principal.).
        val result = ArrayList<UIMessage>(messages.size)
        for (msg in messages) {
            if (msg.role != MessageRole.SYSTEM) {
                result.add(msg)
                continue
            }
            if (!msg.content.contains("{{") && !msg.content.contains("{%")) {
                result.add(msg)
                continue
            }

            // Errores:Volver al texto original cuando se produce un error de sintaxis de la plantilla,No bloquees conversaciones
            val rendered = withContext(Dispatchers.Default) {
                runCatching { engine.render(msg.content, vars) }.getOrElse {
                    // Error de renderizado:Mantener el texto original.,Logger grabar(Evita bloquear conversaciones)
                    Logger.w("TemplateTransformer", "Error al renderizar la plantilla: ${it.message}")
                    msg.content
                }
            }
            result.add(msg.copy(content = rendered))
        }
        return result
    }

    /**
     * v1.97: Leer porcentaje de batería(0-100).
     *
     * Pase [BatteryManager] lectura del servicio del sistema,Retorno del fracaso "unknown".
     * appContext cuando esta vacio(Pruebas unitarias)también regresar "unknown".
     */
    private fun readBatteryLevel(): String {
        val ctx = appContext ?: return "unknown"
        return runCatching {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                ?: return "unknown"
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (level in 0..100) level.toString() else "unknown"
        }.getOrElse {
            Logger.w("TemplateTransformer", "No se pudo leer el nivel de la batería: ${it.message}")
            "unknown"
        }
    }
}
