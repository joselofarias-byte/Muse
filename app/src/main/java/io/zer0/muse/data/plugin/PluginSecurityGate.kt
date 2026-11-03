package io.zer0.muse.data.plugin

import io.zer0.common.AppJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Los complementos externos instalan puertas de seguridad.
 *
 * Esta clase solo maneja lógica pura comprobable: los complementos externos deben revisarse utilizando niveles de confianza en el espacio aislado, listas blancas de capacidades,
 * Verifique la firma del editor y genere una vista previa de la instalación sin contenido fuente. El resumen de la vista previa se calculará nuevamente tras la confirmación.
 * Se utiliza para evitar que el contenido del paquete sea reemplazado después de la confirmación del usuario.
 *
 * El formato de firma es P-256 ECDSA (`SHA256withECDSA`). La firma anula el manifiesto canonicalizado (solo el valor de la firma
 * Déjelo en blanco y aún cubra el ID del emisor, la clave pública y el algoritmo) y todos los archivos cargables ordenados por ruta. clave pública en la lista
 * Sólo un reclamo de identidad; La raíz nativa de la confianza es [PluginTrustStore] Siempre que los emisores desconocidos deban someterse a una decisión explícita de confianza del usuario.
 */
object PluginSecurityGate {

    /**
     * Los complementos externos permiten colecciones declarativas de capacidades.
     *
     * v2.x(Lanzamiento Sandbox): ya no se restringe la lista de habilidades —— Se puede declarar cualquier nombre de capacidad.
     * Anteriormente limitado por lista blanca (resource.read / network / storage.* / clipboard.* / ...),
     * El autor del complemento afirma que no se puede instalar una capacidad que no figura en la lista, lo cual es demasiado conservador. Ahora cambiado a**Declaración abierta**:
     * La declaración es solo para la presentación de la interfaz de usuario (la vista previa de la instalación está detallada para que el usuario sepa qué requiere el complemento).
     * Ya no es una condición de rechazo duro.
     *
     * Restricciones duras preservadas (corrección/Lo fundamental en materia de seguridad no está dentro del alcance de este comunicado):
     *  - Los complementos externos deben estar protegidos con un nivel de confianza;
     *  - Verificación de firma del emisor (a prueba de manipulaciones)
     */
    val allowedCapabilities: Set<String> = emptySet()

    /**
     * Restricciones de formato de nombre de capacidad (solo verifica la legalidad de los caracteres, no limita capacidades específicas).
     */
    private val CAPABILITY_NAME_REGEX = Regex("^[a-z][a-z0-9._-]{0,63}$")

    /** Tipo de complemento de máscara de interfaz de usuario declarativa;Este tipo de complemento está permitido sin herramientas y no debe llevar JS ejecutable. */
    const val UI_SKIN_KIND = "ui-skin"

    /** ui-skin Capacidades que los complementos deben declarar,Se utiliza para limitarlo a distribuir solo recursos de la piel. */
    const val UI_SKIN_CAPABILITY = "ui.skin"

    /** El único nivel de confianza permitido para complementos externos. */
    const val EXTERNAL_TRUST = "sandboxed"

    /** Algoritmo de clave pública del emisor; clave pública codificada como X.509 SubjectPublicKeyInfo. */
    const val KEY_ALGORITHM = "EC"

    /** Algoritmo de firma del emisor; firma codificada como DER. */
    const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    /** Estado de verificación de firma. */
    @Serializable
    enum class SignatureStatus {
        /** La firma es válida y el ID del emisor está vinculado a la clave pública en la raíz de confianza local. */
        VALID_TRUSTED,

        /** La firma es válida, pero el usuario no ha agregado la clave pública del emisor a la raíz de confianza local. */
        VALID_UNTRUSTED,

        /** Los paquetes antiguos o maliciosos no tienen firmas de editores. */
        UNSIGNED,

        /** La firma, el manifiesto o el contenido del paquete no coinciden. */
        INVALID,

        /** Se utilizó un algoritmo de firma o un formato de clave pública no compatible. */
        UNSUPPORTED,
    }

