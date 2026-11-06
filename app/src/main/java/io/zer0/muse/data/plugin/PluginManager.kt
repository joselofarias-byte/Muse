package io.zer0.muse.data.plugin

import android.content.Context
import android.net.Uri
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.BuildConfig
import io.zer0.muse.data.AtomicFileStore
import io.zer0.muse.data.skill.SkillEntity
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.tools.script.ToolDeclaration
import io.zer0.muse.ui.theme.BubbleSkinValidator
import io.zer0.muse.ui.theme.InstalledSkin
import io.zer0.muse.ui.theme.PluginSkinSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.io.InputStream
import java.util.Base64

/**
 * Almacenamiento de configuración del complemento: almacene los valores de configuración definidos por el usuario por identificación del complemento.
 *
 * almacenado en `filesDir/plugin_configs.json`, el formato es `{"pluginId": {"key": value}}`.
 * Utilice AtomicFileStore para garantizar la atomicidad de escritura y evitar que el proceso finalice dejando la mitad de JSON.
 */
@Serializable
data class PluginConfigStore(val configs: Map<String, Map<String, JsonElement>> = emptyMap()) {
    companion object {
        fun fromFile(file: File): PluginConfigStore {
            if (!file.exists()) return PluginConfigStore()
            return runCatching { AppJson.decodeFromString<PluginConfigStore>(file.readText()) }
                .getOrElse { PluginConfigStore() }
        }
        fun toFile(file: File, store: PluginConfigStore) {
            AtomicFileStore.writeText(file, AppJson.encodeToString(PluginConfigStore.serializer(), store))
        }
    }
}

/**
 * B6-01: Administrador de complementos externo.
 *
 * El paquete de complemento se instala en `filesDir/plugins/<id>/`, el registro se guarda en `filesDir/plugin_registry.json`;
 * La herramienta complementaria es `plugin_<pluginId>_<toolName>` Escrito en la tabla de habilidades, ejecutado por SkillExecutor
 * prensa `plugin:<pluginId>:<functionName>` Ruta al entorno limitado de JS.
 *
 * al mismo tiempo que [PluginSkinSource] expuesto `kind == "ui-skin"` Piel declarativa; reutilización de lectura de piel
 * Ruta de reverificación de los paquetes instalados sin ejecutar ningún código de complemento.
 */
