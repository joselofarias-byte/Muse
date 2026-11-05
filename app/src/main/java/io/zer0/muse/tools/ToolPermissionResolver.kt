package io.zer0.muse.tools

/**
 * Analizador de permisos de herramientas.
 *
 * Tres factores integrales determinan si una llamada de herramienta requiere aprobación:
 *  1. de la sesión actual [SessionPermissionMode]
 *  2. herramienta en sí [ToolRiskLevel]
 *  3. Configurado individualmente por el usuario para esta herramienta [ToolApprovalPolicy]
 *
 * prioridad:Herramienta única ALWAYS_DENY / Parámetro Denegación total > sesión TRUSTED > sesión STRICT >
 * sola herramienta ALWAYS_ALLOW > Sesión ASK política de riesgo de impago.
 *
 * v1.0.52: ShellSandboxTool Preservar límites rígidos regulares e independientes([isUnsafeCommand]).
 * El modo de sesión CONFIANZA solo omite la aprobación paso a paso,No omite la propia zona de pruebas, la validación de parámetros ni los permisos de ejecutor de la herramienta.
 */
object ToolPermissionResolver {

    init {
        // v1.0.53: Registrar políticas de permisos parametrizadas integradas(open_url / execute_javascript)
        ParamPolicies.registerBuiltIn()
    }

    /**
     * v1.0.52: Lista negra de archivos ejecutables peligrosos(Línea regular de defensa de la frontera dura).
     *
     * execute_shell Siempre que estos nombres de archivos ejecutables aparezcan en la llamada,[isUnsafeCommand] Es decir, negativa a ejecutar.
     * Esta restricción del entorno limitado del shell de solo lectura es independiente del modo de aprobación seleccionado por el usuario.
     *
     * Base para la inclusión(Disciplina de acción + Mejores prácticas de seguridad):
     *  - Eliminación de archivos/destruir:rm, rmdir, shred, mkfs
     *  - Escalada de privilegios:sudo, su, doas
     *  - control de versiones roto:git(si push --force / reset --hard Destruir almacén)
     *  - Ejecución de descarga de red:curl, wget(Puede descargar y ejecutar scripts maliciosos)
     *  - Gestión de paquetes:apt, pip, npm, yarn, pnpm(Puede instalar paquetes maliciosos)
     *  - intérprete/shell:bash, sh, zsh, fish, dash(Puede ejecutar cualquier script)
     *  - Gestión de procesos:kill, killall, pkill(Los procesos clave se pueden terminar)
     *  - Cambios de permiso:chmod, chown, chgrp(Permisos de archivos destructibles)
     *  - montar/sistema:mount, umount, reboot, shutdown, poweroff
     *  - Depuración/inyectar:strace, ltrace, ptrace, gdb(Puede robar datos en tiempo de ejecución)
     */
    private val BLOCKED_EXECUTABLES: Set<String> = setOf(
        // Eliminación de archivos/destruir
        "rm", "rmdir", "shred", "mkfs",
        // Escalada de privilegios
        "sudo", "su", "doas",
        // control de versiones
        "git",
        // descarga de internet
        "curl", "wget",
        // Gestión de paquetes
        "apt", "apt-get", "pip", "pip3", "npm", "yarn", "pnpm", "gem", "cargo",
        // intérprete/shell
        "bash", "sh", "zsh", "fish", "dash", "python", "python3", "perl", "ruby", "node",
        // Gestión de procesos
        "kill", "killall", "pkill",
        // Cambios de permiso
        "chmod", "chown", "chgrp",
        // montar/sistema
        "mount", "umount", "reboot", "shutdown", "poweroff",
        // Depuración/inyectar
        "strace", "ltrace", "ptrace", "gdb",
        // Redirigir a ejecución
        "exec", "eval",
    )

    /**
     * v1.0.52: Regularización de sintaxis de shell insegura(Línea regular de defensa de la frontera dura).
     *
     * Detectar caracteres de sintaxis peligrosos en cadenas de comandos,Prevenir la inyección de comandos. con [ShellSandboxTool.FORBIDDEN_CHARS]
     * complementario(Esta última es una colección personaje por personaje.,Esta regla cubre más escenarios.):
     *  - `\r` `\n`:inyección de nueva línea(Inyecte el segundo comando después del primer comando)
     *  - `` ` ``:Reemplazo del comando de acento grave
     *  - `$`:expansión variable/sustitución de comando `$(...)`
     *  - `|`:tubo(Puede conectarse a comandos peligrosos como `| sh`)
     *  - `;`:separador de comandos
     *  - `&`:ejecución en segundo plano/separador de comandos `&&`
     *  - `<` `>`:Redirigir(Archivos que se pueden sobrescribir)
     *  - `*` `?`:comodín(Puede coincidir accidentalmente con una gran cantidad de archivos)
     *  - `{` `}`:expansión de corsé(Se pueden construir parámetros complejos.)
     *
     * Nota:Esta regla es consistente con [ShellSandboxTool.FORBIDDEN_CHARS] Hay superposición,esto es intencional——
     * Múltiples capas de defensa(defense in depth),Si alguna capa tiene un error, la otra capa aún puede bloquearlo.
     */
    private val UNSAFE_SHELL_SYNTAX: Regex = Regex("[\r\n`$|;&<>*?{}]")

