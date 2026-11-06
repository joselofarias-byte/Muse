package io.zer0.muse.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.zer0.common.Logger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * v1.0.15: Oyente de estado de la red.
 *
 * Basado en [ConnectivityManager.NetworkCallback] Supervise los cambios en la disponibilidad de la red en tiempo real,Utilizado para transmisiones SSE
 * [io.zer0.ai.core.ChatStreamEvent.StreamInterrupted] después de la interrupción,La reconexión automática se activa cuando la red se recupera.
 *
 * diseño:
 *  - [isOnline]: Exponer el estado actual de la red [StateFlow],UI visualización observable"Corte de red"Consejos
 *  - [onlineEvents]: La red está desconectada de → Dispara una vez en recuperación Unit,ChatViewModel recoge esto Flow
 *    Espere a que la recuperación de la red se regenere automáticamente después de recibir StreamInterrupted
 *  - Registro global a través de Koin single,App Regístrese solo una vez durante el ciclo de vida callback
 *
 * Nota: NetworkMonitor Es un singleton a nivel de aplicación,No se requiere manual unregister(App El sistema se limpia automáticamente cuando se destruye el proceso.).
 */
class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _isOnline = MutableStateFlow(checkOnline())

    /** ¿Está disponible la red actual?(Con capacidad INTERNET + VALIDADO) */
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _onlineEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /** La red está desconectada de → Dispara una vez en recuperación Unit(Filtrar inicial en línea y en línea→Eventos redundantes en línea) */
    val onlineEvents: SharedFlow<Unit> = _onlineEvents.asSharedFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Red desde cero,Notificar a la parte que espera
            if (!_isOnline.value) {
                _isOnline.value = true
                _onlineEvents.tryEmit(Unit)
                Logger.i("NetworkMonitor", "La red ha sido restaurada.")
            }
        }

        override fun onLost(network: Network) {
            val online = checkOnline()
            if (_isOnline.value && !online) {
                _isOnline.value = false
                Logger.w("NetworkMonitor", "La red está desconectada")
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (online && !_isOnline.value) {
                _isOnline.value = true
                _onlineEvents.tryEmit(Unit)
                Logger.i("NetworkMonitor", "Restauración de la capacidad de la red(VALIDATED)")
            } else if (!online && _isOnline.value) {
                _isOnline.value = false
                Logger.w("NetworkMonitor", "Capacidad de red perdida(VALIDATED)")
            }
        }
    }

    init {
        runCatching {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager?.registerNetworkCallback(request, callback)
        }.onFailure {
            Logger.w("NetworkMonitor", "registerNetworkCallback falló", it)
        }
    }

    private fun checkOnline(): Boolean {
        return try {
            val cm = connectivityManager ?: return false
            val activeNetwork = cm.activeNetwork
            val capabilities = cm.getNetworkCapabilities(activeNetwork)
            capabilities != null &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            false
        }
    }
}