@Suppress("TooGenericExceptionCaught", "FunctionParameterNaming")
class PluginManager(
    private val context: Context,
    private val skillRepository: SkillRepository,
    /** La raíz de confianza del editor solo se permite desde el almacenamiento privado de Android; Las pruebas pueden inyectar implementaciones de archivos temporales. */
    private val trustStore: PluginTrustStore = PluginTrustStore(context),
    /** Biblioteca de versiones históricas: conserva varias versiones verificadas recientes para que los usuarios las reviertan activamente; colocado deliberadamente fuera del directorio del complemento. */
    private val versionStore: PluginVersionStore =
        PluginVersionStore(File(context.filesDir, "plugin_versions")),
    /** Versión actual de la aplicación, minAppVersion utilizada para aplicar el manifiesto. */
    private val appVersionName: String = BuildConfig.VERSION_NAME,
    /**
     * Clave de autor nativo: los complementos redactados por el asistente son firmados y habilitados por este solo después de la confirmación explícita por parte del usuario.
     *
     * Colocado en el almacenamiento privado de Android como la raíz de confianza; Las pruebas pueden inyectar implementaciones de archivos temporales.
     */
    private val localAuthorKeyStore: LocalAuthorKeyStore = LocalAuthorKeyStore(context),
) : PluginSkinSource {

    @Serializable
    data class InstalledPlugin(
        val id: String,
        val name: String,
        val version: String,
        val author: String = "",
        val description: String = "",
        val entry: String = "main.js",
        val kind: String = "tool",
        val trust: String = "sandboxed",
        val capabilities: List<String> = emptyList(),
        val permissions: List<String> = emptyList(),
        val tools: List<ToolDeclaration> = emptyList(),
        /**
         * Ya sea para habilitar. Los complementos externos se fijan en falso para la primera instalación y deben habilitarse explícitamente después de la confirmación del usuario.
         * Mantenga el valor anterior verdadero cuando este campo falte en el registro anterior, para evitar romper la compatibilidad de importación existente.
         */
        val enabled: Boolean = true,
        /** SHA-256 del contenido del complemento instalado, utilizado para detectar el reemplazo de archivos de complemento. Vacíe si falta el registro anterior. */
        val contentSha256: String = "",
        val installedAt: Long = System.currentTimeMillis(),
        /**
         * ¿Ha completado la confirmación explícita de instalación del contenido del complemento?
         * Al registro antiguo le falta este campo“No confirmado”Procesamiento para evitar la ejecución silenciosa cuando no se puede probar la confirmación del usuario;
         * El paquete antiguo aún se conservará en el registro y los usuarios podrán volver a ver el resumen y confirmarlo sin destruir la compatibilidad de los datos importados.
         * Colocado después de los parámetros existentes para mantener la compatibilidad con llamadas de construcción posicionales antiguas.
         */
        val installationConfirmed: Boolean = false,
        /** última instalación/Revisar el estado de la firma resultante; aún se revalidará antes de la ejecución y no se confiará en este campo almacenado en caché. */
        val signatureStatus: PluginSecurityGate.SignatureStatus =
            PluginSecurityGate.SignatureStatus.UNSIGNED,
        val publisherId: String = "",
        val publisherKeyFingerprint: String = "",
        /**
         * Instalaciones anteriores de este complemento/Confirme el conjunto completo de ID de habilidades registradas (incluido el ID de formato anterior antes de la codificación del prefijo de longitud).
         *
         * Desinstalar/Al actualizar, limpie de acuerdo con esta colección para evitar depender únicamente del manifiesto actual y dejar las habilidades de la versión anterior.
         * Cuando al registro anterior le falta este campo, será una lista vacía y recurrirá al manifiesto actual para derivar el objetivo de limpieza.
         */
        val registeredSkillIds: List<String> = emptyList(),
    )

    /**
     * Vista de ejecución del complemento después de la revalidación.
     *
     * [capabilities]/[tools]/[entryCode] Todo releído desde el disco y revisado a través de puertas de seguridad.
     * manifest, en lugar de [InstalledPlugin] Campo de caché del registro; la ruta de ejecución debe utilizar esta vista.
     */
    data class VerifiedPlugin(
        val id: String,
        val manifest: PluginManifest,
        val capabilities: List<String>,
        val tools: List<ToolDeclaration>,
        val entryCode: String,
    )

    @Serializable
    private data class PluginRegistry(val plugins: List<InstalledPlugin> = emptyList())

    private val pluginsDir = File(context.filesDir, "plugins")
    private val registryFile = File(context.filesDir, "plugin_registry.json")
    private val configStoreFile = File(context.filesDir, "plugin_configs.json")
    private var configStore: PluginConfigStore = PluginConfigStore.fromFile(configStoreFile)

    @Volatile
    private var cached: List<InstalledPlugin> = loadRegistry()

    /** R-SVC-07: registry leermodificarmutex,Evite instalaciones simultáneas/Desinstale la mala escritura JSON. */
    private val registryMutex = Mutex()

    /** P2-26: Enviar exclusión mutua por ID de complemento — Instalación simultánea del mismo complemento/Descargar serialización,Eliminar la puesta en escena de coberturas escalonadas. */
    private val installLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    /**
     * Lea y revise paquetes de complementos externos, devolviendo decisiones de seguridad y vistas previas de instalación sin código fuente.
     */
    suspend fun reviewFromUri(uri: Uri): Result<PluginSecurityGate.Decision> =
        withTempPluginFile(uri, "muse-plugin") { file -> reviewFromFile(file) }

    /**
     * Guarde el paquete sin firmar encuadernado en vista previa como un borrador deshabilitado.
     *
     * Esta ruta no se escribirá en la raíz de confianza, no se registrará como un complemento ejecutable y volverá a comparar el resumen de contenido para evitar la confirmación del usuario.
     * Después de obtener una vista previa, el URI se reemplaza con otro paquete. Un paquete de firma válido debe utilizar [installConfirmedFromUri].
     */
    suspend fun installDraftFromUri(uri: Uri, expectedPreview: PluginSecurityGate.InstallPreview): Result<InstalledPlugin> =
        withTempPluginFile(uri, "muse-plugin") { file ->
            installDraftFromFile(file, expectedPreview)
        }

    suspend fun installDraftFromFile(file: File, expectedPreview: PluginSecurityGate.InstallPreview): Result<InstalledPlugin> =
        withContext(Dispatchers.IO) {
            val loaded = loadPackage(file)
                .getOrElse { error -> return@withContext Result.failure(error) }
            val decision = PluginSecurityGate.review(loaded, trustedPublisherKeys())
            if (!decision.allowed) {
                return@withContext Result.failure(
                    IllegalStateException(decision.reason ?: "Error en la revisión de seguridad del complemento"),
                )
            }
            if (!PluginSecurityGate.hasSameContentIdentity(expectedPreview, decision.preview)) {
                return@withContext Result.failure(
                    IllegalStateException("El contenido del complemento ha cambiado después de la confirmación; obtenga una vista previa nuevamente"),
                )
            }
            commitInstall(
                loaded,
                installationConfirmed = false,
                signature = decision.signature,
            )
        }

    /** Lee un archivo y devuelve una decisión de puerta de seguridad puramente lógica para su uso en la interfaz de usuario de validación y las pruebas unitarias. */
    suspend fun reviewFromFile(file: File): Result<PluginSecurityGate.Decision> = withContext(Dispatchers.IO) {
        val loaded = loadPackage(file)
            .getOrElse { error -> return@withContext Result.failure(error) }
        Result.success(PluginSecurityGate.review(loaded, trustedPublisherKeys()))
    }

    /** Una asignación de clave pública de solo lectura de la raíz de confianza privada actual de Android; no expone la clave privada o la ruta de almacenamiento a la persona que llama. */
    private fun trustedPublisherKeys(): Map<String, String> = trustStore.list().associate { it.publisherId to it.publicKey }

    /**
     * Visto por el usuario [expectedPreview] Luego confirme e instale el complemento externo.
     *
     * El paquete relee, analiza y recalcula el resumen; Si el URI/archivo se reemplaza después de la vista previa, la validación falla sin
     * Escriba en el directorio o registro del complemento para evitar“Vista previa A. Ejecutar B”El bypass TOCTOU.
     */
    suspend fun installConfirmedFromUri(
        uri: Uri,
        expectedPreview: PluginSecurityGate.InstallPreview,
        trustPublisher: Boolean = false,
    ): Result<InstalledPlugin> = withTempPluginFile(uri, "muse-plugin") { file ->
        installConfirmedFromFile(file, expectedPreview, trustPublisher)
    }

    /**
     * Confirme e instale un archivo de complemento externo que coincida con el resumen de la vista previa.
     *
     * [trustPublisher] Sólo cuando el usuario hace clic explícitamente“Confía en el editor e instala”Cuando pasó a ser verdadero; de editor desconocido
     * La clave pública primero se firmará y verificará, luego se escribirá atómicamente en la raíz de confianza privada de Android y luego se volverá a verificar utilizando el mismo paquete cargado.
     * sin firmar/Los paquetes firmados de forma no válida nunca podrán convertirse en complementos ejecutables a través de esta ruta.
     */
    suspend fun installConfirmedFromFile(
        file: File,
        expectedPreview: PluginSecurityGate.InstallPreview,
        trustPublisher: Boolean = false,
    ): Result<InstalledPlugin> = withContext(Dispatchers.IO) {
        val loaded = loadPackage(file)
            .getOrElse { error -> return@withContext Result.failure(error) }
        var decision = PluginSecurityGate.review(loaded, trustedPublisherKeys())
        if (!decision.allowed) {
            return@withContext Result.failure(
                IllegalStateException(decision.reason ?: "Error en la revisión de seguridad del complemento"),
            )
        }
        if (!PluginSecurityGate.hasSameContentIdentity(expectedPreview, decision.preview)) {
            return@withContext Result.failure(
                IllegalStateException("El contenido del complemento ha cambiado después de la confirmación; obtenga una vista previa nuevamente"),
            )
        }
        if (decision.requiresPublisherTrust) {
            if (!trustPublisher) {
                return@withContext Result.failure(
                    IllegalStateException("Aún no se confía en el editor. Elija explícitamente confiar en el editor antes de realizar la instalación."),
                )
            }
            val signature = loaded.manifest.signature
                ?: return@withContext Result.failure(IllegalStateException("Al complemento le falta la firma del editor"))
            trustStore.trust(signature.publisherId, signature.publicKey)
                .getOrElse { error -> return@withContext Result.failure(error) }
            // Una vez escrita correctamente la raíz de confianza, se utiliza el mismo paquete para recalcular la decisión; no se puede publicar basándose únicamente en el estado de vista previa de la interfaz de usuario.
            decision = PluginSecurityGate.review(loaded, trustedPublisherKeys())
        }
        if (!decision.isInstallable) {
            return@withContext Result.failure(
                IllegalStateException(decision.reason ?: "Los complementos deben tener una firma válida en la que confíe la raíz de confianza local"),
            )
        }
        commitInstall(
            loaded,
            installationConfirmed = true,
            signature = decision.signature,
        )
    }

    /**
     * Confirme explícitamente los borradores de complementos instalados.
     *
     * [trustPublisher] Solo pasa verdadero si el usuario confirma explícitamente el emisor. Vuelva a leer el paquete completo y verifique antes de confirmar.
     * manifest estrategia,ECDSA Firma, raíz de confianza nativa y resumen de contenido; sin firmar/Editor desconocido/Los paquetes de manipulación siempre son
     * No puede convertirse en un complemento ejecutable.
     */
    suspend fun confirmInstallation(id: String, trustPublisher: Boolean = false): Result<InstalledPlugin> = withContext(Dispatchers.IO) {
        val plugin = findPlugin(id)
            ?: return@withContext Result.failure(IllegalStateException("Complemento no instalado: $id"))
        if (plugin.contentSha256.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Al complemento le falta un resumen de contenido y no se puede confirmar"))
        }
        val directory = File(pluginsDir, plugin.id)
        val loaded = loadInstalledPackage(directory)
            ?: return@withContext Result.failure(IllegalStateException("El contenido del complemento no se puede leer en su totalidad ni se puede confirmar."))
        var decision = PluginSecurityGate.review(loaded, trustedPublisherKeys())
        if (!decision.allowed) {
            return@withContext Result.failure(
                IllegalStateException(decision.reason ?: "Error en la revisión de seguridad del complemento"),
            )
        }
        val actual = PluginSecurityGate.contentSha256(loaded)
        if (actual != plugin.contentSha256) {
            return@withContext Result.failure(IllegalStateException("El resumen del contenido del complemento no coincide; vuelva a importarlo"))
        }
        if (decision.requiresPublisherTrust) {
            if (!trustPublisher) {
                return@withContext Result.failure(
                    IllegalStateException("Aún no se confía en el editor. Elija explícitamente confiar en el editor antes de realizar la instalación."),
                )
            }
            val signature = loaded.manifest.signature
                ?: return@withContext Result.failure(IllegalStateException("Al complemento le falta la firma del editor"))
            trustStore.trust(signature.publisherId, signature.publicKey)
                .getOrElse { error -> return@withContext Result.failure(error) }
            decision = PluginSecurityGate.review(loaded, trustedPublisherKeys())
        }
        if (!decision.isInstallable) {
            return@withContext Result.failure(
                IllegalStateException(decision.reason ?: "Los complementos deben tener una firma válida en la que confíe la raíz de confianza local"),
            )
        }
        val confirmed = plugin.copy(
            installationConfirmed = true,
            // A revalidation call must not turn a deliberately disabled plugin back on.
            enabled = if (plugin.installationConfirmed) {
                plugin.enabled
            } else {
                // P2-29: Respeto al confirmar manifest.enabled — Los paquetes declarados deshabilitados permanecen deshabilitados después de la confirmación,
                // Habilitado manualmente por el usuario,Ya no es obligatorio habilitar
                loaded.manifest.enabled
            },
            signatureStatus = decision.signature.status,
            publisherId = decision.signature.publisherId,
            publisherKeyFingerprint = decision.signature.fingerprint,
            // Activación tras la confirmación: registre sincrónicamente la identificación de habilidad del manifiesto actual y limpie el formato anterior/Habilidad eliminada.
            registeredSkillIds = collectRegisteredSkillIds(plugin, loaded.manifest),
        )
        try {
            registryMutex.withLock {
                cached = cached.map { if (it.id == id) confirmed else it }
                persistRegistryOrThrow()
            }
        } catch (error: Exception) {
            Logger.e(TAG, "Error al escribir la confirmación del complemento: $id", error)
            return@withContext Result.failure(error)
        }
        // upsert Presionará confirmado. habilitado para iniciar sesión y evitar“Complemento confirmado pero habilidad aún deshabilitada”o
        // “Complemento deshabilitado pero confirma la llamada para volver a habilitar la habilidad”El Estado está desgarrado.
        syncSkills(plugin, confirmed)
        markRegistryChanged()
        Result.success(confirmed)
    }

    /**
     * Construya el ZIP con el host y escriba un**borrador sin firmar**.
     *
     * El asistente (modelo grande) sólo puede llegar hasta cierto punto:manifest Presionado por el anfitrión [PluginAuthoringRules] generar,
     * Almacenamiento de tiro fijo `installationConfirmed=false`,`enabled=false`,[loadVerifiedPlugin]
     * Siempre devuelve nulo, por lo que este código no se puede ejecutar hasta que el usuario asienta.
     *
     * La verificación precede a la escritura en el disco:id/Nombre/versión/tamaño del código/forma de herramienta/¿Existe la función?/Cualquier elemento en la lista blanca de capacidades
     * Si no se aprueba, devolverá directamente el motivo chino y no quedará ningún producto semiacabado en el directorio o registro del complemento.
     */
    suspend fun createAuthoredDraft(request: AuthoredPluginRequest): Result<InstalledPlugin> = withContext(Dispatchers.IO) {
        PluginAuthoringRules.validate(request)?.let { reason ->
            Logger.w(TAG, "Borrador del complemento asistente rechazado: ${request.id} ($reason)")
            return@withContext Result.failure(IllegalArgumentException(reason))
        }
        val manifest = PluginAuthoringRules.manifestOf(request)
        val zipBytes = runCatching {
            PluginAuthoringRules.buildZip(manifest, request.code)
        }.getOrElse { error ->
            return@withContext Result.failure(
                IllegalStateException("Falló la compilación del paquete de complementos: ${error.message}", error),
            )
        }
        val temp = File(context.cacheDir, "authored_draft_${System.nanoTime()}.muse-plugin")
        try {
            temp.writeBytes(zipBytes)
            val decision = reviewFromFile(temp)
                .getOrElse { error -> return@withContext Result.failure(error) }
            if (!decision.allowed) {
                return@withContext Result.failure(
                    IllegalStateException(decision.reason ?: "Error en la revisión de seguridad del complemento"),
                )
            }
            // Reutilice la ruta de la biblioteca borrador existente: el resumen de contenido está vinculado a la vista previa y la raíz de confianza no está escrita ni habilitada.
            installDraftFromFile(temp, decision.preview)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Logger.e(TAG, "Error al redactar el borrador del complemento del asistente: ${request.id}", error)
            Result.failure(error)
        } finally {
            runCatching { if (temp.exists()) temp.delete() }
        }
    }

    /**
     * Firme y habilite un borrador instalado sin firmar con la clave de autor nativa.
     *
     * La persona que llama debe haber obtenido el consentimiento explícito del usuario (UI El cuadro de diálogo de confirmación muestra el nombre del complemento/id/versión/lista de herramientas),
     * Por lo tanto, la clave pública del autor local se escribirá en la raíz de confianza según sea necesario. [PluginTrustStore.trust];Escribir en la raíz de la confianza misma.
     * No significa liberación——El paquete de firma aún debe volver a aprobarse. [PluginSecurityGate.review] y
     * `isInstallable == true` será presentado.
     *
     * El orden es parte del límite de seguridad: primero revise el borrador del resumen de identidad y contenido, y regrese antes de escribir en el disco si falla algún paso.
     * Las instalaciones existentes (con versiones firmadas confirmadas) permanecen sin cambios. La política de versiones no se omite: reinstalar la misma versión mediante
     * [PluginVersionPolicy] Si se libera, la degradación seguirá bloqueada.
     */
    suspend fun signAndEnableDraft(id: String): Result<InstalledPlugin> = withContext(Dispatchers.IO) {
        val plugin = findPlugin(id)
            ?: return@withContext Result.failure(IllegalStateException("Complemento no instalado: $id"))
        if (plugin.installationConfirmed || plugin.signatureStatus != PluginSecurityGate.SignatureStatus.UNSIGNED) {
            return@withContext Result.failure(
                IllegalStateException("Solo los borradores deshabilitados sin firmar se pueden firmar habilitados: $id"),
            )
        }
        if (plugin.contentSha256.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Al complemento le falta un resumen de contenido y no se puede firmar"))
        }
        val loaded = loadInstalledPackage(File(pluginsDir, plugin.id))
            ?: return@withContext Result.failure(IllegalStateException("El contenido del complemento no se puede leer por completo ni firmar."))
        if (loaded.manifest.id != plugin.id) {
            return@withContext Result.failure(IllegalStateException("El directorio del complemento no coincide con la identificación del registro y se rechaza la firma."))
        }
        // Es posible que el borrador haya sido modificado después de su generación: los resúmenes inconsistentes indican que el código ya no es el que se revisó.
        if (PluginSecurityGate.contentSha256(loaded) != plugin.contentSha256) {
            return@withContext Result.failure(
                IllegalStateException("El contenido del complemento se modificó antes de firmar; vuelva a generar el borrador."),
            )
        }
        val publicKey = localAuthorKeyStore.publicKeyBase64()
            .getOrElse { error -> return@withContext Result.failure(error) }
        val envelope = PluginSignature(
            publisherId = localAuthorKeyStore.publisherId,
            publicKey = publicKey,
            signature = "",
            algorithm = PluginSecurityGate.SIGNATURE_ALGORITHM,
        )
        val packageForSigning = loaded.copy(manifest = loaded.manifest.copy(signature = envelope))
        val signatureBytes = localAuthorKeyStore.sign(PluginSecurityGate.signaturePayload(packageForSigning))
            .getOrElse { error -> return@withContext Result.failure(error) }
        val signedManifest = packageForSigning.manifest.copy(
            signature = envelope.copy(signature = Base64.getEncoder().encodeToString(signatureBytes)),
        )
        val zipBytes = runCatching {
            PluginAuthoringRules.buildZip(signedManifest, packageForSigning.entryCode, packageForSigning.extraFiles)
        }.getOrElse { error ->
            return@withContext Result.failure(IllegalStateException("Falló la compilación del paquete firmado: ${error.message}", error))
        }
        val temp = File(context.cacheDir, "authored_signed_${System.nanoTime()}.muse-plugin")
        try {
            temp.writeBytes(zipBytes)
            var decision = reviewFromFile(temp).getOrElse { error -> return@withContext Result.failure(error) }
            if (!decision.allowed) {
                return@withContext Result.failure(
                    IllegalStateException(decision.reason ?: "La revisión de seguridad del complemento firmado falló"),
                )
            }
            if (decision.signature.status == PluginSecurityGate.SignatureStatus.VALID_UNTRUSTED) {
                // El usuario ha aceptado explícitamente confiar en la clave del autor local en el cuadro de diálogo de confirmación; No se permite el reemplazo de claves para el mismo ID de emisor.
                trustStore.trust(localAuthorKeyStore.publisherId, publicKey)
                    .getOrElse { error -> return@withContext Result.failure(error) }
                decision = reviewFromFile(temp).getOrElse { error -> return@withContext Result.failure(error) }
            }
            if (!decision.isInstallable) {
                return@withContext Result.failure(
                    IllegalStateException(decision.reason ?: "La firma del autor nativo no es de confianza y se niega a habilitarse"),
                )
            }
            installConfirmedFromFile(temp, decision.preview, trustPublisher = true)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Logger.e(TAG, "No se pudo habilitar la firma del borrador del complemento del asistente: $id", error)
            Result.failure(error)
        } finally {
            runCatching { if (temp.exists()) temp.delete() }
        }
    }

    private suspend fun <T> withTempPluginFile(uri: Uri, suffix: String, operation: suspend (File) -> Result<T>): Result<T> =
        withContext(Dispatchers.IO) {
            val temp = File(context.cacheDir, "plugin_import_${System.nanoTime()}.$suffix")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output -> copyLimited(input, output, MAX_PLUGIN_PACKAGE_BYTES) }
                } ?: return@withContext Result.failure(IllegalStateException("El archivo seleccionado no se puede abrir"))
                operation(temp)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (error: Exception) {
                Result.failure(error)
            } finally {
                runCatching { if (temp.exists()) temp.delete() }
            }
        }

    private fun loadPackage(file: File): Result<PluginPackageLoader.LoadedPluginPackage> {
        val bytes = readPluginPackage(file).getOrElse { error -> return Result.failure(error) }
        return when (val loaded = PluginPackageLoader.loadFromZip(bytes)) {
            is PluginPackageLoader.Result.Err -> Result.failure(IllegalStateException(loaded.reason))
            is PluginPackageLoader.Result.Ok -> Result.success(loaded.package_)
        }
    }

    /**
     * P2-26: Serializar confirmaciones por ID de complemento — Instalación simultánea del mismo complemento.(Actualización del mercado + importación manual activadas simultáneamente)tiempo,
     * staging/Cambio de directorio/Los registros no están entrelazados entre sí.,El fallo ya no deja un estado a medio instalar.
     */
    private suspend fun commitInstall(
        loaded: PluginPackageLoader.LoadedPluginPackage,
        installationConfirmed: Boolean,
        signature: PluginSecurityGate.SignatureVerification =
            PluginSecurityGate.SignatureVerification(
                status = PluginSecurityGate.SignatureStatus.UNSIGNED,
            ),
        /** Solo la entrada de reversión es verdadera: la degradación se permite cuando el usuario regresa activamente a la versión histórica. */
        allowDowngrade: Boolean = false,
    ): Result<InstalledPlugin> {
        val lockId = loaded.manifest.id
        return installLocks.getOrPut(lockId) { Mutex() }.withLock {
            commitInstallLocked(loaded, installationConfirmed, signature, allowDowngrade)
        }
    }

    private suspend fun commitInstallLocked(
        loaded: PluginPackageLoader.LoadedPluginPackage,
        installationConfirmed: Boolean,
        signature: PluginSecurityGate.SignatureVerification =
            PluginSecurityGate.SignatureVerification(
                status = PluginSecurityGate.SignatureStatus.UNSIGNED,
            ),
        /** Solo la entrada de reversión es verdadera: la degradación se permite cuando el usuario regresa activamente a la versión histórica. */
        allowDowngrade: Boolean = false,
    ): Result<InstalledPlugin> {
        val manifest = loaded.manifest
        val contentSha256 = PluginSecurityGate.contentSha256(loaded)
        val previous = cached.firstOrNull { it.id == manifest.id }
        // Estrategia de versión: instalación degradada, reemplazo del editor, el paquete sin firmar sobrescribe la instalación firmada,minAppVersion más alto que el actual App
        // Todos se detuvieron antes de escribir. Poner aquí porque es la ruta para todas las instalaciones (borrador/importar/mercado/reversión).
        val versionDecision = PluginVersionPolicy.evaluate(
            candidate = PluginInstallCandidate(
                id = manifest.id,
                version = manifest.version,
                publisherId = signature.publisherId,
                publisherKeyFingerprint = signature.fingerprint,
                signed = signature.isCryptographicallyValid,
                minAppVersion = manifest.minAppVersion,
            ),
            installed = previous?.let { installed ->
                InstalledPluginIdentity(
                    version = installed.version,
                    publisherId = installed.publisherId,
                    publisherKeyFingerprint = installed.publisherKeyFingerprint,
                    signed = installed.signatureStatus == PluginSecurityGate.SignatureStatus.VALID_TRUSTED,
                )
            },
            appVersion = appVersionName,
            allowDowngrade = allowDowngrade,
            // Los complementos firmados por el autor nativo pueden sobrescribirse con nuevos borradores, de modo que el asistente pueda iterar los complementos escritos por él mismo.
            localAuthorPublisherId = localAuthorKeyStore.publisherId,
        )
        if (versionDecision is PluginVersionDecision.Reject) {
            Logger.w(TAG, "La instalación del complemento es rechazada por la política de versión: ${manifest.id} (${versionDecision.reason})")
            return Result.failure(IllegalStateException(versionDecision.reason))
        }
        // P0: Detectar conflictos de identificación de habilidades antes de la instalación (usuario/La habilidad incorporada está ocupada o el nombre de la herramienta se repite en el manifiesto),
        // Rechace en lugar de sobrescribir silenciosamente las habilidades existentes que no pertenecen a este complemento.
        val conflict = runCatching { findSkillConflict(manifest) }.getOrElse { error ->
            return Result.failure(
                IllegalStateException("Error en la detección de conflictos de habilidades del complemento: ${error.message}", error),
            )
        }
        if (conflict != null) {
            Logger.w(TAG, "Instalación del complemento denegada: ${manifest.id} ($conflict)")
            return Result.failure(IllegalStateException(conflict))
        }
        val targetDir = File(pluginsDir, manifest.id)
        val stagingDir = File(pluginsDir, ".staging_${manifest.id}_${System.nanoTime()}")
        val previousCached = cached
        var backupDir: File? = null
        // Antes de reemplazar, conserve una copia histórica de la versión vigente actualmente: los usuarios aún pueden volver a ella después de la actualización.
        // Solo se mantendrán registros de fallas y la instalación nunca se bloqueará (las copias históricas son una capacidad conveniente, no un requisito previo para la instalación).
        previous?.let { installed ->
            if (targetDir.isDirectory) {
                versionStore.retain(
                    pluginId = installed.id,
                    version = installed.version,
                    sourceDirectory = targetDir,
                    record = RetainedVersionRecord(
                        version = installed.version,
                        publisherId = installed.publisherId,
                        publisherKeyFingerprint = installed.publisherKeyFingerprint,
                        contentSha256 = installed.contentSha256,
                        retainedAtEpochMs = 0L,
                    ),
                ).onFailure { error ->
                    Logger.w(TAG, "No se pudieron conservar las versiones históricas(No afecta la instalación): ${installed.id}@${installed.version}", error)
                }
            }
        }
        val installResult = runCatching {
            // Primero escriba completamente en el directorio provisional bajo el mismo sistema de archivos y luego cambie al directorio activo.
            // La versión anterior se conserva en una copia de seguridad temporal y se restaura cuando el conmutador falla para evitar interrupciones en la instalación y dejar complementos a medio instalar.
            writePluginDirectory(stagingDir, manifest, loaded)
            backupDir = replacePluginDirectory(stagingDir, targetDir)
        }
        if (installResult.isFailure) {
            if (stagingDir.exists()) stagingDir.deleteRecursively()
            installResult.exceptionOrNull()?.let { error ->
                Logger.e(TAG, "Error al enviar la instalación del complemento: ${manifest.id}", error)
            }
            return Result.failure(
                installResult.exceptionOrNull() ?: IllegalStateException("Error al enviar la instalación del complemento"),
            )
        }

        val installed = InstalledPlugin(
            id = manifest.id,
            name = manifest.name,
            version = manifest.version,
            author = manifest.author,
            description = manifest.description,
            entry = manifest.entry,
            kind = manifest.kind,
            trust = manifest.trust,
            capabilities = manifest.capabilities,
            permissions = manifest.permissions,
            tools = manifest.tools,
            // Los paquetes externos no confirmados están permanentemente deshabilitados;manifest.enabled Sólo entrará en vigor tras una confirmación explícita.
            enabled = installationConfirmed && manifest.enabled,
            installationConfirmed = installationConfirmed,
            signatureStatus = signature.status,
            publisherId = signature.publisherId,
            publisherKeyFingerprint = signature.fingerprint,
            contentSha256 = contentSha256,
            installedAt = System.currentTimeMillis(),
            // Acumule registros de ID de habilidades registradas previamente (incluidos ID de formato antiguo). Al desinstalar, elimine todos los ID de habilidades que pertenecen a este complemento en consecuencia.
            // skill, en lugar de simplemente confiar en el manifiesto actual.
            registeredSkillIds = collectRegisteredSkillIds(previous, manifest),
        )
        try {
            registryMutex.withLock {
                cached = cached.filterNot { it.id == installed.id } + installed
                try {
                    persistRegistryOrThrow()
                } catch (error: Exception) {
                    cached = previousCached
                    rollbackActivatedPlugin(targetDir, backupDir)
                    throw error
                }
            }
        } catch (error: Exception) {
            Logger.e(TAG, "Error al enviar el registro del complemento, se ha restaurado la versión anterior: ${manifest.id}", error)
            return Result.failure(error)
        }
        backupDir?.deleteRecursively()
        // Mantenga la versión recién instalada: la dirección de reversión es reversible (puede retroceder después de revertir).
        versionStore.retain(
            pluginId = installed.id,
            version = installed.version,
            sourceDirectory = targetDir,
            record = RetainedVersionRecord(
                version = installed.version,
                publisherId = installed.publisherId,
                publisherKeyFingerprint = installed.publisherKeyFingerprint,
                contentSha256 = installed.contentSha256,
                retainedAtEpochMs = 0L,
            ),
        ).onFailure { error ->
            Logger.w(TAG, "No se pudo conservar la versión instalada(No afecta la instalación): ${installed.id}@${installed.version}", error)
        }
        // P0: Al actualizar, elimine la habilidad anterior que el nuevo manifiesto eliminó y luego registre la habilidad declarada actualmente.
        syncSkills(previous, installed)
        markRegistryChanged()
        Logger.i(
            TAG,
            "Complemento instalado: ${installed.id} v${installed.version}, " +
                "confirmed=${installed.installationConfirmed} enabled=${installed.enabled}",
        )
        return Result.success(installed)
    }

    suspend fun uninstall(id: String) {
        withContext(Dispatchers.IO) {
            val plugin = findPlugin(id) ?: return@withContext
            // P2-26: Bloqueado con instalación,evitar la instalación/Descarga escalonada
            installLocks.getOrPut(id) { Mutex() }.withLock {
                // P2-27: Primero escriba el registro y luego elimine el directorio. —
                //  Orden original "Eliminar directorio→"Escribir registro" aparece cuando falla la escritura en el registro."El directorio se ha eliminado pero todavía aparece en el registro."estado de desgarro.
                //  nuevo orden:Si la actualización del registro falla, todo permanece sin cambios.(sin lágrimas);Error al eliminar el directorio y solo quedan archivos huérfanos(Mensaje de registro).
                val previousEntry = cached
                registryMutex.withLock {
                    cached = cached.filterNot { it.id == id }
                    val persisted = runCatching { persistRegistryOrThrow() }
                    if (persisted.isFailure) {
                        cached = previousEntry
                        Logger.e(TAG, "Error al eliminar el registro del complemento,Desinstalación cancelada(Estado sin cambios): $id", persisted.exceptionOrNull())
                        return@withLock
                    }
                }
                val deleteResult = resultOf { File(pluginsDir, id).deleteRecursively() }
                    .onError { msg, t -> Logger.e(TAG, "Error al eliminar el directorio de complementos: $id ($msg)", t) }
                if (deleteResult.isError || deleteResult.getOrNull() == false) {
                    // Registro eliminado,Error al eliminar el directorio y solo quedan archivos huérfanos,No afecta la semántica de desinstalación.
                    Logger.w(TAG, "Error al eliminar el directorio del complemento, pero se eliminó el registro(archivos huérfanos heredados,Se puede limpiar manualmente): $id")
                }
                // P0: El alcance de la eliminación son todas las habilidades registradas en el registro + habilidades derivadas del manifiesto actual (incluidos los formatos antiguos).
                // Ya no confiar únicamente en el manifiesto actual para evitar los restos de versiones históricas.
                skillIdsOwnedBy(plugin).forEach { skillId -> deleteSkillIfOwned(id, skillId) }
                // Desinstalar significa perder el objetivo de reversión: las copias históricas no se conservan (para evitar poder recuperar el código que el usuario pensó que se había eliminado después de la desinstalación).
                versionStore.deleteAll(id)
                markRegistryChanged()
                Logger.i(TAG, "El complemento ha sido desinstalado: $id")
            }
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        withContext(Dispatchers.IO) {
            val plugin = findPlugin(id) ?: return@withContext
            if (enabled && !plugin.installationConfirmed) {
                // El código externo primero debe someterse a una confirmación de instalación explícita; se conserva la antigua API de la Unidad, pero se rechaza la habilitación silenciosa.
                Logger.w(TAG, "Denegar la habilitación de complementos externos no confirmados: $id")
                return@withContext
            }
            registryMutex.withLock {
                cached = cached.map { if (it.id == id) it.copy(enabled = enabled) else it }
                persistRegistry()
            }
            skillIdsOwnedBy(plugin).forEach { skillId ->
                runCatching { skillRepository.setEnabled(skillId, enabled) }
            }
            markRegistryChanged()
        }
    }

    fun list(): List<InstalledPlugin> = cached

    fun findPlugin(id: String): InstalledPlugin? = cached.firstOrNull { it.id == id }

    /** P0-9: Lista de editores de confianza actualmente(Comparte la misma instancia del almacén de confianza que la verificación de la instalación.). */
    fun trustedPublishers(): List<PluginTrustStore.TrustedPublisher> = trustStore.list()

    /**
     * P0-9: Revocar la raíz de confianza de un emisor.
     *
     * Después de la revocación, los complementos firmados por el editor ya no podrán pasar la verificación.(Ejecutar la próxima vez/Vuelva a verificar antes de actualizar),y deshabilitado sincrónicamente
     * Todos los complementos instalados y habilitados de este editor,evitar"El complemento se ejecuta normalmente pero la confianza se ha reciclado"la ilusión.
     *
     * @return emisor revocado id;Emisor no existe Volver null
     */
    suspend fun revokePublisher(publisherId: String): String? {
        val revoked = trustStore.revoke(publisherId)
        if (!revoked) return null
        cached.filter { it.publisherId == publisherId && it.enabled }.forEach { plugin ->
            setEnabled(plugin.id, false)
            Logger.w(TAG, "Emisor $publisherId confianza revocada,Desactivar complementos automáticamente ${plugin.id}")
        }
        return publisherId
    }

    /** Señal de cambio de registro:Instalación/Desinstalar/Confirmar/Incrementa automáticamente después de un inicio y una parada exitosos.,Lista de aspectos del controlador recargada. */
    private val revision = MutableStateFlow(0)
    override val revisionFlow: StateFlow<Int> = revision.asStateFlow()

    /** Las versiones históricas que se han conservado (incluidas las versiones actualmente vigentes), tienen prioridad las nuevas versiones. */
    fun listRetainedVersions(id: String): List<RetainedPluginVersion> = versionStore.list(id)

    /**
     * Retroceder a una versión histórica conservada.
     *
     * Revertir no es "confiar en el archivo antiguo": la copia histórica pasará nuevamente por la lectura del paquete, la política de inventario, la confianza del emisor y la verificación de firma.
     * Y compárelo con el resumen de contenido registrado durante la retención. Si no se pasa algún paso, no se escribirá en el disco. Después de pasar la verificación, reutilice la ruta de envío de instalación
     * (Las degradaciones están explícitamente permitidas), por lo que el registro de habilidades, el cambio de directorio activo y la recuperación de fallas son todos idénticos a una instalación normal.
     */
    suspend fun rollbackTo(id: String, version: String): Result<InstalledPlugin> = withContext(Dispatchers.IO) {
        val retained = versionStore.find(id, version)
            ?: return@withContext Result.failure(
                IllegalStateException("Sin versión revertida: $id@$version"),
            )
        val loaded = loadInstalledPackage(retained.directory)
            ?: return@withContext Result.failure(IllegalStateException("El contenido de la versión histórica es ilegible."))
        if (loaded.manifest.id != id) {
            return@withContext Result.failure(IllegalStateException("La identificación del complemento de la versión histórica no coincide con el objetivo"))
        }
        if (PluginSecurityGate.contentSha256(loaded) != retained.record.contentSha256) {
            return@withContext Result.failure(IllegalStateException("Se ha modificado el contenido de la versión histórica y se rechaza la reversión."))
        }
        val decision = PluginSecurityGate.review(loaded, trustedPublisherKeys())
        if (!decision.isInstallable) {
            return@withContext Result.failure(
                IllegalStateException(decision.reason ?: "La verificación de la firma de la versión histórica falló y se rechazó la reversión"),
            )
        }
        val signature = decision.signature
        if (signature.publisherId != retained.record.publisherId ||
            !signature.fingerprint.equals(retained.record.publisherKeyFingerprint, ignoreCase = true)
        ) {
            return@withContext Result.failure(IllegalStateException("La versión histórica del emisor es inconsistente con el registro de retención."))
        }
        commitInstall(
            loaded,
            installationConfirmed = true,
            signature = signature,
            allowDowngrade = true,
        )
    }

    /**
     * Máscara de burbuja declarativa proporcionada por el complemento instalado.
     *
     * con [loadVerifiedPlugin] Camino seguro al mismo nivel.:Reconstruya el paquete desde el directorio de complementos cada vez, revise la política de manifiesto y
     * Resumen de contenido de comparación y confianza del editor,y solo aceptar `kind == "ui-skin"` paquete;La piel misma todavía tiene que pasar
     * [BubbleSkinValidator] Es legal. Si algún paso falla, no se devolverán entradas disponibles.,Por lo tanto, el lado del consumidor vuelve a recurrir a la
     * default;Las máscaras son siempre solo datos declarativos.,Es imposible que el código del complemento ingrese a Compose.
     *
     * @param includeDisabled true También devuelve Instalado pero actualmente no disponible(No confirmado/Discapacitado/fracaso de confianza)piel,
     * es [InstalledSkin.enabled] para false;Las rutas de renderizado deben usar valores predeterminados,Consuma únicamente las máscaras disponibles.
     */
    override fun listInstalledSkins(includeDisabled: Boolean): List<InstalledSkin> = cached.asSequence()
        .filter { it.kind == PluginSecurityGate.UI_SKIN_KIND }
        .mapNotNull { plugin -> skinEntry(plugin, includeDisabled) }
        // La identificación de la máscara es la única clave para la selección y el retorno.:Solo se conserva una fuente para la misma identificación.,Evite la ambigüedad semántica de elección.
        .distinctBy { it.skin.id }
        .toList()

    /**
     * Lea la entrada del skin para un solo complemento.
     *
     * solo leer ir [loadInstalledPackage] restricciones de ruta(Lista de verificación/Todos los recursos están en el directorio de complementos.,Negarse a cruzar la línea con
     * Tipos de archivos no compatibles);[includeDisabled] Sólo afecta a los indicadores de disponibilidad.,No afecta el formato ni el rigor de la verificación.
     */
    private fun skinEntry(plugin: InstalledPlugin, includeDisabled: Boolean): InstalledSkin? {
        val loaded = loadInstalledPackage(File(pluginsDir, plugin.id)) ?: run {
            Logger.w(TAG, "No se pudo leer la máscara del complemento,No se puede reconstruir el paquete de complementos: ${plugin.id}")
            return null
        }
        if (loaded.manifest.id != plugin.id) {
            Logger.w(TAG, "El directorio del complemento no coincide con la identificación del registro,rechazar la piel: ${plugin.id}")
            return null
        }
        val skin = PluginPackageLoader.extractUiSkin(loaded) ?: return null
        if (!BubbleSkinValidator.isValid(skin)) {
            Logger.w(TAG, "Verificación fallida del skin del complemento,ignorado: ${plugin.id}/${skin.id}")
            return null
        }
        val usable = plugin.installationConfirmed && plugin.enabled && verifyInstalledPackage(plugin, loaded)
        if (!usable && !includeDisabled) return null
        return InstalledSkin(
            pluginId = plugin.id,
            pluginName = plugin.name,
            version = plugin.version,
            enabled = usable,
            skin = skin,
        )
    }

    /**
     * Revise la cadena de confianza y el resumen de contenido de los complementos instalados.,con [loadVerifiedPlugin] Utilice el mismo conjunto de juicios.
     *
     * No comprobando aquí [InstalledPlugin.enabled]/[InstalledPlugin.installationConfirmed],
     * Depende de la persona que llama distinguir entre "no disponible pero aún visible" y "rechazado para cargar".
     */
    private fun verifyInstalledPackage(plugin: InstalledPlugin, loaded: PluginPackageLoader.LoadedPluginPackage): Boolean = runCatching {
        val decision = PluginSecurityGate.review(loaded, trustedPublisherKeys())
        check(decision.isInstallable) {
            decision.reason ?: "Los complementos deben tener una firma válida en la que confíe la raíz de confianza local"
        }
        check(PluginSecurityGate.contentSha256(loaded) == plugin.contentSha256) {
            "El resumen del contenido del complemento no coincide"
        }
    }.onFailure { error ->
        Logger.w(TAG, "Error en la revisión del paquete de máscara del complemento,Marcado como no disponible: ${plugin.id}", error)
    }.isSuccess

    private fun markRegistryChanged() {
        revision.update { it + 1 }
    }

    /**
     * Envoltura delgada compatible con personas que llaman antiguas: equivalente a [loadVerifiedPlugin] código de entrada.
     *
     * La nueva ruta de ejecución debe usarse directamente [loadVerifiedPlugin]/[loadVerifiedFunction], para obtener al mismo tiempo
     * Capacidades revalidadas y declaraciones de herramientas.
     */
    fun loadEntryCode(id: String): String? = loadVerifiedPlugin(id)?.entryCode

    /**
     * P0: Vuelva a validar el complemento y vuelva a la vista ejecutable.
     *
     * Cada llamada relee el paquete completo en el disco, comprueba la puerta de seguridad (firma/raíz de confianza/estrategia de lista de verificación) y resumen de contenido;
     * regresó [VerifiedPlugin.capabilities] con [VerifiedPlugin.tools] de esta revisión
     * manifest, en lugar de [InstalledPlugin.capabilities] Espere los campos de caché del registro.
     */
    fun loadVerifiedPlugin(id: String): VerifiedPlugin? {
        val plugin = findPlugin(id) ?: return null
        if (!plugin.installationConfirmed || !plugin.enabled) {
            Logger.w(TAG, "Negarse a cargar complementos externos no confirmados o deshabilitados: $id")
            return null
        }
        val directory = File(pluginsDir, plugin.id)
        return runCatching {
            val loaded = loadInstalledPackage(directory)
                ?: error("El contenido del complemento no se puede leer completamente")
            val decision = PluginSecurityGate.review(loaded, trustedPublisherKeys())
            check(decision.isInstallable) {
                decision.reason ?: "Los complementos deben tener una firma válida en la que confíe la raíz de confianza local"
            }
            val actual = PluginSecurityGate.contentSha256(loaded)
            check(actual == plugin.contentSha256) { "El resumen del contenido del complemento no coincide" }
            VerifiedPlugin(
                id = plugin.id,
                manifest = loaded.manifest,
                capabilities = loaded.manifest.capabilities,
                tools = loaded.manifest.tools,
                entryCode = loaded.entryCode,
            )
        }.onFailure { error ->
            Logger.e(TAG, "La firma del complemento o la verificación de integridad fallaron y se rechazó la carga.: ${plugin.id}", error)
        }.getOrNull()
    }

    /**
     * P0: Se llama a la verificación antes de ejecutar [functionName] Todavía existe en el manifiesto del disco actual.
     *
     * Es posible que se hayan eliminado registros de habilidades antiguos después de una actualización del complemento/Las funciones renombradas deben rechazarse antes de ingresar al entorno limitado de JS.
     */
    fun loadVerifiedFunction(id: String, functionName: String): VerifiedPlugin? {
        val verified = loadVerifiedPlugin(id) ?: return null
        if (verified.tools.none { it.functionName == functionName }) {
            Logger.w(TAG, "Negarse a ejecutar funciones no declaradas en el manifiesto.: $id/$functionName")
            return null
        }
        return verified
    }

    /** Reconstruya el paquete completo desde el directorio instalado, leyendo solo las rutas relativas seguras admitidas por el cargador. */
    private fun loadInstalledPackage(directory: File): PluginPackageLoader.LoadedPluginPackage? = runCatching {
        require(directory.isDirectory) { "El directorio de complementos no existe" }
        val canonicalRoot = directory.canonicalFile
        val manifestFile = File(canonicalRoot, "manifest.json").canonicalFile
        require(manifestFile.parentFile == canonicalRoot) { "La ruta del manifiesto del complemento es ilegal" }
        val manifest = AppJson.decodeFromString<PluginManifest>(manifestFile.readText())
        val entryFile = File(canonicalRoot, manifest.entry).canonicalFile
        require(isWithin(canonicalRoot, entryFile)) { "Ruta de entrada del complemento fuera de los límites" }
        // ui-skin El paquete skin no tiene entrada JS.(El paquete no debe contener ningún .js,Distribuir solo skin.json),
        // El contenido de la entrada es el mismo que cuando se carga zip y se fija en una cadena vacía.;Los archivos de entrada que faltan para otros tipos siguen siendo errores,No se puede liberar.
        val entryCode = if (entryFile.isFile) {
            entryFile.readText()
        } else {
            require(manifest.kind == PluginSecurityGate.UI_SKIN_KIND) {
                "Al complemento le falta el archivo de entrada: ${manifest.entry}"
            }
            ""
        }
        val extraFiles = linkedMapOf<String, String>()
        directory.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val canonicalFile = file.canonicalFile
                require(isWithin(canonicalRoot, canonicalFile)) { "Ruta del archivo de complemento fuera de límites" }
                if (canonicalFile == manifestFile || canonicalFile == entryFile) return@forEach
                val relative = canonicalFile.relativeTo(canonicalRoot).invariantSeparatorsPath
                require(relative.isNotBlank() && !relative.contains("..") && !relative.contains('\\')) {
                    "Ruta de archivo adicional del complemento ilegal"
                }
                require(
                    relative.endsWith(".js") || relative.endsWith(".json") ||
                        relative.endsWith(".md") || relative.endsWith(".py"),
                ) {
                    "El complemento contiene un tipo de archivo no compatible"
                }
                extraFiles[relative] = canonicalFile.readText()
            }
        PluginPackageLoader.LoadedPluginPackage(
            manifest = manifest,
            entryCode = entryCode,
            extraFiles = extraFiles,
        )
    }.getOrNull()

    private fun isWithin(root: File, file: File): Boolean = file == root || file.path.startsWith(root.path + File.separator)

    private suspend fun registerSkills(plugin: InstalledPlugin) {
        plugin.tools.forEach { tool ->
            val entity = SkillEntity(
                id = skillId(plugin.id, tool.name),
                name = "${plugin.name} · ${tool.name}",
                description = tool.description,
                parametersJson = tool.parametersJson.ifBlank { "{}" },
                requiredJson = tool.requiredJson.ifBlank { "[]" },
                implementationKotlin = "plugin:${plugin.id}:${tool.functionName}",
                enabled = plugin.enabled,
                category = "plugin",
            )
            runCatching { skillRepository.upsert(entity) }
                .onFailure { e -> Logger.w(TAG, "Error en el registro de la herramienta de complemento: ${entity.id}", e) }
        }
    }

    /**
     * P0 Instalación/Actualizar la conciliación de habilidades:
     * 1. Eliminar habilidades registradas en la versión anterior pero eliminadas por el nuevo manifiesto (incluida la identificación del formato anterior antes de la codificación del prefijo de longitud);
     * 2. Vuelva a registrar la habilidad declarada en el manifiesto actual.
     */
    private suspend fun syncSkills(previous: InstalledPlugin?, installed: InstalledPlugin) {
        val currentIds = installed.tools.map { skillId(installed.id, it.name) }.toSet()
        (skillIdsOwnedBy(previous) - currentIds).forEach { staleId ->
            deleteSkillIfOwned(installed.id, staleId)
        }
        registerSkills(installed)
    }

    /**
     * Deducir todos los identificadores de habilidades que pudo haber tenido un complemento: registros de registro ∪ actual/Herramienta de manifiesto de historial
     * (con ID de formato antiguo). La desinstalación y el cambio de estado sobrescribirán los restos de las versiones históricas en consecuencia.
     */
    private fun skillIdsOwnedBy(plugin: InstalledPlugin?): Set<String> {
        if (plugin == null) return emptySet()
        return buildSet {
            addAll(plugin.registeredSkillIds)
            plugin.tools.forEach { tool ->
                add(skillId(plugin.id, tool.name))
                add(legacySkillId(plugin.id, tool.name))
            }
        }
    }

    /**
     * Elimine únicamente las habilidades que pertenecen al complemento: la ruta de implementación debe apuntar a `plugin:<pluginId>:`.
     *
     * La codificación de prefijo de longitud ha eliminado las colisiones de ID entre complementos, pero los usuarios/Es posible que una habilidad incorporada aún ocupe la misma identificación;
     * La verificación de propiedad garantiza que la desinstalación no eliminará accidentalmente datos que no sean complementos.
     */
    private suspend fun deleteSkillIfOwned(pluginId: String, skillId: String) {
        runCatching {
            val existing = skillRepository.getById(skillId) ?: return@runCatching
            if (!existing.implementationKotlin.startsWith("plugin:$pluginId:")) {
                Logger.w(TAG, "Saltar la limpieza de habilidades que no son complementos: $skillId")
                return@runCatching
            }
            skillRepository.delete(skillId)
        }.onFailure { error -> Logger.w(TAG, "Error al limpiar la habilidad del complemento: $skillId", error) }
    }

    /**
     * Calcule el conjunto de identificadores de habilidades que deben registrarse en el registro después de esta instalación (historial acumulado de desinstalación y limpieza).
     */
    private fun collectRegisteredSkillIds(previous: InstalledPlugin?, manifest: PluginManifest): List<String> = buildList {
        if (previous != null) {
            addAll(previous.registeredSkillIds)
            previous.tools.forEach { tool ->
                add(skillId(previous.id, tool.name))
                add(legacySkillId(previous.id, tool.name))
            }
        }
        manifest.tools.forEach { add(skillId(manifest.id, it.name)) }
    }.distinct()

    /**
     * Detecte conflictos de identificación de habilidades antes de la instalación.
     *
     * Devolver el motivo del rechazo;null No indica ningún conflicto. Contiene dos categorías:
     * 1. Nombres de herramientas duplicados en el mismo manifiesto (se asignarán a la misma identificación de habilidad);
     * 2. La habilidad existente ocupa la misma identificación y no pertenece a este complemento (usuario/Habilidad incorporada o datos corruptos).
     */
    private suspend fun findSkillConflict(manifest: PluginManifest): String? {
        val ids = manifest.tools.map { skillId(manifest.id, it.name) }
        if (ids.size != ids.toSet().size) {
            return "El nombre de la herramienta de complemento está duplicado y no se puede asignar de forma única skill id: ${manifest.id}"
        }
        ids.forEach { id ->
            val existing = skillRepository.getById(id) ?: return@forEach
            if (!existing.implementationKotlin.startsWith("plugin:${manifest.id}:")) {
                return "La identificación de la habilidad del complemento entra en conflicto con la habilidad existente: $id"
            }
        }
        return null
    }

    private fun writePluginDirectory(directory: File, manifest: PluginManifest, pluginPackage: PluginPackageLoader.LoadedPluginPackage) {
        check(directory.mkdirs() || directory.isDirectory) { "No se puede crear el directorio provisional del complemento" }
        File(directory, "manifest.json").writeText(
            AppJson.encodeToString(PluginManifest.serializer(), manifest),
        )
        val canonicalRoot = directory.canonicalFile
        val entryFile = File(directory, manifest.entry).canonicalFile
        require(isWithin(canonicalRoot, entryFile)) { "Ruta de entrada del complemento fuera de los límites" }
        entryFile.parentFile?.mkdirs()
        entryFile.writeText(pluginPackage.entryCode)
        pluginPackage.extraFiles.forEach { (relative, content) ->
            val out = File(directory, relative).canonicalFile
            require(isWithin(canonicalRoot, out)) { "Ruta de archivo adicional del complemento fuera de límites" }
            out.parentFile?.mkdirs()
            out.writeText(content)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun replacePluginDirectory(stagingDir: File, targetDir: File): File? {
        val backupDir = File(pluginsDir, ".backup_${targetDir.name}_${System.nanoTime()}")
        var oldMoved = false
        try {
            if (targetDir.exists()) {
                check(targetDir.renameTo(backupDir)) { "No se pueden conservar las versiones antiguas del complemento" }
                oldMoved = true
            }
            check(stagingDir.renameTo(targetDir)) { "No se puede activar el directorio de preparación del complemento" }
            // El directorio antiguo se conserva hasta que la confirmación atómica del registro se realice correctamente y la persona que llama realice la limpieza final.
            return backupDir.takeIf { oldMoved }
        } catch (error: Exception) {
            if (targetDir.exists()) targetDir.deleteRecursively()
            if (oldMoved && backupDir.exists()) backupDir.renameTo(targetDir)
            throw error
        } finally {
            if (stagingDir.exists()) stagingDir.deleteRecursively()
            if (backupDir.exists() && targetDir.exists() && !oldMoved) backupDir.deleteRecursively()
        }
    }

    private fun rollbackActivatedPlugin(targetDir: File, backupDir: File?) {
        if (targetDir.exists()) targetDir.deleteRecursively()
        if (backupDir != null && backupDir.exists()) {
            check(backupDir.renameTo(targetDir)) { "No se puede restaurar la versión anterior del complemento" }
        }
    }

    private fun persistRegistryOrThrow() {
        val json = AppJson.encodeToString(PluginRegistry.serializer(), PluginRegistry(cached))
        // El registro se encuentra en estado recuperable: escritura,flush,fsync, Reemplazo atómico del mismo directorio para evitar que el proceso finalice y deje la mitad de JSON.
        AtomicFileStore.writeText(registryFile, json)
        // Vuelva a leer la verificación después del reemplazo y permita que la persona que llama ejecute el directorio si falla./Reversión de memoria.
        AppJson.decodeFromString<PluginRegistry>(registryFile.readText())
    }

    private fun persistRegistry() {
        runCatching { persistRegistryOrThrow() }
            .onFailure { e -> Logger.w(TAG, "Error al escribir el registro del complemento", e) }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun loadRegistry(): List<InstalledPlugin> {
        if (!registryFile.exists()) return emptyList()
        return runCatching {
            AppJson.decodeFromString<PluginRegistry>(registryFile.readText()).plugins
        }.getOrElse { e ->
            // Aísle el registro dañado para evitar volver a analizar el mismo archivo defectuoso en cada inicio; el directorio de complementos aún se conserva para el diagnóstico/Recuperación manual.
            AtomicFileStore.quarantine(registryFile, "plugin_registry_parse")
            Logger.w(TAG, "Error al analizar el registro del complemento,Archivo corrupto en cuarentena", e)
            emptyList()
        }
    }

    private fun readPluginPackage(file: File): Result<ByteArray> = runCatching {
        require(file.isFile) { "Los paquetes de complementos no son archivos comunes" }
        require(file.length() <= MAX_PLUGIN_PACKAGE_BYTES) {
            "El tamaño comprimido del paquete del complemento excede el límite(${MAX_PLUGIN_PACKAGE_BYTES / 1024 / 1024}MB)"
        }
        file.readBytes()
    }

    private fun copyLimited(input: InputStream, output: java.io.OutputStream, maxBytes: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            copied += read
            require(copied <= maxBytes) {
                "El tamaño comprimido del paquete del complemento excede el límite(${maxBytes / 1024 / 1024}MB)"
            }
            output.write(buffer, 0, read)
        }
    }

    companion object {
        private const val TAG = "PluginManager"

        /** El límite superior del tamaño original del paquete comprimido; coopera con el límite superior del cargador después de la descompresión para evitar el crecimiento ilimitado de readBytes/copyTo. */
        internal const val MAX_PLUGIN_PACKAGE_BYTES = 20L * 1024 * 1024

        /**
         * P0: Generar ID de habilidad del complemento.
         *
         * Utilice el prefijo de longitud en lugar del empalme de guión bajo:`pluginId` con `toolName` Se permiten guiones bajos,
         * viejo `plugin_<id>_<tool>` hará una diferencia (pluginId, toolName) Colisiones combinadas (p. ej.
         * `a_b`+`c` con `a`+`b_c`). El prefijo de longitud permite que la identificación se decodifique sin ambigüedades y ninguna combinación colisionará.
         */
        internal fun skillId(pluginId: String, toolName: String): String = "plugin_${pluginId.length}_${pluginId}_$toolName"

        /**
         * Se introdujo la identificación de formato anterior antes de que se introdujera la codificación de prefijo de longitud.
         *
         * Solo se utiliza para migrar y limpiar habilidades sobrantes de instalaciones históricas y ya no se usa para registrar nuevas habilidades.
         */
        internal fun legacySkillId(pluginId: String, toolName: String): String = "plugin_${pluginId}_$toolName"

        /**
         * v2.0: complemento de análisis skill id(`plugin_<len>_<pluginId>_<toolName>`).
         *
         * con [skillId] Las codificaciones son operaciones inversas entre sí.;Herramientas no complementarias(sin prefijo/Segmento de longitud ilegal)Devuelve nulo.
         */
        internal fun parsePluginToolId(id: String): Pair<String, String>? {
            if (!id.startsWith("plugin_")) return null
            val rest = id.removePrefix("plugin_")
            val sep = rest.indexOf('_')
            if (sep <= 0) return null
            val len = rest.substring(0, sep).toIntOrNull() ?: return null
            val after = rest.substring(sep + 1)
            if (len <= 0 || after.length <= len) return null
            val pluginId = after.substring(0, len)
            val toolName = after.substring(len).removePrefix("_")
            if (pluginId.isBlank() || toolName.isBlank()) return null
            return pluginId to toolName
        }
    }

    /**
     * v1.0.92: Lea el panel UI declarado por el complemento(manifest.uiPanel Archivo HTML apuntado).
     *
     * El camino está verificado por fuera de límites canónico.;Instalación no confirmada/No habilitado/Sin declaración/Devuelve nulo si falta el archivo.
     */
    fun loadPluginPanel(pluginId: String): String? {
        val plugin = findPlugin(pluginId) ?: return null
        if (!plugin.installationConfirmed || !plugin.enabled) return null
        return runCatching {
            val root = File(pluginsDir, pluginId).canonicalFile
            val manifest = AppJson.decodeFromString<PluginManifest>(
                File(root, "manifest.json").readText(),
            )
            val rel = manifest.uiPanel?.trim().orEmpty()
            if (rel.isBlank()) return null
            val panelFile = File(root, rel).canonicalFile
            if (!isWithin(root, panelFile) || !panelFile.isFile) return null
            panelFile.readText()
        }.onFailure { error ->
            Logger.w(TAG, "No se pudo leer el panel de complementos: $pluginId — ${error.message}")
        }.getOrNull()
    }

    /**
     * v2.0: Leer tarjeta de herramientas enchufables HTML(Nombre de la herramienta → manifest.toolCards Declaración).
     *
     * [toolNameOrSkillId] Soporta dos formas:complemento skill id(`plugin_<len>_<id>_<tool>`,
     * ver [skillId])o nombre de herramienta simple(Recorrer todas las coincidencias de complementos habilitados).
     * El camino está verificado por fuera de límites canónico.;Instalación no confirmada/No habilitado/Sin declaración/Devuelve nulo si falta el archivo.
     */
    fun loadToolCardHtml(toolNameOrSkillId: String): String? {
        val key = toolNameOrSkillId.trim()
        if (key.isBlank()) return null
        val parsed = parsePluginToolId(key)
        val toolName = parsed?.second ?: key
        val candidates = if (parsed != null) {
            listOfNotNull(findPlugin(parsed.first))
        } else {
            cached.filter { it.installationConfirmed && it.enabled }
        }
        for (plugin in candidates) {
            if (!plugin.installationConfirmed || !plugin.enabled) continue
            val html = runCatching {
                val root = File(pluginsDir, plugin.id).canonicalFile
                val manifest = AppJson.decodeFromString<PluginManifest>(
                    File(root, "manifest.json").readText(),
                )
                val rel = manifest.toolCards[toolName]?.trim().orEmpty()
                if (rel.isBlank()) return@runCatching null
                val cardFile = File(root, rel).canonicalFile
                if (!isWithin(root, cardFile) || !cardFile.isFile) return@runCatching null
                cardFile.readText()
            }.onFailure { error ->
                Logger.w(TAG, "No se pudo leer la tarjeta de herramientas complementarias: ${plugin.id}/$toolName — ${error.message}")
            }.getOrNull()
            if (html != null) return html
        }
        return null
    }

    // ── Configuración del complemento API ──────────────────────────────────────────────────

    /**
     * Lea el valor del elemento de configuración del complemento.
     *
     * @param pluginId complemento id
     * @param key      Elementos de configuración key
     * @param defaultVal Valor predeterminado (de la declaración del manifiesto)
     * @return Valor configurado por el usuario o valor predeterminado
     */
    fun getPluginConfig(pluginId: String, key: String, defaultVal: JsonElement? = null): JsonElement? {
        val pluginConfigs = configStore.configs[pluginId] ?: return defaultVal
        return pluginConfigs[key] ?: defaultVal
    }

    /**
     * Guarde el valor del elemento de configuración del complemento.
     *
     * @param pluginId complemento id
     * @param key      Elementos de configuración key
     * @param value    Valor de configuración del usuario
     */
    suspend fun setPluginConfig(pluginId: String, key: String, value: JsonElement?) = withContext(Dispatchers.IO) {
        val current = configStore.configs.toMutableMap()
        val pluginConfigs = current[pluginId]?.toMutableMap() ?: mutableMapOf()
        if (value != null) {
            pluginConfigs[key] = value
        } else {
            pluginConfigs.remove(key)
        }
        current[pluginId] = pluginConfigs
        configStore = PluginConfigStore(current)
        PluginConfigStore.toFile(configStoreFile, configStore)
    }

    /**
     * Obtenga todos los elementos de configuración del complemento (incluidos los valores predeterminados).
     *
     * Se utiliza para el formulario de configuración de representación de la interfaz de usuario: recorra la lista de configuración en el manifiesto,
     * Llamado para cada clave [getPluginConfig] Obtenga el valor actual.
     */
    fun getPluginConfigsWithDefaults(manifest: PluginManifest): List<Pair<ConfigItem, JsonElement?>> {
        val configList = manifest.contributes?.configuration ?: return emptyList()
        return configList.map { item ->
            item to getPluginConfig(manifest.id, item.key, item.defaultVal)
        }
    }

    /**
     * Obtenga la configuración completa del complemento (incluido el esquema y los valores actuales) para que lo utilice el puente JS.
     *
     * Devuelve una cadena JSON, formato:{"key": value, ...}
     */
    fun getPluginConfigJson(manifest: PluginManifest): String {
        val configList = manifest.contributes?.configuration ?: return "{}"
        val obj = buildJsonObject {
            for (item in configList) {
                val value = getPluginConfig(manifest.id, item.key, item.defaultVal)
                if (value != null) put(item.key, value)
            }
        }
        return AppJson.encodeToString(JsonObject.serializer(), obj)
    }
}