    /** Resultado de la verificación de la firma del emisor; solo contiene la huella digital de la clave pública, no se retiene ninguna clave privada ni código fuente. */
    data class SignatureVerification(
        val status: SignatureStatus,
        val publisherId: String = "",
        val fingerprint: String = "",
        val reason: String? = null,
    ) {
        /** La firma es criptográficamente válida y permite a los usuarios tomar decisiones sobre si confiar en el emisor. */
        val isCryptographicallyValid: Boolean
            get() = status == SignatureStatus.VALID_TRUSTED || status == SignatureStatus.VALID_UNTRUSTED
    }

    /** Un resumen de las herramientas individuales que se muestra antes de la confirmación de la instalación. */
    data class ToolPreview(
        val name: String,
        val functionName: String,
        val description: String,
    )

    /**
     * Vista previa de la instalación del complemento externo.
     *
     * La vista previa solo guarda metadatos y resumen, no código fuente ejecutable;[contentSha256] Es el valor vinculante al confirmar.
     */
    data class InstallPreview(
        val id: String,
        val name: String,
        val version: String,
        val author: String,
        val description: String,
        val entry: String,
        /**
         * Tiempo de ejecución del complemento (`js` / `python`).
         *
         * Se muestra porque los dos modelos de confianza son diferentes:JS Al ejecutarse en el entorno limitado de WebView, la acción puente tiene una lista blanca;
         * Python Es un proceso hijo del intérprete incorporado. Tiene el mismo UID que la aplicación y puede leer y escribir datos de la aplicación.
         * Los usuarios tienen derecho a saber, en lugar de simplemente leer, "el código externo se ejecutará en una zona de pruebas restringida".
         */
        val runtime: String = "js",
        val trust: String,
        val capabilities: List<String>,
        val permissions: List<String>,
        val tools: List<ToolPreview>,
        val entrySha256: String,
        val contentSha256: String,
        val entryBytes: Int,
        val extraFileCount: Int,
        val totalCodeBytes: Int,
        val signatureStatus: SignatureStatus,
        val publisherId: String,
        val publisherKeyFingerprint: String,
        val requiresPublisherTrust: Boolean,
    )

    /**
     * Resultados de la revisión de seguridad.
     *
     * [allowed] Indica que el paquete ha pasado las verificaciones de estructura, capacidad y firma criptográfica y puede ingresar a la vista previa de confirmación de instalación;
     * [requiresPublisherTrust] Indica que la firma es válida pero el emisor aún no es de confianza y el botón de confirmación debe ejecutarse una vez
     * claro“Confía en el editor e instala”, no puede unirse silenciosamente a la raíz de la confianza. Las condiciones para una verdadera instalabilidad y ejecución están dadas por [isInstallable]
     * expresado. Los paquetes que no estén firmados, hayan sido manipulados o no admitan el algoritmo no entrarán en el proceso de confirmación.
     */
    data class Decision(
        val allowed: Boolean,
        val requiresConfirmation: Boolean,
        val reason: String?,
        val preview: InstallPreview,
        val signature: SignatureVerification,
        val requiresPublisherTrust: Boolean,
    ) {
        /** Los complementos solo se pueden enviar como ejecutables si la firma es válida y el editor está vinculado a la raíz de confianza nativa. */
        val isInstallable: Boolean
            get() = allowed && signature.status == SignatureStatus.VALID_TRUSTED && !requiresPublisherTrust
    }

