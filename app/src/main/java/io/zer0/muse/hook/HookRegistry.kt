package io.zer0.muse.hook

import io.zer0.common.Logger
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * P1-1: Hook Registro — Administrar todo [SkillHook] registro/Cerrar sesión/Ejecutar.
 *
 * diseño:
 *  - Seguridad del hilo: uso [ConcurrentHashMap] almacenamiento Hook
 *  - Priorización: Ejecutar en orden descendente de prioridad(Cuanto mayor sea el valor, antes se ejecutará.)
 *  - tolerancia a fallas: Un solo Hook lanza una excepción sin bloquear el enlace,Continuar después de capturar y grabar
 *  - tipo de seguridad: Por tipo de gancho(KClass)Consulta,Evite errores de conversión de tipos
 *  - Diámetro del barril: Presione siempre **interfaz** Cubos. Al registrarse, ingrese los depósitos uno por uno de acuerdo con las interfaces afectadas por la clase de implementación.,
 *    Al realizar la consulta, consígalo por interfaz.,Mismo diámetro en ambos lados.;Una clase de implementación puede implementar múltiples interfaces al mismo tiempo
 *    (Por ejemplo, WorldBookHook es a la vez SystemPromptComposeHook y PromptFinalizeHook).
 *
 * Uso:
 * ```
 * val registry = HookRegistry()
 * registry.register(MyWorldBookHook())
 *
 * // ejecutar algún tipo Hook
 * val appended = registry.execute(
 *     SystemPromptComposeHook::class,
 *     initial = "",
 * ) { hook, acc ->
 *     acc + hook.afterComposeSystemPrompt(context)
 * }
 * ```
 */
class HookRegistry {

    private val TAG = "HookRegistry"

    /** Almacenamiento de cubos según la interfaz Hook,Elimine duplicados por identificación del mismo tipo. */
    private val buckets = ConcurrentHashMap<KClass<out SkillHook>, MutableMap<String, SkillHook>>()

    /**
     * Todas las instancias registradas(Mantenimiento paralelo con cubetas.).
     *
     * Dependencias del depósito [HOOK_TYPES] Registrarse,Si falta una fila en el registro, el punto de envío de la interfaz correspondiente siempre obtendrá una tabla vacía sin informar un error.
     * Mantenga un índice plano aquí,Al realizar la consulta, presione la interfaz isInstance para conocer los detalles.,Si olvida registrar la nueva interfaz, no dejará de ser válida de forma silenciosa.
     */
    private val allHooks = ConcurrentHashMap<String, SkillHook>()

    /**
     * Registrar gancho. La misma instancia se agrupará por separado según todas las interfaces Hook que implemente.,
     * Por lo tanto, puedes obtenerlo consultando cualquier interfaz.
     * @param hook Instancia de enlace para registrarse
     */
    fun register(hook: SkillHook) {
        // índice completo avanzado:Incluso si la interfaz que implementa no está registrada en HOOK_TYPES,También puede obtener los detalles al realizar consultas.
        allHooks[hook.id] = hook
        val types = hookTypesOf(hook)
        if (types.isEmpty()) {
            Logger.w(TAG, "register: ${hook::class.simpleName}#${hook.id} La interfaz no está registrada, así que aprovéchala al máximo.")
            return
        }
        types.forEach { type -> buckets.getOrPut(type) { ConcurrentHashMap() }[hook.id] = hook }
        Logger.i(
            TAG,
            "registered: ${hook::class.simpleName}#${hook.id} interfaces=${types.size} (priority=${hook.priority})",
        )
    }

    /**
     * Gancho para cancelar el registro. Elimínelos uno por uno según todas las interfaces Hook que implemente.
     * @param hook Instancia de enlace para cancelar el registro
     */
    fun unregister(hook: SkillHook) {
        allHooks.remove(hook.id)
        hookTypesOf(hook).forEach { type -> buckets[type]?.remove(hook.id) }
        Logger.i(TAG, "unregistered: ${hook::class.simpleName}#${hook.id}")
    }

