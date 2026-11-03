package io.zer0.muse.data.plugin

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.util.readZipEntryWithLimit
import kotlinx.serialization.decodeFromString
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * B6-01: Cargador de paquetes de complementos externos.
 *
 * apoyo `.muse-plugin` / ZIP Paquete de complementos:
 * ```
 * plugin.muse-plugin (ZIP)
 * ├── manifest.json   (requerido,PluginManifest)
 * ├── main.js         (kind=tool/provider cuando sea necesario,JS entrada)
 * └── assets/         (Recursos complementarios opcionales)
 * ```
 *
 * Phase 4 anexar `kind == "ui-skin"` Paquete de aspectos declarativo para:
 * ```
 * skin.muse-plugin (ZIP)
 * ├── manifest.json   (requerido,kind="ui-skin",capabilities Debe contener ui.skin,tools debe estar vacío)
 * └── skin.json       (requerido,BubbleSkin JSON;No aparecerá nada en el paquete .js)
 * ```
 * El paquete skin solo distribuye JSON, no transporta ni ejecuta JS; el analizado [LoadedPluginPackage.skin] por anfitrión
 * verificar/Cuando fallan el procesamiento y la verificación, BubbleSkinResolver vuelve al valor predeterminado integrado.
 *
 * Restricciones de seguridad:ZIP Deben existir protección contra bombas, intercepción de recorrido de ruta y archivos de entrada.
 */
object PluginPackageLoader {

    private const val TAG = "PluginPackageLoader"

    private const val MAX_ENTRY_SIZE = 2L * 1024 * 1024
    private const val MAX_TOTAL_SIZE = 10L * 1024 * 1024
    private const val MANIFEST_ENTRY = "manifest.json"

    /** Rutas de recursos de aspectos en paquetes de aspectos declarativos(directorio raíz del paquete). */
    const val UI_SKIN_ENTRY = "skin.json"

    sealed class Result {
        data class Ok(val package_: LoadedPluginPackage) : Result()
        data class Err(val reason: String) : Result()
    }

    data class LoadedPluginPackage(
        val manifest: PluginManifest,
        val entryCode: String,
        val extraFiles: Map<String, String> = emptyMap(),
        /**
         * `kind == "ui-skin"` El aspecto declarativo analizado por el paquete; de lo contrario nulo.
         *
         * Aquí solo se realiza el análisis de la estructura JSON.schema/Rango de valores/El contraste lo determina el anfitrión.
         * [io.zer0.muse.ui.theme.BubbleSkinValidator] Determinar con resolutor antes de renderizar,
         * Cuando el juicio falla, el usuario seguirá viendo la burbuja predeterminada incorporada y el cuerpo del mensaje no se verá afectado.
         */
        val skin: io.zer0.muse.ui.theme.BubbleSkin? = null,
    )

