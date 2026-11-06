package io.zer0.muse.a11y

/**
 * v2.2.1: Componente de servicio de accesibilidad del Proveedor independiente de accesibilidad.
 *
 * Heredar la implementación compartida del módulo :accessibility(leer pantalla/gesto/entrada/captura de pantalla),Los nombres de los componentes son independientes de la aplicación principal.,
 * Los elementos de autorización en la configuración del sistema están vinculados al ciclo de vida de este APK. —— Actualización de la aplicación principal/La reinstalación no afectará su funcionamiento.
 *
 * Las instancias de servicio están representadas por clases base. companion(`MuseAccessibilityService.instance`)Regístrate dentro de este proceso,
 * [A11yBridgeService] Reenvíe llamadas AIDL a la aplicación principal a través de esta instancia.
 */
class A11yProviderAccessibilityService : io.zer0.muse.accessibility.MuseAccessibilityService()
