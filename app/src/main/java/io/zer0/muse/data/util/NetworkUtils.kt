package io.zer0.muse.data.util

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Fase C: Herramientas de red — Obtenga la dirección IPv4 de LAN del dispositivo.
 *
 * Se utiliza para la sección WebServer para mostrar la dirección de acceso real,Evite que los usuarios verifiquen manualmente la IP.
 *
 * darse cuenta: atravesar todo NetworkInterface,filtrar:
 *  - isUp
 *  - !isLoopback
 *  - inetAddress si Inet4Address
 * Devuelve primero la dirección de wlan0/eth0,Evite las interfaces virtuales(Como por ejemplo docker0).
 */
object NetworkUtils {

    fun getLocalIpAddress(): String? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces()
                .toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedByDescending { it.name == "wlan0" || it.name == "eth0" }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull()
                ?.hostAddress
        }.getOrNull()
    }
}
