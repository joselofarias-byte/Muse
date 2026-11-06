package io.zer0.muse.tools

import android.annotation.SuppressLint
import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.notification.MuseNotificationListenerService
import io.zer0.muse.util.MusePatterns
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P1-3b Desdomainización: Sistema/Implementación de herramientas de dispositivo (migrada desde ToolRegistry.kt).
 * Registrado en ToolRegistry por SystemToolsRegistrar.
 */
class SystemToolsImpl(private val context: Context) {
    private val fmtTimeMin = ThreadLocal.withInitial { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    // ── Implementación de herramientas de control de sistemas y correo electrónico.(5 un)──────────────────────────────────────

    /**
     * Abra la página de configuración del sistema:Admite saltar a elementos de configuración específicos a través de una categoría(wifi/bluetooth/display espera).
     * app_settings La categoría irá acompañada del nombre del paquete de la aplicación.,Vaya directamente a la página de detalles de la solicitud. No se requieren permisos de tiempo de ejecución.
     */
    suspend fun execOpenSystemSetting(args: Map<String, String>): String {
        val category = args["category"] ?: "settings"
        val intent = android.content.Intent().apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            action = android.provider.Settings.ACTION_SETTINGS
            when (category) {
                "wifi" -> action = android.provider.Settings.ACTION_WIFI_SETTINGS
                "bluetooth" -> action = android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
                "display" -> action = android.provider.Settings.ACTION_DISPLAY_SETTINGS
                "sound" -> action = android.provider.Settings.ACTION_SOUND_SETTINGS
                "location" -> action = android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS
                "app_settings" -> action = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                "battery" -> action = android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS
                "storage" -> action = android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS
                "security" -> action = android.provider.Settings.ACTION_SECURITY_SETTINGS
                "date_time" -> action = android.provider.Settings.ACTION_DATE_SETTINGS
                // La página de inicio de configuración se abre de forma predeterminada.
            }
            if (category == "app_settings") {
                data = android.net.Uri.fromParts("package", context.packageName, null)
            }
        }
        return try {
            context.startActivity(intent)
            context.getString(R.string.tool_setting_opened, category)
        } catch (e: Exception) {
            context.getString(R.string.tool_setting_open_failed, e.message ?: "")
        }
    }

    /**
     * A-02: toggle Análisis de parámetros unificados de herramienta de clase. — compatibles action(on/off/status) con enabled(true/false).
     *
     * Devuelve cuando no se proporciona ninguno de los dos "status"(consulta de solo lectura). no lo hagas"voltear"——Android 10+ no se puede programar
     * Activa el interruptor WiFi/Bluetooth,Devolver la guía de estado con sinceridad,Quitar de la descripción de la herramienta"voltear"Compromiso.
     */
    private fun resolveToggleAction(args: Map<String, String>): String {
        val action = args["action"]?.lowercase()
        if (action == "on" || action == "off" || action == "status") return action
        return when (args["enabled"]?.toBooleanStrictOrNull()) {
            true -> "on"
            false -> "off"
            null -> "status"
        }
    }

    /**
     * Cambiar WiFi: Android 10+ no puede directamente toggle(WifiManager.setWifiEnabled En desuso).
     * action=status Leer el estado actual;action=on/off Vaya a la página de configuración de WiFi para permitir a los usuarios operar manualmente.
     * necesidad ACCESS_WIFI_STATE(estado de lectura,Permisos normales,No se requiere aplicación de tiempo de ejecución).
     */
    suspend fun execToggleWifi(args: Map<String, String>): String {
        val action = resolveToggleAction(args)
        val wifiManager = context.getSystemService(android.content.Context.WIFI_SERVICE)
            as android.net.wifi.WifiManager
        return when (action) {
            "status" -> {
                val enabled = wifiManager.isWifiEnabled
                if (enabled) context.getString(R.string.tool_wifi_status_on) else context.getString(R.string.tool_wifi_status_off)
            }
            "on", "off" -> {
                // Android 10+ No se puede cambiar directamente,Saltar a la página de configuración
                val intent = android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                context.getString(R.string.tool_wifi_toggle_redirect)
            }
            else -> context.getString(R.string.tool_error_invalid_action)
        }
    }