    /**
     * Revise los paquetes de complementos que hayan pasado la verificación de la estructura ZIP y genere una vista previa de la instalación.
     *
     * [trustedPublisherKeys] Solo debe provenir de la raíz de confianza privada de Android, la clave es el ID del emisor y el valor es X.509
     * Base64 estándar para claves públicas. Los editores desconocidos con firmas válidas aún pueden mostrar vistas previas, pero el usuario debe tomar una decisión de confianza explícita.
     */
    fun review(pluginPackage: PluginPackageLoader.LoadedPluginPackage, trustedPublisherKeys: Map<String, String> = emptyMap()): Decision {
        val signature = verifySignature(pluginPackage, trustedPublisherKeys)
        val preview = summarize(pluginPackage, signature)
        val policyReason = rejectionReason(pluginPackage.manifest)
        // Pueden entrar paquetes sin firmar“borrador de cuarentena/Vista previa”El proceso facilita que la IA revise el complemento primero después de generarlo; pero
        // isInstallable seguirá siendo falso y solo se podrán ejecutar firmas válidas en las que confíe la raíz de confianza local.
        // Las firmas manipuladas, los formatos incorrectos y los algoritmos no compatibles siguen estando cerrados en la vista previa.
        val signatureReason = when {
            signature.status == SignatureStatus.UNSIGNED -> null
            signature.isCryptographicallyValid -> null
            else -> signature.reason
        }
        val reason = policyReason ?: signatureReason
        // La firma del emisor desconocido es criptográficamente válida y puede mostrarse a los usuarios para que tomen decisiones de confianza;
        // Ninguna firma solo se puede utilizar como borrador aislado y no se puede confirmar directamente su habilitación.
        val allowed = reason == null
        val requiresPublisherTrust = allowed && signature.status == SignatureStatus.VALID_UNTRUSTED
        return Decision(
            allowed = allowed,
            // Cualquier código externo del que se pueda obtener una vista previa debe tener una confirmación explícita del usuario; Los editores desconocidos también deben unirse explícitamente a la raíz de confianza.
            requiresConfirmation = allowed,
            reason = reason,
            preview = preview.copy(requiresPublisherTrust = requiresPublisherTrust),
            signature = signature,
            requiresPublisherTrust = requiresPublisherTrust,
        )
    }

    /**
     * Verifique las firmas de los emisores y los enlaces de confianza nativos.
     *
     * La verificación solo utiliza en memoria [pluginPackage], la persona que llama debe recargar el paquete durante las fases de vista previa y confirmación respectivamente.
     */
    fun verifySignature(
        pluginPackage: PluginPackageLoader.LoadedPluginPackage,
        trustedPublisherKeys: Map<String, String> = emptyMap(),
    ): SignatureVerification {
        val envelope = pluginPackage.manifest.signature
            ?: return SignatureVerification(
                status = SignatureStatus.UNSIGNED,
                reason = "Al complemento externo le falta la firma del editor",
            )
        val publisherId = envelope.publisherId
        if (publisherId != publisherId.trim() || !isValidPublisherId(publisherId)) {
            return SignatureVerification(
                status = SignatureStatus.INVALID,
                publisherId = publisherId.trim(),
                reason = "ID de emisor ilegal",
            )
        }
        if (envelope.algorithm != SIGNATURE_ALGORITHM) {
            return SignatureVerification(
                status = SignatureStatus.UNSUPPORTED,
                publisherId = publisherId,
                reason = "Algoritmo de firma de emisor no admitido",
            )
        }

        val publicKeyBytes = publicKeyBytes(envelope.publicKey).getOrElse { error ->
            return SignatureVerification(
                status = SignatureStatus.UNSUPPORTED,
                publisherId = publisherId,
                reason = "El formato de la clave pública del emisor es ilegal.: ${error.message}",
            )
        }
        val canonicalPublicKey = Base64.getEncoder().encodeToString(publicKeyBytes)
        if (envelope.publicKey != canonicalPublicKey) {
            return SignatureVerification(
                status = SignatureStatus.INVALID,
                publisherId = publisherId,
                fingerprint = publicKeyFingerprint(publicKeyBytes),
                reason = "La codificación de la clave pública del emisor no es estándar Base64",
            )
        }
        val signatureBytes = decodeBase64(envelope.signature, MAX_SIGNATURE_BYTES).getOrElse { error ->
            return SignatureVerification(
                status = SignatureStatus.INVALID,
                publisherId = publisherId,
                fingerprint = publicKeyFingerprint(publicKeyBytes),
                reason = "El formato de firma del emisor es ilegal: ${error.message}",
            )
        }
        val canonicalSignature = Base64.getEncoder().encodeToString(signatureBytes)
        if (envelope.signature != canonicalSignature) {
            return SignatureVerification(
                status = SignatureStatus.INVALID,
                publisherId = publisherId,
                fingerprint = publicKeyFingerprint(publicKeyBytes),
                reason = "La codificación de la firma del emisor no es estándar Base64",
            )
        }

        val key = publicKey(publicKeyBytes).getOrElse { error ->
            return SignatureVerification(
                status = SignatureStatus.UNSUPPORTED,
                publisherId = publisherId,
                fingerprint = publicKeyFingerprint(publicKeyBytes),
                reason = "La clave pública del emisor no es compatible: ${error.message}",
            )
        }
        val fingerprint = publicKeyFingerprint(publicKeyBytes)
        val valid = runCatching {
            Signature.getInstance(SIGNATURE_ALGORITHM).apply {
                initVerify(key)
                update(signaturePayload(pluginPackage))
            }.verify(signatureBytes)
        }.getOrElse {
            return SignatureVerification(
                status = SignatureStatus.UNSUPPORTED,
                publisherId = publisherId,
                fingerprint = fingerprint,
                reason = "No se puede verificar la firma del editor: ${it.message}",
            )
        }
        if (!valid) {
            return SignatureVerification(
                status = SignatureStatus.INVALID,
                publisherId = publisherId,
                fingerprint = fingerprint,
                reason = "La firma del editor no coincide con el contenido del complemento",
            )
        }

        val trusted = trustedPublisherKeys[publisherId]
            ?.let { trustedKey ->
                publicKeyBytes(trustedKey).getOrNull()?.contentEquals(publicKeyBytes) == true
            } == true
        return SignatureVerification(
            status = if (trusted) SignatureStatus.VALID_TRUSTED else SignatureStatus.VALID_UNTRUSTED,
            publisherId = publisherId,
            fingerprint = fingerprint,
            reason = if (trusted) null else "La raíz de confianza local no confía en el editor",
        )
    }

