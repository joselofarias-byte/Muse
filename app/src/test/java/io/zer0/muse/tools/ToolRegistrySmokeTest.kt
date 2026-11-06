package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.x: Prueba de humo de herramienta completa — Recorra todas las herramientas integradas registradas en ToolRegistry.
 *
 * diseño(Distinguir entre dos tipos de herramientas.,Resolviendo el bloqueo del entorno Robolectric):
 *
 * 1. herramientas de seguridad([SAFE_EXECUTE_TOOLS]):Kotlin puro/Computación/Sin dependencias de servicios del sistema Android,
 *    Ejecutar directamente en el hilo de prueba.,afirmar"No fallar + devolver no nulo". Esta es la verdadera línea de salud de la cadena de herramientas.
 *    - echo / calculator / Códec/Hash/Número aleatorio/Hora, etc.
 *
 * 2. Herramientas de dependencia del sistema(todo el resto):Confíe en servicios reales de Android(WebView Motor kernel/TTS /
 *    Programación de alarmas/SMS de teléfono/Proveedor de contenido/Red),Robolectric Sin capacidad real de la máquina,
 *    Si se ejecuta, se bloqueará sincrónicamente.(Causa fundamental del estancamiento histórico:withTimeoutOrNull No se puede interrumpir el hilo de bloqueo).
 *    Este tipo sólo verifica "existencia registrada + entrada accesible",La salud en tiempo de ejecución está cubierta por el humo real de la máquina.
 *
 * v1.0.72 historia de la restauración:
 *  - primera edición:Todas las herramientas executeFromJson → Herramientas del sistema bloqueadas,La prueba estuvo estancada durante 17 minutos.
 *  - segunda edición:withTimeoutOrNull Corrutina del paquete → No válido para bloqueo sincrónico,Todavía estancado.
 *  - tercera edición:hilo independiente + Future.get(tiempo de espera) → Robolectric El hilo principal está bloqueado por get,
 *    Dispatchers.Main está permanentemente en cola en la herramienta,Muchos tiempos de espera falsos.
 *  - cuarta edición(Esta edición):seguro/Agrupación de herramientas del sistema,El hilo principal de la herramienta de seguridad se ejecuta directamente(Rápido y estable),
 *    Las herramientas del sistema solo registran la verificación. La prueba de humo nunca se atasca,Las devoluciones pueden quedar expuestas al instante.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToolRegistrySmokeTest {

    private lateinit var registry: ToolRegistry

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        registry = ToolRegistry(context)
    }

    /**
     * Las herramientas de seguridad se ejecutan una por una.:No falla + devuelve un valor no nulo.
     * Tiempo de espera de rutina de 5 segundos para cada herramienta(Evite el bloqueo accidental de nuevas herramientas en el futuro).
     */
    @Test
    fun `safe tools execute without crash and return non-empty result`() = runBlocking {
        val failures = mutableListOf<String>()
        for (name in SAFE_EXECUTE_TOOLS) {
            val result = try {
                withTimeoutOrNull(SAFE_TOOL_TIMEOUT_MS) {
                    registry.executeFromJson(name, "{}")
                }
            } catch (e: Throwable) {
                failures += "$name Lanzar excepción: ${e.message}"
                continue
            }
            if (result == null) {
                failures += "$name Tiempo de espera de ejecución(bloqueando)"
            } else if (result.isBlank()) {
                failures += "$name Devolver cadena vacía"
            }
        }
        assertTrue(
            "La herramienta de seguridad no pasa la prueba de humo:\n${failures.joinToString("\n")}",
            failures.isEmpty(),
        )
    }

    /**
     * v2.6.1: Formulario de registro del conjunto visible del asistente——find_tools En consecuencia, el directorio de búsqueda se reduce.
     * No registrado (null) significa sin límite; filtrar por colección después del registro; volver a nulo después de borrar.
     */
    @Test
    fun `visible tools registry is session scoped and defaults to unrestricted`() {
        assertTrue("Debe ser nulo cuando no está registrado (sin límite)", registry.visibleTools("vs_session") == null)
        registry.setVisibleTools("vs_session", setOf("echo", "calculator"))
        assertTrue(
            "El registro solo debe contener herramientas declaradas.",
            registry.visibleTools("vs_session") == setOf("echo", "calculator"),
        )
        assertTrue("Otras sesiones no se ven afectadas.", registry.visibleTools("vs_other") == null)
        registry.clearVisibleTools("vs_session")
        assertTrue("Después de la limpieza, regrese a no registrado (ilimitado)", registry.visibleTools("vs_session") == null)
    }

    /**
     * Toda la herramienta integrada de integridad de la tabla estática + verificación de registro en tiempo de ejecución propia de ToolRegistry.
     *
     * B-32 Cambios clave:no más"Consulta mutua de tabla estática"(La implementación original registrada directamente toma la misma constante estática de BUILT_IN_TOOL_IDS).
     * dividir en dos capas:
     *  1. capa de tiempo de ejecución(ToolRegistry(context) mismo init{} Herramientas registradas)realmente debe aparecer listTools()
     *     —— Verifique que el enlace de autorregistro CodeExecutionTool/BrowserAutomationTool de ToolRegistry sea válido,
     *     Y el nombre/descripción de cada ToolDef en tiempo de ejecución no está vacío.
     *  2. capa estática:BUILT_IN_TOOL_IDS Todas las herramientas integradas declaradas deben agruparse en la tabla de agrupación SAFE/SYSTEM.,
     *     Evite que se pierdan agrupaciones al ingresar a tablas estáticas(La prueba original ha sido cubierta.,Reservar).
     *
     * Descripción:Cada registrador registra las herramientas integradas restantes a través del Registro de herramientas de inyección de Koin cuando se inicia la aplicación.
     * (ver ToolRegistrarBootstrapper),estructura desnuda ToolRegistry(context) no los activará,
     * Por lo tanto, no es posible realizar afirmaciones de inclusión en tiempo de ejecución para todos los ID estáticos.;El estado de su registro queda anulado por la ruta de inicio de la aplicación.
     */
    @Test
    fun `all built-in tools are registered and reachable`() = runBlocking {
        val staticIds = io.zer0.muse.tools.ToolRegistry.BUILT_IN_TOOL_IDS.toSet()
        assertTrue("La declaración de tabla estática debe tener al menos 20 herramientas integradas,real ${staticIds.size}", staticIds.size >= 20)

        // B-32: de la realidad ToolRegistry(context) leer tiempo de ejecución listTools()(Devolver el registrado real ToolDef)
        val runtimeDefs = registry.listTools()
        assertTrue("No hay herramientas registradas en tiempo de ejecución.", runtimeDefs.isNotEmpty())
        val runtimeNames = runtimeDefs.map { it.name }.toSet()

        // Integridad de la definición del tiempo de ejecución:Cada ToolDef debe tener un nombre/descripción que no esté vacío(definir la existencia)
        val malformed = runtimeDefs.filter { it.name.isBlank() || it.description.isBlank() }
            .map { "name='${it.name}' desc='${it.description}'" }
        assertTrue("Hay una definición de herramienta con nombre/descripción vacíos en tiempo de ejecución.:\n$malformed", malformed.isEmpty())

        // ToolRegistry mismo init{} Las herramientas registradas deben aparecer en tiempo de ejecución. listTools()(No autocertificado)
        val selfRegistered = SELF_REGISTERED_IN_TOOL_REGISTRY
        val missingFromRuntime = selfRegistered.filterNot { it in runtimeNames }
        assertTrue(
            "ToolRegistry Debe registrar las siguientes herramientas usted mismo, pero listTools() No devuelto(init deriva lógica):\n$missingFromRuntime",
            missingFromRuntime.isEmpty(),
        )

        // capa estática:Herramientas de seguridad + Herramientas del sistema = Todas las herramientas estáticas integradas,Evite que se pierdan agrupaciones al ingresar a tablas estáticas
        val all = SAFE_EXECUTE_TOOLS + SYSTEM_ONLY_TOOLS
        val unregistered = all.filterNot { it in staticIds }
        assertTrue("Las herramientas no integradas aparecen en la tabla de agrupación:\n$unregistered", unregistered.isEmpty())

        // Evitar grupos faltantes:BUILT_IN_TOOL_IDS ni en el grupo de seguridad ni en el del sistema → La tabla de agrupación expiró,Se debe informar un error.
        val missingFromGroups = staticIds.filterNot { it in all }
        assertTrue(
            "Las siguientes herramientas no están en la tabla agrupada.(por favor actualiza SAFE_EXECUTE_TOOLS / SYSTEM_ONLY_TOOLS):\n$missingFromGroups",
            missingFromGroups.isEmpty(),
        )
        // B-32: Registro adicional en tiempo de ejecución(no en tabla estática)Sólo un recordatorio,No se considera un fracaso(Registrar/MCP Se puede registrar dinámicamente).
        val runtimeExtras = runtimeNames - staticIds
        if (runtimeExtras.isNotEmpty()) {
            println("[info] Herramientas de registro adicionales en tiempo de ejecución(Dinámico/Registrar/MCP): $runtimeExtras")
        }
    }

    /**
     * B-32: Barandillas de coherencia de herramientas de habilidades integradas — "La definición existe + ejecutar es enrutable"Cobertura de humo.
     *
     * atravesar [SkillExecutor.BUILT_IN_SKILLS]:
     *  1. definir la existencia:Cada habilidad incorporada debe ser una definición válida(id/name/description No vacío,parametersJson ser legal JSON).
     *  2. execute enrutable:implementationKotlin Debe acceder a la rama exclusiva when de SkillExecutor.execute
     *     (Eso es ∈ [SkillExecutor.ROUTABLE_SKILL_IMPL]),De lo contrario, Skill_unknown_impl se eliminará silenciosamente.
     *
     * Excluir los que no están conectados generate_qr(B-07),evitar"Se puede enrutar el conjunto completo de habilidades integradas"Supuesta lesión accidental.
     */
    @Test
    fun `all built-in skill tools are defined and routable`() {
        val skills = io.zer0.muse.tools.SkillExecutor.BUILT_IN_SKILLS
        // B-07: generate_qr La habilidad incorporada está fuera de línea.,de"Conjunto completo ruteable"excluir de la afirmación(dispatch Las sucursales siguen siendo compatibles con datos antiguos)
        val decommissioned = setOf("generate_qr")
        val definitionsBroken = mutableListOf<String>()
        val notRoutable = mutableListOf<String>()

        for (skill in skills) {
            if (skill.id in decommissioned) continue
            // 1. definir la existencia — válido SkillEntity
            if (skill.id.isBlank() || skill.name.isBlank() || skill.description.isBlank() ||
                !isValidJsonObject(skill.parametersJson)
            ) {
                definitionsBroken += "${skill.id}(name='${skill.name}', desc=${skill.description.length}, params=${skill.parametersJson})"
                continue
            }
            // 2. execute enrutable — implementationKotlin Presione la rama exclusiva de ejecutar cuando
            if (skill.implementationKotlin !in SkillExecutor.ROUTABLE_SKILL_IMPL) {
                notRoutable += "${skill.id} → implementationKotlin='${skill.implementationKotlin}'"
            }
        }

        assertTrue(
            "Falta la siguiente definición de habilidad integrada/dañado(por favor revisa SkillExecutor.BUILT_IN_SKILLS):\n${definitionsBroken.joinToString("\n")}",
            definitionsBroken.isEmpty(),
        )
        assertTrue(
            "SkillExecutor.execute no puede enrutar las siguientes habilidades integradas(implementationKotlin aquí no ROUTABLE_SKILL_IMPL," +
                "caminará en silencio skill_unknown_impl):\n${notRoutable.joinToString("\n")}",
            notRoutable.isEmpty(),
        )
    }

    /** B-32: Determine whether the string is a legal JSON object(Se utiliza para verificar que existe la definición de habilidad.). */
    private fun isValidJsonObject(json: String): Boolean {
        return runCatching {
            io.zer0.common.AppJson.decodeFromString(
                kotlinx.serialization.json.JsonObject.serializer(),
                json,
            )
        }.isSuccess
    }

    /**
     * Las herramientas principales deben estar registradas.(protección de devolución: Immediately exposed when the registry is accidentally deleted).
     */
    @Test
    fun `registry contains all documented built-in tool ids`() {
        val names = io.zer0.muse.tools.ToolRegistry.BUILT_IN_TOOL_IDS.toSet()
        val required = listOf(
            "get_current_time",
            "calculator",
            "echo",
            "get_weather",
            "browser_navigate",
            "browser_extract",
            "schedule_reminder",
            "translate",
        )
        val missing = required.filterNot { it in names }
        assertTrue("Faltan herramientas básicas: $missing", missing.isEmpty())
    }

    companion object {
        /** Tiempo de espera de la herramienta de hoja de herramientas de seguridad(milisegundos). */
        private const val SAFE_TOOL_TIMEOUT_MS = 5_000L

        /**
         * B-32: ToolRegistry mismo init{} Herramientas integradas registradas(Correspondencia uno a uno con ToolRegistry.init).
         *
         * CodeExecutionTool(execute_javascript)+ BrowserAutomationTool(browser_* 6 conjunto de piezas).
         * The rest of the built-in tools are injected and registered by each Registrar via Koin when the App starts.(Vea los comentarios de la prueba en la parte superior de esta clase.).
         * Esta colección se utiliza para"de la realidad ToolRegistry(context).listTools() Leer el registro de tiempo de ejecución"Verificación mutua.
         */
        val SELF_REGISTERED_IN_TOOL_REGISTRY: List<String> = listOf(
            "execute_javascript",
            "execute_node_script",
            "browser_navigate",
            "browser_click",
            "browser_type",
            "browser_extract",
            "browser_scroll_bottom",
            "browser_get_html",
            "browser_snapshot",
        )

        /**
         * herramientas de seguridad:cálculo puro/Sin servicio del sistema,Robolectric Realmente implementable.
         * Agregue esta tabla cuando agregue herramientas de lógica pura(y eliminado en SYSTEM_ONLY_TOOLS).
         */
        val SAFE_EXECUTE_TOOLS: List<String> = listOf(
            // cálculo puro
            "echo", "calculator",
            // Códec/picadillo
            "url_encode", "url_decode", "base64_encode", "base64_decode",
            "hash_text", "generate_uuid", "random_number", "generate_password",
            "json_pretty",
            // tiempo(puro LocalDateTime)
            "get_current_time",
        )

        /**
         * Herramientas de dependencia del sistema:Robolectric No ejecutado la próxima vez(La ejecución bloqueará/ninguna habilidad real).
         * Real machine return covered by instrumentation smoke.
         * Agregue esta tabla cuando agregue nuevas herramientas del sistema(Mantener unión con SAFE_EXECUTE_TOOLS = BUILT_IN_TOOL_IDS).
         */
        val SYSTEM_ONLY_TOOLS: List<String> = listOf(
            // red
            "get_weather", "get_network_info", "get_public_ip", "ping_host", "dns_lookup",
            // portapapeles/Calendario/despertador
            "clipboard_read", "clipboard_write", "screen_time", "calendar_today",
            "add_calendar_event", "set_alarm", "set_timer", "schedule_reminder",
            "cancel_reminder", "list_reminders",
            // sistema/Equipo
            "open_app", "share_text", "get_location", "get_device_info",
            "get_contacts_count", "get_contacts_list", "send_sms", "add_contact",
            "open_system_setting", "toggle_wifi", "toggle_bluetooth", "send_email",
            "get_battery_info", "get_recent_notifications", "open_url",
            "list_installed_apps",
            // v1.136 sistema/Equipo/codificación
            "get_storage_info", "get_memory_info", "get_display_info", "get_cpu_info",
            "get_sensors_list", "get_brightness", "set_brightness", "get_volume",
            "set_volume", "toggle_flashlight", "vibrate", "get_foreground_app",
            "get_wifi_info", "get_bluetooth_devices", "make_phone_call", "open_maps",
            // Biblioteca de recursos/registro rápido(depender de Room DB,Robolectric Requiere configuración adicional,pertenece al grupo del sistema)
            "resource_add", "resource_list", "resource_search", "resource_get",
            "resource_delete", "quick_note_add", "quick_note_list",
            "quick_note_get", "quick_note_update", "quick_note_delete", "quick_note_pin",
            // TTS
            "speak_text",
            // generación de medios(Depender de la red + proveedores)
            "generate_image", "generate_video", "generate_qr_code",
            // v2.x: Enviar mensaje de voz(Depende del motor TTS + sistema de archivos,Robolectric No ejecutado la próxima vez)
            "send_voice",
            // Emoticones(depender de SkillExecutor)
            "list_stickers", "send_sticker",
            // memoria/experiencia(depender de DB + LLM)
            "pin_memory", "unpin_memory", "recall_experience", "record_experience",
            // Parámetros del sistema de memoria(depender de DataStore)
            "memory_config_get", "memory_config_set",
            // Estadísticas de uso(Depende de la consulta de agregación de habitaciones,Robolectric No hay datos reales a continuación)
            "get_token_usage",
            // Búsqueda de contenido del espacio de trabajo(Depende del sistema de archivos)
            "workspace_grep",
            // v2.6.11: Se agregaron dos nuevas herramientas de espacio de trabajo a la transformación de montaje.(Depende del sistema de archivos + SAF)
            "workspace_copy", "workspace_mounts",
            // v2.6.7 fix (AUDIT F4): Las dos nuevas herramientas integradas en este lote deben agruparse.
            // De lo contrario, BUILT_IN_TOOL_IDS y la unión de la tabla de agrupación ya no son iguales.
            // read_user_file Confíe en las devoluciones de llamada de SAF + UI;remember_preference Depende del almacén de datos.
            "read_user_file", "remember_preference",
            "todo_write", "show_card", "render_data", "notify", "current_status", "subagent_task",
            "update_card_data",
            // JS caja de arena(WebView)
            "execute_javascript",
            // P1-C: Node guión(Tiempo de ejecución incorporado,Robolectric No ejecutado la próxima vez)
            "execute_node_script",
            // Navegador(headless WebView)
            "browser_navigate", "browser_click", "browser_type", "browser_extract",
            "browser_scroll_bottom", "browser_get_html", "browser_snapshot",
            // canal de mensajes(dependencia de la red)
            "send_channel_message", "channel_list",
            // OAuth conector(dependencia de la red)
            "connector_list", "call_connector",
            // archivo de espacio de trabajo(Depende del sistema de archivos)
            "workspace_list", "workspace_read", "workspace_write", "workspace_edit", "workspace_delete",
            "workspace_mkdir", "workspace_move",
            // tareas programadas(depender de DB)
            "scheduled_task_create", "scheduled_task_list", "scheduled_task_update",
            "scheduled_task_delete", "scheduled_task_execute", "scheduled_task_get_history",
            // Traducción(Depende de la red + LLM)
            "translate",
            // MCP gestión(McpRegistry Registrarse,Depende del estado de la red y de la conexión;Robolectric No ejecutado la próxima vez)
            "mcp_mgmt_list", "mcp_mgmt_configure", "mcp_mgmt_remove",
            "mcp_mgmt_bind_assistant", "mcp_mgmt_reconnect",
            // Archivo/enlace/Documentación(Depende del sistema de archivos y la red.)
            "read_file", "create_download", "parse_link", "parse_pdf",
            // v2.0: Root-level system tools
            "settings_get", "settings_put", "am_start", "list_packages", "logcat_tail", "input_inject",
            // Deseo de recuperación de memoria/subagente/mensaje activo
            "search_memory", "search_conversation", "subagent_run", "subagent_close", "proactive_message_wish",
            // v2.x: escritura de memoria/Eliminar + búsqueda de biblioteca de herramientas + mercado de complementos(Depende de la base de datos/registro/red)
            "save_memory", "delete_memory", "find_tools",
            "plugin_market_search", "plugin_market_install",
            "plugin_market_uninstall", "plugin_market_set_enabled",
            // v2.x Automatización Fase I:Canal de línea de comando del dispositivo(Ejecución jerárquica,Rechazar sin autorización)
            "device_shell",
            // v2.x fase terminal uno:Aplicar comandos de terminal sandbox
            "terminal_exec",
            // v2.x Tiempo de ejecución extendido:Autoprueba integrada de la zona de pruebas de Node(solo lectura;Depende del tiempo de ejecución,Robolectric No ejecutado la próxima vez)
            "runtime_selfcheck",
            // v2.7 Tiempo de ejecución extendido:Python Ejecución de script(Confíe en el tiempo de ejecución integrado,Robolectric No ejecutado la próxima vez)
            "execute_python_script",
            // v2.2.1: Termux canal(Comandos completos del entorno Linux;Registro de tiempo de ejecución,Robolectric No ejecutado la próxima vez)
            "termux_exec",
            // v2.2.1: GUI Agent anillo(Operaciones de varios pasos impulsadas por la visión;Registrado por el inicializador en tiempo de ejecución,Robolectric No ejecutado la próxima vez)
            "ui_agent",
            // v2.6.2: Conjunto de herramientas de control de aplicaciones(AppControlTools Registrarse;Depende del contexto de la aplicación de Android,Robolectric No ejecutado la próxima vez)
            "app_list", "app_launch", "app_settings",
            "app_force_stop", "app_clear_data", "app_uninstall",
            // v2.6.2: Gestión de base de conocimientos + gestión de sesiones (KnowledgeManageTools / SessionManageTools)
            "knowledge_list_docs", "knowledge_delete_doc", "knowledge_create_kb", "knowledge_delete_kb",
            "knowledge_read_doc",
            "session_list", "session_read", "session_rename", "session_move_folder", "session_archive", "session_delete",
            // v2.6.2: Gestión de habilidades + autogestión asistente (SkillManageTools / AssistantManageTools)
            "skill_manage_list", "skill_manage_toggle", "skill_manage_delete",
            "assistant_manage_list", "assistant_manage_create", "assistant_manage_update_persona", "assistant_manage_delete",
        )
    }
}
