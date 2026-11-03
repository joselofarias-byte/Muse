package io.zer0.muse.notification

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Servicio de escucha de notificaciones — Detecta notificaciones de otras aplicaciones como fuentes de eventos.
 *
 * Los usuarios deben autorizar en la configuración del sistema."Aviso de derechos de uso"para que surta efecto.
 * Después de la autorización,Cuando otras aplicaciones envían notificaciones,Este servicio captura y almacena notificaciones recientes.
 * LLM Las notificaciones recientes se pueden consultar a través de la herramienta get_recent_notifications.
 *
 * Nota:Esta clase tiene estática. StateFlow,La instancia de servicio en sí está vinculada al sistema./La desvinculación no afectará a las notificaciones recogidas.
 */
class MuseNotificationListenerService : NotificationListenerService() {

    companion object {
        // L1-1: Nombre del paquete del sistema filtrado,Evite asignar nuevos List
        private val IGNORED_PACKAGES: Set<String> = setOf("android", "com.android.systemui", "io.zer0.muse")

        // Reparación de auditoría (1.5): Nombre del paquete de notificación de alta sensibilidad — banco/pagar/Clase de código de verificación,El texto principal no se recoge directamente.
        // (Solo se conservan el nombre y la hora del paquete fuente,text marcador de posición),Prevenir código de verificación/Saldo/El agua se filtra a través del LLM.
        private val SENSITIVE_PACKAGES: Set<String> = setOf(
            // pagar/banco(doméstico común)
            "com.eg.android.AlipayGphone", "com.tencent.mm", "com.unionpay",
            "com.android.bankabc", "com.chinamworld.bocmbci", "com.icbc", "com.ccb.life",
            "com.cmbchina.ccd.pluto.cmbActivity", "com.android.citic", "com.spdbccc.app",
            // SMS/Clase de agregación de códigos de verificación
            "com.google.android.apps.messaging", "com.android.mms", "com.miui.securitycenter",
            "com.huawei.hwid", "com.oneplus.security",
            // B-29: Mensajería instantánea convencional, correo electrónico y redes sociales——texto libre(private Chat, chat grupal)Aunque la PII esté bloqueada, todavía no se puede bloquear,
            // El texto principal no se recoge directamente.,Sólo se conservan el nombre y la hora del paquete fuente.
            // IM/mensajería instantánea
            "com.tencent.mobileqq", "com.alibaba.android.rimet", "org.telegram.messenger",
            "com.slack", "com.tencent.tim", "com.immomo.momo", "com.tencent.wework",
            // Correo electrónico
            "com.google.android.gm", "com.microsoft.office.outlook",
            // sociales/vídeo corto
            "com.sina.weibo", "com.ss.android.ugc.aweme",
        )

        // La escucha de notificaciones no solo sirve al proceso actual;Conserva los últimos 200 artículos.,Aún se puede ver en páginas y herramientas después de reiniciar la aplicación.
        private const val MAX_RECENT_NOTIFICATIONS = 200
        private const val PREFS_NAME = "muse_notification_listener"
        private const val KEY_RECORDS = "records_json"
        private const val KEY_UNLOCKED_PACKAGES = "unlocked_packages"
        private const val TAG = "MuseNotifListener"
        private const val REBIND_BACKOFF_MS = 30_000L
        private val stateLock = Any()
        private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val persistenceMutex = Mutex()

        // Cuando los acontecimientos llegan rápidamente,Permitir que solo se descargue al disco la última instantánea,Evite que IO concurrentes vuelvan a escribir la lista anterior.
        private var persistenceSequence = 0L
        private var persistedSequence = 0L
        private var preferences: SharedPreferences? = null

        @Volatile
        private var serviceInstance: MuseNotificationListenerService? = null

        @Volatile
        private var initialized = false

        // Lista de notificaciones recientes(Conservar como máximo MAX_RECENT_NOTIFICATIONS elementos)
        private val _recentNotifications = MutableStateFlow<List<NotificationRecord>>(emptyList())
        val recentNotifications = _recentNotifications.asStateFlow()

        /**
         * v2.6.2(comentarios de los usuarios): El texto del aviso ya no insensibilizará la PII.
         *
         * fondo: Muse Es una aplicación local y los datos no pasan por el servidor del desarrollador; El envío del texto de notificación depende de
         * Proveedor configurado por el usuario (BYOK), el riesgo corre a cargo del usuario y la parte de la aplicación solo proporciona notificación de riesgo.
         * La implementación anterior coloca el código de verificación al recopilar/Reemplace el número de tarjeta, etc. con [REDACTED], provocando que el asistente no pueda recordar
         * El contenido específico de la notificación (como el código de verificación, el monto, la dirección), y“El asistente necesita conocer la notificación.”Contradictorio.
         *
         * Nota: Gao Sen Bao (WeChat)/alipay/SMS, etc).“El texto no se recopila de forma predeterminada”aún retenido,
         * El usuario lo abre paquete por paquete en la página de notificación (autorización de puente de mensajes); esta función solo maneja el procesamiento de texto después de la recopilación.
         */
        private fun scrubText(text: String): String = text

        // ¿Está conectado?(El usuario está autorizado)
        @Volatile
        private var connected = false
        fun isConnected() = connected

        // ── v1.0.92: Autorización de puente de mensajes(per-package) — Después de que el usuario enciende explícitamente,El texto de notificación del paquete.
        //    Retenido con enmascaramiento de PII(Los paquetes no autorizados de alta sensibilidad solo registran la fuente pero no el texto.).

        /** Una colección de nombres de paquetes autorizados para leer el texto. */
        private val unlockedPackages = MutableStateFlow<Set<String>>(emptySet())

        /** Estado de autorización para que la UI se suscriba. */
        val unlockedPackagesFlow: StateFlow<Set<String>> = unlockedPackages.asStateFlow()

        /** Si el paquete está autorizado a leer el texto. */
        fun isPackageUnlocked(pkg: String): Boolean = pkg in unlockedPackages.value

        /** Establezca la autorización de lectura de texto de un determinado paquete y consérvelo. */
        fun setPackageUnlocked(pkg: String, unlocked: Boolean) {
            if (pkg.isBlank()) return
            val updated = if (unlocked) unlockedPackages.value + pkg else unlockedPackages.value - pkg
            unlockedPackages.value = updated
            preferences?.edit()?.putStringSet(KEY_UNLOCKED_PACKAGES, updated)?.apply()
        }

        /** Solicita al servicio que resincronice las notificaciones actualmente existentes del sistema. */
        fun refreshActiveNotifications() {
            val service = serviceInstance ?: return
            persistenceScope.launch {
                runCatching {
                    val active = service.activeNotifications.orEmpty()
                    markRecordsNotActive(active.map(::sourceKey).toSet())
                    active.forEach(service::recordNotification)
                }.onFailure { error ->
                    Logger.w(TAG, "Error en la sincronización manual de las notificaciones actuales: ${error.message}", error)
                }
            }
        }

        /** Cree claves estables entre procesos basadas en objetos del sistema. */
        private fun sourceKey(sbn: StatusBarNotification): String = "${sbn.key}|${sbn.id}|${sbn.tag.orEmpty()}"

        /** Inicializar el almacenamiento persistente;Puede ser llamado primero por UI,No es necesario esperar a que el sistema vincule el servicio de escucha. */
        fun initialize(context: Context) {
            synchronized(stateLock) {
                if (initialized) return
                val prefs = context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                preferences = prefs
                val restored = prefs.getString(KEY_RECORDS, null)
                    ?.let { raw ->
                        runCatching {
                            AppJson.decodeFromString(
                                ListSerializer(NotificationRecord.serializer()),
                                raw,
                            )
                        }.onFailure { error ->
                            Logger.w(TAG, "Error al recuperar el registro de notificaciones,Borrar caché corrupto: ${error.message}", error)
                            prefs.edit().remove(KEY_RECORDS).apply()
                        }.getOrNull()
                    }
                    .orEmpty()
                _recentNotifications.value = normalize(restored)
                unlockedPackages.value = prefs.getStringSet(KEY_UNLOCKED_PACKAGES, emptySet())
                    ?.toSet()
                    .orEmpty()
                initialized = true
            }
        }

        /** Si la aplicación actual ya está en la lista de derechos de uso de notificaciones del sistema. */
        fun hasListenerAccess(context: Context): Boolean = runCatching {
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        }.onFailure { error ->
            Logger.w(TAG, "No se pudieron leer los derechos de uso de notificaciones: ${error.message}", error)
        }.getOrDefault(connected)

        /** Consultar notificaciones recientes(Llamado por ToolRegistry). */
        fun getRecent(
            limit: Int = 20,
            packageName: String? = null,
            query: String? = null,
            unreadOnly: Boolean = false,
            activeOnly: Boolean = false,
        ): List<NotificationRecord> {
            val normalizedQuery = query?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            return _recentNotifications.value
                .let { list ->
                    list.filter { record ->
                        (packageName.isNullOrBlank() || record.packageName == packageName) &&
                            (!unreadOnly || !record.isRead) &&
                            (!activeOnly || record.isActive) &&
                            (normalizedQuery == null || record.matches(normalizedQuery))
                    }
                }
                .take(limit.coerceIn(1, MAX_RECENT_NOTIFICATIONS))
        }

        /** El nombre del paquete fuente que ha aparecido hasta ahora.,Para uso en menús de filtro de UI. */
        fun getPackages(): List<String> = _recentNotifications.value
            .map { it.packageName }
            .distinct()
            .sorted()

        /** v1.0.92: Paquetes altamente confidenciales que han aparecido en registros de notificaciones recientes(Lista de UI de autorización de puente de mensajes). */
        fun getSensitivePackagesPresent(): List<String> = _recentNotifications.value
            .map { it.packageName }
            .filter { it in SENSITIVE_PACKAGES }
            .distinct()
            .sorted()

        /** Obtenga la clave estable de un solo registro,Compatible con versiones antiguas sin registros sourceKey. */
        fun keyOf(record: NotificationRecord): String = record.sourceKey
            .takeIf { it.isNotBlank() }
            ?: "legacy:${record.packageName}:${record.timestamp}:${record.title}:${record.text}"

        /** Marque elementos individuales como leídos. */
        fun markRead(record: NotificationRecord) {
            updateRecords { current ->
                current.map { item ->
                    if (keyOf(item) == keyOf(record)) item.copy(isRead = true) else item
                }
            }
        }

        /** Marcar todo como leído. */
        fun markAllRead() {
            updateRecords { current -> current.map { it.copy(isRead = true) } }
        }

        /** Eliminar un solo registro. */
        fun delete(record: NotificationRecord) {
            updateRecords { current -> current.filterNot { keyOf(it) == keyOf(record) } }
        }

        /** Exportar registros de notificaciones actualmente guardados JSON,Escrito por la interfaz de usuario a través de SAF en la ubicación seleccionada por el usuario. */
        fun exportJson(): String = AppJson.encodeToString(
            ListSerializer(NotificationRecord.serializer()),
            _recentNotifications.value,
        )

        /** Borrar historial de notificaciones. */
        fun clearAll() {
            updateRecords { emptyList() }
        }

        private fun markRecordsNotActive(activeKeys: Set<String>) {
            updateRecords { current ->
                current.map { record ->
                    if (record.isActive && keyOf(record) !in activeKeys) {
                        record.copy(isActive = false, removedAt = System.currentTimeMillis())
                    } else {
                        record
                    }
                }
            }
        }

        private fun updateRecords(transform: (List<NotificationRecord>) -> List<NotificationRecord>) {
            val snapshot = synchronized(stateLock) {
                val updated = normalize(transform(_recentNotifications.value))
                _recentNotifications.value = updated
                updated
            }
            persistAsync(snapshot)
        }

        private fun persistAsync(snapshot: List<NotificationRecord>) {
            val prefs = preferences ?: return
            val encoded = runCatching {
                AppJson.encodeToString(ListSerializer(NotificationRecord.serializer()), snapshot)
            }.getOrElse { error ->
                Logger.w(TAG, "Falló la serialización del registro de notificación: ${error.message}", error)
                return
            }
            val sequence = synchronized(stateLock) {
                persistenceSequence += 1
                persistenceSequence
            }
            persistenceScope.launch {
                persistenceMutex.withLock {
                    // IO La programación puede estar fuera de orden,Incluso si la instantánea anterior se bloquea más tarde, no puede sobrescribir la nueva instantánea.
                    if (sequence < persistedSequence) return@withLock
                    if (prefs.edit().putString(KEY_RECORDS, encoded).commit()) {
                        persistedSequence = sequence
                    }
                }
            }
        }

        private fun normalize(records: List<NotificationRecord>): List<NotificationRecord> = records
            .distinctBy(::keyOf)
            .sortedByDescending { it.timestamp }
            .take(MAX_RECENT_NOTIFICATIONS)
    }