    fun loadFromZip(zipBytes: ByteArray): Result {
        var totalSize = 0L
        var manifestJson: String? = null
        val allFiles = mutableMapOf<String, String>()

        try {
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!isSafeRelativePath(name)) {
                        return Result.Err("camino ilegal: $name")
                    }
                    if (entry.isDirectory) {
                        entry = zis.nextEntry
                        continue
                    }
                    // Tipos de archivos aceptados..py ¿Se admite un tiempo de ejecución de complemento desde la versión 2.7 (subproceso CPython integrado)?
                    // Aquí solo se aceptó .js/.json/.md, por lo que el archivo de entrada del complemento de Python estaba en la etapa de descomprimido.
                    // Se descartó silenciosamente, pero el siguiente mensaje de error fue "falta un archivo de entrada".—— La causa raíz está aquí.
                    val lowerName = name.lowercase()
                    if (ACCEPTED_FILE_EXTENSIONS.none { lowerName.endsWith(it) }) {
                        entry = zis.nextEntry
                        continue
                    }

                    val bytes = readZipEntryWithLimit(zis, MAX_ENTRY_SIZE, name)
                    totalSize += bytes.size
                    if (totalSize > MAX_TOTAL_SIZE) {
                        return Result.Err("El tamaño total descomprimido supera el límite(${MAX_TOTAL_SIZE / 1024 / 1024}MB)")
                    }

                    val content = bytes.toString(Charsets.UTF_8)
                    if (name == MANIFEST_ENTRY) {
                        manifestJson = content
                    } else {
                        allFiles[name] = content
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (e: IOException) {
            return Result.Err("ZIP Error de lectura: ${e.message}")
        }

        val json = manifestJson ?: return Result.Err("desaparecido manifest.json")
        val manifest = runCatching {
            AppJson.decodeFromString<PluginManifest>(json)
        }.getOrElse { e ->
            return Result.Err("manifest.json El análisis falló: ${e.message}")
        }

        if (manifest.id.isBlank() || manifest.name.isBlank()) {
            return Result.Err("manifest.json desaparecido id/name")
        }
        if (!PLUGIN_ID_REGEX.matches(manifest.id)) {
            return Result.Err("Los identificadores de complementos solo pueden contener letras minúsculas, números, guiones bajos y guiones.: ${manifest.id}")
        }
        if (manifest.tools.isEmpty() && manifest.kind != PluginSecurityGate.UI_SKIN_KIND) {
            return Result.Err("manifest.json No se declaran herramientas")
        }
        // Los paquetes externos no tienen firmas de autor ni raíces de confianza integradas, y no pueden autoinformar el acceso completo a través del manifiesto.
        if (manifest.trust != "sandboxed") {
            return Result.Err("Los complementos externos deben utilizar el nivel de confianza del espacio aislado")
        }

        // ── Phase 4: Paquete declarativo ui-skin(Sin herramientas, nada JS,Solo se distribuyen skins JSON) ──
        if (manifest.kind == PluginSecurityGate.UI_SKIN_KIND) {
            if (manifest.tools.isNotEmpty()) {
                return Result.Err("ui-skin Los complementos no deben declarar herramientas ejecutables")
            }
            if (PluginSecurityGate.UI_SKIN_CAPABILITY !in manifest.capabilities) {
                return Result.Err("ui-skin Los complementos deben declarar ${PluginSecurityGate.UI_SKIN_CAPABILITY} Habilidad")
            }
            if (allFiles.keys.any { key -> FORBIDDEN_SKIN_EXTENSIONS.any { key.lowercase().endsWith(it) } }) {
                return Result.Err("ui-skin Los complementos no deben contener archivos JS")
            }
            val skinJson = allFiles[UI_SKIN_ENTRY]
                ?: return Result.Err("ui-skin El complemento carece de recursos de máscara.: $UI_SKIN_ENTRY")
            val skin = runCatching {
                AppJson.decodeFromString<io.zer0.muse.ui.theme.BubbleSkin>(skinJson)
            }.getOrElse { e ->
                return Result.Err("$UI_SKIN_ENTRY El análisis falló: ${e.message}")
            }
            Logger.i(TAG, "Paquete de complemento ui-skin cargado: ${manifest.id} v${manifest.version} (piel ${skin.id})")
            return Result.Ok(
                LoadedPluginPackage(
                    manifest = manifest,
                    // Los paquetes declarativos no tienen punto de entrada JS;Una cadena vacía garantiza que no se pueda obtener ningún código ejecutable desde ninguna ruta de ejecución.
                    entryCode = "",
                    extraFiles = allFiles.toMap(),
                    skin = skin,
                ),
            )
        }

        // La extensión de entrada está determinada por manifest.runtime:python El tiempo de ejecución requiere .py, el resto (js predeterminado) requiere .js.
        // Ambos lugares deben ser consistentes. —— Lista blanca de desembalaje, verificación de entrada,PluginSecurityGate.rejectionReason
        // Los tres lugares dan la misma respuesta a "¿Se puede ejecutar este complemento?" De lo contrario, aparecerá nuevamente "Se puede instalar pero no ejecutar".
        val expectedExt = expectedEntryExtension(manifest)
        if (!isSafeRelativePath(manifest.entry) || !manifest.entry.lowercase().endsWith(expectedExt)) {
            return Result.Err(
                "manifest.json La ruta del archivo de entrada es ilegal (runtime=${manifest.runtime} necesitar $expectedExt entrada): ${manifest.entry}",
            )
        }

        val entryCode = allFiles.remove(manifest.entry)
            ?: return Result.Err("Falta el archivo de entrada: ${manifest.entry}")

        Logger.i(TAG, "Paquete de complemento cargado: ${manifest.id} v${manifest.version} (${manifest.tools.size} Herramientas)")
        return Result.Ok(
            LoadedPluginPackage(
                manifest = manifest,
                entryCode = entryCode,
                extraFiles = allFiles.toMap(),
            ),
        )
    }

    /**
     * Elimine la máscara declarativa del paquete reconstruido en el directorio instalado(Ruta no ZIP).
     *
     * con [loadFromZip] La rama ui-skin mantiene el mismo conjunto de restricciones:solo aceptar `kind == "ui-skin"`,
     * Declaración [PluginSecurityGate.UI_SKIN_CAPABILITY], sin herramientas y no contiene ningún `.js` paquete.
     * Cualquier condición no se cumple o `skin.json` Devolver si falla el análisis null,En consecuencia, la persona que llama recurre al teléfono integrado. default,
     * No se realiza ningún análisis de degradación,Los archivos fuera del directorio del complemento tampoco se modifican.
     */
    fun extractUiSkin(pluginPackage: LoadedPluginPackage): io.zer0.muse.ui.theme.BubbleSkin? {
        val manifest = pluginPackage.manifest
        if (manifest.kind != PluginSecurityGate.UI_SKIN_KIND) return null
        if (manifest.tools.isNotEmpty()) return null
        if (PluginSecurityGate.UI_SKIN_CAPABILITY !in manifest.capabilities) return null
        if (pluginPackage.extraFiles.keys.any { key -> FORBIDDEN_SKIN_EXTENSIONS.any { key.lowercase().endsWith(it) } }) return null
        val skinJson = pluginPackage.extraFiles[UI_SKIN_ENTRY] ?: return null
        return runCatching {
            AppJson.decodeFromString<io.zer0.muse.ui.theme.BubbleSkin>(skinJson)
        }.getOrNull()
    }

    private fun isSafeRelativePath(path: String): Boolean {
        val normalized = path.trimEnd('/')
        return normalized.isNotBlank() &&
            !normalized.startsWith("/") &&
            !normalized.startsWith("\\") &&
            !normalized.contains("..") &&
            !normalized.contains('\\') &&
            normalized.split('/').none { it.isBlank() || it == "." }
    }

    private val PLUGIN_ID_REGEX = Regex("^[a-z0-9][a-z0-9_-]*$")

    /** Tipos de archivos permitidos dentro del paquete. */
    private val ACCEPTED_FILE_EXTENSIONS = listOf(".js", ".json", ".md", ".py")

    /** ui-skin Es un paquete declarativo y no debe contener ningún código ejecutable. */
    private val FORBIDDEN_SKIN_EXTENSIONS = listOf(".js", ".py")

    /** Presione manifest.runtime para obtener la extensión que debe tener el archivo de entrada. */
    private fun expectedEntryExtension(manifest: PluginManifest): String =
        if (manifest.runtime.equals("python", ignoreCase = true)) ".py" else ".js"
}