    /**
     * Activar y desactivar Bluetooth:action=status estado de lectura;action=on Saltar a la página de configuración(Android 10+ No se puede abrir directamente);
     * action=off llamar directamente BluetoothAdapter.disable()(Requiere permiso BLUETOOTH_ADMIN).
     *
     * M-TR4: Android 10+ de "off" La rama también salta a la página de configuración de Bluetooth.(Comportamiento simétrico al wifi.),
     * porque BluetoothAdapter.disable() Falla silenciosamente en Android 10+(No lanza una excepción pero no surte efecto).
     */
    @SuppressLint("MissingPermission")
    suspend fun execToggleBluetooth(args: Map<String, String>): String {
        val action = resolveToggleAction(args)
        val bluetoothManager = context.getSystemService(android.content.Context.BLUETOOTH_SERVICE)
            as android.bluetooth.BluetoothManager
        val adapter = bluetoothManager.adapter
        return when (action) {
            "status" -> {
                val enabled = adapter?.isEnabled == true
                if (enabled) context.getString(R.string.tool_bluetooth_status_on) else context.getString(R.string.tool_bluetooth_status_off)
            }
            "on" -> {
                // Android 10+ No se puede abrir directamente,Saltar a la página de configuración
                val intent = android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                context.getString(R.string.tool_bluetooth_on_redirect)
            }
            "off" -> {
                // M-TR4: Android 10+ de disable() falla silenciosamente,Vaya a la página de configuración para permitir a los usuarios cerrarla manualmente
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    context.getString(R.string.tool_bluetooth_off_redirect)
                } else {
                    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
                        context.checkSelfPermission(
                            android.Manifest.permission.BLUETOOTH_CONNECT,
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        adapter?.disable()
                    }
                    context.getString(R.string.tool_bluetooth_turning_off)
                }
            }
            else -> context.getString(R.string.tool_error_invalid_action)
        }
    }

    /**
     * Enviar correo electrónico:Abra la aplicación de correo mediante ACTION_SENDTO + mailto:,Destinatarios precargados/Tema/Texto.
     * Separe varios destinatarios con comas. Se devuelve un mensaje de error cuando no hay ninguna aplicación de correo.
     */
    suspend fun execSendEmail(args: Map<String, String>): String {
        val to = args["to"] ?: return context.getString(R.string.tool_error_missing_to)
        // L-TR9: Verificar el formato de correo electrónico del destinatario(sencillo regular,Verifique varios destinatarios uno por uno)
        val recipients = to.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (recipients.isEmpty()) return context.getString(R.string.tool_email_recipients_empty)
        val invalid = recipients.filter { !MusePatterns.EMAIL_REGEX.matches(it) }
        if (invalid.isNotEmpty()) {
            return context.getString(R.string.tool_email_invalid, invalid.joinToString(", "))
        }
        val subject = args["subject"] ?: ""
        val body = args["body"] ?: ""
        val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
            data = android.net.Uri.parse("mailto:")
            putExtra(android.content.Intent.EXTRA_EMAIL, recipients.toTypedArray())
            if (subject.isNotBlank()) putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
            if (body.isNotBlank()) putExtra(android.content.Intent.EXTRA_TEXT, body)
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(intent)
            context.getString(R.string.tool_email_opened, to)
        } catch (e: Exception) {
            context.getString(R.string.tool_email_not_found, e.message ?: "")
        }
    }

    /**
     * Obtener información de la batería:Porcentaje de batería(BATTERY_PROPERTY_CAPACITY)+ estado de carga(isCharging).
     * No se requieren permisos de tiempo de ejecución(BatteryManager Los servicios del sistema se pueden leer directamente.).
     */
    suspend fun execGetBatteryInfo(_args: Map<String, String>): String {
        val batteryManager = context.getSystemService(android.content.Context.BATTERY_SERVICE)
            as android.os.BatteryManager
        val level = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = batteryManager.isCharging
        return context.getString(
            R.string.tool_battery_info,
            level,
            if (charging) context.getString(R.string.tool_battery_charging) else context.getString(R.string.tool_battery_not_charging),
        )
    }

    /**
     * P2: Reciba notificaciones de aplicaciones recibidas recientemente.
     * Depende de la colección estática MuseNotificationListenerService;Regrese al indicador de inicio cuando no esté autorizado.
     */
    suspend fun execGetRecentNotifications(args: Map<String, String>): String {
        val limit = args["limit"]?.toIntOrNull()?.takeIf { it > 0 } ?: 20
        val pkg = args["package_name"]?.takeIf { it.isNotBlank() }
        val query = args["query"]?.takeIf { it.isNotBlank() }
        val unreadOnly = args["unread_only"]?.trim()?.equals("true", ignoreCase = true) == true
        val activeOnly = args["active_only"]?.trim()?.equals("true", ignoreCase = true) == true
        val records = MuseNotificationListenerService.getRecent(limit, pkg, query, unreadOnly, activeOnly)
        return if (records.isEmpty()) {
            if (MuseNotificationListenerService.isConnected()) {
                context.getString(R.string.tool_no_notifications)
            } else {
                context.getString(R.string.tool_notification_not_connected)
            }
        } else {
            val fmt = fmtTimeMin.get() ?: SimpleDateFormat("HH:mm", Locale.getDefault())
            records.joinToString("\n") { r ->
                val time = fmt.format(Date(r.timestamp))
                val source = r.appLabel.takeIf { it.isNotBlank() && it != r.packageName } ?: r.packageName
                val state = if (r.isActive) "active" else "removed"
                "[$source] $time ${r.title}: ${r.text} ($state)"
            }
        }
    }

    /** Abra la URL: verifique el esquema y ábralo con el navegador del sistema. */
    suspend fun execOpenUrl(args: Map<String, String>): String {
        val url = args["url"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_missing_param_url)
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return context.getString(R.string.tool_open_url_invalid_scheme)
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return resultOf {
            context.startActivity(intent)
            context.getString(R.string.tool_url_opened, url)
        }.onError { msg, _ -> Logger.w("ToolRegistry", "No se pudo abrir la URL: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_open_url_failed, url)
    }

    /** Listar aplicaciones instaladas,Admite filtrado, limitación del número y si se incluyen aplicaciones del sistema. */
    suspend fun execListInstalledApps(args: Map<String, String>): String {
        val filter = args["filter"]?.takeIf { it.isNotBlank() }?.lowercase()
        val limit = args["limit"]?.toIntOrNull()?.takeIf { it > 0 } ?: 20
        val includeSystem = args["include_system"]?.toBoolean() ?: false
        val pm = context.packageManager
        return resultOf {
            val apps = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
            val sb = StringBuilder(context.getString(R.string.tool_installed_apps_header, apps.size))
            var count = 0
            for (app in apps) {
                if (!includeSystem && (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) continue
                val label = pm.getApplicationLabel(app).toString()
                val pkg = app.packageName
                if (filter != null && !label.lowercase().contains(filter) && !pkg.lowercase().contains(filter)) continue
                sb.appendLine(context.getString(R.string.tool_installed_apps_item, label, pkg))
                count++
                if (count >= limit) break
            }
            if (count == 0) context.getString(R.string.tool_installed_apps_empty) else sb.toString().trimEnd()
        }.onError { msg, _ -> Logger.w("ToolRegistry", "No se pudieron enumerar las aplicaciones instaladas: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_installed_apps_failed)
    }

    /** Obtenga información de conexión de red actual. */
    suspend fun execGetNetworkInfo(_args: Map<String, String>): String {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        val activeNetwork = cm.activeNetwork
        val caps = activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val connected = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val type = when {
            caps == null -> "Sin red"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "datos celulares"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Otros"
        }
        val metered = cm.isActiveNetworkMetered
        return context.getString(
            R.string.tool_network_info,
            if (connected) context.getString(R.string.tool_yes) else context.getString(R.string.tool_no),
            type,
            if (metered) context.getString(R.string.tool_yes) else context.getString(R.string.tool_no),
        )
    }

    // ── v1.136: Agregar sistemas en lotes/Equipo/herramientas de codificación ───────────────────────────────────

    /** Obtenga información sobre el espacio de almacenamiento interno. */
    suspend fun execGetStorageInfo(_args: Map<String, String>): String {
        return resultOf {
            val stat = android.os.StatFs(context.filesDir.path)
            val total = stat.totalBytes
            val free = stat.freeBytes
            context.getString(R.string.tool_storage_info, formatBytes(total), formatBytes(total - free), formatBytes(free))
        }.onError { msg, _ -> Logger.w("ToolRegistry", "No se pudo obtener la información de almacenamiento: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_installed_apps_failed)
    }

    /** obtener memoria(RAM)información. */
    suspend fun execGetMemoryInfo(_args: Map<String, String>): String {
        return resultOf {
            val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            context.getString(
                R.string.tool_memory_info,
                formatBytes(info.totalMem),
                formatBytes(info.availMem),
                formatBytes(info.threshold),
                if (info.lowMemory) context.getString(R.string.tool_yes) else context.getString(R.string.tool_no),
            )
        }.onError { msg, _ -> Logger.w("ToolRegistry", "No se pudo obtener información de la memoria: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_installed_apps_failed)
    }

    /** Obtenga resolución de pantalla, densidad y frecuencia de actualización. */
    @Suppress("DEPRECATION")
    suspend fun execGetDisplayInfo(_args: Map<String, String>): String {
        return resultOf {
            val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
            val metrics = android.util.DisplayMetrics()
            wm.defaultDisplay.getMetrics(metrics)
            val refreshRate = wm.defaultDisplay.refreshRate
            context.getString(
                R.string.tool_display_info,
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                metrics.density,
                refreshRate,
            )
        }.onError { msg, _ -> Logger.w("ToolRegistry", "No se pudo obtener información de la pantalla: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_installed_apps_failed)
    }

    /** Obtenga el modelo de CPU y la cantidad de núcleos. */
    suspend fun execGetCpuInfo(_args: Map<String, String>): String {
        return resultOf {
            val processor = readProcCpuField("Hardware") ?: readProcCpuField("Processor") ?: "desconocido"
            val cores = Runtime.getRuntime().availableProcessors()
            context.getString(R.string.tool_cpu_info, processor, cores)
        }.onError { msg, _ -> Logger.w("ToolRegistry", "No se pudo obtener información de la CPU: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_installed_apps_failed)
    }

    /** Obtenga la lista de sensores del dispositivo. */
    suspend fun execGetSensorsList(args: Map<String, String>): String {
        val limit = args["limit"]?.toIntOrNull()?.takeIf { it > 0 } ?: 30
        return resultOf {
            val sm = context.getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
            val sensors = sm.getSensorList(android.hardware.Sensor.TYPE_ALL)
            if (sensors.isNullOrEmpty()) return@resultOf context.getString(R.string.tool_sensors_empty)
            val sb = StringBuilder(context.getString(R.string.tool_sensors_header, sensors.size))
            sensors.take(limit).forEach {
                sb.appendLine(context.getString(R.string.tool_sensors_item, it.name, it.vendor, it.version))
            }
            sb.toString().trimEnd()
        }.onError { msg, _ -> Logger.w("ToolRegistry", "No se pudo obtener la lista de sensores: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_installed_apps_failed)
    }

    /** Obtenga el brillo y el modo de pantalla actual. */
    suspend fun execGetBrightness(_args: Map<String, String>): String {
        return resultOf {
            val cr = context.contentResolver
            val brightness = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, -1)
            val mode = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, -1)
            val modeLabel = if (mode == android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
                context.getString(R.string.tool_brightness_auto)
            } else {
                context.getString(R.string.tool_brightness_manual)
            }
            context.getString(R.string.tool_brightness_info, brightness, modeLabel)
        }.onError { msg, _ -> Logger.w("ToolRegistry", "No se pudo obtener brillo: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_brightness_failed, "")
    }

    /**
     * A-03: Establecer el brillo de la pantalla del sistema — apoyo auto=true Habilitar brillo automático(ignorar value).
     * auto De forma predeterminada, presione value(0-255)Escribir brillo manual;Si no se proporciona ninguno, se devuelve un error de parámetro.
     */
    suspend fun execSetBrightness(args: Map<String, String>): String {
        if (args["auto"].equals("true", ignoreCase = true)) {
            return resultOf {
                android.provider.Settings.System.putInt(
                    context.contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
                )
                context.getString(R.string.tool_brightness_auto_set)
            }.onError { msg, _ -> Logger.w("ToolRegistry", "No se pudo habilitar el brillo automático: $msg") }
                .getOrNull() ?: context.getString(R.string.tool_brightness_failed, "auto")
        }
        val value = args["value"]?.toIntOrNull()?.coerceIn(0, 255)
            ?: return context.getString(R.string.tool_brightness_missing)
        return resultOf {
            android.provider.Settings.System.putInt(
                context.contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                value,
            )
            context.getString(R.string.tool_brightness_set, value)
        }.onError { msg, _ -> Logger.w("ToolRegistry", "No se pudo configurar el brillo: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_brightness_failed, value.toString())
    }

    /** Obtenga el volumen de transmisión de audio especificado. */
    suspend fun execGetVolume(args: Map<String, String>): String {
        val (streamType, streamLabel) = resolveAudioStream(args["stream"])
        return resultOf {
            val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            context.getString(R.string.tool_volume_info, streamLabel, am.getStreamVolume(streamType), am.getStreamMaxVolume(streamType))
        }.onError { msg, _ -> Logger.w("ToolRegistry", "No se pudo obtener el volumen: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_installed_apps_failed)
    }

    /** Establece el volumen de transmisión de audio especificado. */
    suspend fun execSetVolume(args: Map<String, String>): String {
        val (streamType, streamLabel) = resolveAudioStream(args["stream"])
        val rawValue = args["value"]?.toIntOrNull()
            ?: return context.getString(R.string.tool_volume_missing)
        val isPercent = args["percent"].equals("true", ignoreCase = true)
        return resultOf {
            val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            val max = am.getStreamMaxVolume(streamType)
            val index = if (isPercent) (rawValue * max / 100).coerceIn(0, max) else rawValue.coerceIn(0, max)
            am.setStreamVolume(streamType, index, android.media.AudioManager.FLAG_SHOW_UI)
            context.getString(R.string.tool_volume_set, streamLabel, index, max)
        }.onError { msg, _ -> Logger.w("ToolRegistry", "No se pudo configurar el volumen: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_installed_apps_failed)
    }

    /** Enciende y apaga la linterna.A-02: Parámetro habilitado para soporte;status Devuelve el último estado conocido de esta sesión.(sinceramente,ya no es permanente"si"). */
    suspend fun execToggleFlashlight(args: Map<String, String>): String {
        val action = resolveToggleAction(args)
        return resultOf {
            val cm = context.getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = cm.cameraIdList.firstOrNull()
                ?: return@resultOf context.getString(R.string.tool_flashlight_no_camera)
            val hasFlash = cm.getCameraCharacteristics(cameraId)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (!hasFlash) return@resultOf context.getString(R.string.tool_flashlight_unavailable)
            when (action) {
                "on" -> {
                    cm.setTorchMode(cameraId, true)
                    lastFlashlightOn = true
                    context.getString(R.string.tool_flashlight_on)
                }
                "off" -> {
                    cm.setTorchMode(cameraId, false)
                    lastFlashlightOn = false
                    context.getString(R.string.tool_flashlight_off)
                }
                "status" -> when (lastFlashlightOn) {
                    true -> context.getString(R.string.tool_flashlight_state, context.getString(R.string.tool_yes))
                    false -> context.getString(R.string.tool_flashlight_state, context.getString(R.string.tool_no))
                    null -> context.getString(R.string.tool_flashlight_state_unknown)
                }
                else -> context.getString(R.string.tool_flashlight_invalid_action)
            }
        }.onError { msg, _ -> Logger.w("ToolRegistry", "Error en la operación de la linterna: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_flashlight_unavailable)
    }

    companion object {
        /** A-02: El último estado conocido de la linterna en este proceso.(null = Esta sesión aún no ha sido operada.,Informe verazmente desconocido). */
        private var lastFlashlightOn: Boolean? = null
    }

    /** Controlar la vibración del dispositivo. */
    @Suppress("DEPRECATION")
    suspend fun execVibrate(args: Map<String, String>): String {
        val duration = args["duration_ms"]?.toLongOrNull()?.coerceIn(1, 3000) ?: 300
        return resultOf {
            val vibrator = context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    android.os.VibrationEffect.createOneShot(duration, android.os.VibrationEffect.DEFAULT_AMPLITUDE),
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
            context.getString(R.string.tool_vibrate, duration)
        }.onError { msg, _ -> Logger.w("ToolRegistry", "La vibración falló: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_installed_apps_failed)
    }

    /** Ponte al día/El nombre del paquete de aplicación en primer plano más reciente. */
    suspend fun execGetForegroundApp(_args: Map<String, String>): String {
        return resultOf {
            val usm = context.getSystemService(android.content.Context.USAGE_STATS_SERVICE)
                as android.app.usage.UsageStatsManager
            val end = System.currentTimeMillis()
            val start = end - 60_000
            val stats = usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, start, end)
            val recent = stats?.maxByOrNull { it.lastTimeUsed }
            context.getString(R.string.tool_foreground_app, recent?.packageName ?: "desconocido")
        }.onError { msg, _ -> Logger.w("ToolRegistry", "No se pudo obtener la aplicación en primer plano: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_foreground_app_unknown)
    }

    /** Obtenga información WiFi conectada actual. */
    @Suppress("DEPRECATION")
    suspend fun execGetWifiInfo(_args: Map<String, String>): String {
        return resultOf {
            val wm = context.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager

            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            val ssid = info.ssid?.removeSurrounding("\"") ?: "desconocido"
            val bssid = info.bssid ?: "desconocido"
            val level = android.net.wifi.WifiManager.calculateSignalLevel(info.rssi, 5)

            @Suppress("DEPRECATION")
            val ip = android.text.format.Formatter.formatIpAddress(info.ipAddress)
            context.getString(R.string.tool_wifi_info, ssid, bssid, level, ip)
        }.onError { msg, _ -> Logger.w("ToolRegistry", "No se pudo obtener información de WiFi: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_wifi_unavailable)
    }

    /** Obtenga la lista de dispositivos Bluetooth emparejados. */
    @Suppress("DEPRECATION")
    suspend fun execGetBluetoothDevices(_args: Map<String, String>): String {
        return resultOf {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                ?: return@resultOf context.getString(R.string.tool_bluetooth_unsupported)
            val stateLabel = if (adapter.isEnabled) {
                context.getString(R.string.tool_bluetooth_enabled)
            } else {
                context.getString(R.string.tool_bluetooth_disabled)
            }
            val devices = adapter.bondedDevices ?: emptySet()
            if (devices.isEmpty()) {
                "$stateLabel\n${context.getString(R.string.tool_bluetooth_empty)}"
            } else {
                val sb = StringBuilder(stateLabel)
                sb.appendLine()
                sb.appendLine(context.getString(R.string.tool_bluetooth_header, devices.size))
                devices.forEach {
                    sb.appendLine(context.getString(R.string.tool_bluetooth_item, it.name ?: "desconocido", it.address))
                }
                sb.toString().trimEnd()
            }
        }.onError { msg, _ -> Logger.w("ToolRegistry", "No se pudo obtener el dispositivo Bluetooth: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_bluetooth_unsupported)
    }

    /** Abra la interfaz de marcación y complete previamente el número de teléfono celular. */
    suspend fun execMakePhoneCall(args: Map<String, String>): String {
        val phone = args["phone"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_phone_call_missing)
        val intent = android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:$phone")).apply {
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
        val uri = if (!lat.isNullOrBlank() && !lng.isNullOrBlank()) {
            val label = if (!query.isNullOrBlank()) android.net.Uri.encode(query) else "$lat,$lng"
            "geo:$lat,$lng?q=$lat,$lng($label)"
        } else if (!query.isNullOrBlank()) {
            "geo:0,0?q=${android.net.Uri.encode(query)}"
        } else {
            return context.getString(R.string.tool_maps_missing)
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri)).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return resultOf {
            context.startActivity(intent)
            context.getString(R.string.tool_maps_opened, uri)
        }.onError { msg, _ -> Logger.w("ToolRegistry", "No se pudo abrir el mapa: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_open_url_failed, uri)
    }

    // ── Función auxiliar ─────────────────────────────────────────────────────────────

    /** Formatee una cantidad de bytes en una cadena legible por humanos. */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.size - 1) {
            value /= 1024
            unitIndex++
        }
        return String.format(Locale.getDefault(), "%.2f %s", value, units[unitIndex])
    }

    /** Lea los campos especificados de /proc/cpuinfo. */
    fun readProcCpuField(key: String): String? {
        return try {
            java.io.File("/proc/cpuinfo").useLines { lines ->
                lines.mapNotNull { line ->
                    val parts = line.split(":", limit = 2)
                    if (parts.size == 2 && parts[0].trim().equals(key, ignoreCase = true)) {
                        parts[1].trim()
                    } else {
                        null
                    }
                }.firstOrNull()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Analiza el tipo de transmisión de audio. */
    fun resolveAudioStream(name: String?): Pair<Int, String> {
        return when (name?.lowercase()) {
            "ring", "ringtone" -> android.media.AudioManager.STREAM_RING to context.getString(R.string.tool_volume_stream_ring)
            "alarm" -> android.media.AudioManager.STREAM_ALARM to context.getString(R.string.tool_volume_stream_alarm)
            "notification" -> android.media.AudioManager.STREAM_NOTIFICATION to context.getString(R.string.tool_volume_stream_notification)
            "call", "voice_call" -> android.media.AudioManager.STREAM_VOICE_CALL to context.getString(R.string.tool_volume_stream_call)
            "system" -> android.media.AudioManager.STREAM_SYSTEM to context.getString(R.string.tool_volume_stream_system)
            else -> android.media.AudioManager.STREAM_MUSIC to context.getString(R.string.tool_volume_stream_music)
        }
    }

    suspend fun execScreenTime(_args: Map<String, String>): String {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE)
            as android.app.usage.UsageStatsManager
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        val end = System.currentTimeMillis()
        val stats = usm.queryAndAggregateUsageStats(start, end)
        if (stats.isNullOrEmpty()) {
            return context.getString(R.string.tool_screen_time_unavailable)
        }
        val sorted = stats.values.sortedByDescending { it.totalTimeInForeground }
        val sb = StringBuilder(context.getString(R.string.tool_screen_time_header))
        val pm = context.packageManager
        for (stat in sorted.take(10)) {
            // M-TR1: Usar en su lugar resultOf{}(Lanzamiento correcto CancellationException)
            val name = resultOf {
                pm.getApplicationLabel(pm.getApplicationInfo(stat.packageName, 0)).toString()
            }.getOrNull() ?: stat.packageName
            val minutes = stat.totalTimeInForeground / 60000
            if (minutes > 0) {
                sb.appendLine(context.getString(R.string.tool_screen_time_item, name, minutes))
            }
        }
        return sb.toString().trimEnd()
    }
}