    private var lastRebindRequestAt = 0L

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this
        initialize(this)
    }

    override fun onDestroy() {
        if (serviceInstance === this) serviceInstance = null
        connected = false
        super.onDestroy()
    }

    override fun onListenerConnected() {
        connected = true
        Logger.i(TAG, "Notificar que el servicio de escucha está conectado.")
        // Cuando el sistema vuelva a vincular el servicio, compensará las notificaciones que aún existen.,Evite páginas en blanco durante mucho tiempo durante la reconexión de autorización.
        runCatching {
            val active = activeNotifications.orEmpty()
            markRecordsNotActive(active.map(::sourceKey).toSet())
            active.forEach(::recordNotification)
        }.onFailure { error ->
            Logger.w(TAG, "No se pudo restaurar la notificación actual: ${error.message}", error)
        }
    }

    override fun onListenerDisconnected() {
        connected = false
        Logger.w(TAG, "El servicio de escucha de notificaciones ha sido desconectado.")
        // Android Puede deberse al reciclaje del sistema./Cambio de permisos y desvinculación activa;Solicitar una nueva vinculación del sistema,Pero ¿retrocedes los registros de prevención?/IPC tormenta.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val now = System.currentTimeMillis()
            if (now - lastRebindRequestAt >= REBIND_BACKOFF_MS) {
                lastRebindRequestAt = now
                runCatching {
                    requestRebind(ComponentName(this, MuseNotificationListenerService::class.java))
                }.onFailure { error ->
                    Logger.w(TAG, "La solicitud para notificar al servicio de escucha que vuelva a vincularse falló.: ${error.message}", error)
                }
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching { recordNotification(sbn) }
            .onFailure { error -> Logger.w(TAG, "No se pudo manejar la notificación: ${error.message}", error) }
    }

    private fun recordNotification(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        val extras = notification.extras
        val pkg = sbn.packageName

        // Filtrar la interfaz de usuario del sistema y las autonotificaciones,evitar el ruido
        if (pkg in IGNORED_PACKAGES) return

        val rawTitle = firstNonBlank(
            extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
        val rawText = firstNonBlank(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.joinToString("\n"),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
        )

        // De forma predeterminada, los nombres de paquetes de alta sensibilidad solo recuerdan la fuente y no el texto.;Habilitado explícitamente por el usuario"puente de mensajes"Después de la autorización,
        // El texto del paquete está protegido por enmascaramiento PII.(Escondido sin autorización). El resto del texto de la notificación estará enmascarado como PII.
        val bodyHidden = pkg in SENSITIVE_PACKAGES && !isPackageUnlocked(pkg)
        val (safeTitle, safeText) = if (bodyHidden) {
            "[Notificación sensible]" to "(Texto oculto,Fuente: $pkg)"
        } else {
            scrubText(rawTitle) to scrubText(rawText)
        }
        val appLabel = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.onFailure { error -> Logger.d(TAG, "No se pudo leer el nombre de la aplicación de notificación($pkg): ${error.message}") }
            .getOrDefault(pkg)

        val record = NotificationRecord(
            packageName = pkg,
            appLabel = appLabel,
            title = safeTitle,
            text = safeText,
            timestamp = sbn.postTime,
            sourceKey = sourceKey(sbn),
            channelId = notification.channelId,
            category = notification.category,
            groupKey = sbn.groupKey,
            priority = notification.priority,
            visibility = notification.visibility,
            isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            isClearable = sbn.isClearable,
            isActive = true,
            removedAt = null,
            removedReason = null,
            isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0,
        )

        val snapshot = synchronized(stateLock) {
            val recordKey = keyOf(record)
            val old = _recentNotifications.value.firstOrNull { keyOf(it) == recordKey }
            val preservedRead = old?.takeIf {
                it.title == record.title && it.text == record.text
            }?.isRead ?: false
            val updatedRecord = record.copy(isRead = preservedRead)
            val updated = normalize(
                listOf(updatedRecord) + _recentNotifications.value.filterNot { keyOf(it) == recordKey },
            )
            _recentNotifications.value = updated
            updated
        }
        persistAsync(snapshot)
    }

    private fun firstNonBlank(vararg values: String?): String = values.firstOrNull { !it.isNullOrBlank() }.orEmpty()

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        markNotificationRemoved(sbn, null)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        markNotificationRemoved(sbn, reason)
    }

    private fun markNotificationRemoved(sbn: StatusBarNotification, reason: Int?) {
        val removedKey = sourceKey(sbn)
        updateRecords { current ->
            current.map { record ->
                if (keyOf(record) == removedKey) {
                    record.copy(
                        isActive = false,
                        removedAt = System.currentTimeMillis(),
                        removedReason = reason,
                    )
                } else {
                    record
                }
            }
        }
    }
}

