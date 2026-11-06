package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R

/**
 * P1-3b Deslocalización: implementación de herramientas celulares (migrada desde ToolRegistry.kt, encapsulación de objetos para mantener el contexto).
 * Registrado en ToolRegistry por PhoneToolsRegistrar.
 */
class PhoneToolsImpl(private val context: Context) {
// ── Implementación de herramienta celular(7 un)──────────────────────────────────────────────

    /** Configurar la alarma del sistema:Inicie la aplicación del reloj del sistema a través de AlarmClock.ACTION_SET_ALARM. No se requieren permisos de tiempo de ejecución. */
    suspend fun execSetAlarm(args: Map<String, String>): String {
        val hour =
            args["hour"]?.toIntOrNull()
                ?: return context.getString(R.string.tool_missing_param_hour)
        if (hour !in 0..23) return context.getString(R.string.tool_hour_range)
        val minute =
            args["minute"]?.toIntOrNull()
                ?: return context.getString(R.string.tool_missing_param_minute)
        if (minute !in 0..59) return context.getString(R.string.tool_minute_range)
        val label = args["label"]?.takeIf { it.isNotBlank() } ?: context.getString(R.string.tool_alarm_label_default)
        val intent =
            android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        // days_of_week:Repetir cada semana,Como por ejemplo "MON,TUE,WED,THU,FRI"
        // v1.47: weekdays Parámetros de acceso directo — true=jornada laboral(MON-FRI), weekends=true=fin de semana(SAT,SUN)
        val weekdays = args["weekdays"]?.toBoolean() ?: false
        val weekends = args["weekends"]?.toBoolean() ?: false
        val daysStr =
            when {
                weekdays -> "MON,TUE,WED,THU,FRI"
                weekends -> "SAT,SUN"
                else -> args["days_of_week"]?.takeIf { it.isNotBlank() }
            }
        if (daysStr != null) {
            val dayMap =
                mapOf(
                    "SUN" to java.util.Calendar.SUNDAY,
                    "MON" to java.util.Calendar.MONDAY,
                    "TUE" to java.util.Calendar.TUESDAY,
                    "WED" to java.util.Calendar.WEDNESDAY,
                    "THU" to java.util.Calendar.THURSDAY,
                    "FRI" to java.util.Calendar.FRIDAY,
                    "SAT" to java.util.Calendar.SATURDAY,
                )
            val days = daysStr.split(",").mapNotNull { dayMap[it.trim().uppercase()] }
            if (days.isNotEmpty()) {
                intent.putExtra(android.provider.AlarmClock.EXTRA_DAYS, ArrayList(days))
            }
        }
        // M-TR1: Usar en su lugar resultOf{}(Lanzamiento correcto CancellationException)
        val alarmResult =
            resultOf {
                context.startActivity(intent)
                val repeat = daysStr?.let { context.getString(R.string.tool_alarm_repeat, it) } ?: ""
                context.getString(R.string.tool_alarm_set, "%02d".format(hour), "%02d".format(minute), label, repeat)
            }.onError { msg, _ -> Logger.w("ToolRegistry", "No se pudo configurar la alarma: $msg") }
        // v2.x: Añadir fracaso [falló] marca de decisión — redacción desnuda"No se pudo configurar la alarma"Sin marca,Anteriormente ToolResultJudge lo juzgó erróneamente como exitoso
        return alarmResult.getOrNull()
            ?: "[falló] ${context.getString(R.string.tool_alarm_set_failed)},Puedes usar add_calendar_event en su lugar o recordarle al usuario que lo configure manualmente"
    }

    /** Configurar la cuenta regresiva del sistema:Inicie la aplicación del reloj del sistema a través de AlarmClock.ACTION_SET_TIMER. No se requieren permisos de tiempo de ejecución. */
    suspend fun execSetTimer(args: Map<String, String>): String {
        val seconds =
            args["seconds"]?.toIntOrNull()
                ?: return context.getString(R.string.tool_missing_param_seconds)
        if (seconds <= 0) return context.getString(R.string.tool_seconds_positive)
        val label = args["label"]?.takeIf { it.isNotBlank() } ?: context.getString(R.string.tool_timer_label_default)
        val intent =
            android.content.Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        // M-TR1: Usar en su lugar resultOf{}(Lanzamiento correcto CancellationException)
        val timerResult =
            resultOf {
                context.startActivity(intent)
                context.getString(R.string.tool_timer_set, seconds, label)
            }.onError { msg, _ -> Logger.w("ToolRegistry", "No se pudo configurar la cuenta regresiva: $msg") }
        // v2.x: Añadir fracaso [falló] marca de decisión(Lo mismo set_alarm)
        return timerResult.getOrNull()
            ?: "[falló] ${context.getString(R.string.tool_timer_set_failed)}"
    }

