package io.zer0.muse.tools.system

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import io.zer0.common.Logger
import io.zer0.muse.accessibility.IAccessibilityProvider
import io.zer0.muse.automation.executors.MuseAccessibilityService

/**
 * P3-3: Cliente del Servicio de Accesibilidad — Encuadernación bidireccional(Se prefiere proveedor independiente,Servicios en la aplicación).
 *
 * v2.2.1 El servicio de accesibilidad admite dos métodos de alojamiento:
 *  1. **independiente Provider APK**(io.zer0.muse.a11y,Puente AIDL de nivel de firma):
 *     - Los componentes del servicio de accesibilidad están separados del ciclo de vida principal de la aplicación.,Actualización de la aplicación principal/La reinstalación no interrumpe la autorización.;
 *     - Vincularlo a través de bindService [A11yBridgeService] Obtener [IAccessibilityProvider] agente;
 *  2. **Servicios en la aplicación**(MuseAccessibilityService,instancia estática):
 *     - Ruta de autorización de la versión anterior,Cuando el proveedor no está instalado;Los usuarios autorizados pueden seguir usándolo sin preocupaciones.
 *
 * ruta de llamada([withProvider]):Provider El agente está disponible y autorizado. → A lo largo del proceso AIDL;
 * De lo contrario, recurra a la instancia estática en la aplicación. Los valores predeterminados seguros se devuelven en caso de falla de cualquier fuente(false / ""),No se lanza ninguna excepción.
 *
 * Responsabilidades:
 *  1. Compruebe si los servicios de accesibilidad están habilitados en la configuración del sistema([isEnabled],Cubre dos registros de autorización.)
 *  2. Exponer operaciones de UI API(getPageInfo/click/swipe/setText/screenshot espera)
 *  3. Guiar a los usuarios a [openAccessibilitySettings] Habilitar servicio
 *
 * premisa de llamada:
 *  - Los usuarios deben ir a Configuración → Autorizar el servicio correspondiente en "Accesibilidad"(Proveedor independiente o servicio en la aplicación)
 *  - Cuando no está autorizado o el servicio no está funcionando,Todas las operaciones de la interfaz de usuario devuelven valores predeterminados seguros
 */
class AccessibilityClient(private val context: Context) {
    companion object {
        private const val TAG = "AccessibilityClient"

        /** Nombre de clase de servicio estable registrado en el manifiesto principal de la aplicación,Se utiliza para hacer coincidir los registros de autorización del sistema. */
        val SERVICE_CLASS_NAME: String = MuseAccessibilityService::class.java.name

        /** v2.2.1: El nombre del paquete del proveedor independiente de accesibilidad.(publicar/La detección de instalación se comparte con la visibilidad del paquete.). */
        const val PROVIDER_PACKAGE = "io.zer0.muse.a11y"

        /** v2.2.1: Componente de servicio puente AIDL independiente del proveedor(Barandilla de permisos a nivel de firma). */
        const val PROVIDER_BRIDGE_CLASS = "io.zer0.muse.a11y.A11yBridgeService"

        /** v2.2.1: Componente de servicio de accesibilidad independiente del proveedor(Se utiliza para comparar registros de autorización del sistema). */
        const val PROVIDER_SERVICE_CLASS = "io.zer0.muse.a11y.A11yProviderAccessibilityService"

        /**
         * Determine si la información de servicio devuelta por el sistema es el servicio de accesibilidad de destino.
         * Algunas ROM devolverán el nombre de la clase como un nombre relativo o un nombre corto sin el prefijo del nombre del paquete.,Unificado aquí.
         */
        internal fun matchesServiceInfo(
            serviceInfo: ServiceInfo?,
            appPackageName: String,
            expectedClassName: String = SERVICE_CLASS_NAME,
        ): Boolean {
            if (serviceInfo == null || serviceInfo.packageName != appPackageName) return false
            return normalizeClassName(appPackageName, serviceInfo.name) ==
                normalizeClassName(appPackageName, expectedClassName)
        }

        /**
         * Leer compatible Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES resultado.
         * Utilice análisis de cadenas simples,No depende de la implementación OEM de la lista AccessibilityManager para mantener la coherencia.
         */
        internal fun containsEnabledService(
            rawValue: String?,
            appPackageName: String,
            expectedClassName: String = SERVICE_CLASS_NAME,
        ): Boolean {
            val expected = normalizeClassName(appPackageName, expectedClassName)
            return rawValue.orEmpty().split(':').any { raw ->
                val value = raw.trim()
                val separator = value.indexOf('/')
                if (separator <= 0 || separator == value.lastIndex) return@any false
                val packageName = value.substring(0, separator)
                val className = normalizeClassName(packageName, value.substring(separator + 1))
                packageName == appPackageName && className == expected
            }
        }

        private fun normalizeClassName(packageName: String, rawClassName: String?): String {
            val className = rawClassName?.trim().orEmpty()
            return when {
                className.isBlank() -> ""
                className.startsWith('.') -> packageName + className
                '.' !in className -> "$packageName.$className"
                else -> className
            }
        }
    }

