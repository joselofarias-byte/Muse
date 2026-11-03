package io.zer0.muse.ui.translate

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * v1.0.30 gap4.6: Almacenamiento del glosario de traducción.
 *
 * Los datos se guardan como un archivo JSON en el directorio privado de la aplicación.(`filesDir/translate/glossary.json`),
 * Almacenar texto original → mapeo fijo de traducción(Como por ejemplo "API" → "API").
 *
 * en [TranslateViewModel.translate] en,si el glosario no está vacío,estará a la brevedad
 * adicional"Consulte el siguiente glosario para la traducción.: API→API, GPU→procesador de gráficos..."instrucciones,
 * Deje que LLM siga un método de traducción de terminología unificada al traducir(Evite traducciones inconsistentes del mismo término en diferentes traducciones).
 *
 * Seguridad del hilo:La sincronización interna se utiliza para proteger lecturas y escrituras.(readAll/save).
 */
class GlossaryStore(private val context: Context) {

    private val file by lazy {
        java.io.File(context.filesDir, "translate/glossary.json").apply {
            parentFile?.mkdirs()
        }
    }

    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

    private val lock = Any()

    /** Listar todas las asignaciones de términos(Texto original → traducción). */
    fun list(): Map<String, String> = synchronized(lock) { readAll() }

    /** Agregar o actualizar una asignación de términos(Texto original → traducción). */
    fun add(original: String, translated: String) {
        if (original.isBlank()) return
        synchronized(lock) {
            val all = readAll().toMutableMap()
            all[original.trim()] = translated.trim()
            save(all)
        }
    }

    /** Eliminar una asignación de términos;Devuelve si realmente se eliminó. */
    fun remove(original: String): Boolean {
        val key = original.trim()
        if (key.isBlank()) return false
        return synchronized(lock) {
            val all = readAll().toMutableMap()
            val removed = all.remove(key) != null
            if (removed) save(all)
            removed
        }
    }

    /** Limpiar el glosario. */
    fun clear() {
        synchronized(lock) { save(emptyMap()) }
    }

    /** Si el glosario está vacío. */
    fun isEmpty(): Boolean = synchronized(lock) { readAll().isEmpty() }

    /**
     * Formatee el glosario en un fragmento de mensaje,Como por ejemplo "API→API, GPU→procesador de gráficos".
     * La tabla vacía devuelve una cadena vacía(La persona que llama decide entonces si desea conectarse a prompt).
     */
    fun toPromptSnippet(): String = synchronized(lock) { formatPromptSnippet(readAll()) }

    private fun readAll(): Map<String, String> {
        return try {
            if (!file.exists()) return emptyMap()
            AppJson.decodeFromString(mapSerializer, file.readText())
        } catch (e: Exception) {
            Logger.w(TAG, "No se pudo leer el glosario: ${e.message}")
            emptyMap()
        }
    }

    private fun save(map: Map<String, String>) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(AppJson.encodeToString(mapSerializer, map))
        } catch (e: Exception) {
            Logger.w(TAG, "No se pudo guardar el glosario: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "GlossaryStore"

        /**
         * v2.6.7: Glosario → prompt Los fragmentos se extraen como funciones puras, dejando solo una implementación.
         *
         * TranslateViewModel Originalmente escribí un joinToString idéntico en línea.
         * Escribe el mismo formato en dos lugares. Si cambia el separador o la flecha en el futuro, solo se cambiará a un lugar.
         */
        fun formatPromptSnippet(glossary: Map<String, String>): String {
            if (glossary.isEmpty()) return ""
            return glossary.entries.joinToString(", ") { "${it.key}→${it.value}" }
        }
    }
}