    /** Abrir aplicación:apoyo Deep Link(data_uri), personalizado action,O inicie la interfaz principal a través del nombre del paquete. */
    suspend fun execOpenApp(args: Map<String, String>): String {
        val dataUri = args["data_uri"]?.takeIf { it.isNotBlank() }
        val action = args["action"]?.takeIf { it.isNotBlank() }
        val packageName = args["packageName"]?.takeIf { it.isNotBlank() }
        // Elija una de las tres prioridades:data_uri > action > packageName
        // M-TR1: Usar en su lugar resultOf{}(Lanzamiento correcto CancellationException)
        return resultOf {
            if (dataUri != null) {
                // Deep Link saltar
                val intent =
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(dataUri),
                    ).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                context.startActivity(intent)
                context.getString(R.string.tool_deep_link_opened, dataUri)
            } else if (action != null) {
                // Inicio de acción personalizada
                val intent =
                    android.content.Intent(action).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                context.startActivity(intent)
                context.getString(R.string.tool_action_started, action)
            } else {
                // El nombre del paquete inicia la interfaz principal.
                if (packageName == null) return context.getString(R.string.tool_missing_param_package_or_action)
                val launchIntent =
                    context.packageManager.getLaunchIntentForPackage(packageName)
                        ?: return context.getString(R.string.tool_app_not_found, packageName)
                launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                context.getString(R.string.tool_app_opened, packageName)
            }
        }.onError { msg, _ -> Logger.w("ToolRegistry", "No se pudo abrir la aplicación: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_open_app_failed)
    }

    /** compartir texto:Abra el panel para compartir el sistema a través de ACTION_SEND + createChooser. */
    suspend fun execShareText(args: Map<String, String>): String {
        val text =
            args["text"]?.trim()
                ?: return context.getString(R.string.tool_missing_param_text_share)
        if (text.isEmpty()) return context.getString(R.string.tool_text_empty)
        val mimeType = args["mime_type"]?.takeIf { it.isNotBlank() } ?: "text/plain"
        val title = args["title"]?.takeIf { it.isNotBlank() } ?: context.getString(R.string.tool_share_title_default)
        val sendIntent =
            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(android.content.Intent.EXTRA_TEXT, text)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        val chooser =
            android.content.Intent.createChooser(sendIntent, title).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        // M-TR1: Usar en su lugar resultOf{}(Lanzamiento correcto CancellationException)
        return resultOf {
            context.startActivity(chooser)
            context.getString(R.string.tool_text_shared, text.take(50) + if (text.length > 50) "..." else "")
        }.onError { msg, _ -> Logger.w("ToolRegistry", "Error al compartir: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_share_failed)
    }

    /**
     * Obtener una ubicación aproximada:Leer la última posición conocida del sistema.(No solicitar permisos activamente,no abierto GPS).
     * Requiere permiso de tiempo de ejecución ACCESS_COARSE_LOCATION;Aviso de devolución cuando no esté autorizado,No chocar.
     * provider Parámetro opcional network/gps(Recorrer todo por defecto provider);timeout Reserva de parámetros(Este problema se basa en la última ubicación conocida.,Sin espera de bloqueo).
     */
    suspend fun execGetLocation(args: Map<String, String>): String {
        val hasPermission =
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            return context.getString(R.string.tool_location_no_permission)
        }
        val lm =
            context.getSystemService(android.content.Context.LOCATION_SERVICE)
                as android.location.LocationManager
        // provider parámetros:Priorizar lo especificado(network/gps),De lo contrario, recorra todos los proveedores y obtenga la última versión.
        val providerParam = args["provider"]?.trim()?.lowercase()
        // timeout Lectura de parámetros(Interfaz reservada,Este problema se basa en la última posición conocida y no bloquea la espera.)
        val location =
            if (providerParam != null &&
                lm.allProviders.any { it.equals(providerParam, ignoreCase = true) }
            ) {
                // M-TR1: Usar en su lugar resultOf{}(Lanzamiento correcto CancellationException)
                resultOf { lm.getLastKnownLocation(providerParam) }.getOrNull()
                    ?: lm.allProviders.mapNotNull { p ->
                        resultOf { lm.getLastKnownLocation(p) }.getOrNull()
                    }.maxByOrNull { it.time }
            } else {
                lm.allProviders.mapNotNull { p ->
                    resultOf { lm.getLastKnownLocation(p) }.getOrNull()
                }.maxByOrNull { it.time }
            } ?: return context.getString(R.string.tool_location_unavailable)
        return context.getString(
            R.string.tool_location_result,
            "%.4f".format(location.latitude),
            "%.4f".format(location.longitude),
            "%.0f".format(location.accuracy),
        )
    }

    /** Obtener información del dispositivo:marca/modelo/Android versión/resolución de pantalla/fuerza. */
    suspend fun execGetDeviceInfo(_args: Map<String, String>): String {
        // M-TR1: Usar en su lugar resultOf{}(Lanzamiento correcto CancellationException)
        val batteryLevel =
            resultOf {
                val bm =
                    context.getSystemService(android.content.Context.BATTERY_SERVICE)
                        as android.os.BatteryManager
                "${bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)}%"
            }.getOrNull() ?: context.getString(R.string.tool_device_unknown)
        val dm = context.resources.displayMetrics
        return buildString {
            appendLine(context.getString(R.string.tool_device_brand, android.os.Build.BRAND))
            appendLine(context.getString(R.string.tool_device_model, android.os.Build.MODEL))
            appendLine("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            appendLine(context.getString(R.string.tool_device_screen, dm.widthPixels, dm.heightPixels))
            append(context.getString(R.string.tool_device_battery, batteryLevel))
        }
    }

    /**
     * v1.0.53: get_device_info Versión estructurada — Adjunte los detalles de marca/modelo/androidVersion/sdkInt.
     * marca/modelo/Las versiones son constantes de compilación.,Cero IO extra.
     */
    suspend fun execGetDeviceInfoOutcome(_args: Map<String, String>): ToolOutcome {
        val text = execGetDeviceInfo(_args)
        return ToolOutcome.ok(
            text,
            details =
            mapOf(
                "brand" to android.os.Build.BRAND,
                "model" to android.os.Build.MODEL,
                "androidVersion" to android.os.Build.VERSION.RELEASE,
                "sdkInt" to android.os.Build.VERSION.SDK_INT,
            ),
        )
    }

    /** Obtener el número de contactos en la libreta de direcciones:Requiere permiso de ejecución READ_CONTACTS. Admite el recuento después de filtrar por filtro de nombre. */
    suspend fun execGetContactsCount(args: Map<String, String>): String {
        val hasPermission =
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_CONTACTS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            return context.getString(R.string.tool_contacts_no_permission)
        }
        val filter = args["filter"]?.takeIf { it.isNotBlank() }
        // M-TR3: Escapar del carácter comodín LIKE(% _ \),agregar ESCAPE '\' cláusula,Evitar coincidencias falsas cuando el filtro contiene %_
        val selection =
            if (filter != null) {
                "${android.provider.ContactsContract.Contacts.DISPLAY_NAME} LIKE ? ESCAPE '\\'"
            } else {
                null
            }
        val selectionArgs =
            if (filter != null) {
                val escaped = filter.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                arrayOf("%$escaped%")
            } else {
                null
            }
        // M-TR1: Usar en su lugar resultOf{}(Lanzamiento correcto CancellationException)
        return resultOf {
            val cursor =
                context.contentResolver.query(
                    android.provider.ContactsContract.Contacts.CONTENT_URI,
                    null, selection, selectionArgs, null,
                )
            val count = cursor?.use { it.count } ?: 0
            if (filter != null) {
                context.getString(R.string.tool_contacts_count_filtered, filter, count)
            } else {
                context.getString(R.string.tool_contacts_count, count)
            }
        }.onError { msg, _ -> Logger.w("ToolRegistry", "No se pudieron leer los contactos: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_contacts_read_failed)
    }

    /**
     * Obtener lista de contactos(Versión mejorada):retorno por fila "name | phone".
     * Requiere permiso de ejecución READ_CONTACTS;Admite filtrado por filtro de nombre,limit Limite el número de devoluciones.
     */
    suspend fun execGetContactsList(args: Map<String, String>): String {
        val hasPermission =
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_CONTACTS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            return context.getString(R.string.tool_contacts_no_permission)
        }
        val filter = args["filter"]?.takeIf { it.isNotBlank() }
        val limit = args["limit"]?.toIntOrNull()?.takeIf { it > 0 } ?: 20
        // M-TR1: Usar en su lugar resultOf{}(Lanzamiento correcto CancellationException)
        return resultOf {
            val projection =
                arrayOf(
                    android.provider.ContactsContract.Contacts._ID,
                    android.provider.ContactsContract.Contacts.DISPLAY_NAME,
                    android.provider.ContactsContract.Contacts.HAS_PHONE_NUMBER,
                )
            // M-TR3: Escapar del carácter comodín LIKE(% _ \),agregar ESCAPE '\' cláusula
            val selection =
                if (filter != null) {
                    "${android.provider.ContactsContract.Contacts.DISPLAY_NAME} LIKE ? ESCAPE '\\'"
                } else {
                    null
                }
            val selectionArgs =
                if (filter != null) {
                    val escaped = filter.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                    arrayOf("%$escaped%")
                } else {
                    null
                }
            val sortOrder = "${android.provider.ContactsContract.Contacts.DISPLAY_NAME} ASC"
            val sb = StringBuilder()
            var count = 0
            context.contentResolver.query(
                android.provider.ContactsContract.Contacts.CONTENT_URI,
                projection, selection, selectionArgs, sortOrder,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(android.provider.ContactsContract.Contacts._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(android.provider.ContactsContract.Contacts.DISPLAY_NAME)
                val hasPhoneIdx = cursor.getColumnIndexOrThrow(android.provider.ContactsContract.Contacts.HAS_PHONE_NUMBER)
                while (cursor.moveToNext() && count < limit) {
                    val id = cursor.getLong(idIdx)
                    val name = cursor.getString(nameIdx) ?: context.getString(R.string.tool_no_name)
                    val phone =
                        if (cursor.getInt(hasPhoneIdx) > 0) {
                            // Consultar el número de teléfono del contacto.(toma el primero)
                            // M-TR1: Usar en su lugar resultOf{}(Lanzamiento correcto CancellationException)
                            resultOf {
                                context.contentResolver.query(
                                    android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                                    arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                                    "${android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                                    arrayOf(id.toString()),
                                    null,
                                )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: ""
                            }.getOrNull() ?: ""
                        } else {
                            ""
                        }
                    sb.appendLine("$name | $phone")
                    count++
                }
            }
            if (count == 0) {
                if (filter != null) context.getString(R.string.tool_contacts_not_found_filtered, filter) else context.getString(R.string.tool_contacts_empty)
            } else {
                sb.toString().trimEnd()
            }
        }.onError { msg, _ -> Logger.w("ToolRegistry", "No se pudo leer la lista de contactos: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_contacts_list_read_failed)
    }

    /**
     * Enviar SMS:Envíe directamente cuando tenga permiso SEND_SMS y el mensaje no esté vacío,De lo contrario, abra la aplicación de SMS del sistema para precompletar.
     * slot Los parámetros están reservados para tarjetas duales.(La programación de SubscriptionManager no está implementada en este problema).
     */
    suspend fun execSendSms(args: Map<String, String>): String {
        val phone =
            args["phone"]?.takeIf { it.isNotBlank() }
                ?: return context.getString(R.string.tool_missing_param_phone)
        // El nombre del parámetro debe ser coherente con el esquema de PhoneToolsRegistrar.("message"):Leer anteriormente "body",
        // El texto siempre está vacío. → “Enviar directamente”La sucursal nunca se establece,Siempre degradado para abrir la aplicación de SMS.
        val body = args["message"] ?: ""
        // slot Lectura de parámetros(reservado,La selección de tarjeta dual no está disponible en esta edición)
        val hasPermission =
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.SEND_SMS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (hasPermission && body.isNotEmpty()) {
            // M-TR1: Usar en su lugar resultOf{}(Lanzamiento correcto CancellationException)
            return resultOf {
                val sentIntent =
                    android.app.PendingIntent.getBroadcast(
                        context, 0,
                        android.content.Intent("SMS_SENT"),
                        android.app.PendingIntent.FLAG_IMMUTABLE,
                    )
                // M-TR2: SmsManager.getDefault() En desuso en API 31+,Usar en su lugar Context.getSystemService
                val smsManager =
                    context.getSystemService(android.telephony.SmsManager::class.java)
                        ?: return@resultOf context.getString(R.string.tool_sms_send_failed_no_manager)
                smsManager.sendTextMessage(phone, null, body, sentIntent, null)
                context.getString(R.string.tool_sms_sent, phone)
            }.onError { msg, _ -> Logger.w("ToolRegistry", "No se pudo enviar SMS: $msg") }
                .getOrNull() ?: context.getString(R.string.tool_sms_send_failed)
        }
        // Sin permiso o sin cuerpo: precarga de solicitud de SMS de sistema abierto
        // M-TR1: Usar en su lugar resultOf{}(Lanzamiento correcto CancellationException)
        return resultOf {
            val intent =
                android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                    data = android.net.Uri.parse("smsto:$phone")
                    putExtra("sms_body", body)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            context.startActivity(intent)
            context.getString(R.string.tool_sms_app_opened, phone)
        }.onError { msg, _ -> Logger.w("ToolRegistry", "No se pudo abrir la aplicación de SMS: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_sms_app_open_failed)
    }

    /**
     * Crear nuevo contacto:Abra el nuevo formulario de contacto del sistema a través de Intent.ACTION_INSERT,
     * Nombre de precarga/telefono/Correo electrónico. No se requieren permisos de tiempo de ejecución(Realizado por la aplicación de libreta de direcciones del sistema.).
     */
    suspend fun execAddContact(args: Map<String, String>): String {
        val name =
            args["name"]?.takeIf { it.isNotBlank() }
                ?: return context.getString(R.string.tool_missing_param_name)
        // M-TR1: Usar en su lugar resultOf{}(Lanzamiento correcto CancellationException)
        return resultOf {
            val intent =
                android.content.Intent(android.content.Intent.ACTION_INSERT).apply {
                    type = android.provider.ContactsContract.Contacts.CONTENT_TYPE
                    putExtra(android.provider.ContactsContract.Intents.Insert.NAME, name)
                    args["phone"]?.takeIf { it.isNotBlank() }?.let {
                        putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, it)
                    }
                    args["email"]?.takeIf { it.isNotBlank() }?.let {
                        putExtra(android.provider.ContactsContract.Intents.Insert.EMAIL, it)
                    }
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            context.startActivity(intent)
            context.getString(R.string.tool_contact_form_opened, name)
        }.onError { msg, _ -> Logger.w("ToolRegistry", "No se pudo abrir un nuevo formulario de contacto: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_contact_form_open_failed)
    }

/** Abra la interfaz de marcación del sistema y complete previamente el número de teléfono celular. */
    suspend fun execMakePhoneCall(args: Map<String, String>): String {
        val phone =
            args["phone"]?.takeIf { it.isNotBlank() }
                ?: return context.getString(R.string.tool_phone_call_missing)
        val intent =
            android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:$phone")).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        return resultOf {
            context.startActivity(intent)
            context.getString(R.string.tool_phone_call_opened, phone)
        }.onError { msg, _ -> Logger.w("ToolRegistry", "No se pudo abrir el acceso telefónico: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_open_url_failed, phone)
    }

/** Abre la aplicación Mapas. */
    suspend fun execOpenMaps(args: Map<String, String>): String {
        val query = args["query"]
        val lat = args["lat"]
        val lng = args["lng"]
        val uri =
            if (!lat.isNullOrBlank() && !lng.isNullOrBlank()) {
                val label = if (!query.isNullOrBlank()) android.net.Uri.encode(query) else "$lat,$lng"
                "geo:$lat,$lng?q=$lat,$lng($label)"
            } else if (!query.isNullOrBlank()) {
                "geo:0,0?q=${android.net.Uri.encode(query)}"
            } else {
                return context.getString(R.string.tool_maps_missing)
            }
        val intent =
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri)).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        return resultOf {
            context.startActivity(intent)
            context.getString(R.string.tool_maps_opened, uri)
        }.onError { msg, _ -> Logger.w("ToolRegistry", "No se pudo abrir el mapa: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_open_url_failed, uri)
    }
}