    /**
     * v1.0.52: Línea regular de defensa de la frontera dura — Detecta si los comandos contienen archivos ejecutables peligrosos o sintaxis insegura.
     *
     * Como herramienta de concha(Como por ejemplo [ShellSandboxTool])primera línea de defensa,Ejecutado antes de la verificación de la lista blanca.
     * La persona que llama debe rechazar inmediatamente la ejecución cuando se devuelve verdadero,No ingresar permisos posteriores/Proceso de lista blanca.
     *
     * principios de diseño(Sigue la disciplina de la acción.):
     *  - La lista negra tiene prioridad sobre la lista blanca:Primero asegúrese de que no sea un comando peligroso conocido.,Comprueba nuevamente si está en la lista blanca.
     *  - TRUSTED El modo permite que las herramientas se ejecuten directamente,Pero ejecutar_shell todavía está protegido por este límite estricto.
     *  - con [ShellSandboxTool] lista blanca + [FORBIDDEN_CHARS] complementario,Crea múltiples capas de defensa
     *
     * v2.x: Paso 0: ejecutar primero [ShellCommandBoundary.forbidReason] primera marcha —— El "conjunto mínimo inevitable" de proyectiles de uso general.
     * Lo que complementa es el tipo de comando que no se puede ver según los dos criterios anteriores, pero el poder destructivo es suficiente para bloquearlo.(por ejemplo `dd of=/dev/block/sda`
     * No contiene caracteres de sintaxis prohibidos.,`dd` Tampoco está en la lista negra). Este paso sólo aumenta el rechazo.,No relajar los criterios existentes.,entonces
     * execute_shell El comportamiento existente permanece sin cambios. Herramientas terminales universales [TerminalExecTool] Utilice sólo la primera velocidad,No utilices esta función ——
     * De lo contrario, el trabajo normal de canalizaciones, redirecciones y variables de entorno se interrumpirá accidentalmente.
     *
     * @param command La cadena de comando a verificar
     * @return true Indica que el comando no es seguro,debe negarse;false significa pasar un límite duro,Puede ingresar a la verificación posterior
     */
    fun isUnsafeCommand(command: String): Boolean {
        if (command.isBlank()) return false
        // 0. Límite duro de caparazón universal primer nivel:Juicio basado en el verdadero poder destructivo,No mires la forma de la cuerda
        if (ShellCommandBoundary.forbidReason(command) != null) return true
        // 1. Comprobación de caracteres de sintaxis insegura(inyección de nueva línea/sustitución de comando/tubo/Redirigir/Comodines, etc.)
        if (UNSAFE_SHELL_SYNTAX.containsMatchIn(command)) return true
        // 2. Comprobación de archivos ejecutables peligrosos:Verifique el nombre del comando simple de cada token después de dividirlo por espacios en blanco
        //    Nombre del comando desnudo = Eliminar prefijo de ruta(/usr/bin/rm → rm)la ultima parte
        val tokens = command.trim().split(Regex("\\s+"))
        for (token in tokens) {
            // Tome el último párrafo como nombre del comando.(Manejar el formulario /ruta/a/cmd)
            val bareName = token.substringAfterLast('/').lowercase()
            if (bareName in BLOCKED_EXECUTABLES) return true
        }
        return false
    }

