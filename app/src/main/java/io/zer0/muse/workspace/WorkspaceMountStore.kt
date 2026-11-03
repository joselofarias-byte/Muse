package io.zer0.muse.workspace

import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persistencia de tablas de montaje.
 *
 * Guarde un JSON en el directorio privado de la aplicación: el montaje es "configuración" en lugar de "datos".
 * Usar un archivo es más liviano que importar una nueva tabla y no es necesario migrar según la versión de la base de datos.
 *
 * Los montajes integrados no se colocan en el disco y se muestran cada vez que se leen: apuntan a directorios fijos dentro del entorno limitado de la aplicación.
 * El usuario no podrá eliminarlo ni modificarlo.
 *
 * @param context Contexto de aplicación, utilizado para posicionamiento. filesDir
 */
class WorkspaceMountStore(private val context: Context) {

    /** Montar archivo de tabla. */
    private val storeFile: File = File(context.filesDir, "workspace_mounts.json")

    /** El directorio sandbox correspondiente se monta de forma predeterminada, utilizando la ruta histórica, y los datos antiguos están disponibles en su lugar. */
    private val mainDir: File = File(context.filesDir, MAIN_DIR_NAME)

    /** El directorio de salida largo de la herramienta es de solo lectura en la raíz del montaje integrado. */
    private val toolOutputsDir: File = File(context.filesDir, TOOL_OUTPUTS_DIR_NAME)

    /** La lectura y la escritura se excluyen mutuamente para evitar que la interfaz y las herramientas se sobrescriban entre sí al cambiar de tabla al mismo tiempo. */
    private val mutex = Mutex()

    /** Caché en memoria; el archivo es la única fuente de verdad y el caché solo se usa para reducir el análisis repetido. */
    @Volatile
    private var cache: List<WorkspaceMount>? = null

    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /**
     * Montar todos, primero los integrados y el resto en orden.
     *
     * Los montajes integrados no participan en la persistencia: el espacio de trabajo siempre tiene una raíz disponible, incluso si el archivo está vacío o dañado.
     * y un directorio de salida de herramientas que se puede enumerar y leer.
     */
    suspend fun mounts(): List<WorkspaceMount> = withContext(Dispatchers.IO) {
        mutex.withLock { builtInMounts() + readStored().filter { it.id !in BUILT_IN_MOUNT_IDS } }
    }

    /** Todos los ID de montaje registrados actualmente. */
    suspend fun mountIds(): Set<String> = mounts().mapTo(mutableSetOf()) { it.id }

    /** Busque una montura por su ID y devuelva nulo si no la encuentra. */
    suspend fun find(id: String): WorkspaceMount? = mounts().firstOrNull { it.id == id }

    /** Montado por defecto. */
    fun mainMount(): WorkspaceMount {
        if (!mainDir.exists()) mainDir.mkdirs()
        return WorkspaceMount(
            id = WorkspaceMount.MAIN_ID,
            label = MAIN_LABEL,
            provider = MountProvider.LOCAL,
            rootPath = mainDir.absolutePath,
            capabilities = WorkspaceMount.DEFAULT_CAPABILITIES,
            system = true,
        )
    }

    /**
     * Un montaje de solo lectura del directorio de salida largo de la herramienta.
     *
     * El directorio en sí lo escribe la herramienta (no sujeto a esta capacidad); colgar en el espacio de trabajo es para las personas/La entrada a la visualización de modelos,
     * Así que solo enumere y lea: no hay ningún uso legítimo para sobrescribir o eliminar la salida de la herramienta desde aquí.
     */
    fun toolOutputsMount(): WorkspaceMount {
        if (!toolOutputsDir.exists()) toolOutputsDir.mkdirs()
        return WorkspaceMount(
            id = TOOL_OUTPUTS_MOUNT_ID,
            label = TOOL_OUTPUTS_LABEL,
            provider = MountProvider.LOCAL,
            rootPath = toolOutputsDir.absolutePath,
            capabilities = WorkspaceMount.READ_ONLY_CAPABILITIES,
            system = true,
        )
    }

    /** Montaje incorporado (no cae al disco, no se puede eliminar, no se puede cambiar el nombre). */
    private fun builtInMounts(): List<WorkspaceMount> = listOf(mainMount(), toolOutputsMount())

    /**
     * Agregue o actualice una montura.
     *
     * Verificación: el identificador cumple con las reglas de nomenclatura y no está reservado por el sistema.LOCAL El directorio montado debe existir y no debe estar duplicado por otros montajes.
     *
     * @return Después del éxito, se devuelve la lista de montaje después del disco; en caso de error, se devuelve un error con un motivo.
     */
    suspend fun upsert(mount: WorkspaceMount): MountStoreResult = withContext(Dispatchers.IO) {
        if (mount.id in BUILT_IN_MOUNT_IDS) {
            return@withContext MountStoreResult.Error("Los soportes integrados no se pueden modificar")
        }
        if (!WorkspaceMount.isValidId(mount.id)) {
            return@withContext MountStoreResult.Error("El ID de montaje solo puede ser una letra que comience con una letra minúscula, un número, un guión bajo o un guión.")
        }
        if (mount.provider == MountProvider.LOCAL) {
            val root = mount.rootPath?.let(::File)
            if (root == null || !root.isDirectory) {
                return@withContext MountStoreResult.Error("El directorio no existe o no es una carpeta")
            }
            if (root.absolutePath == toolOutputsDir.absolutePath) {
                return@withContext MountStoreResult.Error("El directorio de salida de la herramienta ya existe como montaje integrado.,No es necesario volver a colgar")
            }
        } else {
            if (mount.treeUri.isNullOrBlank()) {
                return@withContext MountStoreResult.Error("Falta autorización de directorio")
            }
        }
        mutex.withLock {
            val stored = readStored()
            if (stored.any { it.id != mount.id && sameRoot(it, mount) }) {
                return@withLock MountStoreResult.Error("Este directorio ya ha sido montado.")
            }
            val next = stored.filter { it.id != mount.id } + mount
            if (!writeStored(next)) return@withLock MountStoreResult.Error("Error al guardar")
            cache = next
            MountStoreResult.Success(builtInMounts() + next)
        }
    }

