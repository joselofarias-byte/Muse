package io.zer0.muse.tools

/**
 * P1-3b Desdominio: registrador de herramientas celulares.
 *
 * Registrarse set_alarm / set_timer / open_app / share_text / get_location /
 * get_device_info / get_contacts_count / get_contacts_list / send_sms /
 * add_contact / make_phone_call / open_maps.
 * La implementación está ubicada en [PhoneToolsImpl.kt](La función de nivel superior se migra sin cambios).
 */
class PhoneToolsRegistrar(
    private val context: android.content.Context,
    private val toolRegistry: ToolRegistry,
) {
    private val impl = PhoneToolsImpl(context)

    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "set_alarm",
                description = "Configurar la alarma del sistema(Pase AlarmClock.ACTION_SET_ALARM,Emprendimiento de la aplicación del reloj del sistema).",
                parameters = mapOf(
                    "hour" to "Requerido,hora del despertador(0-23)",
                    "minute" to "Requerido,Minutos de alarma(0-59)",
                    "label" to "Opcional,etiquetas de despertador,Predeterminado 'muse despertador'",
                    "days_of_week" to "Opcional,Repetir cada semana,Como por ejemplo MON,TUE,WED,THU,FRI",
                    "weekdays" to "Opcional,Establezca rápidamente los días laborables para repetir(lunes a viernes),Pasar verdadero se expande a MON-FRI,tener prioridad sobre days_of_week",
                    "weekends" to "Opcional,Establecer rápidamente la repetición del fin de semana(sábado domingo),Pasar verdadero se expande a SAT,SUN,tener prioridad sobre days_of_week",
                ),
                required = setOf("hour", "minute"),
                category = "built-in",
                // P0-3: Consistente con la tabla explícita ToolPermissionResolver(HIGH)— Los cambios del despertador del sistema son irreversibles
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args -> impl.execSetAlarm(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "set_timer",
                description = "Configurar la cuenta regresiva del sistema(Pase AlarmClock.ACTION_SET_TIMER,Emprendimiento de la aplicación del reloj del sistema).",
                parameters = mapOf(
                    "seconds" to "Requerido,Segundos de cuenta regresiva",
                    "label" to "Opcional,etiqueta de cuenta regresiva,Predeterminado 'muse cuenta regresiva'",
                ),
                required = setOf("seconds"),
                category = "built-in",
                // P0-3: Consistente con la tabla explícita ToolPermissionResolver(HIGH)— Los cambios de cuenta regresiva del sistema son irreversibles
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args -> impl.execSetTimer(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "open_app",
                description = "Abra la aplicación especificada(Inicie su main por nombre de paquete Activity),O salta a través de Deep Link/acción personalizada.",
                parameters = mapOf(
                    "packageName" to "Opcional,Nombre del paquete de la aplicación,Como por ejemplo com.tencent.mm(WeChat)/com.tencent.mobileqq(QQ)",
                    "action" to "Opcional,Personalizar action(Como por ejemplo com.example.MY_ACTION)",
                    "data_uri" to "Opcional,Deep Link URI,Como por ejemplo myapp://page/123",
                ),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execOpenApp(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "share_text",
                description = "Comparta texto a través del panel para compartir del sistema(selector emergente).",
                parameters = mapOf(
                    "text" to "Requerido,texto para compartir",
                    "title" to "Opcional,Compartir título del panel",
                ),
                required = setOf("text"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execShareText(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_location",
                description = "Obtener la ubicación actual del dispositivo(Latitud y longitud). Se requiere permiso de ubicación.",
                parameters = mapOf(
                    "provider" to "Opcional,gps/network/passive,Automático predeterminado",
                    "timeout" to "Opcional,Tiempo de espera en milisegundos,Predeterminado 5000",
                ),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execGetLocation(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_device_info",
                description = "Obtener información del dispositivo:marca/modelo/Android versión/resolución de pantalla/fuerza.",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { impl.execGetDeviceInfo(emptyMap()) }

        toolRegistry.registerOutcome(
            ToolRegistry.ToolDef(
                name = "get_device_info",
                description = "Obtener información del dispositivo(versión estructurada).",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { impl.execGetDeviceInfoOutcome(emptyMap()) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_contacts_count",
                description = "Obtenga el número de contactos en la libreta de direcciones. Requiere permiso READ_CONTACTS.",
                parameters = mapOf("filter" to "Opcional,Filtrar por nombre"),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execGetContactsCount(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_contacts_list",
                description = "Obtenga la lista de contactos de la libreta de direcciones. Requiere permiso READ_CONTACTS.",
                parameters = mapOf(
                    "limit" to "Opcional,Cantidad de devolución,Predeterminado 20",
                    "filter" to "Opcional,Filtrar por nombre",
                ),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execGetContactsList(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "send_sms",
                description = "Envía un mensaje de texto. Requiere permiso SEND_SMS.",
                parameters = mapOf(
                    "phone" to "Requerido,Número de teléfono celular de destino",
                    "message" to "Requerido,Contenido SMS",
                ),
                required = setOf("phone", "message"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args -> impl.execSendSms(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "add_contact",
                description = "Añade un contacto. Requiere permiso WRITE_CONTACTS.",
                parameters = mapOf(
                    "name" to "Requerido,Nombre de contacto",
                    "phone" to "Opcional,Número de teléfono celular",
                    "email" to "Opcional,Correo electrónico",
                ),
                required = setOf("name"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args -> impl.execAddContact(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "make_phone_call",
                description = "Abra la interfaz de marcación del sistema y complete previamente el número de teléfono celular(No se marcará directamente).",
                parameters = mapOf("phone" to "Requerido,Número de teléfono celular de destino"),
                required = setOf("phone"),
                category = "built-in",
                // Efectos secundarios del teléfono etiquetados HIGH,Hacer que el modo PREGUNTAR/ESTRICT requiera aprobación;
                // TRUSTED El modo se ejecuta en función de la configuración sin confirmación para todas las herramientas seleccionadas explícitamente por el usuario.
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args -> impl.execMakePhoneCall(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "open_maps",
                description = "Abra la aplicación Mapas para buscar un lugar o navegar a una latitud y longitud específicas.",
                parameters = mapOf(
                    "query" to "Opcional,Buscar palabras clave,Como el Museo del Palacio",
                    "lat" to "Opcional,Latitud",
                    "lng" to "Opcional,longitud",
                ),
                required = emptySet(),
                category = "built-in",
                // P0-3: Consistente con la tabla explícita ToolPermissionResolver(HIGH)— Riesgos de privacidad de los mapas de enlaces externos
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args -> impl.execOpenMaps(args) }
    }
}
