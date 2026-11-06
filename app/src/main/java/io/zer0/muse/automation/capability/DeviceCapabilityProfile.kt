package io.zer0.muse.automation.capability

import android.os.Build
import io.zer0.muse.automation.core.PermissionLevel

/**
 * Perfil de capacidad del dispositivo —— La única fuente de verdad para "¿Qué puede hacer exactamente el agente en este teléfono?"
 *
 * ## por qué es necesario
 *
 * Canal de control de tres capas (sin barreras / Shizuku·adb / Root) cada uno solo cubre parte de las capacidades y depende en gran medida de los usuarios
 * Estado de la autorización. Anteriormente, los modelos sólo podían basarse en prueba y error: ajustar la herramienta una vez y luego cambiarla si fallaba, desperdiciando rondas y facilitando el proceso.
 * "Sin permiso"falso positivo"La función no existe".
 *
 * El archivo proporciona los canales disponibles + límites de capacidad clave al modelo a la vez, lo que le permite seleccionar soluciones factibles en el primer paso.
 *
 * ## Límites de capacidad (alinearse con la implementación, no cambiar según las impresiones)
 *
 * - **Accesibilidad**: Leer árbol de control + despachoInyección de gestos + `inputText`/`pressKey`.
 *   **no puedo**captura de pantalla,**no puedo**Interfaz del sistema operativo (barra de estado/barra de notificaciones/ventana de uid del sistema, como el menú de apagado),
 *   **no puedo**Instale aplicaciones o cambie la configuración del sistema.
 * - **Shizuku / adb shell**:`input`,`screencap`,`pm`/`am`,`settings`,`content` espera
 *   shell Dominio; Puede tomar capturas de pantalla, operar la interfaz del sistema operativo y puede instalarse de forma silenciosa.
 * - **Root**: Todo lo anterior + Leer y escribir directamente la partición del sistema/base de datos.
 * - **pantalla virtual**: inicie la aplicación en una pantalla separada sin interferir con el primer plano actual del usuario (preferido para la automatización de varios pasos).
 * - **Incorporado Node**:Procesamiento de datos/Procesamiento por lotes/guión/MCP stdio server, no consume tokens modelo.
 *
 * @property accessibility ¿Están conectados los servicios de accesibilidad?
 * @property shizuku ¿Se ha obtenido el nivel de caparazón (Shizuku/adb) autorización
 * @property root ¿Has obtenido su
 * @property virtualDisplay ¿Está disponible el servidor de pantalla virtual?
 * @property nodeRuntime ¿Está listo el tiempo de ejecución de Node integrado?
 * @property sandboxAvailable Reservado: si la ejecución de archivos sandbox en la aplicación está disponible
 * @property details orientado al registro/Detalles legibles de la página de configuración (sin ingresar directamente la palabra solicitada)
 */