    /**
     * Revise el manifiesto enviado.
     *
     * La confirmación de la instalación no solo verifica el resumen de contenido, sino que también debe volver a aplicar la misma capacidad./Reglas de nomenclatura; la verificación de firma requiere el paquete completo,
     * Por lo tanto, utilizado por la persona que llama. [verifySignature] o [review] Revisar el contenido del paquete.
     */
    fun rejectionReason(manifest: PluginManifest): String? {
        if (manifest.id.isBlank() || !PLUGIN_ID_REGEX.matches(manifest.id)) {
            return "La identificación del complemento es ilegal"
        }
        val isUiSkin = manifest.kind == UI_SKIN_KIND
        if (isUiSkin) {
            // Paquete de aspectos declarativo:permitido no tools,Pero se debe declarar la capacidad ui.skin,y no se les permite llevar herramientas ejecutables/JS.
            if (manifest.tools.isNotEmpty()) {
                return "ui-skin Los complementos no deben declarar herramientas ejecutables"
            }
            if (UI_SKIN_CAPABILITY !in manifest.capabilities) {
                return "ui-skin Los complementos deben declarar $UI_SKIN_CAPABILITY Habilidad"
            }
        } else {
            // v2.7: La extensión de entrada está determinada por manifest.runtime ——
            //   python El tiempo de ejecución requiere .py; el resto (js predeterminado) requiere .js.
            // Todavía aplica rutas relativas seguras (prevención de fugas de rutas).
            val expectedExt = if (manifest.runtime.equals("python", ignoreCase = true)) ".py" else ".js"
            if (!isSafeRelativePath(manifest.entry) || !manifest.entry.lowercase().endsWith(expectedExt)) {
                return "La ruta del archivo de entrada del complemento es ilegal"
            }
            if (manifest.tools.isEmpty()) {
                return "El complemento no declara ninguna herramienta."
            }
        }
        if (manifest.trust != EXTERNAL_TRUST) {
            return "Se deben utilizar complementos externos. $EXTERNAL_TRUST nivel de confianza"
        }
        // v2.x(Versión Sandbox): las habilidades ya no se rechazan según una lista fija, solo se verifica que el formato del nombre sea legal.
        // La declaración se utiliza para la visualización de vista previa de la instalación y ya no se utiliza como condición de rechazo firme.
        val invalidCapability = (manifest.capabilities + manifest.permissions)
            .firstOrNull { !CAPABILITY_NAME_REGEX.matches(it) }
        if (invalidCapability != null) {
            return "El formato del nombre de la capacidad declarado por el complemento es ilegal: $invalidCapability"
        }
        val invalidTool = manifest.tools.firstOrNull { tool ->
            !TOOL_NAME_REGEX.matches(tool.name) || !FUNCTION_NAME_REGEX.matches(tool.functionName)
        }
        return invalidTool?.let {
            "El nombre de la herramienta del complemento o el nombre de la función es ilegal: ${it.name}/${it.functionName}"
        }
    }