    /**
     * El estado de aprobación inicial de la llamada a la herramienta de análisis.
     *
     * @param toolName Nombre de la herramienta
     * @param risk Nivel de riesgo de la herramienta(UNKNOWN usado cuando [fallbackRiskFor] inferencia)
     * @param mode Modo de permiso de sesión actual
     * @param perToolPolicy La política de persistencia establecida por el usuario para esta herramienta.,no configurado para null
     * @param args v1.0.53: Parámetros de la herramienta,para estrategias parametrizadas([ParamPolicies])juicio
     */
    fun resolve(toolName: String, risk: ToolRiskLevel?, mode: SessionPermissionMode, perToolPolicy: ToolApprovalPolicy?, args: Map<String, Any?> = emptyMap()): ToolApprovalState {
        // Las herramientas dinámicas de terceros no aceptan el elemento "Escribir SAFE en el libro mayor":El valor predeterminado para el libro mayor de registro es SAFE,
        // Una vez que una herramienta de terceros no cumple con la declaración, se considerará una herramienta libre de revisión.,Por lo tanto, SEGURO siempre vuelve a NORMAL.
        // La declaración explícita de ALTO todavía tiene efecto —— Sólo aprieta aquí,Sin degradación.
        val effectiveRisk = conservativeRisk(toolName, risk ?: fallbackRiskFor(toolName))
        // 1. Cuando el usuario desactiva explícitamente una herramienta,Cualquier modo es rechazado.
        if (perToolPolicy == ToolApprovalPolicy.ALWAYS_DENY) {
            return ToolApprovalState.Denied("Herramientas $toolName Inhabilitado por el usuario")
        }

        // Los rechazos firmes a nivel de parámetro siempre surten efecto(Por ejemplo, file://URL/ es peligroso JS);TRUSTED Sólo omitir confirmación,
        // No omita el rechazo de parámetros explícitamente ilegales o peligrosos.
        val paramState = ParamPolicies.evaluate(toolName, args)
        if (paramState is ToolApprovalState.Denied) {
            return paramState
        }

        // v1.x: TRUSTED = Los usuarios seleccionan explícitamente todas las herramientas sin tener que confirmar una tras otra.
        // Deje intactos los argumentos explícitos de inhabilitación y negación tajante anteriores; Las propias comprobaciones de permisos y zona de pruebas del ejecutor no se ven afectadas.
        if (mode == SessionPermissionMode.TRUSTED) {
            return ToolApprovalState.Auto
        }

        // v1.0.53: estrategia paramétrica(Se utiliza cuando se devuelve un valor no nulo,Anular la determinación de riesgo estático)
        // P0-8: Corrección de prioridad — modo de sesión(STRICT)tener prioridad sobre"parametrización Auto":STRICT Estrategia de parámetros más bajos
        // sólo se puede apretar(Denied),no se puede relajar(Auto). De lo contrario, open_url http/https → Auto precederá
        // Retorno de juicio de modo,DESVIACIÓN ESTRICTA"Todos los enlaces externos serán aprobados.". El parámetro denegado todavía tiene prioridad(apretar).
        // Orden general de prioridad:ALWAYS_DENY > parametrización Denied > STRICT modo > parametrización Auto > Otros.
        if (paramState != null && !(mode == SessionPermissionMode.STRICT && paramState is ToolApprovalState.Auto)) {
            return paramState
        }

        // 2. modo estricto:Aprobación para todo excepto herramientas de seguridad.;Si aplicaciones externas están involucradas en herramientas de seguridad./La red también aprueba
        //    Nota:ALWAYS_ALLOW explícito tiene prioridad sobre la evaluación ESTRICTA a continuación(Las herramientas que el usuario publica explícitamente ya no preguntan)
        if (mode == SessionPermissionMode.STRICT && perToolPolicy != ToolApprovalPolicy.ALWAYS_ALLOW) {
            return if (effectiveRisk == ToolRiskLevel.SAFE && toolName in STRICT_SAFE_ALLOWLIST) {
                ToolApprovalState.Auto
            } else {
                ToolApprovalState.Pending
            }
        }

        // 3. Cuando el usuario permite explícitamente una herramienta,Lanzamiento directo en cualquier modo.
        //    v1.x Reparación:La implementación original del modo ASK/STRICT ignora ALWAYS_ALLOW,causa"Permitir siempre herramientas"
        //    comprobar/Después de presionar el botón, la tarjeta de aprobación seguirá apareciendo la próxima vez que la llame.(Los comentarios de los usuarios no tienen efecto).
        //    La estrategia explícita es la señal más fuerte,La prioridad es mayor que el modo de sesión.
        if (perToolPolicy == ToolApprovalPolicy.ALWAYS_ALLOW) {
            return ToolApprovalState.Auto
        }

        // 4. Política predeterminada(Por modo de sesión + risk)
        return when (mode) {
            SessionPermissionMode.TRUSTED -> ToolApprovalState.Auto
            SessionPermissionMode.ASK -> when (effectiveRisk) {
                ToolRiskLevel.SAFE -> ToolApprovalState.Auto
                ToolRiskLevel.NORMAL -> ToolApprovalState.Pending
                ToolRiskLevel.HIGH -> ToolApprovalState.Pending
            }
            SessionPermissionMode.STRICT -> ToolApprovalState.Pending // Lo anterior ha sido procesado.
        }
    }

    /**
     * P0-3: única fuente de riesgo — tabla explícita [EXPLICIT_RISK_OVERRIDES] + Entrada pública para la inferencia de prefijo de nombre.
     *
     * Filtrado de subagentes/tareas programadas sin enlaces de aprobación de sesión**debe**Utilice esta función para determinar el nivel de riesgo,
     * No se permite la comparación directa de los libros de registro.(ToolDef.riskLevel)valor —— Cuando el valor registrado es inconsistente con la tabla explícita
     * Basado en esta función,Evite la degradación del libro de registro provocando que la herramienta ALTA se ejecute sin aprobación.
     *
     * Herramientas dinámicas de terceros(complemento / MCP)No involucrado en la inferencia de nombres,directo NORMAL,ver [fallbackRiskFor].
     */
    fun riskLevelFor(toolName: String): ToolRiskLevel = fallbackRiskFor(toolName)