    // ── Enlace de proveedor independiente(v2.2.1) ────────────────────────────────────────────

    /** AIDL agente(onServiceConnected Valor de publicación;binder muerte/Borrar cuando está desconectado). */
    @Volatile private var providerProxy: IAccessibilityProvider? = null

    /** ¿Se ha iniciado la vinculación?(evitar la duplicación bindService;Restablecer después de un error de vinculación). */
    @Volatile private var providerBindRequested = false

    private val providerConnection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val proxy = IAccessibilityProvider.Stub.asInterface(service)
                providerProxy = proxy
                runCatching {
                    service?.linkToDeath(
                        android.os.IBinder.DeathRecipient {
                            providerProxy = null
                            providerBindRequested = false
                        },
                        0,
                    )
                }.onFailure { Logger.w(TAG, "Error en la supervisión de la muerte de la carpeta del proveedor: ${it.message}") }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                providerProxy = null
            }

            override fun onBindingDied(name: ComponentName?) {
                providerProxy = null
                providerBindRequested = false
            }
        }

    /** ¿Está instalado el APK del proveedor independiente?(Los servicios dentro de la aplicación todavía están disponibles cuando no están instalados). */
    fun isProviderInstalled(): Boolean = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(PROVIDER_PACKAGE, 0)
    }.isSuccess

    /** Iniciar un enlace de proveedor(idempotente;Sólo si APK está instalado). */
    private fun ensureProviderBound() {
        if (providerBindRequested) return
        if (!isProviderInstalled()) return
        synchronized(this) {
            if (providerBindRequested) return
            val intent = Intent().apply { setClassName(PROVIDER_PACKAGE, PROVIDER_BRIDGE_CLASS) }
            val ok =
                runCatching {
                    context.bindService(intent, providerConnection, Context.BIND_AUTO_CREATE)
                }.onFailure {
                    Logger.w(TAG, "No se pudo vincular al proveedor independiente: ${it.message}")
                }.getOrDefault(false)
            if (ok) providerBindRequested = true
        }
    }

    // ── Consulta de estado ──────────────────────────────────────────────────────────────

    /**
     * Si los servicios de accesibilidad están habilitados en la configuración del sistema.
     * Devuelve cuando se autoriza el proveedor independiente o el servicio en la aplicación true(Durante el periodo migratorio conviven dos conjuntos de autorizaciones).
     */
    fun isEnabled(): Boolean = isServiceEnabledInSystem(context.packageName, SERVICE_CLASS_NAME) ||
        isServiceEnabledInSystem(PROVIDER_PACKAGE, PROVIDER_SERVICE_CLASS)

    private fun isServiceEnabledInSystem(packageName: String, className: String): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val managerResult =
            runCatching {
                am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    ?.any { info ->
                        matchesServiceInfo(info.resolveInfo?.serviceInfo, packageName, className)
                    } == true
            }.onFailure {
                // Algunas ROM personalizadas generarán una excepción al acceder a la lista de servicios de accesibilidad.,Continúe hasta Reserva de configuración segura.
                Logger.w(TAG, "No se pudo leer la lista de servicios de accesibilidad del sistema: ${it.message}")
            }.getOrDefault(false)
        if (managerResult) return true

        val rawEnabledServices =
            runCatching {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                )
            }.onFailure {
                Logger.w(TAG, "No se pudo leer la configuración del servicio de accesibilidad habilitada: ${it.message}")
            }.getOrNull()
        return containsEnabledService(rawEnabledServices, packageName, className)
    }

    /** ¿Está actualmente conectado?(Disponible como proveedor independiente o como cualquier instancia del servicio en la aplicación). */
    fun isConnected(): Boolean {
        ensureProviderBound()
        val remoteConnected =
            providerProxy?.let { proxy ->
                runCatching { proxy.isAccessibilityServiceEnabled() }.getOrDefault(false)
            } == true
        return remoteConnected || MuseAccessibilityService.instance?.isAccessibilityServiceEnabled() == true
    }

    // ── UI Operación API(Enrutamiento dual,Volver a valores predeterminados seguros en caso de falla) ────────────────────────────

    suspend fun getPageInfo(): String = withProvider(defaultOnError = "") { it.getUiHierarchy() }

    suspend fun click(x: Int, y: Int): Boolean = withProvider(defaultOnError = false) { it.performClick(x, y) }

    suspend fun longPress(x: Int, y: Int): Boolean = withProvider(defaultOnError = false) { it.performLongPress(x, y) }

    suspend fun globalAction(actionId: Int): Boolean = withProvider(defaultOnError = false) { it.execGlobalAction(actionId) }

    suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long): Boolean =
        withProvider(defaultOnError = false) { it.performSwipe(startX, startY, endX, endY, duration) }

    suspend fun findFocusedNodeId(): String = withProvider(defaultOnError = "") { it.findFocusedNodeId() }

    suspend fun setText(nodeId: String, text: String): Boolean = withProvider(defaultOnError = false) { it.setTextOnNode(nodeId, text) }

    suspend fun screenshot(path: String, format: String = "PNG"): Boolean =
        withProvider(defaultOnError = false) { it.takeScreenshot(path, format) }

    /**
     * v2.6.13: Si el servicio actual realmente tiene la capacidad de realizar capturas de pantalla.
     *
     * La antigua implementación se llamó screenshotCapabilityFailed(), confiar en“Si la escritura reflexiva de campos ocultos genera un error”contrarrestar;
     * Ese campo no existe en AOSP, por lo que siempre devuelve verdadero, y lo que ve la capa de herramienta es“No se puede tomar captura de pantalla”.
     * Ahora pregunte directamente el bit de capacidad informado por el sistema.
     *
     * Provider Si la ruta (proceso independiente) no puede obtener la información del servicio local, se procesará como disponible; si la llamada falla, se devolverá un código de error.
     */
    suspend fun canTakeScreenshot(): Boolean = MuseAccessibilityService.instance?.canTakeScreenshot() ?: true

    suspend fun currentActivityName(): String = withProvider(defaultOnError = "") { it.getCurrentActivityName() }

    // ── herramientas internas ──────────────────────────────────────────────────────────────

    /**
     * Llamada de enrutamiento bidireccional:
     *  1. El proxy del proveedor independiente está disponible y su servicio está conectado → Llamadas AIDL de procesos cruzados;
     *  2. De lo contrario, recurra a la instancia estática en la aplicación.;
     *  3. No hay ninguno disponible o se devuelve una excepción al llamar [defaultOnError](no tirar,Garantizar la estabilidad de la cadena de herramientas).
     */
    private suspend fun <T> withProvider(defaultOnError: T, block: (A11yOps) -> T): T {
        ensureProviderBound()
        providerProxy?.let { proxy ->
            val remoteEnabled = runCatching { proxy.isAccessibilityServiceEnabled() }.getOrDefault(false)
            if (remoteEnabled) {
                val result = runCatching { block(RemoteOps(proxy)) }
                result.getOrNull()?.let { return it }
                // binder muerte/excepción remota:Bórralo para poder volver a vincularlo la próxima vez.,Servicio alternativo en la aplicación
                providerProxy = null
                providerBindRequested = false
                Logger.w(TAG, "Provider llamada fallida,Servicio alternativo en la aplicación: ${result.exceptionOrNull()?.message}")
            }
        }

        val service = MuseAccessibilityService.instance ?: return defaultOnError
        return runCatching { block(LocalOps(service)) }.getOrElse { e ->
            Logger.w(TAG, "Error en la llamada de servicio: ${e.message}")
            defaultOnError
        }
    }

    /** superficie operativa interna — Bloquear la \"independencia\" Provider(AIDL agente)” y “servicios en la aplicación(instancia estática)\"Diferencia. */
    internal interface A11yOps {
        fun isAccessibilityServiceEnabled(): Boolean

        fun getCurrentActivityName(): String

        fun getUiHierarchy(): String

        fun performClick(x: Int, y: Int): Boolean

        fun performLongPress(x: Int, y: Int): Boolean

        fun execGlobalAction(actionId: Int): Boolean

        fun performSwipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long): Boolean

        fun findFocusedNodeId(): String

        fun setTextOnNode(nodeId: String, text: String): Boolean

        fun takeScreenshot(path: String, format: String): Boolean
    }

    /** Implementación de servicios en la aplicación(Mismo proceso de instancia estática). */
    private class LocalOps(private val service: MuseAccessibilityService) : A11yOps {
        override fun isAccessibilityServiceEnabled(): Boolean = service.isAccessibilityServiceEnabled()

        override fun getCurrentActivityName(): String = service.getCurrentActivityName()

        override fun getUiHierarchy(): String = service.getUiHierarchy()

        override fun performClick(x: Int, y: Int): Boolean = service.performClick(x, y)

        override fun performLongPress(x: Int, y: Int): Boolean = service.performLongPress(x, y)

        override fun execGlobalAction(actionId: Int): Boolean = service.execGlobalAction(actionId)

        override fun performSwipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long): Boolean =
            service.performSwipe(startX, startY, endX, endY, duration)

        override fun findFocusedNodeId(): String = service.findFocusedNodeId()

        override fun setTextOnNode(nodeId: String, text: String): Boolean = service.setTextOnNode(nodeId, text)

        override fun takeScreenshot(path: String, format: String): Boolean = service.takeScreenshot(path, format)
    }

    /** Implementación de proveedor independiente(Proceso cruzado AIDL;performGlobalAction ←→ execGlobalAction mapeo de nombres). */
    private class RemoteOps(private val provider: IAccessibilityProvider) : A11yOps {
        override fun isAccessibilityServiceEnabled(): Boolean = provider.isAccessibilityServiceEnabled

        override fun getCurrentActivityName(): String = provider.currentActivityName

        override fun getUiHierarchy(): String = provider.uiHierarchy

        override fun performClick(x: Int, y: Int): Boolean = provider.performClick(x, y)

        override fun performLongPress(x: Int, y: Int): Boolean = provider.performLongPress(x, y)

        override fun execGlobalAction(actionId: Int): Boolean = provider.performGlobalAction(actionId)

        override fun performSwipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long): Boolean =
            provider.performSwipe(startX, startY, endX, endY, duration)

        override fun findFocusedNodeId(): String = provider.findFocusedNodeId()

        override fun setTextOnNode(nodeId: String, text: String): Boolean = provider.setTextOnNode(nodeId, text)

        override fun takeScreenshot(path: String, format: String): Boolean = provider.takeScreenshot(path, format)
    }

    /** Abra la página de configuración de accesibilidad del sistema(Guiar a los usuarios para habilitar los servicios.). */
    fun openAccessibilitySettings() {
        val intent =
            android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
    }
}