data class DeviceCapabilityProfile(
    val accessibility: Boolean = false,
    val shizuku: Boolean = false,
    val root: Boolean = false,
    val virtualDisplay: Boolean = false,
    val nodeRuntime: Boolean = false,
    val sandboxAvailable: Boolean = false,
    val details: Map<String, String> = emptyMap(),
) {
    /** Nivel más alto disponible (sin ningún canal) [PermissionLevel.NONE]). */
    val highestLevel: PermissionLevel
        get() = when {
            root -> PermissionLevel.ROOT
            shizuku -> PermissionLevel.SHELL
            accessibility -> PermissionLevel.ACCESSIBILITY
            else -> PermissionLevel.NONE
        }

    /** ¿Hay alguna habilidad?"operar el teléfono celular"canal (Node/La pantalla virtual por sí sola no cuenta como canal de control). */
    val hasControlChannel: Boolean
        get() = accessibility || shizuku || root

    /** Android 14+ Los servicios de accesibilidad pueden llamar directamente a AccessibilityService.takeScreenshot. */
    val accessibilityScreenshot: Boolean
        get() = accessibility && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** ¿Puedo tomar una captura de pantalla (Shell/Root o Android 14+ accesible). */
    val canScreenshot: Boolean
        get() = shizuku || root || accessibilityScreenshot

    /**
     * Representado en un bloque de archivo compacto para que lo vea el modelo.
     *
     * Sea breve: cada línea en el mensaje del sistema le cuesta dinero al usuario, simplemente escriba"influir en las decisiones"hecho.
     */
    fun renderForModel(): String = buildString {
        appendLine("## Perfil de capacidad del dispositivo (disponibilidad real actual)")
        appendLine(
            "- Accesibilidad(leer pantalla/gesto/entrada): " +
                if (accessibility) "Disponible" else "No disponible (el servicio de accesibilidad de Muse no está activado)",
        )
        appendLine(
            "- Shell canal(Shizuku/adb: input/screencap/pm/am/settings): " +
                if (shizuku) "Disponible" else "No disponible (Shizuku no está instalado o no está autorizado)",
        )
        appendLine(
            "- Root(su: Lectura y escritura de partición del sistema/Control total): " +
                if (root) "Disponible" else "No disponible",
        )
        appendLine(
            "- Capacidad de captura de pantalla: " +
                if (canScreenshot) {
                    if (accessibilityScreenshot && !shizuku && !root) {
                        "Disponible (realizando capturas de pantalla accesibles para Android 14+)"
                    } else {
                        "Disponible (usando Shell/Root)"
                    }
                } else {
                    "No disponible (requiere accesibilidad a Android 14+,Shizuku/adb o raíz)"
                },
        )
        appendLine(
            "- pantalla virtual(Inicie la aplicación en una pantalla separada,No perturbar la interfaz actual del usuario.): " +
                if (virtualDisplay) "Disponible" else "No disponible",
        )
        appendLine(
            "- Tiempo de ejecución de nodo incorporado(Ejecutar script/Procesamiento de datos/MCP stdio): " +
                if (nodeRuntime) "Disponible" else "No disponible (no listo en tiempo de ejecución)",
        )
        append(
            "- Nivel más alto de autoridad actual: " + when (highestLevel) {
                PermissionLevel.ROOT -> "Root"
                PermissionLevel.SHELL -> "Shell(Shizuku/adb)"
                PermissionLevel.ACCESSIBILITY -> "Accesibilidad"
                PermissionLevel.NONE -> "Ninguno (solo puede realizar operaciones dentro de la aplicación, no puede controlar otras aplicaciones)"
            },
        )
    }

    /**
     * Procedimiento de decisión de operación de renderizado: poner"Detectar primero, luego seleccionar la capa, luego verificar"La práctica establecida está escrita en la palabra clave.
     *
     * Estas no son sugerencias sino restricciones estrictas (cada una de las cuales corresponde a un obstáculo real), por lo que se inyectan junto con el perfil de habilidades.
     */
    fun renderOperatingGuide(): String = buildString {
        appendLine("## Procedimientos de toma de decisiones para el funcionamiento de teléfonos celulares.")
        appendLine("1. Confirme sus habilidades antes de actuar: en la tabla anterior se enumeran**actual**Disponibilidad real; no vuelva a intentar el canal que está escrito como \"no disponible\" en la tabla. Cambie directamente el plan o explíquele al usuario qué permiso falta.")
        appendLine("2. Seleccione canales según el nivel más bajo disponible:")
        appendLine("   - Capa de aplicación (**No se requiere autorización automática**, prueba siempre esta capa primero):`app_list` Verifique el nombre y el nombre del paquete,`app_launch` Inicie la aplicación,`app_settings` Abra la página de configuración del sistema de una aplicación (permitiendo a los usuarios cambiar los permisos ellos mismos)/datos claros).")
        appendLine("   - Interacción de la interfaz: priorice la accesibilidad (árbol de control legible, gestos inyectables), más confiable que coordinar puntos ciegos.")
        appendLine("   - Necesita tomar capturas de pantalla, interfaz del sistema, instalar aplicaciones, cambiar la configuración del sistema o forzar la detención/Borrar datos/Al desinstalar, use Shell/Root (`app_force_stop`/`app_clear_data`/`app_uninstall`/`device_shell`).")
        appendLine("3. Cada paso debe ser verificado: vuelva a leer la pantalla después de emitir la acción./Los resultados de confirmación de estado (si el texto cambia, si la página cambia), no envían múltiples operaciones ciegas seguidas.")
        appendLine("4. Si la interfaz falla, primero mire la estructura y luego cambie el método: primero use el resultado de la lectura de la pantalla para ubicar el control (texto/Descripción/nivel); si no lo encuentra, considere hacer clic en las coordenadas; cuando las coordenadas sean inestables, utilice la solución de nivel de comando Shell/Root.")
        appendLine("5. Repetibilidad/Los procesos largos deben escribirse primero como scripts: use el Nodo integrado para completar el ciclo,JSON/El procesamiento regular y las solicitudes por lotes ahorran tiempo y tokens en comparación con el razonamiento paso a paso; Después de la estabilización, se convertirá en una habilidad.")
        appendLine(
            "6. No molestar al usuario: llamar `ui_agent` prioridad al usar display=auto y proporcione nombre_paquete;" +
                "Intentará crear esta pantalla virtual exclusiva,Shell/Root O bajar explícitamente al primer plano si la aplicación no lo admite." +
                "display=virtual Especifique únicamente si el archivo de capacidad muestra que la pantalla virtual está disponible; No asuma que la pantalla virtual compatible y la pantalla independiente del Agente son la misma pantalla.",
        )
        appendLine("7. Para acciones de alto riesgo (enviar mensajes, realizar pagos, eliminar, cambiar la configuración del sistema), indique lo que va a hacer primero y espere la confirmación del usuario antes de ejecutar.")
        appendLine("8. Si faltan permisos, diga la verdad: explique \"qué elemento se requiere\" (como Shizuku/Root/Accesibilidad), no utilice los resultados de la simulación para reemplazar la ejecución real y no mienta sobre el éxito.")
        appendLine(
            "9. Consulte el mapa antes de actuar: Para operar el equipo (haga clic en/Pantalla deslizante/leer pantalla/Iniciar y detener aplicaciones/shell), primero ajuste `tool_status`\n" +
                "   Vea cuáles realmente funcionan. Dividirá las herramientas en dos grupos: disponibles y bloqueadas debido a permisos, y le dirá qué falta.\n" +
                "   No aceptes \"déjame encenderlo por ti\" primero y luego golpear la pared —— Eso duele más que decir que no se puede hacer en primer lugar.",
        )
    }

    companion object {
        /**
         * Construido a partir de tres capas de señales autorizadas (entradas más utilizadas).
         *
         * Más parámetros que el umbral predeterminado de detekt (6) es deliberado: estas banderas son independientes entre sí y deben darse explícitamente,
         * Usar el valor predeterminado hará"Olvidé transferir una determinada capa"Conviértase en una declaración silenciosa y falsa de capacidad. La persona que llama siempre debe pasar los parámetros por nombre.
         */
        @Suppress("LongParameterList")
        fun fromFlags(
            accessibility: Boolean,
            shizuku: Boolean,
            root: Boolean,
            virtualDisplay: Boolean = false,
            nodeRuntime: Boolean = false,
            sandboxAvailable: Boolean = false,
            details: Map<String, String> = emptyMap(),
        ): DeviceCapabilityProfile = DeviceCapabilityProfile(
            accessibility = accessibility,
            shizuku = shizuku,
            root = root,
            virtualDisplay = virtualDisplay,
            nodeRuntime = nodeRuntime,
            sandboxAvailable = sandboxAvailable,
            details = details,
        )
    }
}