    /**
     * La forma del nombre de la herramienta de registro dinámico de terceros.
     *
     *  - Herramientas complementarias:`plugin_<len>_<pluginId>_<toolName>`(PluginManager.skillId)
     *  - MCP Herramientas:`mcp_<serverId>__<toolName>`(McpRegistry.registerTools)
     *
     * dos secciones**sección final**Todo recogido por un tercero.:`get_diagnostics` Este nombre que suena como "sólo lectura" no significa
     * Sin efectos secundarios,`send_` / `delete_` Este nombre que "suena peligroso" no significa que sea realmente peligroso. prefijado por el anfitrión
     * Fijo,Entonces el prefijo es solo**Etiqueta fuente**,No puede utilizarse como criterio de riesgo.
     *
     * incorporado `plugin_market_*` / `mcp_mgmt_*` Las herramientas de gestión no se ajustan a estas dos formas.(El segundo párrafo del primero no es
     * número de longitud,Este último no `__` separar),Seguir procediendo según sus respectivos niveles declarados.
     */
    private val THIRD_PARTY_TOOL_NAME: Regex = Regex("^plugin_[0-9]+_.+|^mcp_.+__.+")

    /** Si el nombre de la herramienta proviene del registro dinámico de terceros(complemento / MCP server). */
    internal fun isThirdPartyToolName(toolName: String): Boolean = THIRD_PARTY_TOOL_NAME.matches(toolName)

    /** El juicio SEGURO de las herramientas dinámicas de terceros siempre converge en NORMAL;Los niveles restantes se devuelven sin cambios.
     *
     * Demarcación de calibre (inconsistencia intencional,no omitido): incorporado `mcp_mgmt_*` No es una herramienta de terceros
     * ([THIRD_PARTY_TOOL_NAME] no coincide),Siguen utilizando el nivel declarado en la cuenta de registro;
     * extremo opuesto `mcp_<server>__<tool>` entonces NORMAL siempre es forzado. Misma razón `plugin_market_*` toma el libro mayor,
     * `plugin_<len>_<id>_<tool>` VAYA NORMAL.
     */
    private fun conservativeRisk(toolName: String, declared: ToolRiskLevel): ToolRiskLevel = if (declared == ToolRiskLevel.SAFE && isThirdPartyToolName(toolName)) ToolRiskLevel.NORMAL else declared

    /**
     * P0-3: Acceso de solo lectura a la tabla de riesgos explícitos(Cuenta de registro para pruebas y verificación de coherencia.,única fuente de verdad).
     */
    internal fun explicitRiskTable(): Map<String, ToolRiskLevel> = EXPLICIT_RISK_OVERRIDES