/**
 * Registro único de notificación(Para que las herramientas LLM lean).
 */
@Serializable
data class NotificationRecord(
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long,
    /** sistema sbn.key,Se utiliza para actualizar la deduplicación.;Las claves de compatibilidad se generan a partir del contenido cuando los registros antiguos están vacíos. */
    val sourceKey: String = "",
    /** ¿Se ha visto en Muse? */
    val isRead: Boolean = false,
    /** Si se trata de una notificación permanente. */
    val isOngoing: Boolean = false,
    /** Nombre de la aplicación legible por humanos,Recurra al nombre del paquete si no se puede recuperar. */
    val appLabel: String = "",
    /** Android ID del canal de notificación. */
    val channelId: String? = null,
    /** Android Categoría de notificación. */
    val category: String? = null,
    /** Clave de agrupación de sistemas. */
    val groupKey: String? = null,
    /** Prioridad de notificación y visibilidad. */
    val priority: Int = Notification.PRIORITY_DEFAULT,
    val visibility: Int = Notification.VISIBILITY_PRIVATE,
    /** Si es un resumen del grupo de notificación. */
    val isGroupSummary: Boolean = false,
    /** Si el sistema permite a los usuarios borrar. */
    val isClearable: Boolean = true,
    /** Si la notificación aún existe en la barra de notificaciones del sistema. */
    val isActive: Boolean = true,
    /** Tiempo de eliminación y motivo de eliminación del sistema(Los datos antiguos están vacíos.). */
    val removedAt: Long? = null,
    val removedReason: Int? = null,
)

private fun NotificationRecord.matches(query: String): Boolean = packageName.lowercase().contains(query) ||
    appLabel.lowercase().contains(query) ||
    title.lowercase().contains(query) ||
    text.lowercase().contains(query) ||
    channelId.orEmpty().lowercase().contains(query) ||
    category.orEmpty().lowercase().contains(query)
