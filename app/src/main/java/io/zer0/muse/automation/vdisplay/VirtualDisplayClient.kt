package io.zer0.muse.automation.vdisplay

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * v2.2.1 pantalla virtual:orientado a la persona que llama(Herramientas/Agent)Cliente conveniente.
 *
 * responsable:ensure Servidor+Pantalla virtual, captura de pantalla de un solo cuadro, abrir aplicación en pantalla virtual, destruir.
 * La inyección de entrada no está en esta clase.(usar `input -d <displayId>` Reemplazar el ejecutor de shell existente).
 */
class VirtualDisplayClient(
    private val context: Context,
    private val manager: VirtualDisplayServerManager,
) {
    data class DisplayHandle(val displayId: Int, val width: Int, val height: Int, val dpi: Int = DEFAULT_DPI)

    @Volatile
    private var compatibilityWidth: Int = DEFAULT_WIDTH

    @Volatile
    private var compatibilityHeight: Int = DEFAULT_HEIGHT

    @Volatile
    private var compatibilityDpi: Int = DEFAULT_DPI

    /** Asegúrese de que el servidor + pantalla virtual esté listo(Reutilizar el mismo tamaño);El fracaso conlleva una razón legible. */
    suspend fun ensureDisplay(width: Int = DEFAULT_WIDTH, height: Int = DEFAULT_HEIGHT, dpi: Int = DEFAULT_DPI): Result<DisplayHandle> =
        withContext(Dispatchers.IO) {
            val started =
                try {
                    manager.ensureStarted()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    return@withContext Result.failure(error)
                }
            val proxy = started.getOrElse { return@withContext Result.failure(it) }
            val id =
                try {
                    proxy.ensureDisplay(width, height, dpi)
                } catch (e: Exception) {
                    -1
                }
            if (id < 0) {
                return@withContext Result.failure(
                    IllegalStateException("Falló la creación de la pantalla virtual(Ver detalles /data/local/tmp/muse-vd-server.log)"),
                )
            }
            compatibilityWidth = width
            compatibilityHeight = height
            compatibilityDpi = dpi
            manager.lastDisplayId = id
            Result.success(DisplayHandle(id, width, height, dpi))
        }

    /** Recreate or reuse the process-wide compatibility display after a service restart. */
    suspend fun ensureCompatibilityDisplay(): Result<DisplayHandle> =
        ensureDisplay(compatibilityWidth, compatibilityHeight, compatibilityDpi)

    /** Cree una pantalla virtual que sea exclusiva de esta convocatoria y no será reutilizada. `ensureDisplay` pantalla compatible; la persona que llama es responsable de destruir finalmente. */
    suspend fun createDisplay(width: Int = DEFAULT_WIDTH, height: Int = DEFAULT_HEIGHT, dpi: Int = DEFAULT_DPI): Result<DisplayHandle> =
        withContext(Dispatchers.IO) {
            val proxy = manager.ensureStarted().getOrElse { return@withContext Result.failure(it) }
            val id = try {
                // Once the remote side effect starts, do not let cancellation hide the allocated displayId.
                withContext(NonCancellable) { proxy.createDisplay(width, height, dpi) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                -1
            }
            if (id < 0) {
                return@withContext Result.failure(
                    IllegalStateException("Error al crear la pantalla virtual independiente(La capacidad del servidor está llena o consulte para más detalles. /data/local/tmp/muse-vd-server.log)"),
                )
            }
            try {
                currentCoroutineContext().ensureActive()
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { runCatching { proxy.destroyDisplay(id) } }
                throw cancelled
            }
            Result.success(DisplayHandle(id, width, height, dpi))
        }

    /** agarrar un marco(JPEG bytes);Actualiza automáticamente la pantalla cuando falla la pantalla virtual compatible. */
    suspend fun screenshot(displayId: Int = manager.lastDisplayId): ByteArray? = withContext(Dispatchers.IO) {
        // The compatibility display is the only display this client can safely recreate.
        // Dedicated GUI-Agent displays are owned by the caller and must not be replaced here.
        val compatibilityRequest = displayId >= 0 && displayId == manager.lastDisplayId
        val proxy = manager.ensureStarted().getOrNull() ?: return@withContext null
        val id = if (displayId >= 0) displayId else manager.lastDisplayId
        if (id < 0) return@withContext null
        try {
            proxy.requestScreenshot(id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (!compatibilityRequest) {
                clearCachedDisplayIf(id)
                return@withContext null
            }
            clearCachedDisplayIf(id)
            val refreshed = ensureCompatibilityDisplay().getOrNull() ?: return@withContext null
            val refreshedProxy = manager.ensureStarted().getOrNull() ?: return@withContext null
            try {
                refreshedProxy.requestScreenshot(refreshed.displayId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                clearCachedDisplayIf(refreshed.displayId)
                null
            }
        }
    }

    /** Abre la aplicación en la pantalla virtual.:Actualiza automáticamente la pantalla cuando falla la pantalla compatible. */
    suspend fun openApp(packageName: String, displayId: Int = manager.lastDisplayId): Boolean = withContext(Dispatchers.IO) {
        if (!PACKAGE_REGEX.matches(packageName) || displayId < 0) return@withContext false
        val compatibilityRequest = displayId == manager.lastDisplayId
        val proxy = manager.existingLiveProxy()
        if (proxy != null) {
            try {
                return@withContext proxy.launchApp(packageName, displayId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!compatibilityRequest) {
                    clearCachedDisplayIf(displayId)
                    return@withContext false
                }
                clearCachedDisplayIf(displayId)
            }
        } else if (!compatibilityRequest) {
            return@withContext launchViaShell(packageName, displayId)
        }

        if (compatibilityRequest) {
            val refreshed = ensureCompatibilityDisplay().getOrNull() ?: return@withContext false
            val refreshedProxy = manager.ensureStarted().getOrNull()
            if (refreshedProxy != null) {
                try {
                    return@withContext refreshedProxy.launchApp(packageName, refreshed.displayId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    clearCachedDisplayIf(refreshed.displayId)
                    return@withContext false
                }
            }
            return@withContext launchViaShell(packageName, refreshed.displayId)
        }

        return@withContext launchViaShell(packageName, displayId)
    }

    private suspend fun launchViaShell(packageName: String, displayId: Int): Boolean {
        val resolved = manager.exec("cmd package resolve-activity --brief $packageName")
        val component =
            resolved.output
                .lineSequence()
                .map { it.trim() }
                .lastOrNull { it.contains('/') }
                ?: return false
        // Antiinyección:Los metacaracteres del shell que aparecen en los nombres de los componentes se rechazan directamente.(El nombre del paquete está en la lista blanca.,Este es un doble seguro.)
        if (component.any { it in INJECTION_CHARS }) return false
        return manager.exec("am start --display $displayId -n $component").exitCode == 0
    }

    /** Destruir la pantalla virtual(Devuelve cuando el servidor no existe false,No es un error). */
    suspend fun destroy(displayId: Int = manager.lastDisplayId): Boolean = withContext(Dispatchers.IO) {
        val proxy = manager.existingLiveProxy() ?: run {
            if (displayId == manager.lastDisplayId) manager.lastDisplayId = -1
            return@withContext false
        }
        runCatching {
            proxy.destroyDisplay(displayId)
            if (displayId == manager.lastDisplayId) manager.lastDisplayId = -1
            true
        }.getOrDefault(false)
    }

    private fun clearCachedDisplayIf(displayId: Int) {
        if (displayId >= 0 && displayId == manager.lastDisplayId) {
            manager.lastDisplayId = -1
        }
    }

    companion object {
        private const val DEFAULT_WIDTH = 720
        private const val DEFAULT_HEIGHT = 1280
        private const val DEFAULT_DPI = 320

        private val PACKAGE_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_.]*$")

        /** shell conjunto de metacaracteres(Rechazar cuando aparece el nombre del componente,Doble seguro antiinyección). */
        private val INJECTION_CHARS = setOf(' ', ';', '&', '|', '<', '>', '\$', '`', '\'', '"', '\\')
    }
}