    /**
     * no en [ToolRegistry] Herramientas registradas(Como herramienta de habilidad/herramienta MCP)Sea consciente de los niveles de riesgo.
     *
     * v1.0.20: Cambiar a"Mapeo explícito + inferencia de prefijo de nombre"Fondo de doble capa,Cubre una gran cantidad de herramientas nombradas según las convenciones de nomenclatura dentro del proyecto.
     *
     * prioridad:
     *  0. Herramientas dinámicas de terceros(complemento / MCP):Sin inferencia de nombre,directo NORMAL
     *  1. Tabla de mapeo explícito [EXPLICIT_RISK_OVERRIDES](Algunas herramientas tienen semántica y prefijos de nombres inconsistentes,Si open_url regresa HIGH)
     *  2. inferencia de prefijo de nombre(Anular read_/get_/list_/search_ y otras familias de solo lectura,send_/delete_ grupos de alto riesgo)
     *  3. Predeterminado NORMAL(Las herramientas desconocidas se tratan de forma conservadora como riesgos ordinarios,ASK el modo preguntará)
     *
     * La inferencia de nombres solo es correcta**Incorporado**La herramienta funciona:Esos nombres están escritos en el código.,El autor de la herramienta no tiene control.
     * Los nombres de los complementos y las herramientas MCP los eligen terceros.,Una vez que participe en la inferencia, podrá nombrar usted mismo la llamada saliente.
     * `get_diagnostics` Obtenga SAFE y quede exento de revisión en modo PREGUNTAR,Por lo tanto, sus nombres no estarán involucrados en la inferencia.
     *
     * Clasificación de prefijos de nombres basada en:
     *  - SAFE:read_* / get_* / list_* / search_* / ping_* / dns_* / url_* / base64_* / hash_* /
     *          generate_* / calculator / echo — consulta pura/codificación/calcular,Sin efectos secundarios
     *  - NORMAL:set_* / update_* / create_* / add_* / toggle_* / open_* / share_* / speak_* /
     *          vibrate / scheduled_task_* / quick_note_* / resource_* / workspace_* / translate —
     *          Efectos secundarios locales pero reversibles.
     *  - HIGH:send_* / delete_* / make_phone_call / toggle_wifi / toggle_bluetooth /
     *          toggle_flashlight / set_brightness / set_volume / set_alarm / set_timer /
     *          open_url / open_maps / send_email / send_sms / add_contact / add_calendar_event /
     *          pin_memory / unpin_memory / subagent_task / browser_* / execute_javascript —
     *          irreversibles/En todos los dispositivos/implicaciones de financiación o privacidad
     */
    private fun fallbackRiskFor(toolName: String): ToolRiskLevel {
        // 0. Herramientas dinámicas de terceros(complemento / MCP)No se hace ninguna inferencia de nombre.,Siempre NORMAL.
        //    Sus nombres son determinados por un tercero.,`get_*` Tal vez una llamada saliente,`send_*` O tal vez sea solo un cálculo local.;
        //    Dar SAFE por su nombre otorga aprobación al autor de la herramienta. El juicio debe detenerse antes del nombre.
        if (isThirdPartyToolName(toolName)) return ToolRiskLevel.NORMAL

        // 1. El mapeo explícito tiene prioridad(Anular herramientas cuya semántica sea inconsistente con los prefijos de nombres)
        EXPLICIT_RISK_OVERRIDES[toolName]?.let { return it }

        // 2. inferencia de prefijo de nombre(Nota:HIGH La familia contiene prefijos más específicos como set_/open_/send_,Primero es necesario coincidir con la familia NORMAL)
        return when {
            // ── HIGH clan(prefijo más específico,Prefiero emparejamiento familiar NORMAL)──
            toolName.startsWith("send_") -> ToolRiskLevel.HIGH
            toolName.startsWith("delete_") -> ToolRiskLevel.HIGH
            toolName.startsWith("browser_") -> ToolRiskLevel.HIGH
            // P3-3: UI Herramientas de automatización(Manipular la pantalla del dispositivo/Leer interfaz de usuario/captura de pantalla),Privacidad y seguridad sensibles,unificar HIGH
            toolName.startsWith("ui_") -> ToolRiskLevel.HIGH
            // L1-9: Originalmente habia `toolName in HIGH_RISK_EXPLICIT_SET` rama,Pero la función comienza con
            // `EXPLICIT_RISK_OVERRIDES[toolName]?.let { return it }`,Venir aquí definitivamente no estará en la lista.,
            // Todas las entradas de este SET provienen de la tabla —— La sucursal nunca se establece,código muerto,Eliminar junto con SET.

            // ── SAFE clan(consulta pura/codificación/calcular)──
            toolName.startsWith("read_") -> ToolRiskLevel.SAFE
            toolName.startsWith("get_") -> ToolRiskLevel.SAFE
            toolName.startsWith("list_") -> ToolRiskLevel.SAFE
            toolName.startsWith("search_") -> ToolRiskLevel.SAFE
            toolName.startsWith("ping_") -> ToolRiskLevel.SAFE
            toolName.startsWith("dns_") -> ToolRiskLevel.SAFE
            toolName.startsWith("url_") -> ToolRiskLevel.SAFE
            toolName.startsWith("base64_") -> ToolRiskLevel.SAFE
            toolName.startsWith("hash_") -> ToolRiskLevel.SAFE
            toolName.startsWith("generate_") -> ToolRiskLevel.SAFE
            toolName == "calculator" || toolName == "echo" -> ToolRiskLevel.SAFE

            // ── NORMAL clan(efectos secundarios locales,reversibles)──
            toolName.startsWith("set_") -> ToolRiskLevel.NORMAL
            toolName.startsWith("update_") -> ToolRiskLevel.NORMAL
            toolName.startsWith("create_") -> ToolRiskLevel.NORMAL
            toolName.startsWith("add_") -> ToolRiskLevel.NORMAL
            toolName.startsWith("toggle_") -> ToolRiskLevel.NORMAL
            toolName.startsWith("open_") -> ToolRiskLevel.NORMAL
            toolName.startsWith("share_") -> ToolRiskLevel.NORMAL
            toolName.startsWith("speak_") -> ToolRiskLevel.NORMAL
            toolName.startsWith("scheduled_task_") -> ToolRiskLevel.NORMAL
            toolName.startsWith("quick_note_") -> ToolRiskLevel.NORMAL
            toolName.startsWith("resource_") -> ToolRiskLevel.NORMAL
            toolName.startsWith("workspace_") -> ToolRiskLevel.NORMAL
            toolName == "vibrate" || toolName == "translate" -> ToolRiskLevel.NORMAL

            // 3. Herramienta desconocida presiona de forma conservadora NORMAL(ASK el modo preguntará)
            else -> ToolRiskLevel.NORMAL
        }
    }

