package io.zer0.muse.web

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import io.zer0.common.Logger
import io.zer0.common.resultOf

/**
 * Phase 8.11: mDNS descubrimiento de servicios(NSD — Network Service Discovery).
 *
 * Regístrate dentro de LAN `_http._tcp` servicio,Permitir el paso de dispositivos bajo el mismo Wi-Fi
 * Bonjour/Avahi/NSD El navegador descubrió el servidor web muse.
 *
 * darse cuenta:
 *  - [NsdManager.registerService] Servicio de registro(API 16+,No se requieren permisos adicionales)
 *  - Nombre del servicio "muse-web",Tipo "_http._tcp",El puerto es consistente con WebServer.
 *  - [unregister] Cerrar sesión cuando el servidor web se detenga
 *
 * Límite:
 *  - NSD Puede estar restringido por la política de ahorro de energía en algunas ROM nacionales(No detectable en segundo plano)
 *  - Solo detectable dentro de la misma subred(No visible a través de VLAN/VPN)
 *  - El registro del servicio es asíncrono.,[register] Regresar no significa que estés en línea
 */
class MdnsService(private val context: Context) {

    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var registrationListener: NsdManager.RegistrationListener? = null

    /** Nombre del servicio(El nombre mostrado en la LAN). */
    private var registeredName: String? = null

    /**
     * Registre el servicio mDNS.
     * @param port WebServer puerto de escucha
     * @param serviceName Nombre del servicio(Predeterminado "muse-web")
     */
    fun register(port: Int, serviceName: String = DEFAULT_SERVICE_NAME) {
        // Primero anule el registro del anterior(Evite registros repetidos que provoquen que NsdManager informe errores)
        unregister()

        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = SERVICE_TYPE
            this.port = port
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredName = info.serviceName
                Logger.i(TAG, "mDNS El servicio esta registrado: ${info.serviceName} :${info.port}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Logger.e(TAG, "mDNS Registro fallido: errorCode=$errorCode, service=${info.serviceName}")
                registrationListener = null
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Logger.i(TAG, "mDNS El servicio ha sido cerrado: ${info.serviceName}")
                registeredName = null
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Logger.w(TAG, "mDNS Error al cerrar sesión: errorCode=$errorCode")
            }
        }

        registrationListener = listener
        resultOf {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onError { msg, _ ->
            Logger.e(TAG, "registerService anormal: $msg")
            registrationListener = null
        }
    }

    /** Cerrar sesión en el servicio mDNS(idempotente,Ninguna operación cuando no está registrado). */
    fun unregister() {
        registrationListener?.let { listener ->
            resultOf { nsdManager.unregisterService(listener) }
            registrationListener = null
            registeredName = null
        }
    }

    companion object {
        private const val TAG = "MdnsService"
        private const val DEFAULT_SERVICE_NAME = "muse-web"

        /** NSD Tipo de servicio(HTTP over TCP). */
        private const val SERVICE_TYPE = "_http._tcp."
    }
}