    /** Genere una vista previa de la instalación a partir de paquetes cargados. */
    fun summarize(
        pluginPackage: PluginPackageLoader.LoadedPluginPackage,
        signature: SignatureVerification = verifySignature(pluginPackage),
    ): InstallPreview {
        val entryBytes = pluginPackage.entryCode.toByteArray(Charsets.UTF_8)
        val extraBytes = pluginPackage.extraFiles.values.sumOf { it.toByteArray(Charsets.UTF_8).size }
        return InstallPreview(
            id = pluginPackage.manifest.id,
            name = pluginPackage.manifest.name,
            version = pluginPackage.manifest.version,
            author = pluginPackage.manifest.author,
            description = pluginPackage.manifest.description,
            entry = pluginPackage.manifest.entry,
            trust = pluginPackage.manifest.trust,
            capabilities = pluginPackage.manifest.capabilities,
            permissions = pluginPackage.manifest.permissions,
            tools = pluginPackage.manifest.tools.map { tool ->
                ToolPreview(
                    name = tool.name,
                    functionName = tool.functionName,
                    description = tool.description,
                )
            },
            entrySha256 = sha256(entryBytes),
            runtime = pluginPackage.manifest.runtime.ifBlank { "js" },
            contentSha256 = contentSha256(pluginPackage),
            entryBytes = entryBytes.size,
            extraFileCount = pluginPackage.extraFiles.size,
            totalCodeBytes = entryBytes.size + extraBytes,
            signatureStatus = signature.status,
            publisherId = signature.publisherId,
            publisherKeyFingerprint = signature.fingerprint,
            requiresPublisherTrust = signature.status == SignatureStatus.VALID_UNTRUSTED,
        )
    }

    /** Compare las dos vistas previas para ver si todavía están vinculadas al mismo contenido del complemento y a la misma identidad del editor. */
    fun hasSameInstallIdentity(expected: InstallPreview, actual: InstallPreview): Boolean =
        hasSameContentIdentity(expected, actual) && expected.signatureStatus == actual.signatureStatus

    /**
     * Compare las identidades del contenido vinculado en vista previa, pero ignore el estado de confianza.
     *
     * El usuario lo deja claro en el cuadro de diálogo de confirmación.“Confía en el editor”Después de eso, el estado de la firma cambiará de VALID_UNTRUSTED a
     * VALID_TRUSTED; La confirmación aún debe estar vinculada al mismo contenido y a la misma clave pública, y no puede juzgarse erróneamente debido a cambios de estado.
     * TOCTOU. Este método no ignora el ID del editor/la huella digital, solo el estado de confianza nativo.
     */
    fun hasSameContentIdentity(expected: InstallPreview, actual: InstallPreview): Boolean = expected.id == actual.id &&
        expected.entry == actual.entry &&
        expected.entrySha256 == actual.entrySha256 &&
        expected.contentSha256 == actual.contentSha256 &&
        expected.publisherId == actual.publisherId &&
        expected.publisherKeyFingerprint == actual.publisherKeyFingerprint

    /** Calcule el resumen del código de entrada para la visualización de la interfaz de usuario y la afirmación de prueba única. */
    fun entrySha256(entryCode: String): String = sha256(entryCode.toByteArray(Charsets.UTF_8))