    /**
     * Mapeo explícito de niveles de riesgo — Caso especial para anular la inferencia de prefijo con nombre.
     *
     * por ejemplo:
     *  - open_url / open_maps Pendiente HIGH(Abrir enlace externo/mapa,Riesgos potenciales para la privacidad),
     *    Pero open_app/open_system_setting pertenece a NORMAL — Igual que el prefijo open_,Semántica diferente,
     *    debe distinguirse explícitamente
     *  - set_brightness / set_volume / set_alarm / set_timer Pendiente HIGH(estado irreversible del sistema),
     *    Pero otros set_* volver NORMAL — Igual que set_ prefijo
     *  - pin_memory / unpin_memory volver HIGH(Afecta la memoria a largo plazo,sensible a la privacidad),
     *    con [io.zer0.muse.data.audit.AuditLogger] La clasificación de la auditoría es consistente.
     *  - web_search / web_fetch / read_file Esperando a que vuelva la herramienta de consulta SAFE
     *  - write_file / pin_memory etc. Las herramientas de efectos secundarios locales son NORMALES o HIGH
     */
    private val EXPLICIT_RISK_OVERRIDES: Map<String, ToolRiskLevel> = mapOf(
        "relay_channel_message" to ToolRiskLevel.HIGH,
        // SAFE familia explícita(consulta pura/leer)
        "web_search" to ToolRiskLevel.SAFE,
        "web_fetch" to ToolRiskLevel.SAFE,
        "knowledge_search" to ToolRiskLevel.SAFE,
        "arxiv_search" to ToolRiskLevel.SAFE,
        "read_file" to ToolRiskLevel.SAFE,
        "http_get" to ToolRiskLevel.SAFE,
        "current_status" to ToolRiskLevel.SAFE,
        "get_token_usage" to ToolRiskLevel.SAFE,
        "recall_experience" to ToolRiskLevel.SAFE,
        // v2.0.1: La búsqueda de mercado mediante complementos es una consulta de solo lectura
        "plugin_market_search" to ToolRiskLevel.SAFE,

        // NORMAL familia explícita(efectos secundarios locales,reversibles)
        "write_file" to ToolRiskLevel.NORMAL,
        "http_post" to ToolRiskLevel.NORMAL,
        // v2.6.10: La lista de tareas pendientes es el tablero de planificación del propio agente. —— Solo dibujado en la interfaz.,No escribas archivos,
        // No toque el equipo. una vez regresado NORMAL,ASK En el modo, debe hacer clic en Aceptar cada vez que actualice el plan.,
        // 30 Rechazado automáticamente en segundos,Todo el arreglo de tareas está estancado. El mismo calibre que show_card pertenece a SAFE.
        "todo_write" to ToolRiskLevel.SAFE,
        // v2.6.10: Las cartas son puramente acciones de visualización. —— El contenido se representa en un WebView de espacio aislado,No escribas archivos,
        // No toca aplicaciones externas ni dispositivos cruzados. una vez regresado NORMAL,La consecuencia es que cada tarjeta en modo ASK requiere que el usuario haga clic
        // De acuerdo una vez,30 Se rechazará automáticamente en unos segundos y la tarjeta se desechará. Lo que ve el usuario es"La tarjeta no se puede producir.".
        // Mismo calibre que render_data,Vaya SEGURO.
        "show_card" to ToolRiskLevel.SAFE,
        "render_data" to ToolRiskLevel.SAFE,
        "notify" to ToolRiskLevel.NORMAL,
        "record_experience" to ToolRiskLevel.NORMAL,
        "channel_reply" to ToolRiskLevel.NORMAL,
        "channel_pass" to ToolRiskLevel.NORMAL,
        "channel_read_context" to ToolRiskLevel.NORMAL,
        // enable_skill Solo se cambia el bit de habilitación local y se puede revertir usando enable_skill,Por eso está marcado como NORMAL.
        "enable_skill" to ToolRiskLevel.NORMAL,
        // v2.x: Enviar mensaje de voz —— El nombre tiene el prefijo send_(De lo contrario, será empujado a HIGH),En realidad, solo síntesis TTS local.
        // Luego cuélgalo en tu propio mensaje.:No enviado ni escrito en el directorio de usuarios. Si está configurado en ALTO, la tarjeta de aprobación aparecerá cada vez que envíe un mensaje de voz.
        "send_voice" to ToolRiskLevel.NORMAL,

        // HIGH familia explícita(irreversibles/En todos los dispositivos/Privacidad)
        "install_skill" to ToolRiskLevel.HIGH,
        // v2.0.1: Instalar el complemento de mercado = Introduciendo código ejecutable externo,Mismo nivel que install_skill
        "plugin_market_install" to ToolRiskLevel.HIGH,
        // v2.x: Cambios en el ciclo de vida del complemento(Desinstalar/Iniciar y detener) — Mismo diámetro que la instalación.,volver HIGH
        "plugin_market_uninstall" to ToolRiskLevel.HIGH,
        "plugin_market_set_enabled" to ToolRiskLevel.HIGH,
        // v2.x Agent herramientas moviles:El control de pasos, la pantalla virtual y la lectura de privacidad entre aplicaciones son ALTOS.
        "automation_workflow" to ToolRiskLevel.HIGH,
        "virtual_screen" to ToolRiskLevel.HIGH,
        "virtual_screen_input" to ToolRiskLevel.HIGH,
        "screen_read" to ToolRiskLevel.HIGH,
        "screen_current_app" to ToolRiskLevel.HIGH,
        "screen_tap" to ToolRiskLevel.HIGH,
        "screen_tap_text" to ToolRiskLevel.HIGH,
        "screen_swipe" to ToolRiskLevel.HIGH,
        "screen_pinch" to ToolRiskLevel.HIGH,
        "screen_swipe_path" to ToolRiskLevel.HIGH,
        "screen_input" to ToolRiskLevel.HIGH,
        "screen_launch_app" to ToolRiskLevel.HIGH,
        "app_force_stop" to ToolRiskLevel.HIGH,
        "app_clear_data" to ToolRiskLevel.HIGH,
        "app_uninstall" to ToolRiskLevel.HIGH,
        // Root/Shizuku La operación del equipo y la lectura sensible deben seguir la misma fuente real de riesgo.
        "settings_get" to ToolRiskLevel.HIGH,
        "settings_put" to ToolRiskLevel.HIGH,
        "network_toggle" to ToolRiskLevel.HIGH,
        "am_start" to ToolRiskLevel.HIGH,
        "list_packages" to ToolRiskLevel.HIGH,
        "logcat_tail" to ToolRiskLevel.HIGH,
        "input_inject" to ToolRiskLevel.HIGH,
        // v2.x Automatización Fase I:Canal de línea de comando del dispositivo — Se puede tocar/activar/Cambiar configuración,volver HIGH
        "device_shell" to ToolRiskLevel.HIGH,
        // v2.x fase terminal uno:App Terminal sandbox con permisos propios — Los archivos de la aplicación se pueden agregar o eliminar,volver HIGH
        "terminal_exec" to ToolRiskLevel.HIGH,
        // v2.2.1: Termux canal — Entorno Linux completo para ejecutar comandos arbitrarios.,volver HIGH
        "termux_exec" to ToolRiskLevel.HIGH,
        // v2.2.1: GUI Agent anillo — Puede realizar cualquier operación continua de varios pasos App,volver HIGH
        "ui_agent" to ToolRiskLevel.HIGH,
        // v1.x: update_skill Mismo nivel que install_skill — Ambos pueden reescribir la definición de habilidad que posteriormente ejecutará el asistente.;
        // El contenido todavía está en la lista blanca de SkillImporter/Inyectar verificación de lista negra,Y solo se permite modificar los creados por el usuario. skill
        "update_skill" to ToolRiskLevel.HIGH,
        // Complemento autoescrito del asistente:Producir código que será firmado por el usuario.,Movimiento autoexpandible y de alto riesgo
        "author_plugin" to ToolRiskLevel.HIGH,
        "delegate_agent" to ToolRiskLevel.HIGH,
        // v1.0.52 P2-1: subagent_run Niño independiente de bloqueo sincrónico agent,Se pueden llamar varias herramientas.,Posibles efectos secundarios
        "subagent_run" to ToolRiskLevel.HIGH,
        "execute_javascript" to ToolRiskLevel.HIGH,
        "execute_node_script" to ToolRiskLevel.HIGH,
        "workspace_write" to ToolRiskLevel.HIGH,
        // v2.x: El reemplazo parcial también se escribe en el disco, que está al mismo nivel que workspace_write.
        "workspace_edit" to ToolRiskLevel.HIGH,
        "workspace_delete" to ToolRiskLevel.HIGH,
        "workspace_mkdir" to ToolRiskLevel.HIGH,
        "workspace_move" to ToolRiskLevel.HIGH,
        // open_ Cadena de excepción especial con ALTO en prefijo
        "open_url" to ToolRiskLevel.HIGH,
        "open_maps" to ToolRiskLevel.HIGH,
        // set_ El estado del sistema cambia con ALTO en el prefijo
        "set_brightness" to ToolRiskLevel.HIGH,
        "set_volume" to ToolRiskLevel.HIGH,
        "set_alarm" to ToolRiskLevel.HIGH,
        "set_timer" to ToolRiskLevel.HIGH,
        // Memoria a largo plazo / asistente cruzado / retorno de comunicación HIGH
        "pin_memory" to ToolRiskLevel.HIGH,
        "unpin_memory" to ToolRiskLevel.HIGH,
        "save_memory" to ToolRiskLevel.HIGH,
        "delete_memory" to ToolRiskLevel.HIGH,
        "subagent_task" to ToolRiskLevel.HIGH,
        "make_phone_call" to ToolRiskLevel.HIGH,
        "toggle_wifi" to ToolRiskLevel.HIGH,
        "toggle_bluetooth" to ToolRiskLevel.HIGH,
        "toggle_flashlight" to ToolRiskLevel.HIGH,
    )