    /**
     * Anular el registro del tipo especificado de Hook por id.
     */
    fun unregister(type: KClass<out SkillHook>, id: String) {
        buckets[type]?.remove(id)
    }

    /**
     * Obtener todo habilitado Hook(Ordenar por prioridad descendente).
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : SkillHook> getHooks(type: KClass<T>): List<T> {
        // Si hay un cubo, entonces prevalecerá el cubo.(El depósito es la única credencial para "cerrar sesión por tipo");
        // Sin depósito significa que la interfaz no está registrada en HOOK_TYPES,En este momento, escanee el índice completo para averiguarlo.
        val bucket = buckets[type]
        val candidates = if (bucket != null) {
            bucket.values
        } else {
            allHooks.values.filter { type.java.isInstance(it) }
        }
        return candidates
            .filter { it.enabled }
            .sortedByDescending { it.priority }
            .map { it as T }
    }

    /**
     * Ejecute la cadena Hook del tipo especificado.
     *
     * Cada Hook recibe la salida del Hook anterior como entrada,Devuelve nueva salida.
     * Un solo Hook genera una excepción(no CancellationException)No bloquees el enlace,Omita este gancho después de iniciar sesión.
     *
     * @param type Hook Tipo
     * @param initial valor inicial
     * @param block Función ejecutada para cada Hook,recibir (hook, accumulator) volver nuevo accumulator
     * @return resultado final acumulativo
     */
    suspend fun <T : SkillHook, R> execute(type: KClass<T>, initial: R, block: suspend (hook: T, acc: R) -> R): R {
        val hooks = getHooks(type)
        if (hooks.isEmpty()) return initial
        var acc = initial
        for (hook in hooks) {
            acc = try {
                block(hook, acc)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Error) {
                throw e
            } catch (err: Exception) {
                val summary = "${err::class.simpleName}: ${err.message?.take(200)}"
                Logger.e(TAG, "Hook ${hook::class.simpleName}#${hook.id} failed, skipping: $summary", err)
                acc // Saltar si falla,Mantener el resultado del paso anterior
            }
        }
        return acc
    }

    /**
     * Ejecutar cadena Hook sin valor de retorno(solo efectos secundarios,según lo notificado/Iniciar sesión).
     */
    suspend fun <T : SkillHook> executeNoResult(type: KClass<T>, block: suspend (hook: T) -> Unit) {
        val hooks = getHooks(type)
        for (hook in hooks) {
            try {
                block(hook)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Error) {
                throw e
            } catch (err: Exception) {
                val summary = "${err::class.simpleName}: ${err.message?.take(200)}"
                Logger.e(TAG, "Hook ${hook::class.simpleName}#${hook.id} failed, skipping: $summary", err)
            }
        }
    }

    /** Borrar todos los Hooks registrados. */
    fun clear() {
        buckets.clear()
        allHooks.clear()
        Logger.i(TAG, "all hooks cleared")
    }

    /** Obtenga el número total de Hooks registrados(Deduplicación por instancia,para depurar). */
    fun size(): Int = allHooks.size

    /** El depósito de interfaz correspondiente a esta instancia de Hook. Altas y bajas comparten el mismo calibre. */
    private fun hookTypesOf(hook: SkillHook): List<KClass<out SkillHook>> =
        HOOK_TYPES.filter { it.java.isInstance(hook) }

    private companion object {
        /**
         * Lista de todas las interfaces Hook.
         *
         * debe ser con [SkillHook] Las interfaces declaradas en el archivo permanecen sincronizadas.;Agregue una línea aquí cuando agregue una nueva interfaz Hook,
         * De lo contrario, el punto de envío de esta interfaz siempre obtendrá una tabla vacía.(y no informará un error).
         */
        val HOOK_TYPES: List<KClass<out SkillHook>> = listOf(
            AppLifecycleHook::class,
            MessageProcessingPlugin::class,
            SystemPromptComposeHook::class,
            ToolPromptComposeHook::class,
            PromptFinalizeHook::class,
            ToolLifecycleHook::class,
            SummaryGenerateHook::class,
        )
    }
}
