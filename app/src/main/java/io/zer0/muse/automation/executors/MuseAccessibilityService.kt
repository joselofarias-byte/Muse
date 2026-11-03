package io.zer0.muse.automation.executors

/**
 * Muse El componente principal de la aplicación para servicios de accesibilidad.
 *
 * El nombre del componente permanece en [io.zer0.muse.automation.executors.MuseAccessibilityService],
 * De esta forma, los usuarios que hayan activado la versión anterior del servicio en la configuración del sistema pueden seguir utilizando el mismo elemento de autorización.
 * La implementación específica de lectura de pantalla, gestos y entradas están unificadas y heredadas del módulo :accessibility,Evite los módulos principales de aplicaciones y bibliotecas
 * Registrar un servicio cada uno da como resultado“El sistema está encendido pero la aplicación está comprobando una instancia diferente.”.
 */
class MuseAccessibilityService : io.zer0.muse.accessibility.MuseAccessibilityService() {
    companion object {
        /** Instancia principal actual del servicio de accesibilidad de aplicaciones;Las instancias de clase base se mantienen durante el ciclo de vida del sistema. */
        val instance: MuseAccessibilityService?
            get() = io.zer0.muse.accessibility.MuseAccessibilityService.instance as? MuseAccessibilityService

        /** Si el servicio ha completado la conexión del sistema. */
        fun isConnected(): Boolean = instance != null
    }
}