    /**
     * v2.x(Proyecto de deuda técnica C): participe en la lista blanca de campos de nivel superior del manifiesto para cargas útiles firmadas.
     *
     * Reglas de mantenimiento:
     *  - Valor predeterminado del campo de manifiesto agregado**no te unas**esta colección → No participar en la firma → La verificación de paquetes existentes no se verá afectada;
     *  - Solo agregue nuevos campos si deben estar protegidos por su integridad, y luego ambos:
     *    Actualice la lista blanca de herramientas/market-signer/sign.py + vuelva a ejecutar MarketSamplePackageTest;
     *  - Este conjunto debe ser estrictamente coherente con el conjunto de campos existente del PluginManifest (demonio de prueba único:
     *    SignedManifestFieldCoverageTest), el campo cambia de nombre/Mantenimiento sincronizado al eliminarlo.
     */
    internal val SIGNED_TOP_LEVEL_FIELDS: Set<String> = setOf(
        "id", "name", "version", "description", "author", "minAppVersion",
        "entry", "kind", "trust", "hidden", "capabilities", "permissions",
        "activationEvents", "hooks", "enabled", "tools", "signature",
        "contributes", "uiPanel", "toolCards",
    )

    /**
     * manifest → Firmar usando JSON: después de la serialización completa, se eliminarán los campos de nivel superior que no estén en la lista blanca.
     *
     * El serializador Kotlinx genera el orden de los campos en el orden de declaración (JsonObject Conservar orden), eliminar sin cambiar el orden,
     * Para todos los paquetes existentes (todos los campos están en la lista blanca), la salida es consistente con encodeToString directo byte a byte.
     */
    internal fun signedManifestJson(manifest: PluginManifest): String {
        val full = AppJson.parseToJsonElement(
            AppJson.encodeToString(PluginManifest.serializer(), manifest),
        ).jsonObject
        val filtered = buildJsonObject {
            full.forEach { (key, value) ->
                if (key in SIGNED_TOP_LEVEL_FIELDS) put(key, value)
            }
        }
        return filtered.toString()
    }

    /**
     * Generar entrada de firma del emisor.
     *
     * El sobre de firma conserva el ID del editor, la clave pública y el algoritmo, pero deja el campo de firma en blanco para evitar la autorreferencia;
     * Luego agregue el manifiesto, la entrada y los archivos adicionales ordenados por ruta, todos con límites de ruta y longitud.
     * Los autores de paquetes pueden utilizar este método para generar firmas y las aplicaciones solo aceptan [SIGNATURE_ALGORITHM].
     *
     * Contrato entre terminales:manifest La secuencia de bytes JSON debe coincidir `tools/market-signer/sign.py` Acuerdo literal.
     *
     * v2.x(Proyecto de deuda técnica C) Lista blanca de carga útil de firmas:manifest Los campos de nivel superior que participan en la firma se componen de
     * [SIGNED_TOP_LEVEL_FIELDS] declaración explícita——Campo de manifiesto agregado**No participa en la firma por defecto.**,
     * La carga útil del paquete existente no se ve afectada; Si el nuevo campo realmente necesita protección de firma, el nombre del campo debe agregarse conscientemente.
     * Incluya en la lista blanca y sincronice las actualizaciones de sign.py con las pruebas de fijación del mercado. Esto reemplaza el viejo“Cada campo agregado es obligatorio
     * pegar @EncodeDefault(NEVER) De lo contrario, la verificación del paquete de inventario falla.”defensa humana.
     */
    fun signaturePayload(pluginPackage: PluginPackageLoader.LoadedPluginPackage): ByteArray {
        val manifestForSignature = pluginPackage.manifest.signature?.let { envelope ->
            pluginPackage.manifest.copy(signature = envelope.copy(signature = ""))
        } ?: pluginPackage.manifest
        val parts = buildList {
            add(
                "manifest.json" to signedManifestJson(manifestForSignature).toByteArray(Charsets.UTF_8),
            )
            add(pluginPackage.manifest.entry to pluginPackage.entryCode.toByteArray(Charsets.UTF_8))
            pluginPackage.extraFiles.toSortedMap().forEach { (path, content) ->
                add(path to content.toByteArray(Charsets.UTF_8))
            }
        }
        return parts.fold(ByteArray(0)) { current, (path, bytes) ->
            current + signatureHashPart(path, bytes)
        }
    }

