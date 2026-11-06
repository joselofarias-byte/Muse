package io.zer0.muse.tools

import io.zer0.ai.core.ToolDefinition
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExposurePolicyTest {

    private fun tool(name: String) = ToolDefinition(
        name = name,
        description = name,
        parametersJsonSchema = """{"type":"object","properties":{}}""",
    )

    private val allTools = listOf(
        tool("calculator"),
        tool("echo"),
        tool("get_current_time"),
        tool("web_search"),
        tool("web_fetch"),
        tool("send_sms"),
        tool("delegate_agent"),
        tool("generate_image"),
    )

    @Test
    fun `simple tool request can disable repeated reasoning`() {
        assertTrue(ToolExposurePolicy.isSimpleToolRequest("Ayúdame a hacer los cálculos. 1+2"))
        assertTrue(ToolExposurePolicy.isSimpleToolRequest("Llama a echo para grabarlo."))
        assertFalse(ToolExposurePolicy.isSimpleToolRequest("Por favor analice por qué falló este plan."))
    }

    @Test
    fun `explicit no-tool request disables provider tool calls`() {
        assertTrue(ToolExposurePolicy.shouldDisableTools("Enumere todas las herramientas, no llame a ninguna"))
        assertTrue(ToolExposurePolicy.shouldDisableTools("list all tools, do not call any tools"))
        assertFalse(ToolExposurePolicy.shouldRequireTool("Enumere todas las herramientas, no llame a ninguna", allTools))
    }

    @Test
    fun `explicit action requires tool choice`() {
        assertTrue(
            ToolExposurePolicy.shouldRequireTool(
                "Ayúdame a buscar las noticias de hoy.",
                allTools,
            ),
        )
        assertTrue(
            ToolExposurePolicy.shouldRequireTool(
                "echo hello",
                allTools,
            ),
        )
        assertFalse(
            ToolExposurePolicy.shouldRequireTool(
                "Estoy de buen humor hoy",
                allTools,
            ),
        )
    }

    @Test
    fun `MCP action can require a tool without explicitly saying MCP`() {
        assertTrue(
            ToolExposurePolicy.shouldRequireTool(
                "ayudame a crear uno issue",
                allTools + tool("mcp_github__create_issue"),
            ),
        )
        assertTrue(
            ToolExposurePolicy.shouldRequireTool(
                "Ayúdame a encontrar esto issue",
                allTools + tool("mcp_github__search_issues"),
            ),
        )
    }

    /** Construir un"Muchas herramientas"lista de,Anula el umbral de activación para la lógica de restricción. */
    private val manyTools = allTools + listOf(
        tool("set_alarm"), tool("toggle_flashlight"), tool("quick_note_add"), tool("translate"),
        tool("get_weather"), tool("open_app"), tool("clipboard_read"), tool("search_memory"),
        tool("generate_video"), tool("parse_pdf"), tool("ping_host"), tool("notify"),
        tool("show_card"), tool("todo_write"), tool("share_text"), tool("set_timer"),
        tool("list_reminders"), tool("base64_encode"), tool("mcp_github__create_issue"), tool("mcp_files__list"),
        tool("workspace_write"), tool("browser_navigate"), tool("plugin_market_install"),
        tool("session_list"), tool("session_read"), tool("session_delete"),
    )

    @Test
    fun `session keywords expose the session family`() {
        // v2.6.2(comentarios de los usuarios): El asistente hojea otras conversaciones —— “sesión”La palabra clave class debería mostrar session_read.
        val filtered = ToolExposurePolicy.filterToolsForRequest("Ayúdame a ver lo que se discutió en conversaciones anteriores.", manyTools)
        val names = filtered.map { it.name }.toSet()
        assertTrue("session_read Debe exponerse con la familia de sesión.", "session_read" in names)
        assertTrue("session_list Debe exponerse con la familia de sesión.", "session_list" in names)
        assertFalse("Las herramientas opcionales irrelevantes deben limitarse", "generate_video" in names)
    }

    @Test
    fun `require tool needs a matching exposed tool`() {
        // v2.6.2(Auditoría de herramientas): Cuando se accede a la palabra clave pero no hay una herramienta de coincidencia, no se fuerza la opción requerida.
        assertTrue("Solicite herramientas cuando haya herramientas compatibles disponibles", ToolExposurePolicy.shouldRequireTool("Ayúdame a buscar las noticias de hoy.", listOf(tool("web_search"))))
        assertFalse("No se debe forzar cuando no existe una herramienta coincidente. required", ToolExposurePolicy.shouldRequireTool("Ayúdame a buscar las noticias de hoy.", listOf(tool("calculator"))))
    }

    @Test
    fun `simple matched request keeps only family core standard and mcp`() {
        val filtered = ToolExposurePolicy.filterToolsForRequest("Pon una alarma para mañana a las ocho en punto", manyTools)
        val names = filtered.map { it.name }.toSet()
        assertTrue("Las herramientas de la familia Hit deberían permanecer", "set_alarm" in names)
        assertTrue("Las herramientas básicas deben permanecer", "calculator" in names)
        assertTrue("Las herramientas normalmente abiertas deben conservarse", "web_search" in names)
        assertTrue("MCP Se deben conservar las herramientas.", "mcp_github__create_issue" in names)
        assertFalse("Las herramientas opcionales irrelevantes deben limitarse", "generate_image" in names)
        assertFalse("Se deben reducir las herramientas globales irrelevantes", "workspace_write" in names)
    }

    @Test
    fun `unmatched simple request narrows to core and standard`() {
        val filtered = ToolExposurePolicy.filterToolsForRequest("hola,Estoy de buen humor hoy", manyTools)
        val names = filtered.map { it.name }.toSet()
        assertTrue("Las herramientas normalmente abiertas deben conservarse", "web_search" in names)
        assertFalse("Las herramientas opcionales irrelevantes deben limitarse", "echo" in names)
        assertFalse("Se deben reducir las herramientas globales irrelevantes", "browser_navigate" in names)
    }

    @Test
    fun `complex request without action keywords also narrows`() {
        val filtered = ToolExposurePolicy.filterToolsForRequest("Por favor analice por qué falló este plan.", manyTools)
        assertTrue(filtered.size < manyTools.size)
        assertFalse(filtered.map { it.name }.contains("echo"))
    }

    @Test
    fun `complex direct request relaxes optional but not global`() {
        val filtered = ToolExposurePolicy.filterToolsForRequest(
            "Hazlo por mí: analiza estos datos,Luego organízalo en notas y envíalas al equipo.",
            manyTools,
        )
        val names = filtered.map { it.name }.toSet()
        assertTrue("Las solicitudes de acción explícitas deberían liberar herramientas opcionales", "echo" in names)
        assertFalse("Las herramientas globales todavía necesitan ser atacadas o autorizadas", "workspace_write" in names)
    }

    @Test
    fun `global tools need family hit sticky or authorization`() {
        val hit = ToolExposurePolicy.filterToolsForRequest("Ayúdame a cambiar el código en el espacio de trabajo.", manyTools)
        assertTrue("Los éxitos familiares deberían liberar herramientas en el espacio de trabajo", "workspace_write" in hit.map { it.name })
        val sticky = ToolExposurePolicy.filterToolsForRequest(
            "continuar",
            manyTools,
            stickyToolNames = setOf("browser_navigate"),
        )
        assertTrue("La permanencia de la sesión debe preservar las herramientas usadas", "browser_navigate" in sticky.map { it.name })
        val authorized = ToolExposurePolicy.filterToolsForRequest(
            "continuar",
            manyTools,
            authorizedToolNames = setOf("plugin_market_install"),
        )
        assertTrue("La autorización de sesión debe conservarse en las herramientas.", "plugin_market_install" in authorized.map { it.name })
        val loaded = ToolExposurePolicy.filterToolsForRequest(
            "continuar",
            manyTools,
            loadedToolNames = setOf("workspace_write"),
        )
        assertTrue("find_tools Las herramientas cargadas deben permanecer visibles", "workspace_write" in loaded.map { it.name })
    }

    @Test
    fun `small tool face is untouched by tiering`() {
        val small = allTools.take(8)
        val filtered = ToolExposurePolicy.filterToolsForRequest("Hola", small)
        assertTrue(filtered.size == small.size)
    }

    @Test
    fun `greeting is not a direct tool request`() {
        // v2.0 Regresar:Los saludos breves no deben utilizarse con intención instrumental.,De lo contrario, el pensamiento profundo se degradará por error y el proceso de pensamiento no será visible.
        assertFalse(ToolExposurePolicy.isDirectToolRequest("Hola"))
        assertFalse(ToolExposurePolicy.isDirectToolRequest("Hola", allTools))
        assertFalse(ToolExposurePolicy.isDirectToolRequest("Estoy de buen humor hoy", allTools))
    }

    @Test
    fun `explicit action is a direct tool request`() {
        assertTrue(ToolExposurePolicy.isDirectToolRequest("Ayúdame a buscar las noticias de hoy.", allTools))
        assertTrue(ToolExposurePolicy.isDirectToolRequest("Establece un recordatorio para mí: llámame mañana por la mañana a las ocho en punto", allTools))
        assertTrue(
            ToolExposurePolicy.isDirectToolRequest(
                "ayudame a crear uno issue",
                allTools + tool("mcp_github__create_issue"),
            ),
        )
        // Las palabras de operación de MCP de estilo pregunta no se cuentan como solicitudes de acción
        assertFalse(
            ToolExposurePolicy.isDirectToolRequest(
                "que es MCP",
                allTools + tool("mcp_github__create_issue"),
            ),
        )
    }

    @Test
    fun `python request exposes the builtin python script tool`() {
        // v2.6.1 Regresar: Las herramientas integradas de Python pertenecen a la categoría GLOBAL,Es necesario pulsar la familia de palabras clave para que emita un sonido.
        // Anteriormente, la familia de scripts solo enumeraba js/node,No incluido python → Los usuarios dicen"Ejecutar con Python"La herramienta no se envía en absoluto.,
        // El modelo solo puede ir al shell para encontrar al intérprete.(Ralentí repetido medido por los usuarios).
        val scriptTools = padding + listOf(
            tool("execute_javascript"), tool("execute_node_script"),
            tool("execute_python_script"), tool("execute_shell"),
            tool("calculator"),
        )
        val zhAsks = ToolExposurePolicy.filterToolsForRequest("Ayúdame a ejecutar un script en Python para procesar los datos.", scriptTools)
            .map { it.name }.toSet()
        assertTrue("Las solicitudes de Python chino deberían mostrar las herramientas integradas de Python", "execute_python_script" in zhAsks)
        assertTrue("También mantenga las herramientas de Node", "execute_node_script" in zhAsks)

        val enAsks = ToolExposurePolicy.filterToolsForRequest("run a python3 script to parse this csv", scriptTools)
            .map { it.name }.toSet()
        assertTrue("Las solicitudes de Python3 en inglés deberían mostrar las herramientas integradas de Python", "execute_python_script" in enAsks)
    }

    @Test
    fun `unrelated chat still narrows the python tool`() {
        // Regresión hacia atrás: python La familia no debe ampliar el área afectada.,Al chatear, todavía se reduce.
        val scriptTools = padding + listOf(
            tool("execute_python_script"), tool("execute_node_script"), tool("calculator"),
        )
        val idle = ToolExposurePolicy.filterToolsForRequest("hola,Estoy de buen humor hoy", scriptTools)
            .map { it.name }.toSet()
        assertFalse("Una pequeña charla no debería conducir a las herramientas de Python", "execute_python_script" in idle)
    }

    @Test
    fun `skill tools are narrowed by default and released when matched or loaded`() {
        // Regresar(Etapa de adelgazamiento de herramientas.4):Las habilidades no están clasificadas en ToolCategories,Antes de que se reduzca, se enviará completo con la solicitud.,
        // Es la mayor fuente de marcadores de posición de esquemas de herramientas. Ahora reducido en el mismo calibre que OPCIONAL.
        val withSkills = manyTools + listOf(
            tool("skill_weekly_report"),
            tool("skill_translate_doc"),
        )
        val skillNames = setOf("skill_weekly_report", "skill_translate_doc")

        // chat irrelevante:Se deben reducir las habilidades.
        val idle = ToolExposurePolicy.filterToolsForRequest(
            "hola",
            withSkills,
            skillToolNames = skillNames,
        ).map { it.name }.toSet()
        assertFalse("Las habilidades irrelevantes deben reducirse", "skill_weekly_report" in idle)
        assertFalse("Las habilidades irrelevantes deben reducirse", "skill_translate_doc" in idle)

        // find_tools Después de cargar:Las habilidades deben permanecer visibles.
        val loaded = ToolExposurePolicy.filterToolsForRequest(
            "continuar",
            withSkills,
            loadedToolNames = setOf("skill_weekly_report"),
            skillToolNames = skillNames,
        ).map { it.name }.toSet()
        assertTrue("Las habilidades cargadas deben ser visibles", "skill_weekly_report" in loaded)
        assertFalse("Las habilidades descargadas aún deberían reducirse", "skill_translate_doc" in loaded)

        // No transmitido skillToolNames(viejo comportamiento)tiempo,Las categorías no registradas siguen siendo tan visibles como son.
        val legacy = ToolExposurePolicy.filterToolsForRequest(
            "hola",
            withSkills,
        ).map { it.name }.toSet()
        assertTrue("Mantener la visibilidad existente cuando no se declara como una habilidad", "skill_weekly_report" in legacy)
    }

    @Test
    fun `La familia del espacio de trabajo puede aparecer cuando se golpea workspace_edit`() {
        // Si la herramienta existe pero no se puede enviar, significa que no existe: los nombres de herramientas de la familia del espacio de trabajo deben contener workspace_edit.
        // De lo contrario el usuario dice“Cambiar el código en el espacio de trabajo.”Esta herramienta no quedará expuesta cuandofind_tools No hay forma de hablar de eso.
        val workspaceTools = padding + listOf(
            tool("workspace_read"), tool("workspace_write"), tool("workspace_edit"), tool("calculator"),
        )

        val hit = ToolExposurePolicy.filterToolsForRequest("Ayúdame a cambiar el código en el espacio de trabajo.", workspaceTools)
            .map { it.name }
            .toSet()
        assertTrue("Los éxitos familiares del espacio de trabajo deberían sacarse a la luz workspace_edit", "workspace_edit" in hit)
        assertTrue("El comportamiento de otras herramientas de la misma familia se mantiene sin cambios.", "workspace_write" in hit)
    }

    /** Rellenar hasta reducir el umbral(20)arriba, de lo contrario la lógica jerárquica no tendrá efecto. */
    private val padding = (1..22).map { tool("pad_tool_$it") }

    @Test
    fun `device family narrowing splits cold params from hot toggles`() {
        // escenario4:Desglose de la familia de información del dispositivo — Los parámetros de dispositivos impopulares solo se publican según las coincidencias de palabras clave del dispositivo.
        val deviceTools = padding + listOf(
            tool("get_battery_info"), tool("get_memory_info"), tool("get_cpu_info"), tool("get_sensors_list"),
            tool("toggle_flashlight"), tool("vibrate"), tool("toggle_wifi"),
            tool("browser_navigate"), tool("calculator"),
        )
        val batteryOnly = ToolExposurePolicy.filterToolsForRequest("¿Cuánta potencia tiene el teléfono?", deviceTools)
            .map { it.name }.toSet()
        assertTrue("La familia de baterías debería plantear la consulta sobre la batería.", "get_battery_info" in batteryOnly)
        assertFalse("No se debe emitir una consulta de memoria cuando se omite la familia de parámetros del dispositivo", "get_memory_info" in batteryOnly)
        assertFalse("No se deben emitir consultas de sensores cuando no se alcanza la familia de parámetros del dispositivo.", "get_sensors_list" in batteryOnly)

        val cpuAsked = ToolExposurePolicy.filterToolsForRequest("Dime el modelo de CPU", deviceTools)
            .map { it.name }.toSet()
        assertTrue("La familia de parámetros del dispositivo afectado debería emitir una consulta de CPU", "get_cpu_info" in cpuAsked)
    }

    @Test
    fun `accessibility family splits read from action`() {
        // escenario4:Desglose de grupos de accesibilidad — Mirar la pantalla versus operar la pantalla ya no resalta mutuamente.
        val autoTools = padding + listOf(
            tool("screen_read"), tool("ui_click"), tool("screen_tap"),
            tool("automation_workflow"), tool("ui_agent"),
            tool("calculator"),
        )
        val looksAtScreen = ToolExposurePolicy.filterToolsForRequest("mira lo que hay en la pantalla", autoTools)
            .map { it.name }.toSet()
        assertTrue("Mirar la pantalla debería conducir a la lectura de la pantalla.", "screen_read" in looksAtScreen)
        assertFalse("Al mirar la pantalla no debería aparecer el tipo de clic.", "ui_click" in looksAtScreen)
        assertFalse("La visualización de la pantalla no debe conducir a GUI Agent", "ui_agent" in looksAtScreen)
    }
}
