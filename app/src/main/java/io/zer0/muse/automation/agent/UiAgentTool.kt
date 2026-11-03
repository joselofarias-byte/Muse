package io.zer0.muse.automation.agent

import io.zer0.muse.tools.ToolOutcome
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel

/**
 * v2.2.1: Registre el anillo del Agente GUI como `ui_agent` Herramientas(HIGH,Aprobación obligatoria).
 */
class UiAgentTool(private val runner: UiAgentRunner) {

    fun register(registry: ToolRegistry) {
        registry.registerOutcome(
            ToolRegistry.ToolDef(
                name = "ui_agent",
                description = "GUI Agent anillo:Mirando la pantalla con un modelo visual.,Utilice su teléfono paso a paso para completar tareas de varios pasos en todas las aplicaciones" +
                    "(Como por ejemplo\"Abre WeChat y envía un mensaje a Zhang San.\"). Ciclo automático interno:captura de pantalla→toma de decisiones visuales→Haga clic/Deslizar/entrada,El valor predeterminado es hasta 15 pasos." +
                    "display=auto(Predeterminado) Cuando existe nombre_paquete y hay disponible una pantalla virtual independiente, se da prioridad a la ejecución aislada.,De lo contrario, se rebajará a recepción por razones de accesibilidad.;" +
                    "display=foreground Forzar la pantalla de inicio actual;display=virtual Fuerza la pantalla independiente y requiere Shizuku/Root." +
                    "Requiere ayuda visual configurada(ajustes→ayuda visual). Cada paso interceptará la interfaz de ejecución actual y la enviará al modelo visual seleccionado para su análisis. La captura de pantalla puede contener información confidencial como chat o cuenta; solo será llamado dentro del alcance de las tareas explícitamente autorizadas por el usuario." +
                    "Úselo directamente para una operación de un solo paso screen_* Herramientas,Utilice esta herramienta para procesos de varios pasos.",
                parameters = mapOf(
                    "task" to "Requerido. Descripción de la tarea a realizar.,Sea lo más específico posible(Aplicación de destino, objeto, intención de operación)",
                    "max_steps" to "Opcional. Número máximo de pasos 3-30,Predeterminado 15",
                    "display" to "Opcional.auto(Predeterminado) Cuando hay un nombre de paquete de destino, se le da prioridad a la pantalla virtual independiente; de ​​lo contrario, al primer plano; También puede especificar primer plano o virtual",
                    "package_name" to "Nombre del paquete de la aplicación de destino opcional, como com.example.app;virtual Se requiere patrón,auto Una vez proporcionado el modo, primero intentará ejecutarse de forma aislada.",
                ),
                required = setOf("task"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val task = args["task"]?.trim().orEmpty()
            if (task.isBlank()) return@registerOutcome ToolOutcome.error("error:Parámetro de tarea faltante")
            val maxSteps = args["max_steps"]?.toIntOrNull() ?: UiAgentRunner.DEFAULT_MAX_STEPS
            val displayMode = when (args["display"]?.trim()?.lowercase()) {
                null, "", "auto" -> UiAgentRunner.DisplayMode.AUTO
                "foreground" -> UiAgentRunner.DisplayMode.FOREGROUND
                "virtual" -> UiAgentRunner.DisplayMode.VIRTUAL
                else -> return@registerOutcome ToolOutcome.error("error:display Solo admite auto,foreground o virtual")
            }
            val result = runner.run(task, maxSteps, displayMode = displayMode, packageName = args["package_name"])
            val content = buildString {
                append(if (result.finished) "Misión terminada:" else "Tarea no completada:")
                append('\n')
                append(result.summary)
            }
            if (result.finished) {
                ToolOutcome.ok(content, mapOf("finished" to true))
            } else {
                // A vision/screenshot/channel/max-step failure must not be reported to the
                // outer Agent loop as a successful tool invocation.
                ToolOutcome.error(content, mapOf("finished" to false))
            }
        }
    }
}