    /** Computar resumen de contenido del complemento; El sobre de firma participa en la vinculación de integridad como parte del contenido del manifiesto. */
    fun contentSha256(pluginPackage: PluginPackageLoader.LoadedPluginPackage): String {
        val digest = MessageDigest.getInstance("SHA-256")
        appendHashPart(
            digest,
            "manifest.json",
            signedManifestJson(pluginPackage.manifest).toByteArray(),
        )
        appendHashPart(
            digest,
            pluginPackage.manifest.entry,
            pluginPackage.entryCode.toByteArray(Charsets.UTF_8),
        )
        pluginPackage.extraFiles.toSortedMap().forEach { (path, content) ->
            appendHashPart(digest, path, content.toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    /** Normalice las claves públicas X.509 al estándar Base64 sin nuevas líneas para la persistencia de la raíz de confianza. */
    internal fun canonicalPublicKey(encoded: String): Result<String> =
        publicKeyBytes(encoded).map { Base64.getEncoder().encodeToString(it) }

    /** Calcula la huella digital SHA-256 de la clave pública X.509. */
    internal fun publicKeyFingerprint(encoded: ByteArray): String = sha256(encoded)

    /** Verifique el ID del emisor para evitar rutas, espacios en blanco o identificadores demasiado largos en la raíz de confianza. */
    internal fun isValidPublisherId(publisherId: String): Boolean = PUBLISHER_ID_REGEX.matches(publisherId)

    /**
     * v2.x(Versión Sandbox): Verifique el formato del nombre de la habilidad (no es necesario que el nombre de la habilidad específica esté en la lista blanca).
     * La declaración de capacidad ahora solo se usa para la visualización de vista previa de la instalación y ya no es una condición de rechazo estricto.
     */
    fun isValidCapabilityName(name: String): Boolean = CAPABILITY_NAME_REGEX.matches(name)

    private fun publicKeyBytes(encoded: String): Result<ByteArray> = decodeBase64(encoded, MAX_PUBLIC_KEY_BYTES).map { bytes ->
        require(bytes.isNotEmpty()) { "La clave pública está vacía." }
        bytes
    }

    private fun publicKey(encoded: ByteArray): Result<PublicKey> = runCatching {
        val key = KeyFactory.getInstance(KEY_ALGORITHM)
            .generatePublic(X509EncodedKeySpec(encoded))
        val ecKey = key as? ECPublicKey ?: error("La clave pública no es una clave pública EC")
        require(ecKey.params.curve.field.fieldSize == P256_FIELD_SIZE) { "Solo admite claves públicas P-256" }
        key
    }

    private fun decodeBase64(encoded: String, maxBytes: Int): Result<ByteArray> = runCatching {
        require(encoded.isNotBlank()) { "Base64 El valor está vacío." }
        require(encoded.length <= maxBytes * 2) { "Base64 El valor es demasiado grande." }
        Base64.getDecoder().decode(encoded).also { bytes ->
            require(bytes.size <= maxBytes) { "Base64 El resultado de la decodificación es demasiado grande" }
        }
    }

    private val PLUGIN_ID_REGEX = Regex("^[a-z0-9][a-z0-9_-]*$")
    private val PUBLISHER_ID_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    private val TOOL_NAME_REGEX = Regex("^[a-zA-Z0-9_-]{1,64}$")
    private val FUNCTION_NAME_REGEX = Regex("^[a-zA-Z_$][a-zA-Z0-9_$]{0,63}$")

    private const val P256_FIELD_SIZE = 256
    private const val MAX_PUBLIC_KEY_BYTES = 512
    private const val MAX_SIGNATURE_BYTES = 256

    private fun isSafeRelativePath(path: String): Boolean {
        val normalized = path.trimEnd('/')
        return normalized.isNotBlank() &&
            !normalized.startsWith("/") &&
            !normalized.startsWith("\\") &&
            !normalized.contains("..") &&
            !normalized.contains('\\') &&
            normalized.split('/').none { it.isBlank() || it == "." }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun appendHashPart(digest: MessageDigest, path: String, bytes: ByteArray) {
        digest.update(path.toByteArray())
        digest.update(0.toByte())
        digest.update(bytes.size.toString().toByteArray())
        digest.update(0.toByte())
        digest.update(bytes)
        digest.update(0.toByte())
    }

    private fun signatureHashPart(path: String, bytes: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        appendHashPart(digest, path, bytes)
        return digest.digest()
    }
}