    /**
     * Lista blanca de herramientas de seguridad que todavía están permitidas automáticamente en modo estricto.
     *
     * v1.0.20: Ampliado de 3 a todas las herramientas de solo lectura,Permitir a los usuarios llamar a clases de consulta diarias en modo ESTRICTO
     * Todavía suave(No es necesaria una aprobación paso a paso),Sólo las herramientas NORMAL/ALTA entran en el proceso de aprobación.
     *
     * Cobertura:
     *  - get_*(get_current_time / get_weather / get_device_info / get_battery_info /
     *          get_location / get_storage_info / get_memory_info / get_display_info /
     *          get_cpu_info / get_sensors_list / get_brightness / get_volume /
     *          get_foreground_app / get_wifi_info / get_bluetooth_devices /
     *          get_network_info / get_contacts_count espera)
     *  - list_*(list_installed_apps / list_reminders / list_stickers espera)
     *  - read_*(clipboard_read / workspace_read espera)
     *  - search_*(quick_note_search / resource_search espera)
     *  - Otros solo leen:calculator / echo / ping_host / dns_lookup / get_public_ip /
     *            url_encode / url_decode / base64_encode / base64_decode / hash_text /
     *            generate_uuid / random_number / json_pretty / generate_password /
     *            generate_qr_code / scheduled_task_list / scheduled_task_get_history /
     *            translate / quick_note_get / resource_get / workspace_list / workspace_grep
     *
     * Nota:Aquí solo se coloca solo lectura local pura/Consulta/codificación/Herramientas de cálculo.
     * Se excluyen todas las escrituras, POST de red, redirecciones de aplicaciones externas, asistentes cruzados y automatización del navegador.
     */
    private val STRICT_SAFE_ALLOWLIST: Set<String> = setOf(
        // ── get_* clan(consulta de solo lectura)──
        "get_current_time",
        "get_weather",
        "get_device_info",
        "get_battery_info",
        "get_location",
        "get_storage_info",
        "get_memory_info",
        "get_display_info",
        "get_cpu_info",
        "get_sensors_list",
        "get_brightness",
        "get_volume",
        "get_foreground_app",
        "get_wifi_info",
        "get_bluetooth_devices",
        "get_network_info",
        "get_contacts_count",
        "get_public_ip",
        // ── list_* clan(enumeración de solo lectura)──
        "list_installed_apps",
        "list_reminders",
        "list_stickers",
        // ── read_* clan(leer solo leer)──
        "clipboard_read",
        "workspace_read",
        "workspace_grep",
        "memory_config_get",
        // ── search_* clan(Búsqueda de solo lectura)──
        "quick_note_search",
        "quick_note_list",
        "resource_search",
        "resource_list",
        // ── Otros solo leen/calcular/codificación/Consulta ──
        "calculator",
        "echo",
        "ping_host",
        "dns_lookup",
        "url_encode",
        "url_decode",
        "base64_encode",
        "base64_decode",
        "hash_text",
        "generate_uuid",
        "random_number",
        "json_pretty",
        "generate_password",
        "generate_qr_code",
        // v2.6.10: Las tarjetas son solo para exhibición.,Igual que render_data para renderizado sin efectos secundarios,La aprobación no debe bloquearse en modo estricto
        "show_card",
        // v2.6.10: La lista de tareas pendientes también es solo una capa de presentación.,No produce ningún efecto secundario del dispositivo.
        "todo_write",
        "scheduled_task_list",
        "scheduled_task_get_history",
        "translate",
        "quick_note_get",
        "resource_get",
        "workspace_list",
    )
}

/**
 * Merge the per-session "allow this tool" cache with the persisted policy without allowing
 * a temporary allow to override an explicit deny.
 */
internal fun effectivePerToolPolicy(configuredPolicy: ToolApprovalPolicy?, allowedThisSession: Boolean): ToolApprovalPolicy? = configuredPolicy ?: if (allowedThisSession) ToolApprovalPolicy.ALWAYS_ALLOW else null
