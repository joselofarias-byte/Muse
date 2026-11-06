package io.zer0.muse.ui.memory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.space.MemorySpaceEntity
import io.zer0.memory.space.MemorySpaceRepository
import io.zer0.memory.space.MemorySpaceWithCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v1.0.52 P2-2: Gestión del espacio de memoria ViewModel.
 *
 * Responsabilidades:
 *  - Space Visualización de lista(Contiene estadísticas factuales)
 *  - crear/Cambiar nombre/Eliminar Space
 *  - Ajustar la clasificación del espacio
 *
 * con [io.zer0.muse.ui.MemoryViewModel] división del trabajo:
 *  - Este ViewModel es responsable de los metadatos del espacio. CRUD
 *  - MemoryViewModel Responsable de filtrar la lista de hechos por el Espacio actualmente seleccionado
 *  - Ambos comparten el espacio actual a través de SettingsRepository.currentSpaceId Space(Inyectado por capa de aplicación)
 */
class MemorySpaceViewModel(
    application: Application,
    private val spaceRepository: MemorySpaceRepository,
) : AndroidViewModel(application) {

    /**
     * Space lista(Contiene el número de hechos.),Actualizaciones responsivas.
     * UI Actualiza automáticamente la lista a través de este flujo.
     */
    val spacesWithCount: StateFlow<List<MemorySpaceWithCount>> =
        spaceRepository.observeSpacesWithCount()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Space lista(ligero,No incluye cantidad real).
     * para el menú desplegable del conmutador,Evite hacer LEFT JOIN COUNT cada vez.
     */
    val spaces: StateFlow<List<MemorySpaceEntity>> =
        spaceRepository.observeSpaces()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Copia del estado operativo actual(crear/Eliminar/Cambiar el nombre de los comentarios de resultados). */
    private val _operationMessage = MutableStateFlow<String?>(null)
    val operationMessage: StateFlow<String?> = _operationMessage.asStateFlow()

    /** Crea un nuevo espacio. Volver a nuevo id(Retorno del fracaso null). */
    fun createSpace(name: String, icon: String? = null, description: String = "") {
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                resultOf { spaceRepository.createSpace(name, icon, description) }
                    .onError { msg, t ->
                        _operationMessage.value = "No se pudo crear el espacio: $msg"
                        Logger.w("MemorySpaceViewModel", "createSpace falló: $msg", t)
                    }
                    .getOrNull()
            }
            if (id != null) {
                _operationMessage.value = "Espacio creado: $name"
            }
        }
    }

    /** Cambiar el nombre del espacio. */
    fun renameSpace(id: String, newName: String) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                resultOf { spaceRepository.renameSpace(id, newName) }
                    .onError { msg, t ->
                        _operationMessage.value = "Error al cambiar el nombre: $msg"
                        Logger.w("MemorySpaceViewModel", "renameSpace falló: $msg", t)
                    }
                    .getOrNull() ?: false
            }
            if (ok) _operationMessage.value = "renombrado"
        }
    }

    /** Descripción del espacio actualizada. */
    fun updateDescription(id: String, description: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                resultOf { spaceRepository.updateDescription(id, description) }
                    .onError { msg, t -> Logger.w("MemorySpaceViewModel", "updateDescription falló: $msg", t) }
            }
        }
    }

    /** Icono de espacio actualizado. */
    fun updateIcon(id: String, icon: String?) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                resultOf { spaceRepository.updateIcon(id, icon) }
                    .onError { msg, t -> Logger.w("MemorySpaceViewModel", "updateIcon falló: $msg", t) }
            }
        }
    }

    /**
     * Eliminar espacio.
     * El espacio predeterminado no se puede eliminar,Se devolverá un mensaje de error.
     * Los hechos se migrarán a la configuración predeterminada antes de eliminarlos. Space,Evite la pérdida de datos.
     */
    fun deleteSpace(id: String) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                resultOf { spaceRepository.deleteSpace(id) }
                    .onError { msg, t ->
                        _operationMessage.value = "Error al eliminar: $msg"
                        Logger.w("MemorySpaceViewModel", "deleteSpace falló: $msg", t)
                    }
                    .getOrNull() ?: false
            }
            _operationMessage.value = if (ok) "Espacio eliminado,El hecho ha sido movido de nuevo al espacio predeterminado." else "El espacio predeterminado no se puede eliminar"
        }
    }

    /** Ajustar la clasificación del espacio. */
    fun reorderSpaces(orderedIds: List<String>) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                resultOf { spaceRepository.reorderSpaces(orderedIds) }
                    .onError { msg, t -> Logger.w("MemorySpaceViewModel", "reorderSpaces falló: $msg", t) }
            }
        }
    }

    /** Borrar mensaje de operación(Llamado por la interfaz de usuario después de que el usuario haya visto el mensaje). */
    fun clearOperationMessage() {
        _operationMessage.value = null
    }
}