    /**
     * Eliminar una montura.
     *
     * Sólo se eliminará el registro y no se tocarán los archivos del disco.——La montura es un registro de autorización, no de propiedad.
     *
     * @return ¿Realmente eliminaste un elemento?
     */
    suspend fun remove(id: String): Boolean = withContext(Dispatchers.IO) {
        if (id in BUILT_IN_MOUNT_IDS) return@withContext false
        mutex.withLock {
            val stored = readStored()
            val next = stored.filter { it.id != id }
            if (next.size == stored.size) return@withLock false
            if (!writeStored(next)) return@withLock false
            cache = next
            true
        }
    }

    /** Registre un uso y utilícelo para ordenar la interfaz por uso reciente. */
    suspend fun touch(id: String): Unit = withContext(Dispatchers.IO) {
        if (id in BUILT_IN_MOUNT_IDS) return@withContext
        mutex.withLock {
            val stored = readStored()
            val index = stored.indexOfFirst { it.id == id }
            if (index < 0) return@withLock
            val next = stored.toMutableList()
            next[index] = next[index].copy(lastUsedAt = System.currentTimeMillis())
            if (writeStored(next)) cache = next
        }
    }

    /** Lea la tabla de montaje del disco caído; cuando el archivo falta o está dañado, se devuelve una tabla vacía para evitar que los archivos defectuosos bloqueen todo el espacio de trabajo. */
    private fun readStored(): List<WorkspaceMount> {
        cache?.let { return it }
        if (!storeFile.exists()) return emptyList()
        val parsed: List<WorkspaceMount> = resultOf {
            val text = storeFile.readText(Charsets.UTF_8)
            if (text.isBlank()) {
                emptyList()
            } else {
                json.decodeFromString(ListSerializer(WorkspaceMount.serializer()), text)
            }
        }.onError { message, _ -> Logger.w(TAG, "No se pudo leer la tabla de montaje: $message") }
            .getOrNull() ?: return emptyList()
        val cleaned = parsed.filter { it.id !in BUILT_IN_MOUNT_IDS && WorkspaceMount.isValidId(it.id) }
        cache = cleaned
        return cleaned
    }

    /** Escritura atómica: primero escriba el archivo temporal y luego cámbiele el nombre para evitar que lo eliminen a la mitad y deje la mitad del JSON. */
    private fun writeStored(mounts: List<WorkspaceMount>): Boolean = resultOf {
        val payload = json.encodeToString(ListSerializer(WorkspaceMount.serializer()), mounts)
        val temp = File(storeFile.parentFile, "${storeFile.name}.tmp")
        temp.writeText(payload, Charsets.UTF_8)
        if (!temp.renameTo(storeFile)) {
            storeFile.writeText(payload, Charsets.UTF_8)
            temp.delete()
        }
    }.onError { message, _ -> Logger.w(TAG, "No se pudo escribir en la tabla de montaje: $message") }.isSuccess

    /** Si los dos soportes apuntan a la misma ubicación. */
    private fun sameRoot(a: WorkspaceMount, b: WorkspaceMount): Boolean = when {
        a.provider != b.provider -> false
        a.provider == MountProvider.SAF -> a.treeUri == b.treeUri
        else -> a.rootPath == b.rootPath
    }

    /** [upsert] resultado. */
    sealed class MountStoreResult {
        data class Success(val mounts: List<WorkspaceMount>) : MountStoreResult()

        data class Error(val message: String) : MountStoreResult()
    }

    companion object {
        private const val TAG = "WorkspaceMountStore"

        /** El nombre del directorio montado predeterminado. */
        private const val MAIN_DIR_NAME = "workspace"

        /** El nombre para mostrar montado predeterminado. */
        private const val MAIN_LABEL = "espacio de trabajo"

        /** El nombre del directorio generado por la herramienta también es su identificador como montaje integrado. */
        private const val TOOL_OUTPUTS_DIR_NAME = "tool_outputs"

        /** La herramienta genera el ID de montaje. */
        const val TOOL_OUTPUTS_MOUNT_ID: String = TOOL_OUTPUTS_DIR_NAME

        /** La herramienta genera el nombre para mostrar del montaje. */
        private const val TOOL_OUTPUTS_LABEL = "Salida de herramienta"

        /** Montaje integrado: no se puede colocar en el disco, no se puede eliminar y no se le puede cambiar el nombre. */
        private val BUILT_IN_MOUNT_IDS: Set<String> = setOf(WorkspaceMount.MAIN_ID, TOOL_OUTPUTS_MOUNT_ID)
    }
}
